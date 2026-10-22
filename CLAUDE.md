# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Phone Whisper is a single-module Android app (Kotlin, package `com.kafkasl.phonewhisper`, minSdk 30, targetSdk 36, compileSdk 37, JDK 17, arm64-v8a only; AGP 9 with its built-in Kotlin, Gradle 9). It shows a floating push-to-talk overlay button, records audio, transcribes it locally (sherpa-onnx) or in the cloud (OpenAI or OpenRouter), optionally cleans up the text with a chat model (OpenAI or OpenRouter), and inserts the result into the focused text field of whatever app is in the foreground.

## Commands

```bash
make jni-libs      # (re-)download the committed sherpa-onnx native libs if missing (pinned version + SHA-256)
make build         # ./gradlew assembleDebug → app/build/outputs/apk/debug/app-debug.apk
make test          # ./gradlew testDebugUnitTest
make integration-test MODELS=/dir WAV=/file.wav   # run every catalog model through the real JNI (Linux x64)
make adb-install   # build + adb install -r
make install       # build + scp the APK to a phone running Termux over SSH (PHONE_HOST/SSH_PORT from .env)
make push-model MODEL=/path/to/model-dir   # copy a local sherpa-onnx model into the app's files/models/ via adb run-as
make clean
```

Run a single test class or method:

```bash
./gradlew testDebugUnitTest --tests 'com.kafkasl.phonewhisper.PostProcessorTest'
./gradlew testDebugUnitTest --tests 'com.kafkasl.phonewhisper.TranscriberClientTest.parses success response'
```

On macOS the Makefile defaults `JAVA_HOME`/`ANDROID_HOME` to the Homebrew/Android Studio paths. Elsewhere, set them yourself or put `sdk.dir` in `local.properties`.

There is no lint or format configuration. Unit tests are plain JVM JUnit 4 tests (no Robolectric or instrumentation). `org.json` is added as a test dependency so the `parseResponse` functions work off-device. `isReturnDefaultValues` makes `android.util.Log` a no-op in tests. `CloudProviderTest` has a map-backed `FakePrefs` for testing pref-driven code.

`LocalTranscriberIntegrationTest` is skipped unless `SHERPA_JNI_DIR`, `PHONE_WHISPER_MODELS_DIR` and `PHONE_WHISPER_TEST_WAV` are set. `make integration-test` downloads the desktop JNI build of the pinned sherpa-onnx version into `build/` and sets them. `MODELS` needs one sub-dir per catalog `archive` name, and `WAV` must say "Ask not what your country can do for you..." (the `test_wavs/en.wav` of the Parakeet 0.6B archive). Run it after changing `LocalTranscriber`, the catalog, or the sherpa-onnx version: JVM unit tests can't catch a JNI mismatch.

CI (`.github/workflows/ci.yml`) runs the unit tests and builds the debug APK on every push and PR. `release.yml` builds a signed release APK for `v*` tags (signing secrets are described in the workflow).

## Architecture

There are only two Android components (see `AndroidManifest.xml`). They share state through `SharedPreferences("phonewhisper")`.

