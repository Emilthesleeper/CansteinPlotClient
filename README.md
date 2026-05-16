# CansteinPlotClient - Fabric Mod

Ein Minecraft Fabric Mod, der einen Webserver mit ModMenu-Konfiguration bietet.

## Features

### ModMenu Konfigurationsscreen
- Server-Adresse einstellen (Standard: `127.0.0.1`)
- Passwort konfigurieren (Standard: `password`)
- Start/Stop-Buttons für den Webserver

### Webserver Dashboard
- **Passwort-geschützt**: HTTP Basic Authentication
- **Hauptseite** (`/`): Dashboard mit Statistiken
- **Chat-Seite** (`/chat`): Alle empfangenen Chat-Nachrichten
- **Spieler-Seite** (`/players`): Liste der Online-Spieler
- **APIs**: JSON-Endpoints für Automatisierung

### Automatisches Tracking
- Chat-Nachrichten werden automatisch aufgezeichnet
- Online-Spieler werden verfolgt
- Bis zu 1000 Nachrichten im Speicher

## Installation & Build

### Voraussetzungen
- Java 21 (oder höher)
- (Optional) Gradle installiert

### Build-Prozess

**Option 1: Mit PowerShell-Script (empfohlen für Windows)**
```powershell
.\build.ps1
```

**Option 2: Mit Gradle-Wrapper (wenn vorhanden)**
```bash
./gradlew build
```

**Option 3: Manuell mit Java**
```bash
java -Dorg.gradle.appname=gradlew -classpath "gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain build
```

Das Build-Ergebnis findest du in: `build/libs/CansteinPlotClient-1.0.jar`

## Verwendung

1. **Mod installieren**: Kopiere die JAR-Datei in dein Mods-Verzeichnis
2. **Minecraft starten**: Der Mod wird automatisch geladen
3. **ModMenu öffnen**: Im Hauptmenü `Mod Menu` wählen
4. **CansteinPlot konfigurieren**: 
   - (Optional) Server-Adresse und Passwort anpassen
   - "Start Server" klicken
5. **Im Browser öffnen**:
   - Standard: `http://127.0.0.1:8080`
   - Mit den konfigurierten Anmeldedaten (Standard: `admin:password`)

## Webserver API

### Authentifizierung
Alle Endpoints erfordern HTTP Basic Authentication mit dem konfigurierten Passwort.

### Endpoints

| URL | Beschreibung |
|-----|-------------|
| `/` | Hauptdashboard |
| `/chat` | Chat-Nachrichten-Seite |
| `/players` | Online-Spieler-Seite |
| `/api/messages` | JSON-Liste aller Chat-Nachrichten |
| `/api/players` | JSON-Liste aller Online-Spieler |

### API-Beispiele

**Chat-Nachrichten abrufen:**
```bash
curl -u admin:password http://127.0.0.1:8080/api/messages
```

**Online-Spieler abrufen:**
```bash
curl -u admin:password http://127.0.0.1:8080/api/players
```

## Modding Details

### Eingangspunkte
- `main`: `com.emilsleeper.cansteinplotclient.Cansteinplotclient`
- `client`: `com.emilsleeper.cansteinplotclient.client.CansteinplotclientClient`
- `modmenu`: `com.emilsleeper.cansteinplotclient.menu.ModMenuImpl`

### Mixins
- `ChatMessageMixin`: Interceptiert Chat-Nachrichten
- `PlayerListMixin`: Verfolgt Online-Spieler

### Abhängigkeiten
- Fabric API
- Fabric Loader
- ModMenu (für Konfigurationsscreen)

## Troubleshooting

### "Build failed"
Stelle sicher, dass Java 21 installiert ist:
```bash
java -version
```

### Webserver startet nicht
- Überprüfe, ob der Port 8080 verfügbar ist
- Ändere die Serveradresse in den Einstellungen
- Prüfe die Java-Konsole auf Fehlermeldungen

### Passwort funktioniert nicht
- Verwende Standard-Anmeldedaten: `admin:password`
- Oder stelle neue Werte im ModMenu ein

## Entwicklung

Zum Entwickeln und Debuggen:

```bash
# Build mit Loom-Cache
./gradlew build

# Clean rebuild
./gradlew clean build

# Run-Umgebung starten (IDE muss konfiguriert sein)
./gradlew runClient
```

## Lizenz
All Rights Reserved - Alle Rechte vorbehalten

## Autor
Emil Sleeper

