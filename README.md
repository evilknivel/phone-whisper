<p align="center">
  <img src="docs/logo.svg" width="128" height="128" alt="Phone Whisper Logo">
</p>

# Phone Whisper

Push-to-talk dictation for Android.

Phone Whisper lets you speak into most apps without switching keyboards. Tap the floating button, speak, tap again, and your text is inserted into the currently focused text field when the app exposes a standard Android input field.\

It supports:

- **Local on-device transcription** with sherpa-onnx
- **Cloud transcription** with OpenAI's speech-to-text models or any speech-to-text model on [OpenRouter](https://openrouter.ai)
- **Optional cleanup** with OpenAI or any OpenRouter chat model to fix punctuation and grammar

If you try it and it genuinely saves you time, consider [sponsoring](https://github.com/sponsors/kafkasl)


## Why I built this

- I like SwiftKey and want to keep it as keyboard but...
- Most keyboard dictation felt too inaccurate
- Gemini's voice input auto submits your transcription (which is pretty bad) so you can't edit it before sending
- Post processing yields much better results, specially adding a list of keywords and technical terms you often use
- Inserting text into the field you're already using lets you keep editing it like any other draft.

## Install

### Easiest: download the APK

Grab the latest APK from [GitHub Releases](https://github.com/kafkasl/phone-whisper/releases).

Open it on your phone, install it, then launch the app once to finish setup.

### Build from source

Requires JDK 17 and an Android SDK with platform 37 (set `ANDROID_HOME` or `sdk.dir` in `local.properties`).

```bash
git clone https://github.com/kafkasl/phone-whisper.git && cd phone-whisper
make build
```

APK output:

```bash
app/build/outputs/apk/debug/app-debug.apk
```

If you use ADB:

```bash
make adb-install
```

## How it works

1. A small button floats on screen, by default only while a keyboard is open
2. Tap once to start recording; bars in the button follow your voice
3. Tap again to stop
4. Silence is trimmed, then the audio is transcribed locally or in the cloud
5. The text is inserted into the focused text field
6. If insertion fails, the text is copied to the clipboard
7. If transcription fails (no network, wrong key, ...), the button turns orange: tap to retry the same recording, hold to discard it

Turning the screen off while recording cancels the recording.

## Setup

### First-time setup

1. Open **Phone Whisper**
2. Grant the **audio recording** permission
3. Enable the **Accessibility Service**
4. Choose your transcription mode:
   - **Local**: download a model in the app
   - **Cloud**: pick OpenAI, OpenRouter or a custom OpenAI-compatible server (Groq, a Whisper server on your computer) and set its API key under **More settings → API keys**. You can choose the model for each provider.

Once setup is done, the floating button is ready.

### Settings

The main screen holds setup, transcription and cleanup. Everything else is under **More settings**:

- **API keys**: OpenAI, OpenRouter, and the address and key of a custom server
- **Button & insertion**: show the button always or only with a keyboard, vibration, cancelling on screen off, and **Keep my clipboard** (write text straight into the field instead of pasting it)
- **Advanced**: CPU threads, freeing the model's memory when idle, silence trimming, microphone source

## Why does it need Accessibility?

Phone Whisper uses Android Accessibility Service for one narrow reason: to insert dictated text into the currently focused text field across apps.

It does **not** replace your keyboard. It does **not** run background automation. It only acts after you explicitly tap the overlay button.

## Privacy

Phone Whisper supports two modes:

- **Local mode**: audio stays on-device. If the local model isn't ready, the app tells you instead of falling back to the cloud.
- **Cloud mode**: audio is sent directly from your device to the transcription API of the provider you pick (OpenAI, OpenRouter or your own server)
- **Optional cleanup**: transcript text is sent directly from your device to the chat API of the provider you pick

I don't run a backend for this app. In cloud mode, requests go straight from your phone to the provider using your own API key. OpenRouter forwards requests to the provider of the model you choose.

Full policy: [PRIVACY.md](PRIVACY.md)

## Local models

Models are stored in app storage under:

```bash
/data/data/com.kafkasl.phonewhisper/files/models/
```

Current catalog:

| Model | Size on device | Languages | Notes |
|---|---:|---|---|
| Parakeet 110M | 132 MB | English | Best value |
| Moonshine Tiny | 124 MB | English | Fastest |
| Whisper Base | 161 MB | 99 languages | Small, many languages |
| Whisper Small | 375 MB | 99 languages | More accurate Whisper |
| Parakeet 0.6B | 671 MB | 25 European languages | Best quality |
| Parakeet Ultra | 630 MB | 25 European languages | Most accurate |

**Language:** Parakeet detects the spoken language itself. Whisper and cloud transcription use the **Language** setting on the main screen: "Detect automatically" by default, or a fixed language if short dictations come out in the wrong one. (Without it, Whisper would translate other languages into English.) The English-only Whisper Base of earlier versions stays usable while installed but is no longer offered.

Tap a model to download it, tap it again to select it, and use ✕ to delete it. Downloads keep running with the screen off and show their progress in a notification. Under **More settings → Advanced** you can set how many CPU cores local transcription uses (default 4) and whether the model is unloaded after a few idle minutes to save memory.

Most models are downloaded as individual files from Hugging Face (the sherpa-onnx mirrors and [mldecode/parakeet-ultra-onnx-int8](https://huggingface.co/mldecode/parakeet-ultra-onnx-int8)), pinned to a commit and checked against their SHA-256. Parakeet 110M comes from the sherpa-onnx release archive. Files the app doesn't load (full-precision copies, test recordings) are deleted after installing.

## Development

```bash
make build       # build debug APK
make test        # run unit tests
make integration-test MODELS=... WAV=...  # run every catalog model through sherpa-onnx (Linux x64, see CLAUDE.md)
make adb-install # build + install via ADB
make clean       # clean build artifacts
```

## App compatibility

Phone Whisper works best in apps that use standard Android text fields.
Some apps use custom text surfaces or terminal-style views, which may not support direct accessibility paste.
When insertion is not possible, Phone Whisper falls back to copying the transcript to the clipboard.

### Termux

Termux's main terminal area is not a standard Android text field, so direct insertion may not work there.

To use Phone Whisper in Termux:

1. Focus Termux
2. Swipe the extra keys row (`ESC`, `CTRL`, `ALT`, arrows, etc.) left or right
3. Switch to Termux's native text input box
4. Dictate there

Once text is inserted into the native input box, Termux sends it to the terminal normally.

## Current limitations

- Accessibility permission is required for cross-app insertion
- Some apps may block paste or text injection
- Some apps use custom input surfaces instead of standard Android text fields
- Local models are large
- Cloud mode requires your own OpenAI or OpenRouter API key

## Support the project

If Phone Whisper saves you time, you can sponsor the project on GitHub:

- https://github.com/sponsors/kafkasl

## License

Personal project. Do whatever you want with it.
