# Tresor – verschlüsselter Foto- und Datei-Tresor für Android

Kotlin · Jetpack Compose · minSdk 26 (Android 8.0) · targetSdk 35
Paket: `ch.eshopit.tresor` · eShop IT Solutions GmbH

## Funktionen

- **Verschlüsseln**: Bilder, Videos und beliebige Dateien über die Android-Dateiauswahl in den Tresor aufnehmen (Mehrfachauswahl).
- **Entschlüsseln**: Element als Klartext an einem frei wählbaren Ort speichern („Entschlüsseln & speichern“) oder kurzzeitig mit einer anderen App öffnen („Öffnen mit …“).
- **Ganze Ordner**: „Verschlüsseln → Ganzer Ordner“ übernimmt alle Dateien inkl. Unterordner (auch leere) und legt im Tresor einen Ordner an, durch den man wie im Datei-Explorer navigiert. Ist „Originale löschen“ aktiv und wurde alles übernommen, wird der Originalordner komplett gelöscht.
- **Wiederherstellen**: Ordner gedrückt halten bzw. ⋮ im Ordner → „Wiederherstellen“. Am Originalort entstehen wieder derselbe Ordner, dieselben Unterordner, Dateinamen und Änderungsdaten (benötigt die Berechtigung „Zugriff auf alle Dateien“, die Tresor bei Bedarf anfragt). Alternativ einen anderen Zielordner wählen. Optional danach aus dem Tresor entfernen.
- **Bildbetrachter** direkt in der App (Zoom mit zwei Fingern, Doppeltipp), verschlüsselte Vorschaubilder im Raster.
- **App-Sperre** mit Code (6–12 Ziffern) und optional **Fingerabdruck** (Android BiometricPrompt, Klasse „stark“).
- Automatische Sperre, sobald die App in den Hintergrund geht; Screenshots und App-Übersicht-Vorschau sind blockiert (`FLAG_SECURE`).
- Wartezeit nach 5 Fehlversuchen (30 s, verdoppelt sich bis max. 1 h).
- Optional: Originaldatei nach dem Verschlüsseln löschen.
- Kein Cloud-Backup der Tresor-Daten (`allowBackup=false`, Data-Extraction-Rules).

## App-Sperre (andere Apps sperren)

Im Tresor oben auf das Raster-Symbol **App-Sperre** tippen und die gewünschten Apps einschalten
(z. B. WhatsApp, Galerie, Banking, Einstellungen). Beim Öffnen einer gesperrten App erscheint
der Tresor-Sperrbildschirm – entsperren mit demselben Code oder Fingerabdruck.

- Einmalig nötig: Android-Einstellungen → **Bedienungshilfen** → „Tresor App-Sperre“ aktivieren.
  Android bietet für App-Sperren keine andere zuverlässige Schnittstelle. Der Dienst liest nur
  Paket- und Activity-Namen des Fensters im Vordergrund, keine Inhalte.
- Android 13+: Ist der Schalter ausgegraut, unter *Apps → Tresor → ⋮* zuerst
  „Eingeschränkte Einstellungen zulassen“ wählen.
- Eine entsperrte App bleibt offen, bis man sie verlässt (Home, andere App) oder der Bildschirm ausgeht.
- Grenzen: Wer Zugriff auf die Android-Einstellungen hat, kann den Dienst abschalten – deshalb
  „Einstellungen“ ebenfalls sperren. Bei Veröffentlichung im Play Store verlangt Google für die
  Nutzung der Bedienungshilfen-API eine Begründung (Accessibility-Deklaration).

## APK von GitHub herunterladen (ohne Android Studio)

Jeder Push auf `main` baut die App automatisch (Reiter **Actions** → letzter Lauf → *Tresor-APK*).
Für eine Version mit Download-Seite ein Tag setzen, z. B. `v1.1` → die APK erscheint unter **Releases**.

Installation auf dem Tablet: APK herunterladen → öffnen → „Installation aus dieser Quelle zulassen“.
Alle Builds sind mit demselben Schlüssel signiert (Repository-Secrets `TRESOR_KEYSTORE_BASE64` und `TRESOR_KEYSTORE_PASSWORD`),
daher lassen sich Updates einfach über die bestehende Version installieren – die Tresor-Daten bleiben erhalten.

## Projekt öffnen und bauen

1. Ordner `Tresor` in **Android Studio** (Ladybug oder neuer) öffnen – Gradle-Sync lädt alle Abhängigkeiten.
2. Gerät oder Emulator wählen, **Run ▶**.
3. Release-APK: *Build → Generate Signed App Bundle / APK*.

