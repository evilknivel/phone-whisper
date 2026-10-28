# Changelog

All notable changes to this fork. Versions before 1.0.0 were betas; 0.3.0 is the last version of the [original app](https://github.com/kafkasl/phone-whisper) by Pol Alvarez.

## 1.3.0 – 2026-10-28

### Added
- Transcribe while you speak (local models): Silero VAD watches the recording, and after a pause following at least 3 s of speech that part is transcribed in the background while you keep talking. After you stop, only the last part is left, so the wait no longer grows with the length of the dictation. On by default (*Advanced → Transcribe while you speak*, needs *Trim silence*). Ported from evilknivel's InstantSTT for Windows.
- *Keep running* (main screen and a new step of the setup assistant): opens the settings the phone's maker needs so the accessibility service survives battery saving and "clear all" in the recent apps, for Xiaomi/Redmi/POCO, Samsung, Huawei, Honor, OPPO/realme/OnePlus, vivo/iQOO, ASUS, Meizu, Tecno/Infinix, and battery optimization elsewhere. Shows ✓ where the setting can be read (battery optimization, Xiaomi Autostart).

### Changed
- Setup assistant, accessibility step: the steps are numbered and in the order Android needs them: first allow restricted settings in App info (for APKs installed from a file), then turn on the service.

### Fixed
- After "clear all" in the recent apps (e.g. HyperOS's OneKeyClean), the accessibility settings showed Phone Whisper as not working until it was turned off and on: the system killed it and, without Autostart, Android didn't restart it. *Keep running* now walks through the settings that prevent this, and "on, but not running" points there.

## 1.2.1 – 2026-10-25

### Changed
- If the AI cleanup or a translation fails (no network, wrong key, provider error), the raw or untranslated text is no longer inserted. The button turns orange with the error message; a tap retries just the cleanup or translation with the kept transcript (for example after fixing the key), the ✕ discards it. A translation without a configured cleanup provider is handled the same way.

## 1.2.0 – 2026-10-25

### Added
- The screen no longer times out while you dictate or the text is being transcribed (*Button & insertion → Keep the screen on while dictating*, on by default).
- The button remembers its position separately for portrait and landscape, and keeps it after a restart. Turning the phone brings it back to where you put it in that orientation; landscape starts from the portrait spot until you move it there.

## 1.1.1 – 2026-10-25

### Fixed
- In landscape the button could sit on top of the navigation buttons (back, home, recents) at the side of the screen. The button, its messages and the language arc now stay inside the area without the status bar, navigation bar and display cutouts, in every orientation.

## 1.1.0 – 2026-10-25

### Translation
- Hold the button, then slide your thumb onto a target language on the arc that opens around it, and let go: the dictation is transcribed with your selected model and translated by the AI cleanup model. The button shows the language code while you speak.
- Up to 6 target languages under AI cleanup → Translation; your own language is left off the arc.
- Without a cleanup provider, an installed multilingual Whisper (Base or Small) translates into English offline, for that dictation only.
- The *Language* setting is now *Your language* and is also used as the source language when translating.

### Settings shortcuts
- **+** on the arc opens the translation languages, **⚙** the settings.
- New Quick Settings tile that opens the settings.

### Button
- The hold ring also shows when cleanup is set to *never* (red).

### App ID
- The app ID (package name) is now `com.evilknivel.phonewhisper` instead of the original app's `com.kafkasl.phonewhisper`.

### Upgrading from 1.0.0 or a beta
Because of the new app ID, Android installs 1.1.0 as a separate app. Uninstall the old Phone Whisper first, or at least switch off its accessibility service, otherwise two buttons appear. Settings and models don't carry over.

## 1.0.0 – 2026-10-25

First stable release of this fork. Compared with the original 0.3.0:

### Transcription
- Cloud providers: OpenAI, OpenRouter (any speech-to-text model) and custom OpenAI-compatible servers (Groq, self-hosted Whisper), each with a model picker that loads the provider's live model list. OpenAI defaults to `gpt-4o-mini-transcribe`.
- Local models: Parakeet 110M, Parakeet 0.6B, Parakeet Ultra (new), multilingual Whisper Base and Whisper Small (new), Moonshine Tiny. sherpa-onnx 1.13.8.
- Language setting (detect automatically or fixed) for Whisper and the cloud. Whisper no longer translates other languages into English.
- Silence trimming with Silero VAD; recordings without speech are not transcribed.
- Local mode never sends audio to the cloud, also not while the model loads.
- Optional release of the model after idle minutes; it loads again while you speak.
- Configurable CPU threads and microphone source.

### AI cleanup
- Modes: only when holding the button (default), always, never.
- Separate provider and model for cleanup; prompts keep the language and never translate; *Simple cleanup* is the default preset.
- Models that reject `temperature` are retried without it.

### The button
- Shown only while a keyboard is open (default) or always; keeps its edge and height across rotation.
- Tap to dictate (red), hold to dictate with AI cleanup (purple).
- Hold ring larger than the button, growing in the color of the mode; distinct vibrations for start, cleanup and discard; optional glowing screen edge while recording; messages above the thumb.
- Level bars while recording. Retry state (orange) with readable error messages and a ✕ to discard.
- Recording is cancelled when the screen turns off.

### Inserting text
- Only into the focused field, never into another field on the screen.
- Optional "Keep my clipboard": write directly into the field; the clipboard is only a fallback.
- Copied transcripts are marked sensitive; field contents are never logged.

### Models and downloads
- Downloads run in a foreground service with a progress notification, also with the screen off, as SHA-256-checked files from Hugging Face where available.
- Atomic installs, deletable models, unused files pruned (Whisper Base 433 → 161 MB). Whisper Base actually loads now.

### Setup and settings
- First-run setup assistant: microphone, offline or online, model or provider and key, cleanup, accessibility service with the disclosure and consent Google Play requires.
- Status line names what is missing and detects a service that is switched on but not running.
- Settings split into a main screen and API keys, Button & insertion, Advanced; an About row shows the version.
- API keys are entered in a masked, screenshot-protected field that never shows the saved key.

### Project
- Toolchain: AGP 9, Gradle 9, Kotlin 2.4, compileSdk 37, targetSdk 36.
- CI on every push; release workflow with signing via secrets; JNI integration test for every catalog model.
- Documentation in English and German; credits for the original author and the authors of the pull requests whose ideas were used; contribution guide with a call for translators.

### Upgrading from a beta
The 0.4–0.6 betas were signed with a development key, 1.0.0 with the release key. Uninstall the beta once before installing 1.0.0.
