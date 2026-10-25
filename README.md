<p align="center">
  <img src="docs/logo.svg" width="128" height="128" alt="Phone Whisper Logo">
</p>

# Phone Whisper

**English** | [Deutsch](README.de.md)

Push-to-talk dictation for Android.

Phone Whisper lets you speak into most apps without switching keyboards. Tap the floating button, speak, tap again, and your text is inserted into the currently focused text field when the app exposes a standard Android input field.

> **This is a fork.** Phone Whisper was created by **Pol Alvarez** ([@kafkasl](https://github.com/kafkasl)) at [kafkasl/phone-whisper](https://github.com/kafkasl/phone-whisper). The idea, the original app and its design are his. This fork adds cloud providers, more models, languages and many fixes; several features are our own implementations of ideas from open pull requests in the original repository. Everyone involved is named under [Credits](#credits), and the [development history](#development-history) at the end shows what was added here.

## Features

**Transcription**
- **Local, on-device** with [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx): Parakeet (110M, 0.6B, Ultra), Whisper (Base, Small) and Moonshine. Audio never leaves the phone, and local mode never falls back to the cloud.
- **Cloud** with OpenAI, [OpenRouter](https://openrouter.ai) (any speech-to-text model there), or your own **OpenAI-compatible server** (Groq, a Whisper server on your computer). The model is selectable per provider, from the provider's live model list.
- **Languages**: Parakeet 0.6B and Ultra understand 25 European languages including German, Whisper 99 languages. A **Your language** setting (default: detect automatically) fixes the spoken language for Whisper and the cloud.
- **Silence trimming** with Silero VAD before transcribing: faster, cheaper, and no invented text for silent stretches. If no speech is detected, nothing is sent; tapping retry sends the untrimmed recording.

**AI cleanup (optional)**
- A chat model (OpenAI, OpenRouter or your own server) fixes punctuation, grammar and obvious recognition errors. It keeps your language and never translates.
- **Hold the button** to dictate with cleanup, tap it for a plain dictation (default). Cleanup can also be set to *always* or *never*.
- Presets: *Simple cleanup* (default), *Dev cleanup* (turns "command mode …" into shell commands), or your own prompt.

**Translation (optional)**
- Speak in your language, insert the text in another one. Pick up to 6 target languages under **AI cleanup → Translation**.
- **Hold** the button until the ring is full: the languages appear on an arc around it. **Slide your thumb** onto one (it lights up, with a tick) and let go. The button shows the language code, e.g. **ES**, while you speak. Letting go near the button is a normal dictation with cleanup.
- Transcription always uses the model from your settings (Parakeet Ultra, say, or the cloud). The **AI cleanup model** then cleans up and translates in one step.
- **Without a cleanup provider**, only English is offered: an installed **Whisper Base or Small** translates it offline, for that dictation only. Whisper Small translates noticeably better than Base.
- The arc also has **+** (opens the language list) and **⚙** (opens the settings). Letting go on them drops the recording.

**The button**
- Shows **only while a keyboard is open** (default) or always. It can be dragged to either edge and stays there across rotation.
- **Tap**: dictate (red while recording). **Hold**: dictate with AI cleanup (purple while recording).
- Level bars while recording and a spinner while transcribing.
- If something fails (no network, wrong key, no credit …), it turns **orange**: tap to retry the same recording, or tap the **✕** next to it to discard it.
- Your thumb covers the button, so the app also tells you without looking: a **ring larger than the button** grows around it while you hold it (purple for AI cleanup, orange for discarding; it triggers after 0.8 s), and **different vibrations** mark a normal start (click), a start with AI cleanup (double pulse) and discarding (long buzz). Optionally the **screen edges glow** red or purple while recording (*Button & insertion → Glowing screen edge*).
- Messages appear above your thumb, above the ring.
- A **Quick Settings tile** (add *Phone Whisper* in the notification shade's edit mode) opens the settings from anywhere; so does the ⚙ on the arc.
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

Grab the latest APK from [GitHub Releases](https://github.com/evilknivel/phone-whisper/releases), open it on your phone and install it. Release APKs are signed with this fork's release key, so later versions install over earlier ones.

**Coming from 1.0.0 or a beta?** Since 1.1.0 the app ID is `com.evilknivel.phonewhisper` (before: `com.kafkasl.phonewhisper`), so Android installs it as a separate app. Uninstall the old Phone Whisper first, or at least switch off its accessibility service, otherwise two buttons appear. Settings and downloaded models don't carry over; the setup assistant walks you through again. Later updates install over 1.1.0 as usual.

Android blocks the accessibility permission for apps installed from a file ("restricted settings"). If enabling the service shows "App was denied access": open **Settings → Apps → Phone Whisper → ⋮ → Allow restricted settings**, then enable the service again.

### Build from source

Requires JDK 17 and an Android SDK with platform 37 (set `ANDROID_HOME` or `sdk.dir` in `local.properties`).

```bash
git clone https://github.com/evilknivel/phone-whisper.git && cd phone-whisper
make build
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. With a phone connected over ADB, `make adb-install` builds and installs it (no restricted-settings prompt this way).

A signed release APK needs your own keystore:

```bash
RELEASE_KEYSTORE_FILE=/path/to/release.keystore RELEASE_KEYSTORE_PASSWORD=... \
RELEASE_KEY_ALIAS=... RELEASE_KEY_PASSWORD=... ./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

## Setup

On first launch a **setup assistant** explains the app and walks you through:

1. the **microphone** permission
2. **offline or online** transcription
3. a **model** (offline; it suggests one for your phone's language and downloads it) or a **cloud service and API key** (online)
4. **AI cleanup**: only when holding the button, always, or never, and the service and key for it
5. the **Accessibility Service**: what it accesses and why, your agreement, and a shortcut to the system settings (plus help for "restricted settings")

You can skip it and set everything up yourself, or run it again under **More settings → Setup assistant**.

The **Status** line at the top shows what is still missing, for example "Setup required: accessibility service". When it says "Ready", open any app with a text field and tap the button.

## Settings

The main screen holds status, setup, transcription (mode, your language, models or cloud provider) and AI cleanup (mode, translation languages, service, model, prompt). Everything else is under **More settings**, together with the setup assistant:

| Page | Settings |
|---|---|
| **API keys** | OpenAI and OpenRouter keys; address and key of a custom server. Keys are entered in a masked field that never shows the saved key and can't be screenshotted. |
| **Button & insertion** | Show the button only with a keyboard or always; vibration; glowing screen edge while recording (off by default); cancel on screen off; *Keep my clipboard* |
| **Advanced** | CPU threads for local models (default 4); free model memory when idle (never, 2–30 min, default 5); trim silence (default on); microphone source (speech recognition or standard microphone) |

At the very bottom, **About** shows the installed version.

## Local models

| Model | Size on device | Languages | Notes |
|---|---:|---|---|
| Parakeet 110M | 132 MB | English | Best value |
| Moonshine Tiny | 124 MB | English | Fastest |
| Whisper Base | 161 MB | 99 languages | Small, many languages |
| Whisper Small | 375 MB | 99 languages | More accurate Whisper |
| Parakeet 0.6B | 671 MB | 25 European languages | Best quality |
| Parakeet Ultra | 630 MB | 25 European languages | Most accurate |

Tap a model to download it, tap it again to select it, and use ✕ to delete it. Parakeet detects the spoken language itself; Whisper uses the **Your language** setting. The English-only Whisper Base of earlier versions stays usable while installed but is no longer offered.

Most models are downloaded as individual files from Hugging Face (the sherpa-onnx mirrors and [mldecode/parakeet-ultra-onnx-int8](https://huggingface.co/mldecode/parakeet-ultra-onnx-int8)), pinned to a commit. Parakeet 110M comes from the sherpa-onnx release archive. Models live in app storage under `/data/data/com.evilknivel.phonewhisper/files/models/`.

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

There is no backend. Full policy: [PRIVACY.md](PRIVACY.md) ([Deutsch](PRIVACY.de.md))

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

CI runs the unit tests and builds the APK on every push. Pushing a tag like `v1.0.0` builds a release APK and attaches it to a GitHub release (pre-release for tags with a `-`). To sign releases with a fixed key, set the secrets described in `.github/workflows/release.yml`. [CLAUDE.md](CLAUDE.md) describes the architecture, [CHANGELOG.md](CHANGELOG.md) lists the changes per version, and [CONTRIBUTING.md](CONTRIBUTING.md) explains how to help.

## Help translate

The app is in English only so far, and its texts are still written directly in the Kotlin code. **Translators and Android developers are very welcome:** the first step is moving the texts into `res/values/strings.xml`, after which every language is just one more file. The documentation already exists in English and German. See [CONTRIBUTING.md](CONTRIBUTING.md#translations) for how to start.

## Current limitations

- Accessibility permission is required for cross-app insertion
- Some apps block paste or text injection, or use custom input surfaces
- Good local models are large (130–670 MB)
- Cloud mode requires your own API key (or your own server)
- The app's interface is English only (see [Help translate](#help-translate))

## Credits

**Original author**
- **Pol Alvarez** ([@kafkasl](https://github.com/kafkasl)) created Phone Whisper: the idea, the floating push-to-talk button, insertion through the accessibility service, local transcription with sherpa-onnx, the model downloader, cleanup with prompt presets, the settings UI, the logo and the website. If the app saves you time, consider [sponsoring him](https://github.com/sponsors/kafkasl).

**Ideas from pull requests to the original repository**

None of these pull requests were merged here as code. We took their ideas and implemented them our own way; the authors deserve the credit for the ideas and for the problems they found.

| Contributor | Pull requests | What we took from them |
|---|---|---|
| **Renat** | [#8](https://github.com/kafkasl/phone-whisper/pull/8), [#9](https://github.com/kafkasl/phone-whisper/pull/9), [#10](https://github.com/kafkasl/phone-whisper/pull/10), [#11](https://github.com/kafkasl/phone-whisper/pull/11), [#12](https://github.com/kafkasl/phone-whisper/pull/12), [#13](https://github.com/kafkasl/phone-whisper/pull/13) | Show the button only while a keyboard is open; custom transcription API address; inserting without the clipboard; placeholder text in web editors; level meter and cancelling on screen off; retry state with readable error messages |
| **Maxime Borges** | [#19](https://github.com/kafkasl/phone-whisper/pull/19)–[#25](https://github.com/kafkasl/phone-whisper/pull/25) | Direct per-file downloads from Hugging Face instead of slow bz2 archives; downloads in a foreground service; the finding that sherpa-onnx's Whisper default translates into English; correct model sizes; inserting directly while a keyboard is open, and Telegram's placeholder reported as text |
| **ArtificialTruth** | [#16](https://github.com/kafkasl/phone-whisper/pull/16) | Releasing the local model after inactivity and loading it again on demand |
| **Luc Brinkman** ([@lucbrinkman](https://github.com/lucbrinkman)) | [#14](https://github.com/kafkasl/phone-whisper/pull/14) | Proposed an open-source license; the original repository is licensed under Apache 2.0 |

**This fork:** evilknivel, with [Claude Code](https://claude.ai/code).

**Software and models used**

| Component | Author | License |
|---|---|---|
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (speech recognition runtime, Kotlin API) | k2-fsa / Next-gen Kaldi | Apache 2.0 |
| [Silero VAD](https://github.com/snakers4/silero-vad) (bundled, silence trimming) | Silero Team | MIT |
| Parakeet 110M, Parakeet 0.6B v3 | NVIDIA | CC BY 4.0 |
| [Parakeet Ultra](https://huggingface.co/moondream/parakeet-ultra) | Moondream (based on NVIDIA Parakeet) | CC BY 4.0 |
| [Parakeet Ultra int8 for sherpa-onnx](https://huggingface.co/mldecode/parakeet-ultra-onnx-int8) | mldecode | CC BY 4.0 |
| Whisper Base, Whisper Small | OpenAI | Apache 2.0 (model weights), MIT (code) |
| Moonshine Tiny | Useful Sensors | MIT |
| sherpa-onnx model conversions on Hugging Face | Fangjun Kuang ([csukuangfj](https://huggingface.co/csukuangfj)) | as the original models |
| OkHttp, AndroidX, Material Components, Apache Commons Compress | Square, Google, Apache Software Foundation | Apache 2.0 |

The models are downloaded from their publishers at your request and are not part of this repository.

## License

Apache License 2.0, see [LICENSE](LICENSE). Copyright of the original code: Pol Alvarez. The names "Phone Whisper" and the logo belong to the original author; the Apache license does not grant trademark rights.

## Development history

**Original app (March–August 2026, Pol Alvarez, [@kafkasl](https://github.com/kafkasl))**
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
8. **Features from the original repo's open pull requests**, reimplemented our own way (see [Credits](#credits)): button only with a keyboard, inserting without the clipboard, level bars, cancel on screen off, retry state with readable errors and a custom API address (ideas by Renat); background and direct Hugging Face downloads and the Whisper language finding (Maxime Borges); freeing the model when idle (ArtificialTruth). Plus silence trimming (VAD), language labels, pruning unused model files, a cleanup prompt that keeps the language, privacy fixes, vibration, microphone source, and settings split into sub-pages
9. **Languages**: language setting for Whisper and the cloud, multilingual Whisper Base and Whisper Small (Whisper had translated German into English with sherpa-onnx's default), complete endings for Whisper Base
10. **Documentation and credits**: complete README, website and privacy policy; credits for the original author and the pull request authors
11. **Setup assistant and hold-to-clean-up**: a first-run assistant for permissions, mode, model or provider, cleanup and the accessibility service (with the disclosure Google Play asks for); holding the button dictates with AI cleanup
12. **Feedback under the thumb**: a hold ring larger than the button, growing in the color of the mode; distinct vibrations; a ✕ next to the orange button to discard; an optional glowing screen edge while recording; messages shown above the thumb
13. **Version 1.0.0**: documentation in English and German, changelog, contribution guide with a call for translators, an About row with the version, and the first release signed with this fork's release key
14. **Translation**: hold the button and slide onto a target language on an arc; the cleanup model translates, or Whisper offline into English without a provider; + and ⚙ on the arc and a Quick Settings tile open the settings
15. **Version 1.1.0**: translation and settings shortcuts as a stable release, and this fork's own app ID `com.evilknivel.phonewhisper`

## Support

If Phone Whisper saves you typing, you can buy me a coffee for the brainpower that went into this fork:

[![Buy me a coffee](https://img.shields.io/badge/Buy%20me%20a%20coffee-fmholzo-FFDD00?logo=buymeacoffee&logoColor=black)](https://buymeacoffee.com/fmholzo)