- **`MainActivity`** is the settings/setup screen. The whole UI is built programmatically in Kotlin; there are no layout XML files. It handles permissions, the accessibility-service link, the local/cloud toggle, provider/model choice, the API keys, model download/select/delete, and post-processing prompt presets. It writes prefs and calls `WhisperAccessibilityService.instance?.reloadModel()` when the selected model, the thread count or the local/cloud mode changes. API keys are entered in a masked, `FLAG_SECURE` dialog that never shows the saved key.
- **`WhisperAccessibilityService`** is where the app actually runs. On `onServiceConnected` it adds a `TYPE_ACCESSIBILITY_OVERLAY` button and loads the local model. Loading runs on a single-thread `modelExecutor`; the old model is released before the new one loads, and `transcriberLock` keeps a model from being released mid-transcription. The overlay remembers its edge and relative height and is re-placed in `onConfigurationChanged`. A small state machine (`IDLE → RECORDING → TRANSCRIBING`) drives the pipeline:
  1. `AudioRecord` captures 16 kHz mono PCM16 into a `ByteArrayOutputStream` on a background thread. If the microphone is busy, a message is shown instead.
  2. Local mode (`use_local` pref): PCM is converted to floats and passed to `LocalTranscriber`. Local mode **never** falls back to the cloud: if the model is still loading or missing, the user gets a message. Cloud mode: `WavWriter.encode` wraps the PCM, and `TranscriberClient` sends it to the provider from `transcriptionTarget()`. OpenAI gets multipart. OpenRouter gets JSON with base64 audio under `input_audio`, which is its documented main format.
  3. If `use_post_processing` is set: `PostProcessor` sends the text to the chat-completions endpoint of the provider from `postProcessingTarget()` with the selected prompt and temperature 0. If a model rejects the temperature, the request is repeated without it. Both providers share the OpenAI request/response format. If that call fails, the raw text is used.
  4. `injectText` always copies the text to the clipboard first. It then looks only at the focused text node(s) of the active window (scored), and tries a custom paste action, `ACTION_PASTE`, and finally `ACTION_SET_TEXT`. It never targets unfocused fields.

  If transcription fails, the recording is kept in `failedPcm`, and a long press on the button retries it. Callbacks come back on OkHttp or worker threads. UI and state changes are posted to the main `handler`. All HTTP goes through `Http.client` (longer timeouts than OkHttp's 10 s defaults).

Prefs keys: `use_local`, `model_name`, `num_threads`, `use_post_processing`, `post_processing_prompt`, `custom_post_processing_prompt`, plus the cloud settings `api_key` (OpenAI), `openrouter_api_key`, `transcription_provider` / `post_processing_provider` (`openai`|`openrouter`), and the per-provider models `openai_transcription_model`, `openai_post_processing_model`, `openrouter_transcription_model`, `openrouter_post_processing_model`.

### Cloud providers

`CloudProvider.kt` defines `CloudTask` (`TRANSCRIPTION`, `POST_PROCESSING`). For each task and provider it holds the pref key, the default model and the presets. `SharedPreferences.cloudTarget(task)` (and the `transcriptionTarget()`/`postProcessingTarget()` shorthands) turns prefs into a `CloudTarget(provider, apiKey, model)`. Both the service and `MainActivity` use them. The defaults are OpenAI `gpt-4o-mini-transcribe`/`gpt-4o-mini` and OpenRouter `openai/whisper-large-v3`/`openai/gpt-4o-mini`. The model picker loads the provider's live list, filtered to the task: OpenRouter's public `/models` by output modality (`OpenRouter.listModels`), or OpenAI's `/v1/models` with the API key, filtered by ID (`OpenAiModels`). If that fails it falls back to the presets plus free-text input.

### Local models

- `MODEL_CATALOG` (in `ModelDownloader.kt`) lists the models. For most entries, the `archive` name is the base name of a `.tar.bz2` file in the sherpa-onnx GitHub `asr-models` release. Entries with `files` (currently Parakeet Ultra from Hugging Face) are instead fetched file by file from `baseUrl` (pin it to a commit), and each file's SHA-256 is checked. Both kinds are assembled in `files/model-downloads/<archive>/` and renamed to `files/models/<archive>/` with an `.installed` marker only when complete. `extractTarBz2` guards against path traversal. `isInstalled` also accepts older installs without the marker if their model files are detectable.
- `ModelDownloader` keeps running downloads in a map: `state()`, `addListener()`/`removeListener()`. A download survives the activity, and a second one of the same model is refused. `delete()` removes an installed model.
- `LocalTranscriber.detectModelConfig(dir, numThreads)` works out the model type from the files in the directory: `preprocess.onnx` means Moonshine; encoder+decoder without a joiner means Whisper; encoder+decoder+joiner means a NeMo transducer/Parakeet; a single `model*.onnx` means NeMo CTC. File names may carry a prefix (`base.en-encoder.int8.onnx`, `base.en-tokens.txt` in the Whisper archives). It prefers `int8` files. To add a new model family, you need a new branch here. The thread count comes from the `num_threads` pref (default `LocalTranscriber.DEFAULT_THREADS`, min(4, cores)).

### sherpa-onnx bindings

`app/src/main/kotlin/com/k2fsa/sherpa/onnx/` holds Kotlin API files vendored from upstream sherpa-onnx; treat them as third-party code. They call `System.loadLibrary("sherpa-onnx-jni")`. The native `.so` files (`libsherpa-onnx-jni.so`, `libonnxruntime.so`) are committed in `app/src/main/jniLibs/arm64-v8a/`. `make jni-libs` re-downloads them from the sherpa-onnx Android release pinned in the Makefile (`SHERPA_VERSION`, verified against `SHERPA_SHA256`); it only runs when the files are missing. When bumping `SHERPA_VERSION`, update `SHERPA_SHA256`, delete the old `.so` files, run `make jni-libs`, and re-vendor the Kotlin API files from the same release (`sherpa-onnx/kotlin-api/` at the release tag), since the JNI signatures must match. Then run `make integration-test`.

## Other directories

- `docs/` is the static GitHub Pages site (landing page, privacy page, JS demo animations). It is not part of the app build.
- `PRIVACY.md` describes the data flows (local vs. direct to OpenAI/OpenRouter, model downloads). Keep it in sync if network behavior changes.
