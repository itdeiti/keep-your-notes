# Notizen

Small Android app that replaces Google Keep and keeps every note as a Markdown file in an Obsidian vault, synced with Git.

- Widgets: "Schnellnotiz" (quick note, checklist, dictation) and "Notizliste" (Keep-style list of notes, pinned first; tap a checkbox to tick it without opening the app, tap text to edit the note).
- List of the whole vault, newest first, pinned on top, search and tag filter: tag chips, "Tags…" opens a searchable tag list with counts, `#ko` in the search field filters by tags containing "ko".
- Editor with Markdown highlighting, toolbar, list continuation, read view with tappable checkboxes.
- Tasks with due date and reminder in the Obsidian Tasks/Reminder format:
  `- [ ] Zahnarzt anrufen 📅 2026-11-20 ⏰ 2026-11-20 09:00`
  A task with only `📅` reminds at the default time (09:00, configurable). "Erledigt" in the notification ticks the task (`✅ date`), commits and pushes.
- Web clipping into `Clippings/` (with `source:` in the frontmatter, like the Obsidian Web Clipper):
  - Share a page from the browser → the app downloads it, keeps the article (Readability, Firefox reader mode) and converts it to Markdown: headings, lists, links, quotes, code, tables, images as remote links.
  - Select text, then "Teilen" in Chrome → quote plus link back to the highlighted passage.
  - Select text in any app → "Als Notiz" in the selection menu.
- Properties ("Eigenschaften", collapsed under the title): every frontmatter field except `tags`/`pinned`, editable inline, × removes, "＋ Eigenschaft" adds text or date. Date fields (value starts with YYYY-MM-DD or key like `due`, `datum`, `created`) open a date picker and store `YYYY-MM-DD`; an existing time part (`created: 2026-11-17T14:32`) is kept. New notes get `created:` with date and time.
- Tags: chips under the title; × removes, ＋ picks an existing tag or creates a new one (`tags: [a, b]` in the frontmatter).
- Nightly inbox sorting on the Mac (see below).
- Git: saving commits right away (works offline), pushing happens as soon as there is network, the app pulls when opened and every 3 hours. A conflict keeps the server version and saves the phone version next to it as `… (Konflikt Handy <time>).md`.
- The repo lives in `Documents/<Ordner>` on shared storage, so Obsidian Android can open the same folder. Obsidian is optional.

## Setup

New or reset Mac: see [SETUP-MAC.md](SETUP-MAC.md) (tools, SSH, Ollama, LaunchAgent, phone). A ready-to-install APK is in `apk/notizen.apk` (`adb install -r apk/notizen.apk`).

### 1. GitHub

1. Create a private repo, e.g. `vault` (empty or with a README).
2. Create a fine-grained token: GitHub → Settings → Developer settings → Fine-grained tokens. Repository access: only `vault`. Permission: **Contents: Read and write**.

### 2. Mac (Obsidian desktop)

```bash
git clone https://github.com/<name>/vault.git ~/Vault
```

Open `~/Vault` as a vault in Obsidian, install the community plugin **Git** (Vinzent03) and set:

- Auto commit-and-sync interval: 5 minutes
- Pull on startup: on

Optional plugins that understand the same task format: **Tasks** (due lists) and **Reminder** (notifications on the Mac).

Put this in `.gitignore` (the app creates it as well when it clones an empty repo):

```
.obsidian/workspace*.json
.trash/
```

### 3. Phone

Build and install over USB (USB debugging on):

```bash
./gradlew installDebug
```

In the app: allow "Zugriff auf alle Dateien" and notifications, enter repo URL, token, folder name (default `Vault`), name and email for commits, then "Speichern & klonen".

Add the widget from the home screen widget list ("Schnellnotiz").

Optional: in Obsidian Android, "Open folder as vault" → `Documents/Vault`. Do **not** install the Git plugin there; the app does the syncing.

## Note method

The vault follows PARA (Tiago Forte): `projekte/`, `bereiche/`, `wissen/` (Resources), `archiv/`, plus `inbox/` as entry point and a fixed tag list for topics. Daily use, weekly review and how to extend it are described in the vault itself: `Notiz-Methode.md` (pinned, readable in the app).

## Nightly inbox sorting (Mac, local AI)

