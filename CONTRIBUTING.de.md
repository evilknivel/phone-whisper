# Mitmachen

[English](CONTRIBUTING.md) | **Deutsch**

Danke, dass du bei Phone Whisper mithilfst! Fehlerberichte, Ideen, Übersetzungen und Code sind gleichermaßen willkommen.

## Fehler und Ideen melden

Eröffne ein Issue unter [github.com/evilknivel/phone-whisper/issues](https://github.com/evilknivel/phone-whisper/issues). Gib bei Fehlern bitte dein Handymodell, die Android-Version, die App-Version (steht unter **About** in den Einstellungen), die App, in die du diktiert hast, und an, was der Button und seine Meldung angezeigt haben.

## Übersetzungen

**Hilfe gesucht!** Die Oberfläche der App gibt es bisher nur auf Englisch. Wir würden sie gern auf Deutsch, Spanisch, Französisch und in allen anderen Sprachen anbieten, in die Leute diktieren.

Die Texte stehen noch direkt im Kotlin-Code, deshalb gibt es zwei Wege mitzuhelfen:

1. **Android-Entwickler**: Verschiebt die sichtbaren Texte aus den Kotlin-Dateien (vor allem `MainActivity`, `SetupWizardActivity`, die übrigen `*Activity`-Dateien, `Prefs.kt`, `ErrorMessages.kt` und die Meldungen in `WhisperAccessibilityService`) nach `app/src/main/res/values/strings.xml` und nutzt `getString(R.string.…)`. Kleine Pull Requests je Bildschirm sind ideal.
2. **Übersetzerinnen und Übersetzer**: Sobald die Texte eines Bildschirms in `strings.xml` stehen, legt `app/src/main/res/values-<sprache>/strings.xml` an (zum Beispiel `values-de/strings.xml`) und tragt die Übersetzungen ein. Programmieren ist dafür nicht nötig, das geht direkt im Web-Editor von GitHub. Bis dahin könnt ihr schon die Dokumentation übersetzen (`README.<sprache>.md`, `PRIVACY.<sprache>.md`) oder euch im Issue für eure Sprache melden.

Modellnamen, Anbieternamen und Fachbegriffe wie „API key“ sollten erkennbar bleiben, und Platzhalter wie `%1$s` bitte unverändert lassen.

Die Dokumentation wird immer auf Englisch gepflegt. Weitere Sprachen sind als zusätzliche Dateien mit Sprachumschalter oben willkommen, wie `README.de.md`.

## Code

- Bauen und testen: `make build`, `make test`. [CLAUDE.md](CLAUDE.md) erklärt Architektur und Konventionen.
- Nach Änderungen an der lokalen Transkription, am Modellkatalog oder an der sherpa-onnx-Version `make integration-test` ausführen (siehe CLAUDE.md).
- Pull Requests bitte fokussiert halten, Unit-Tests für Logik ergänzen, die auf der JVM läuft, und README.md, README.de.md sowie CHANGELOG.md anpassen, wenn sich Verhalten ändert.
- `PRIVACY.md` muss zu dem passen, was die App über das Netz sendet.

Mit deinem Beitrag stimmst du zu, dass er unter der [Apache License 2.0](LICENSE) steht.