Kommandozeile: `./gradlew assembleDebug` · Unit-Tests: `./gradlew test`

> Im Emulator lässt sich ein Fingerabdruck unter *Einstellungen → Sicherheit* einrichten und über
> *Extended Controls → Fingerprint* „berühren“.

## Sicherheitskonzept

```
Code ──PBKDF2-HMAC-SHA256 (150 000 Iter.)──► KEK ─┐
                                                  ├─AES-GCM─► verpackter Master-Key ─► Keystore-Geräteschlüssel (AES-GCM)
Master-Key (256 Bit, zufällig) ───────────────────┘
Master-Key ──AES-GCM──► Keystore-Schlüssel mit Biometrie-Pflicht   (optional)
Master-Key ──AES-256-GCM (64-KiB-Blöcke)──► Dateien, Metadaten, Vorschaubilder
```

- Der **Master-Key** existiert nur im Arbeitsspeicher, solange der Tresor offen ist, und wird beim Sperren überschrieben.
- Der mit dem Code verpackte Schlüssel ist zusätzlich mit einem nicht exportierbaren **Android-Keystore-Schlüssel** verschlüsselt. Ein Code lässt sich daher nicht offline auf einem anderen Rechner durchprobieren.
- Der **Biometrie-Schlüssel** im Keystore wird nur nach erfolgreichem Fingerabdruck freigegeben und wird ungültig, wenn neue Fingerabdrücke registriert werden. Dann muss die Biometrie mit dem Code neu aktiviert werden.
- **Dateiformat** (`FileCrypto`): Header `TRV1` + 8-Byte-Nonce-Präfix; jeder 64-KiB-Block ist einzeln mit AES-GCM authentifiziert. Blockzähler, Letzter-Block-Flag und Element-ID fliessen in die AAD ein. Damit fallen Manipulation, Kürzen und Vertauschen von Dateien auf. Grosse Dateien werden gestreamt, nicht komplett in den Speicher geladen.
- Dateinamen im Speicher sind zufällige UUIDs. Name, Typ und Grösse liegen verschlüsselt in `<id>.meta`.

### Grenzen, die man kennen sollte

- **Kein Code = keine Daten.** Es gibt bewusst keine Wiederherstellung.
- **Deinstallation löscht den Tresor** (Daten liegen im privaten App-Speicher, Schlüssel im Keystore). Wichtige Dateien vorher entschlüsseln.
- „Öffnen mit …“ legt kurzzeitig eine Klartext-Kopie im App-Cache ab. Die Kopie wird beim nächsten Sperren gelöscht. Die fremde App kann die Datei während dieser Zeit lesen.
- Das Löschen von Originalen funktioniert nur bei Speicherorten, die das erlauben (z. B. Dateien-App, Downloads). Auf Flash-Speicher ist ein spurloses Überschreiben technisch nicht garantiert.
- Der Code wird im Arbeitsspeicher kurz als `String` gehalten (Compose-Eingabe) und lässt sich nicht gezielt überschreiben.

## Aufbau

```
app/src/main/java/ch/eshopit/tresor/
├── TresorApp.kt              Auto-Sperre (ProcessLifecycleOwner)
├── crypto/
│   ├── Crypto.kt             AES-GCM-Helfer
│   ├── FileCrypto.kt         Streaming-Dateiverschlüsselung
│   └── KeyManager.kt         Code, Keystore, Biometrie, Fehlversuche
├── vault/
│   ├── VaultSession.kt       Schlüssel im Speicher, Sperrstatus
│   └── VaultRepository.kt    Import, Export, Vorschaubilder, Löschen
├── applock/
│   ├── AppLockService.kt     Bedienungshilfe-Dienst, erkennt geöffnete Apps
│   ├── AppLockActivity.kt    Sperrbildschirm über gesperrten Apps
│   └── AppLockStore.kt       gesperrte Apps, App-Liste
└── ui/
    ├── MainActivity.kt       FLAG_SECURE, BiometricPrompt
    ├── AppRoot.kt            Navigation, Dateiauswahl, Speichern-Dialog
    ├── AppViewModel.kt
    ├── BiometricHelper.kt
    ├── theme/Theme.kt
    └── screens/              Einrichtung, Sperre, Tresor, Bildbetrachter, Einstellungen
```

## Ideen für Erweiterungen

- Teilen-Ziel („Senden an Tresor“) direkt aus Galerie oder anderen Apps
- Alben/Ordner und Suche
- Verschlüsseltes Backup-Export (Datei mit eigenem Passwort) für Gerätewechsel
- Video-Wiedergabe in der App über einen entschlüsselnden `DataSource` (Media3)
