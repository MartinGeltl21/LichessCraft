# LichessCraft 0.1.0

Paper-Plugin für ein Schachbrett in der Minecraft-Welt. Menschen bewegen ihre Figuren per Rechtsklick; Lichess führt die echte Onlinepartie und speichert das Ergebnis im jeweiligen Account.

[Release v0.1.0](https://github.com/MartinGeltl21/LichessCraft/releases/tag/v0.1.0) · [JAR herunterladen](https://github.com/MartinGeltl21/LichessCraft/releases/download/v0.1.0/LichessCraft-0.1.0.jar)

Erster spielbarer Prototyp für Paper 26.2 / Java 25. Ein Brett, Standard-Schach aus der Startstellung, Browser-Anmeldung, Live-Spielstand, Uhren, Umwandlung, Remis und Aufgabe. Eigene Herausforderungen sind ungewertet und verwenden 15+10. Bestehende Standard-Partien können über die Board API geöffnet werden, soweit Lichess die Bedenkzeit unterstützt. Keine Zugvorschläge, Bewertung oder Schachengine. Lichess prüft die gesendeten Züge.

## Installation

1. Gebaute `LichessCraft-0.1.0.jar` in das vorhandene `plugins`-Verzeichnis kopieren und Paper neu starten.
2. In `plugins/LichessCraft/config.yml` unter `oauth.public-url` eine eigene HTTPS-Adresse eintragen.
3. Den vorhandenen HTTPS-Reverse-Proxy auf den internen HTTP-Port 8765 des Minecraft-Containers richten. `oauth.bind: 0.0.0.0` ist für Containerbetrieb vorgesehen. Port 8765 braucht kein öffentliches Host-Portmapping.
4. Erneut starten. HTTPS-Adresse aufrufen: Die Antwort muss „LichessCraft läuft“ enthalten.
5. Im Spiel `/schach verbinden`, Link anklicken, bei Lichess anmelden und Rechte erlauben. Browser zeigt Accountname und einen einmaligen Code; in Minecraft `/schach bestaetigen <Code>` eingeben. Der Account wird erst nach dieser Bestätigung gespeichert.

Lichess-Passwörter werden nie vom Plugin abgefragt. OAuth verwendet Authorization Code mit PKCE und zufälligem state. Tokens liegen AES-GCM-verschlüsselt in `plugins/LichessCraft/private/accounts.enc`; der Schlüssel bleibt als `server.key` auf demselben Server. Beide Dateien gehören zu vertraulichen Server-Backups und dürfen nicht veröffentlicht werden. Linux-Dateirechte werden auf 0600, das private Verzeichnis auf 0700 gesetzt. Windows verwendet die vorhandenen ACLs.

`/schach trennen` löscht die lokale Zuordnung. Ein bereits erteiltes Lichess-App-Token lässt sich unter https://lichess.org/account/oauth/token widerrufen. Während einer aktiv angezeigten eigenen Partie lässt das Plugin keinen Accountwechsel zu.

## Brett aufstellen und spielen

- Als OP auf eine freie Fläche stellen und `/schach brett` ausführen. Benötigt 32×32×5 freie Blöcke ab der Fußposition (Feldgröße 4). Das Brett beginnt bei dir und erstreckt sich nach +X und +Z. Keine vorhandenen Blöcke werden überschrieben. Der bestehende Boden unter deinen Füßen bleibt erhalten.
- Weiße Figuren sind hell, schwarze dunkel; Typen stehen darüber. Felder tragen Koordinaten. Rechtsklick auf eine eigene Figur, dann auf das Zielfeld. Bei Umwandlung die gewünschte Figur im Chat anklicken.
- `/schach herausfordern <Lichess-Name>` sendet eine ungewertete 15+10-Einladung. Die andere Person muss innerhalb von 20 Sekunden annehmen; der Befehl zeigt einen Link zur Herausforderung. Alternativ auf Lichess selbst eine passende Partie starten.
- `/schach partien` zeigt laufende Partie-IDs; `/schach spielen <ID>` öffnet eine davon auf dem Brett. Die Gegenseite kann im Browser oder mit eigenem verbundenem Minecraft-Account spielen.
- Zuschauer dürfen zusehen, aber keine fremden Figuren bewegen.
- `/schach aufgeben ja` gibt auf. `/schach remis ja` bietet Remis an oder nimmt ein vorhandenes Angebot an.
- `/schach freigeben ja` (Admin) beendet nur die lokale Anzeige. Eine laufende Partie bleibt auf Lichess aktiv und muss dort weitergespielt oder beendet werden.
- `/schach entfernen` (Admin) entfernt die Figuren und den vom Plugin erzeugten Brettboden; während einer laufenden Partie zuerst beenden oder die Anzeige bewusst freigeben.

## Befehle und Rechte

| Befehl | Zweck |
| --- | --- |
| `/schach hilfe` | Hilfe |
| `/schach status` | OAuth, Brett, Partie und eigener Account |
| `/schach verbinden` | Neue Browser-Anmeldung |
| `/schach bestaetigen <Code>` | Angezeigten Account mit der eigenen Minecraft-UUID verbinden |
| `/schach trennen` | Lokale Verknüpfung löschen |
| `/schach partien` | Eigene laufende Partien anzeigen |
| `/schach herausfordern <Name>` | Ungewertete 15+10-Partie anbieten |
| `/schach annehmen <ID>` | Herausforderung annehmen |
| `/schach spielen <ID>` | Eigene Partie auf dem Brett anzeigen |
| `/schach zug <UCI>` | Zug senden, z. B. e2e4 oder e7e8n |
| `/schach umwandeln q\|r\|b\|n` | Dame, Turm, Läufer oder Springer wählen |
| `/schach aufgeben ja` | Partie aufgeben |
| `/schach remis ja` | Remis anbieten oder annehmen |
| `/schach brett` | Brett erstellen, Admin |
| `/schach entfernen` | Freies Brett entfernen, Admin |
| `/schach freigeben ja` | Anzeige lösen, Admin; Onlinepartie läuft weiter |

Alias: `/chess`. `lichesscraft.play` ist standardmäßig erlaubt. `lichesscraft.admin` ist standardmäßig OPs vorbehalten.

## Verhalten und Grenzen

- Genau ein Brett pro Server; keine Varianten, Chess960 oder beliebigen Startstellungen. Kein lokales Offline-Schach in dieser Version.
- Der Spielstand stammt immer aus Lichess. Fehlgeschlagene Züge verändern die Anzeige nicht. Bei unklarem HTTP-Zugstatus wird vor weiteren Zügen neu synchronisiert. Streams verbinden sich nach einem Ausfall automatisch wieder; die Lichess-Uhr läuft bei Netzwerkproblemen weiter.
- Bei einem Server-Neustart lädt das Plugin Brett und aktive Partie anhand der gespeicherten Zuordnung neu, wenn Account-Token und Welt verfügbar sind. Nach Tokenablauf erneut anmelden. `/reload` wird nicht unterstützt; regulären Neustart verwenden.
- Das Plugin zeigt Uhren am Brett, ohne die Actionbar anderer Plugins zu belegen. Die Uhranzeige wird lokal zwischen bestätigten Lichess-Updates fortgeschrieben.
- Nach einer Partie zeigt das Brett die Endstellung bis zur nächsten Partie; nach einem vollständigen Server-Neustart zeigt ein freies Brett wieder die Startstellung. Partieverlauf und Ergebnis bleiben auf Lichess.
- Der API-Client nutzt asynchrone Hintergrundarbeit, eine serielle Warteschlange für normale HTTP-Anfragen und bei HTTP 429 eine Sperre von mindestens 60 Sekunden. Eine Anfrage pro Spieler läuft gleichzeitig. Streams laufen separat.
- Brettboden ist gegen Abbau, Explosionen und Kolben geschützt. Figuren sind Display-Entities; eine Client-Mod und ein Ressourcenpaket sind nicht erforderlich.
- Lokale Prüfungen umfassen automatisierte OAuth-Tests mit einem simulierten Anbieter sowie echte Paper-Läufe mit simulierten Lichess-Antworten. Noch keine echte Account-Anmeldung, keine Onlinepartie und keine Sichtprüfung mit Minecraft-Client durchgeführt. Vor regulärer Nutzung eine ungewertete Partie testen.

## Entwicklung und Prüfungen

JDK 25 und Maven: `mvn clean verify`. Es werden 17 Unit-/HTTP-Tests ausgeführt und die installierbare JAR inklusive intern umbenanntem Gson gebaut.

Der separate `scripts/paper-smoke.ps1` startet zwei lokale Paper-Phasen. Er benötigt JDK, Maven und einen vorhandenen Paper-Laufzeitcache mit bereits akzeptierter Minecraft-EULA sowie ein frisches Testverzeichnis. Server lauscht nur auf 127.0.0.1:25589, Test-OAuth auf 127.0.0.1:18765. Ausschließlich künstliche Tokens und Konten werden für die simulierte Board API verwendet. Der Smoke-Test ersetzt den API-Client im Speicher, bevor er Testkonten aktiviert; echte Lichess-Accounts werden nicht verwendet.

## Quellen

- Lichess Board API: https://lichess.org/api#tag/Board
- OAuth: https://lichess.org/api#tag/OAuth
- Board-API-Bedenkzeiten: https://lichess.org/page/eboards
- Paper-Projektsetup: https://docs.papermc.io/paper/dev/project-setup/
- Coolify-Proxy und interne Service-Ports: https://coolify.io/docs/applications/builds/docker-compose
