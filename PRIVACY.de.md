# Datenschutzerklärung für Phone Whisper

[English](PRIVACY.md) | **Deutsch**

Diese Erklärung gilt für den Fork unter [evilknivel/phone-whisper](https://github.com/evilknivel/phone-whisper) ab Version 1.0.0. Maßgeblich ist die [englische Fassung](PRIVACY.md); diese Übersetzung dient dem besseren Verständnis.

Phone Whisper ist eine Android-Diktier-App, die Sprache aufnimmt, transkribiert und das Ergebnis appübergreifend in Textfelder einfügt.

## Umgang mit Daten

Phone Whisper kennt zwei Transkriptionsmodi.

### Lokaler Modus

Im lokalen Modus wird die Aufnahme auf dem Gerät mit lokalen Spracherkennungsmodellen verarbeitet. Die Aufnahme verlässt das Gerät nicht, auch nicht, wenn das lokale Modell fehlt oder noch lädt: Die App zeigt dann eine Meldung, statt einen Cloud-Anbieter zu nutzen.

Stille wird vor der Transkription auf dem Gerät aus der Aufnahme entfernt (Silero VAD, in der App enthalten).

Modelle werden nur heruntergeladen, wenn du das auswählst, und zwar von GitHub (sherpa-onnx-Releases) oder Hugging Face. Diese Downloads enthalten weder Audio noch Text.

### Cloud-Modus

Im Cloud-Modus wird die Aufnahme direkt vom Gerät an die Transkriptions-API des gewählten Anbieters gesendet: OpenAI, OpenRouter oder ein eigener OpenAI-kompatibler Server, dessen Adresse du einträgst. Hast du eine feste Sprache eingestellt, wird sie mitgesendet. Mit „Stille abschneiden“ (Standard) werden nur die Abschnitte mit Sprache gesendet; eine Aufnahme ohne erkannte Sprache wird nicht gesendet, außer du tippst auf „Wiederholen“.

Öffnest du die Modellauswahl, lädt die App die Liste der verfügbaren Modelle vom Anbieter. Bei OpenAI und einem eigenen Server enthält diese Anfrage deinen API-Schlüssel; Audio oder Text werden dabei nicht gesendet.

Ist die optionale Glättung aktiv, wird zusätzlich der transkribierte Text direkt vom Gerät an die Chat-API des gewählten Anbieters gesendet, um Satzzeichen, Groß- und Kleinschreibung und Verständlichkeit zu verbessern.

Die Adresse eines eigenen Servers darf unverschlüsseltes http verwenden, für einen Server im eigenen Netz. So gesendete Aufnahmen kann jeder in diesem Netz mitlesen.

OpenRouter leitet Anfragen an den Anbieter des dort gewählten Modells weiter, der die Daten nach seinen eigenen Bedingungen verarbeitet.

## API-Schlüssel

Nutzt du Cloud-Funktionen, werden deine API-Schlüssel lokal im App-Speicher auf deinem Gerät abgelegt und nur zur Anmeldung bei den direkt angesprochenen Diensten verwendet.

Es gibt keinen Zwischenserver für diese Anfragen.

## Bedienungshilfe

Bevor dich die App zu den Bedienungshilfe-Einstellungen des Systems schickt, erklärt sie, worauf der Dienst zugreift, und bittet um deine Zustimmung.

Phone Whisper nutzt den Android-Bedienungshilfendienst nur, um das gerade fokussierte Textfeld zu finden und diktierten Text einzufügen, nachdem du ausdrücklich auf den schwebenden Button getippt hast, und um zu sehen, ob eine Tastatur offen ist, damit der Button nur dann erscheint. Inhalte von Textfeldern werden nicht protokolliert.

Eingefügter Text wird in die Zwischenablage kopiert und eingefügt, außer du schaltest „Keep my clipboard“ ein. Kopierte Transkripte sind als sensibel markiert: Android blendet ihren Inhalt dann in der Vorschau der Zwischenablage aus, und Tastaturen, die diese Markierung beachten, schlagen sie nicht vor.

Phone Whisper ist nicht dafür gedacht, das Surfverhalten zu überwachen, Bildschirminhalte für Analysen zu sammeln oder Abläufe im Hintergrund zu automatisieren.

## Datenerhebung

Für Phone Whisper gibt es keinen eigenen Server. Es werden keine Benutzerkonten, Analysedaten oder hochgeladenen Aufnahmen erhoben.

Drittanbieter, die du selbst nutzt, etwa OpenAI oder OpenRouter, können Daten nach ihren eigenen Bedingungen und Datenschutzerklärungen verarbeiten.

## Kontakt

Fragen zum Datenschutz in diesem Fork stellst du als Issue unter [github.com/evilknivel/phone-whisper/issues](https://github.com/evilknivel/phone-whisper/issues). Zur ursprünglichen App von Pol Alvarez siehe [kafkasl/phone-whisper](https://github.com/kafkasl/phone-whisper).
