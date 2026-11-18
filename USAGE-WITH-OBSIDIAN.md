# KYN mit Obsidian nutzen

Der Vault ist ein normaler Obsidian-Vault in einem privaten GitHub-Repo. Am Mac liegt er in einem beliebigen Ordner (in [SETUP-MAC.md](SETUP-MAC.md): `~/vault`), auf dem Handy je nach Einstellung in `Documents/Vault` oder privat in der App. Abgeglichen wird über GitHub: am Mac mit dem Obsidian-Plugin **Git**, am Handy mit der App.

Einrichtung von Obsidian, Git-Plugin und SSH-Schlüssel: siehe [SETUP-MAC.md](SETUP-MAC.md).

## Frontmatter neuer Notizen

Die App legt neue Notizen so an:

```markdown
---
created: 2026-11-18T08:46
---
```

Damit Notizen vom Mac genauso aussehen, gibt es im Vault zwei Vorlagen in `templates/`:

| Datei | Für |
| --- | --- |
| `templates/Neue Notiz (Templater).md` | Variante A, automatisch bei jeder neuen Notiz |
| `templates/Neue Notiz (Vorlagen).md` | Variante B, per Tastenkürzel, ohne Community-Plugin |

Beide setzen:

- `created`: Datum und Uhrzeit im Format `YYYY-MM-DDTHH:mm`, wie die App.
- `tags: []`: leer. Das abendliche Inbox-Sortieren ergänzt Tags; von Hand gesetzte Tags bleiben.

Andere Felder (`aliases`, `pinned: true`, `due` …) nur bei Bedarf über die Eigenschaften der Notiz ergänzen. `import_quelle` stammt aus dem einmaligen Import und gehört nicht in neue Notizen.

Den Ordner `templates/` im Vault-Stamm blendet die App aus: Er erscheint nicht in der Notizliste, der Suche, den Widgets oder der Ordnerauswahl. Das Inbox-Sortieren fasst ihn ebenfalls nicht an (es arbeitet nur in `inbox/`).

Nur eine Variante einrichten. Die andere Vorlagendatei kann liegen bleiben oder gelöscht werden.

## Variante A: automatisch mit Templater

Jede neue Notiz bekommt das Frontmatter sofort, ohne Zutun.

1. **Templater installieren**
   Obsidian → Einstellungen → Community-Plugins → Durchsuchen → „Templater“ → Installieren → Aktivieren.
   (Ist der eingeschränkte Modus noch an, zuerst „Eingeschränkten Modus ausschalten“.)

2. **Vorlagenordner festlegen**
   Einstellungen → Templater → *Template folder location*: `templates`.

3. **Bei neuen Dateien auslösen**
   Einstellungen → Templater → *Trigger Templater on new file creation*: einschalten.

4. **Vorlage für den ganzen Vault**
   Einstellungen → Templater → *Folder templates*: einschalten → „Add new folder template“ →
   Ordner `/`, Vorlage `templates/Neue Notiz (Templater).md`.

5. **Neue Notizen in die Inbox**
   Einstellungen → Dateien und Links → *Standardort für neue Notizen* → „Im unten angegebenen Ordner“ → `inbox`.
   So landen Mac-Notizen wie die vom Handy in `inbox/` und werden abends einsortiert.

6. **`created` als Datum anzeigen**
   In einer Notiz bei der Eigenschaft `created` auf das Symbol links klicken → Typ „Datum & Uhrzeit“.
   Obsidian merkt sich das für den ganzen Vault und zeigt danach einen Kalender.

7. **Testen**
   Cmd+N. Die Notiz liegt in `inbox/`, `created` hat die aktuelle Zeit, der Cursor steht unter dem Frontmatter.

8. **Nach dem nächsten Git-Pull prüfen**
   Eine Notiz öffnen, die vom Handy kam. Sie darf kein zweites Frontmatter bekommen haben.
   Falls doch: in Schritt 4 den Ordner `/` durch `inbox` ersetzen und die betroffene Notiz von Hand bereinigen.

## Variante B: per Tastenkürzel mit dem Core-Plugin „Vorlagen“

Kein Community-Plugin nötig. Dafür fügst du die Vorlage selbst ein.

1. **Plugin prüfen**
   Einstellungen → Core-Plugins → „Vorlagen“ ist eingeschaltet (im Vault bereits an).

2. **Vorlagenordner festlegen**
   Einstellungen → Vorlagen → *Speicherort für Vorlagen*: `templates`.

3. **Tastenkürzel vergeben**
   Einstellungen → Tastenkürzel → nach „Vorlage einfügen“ suchen → `+` → ein freies Kürzel drücken, z. B. Ctrl+Cmd+N.

4. **Neue Notizen in die Inbox**
   Wie Variante A, Schritt 5.

5. **`created` als Datum anzeigen**
   Wie Variante A, Schritt 6.

6. **Benutzen**
   Neue Notiz (Cmd+N) → Kürzel aus Schritt 3 → „Neue Notiz (Vorlagen)“ wählen.
   Hat die Notiz schon Eigenschaften, ergänzt Obsidian die fehlenden Felder aus der Vorlage.

## Wenn etwas nicht passt

- **Vorlage taucht in der Handy-App auf:** Der Ordner muss genau `templates` heißen und direkt im Vault-Stamm liegen. Unterordner wie `wissen/templates` zeigt die App absichtlich an.
- **`created` steht als Text da, z. B. `{{date:…}}` oder `<% … %>`:** Die Vorlage wurde nicht von Obsidian ausgefüllt, sondern als Datei kopiert. Variante A: Templater ist aus oder der Folder template fehlt. Variante B: Vorlage über das Kürzel einfügen, nicht die Datei duplizieren.
- **Andere Felder gewünscht:** Vorlage in `templates/` anpassen. Die App liest jedes Feld und zeigt es unter „Eigenschaften“; Datumsfelder sollten das Format `YYYY-MM-DD` haben.
