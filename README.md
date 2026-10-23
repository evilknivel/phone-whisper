<p align="center">
  <img src="docs/logo.svg" width="128" height="128" alt="Phone Whisper Logo">
</p>

# Phone Whisper

Push-to-talk dictation for Android.

Phone Whisper lets you speak into most apps without switching keyboards. Tap the floating button, speak, tap again, and your text is inserted into the currently focused text field when the app exposes a standard Android input field.

This repository is a fork of [kafkasl/phone-whisper](https://github.com/kafkasl/phone-whisper) by Pol Alvarez. The [development history](#development-history) at the end shows what was added here.

## Features

**Transcription**
- **Local, on-device** with [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx): Parakeet (110M, 0.6B, Ultra), Whisper (Base, Small) and Moonshine. Audio never leaves the phone, and local mode never falls back to the cloud.
- **Cloud** with OpenAI, [OpenRouter](https://openrouter.ai) (any speech-to-text model there), or your own **OpenAI-compatible server** (Groq, a Whisper server on your computer). The model is selectable per provider, from the provider's live model list.
- **Languages**: Parakeet 0.6B and Ultra understand 25 European languages including German, Whisper 99 languages. A **Language** setting (default: detect automatically) fixes the language for Whisper and the cloud.
- **Silence trimming** with Silero VAD before transcribing: faster, cheaper, and no invented text for silent stretches. If no speech is detected, nothing is sent; tapping retry sends the untrimmed recording.

**Cleanup (optional)**
- A chat model (OpenAI, OpenRouter or your own server) fixes punctuation, grammar and obvious recognition errors. It keeps your language and never translates.
- Presets: *Simple cleanup* (default), *Dev cleanup* (turns "command mode …" into shell commands), or your own prompt.

**The button**
- Shows **only while a keyboard is open** (default) or always. It can be dragged to either edge and stays there across rotation.
- Level bars while recording, a spinner while transcribing, and a short vibration on start and stop.
- If something fails (no network, wrong key, no credit …), it turns **orange**: tap to retry the same recording, hold to discard it.
- Turning the screen off while recording cancels the recording.

**Inserting text**
- Only into the focused field, never into another field on the screen.
- By default the text is pasted via the clipboard, which works in the most apps. With **Keep my clipboard**, it is written straight into the field and your clipboard stays untouched. The clipboard is only used as a fallback then.
- Copied transcripts are marked sensitive, so Android hides them in the clipboard preview.

**Models and memory**
- Downloads run in the background with a progress notification, also with the screen off. Every file is checked against a pinned SHA-256.
- Models can be deleted. Files a model doesn't need are removed after installing.
- The model can be unloaded after a few idle minutes (default 5) to save memory. It loads again while you speak the next time.

## Install

### Download the APK

Grab the latest APK from [GitHub Releases](https://github.com/evilknivel/phone-whisper/releases), open it on your phone and install it.

Android blocks the accessibility permission for apps installed from a file ("restricted settings"). If enabling the service shows "App was denied access": open **Settings → Apps → Phone Whisper → ⋮ → Allow restricted settings**, then enable the service again.

### Build from source

Requires JDK 17 and an Android SDK with platform 37 (set `ANDROID_HOME` or `sdk.dir` in `local.properties`).

```bash
git clone https://github.com/evilknivel/phone-whisper.git && cd phone-whisper
make build
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. With a phone connected over ADB, `make adb-install` builds and installs it (no restricted-settings prompt this way).

## Setup

1. Open **Phone Whisper**
2. Grant the **audio recording** permission
3. Enable the **Accessibility Service**
4. Choose your transcription mode:
   - **Local**: download a model in the list and tap it to select it. For German, use Parakeet 0.6B or Ultra.
   - **Cloud**: switch on **Use cloud transcription**, pick a provider and model, and set its API key under **More settings → API keys**.

The **Status** line at the top shows what is still missing, for example "Setup required: accessibility service". When it says "Ready", open any app with a text field and tap the button.

## Settings

The main screen holds status, setup, transcription (mode, language, models or cloud provider) and cleanup. Everything else is under **More settings**:

| Page | Settings |
|---|---|
| **API keys** | OpenAI and OpenRouter keys; address and key of a custom server. Keys are entered in a masked field that never shows the saved key and can't be screenshotted. |
| **Button & insertion** | Show the button only with a keyboard or always; vibration; cancel on screen off; *Keep my clipboard* |
| **Advanced** | CPU threads for local models (default 4); free model memory when idle (never, 2–30 min, default 5); trim silence (default on); microphone source (speech recognition or standard microphone) |

## Local models

| Model | Size on device | Languages | Notes |
|---|---:|---|---|
| Parakeet 110M | 132 MB | English | Best value |
| Moonshine Tiny | 124 MB | English | Fastest |
| Whisper Base | 161 MB | 99 languages | Small, many languages |
| Whisper Small | 375 MB | 99 languages | More accurate Whisper |
| Parakeet 0.6B | 671 MB | 25 European languages | Best quality |
| Parakeet Ultra | 630 MB | 25 European languages | Most accurate |

Tap a model to download it, tap it again to select it, and use ✕ to delete it. Parakeet detects the spoken language itself; Whisper uses the **Language** setting. The English-only Whisper Base of earlier versions stays usable while installed but is no longer offered.

Most models are downloaded as individual files from Hugging Face (the sherpa-onnx mirrors and [mldecode/parakeet-ultra-onnx-int8](https://huggingface.co/mldecode/parakeet-ultra-onnx-int8)), pinned to a commit. Parakeet 110M comes from the sherpa-onnx release archive. Models live in app storage under `/data/data/com.kafkasl.phonewhisper/files/models/`.

## Cloud providers

| Provider | Transcription default | Cleanup default | Notes |
|---|---|---|---|
| OpenAI | `gpt-4o-mini-transcribe` | `gpt-4o-mini` | Model list loads with your API key |
| OpenRouter | `openai/whisper-large-v3` | `openai/gpt-4o-mini` | Public model list, filterable by typing |
| Custom | `whisper-1` | `gpt-4o-mini` | Any OpenAI-compatible server, e.g. `https://api.groq.com/openai/v1` or `http://192.168.1.10:8000`. Key optional; plain http allowed for your own network. |

Transcription and cleanup pick their provider separately. Models that reject `temperature` (OpenAI's reasoning models) are retried without it.

## Why does it need Accessibility?

Phone Whisper uses the Android Accessibility Service to insert dictated text into the focused text field across apps, and to see whether a keyboard is open so the button can be shown only then.

It does **not** replace your keyboard, does **not** run background automation, and does **not** log the contents of text fields. It only acts after you tap the button.

## Privacy

- **Local mode**: audio stays on the device, also when the model is missing or still loading.
- **Cloud mode**: audio goes directly from your device to the provider you pick, with your own API key. If no speech is detected, nothing is sent unless you tap retry.
- **Cleanup**: the transcript goes directly to the chat API of the provider you pick.

There is no backend. Full policy: [PRIVACY.md](PRIVACY.md)

## App compatibility

Phone Whisper works best in apps that use standard Android text fields. Some apps use custom text surfaces or block paste; then the transcript stays on the clipboard so you can paste it yourself.

**Termux:** the terminal area is not a standard text field. Swipe the extra keys row (`ESC`, `CTRL`, `ALT`, …) to switch to Termux's native text input box and dictate there.

**Xiaomi and other phones with aggressive battery saving:** if the status says the accessibility service is "on, but not running", turn it off and on again and allow **Autostart** and **no battery restrictions** for Phone Whisper.

## Development

```bash
make build             # debug APK
make test              # unit tests
make integration-test MODELS=/dir WAV=en.wav [WAV_DE=de.wav]   # every catalog model through the real sherpa-onnx JNI (Linux x64)
make adb-install       # build + install via ADB
make clean
```

CI runs the unit tests and builds the APK on every push. Pushing a tag like `v0.5.0` builds a release APK and attaches it to a GitHub release (pre-release for tags with a `-`). To sign releases with a fixed key, set the secrets described in `.github/workflows/release.yml`. [CLAUDE.md](CLAUDE.md) describes the architecture.

## Current limitations

- Accessibility permission is required for cross-app insertion
- Some apps block paste or text injection, or use custom input surfaces
- Good local models are large (130–670 MB)
- Cloud mode requires your own API key (or your own server)

## License

Apache License 2.0, see [LICENSE](LICENSE). If Phone Whisper saves you time, consider [sponsoring the original author](https://github.com/sponsors/kafkasl).

## Development history

**Original app (March–August 2026, Pol Alvarez)**
- MVP: floating overlay button, recording, OpenAI Whisper transcription and insertion through the accessibility service
- Local transcription with sherpa-onnx and a model downloader (Parakeet, Whisper, Moonshine)
- Material 3 settings screen, cleanup with OpenAI chat models and prompt presets, improved insertion fallback, launcher icon, GitHub Pages site, license

**This fork (October 2026, with Claude Code)**
1. **Foundations**: CLAUDE.md, the sherpa-onnx native libraries pinned and committed (`make jni-libs`)
2. **OpenRouter**: separate provider and model choice for transcription and cleanup, with the live model list
3. **Parakeet Ultra**: support for models published as loose files with SHA-256 checks; Ultra added and verified with real recordings
4. **Releases and CI**: release workflow on tags, CI on every push, signing via secrets
5. **Toolchain update**: AGP 9, Gradle 9, Kotlin 2.4, targetSdk 36, sherpa-onnx 1.13.8 (native libs and Kotlin API re-vendored)
6. **Review fixes**: local mode no longer sends audio to the cloud when the model isn't ready; old models are freed; a busy microphone no longer crashes the service; longer network timeouts; text only goes into the focused field; interrupted installs aren't treated as installed; models can be deleted; Whisper Base actually loads; OpenAI model choice; CPU threads setting; protected API key entry
7. **Setup status** that names what is missing and notices a service that is on but not running
8. **Features from the original repo's open pull requests**, reimplemented: button only with a keyboard, retry state with readable errors, level bars, cancel on screen off, inserting without the clipboard, background downloads, direct Hugging Face downloads, freeing the model when idle, custom OpenAI-compatible server. Plus silence trimming (VAD), language labels, pruning unused model files, a cleanup prompt that keeps the language, privacy fixes, vibration, microphone source, and settings split into sub-pages
9. **Languages**: language setting for Whisper and the cloud, multilingual Whisper Base and Whisper Small (Whisper had translated German into English with sherpa-onnx's default), complete endings for Whisper Base
