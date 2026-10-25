# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Phone Whisper is a single-module Android app (Kotlin, package `com.kafkasl.phonewhisper`, minSdk 30, targetSdk 36, compileSdk 37, JDK 17, arm64-v8a only; AGP 9 with its built-in Kotlin, Gradle 9). It shows a floating push-to-talk overlay button, records audio, trims silence (Silero VAD), transcribes it locally (sherpa-onnx: Parakeet, Whisper, Moonshine) or in the cloud (OpenAI, OpenRouter, or a custom OpenAI-compatible server), optionally cleans up the text with a chat model from one of those providers, and inserts the result into the focused text field of whatever app is in the foreground. This repo is a fork of kafkasl/phone-whisper; README.md ends with a short development history.

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

`LocalTranscriberIntegrationTest` is skipped unless `SHERPA_JNI_DIR`, `PHONE_WHISPER_MODELS_DIR` and `PHONE_WHISPER_TEST_WAV` are set. `make integration-test` downloads the desktop JNI build of the pinned sherpa-onnx version into `build/` and sets them. `MODELS` needs one sub-dir per catalog `archive` name, and `WAV` must say "Ask not what your country can do for you..." (the `test_wavs/en.wav` of the Parakeet 0.6B archive). Optional `WAV_DE` (its `test_wavs/de.wav`) checks that every multilingual model transcribes German as German. Run it after changing `LocalTranscriber`, the catalog, or the sherpa-onnx version: JVM unit tests can't catch a JNI mismatch.

CI (`.github/workflows/ci.yml`) runs the unit tests and builds the debug APK on every push and PR. `release.yml` builds a signed release APK for `v*` tags (signing secrets are described in the workflow).

## Architecture

The Android components (see `AndroidManifest.xml`) are the settings activities, the accessibility service and the download service. They share state through `SharedPreferences("phonewhisper")` (`Prefs.of(ctx)`).