Every evening the Mac sorts new notes from `inbox/` into the vault folders and tags them. Everything runs locally; note content never leaves the machine.

### What a run does

`python3 tools/vault_import.py inbox <vault>`:

1. Commits pending local changes in the vault, then `git pull` (stops with a notification if the pull fails).
2. For each `inbox/*.md`:
   - skipped if changed in the last 60 minutes (avoids conflicts with the phone),
   - skipped if the model already left it in `inbox/` and it has not changed since (`.sortierung/geprueft.json`),
   - otherwise `qwen2.5:32b` gets `.sortierung/regeln.md` plus the note and answers with one folder and 1–3 tags from the list.
3. Notes for another folder get `tags: [...]` in the frontmatter (tags set by hand stay) and are moved with `git mv`; name clashes get ` 2`.
4. Commit `Inbox sortiert: N verschoben`, push, macOS notification. Output and log contain counts only.

### Rules

`<vault>/.sortierung/regeln.md`, plain Markdown, three sections:

```markdown
## Ordner (genau einer pro Notiz)
- projekte: hat ein Ziel und ein Ende
- ...
## Archiv-Regel
- ...
## Tags (1 bis 3 pro Notiz, nur aus dieser Liste)
- kochen: Rezepte, Einkauf, Essen
- ...
```

Only lines `- name: description` under `## Ordner` and `## Tags` are parsed; the whole file is passed to the model as instructions. Answers outside the lists are dropped (unknown folder -> stays in `inbox/`).

### Install

Requirements: Ollama with `qwen2.5:32b` running as a service (`brew services start ollama`), the vault cloned with an SSH remote (pushes must not depend on the active `gh` account), Python 3 without extra packages.

```bash
sed "s#__HOME__#$HOME#g" tools/app.notizen.inbox-sort.plist > ~/Library/LaunchAgents/app.notizen.inbox-sort.plist
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/app.notizen.inbox-sort.plist
```

The template expects the app repo at `~/workspace/personal/notizen-app` and the vault at `~/workspace/personal/notes-vault`; edit the paths and the time (`StartCalendarInterval`, default 21:00) in the plist if needed. If the Mac sleeps at that time, launchd runs the job after wake-up. Use another model with `VAULT_LLM=...` in the plist's `EnvironmentVariables`.

### Operate

```bash
launchctl kickstart gui/$(id -u)/app.notizen.inbox-sort      # run now
tail ~/Library/Logs/notizen-inbox-sort.log                     # last runs (counts only)
launchctl bootout gui/$(id -u)/app.notizen.inbox-sort        # disable
```

Undo a run: `git revert` the `Inbox sortiert: ...` commit in the vault.

| Log line | Meaning |
|---|---|
| `zu_frisch N` | changed in the last hour, next run |
| `bleibt_in_inbox N` | model chose `inbox`, remembered until the note changes |
| `ordner_ungueltig N` | model answered a folder that is not in the rules, note stays |
| `ollama_nicht_erreichbar` | Ollama not running, nothing changed |
| `pull_fehlgeschlagen` | merge conflict or no network, nothing changed |
| `push_fehler 1` | sorted and committed locally, push failed (next run retries) |

### Import of an old vault

The same script imported the old Zettelkasten (`copy` -> `topics` -> `sort` -> `apply`, see the docstring in `tools/vault_import.py`). It never prints note content or file names, only counts; details go to files the user opens.

## Development

```bash
./gradlew testDebugUnitTest   # Markdown helpers, clipping, git merge/conflict tests against a local bare repo
./gradlew assembleDebug
```

Stack: Kotlin, Jetpack Compose, JGit 5.13 (last Java 8 line, runs on Android), WorkManager, AlarmManager.

| File | What |
|---|---|
| `GitSync.kt` | clone, commit, fetch + merge + conflict copies, push |
| `Vault.kt` | reading/writing notes, frontmatter |
| `Clipper.kt` | share parsing, page download, HTML → Markdown |
| `Markdown.kt` | highlighting, rendering, editing helpers |
| `Reminders.kt` | scanning tasks, alarms, notification, "Erledigt" |
| `Background.kt` | app start, sync worker, widget |
| `AppVM.kt`, `Screens.kt` | UI state and screens |
