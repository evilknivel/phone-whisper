<p align="center">
  <img src="docs/logo.svg" width="128" height="128" alt="Phone Whisper Logo">
</p>

# Phone Whisper

[English](README.md) | **Deutsch**

Push-to-talk-Diktat für Android.

Mit Phone Whisper sprichst du in fast jede App, ohne die Tastatur zu wechseln. Tippe auf den schwebenden Button, sprich, tippe noch einmal, und dein Text landet im gerade aktiven Textfeld, sofern die App ein normales Android-Eingabefeld verwendet.

> **Dies ist ein Fork.** Phone Whisper wurde von **Pol Alvarez** ([@kafkasl](https://github.com/kafkasl)) unter [kafkasl/phone-whisper](https://github.com/kafkasl/phone-whisper) entwickelt. Die Idee, die ursprüngliche App und ihr Design stammen von ihm. Dieser Fork von **evilknivel** ergänzt Cloud-Anbieter, weitere Modelle, Sprachen, Übersetzen und viele Korrekturen. Die Ideen dieses Forks stammen von evilknivel, mit Ausnahme der Funktionen aus offenen Pull Requests im Original-Repository, die ihren Autoren zugeschrieben sind. Alle Beteiligten stehen unter [Credits](#credits), und die [Entwicklungsgeschichte](#entwicklungsgeschichte) am Ende zeigt, was hier dazukam.

## Funktionen

**Transkription**
- **Lokal auf dem Gerät** mit [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx): Parakeet (110M, 0.6B, Ultra), Whisper (Base, Small) und Moonshine. Die Aufnahme verlässt das Handy nie, und der lokale Modus weicht nie auf die Cloud aus.
- **Cloud** mit OpenAI, [OpenRouter](https://openrouter.ai) (jedes Spracherkennungsmodell dort) oder deinem eigenen **OpenAI-kompatiblen Server** (Groq, ein Whisper-Server auf deinem Rechner). Das Modell ist je Anbieter wählbar, aus der aktuellen Modellliste des Anbieters.
- **Sprachen**: Parakeet 0.6B und Ultra verstehen 25 europäische Sprachen inklusive Deutsch, Whisper 99 Sprachen. Die Einstellung **Your language** (Standard: automatisch erkennen) legt die gesprochene Sprache für Whisper und die Cloud fest.
- **Stille abschneiden** mit Silero VAD vor der Transkription: schneller, günstiger und keine erfundenen Sätze bei Stille. Wird keine Sprache erkannt, wird nichts gesendet; „Wiederholen“ sendet die ungekürzte Aufnahme.

**KI-Glättung (optional)**
- Ein Sprachmodell (OpenAI, OpenRouter oder dein eigener Server) korrigiert Satzzeichen, Grammatik und offensichtliche Erkennungsfehler. Es behält deine Sprache bei und übersetzt nie.
- **Halte den Button**, um mit Glättung zu diktieren; tippe für ein normales Diktat (Standard). Die Glättung lässt sich auch auf *immer* oder *nie* stellen.
- Vorlagen: *Simple cleanup* (Standard), *Dev cleanup* (macht aus „command mode …“ Shell-Befehle) oder ein eigener Prompt.

**Übersetzen (optional)**
- In deiner Sprache sprechen, in einer anderen einfügen. Bis zu 6 Zielsprachen wählst du unter **AI cleanup → Translation**.
- Den Button **halten**, bis der Ring voll ist: Die Sprachen erscheinen im Halbkreis um ihn herum. **Schieb den Daumen** auf eine (sie leuchtet auf, mit kurzem Vibrieren) und lass los. Während du sprichst, zeigt der Button das Sprachkürzel, z. B. **ES**. Loslassen nahe am Button ist ein normales Diktat mit Glättung.
- Transkribiert wird immer mit dem Modell aus deinen Einstellungen (etwa Parakeet Ultra oder die Cloud). Das **Modell der KI-Glättung** glättet und übersetzt dann in einem Schritt.
- **Ohne Glättungsanbieter** wird nur Englisch angeboten: Ein installiertes **Whisper Base oder Small** übersetzt offline, nur für dieses Diktat. Whisper Small übersetzt spürbar besser als Base.
- Im Halbkreis gibt es außerdem **+** (öffnet die Sprachliste) und **⚙** (öffnet die Einstellungen). Loslassen darauf verwirft die Aufnahme.

**Der Button**
- Erscheint **nur bei offener Tastatur** (Standard) oder immer. Er lässt sich an beide Bildschirmränder ziehen und bleibt beim Drehen dort.
- **Tippen**: diktieren (rot während der Aufnahme). **Halten**: diktieren mit KI-Glättung (violett während der Aufnahme).
- Pegelbalken während der Aufnahme und ein Ladekreis während der Transkription.
- Geht etwas schief (kein Netz, falscher Schlüssel, kein Guthaben …), wird er **orange**: Antippen wiederholt dieselbe Aufnahme, das **✕** daneben verwirft sie.
- Dein Daumen verdeckt den Button, deshalb gibt es Rückmeldung ohne Hinsehen: Ein **Ring, größer als der Button**, wächst beim Halten um ihn herum (violett für KI-Glättung, orange zum Verwerfen; er löst nach 0,8 s aus), und **unterschiedliche Vibrationen** markieren einen normalen Start (Klick), einen Start mit KI-Glättung (Doppelimpuls) und das Verwerfen (langes Brummen). Optional **leuchten die Bildschirmränder** während der Aufnahme rot oder violett (*Button & insertion → Glowing screen edge*).
- Meldungen erscheinen über dem Daumen, oberhalb des Rings.
- Eine **Kachel in den Schnelleinstellungen** (im Bearbeiten-Modus der Benachrichtigungsleiste *Phone Whisper* hinzufügen) öffnet die Einstellungen von überall; das ⚙ im Halbkreis ebenso.
- Schaltest du den Bildschirm während der Aufnahme aus, wird sie abgebrochen.

**Text einfügen**
- Nur ins fokussierte Feld, nie in ein anderes Feld auf dem Bildschirm.
- Standardmäßig wird der Text über die Zwischenablage eingefügt, das klappt in den meisten Apps. Mit **Keep my clipboard** wird er direkt ins Feld geschrieben, und deine Zwischenablage bleibt unangetastet; sie dient dann nur als Ausweichweg.
- Kopierte Transkripte sind als sensibel markiert, Android blendet sie in der Vorschau der Zwischenablage aus.

**Modelle und Speicher**
- Downloads laufen im Hintergrund mit Fortschrittsanzeige, auch bei ausgeschaltetem Bildschirm. Jede Datei wird gegen eine festgelegte SHA-256-Prüfsumme geprüft.
- Modelle lassen sich löschen. Dateien, die ein Modell nicht braucht, werden nach der Installation entfernt.
- Das Modell kann nach einigen Minuten ohne Diktat entladen werden (Standard 5), um Speicher zu sparen. Beim nächsten Mal lädt es, während du sprichst.

## Installation

### APK herunterladen

Lade die aktuelle APK von den [GitHub Releases](https://github.com/evilknivel/phone-whisper/releases), öffne sie auf dem Handy und installiere sie. Release-APKs sind mit dem Release-Schlüssel dieses Forks signiert, spätere Versionen lassen sich also darüber installieren.

**Du kommst von 1.0.0 oder einer Beta?** Seit 1.1.0 lautet die App-ID `com.evilknivel.phonewhisper` (vorher `com.kafkasl.phonewhisper`), deshalb installiert Android sie als eigene App. Deinstalliere zuerst das alte Phone Whisper oder schalte wenigstens dessen Bedienungshilfe aus, sonst erscheinen zwei Buttons. Einstellungen und geladene Modelle werden nicht übernommen; der Einrichtungsassistent führt dich erneut durch. Spätere Updates installieren sich wie gewohnt über 1.1.0.

Android sperrt die Bedienungshilfen-Berechtigung für Apps, die aus einer Datei installiert wurden („eingeschränkte Einstellungen“). Erscheint beim Einschalten „App wurde Zugriff verweigert“: **Einstellungen → Apps → Phone Whisper → ⋮ → Eingeschränkte Einstellungen zulassen**, dann den Dienst erneut einschalten.

### Selbst bauen

Benötigt JDK 17 und ein Android SDK mit Plattform 37 (`ANDROID_HOME` setzen oder `sdk.dir` in `local.properties`).

```bash
git clone https://github.com/evilknivel/phone-whisper.git && cd phone-whisper
make build
```

Die APK liegt dann unter `app/build/outputs/apk/debug/app-debug.apk`. Mit einem per ADB verbundenen Handy baut und installiert `make adb-install` sie (dabei gibt es keine Sperre durch eingeschränkte Einstellungen).

Eine signierte Release-APK braucht einen eigenen Keystore:

```bash
RELEASE_KEYSTORE_FILE=/pfad/zu/release.keystore RELEASE_KEYSTORE_PASSWORD=... \
RELEASE_KEY_ALIAS=... RELEASE_KEY_PASSWORD=... ./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

## Einrichtung

Beim ersten Start erklärt ein **Einrichtungsassistent** die App und führt dich durch:

1. die **Mikrofon**-Berechtigung
2. **offline oder online** transkribieren
3. ein **Modell** (offline; er schlägt eines passend zur Sprache deines Handys vor und lädt es) oder einen **Cloud-Dienst mit API-Schlüssel** (online)
4. die **KI-Glättung**: nur beim Halten, immer oder nie, dazu Dienst und Schlüssel
5. die **Bedienungshilfe**: was sie sieht und warum, deine Zustimmung und eine Abkürzung zu den Systemeinstellungen (samt Hilfe zu „eingeschränkten Einstellungen“)

Du kannst ihn überspringen und alles selbst einstellen oder ihn unter **More settings → Setup assistant** erneut starten.

Die **Status**-Zeile oben zeigt, was noch fehlt, zum Beispiel „Setup required: accessibility service“. Steht dort „Ready“, öffne eine beliebige App mit Textfeld und tippe auf den Button.

## Einstellungen

Die Hauptseite enthält Status, Einrichtung, Transkription (Modus, deine Sprache, Modelle oder Cloud-Anbieter) und KI-Glättung (Modus, Übersetzungssprachen, Dienst, Modell, Prompt). Alles Weitere liegt unter **More settings**, zusammen mit dem Einrichtungsassistenten:

| Seite | Einstellungen |
|---|---|
| **API keys** | Schlüssel für OpenAI und OpenRouter; Adresse und Schlüssel eines eigenen Servers. Schlüssel werden in ein verdecktes Feld eingegeben, das den gespeicherten Schlüssel nie zeigt und keine Screenshots zulässt. |
| **Button & insertion** | Button nur mit Tastatur oder immer; Vibration; leuchtender Bildschirmrand während der Aufnahme (standardmäßig aus); Abbruch bei ausgeschaltetem Bildschirm; *Keep my clipboard* |
| **Advanced** | CPU-Threads für lokale Modelle (Standard 4); Modellspeicher im Leerlauf freigeben (nie, 2–30 Min., Standard 5); Stille abschneiden (standardmäßig an); Mikrofonquelle (Spracherkennung oder normales Mikrofon) |

Ganz unten zeigt **About** die installierte Version.

## Lokale Modelle

| Modell | Größe auf dem Gerät | Sprachen | Hinweis |
|---|---:|---|---|
| Parakeet 110M | 132 MB | Englisch | Bestes Preis-Leistungs-Verhältnis |
| Moonshine Tiny | 124 MB | Englisch | Am schnellsten |
| Whisper Base | 161 MB | 99 Sprachen | Klein, viele Sprachen |
| Whisper Small | 375 MB | 99 Sprachen | Genaueres Whisper |
| Parakeet 0.6B | 671 MB | 25 europäische Sprachen | Beste Qualität |
| Parakeet Ultra | 630 MB | 25 europäische Sprachen | Am genauesten |

Tippe auf ein Modell, um es zu laden, noch einmal, um es auszuwählen, und auf ✕, um es zu löschen. Parakeet erkennt die gesprochene Sprache selbst; Whisper nutzt die Einstellung **Your language**. Das rein englische Whisper Base früherer Versionen bleibt nutzbar, solange es installiert ist, wird aber nicht mehr angeboten.

Die meisten Modelle werden als einzelne Dateien von Hugging Face geladen (die sherpa-onnx-Spiegel und [mldecode/parakeet-ultra-onnx-int8](https://huggingface.co/mldecode/parakeet-ultra-onnx-int8)), auf einen festen Commit gepinnt. Parakeet 110M kommt aus dem Release-Archiv von sherpa-onnx. Die Modelle liegen im App-Speicher unter `/data/data/com.evilknivel.phonewhisper/files/models/`.

## Cloud-Anbieter

| Anbieter | Standard Transkription | Standard Glättung | Hinweis |
|---|---|---|---|
| OpenAI | `gpt-4o-mini-transcribe` | `gpt-4o-mini` | Modellliste wird mit deinem API-Schlüssel geladen |
| OpenRouter | `openai/whisper-large-v3` | `openai/gpt-4o-mini` | Öffentliche Modellliste, per Eingabe filterbar |
| Custom | `whisper-1` | `gpt-4o-mini` | Jeder OpenAI-kompatible Server, z. B. `https://api.groq.com/openai/v1` oder `http://192.168.1.10:8000`. Schlüssel optional; unverschlüsseltes http ist für dein eigenes Netz erlaubt. |

Transkription und Glättung wählen ihren Anbieter getrennt. Modelle, die `temperature` ablehnen (die Reasoning-Modelle von OpenAI), werden ohne diesen Parameter erneut angefragt.

## Warum braucht die App die Bedienungshilfe?

Phone Whisper nutzt den Android-Bedienungshilfendienst, um diktierten Text appübergreifend ins fokussierte Textfeld einzufügen, und um zu sehen, ob eine Tastatur offen ist, damit der Button nur dann erscheint.

Er ersetzt **nicht** deine Tastatur, führt **keine** Automatisierung im Hintergrund aus und protokolliert **keine** Inhalte von Textfeldern. Er wird erst aktiv, wenn du auf den Button tippst.

## Datenschutz

- **Lokaler Modus**: Die Aufnahme bleibt auf dem Gerät, auch wenn das Modell fehlt oder noch lädt.
- **Cloud-Modus**: Die Aufnahme geht direkt von deinem Gerät an den gewählten Anbieter, mit deinem eigenen API-Schlüssel. Wird keine Sprache erkannt, wird nichts gesendet, außer du tippst auf „Wiederholen“.
- **Glättung**: Das Transkript geht direkt an die Chat-API des gewählten Anbieters.

Es gibt keinen eigenen Server. Vollständige Datenschutzerklärung: [PRIVACY.de.md](PRIVACY.de.md) ([English](PRIVACY.md))

## Kompatibilität

Phone Whisper funktioniert am besten in Apps mit normalen Android-Textfeldern. Manche Apps nutzen eigene Eingabeflächen oder blockieren das Einfügen; dann bleibt das Transkript in der Zwischenablage, und du fügst es selbst ein.

**Termux:** Der Terminalbereich ist kein normales Textfeld. Wische über die Zusatztastenleiste (`ESC`, `CTRL`, `ALT`, …), um zum Texteingabefeld von Termux zu wechseln, und diktiere dort.

**Xiaomi und andere Handys mit aggressivem Akkusparen:** Meldet der Status, der Bedienungshilfendienst sei „on, but not running“, schalte ihn aus und wieder ein und erlaube für Phone Whisper **Autostart** und **keine Akku-Einschränkungen**.

## Entwicklung

```bash
make build             # Debug-APK
make test              # Unit-Tests
make integration-test MODELS=/dir WAV=en.wav [WAV_DE=de.wav]   # alle Katalogmodelle über die echte sherpa-onnx-JNI (Linux x64)
make adb-install       # bauen und per ADB installieren
make clean
```

CI führt bei jedem Push die Unit-Tests aus und baut die APK. Ein Tag wie `v1.0.0` baut eine Release-APK und hängt sie an ein GitHub-Release (Pre-Release bei Tags mit `-`). Für eine feste Signatur trägst du die in `.github/workflows/release.yml` beschriebenen Secrets ein. [CLAUDE.md](CLAUDE.md) beschreibt die Architektur, [CHANGELOG.md](CHANGELOG.md) die Änderungen je Version, und [CONTRIBUTING.md](CONTRIBUTING.md) erklärt, wie du mithelfen kannst.

## Hilf beim Übersetzen

Die App ist bisher nur auf Englisch, und ihre Texte stehen noch direkt im Kotlin-Code. **Übersetzerinnen, Übersetzer und Android-Entwickler sind sehr willkommen:** Der erste Schritt ist, die Texte nach `res/values/strings.xml` zu verschieben; danach ist jede Sprache nur noch eine weitere Datei. Die Dokumentation gibt es bereits auf Englisch und Deutsch. Wie du anfängst, steht in [CONTRIBUTING.md](CONTRIBUTING.md#translations).

## Bekannte Einschränkungen

- Für das appübergreifende Einfügen ist die Bedienungshilfen-Berechtigung nötig
- Manche Apps blockieren Einfügen oder Texteingabe von außen oder nutzen eigene Eingabeflächen
- Gute lokale Modelle sind groß (130–670 MB)
- Der Cloud-Modus braucht einen eigenen API-Schlüssel (oder einen eigenen Server)
- Die App-Oberfläche ist nur auf Englisch (siehe [Hilf beim Übersetzen](#hilf-beim-übersetzen))

## Credits

**Ursprünglicher Autor**
- **Pol Alvarez** ([@kafkasl](https://github.com/kafkasl)) hat Phone Whisper erschaffen: die Idee, den schwebenden Push-to-talk-Button, das Einfügen über die Bedienungshilfe, die lokale Transkription mit sherpa-onnx, den Modell-Downloader, die Glättung mit Prompt-Vorlagen, die Einstellungsoberfläche, das Logo und die Website. Wenn dir die App Zeit spart, [unterstütze ihn](https://github.com/sponsors/kafkasl).

**Ideen aus Pull Requests an das Original-Repository**

Keiner dieser Pull Requests wurde hier als Code übernommen. Wir haben ihre Ideen aufgegriffen und auf unsere Weise umgesetzt; den Autoren gebührt der Dank für die Ideen und die Probleme, die sie gefunden haben.

| Mitwirkende | Pull Requests | Was wir von ihnen übernommen haben |
|---|---|---|
| **Renat** | [#8](https://github.com/kafkasl/phone-whisper/pull/8), [#9](https://github.com/kafkasl/phone-whisper/pull/9), [#10](https://github.com/kafkasl/phone-whisper/pull/10), [#11](https://github.com/kafkasl/phone-whisper/pull/11), [#12](https://github.com/kafkasl/phone-whisper/pull/12), [#13](https://github.com/kafkasl/phone-whisper/pull/13) | Button nur bei offener Tastatur; eigene Transkriptions-API-Adresse; Einfügen ohne Zwischenablage; Platzhaltertexte in Web-Editoren; Pegelanzeige und Abbruch bei ausgeschaltetem Bildschirm; Wiederholen-Zustand mit verständlichen Fehlermeldungen |
| **Maxime Borges** | [#19](https://github.com/kafkasl/phone-whisper/pull/19)–[#25](https://github.com/kafkasl/phone-whisper/pull/25) | Direkte Einzeldatei-Downloads von Hugging Face statt langsamer bz2-Archive; Downloads in einem Vordergrunddienst; die Erkenntnis, dass sherpa-onnx' Whisper-Standard ins Englische übersetzt; korrekte Modellgrößen; direktes Einfügen bei offener Tastatur und Telegrams als Text gemeldeter Platzhalter |
| **ArtificialTruth** | [#16](https://github.com/kafkasl/phone-whisper/pull/16) | Lokales Modell nach Inaktivität freigeben und bei Bedarf neu laden |
| **Luc Brinkman** ([@lucbrinkman](https://github.com/lucbrinkman)) | [#14](https://github.com/kafkasl/phone-whisper/pull/14) | Hat eine Open-Source-Lizenz vorgeschlagen; das Original-Repository steht unter Apache 2.0 |

**Dieser Fork: evilknivel** ([@evilknivel](https://github.com/evilknivel)), umgesetzt mit [Claude Code](https://claude.ai/code). Jede Idee in diesem Fork, die oben nicht anderen zugeschrieben ist, stammt von evilknivel, darunter:
- OpenRouter und eigene Server als Anbieter, die Modellauswahl aus der aktuellen Liste, getrennte Anbieter für Transkription und Glättung
- Parakeet Ultra und die mehrsprachigen Whisper-Modelle, die Spracheinstellung, eine Glättung, die nie übersetzt
- der Einrichtungsassistent, die Statuszeile, die sagt, was fehlt, die Einstellungen auf Unterseiten, die geschützte Eingabe der API-Schlüssel
- Halten zum Diktieren mit KI-Glättung, der Halte-Ring in der Farbe des Modus, unterschiedliche Vibrationen, der leuchtende Bildschirmrand, Meldungen über dem Daumen, das ✕ zum Verwerfen
- Übersetzen durch Schieben auf eine Sprache im Halbkreis, die Offline-Übersetzung ins Englische mit Whisper, die Abkürzungen + und ⚙ und die Kachel in den Schnelleinstellungen
- die Dokumentation auf Englisch und Deutsch und die 1.x-Releases

**Verwendete Software und Modelle**

| Komponente | Urheber | Lizenz |
|---|---|---|
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Spracherkennung, Kotlin-API) | k2-fsa / Next-gen Kaldi | Apache 2.0 |
| [Silero VAD](https://github.com/snakers4/silero-vad) (mitgeliefert, Stille abschneiden) | Silero Team | MIT |
| Parakeet 110M, Parakeet 0.6B v3 | NVIDIA | CC BY 4.0 |
| [Parakeet Ultra](https://huggingface.co/moondream/parakeet-ultra) | Moondream (auf Basis von NVIDIA Parakeet) | CC BY 4.0 |
| [Parakeet Ultra int8 für sherpa-onnx](https://huggingface.co/mldecode/parakeet-ultra-onnx-int8) | mldecode | CC BY 4.0 |
| Whisper Base, Whisper Small | OpenAI | Apache 2.0 (Modellgewichte), MIT (Code) |
| Moonshine Tiny | Useful Sensors | MIT |
| sherpa-onnx-Modellkonvertierungen auf Hugging Face | Fangjun Kuang ([csukuangfj](https://huggingface.co/csukuangfj)) | wie die Originalmodelle |
| OkHttp, AndroidX, Material Components, Apache Commons Compress | Square, Google, Apache Software Foundation | Apache 2.0 |

Die Modelle werden auf deinen Wunsch von ihren Herausgebern geladen und sind nicht Teil dieses Repositorys.

## Lizenz

Apache License 2.0, siehe [LICENSE](LICENSE). Urheberrecht am ursprünglichen Code: Pol Alvarez. Der Name „Phone Whisper“ und das Logo gehören dem ursprünglichen Autor; die Apache-Lizenz gewährt keine Markenrechte.

## Entwicklungsgeschichte

**Ursprüngliche App (März–August 2026, Pol Alvarez, [@kafkasl](https://github.com/kafkasl))**
- MVP: schwebender Button, Aufnahme, Transkription mit OpenAI Whisper und Einfügen über die Bedienungshilfe
- Lokale Transkription mit sherpa-onnx und ein Modell-Downloader (Parakeet, Whisper, Moonshine)
- Material-3-Einstellungsseite, Glättung mit OpenAI-Chatmodellen und Prompt-Vorlagen, besseres Ausweichen beim Einfügen, App-Icon, GitHub-Pages-Website, Lizenz

**Dieser Fork (Oktober 2026, mit Claude Code)**
1. **Grundlagen**: CLAUDE.md, die nativen sherpa-onnx-Bibliotheken gepinnt und eingecheckt (`make jni-libs`)
2. **OpenRouter**: getrennte Anbieter- und Modellwahl für Transkription und Glättung, mit aktueller Modellliste
3. **Parakeet Ultra**: Unterstützung für Modelle als Einzeldateien mit SHA-256-Prüfung; Ultra ergänzt und mit echten Aufnahmen geprüft
4. **Releases und CI**: Release-Workflow bei Tags, CI bei jedem Push, Signierung über Secrets
5. **Werkzeug-Update**: AGP 9, Gradle 9, Kotlin 2.4, targetSdk 36, sherpa-onnx 1.13.8 (native Bibliotheken und Kotlin-API neu übernommen)
6. **Korrekturen aus dem Review**: Der lokale Modus schickt keine Aufnahmen mehr in die Cloud, wenn das Modell nicht bereit ist; alte Modelle werden freigegeben; ein belegtes Mikrofon lässt den Dienst nicht mehr abstürzen; längere Netzwerk-Timeouts; Text nur noch ins fokussierte Feld; abgebrochene Installationen gelten nicht als installiert; Modelle lassen sich löschen; Whisper Base lädt wirklich; OpenAI-Modellwahl; Einstellung für CPU-Threads; geschützte Eingabe der API-Schlüssel
7. **Statusanzeige**, die nennt, was fehlt, und einen eingeschalteten, aber nicht laufenden Dienst erkennt
8. **Funktionen aus offenen Pull Requests des Original-Repos**, auf eigene Weise umgesetzt (siehe [Credits](#credits)): Button nur mit Tastatur, Einfügen ohne Zwischenablage, Pegelbalken, Abbruch bei ausgeschaltetem Bildschirm, Wiederholen-Zustand mit verständlichen Fehlern und eigene API-Adresse (Ideen von Renat); Hintergrund- und Direkt-Downloads von Hugging Face und die Whisper-Sprach-Erkenntnis (Maxime Borges); Modell im Leerlauf freigeben (ArtificialTruth). Dazu Stille abschneiden (VAD), Sprachangaben, Aufräumen ungenutzter Modelldateien, ein Glättungs-Prompt, der die Sprache beibehält, Datenschutz-Korrekturen, Vibration, Mikrofonquelle und Einstellungen auf Unterseiten
9. **Sprachen**: Spracheinstellung für Whisper und die Cloud, mehrsprachiges Whisper Base und Whisper Small (Whisper hatte mit der sherpa-onnx-Voreinstellung Deutsch ins Englische übersetzt), vollständige Satzenden bei Whisper Base
10. **Dokumentation und Credits**: vollständige README, Website und Datenschutzerklärung; Credits für den ursprünglichen Autor und die Pull-Request-Autoren
11. **Einrichtungsassistent und Halten für Glättung**: ein Assistent beim ersten Start für Berechtigungen, Modus, Modell oder Anbieter, Glättung und die Bedienungshilfe (mit dem Hinweis, den Google Play verlangt); Halten des Buttons diktiert mit KI-Glättung
12. **Rückmeldung unter dem Daumen**: ein Halte-Ring, größer als der Button, der in der Farbe des Modus wächst; unterschiedliche Vibrationen; ein ✕ neben dem orangen Button zum Verwerfen; ein optional leuchtender Bildschirmrand während der Aufnahme; Meldungen über dem Daumen
13. **Version 1.0.0**: Dokumentation auf Englisch und Deutsch, Changelog, Leitfaden zum Mitmachen mit Aufruf an Übersetzer, eine About-Zeile mit der Version und das erste Release mit dem Release-Schlüssel dieses Forks
14. **Übersetzen**: Button halten und auf eine Zielsprache im Halbkreis schieben; das Glättungsmodell übersetzt, ohne Anbieter Whisper offline ins Englische; + und ⚙ im Halbkreis sowie eine Kachel in den Schnelleinstellungen öffnen die Einstellungen
15. **Version 1.1.0**: Übersetzen und Einstellungs-Abkürzungen als stabiles Release und eine eigene App-ID dieses Forks, `com.evilknivel.phonewhisper`

## Unterstützen

Wenn dir Phone Whisper Tipparbeit spart, kannst du mir einen Kaffee für das Hirnschmalz spendieren, das in diesen Fork geflossen ist:

[![Buy me a coffee](https://img.shields.io/badge/Buy%20me%20a%20coffee-fmholzo-FFDD00?logo=buymeacoffee&logoColor=black)](https://buymeacoffee.com/fmholzo)
