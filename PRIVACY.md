# Privacy Policy for Phone Whisper

Phone Whisper is an Android dictation app that records speech, transcribes it, and inserts the result into text fields across apps.

## Data handling

Phone Whisper supports two transcription modes.

### Local mode

In local mode, audio is processed on-device using local speech recognition models. Audio does not leave the device, also not when the local model is missing or still loading: the app then shows a message instead of using a cloud provider.

Silence is trimmed from recordings on the device before transcription (Silero VAD, bundled with the app).

Models are downloaded only when you choose to download them, from GitHub (sherpa-onnx releases) or Hugging Face. These downloads contain no audio or text.

### Cloud mode

In cloud mode, recorded audio is sent directly from the device to the transcription API of the provider you select, OpenAI, OpenRouter, or a custom OpenAI-compatible server whose address you enter, to generate text. If you set a fixed language, it is sent along. With silence trimming on (the default), only the parts of the recording with speech are sent, and a recording with no detected speech is not sent unless you tap the retry button.

When you open the model picker, the app loads the list of available models from the provider. For OpenAI and a custom server this request includes your API key; no audio or text is sent.

If optional cleanup is enabled, the transcribed text is also sent directly from the device to the chat API of the provider you select to improve punctuation, capitalization, and clarity.

A custom server address may use unencrypted http, for a server on your own network. Audio sent that way can be read by anyone on that network.

OpenRouter forwards requests to the provider of the model you choose there, which processes the data under its own terms.

## API keys

If you use cloud features, your API keys are stored locally on your device in app storage and used to authenticate requests sent directly to those services.

I do not operate a relay server for these requests.

## Accessibility Service

Phone Whisper uses Android Accessibility Service only to identify the currently focused text field and insert dictated text after you explicitly interact with the floating overlay button, and to see whether a keyboard is open so the button can be shown only then. It does not log the contents of text fields.

Inserted text is copied to the clipboard and pasted, unless you turn on "Keep my clipboard". Copied transcripts are marked as sensitive: Android then hides their content in the clipboard preview, and keyboards that honor the flag don't show them as suggestions.

Phone Whisper is not designed to monitor browsing, collect screen content for analytics, or perform background automation.

## Data collection

I do not run a backend for Phone Whisper and do not collect user accounts, analytics, or uploaded recordings myself.

Third-party services you choose to use, such as OpenAI or OpenRouter, may process data according to their own terms and privacy policies.

## Contact

For questions about privacy, contact: pol.avms@gmail.com