- **Settings screens** are built programmatically in Kotlin; there are no layout XML files. They extend `SettingsScreen` (row helpers `settingsRow`/`switchRow`/`toggleRow`/`choiceRow`/`navRow`; rows that show a value register an updater with `onRefresh`, and `refresh()` runs them on resume and after each change). `MainActivity` has status, setup, transcription (cloud provider/model or the local model list) and AI cleanup; on first run (`setup_done` unset and not already configured) it starts `SetupWizardActivity`, which walks through microphone, offline/online, model or provider and key, cleanup mode and the accessibility service, one `setScreen` per step, writing the same prefs. `AccessibilityDisclosure` is the prominent disclosure Google Play requires before sending the user to the accessibility settings (consent stored in `accessibility_consent`); both the assistant and the main screen's accessibility row go through it. `ApiKeysActivity` holds the API keys (masked, `FLAG_SECURE` dialog that never shows the saved key) and the custom server address. `ButtonSettingsActivity` and `AdvancedSettingsActivity` hold the switchable options. The last main-screen row, About, shows the installed `versionName` and opens the repo. Their pref keys and defaults are in `Prefs.kt`. Screens call `WhisperAccessibilityService.instance?.reloadModel()` when the selected model, the thread count or the local/cloud mode changes; the service listens to pref changes for the overlay mode and idle timeout.
- **`DownloadService`** is a foreground service (`dataSync`, wake lock, progress notification) that keeps model downloads alive while the screen is off. `ModelDownloader` does the work.
- **`WhisperAccessibilityService`** is where the app actually runs. On `onServiceConnected` it adds a `TYPE_ACCESSIBILITY_OVERLAY` button and loads the local model. Loading, releasing and local transcription run on a single-thread `modelExecutor`; the old model is released before the new one loads. With `idle_release_minutes` set, the model is released after that much idle time and reloaded when recording starts; a transcription queued on the executor simply waits for the load. The overlay remembers its edge and relative height and is re-placed in `onConfigurationChanged`. In "keyboard" overlay mode it is hidden (`View.GONE`, not removed) unless a `TYPE_INPUT_METHOD` window exists or the state isn't `IDLE`; window events plus a 1 s watchdog while shown drive this, and it needs `flagRetrieveInteractiveWindows` in `accessibility_service_config.xml`. A state machine (`IDLE → RECORDING → TRANSCRIBING`, plus `RETRY`) drives the pipeline:
  1. `AudioRecord` (source `VOICE_RECOGNITION` unless the `mic_source` pref says `mic`) captures 16 kHz mono PCM16 into a `ByteArrayOutputStream` on a background thread, which also feeds `LevelBarsView`. If the microphone is busy, a message is shown instead. Screen off cancels the recording (pref).
  2. With `trim_silence`, `SilenceTrimmer` (Silero VAD, `assets/silero_vad.onnx`, copied to `files/vad/` on first use) cuts silence; no speech at all means nothing is transcribed. Local mode (`use_local` pref): the floats go to `LocalTranscriber`. Local mode **never** falls back to the cloud. Cloud mode: `WavWriter.encode` wraps the PCM, and `TranscriberClient` sends it to `transcriptionTarget()`. OpenAI and custom servers get multipart; OpenRouter gets JSON with base64 audio under `input_audio`, its documented main format.
  3. If this dictation is cleaned up (`cleanupThisDictation`: `cleanup_mode` "always", or "hold" and the recording was started by holding the button, which fires while the finger is still down and records in purple): `PostProcessor` sends the text to the chat-completions endpoint of the provider from `postProcessingTarget()` with the selected prompt and temperature 0. If a model rejects the temperature, the request is repeated without it. Both providers share the OpenAI request/response format. If that call fails, the raw text is used.
  4. `injectText` looks only at the focused text node(s) of the active window (scored) and never targets unfocused fields. With `preserve_clipboard`, it first writes the text with `ACTION_SET_TEXT` where `textAfterInsert` knows the caret (placeholders count as empty). Otherwise, or if that fails, it copies the text to the clipboard (marked sensitive) and tries a custom paste action, `ACTION_PASTE`, and `ACTION_SET_TEXT`. `logNode` logs field shape, never field content.

  If transcription fails, the recording is kept in `failedPcm` and the state is `RETRY`: the button turns orange, a tap retries without trimming, and the ✕ window next to it (or a long press) discards it. Because the thumb covers the button, feedback also goes around it: `HoldRingView` (a 128 dp ring in its own untouchable window that grows to a full circle while the button is held, in the color of what the hold does: purple for cleanup, orange for discard; the hold triggers after `HOLD_MS` = 800 ms), feedback messages positioned above the ring (`positionFeedback`), distinct `Haptic` patterns (click, double pulse for cleanup, long buzz for discard), and with the `edge_glow` pref `EdgeGlowView`, a full-screen untouchable window that glows red or purple along the edges while recording. `ErrorMessages` turns HTTP and exception text into short messages. Callbacks come back on OkHttp or worker threads. UI and state changes are posted to the main `handler`. All HTTP goes through `Http.client` (longer timeouts than OkHttp's 10 s defaults).

Prefs keys: `use_local`, `model_name`, `language` (ISO 639-1, "" = detect), `num_threads`, `idle_release_minutes`, `trim_silence`, `mic_source`, `overlay_mode`, `haptics`, `edge_glow`, `cancel_on_screen_off`, `preserve_clipboard`, `cleanup_mode` (`hold`|`always`|`never`; older installs had `use_post_processing`, read by `Prefs.cleanupMode()`), `setup_done`, `accessibility_consent`, `post_processing_prompt`, `custom_post_processing_prompt`, plus the cloud settings `api_key` (OpenAI), `openrouter_api_key`, `custom_api_key`, `custom_base_url`, `transcription_provider` / `post_processing_provider` (`openai`|`openrouter`|`custom`), and the per-provider models `<provider>_transcription_model` / `<provider>_post_processing_model`.

### Cloud providers

`CloudProvider.kt` defines `CloudTask` (`TRANSCRIPTION`, `POST_PROCESSING`). For each task and provider it holds the pref key, the default model and the presets. `SharedPreferences.cloudTarget(task)` (and the `transcriptionTarget()`/`postProcessingTarget()` shorthands) turns prefs into a `CloudTarget(provider, apiKey, model, baseUrl)`. `CUSTOM` is any OpenAI-compatible server: `normalizeBaseUrl` turns the entered address into a `/v1` base, the key is optional, and `usesCleartextTraffic` allows http for servers on the local network. Both the service and `MainActivity` use them. The defaults are OpenAI `gpt-4o-mini-transcribe`/`gpt-4o-mini` and OpenRouter `openai/whisper-large-v3`/`openai/gpt-4o-mini`. The model picker loads the provider's live list, filtered to the task: OpenRouter's public `/models` by output modality (`OpenRouter.listModels`), or `/models` of OpenAI (filtered by ID) or the custom server (unfiltered) via `OpenAiModels`. If that fails it falls back to the presets plus free-text input.

### Local models

- `MODEL_CATALOG` (in `ModelDownloader.kt`) lists the models with their installed size and `languages`. `archive` is the install dir name. Entries with `files` are fetched file by file from `baseUrl` (a Hugging Face `/resolve/<commit>` URL; only the int8 files and tokens) and each file's SHA-256 is checked: bz2 decoding on the phone is slow. Entries without `files` (Parakeet 110M, which has no complete mirror) download `<archive>.tar.bz2` from the sherpa-onnx `asr-models` release. `pruneUnusedFiles` deletes what the recognizer doesn't load (also in older installs, via `pruneInstalled`). Both kinds are assembled in `files/model-downloads/<archive>/` and renamed to `files/models/<archive>/` with an `.installed` marker only when complete. `extractTarBz2` guards against path traversal. `isInstalled` also accepts older installs without the marker if their model files are detectable.
- `ModelDownloader` keeps running downloads in a map: `state()`, `addListener()`/`removeListener()`. A download survives the activity, and a second one of the same model is refused. `delete()` removes an installed model.
- `LocalTranscriber.detectModelConfig(dir, numThreads)` works out the model type from the files in the directory: `preprocess.onnx` means Moonshine; encoder+decoder without a joiner means Whisper; encoder+decoder+joiner means a NeMo transducer/Parakeet; a single `model*.onnx` means NeMo CTC. File names may carry a prefix (`base.en-encoder.int8.onnx`, `base.en-tokens.txt` in the Whisper archives). It prefers `int8` files. To add a new model family, you need a new branch here. Whisper gets `language` from the `language` pref ("" = detect; sherpa-onnx's default "en" would make a multilingual Whisper translate into English), `task = "transcribe"`, and `tailPaddings = 2000` (with 1000, Whisper Base drops the last sentence). The service reloads the model when the language changes. Cloud requests send `language` when it is set. Catalog entries with `legacy = true` are not offered for download and only listed while installed. The thread count comes from the `num_threads` pref (default `LocalTranscriber.DEFAULT_THREADS`, min(4, cores)).

### sherpa-onnx bindings

`app/src/main/kotlin/com/k2fsa/sherpa/onnx/` holds Kotlin API files vendored from upstream sherpa-onnx; treat them as third-party code. They call `System.loadLibrary("sherpa-onnx-jni")`. The native `.so` files (`libsherpa-onnx-jni.so`, `libonnxruntime.so`) are committed in `app/src/main/jniLibs/arm64-v8a/`. `make jni-libs` re-downloads them from the sherpa-onnx Android release pinned in the Makefile (`SHERPA_VERSION`, verified against `SHERPA_SHA256`); it only runs when the files are missing. When bumping `SHERPA_VERSION`, update `SHERPA_SHA256`, delete the old `.so` files, run `make jni-libs`, and re-vendor the Kotlin API files from the same release (`sherpa-onnx/kotlin-api/` at the release tag), since the JNI signatures must match. Then run `make integration-test`.

## Documentation

README.md, PRIVACY.md and CONTRIBUTING.md are the English originals and always come first; README.de.md, PRIVACY.de.md and CONTRIBUTING.de.md are German translations with a language switch at the top. Update both languages when behavior changes, and add user-visible changes to CHANGELOG.md. The app's UI strings are still hard-coded in Kotlin; CONTRIBUTING.md asks for help moving them to `strings.xml` for translations.

## Other directories

- `docs/` is the static GitHub Pages site (landing page, privacy page, JS demo animations). It is not part of the app build.
- `PRIVACY.md` (and `PRIVACY.de.md`) describes the data flows (local vs. direct to OpenAI/OpenRouter/custom server, model downloads, clipboard). Keep it in sync if network behavior changes.
