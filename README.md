# Schematic Live (Fabric-Mod, nur Client)

Sendet deine Position live an den **Schematic-Loader** im Browser (Tab „📡 Live (Mod)“ bei „Wo stehst du?“).
Die Mod läuft nur auf deinem Rechner. Auf dem Server ist **nichts** nötig, deshalb funktioniert sie auf Paper (und jedem anderen Server).

**Stand:** Der Quelltext ist gegen die offizielle Fabric-Doku für 26.2 geschrieben und für 26.3 gedacht. Die Verbindung zwischen Mod-Server und Loader ist getestet, das Bauen gegen Minecraft 26.3 und der Einsatz im Spiel noch nicht. Kommt beim Bauen ein Fehler, einfach die Meldung weitergeben.

## Voraussetzungen
- Minecraft Java **26.3 oder neuer**
- Fabric Loader 0.19.5+ und **Fabric API 0.161.0+26.3** (oder neuer)
- Java 25 (liefert der Minecraft-Launcher mit)

## Jar bauen, ohne etwas zu installieren (GitHub)
1. Auf github.com ein neues Repository anlegen und den Inhalt dieses Ordners hochladen (inkl. `.github/`).
2. Reiter **Actions** öffnen, der Workflow „build“ startet automatisch (sonst „Run workflow“).
3. Fertigen Lauf öffnen, unten unter **Artifacts** `schematic-live-mod` herunterladen und entpacken.
4. `schematic-live-1.0.0.jar` in den `mods`-Ordner von Minecraft legen (zusammen mit Fabric API).

Lokal geht es auch: JDK 25 und Gradle 9.5.1 installieren, im Ordner `gradle build` ausführen, die Jar liegt dann in `build/libs/`.

## Benutzen
1. Minecraft starten, Welt oder Server betreten.
2. `/liveloader` eingeben. Minecraft zeigt den **Code** (6 Zeichen, zufällig erzeugt).
3. Im Loader bei „Wo stehst du?“ auf **📡 Live (Mod)** klicken, Code eingeben, **Verbinden**.

Der Code bleibt gespeichert (`config/schematic-live-code.txt`) und der Loader merkt ihn sich. Neuer Code: `/liveloader newcode`.

## Sicherheit und Datenschutz
- Der Mini-Server lauscht nur auf `127.0.0.1:47615`, aus dem Netzwerk ist er nicht erreichbar.
- Ohne den richtigen Code liefert er nichts. Falsche Versuche werden gebremst.
- Gesendet werden nur X, Y, Z, Blickrichtung und Dimension.
- Versteckt ein Server die F3-Koordinaten („reduzierte Debug-Infos“), sendet die Mod nichts (so gut es sich prüfen lässt).

## Hinweise
- Den Loader am besten als lokale Datei öffnen. Läuft er über https, blockieren manche Browser (Safari) die Verbindung zu `127.0.0.1`, und Chrome fragt eventuell nach Zugriff auf das lokale Netzwerk.
- „Port belegt“ im Minecraft-Log: ein anderes Programm nutzt 47615. Dann den Port in `LiveServer.java` und im Loader (`LV.base`) ändern.
- Neuere Minecraft-Versionen: `minecraft_version` und `fabric_version` in `gradle.properties` anpassen, neu bauen.
