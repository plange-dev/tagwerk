# Tagwerk – Kalender für Android und PC

Eigenständige Kalender-App mit eigenem Design.

## Funktionen
- Monats-, Wochen-, Tages- und Listenansicht, Kalenderwochen, deutsche Feiertage
- **Schnell eintragen** in normaler Sprache, z. B. `Morgen 15 Uhr Zahnarzt Hauptstraße 5, 10115 Berlin`
- Adressen (Straße + Hausnummer, PLZ + Ort) werden erkannt
- **Navigation:** Tippen auf das Pfeil-Symbol öffnet die Route in Google Maps (auf Android zusätzlich „Andere Navi-App“, z. B. Waze oder OsmAnd)
- Wiederkehrende Termine, eigene Kalender mit Farben, Suche, dunkles Design
- Funktioniert offline, Daten bleiben auf dem Gerät; Backup über *Optionen*

## Installieren

**Android:** Unter *Releases* die neueste `Tagwerk.apk` herunterladen und öffnen.
Android fragt einmalig, ob Apps aus dieser Quelle installiert werden dürfen.

**PC (Chrome/Edge):** Die GitHub-Pages-Seite öffnen und in der Adressleiste auf
„App installieren“ klicken. (Einmalig in den Repo-Einstellungen unter
*Settings → Pages → Source* „GitHub Actions“ auswählen.)

## Selbst bauen
```
npm ci
npx cap sync android
cd android && ./gradlew assembleDebug
```
Jeder Push auf `main` baut automatisch eine neue APK (GitHub Actions).

## Branches
- **`develop`** – Standard-Branch für neue Funktionen. Jeder Push baut eine Test-APK
  (unter *Actions* → letzter Lauf → *Artifacts*), aber kein Release.
- **`main`** – stabile Version. Was hier landet, wird automatisch als Release mit
  `Tagwerk.apk` veröffentlicht. Neue Funktionen kommen per Pull Request von `develop` nach `main`.
