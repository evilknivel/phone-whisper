# Contributing

**English** | [Deutsch](CONTRIBUTING.de.md)

Thanks for helping with Phone Whisper! Bug reports, ideas, translations and code are all welcome.

## Reporting bugs and ideas

Open an issue at [github.com/evilknivel/phone-whisper/issues](https://github.com/evilknivel/phone-whisper/issues). For bugs, please include your phone model, Android version, the app version (shown under **About** in the settings), the app you dictated into, and what the button and its message showed.

## Translations

**Help wanted!** The app's interface is English only so far. We would love to offer it in German, Spanish, French, and any other language people speak into it.

The texts are still written directly in the Kotlin code, so there are two ways to help:

1. **Android developers**: move the user-visible texts from the Kotlin files (mainly `MainActivity`, `SetupWizardActivity`, the other `*Activity` files, `Prefs.kt`, `ErrorMessages.kt` and the messages in `WhisperAccessibilityService`) into `app/src/main/res/values/strings.xml` and use `getString(R.string.…)`. Small pull requests per screen are perfect.
2. **Translators**: once a screen's texts are in `strings.xml`, add `app/src/main/res/values-<language>/strings.xml` (for example `values-de/strings.xml`) with the translations. No coding needed; you can do it directly in the GitHub web editor. Until then, you can already translate the documentation (`README.<language>.md`, `PRIVACY.<language>.md`) or comment on the issue for your language.

Please keep model names, provider names and technical terms like "API key" recognizable, and keep placeholders such as `%1$s` unchanged.

The documentation is always kept in English. Other languages are welcome as additional files with a language switch at the top, like `README.de.md`.

## Code

- Build and test: `make build`, `make test`. [CLAUDE.md](CLAUDE.md) explains the architecture and the conventions.
- After changes to local transcription, the model catalog or the sherpa-onnx version, run `make integration-test` (see CLAUDE.md).
- Keep pull requests focused, add unit tests for logic that can run on the JVM, and update README.md, README.de.md and CHANGELOG.md when behavior changes.
- `PRIVACY.md` must stay in sync with what the app sends over the network.

By contributing you agree that your contribution is licensed under the [Apache License 2.0](LICENSE).
