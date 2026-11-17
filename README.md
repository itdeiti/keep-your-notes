# Notizen

Small Android app that replaces Google Keep and keeps every note as a Markdown file in an Obsidian vault, synced with Git.

- Widgets: "Schnellnotiz" (quick note, checklist, dictation) and "Notizliste" (Keep-style list of notes, pinned first; tap a checkbox to tick it without opening the app, tap text to edit the note).
- List of the whole vault, newest first, pinned on top, search and tag filter.
- Editor with Markdown highlighting, toolbar, list continuation, read view with tappable checkboxes.
- Tasks with due date and reminder in the Obsidian Tasks/Reminder format:
  `- [ ] Zahnarzt anrufen 📅 2026-11-20 ⏰ 2026-11-20 09:00`
  A task with only `📅` reminds at the default time (09:00, configurable). "Erledigt" in the notification ticks the task (`✅ date`), commits and pushes.
- Web clipping into `Clippings/` (with `source:` in the frontmatter, like the Obsidian Web Clipper):
  - Share a page from the browser → the app downloads it, keeps the article (Readability, Firefox reader mode) and converts it to Markdown: headings, lists, links, quotes, code, tables, images as remote links.
  - Select text, then "Teilen" in Chrome → quote plus link back to the highlighted passage.
  - Select text in any app → "Als Notiz" in the selection menu.
- Tags: chips under the title; × removes, ＋ picks an existing tag or creates a new one (`tags: [a, b]` in the frontmatter).
- Nightly inbox sorting on the Mac (see below).
- Git: saving commits right away (works offline), pushing happens as soon as there is network, the app pulls when opened and every 3 hours. A conflict keeps the server version and saves the phone version next to it as `… (Konflikt Handy <time>).md`.
- The repo lives in `Documents/<Ordner>` on shared storage, so Obsidian Android can open the same folder. Obsidian is optional.

## Setup

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

## Nightly inbox sorting (Mac, local AI)

A LaunchAgent runs every day at 21:00 (`~/Library/LaunchAgents/app.notizen.inbox-sort.plist`):

```bash
python3 tools/vault_import.py inbox ~/workspace/personal/notes-vault
```

It pulls the vault, lets `qwen2.5:32b` (Ollama) pick a folder and 1–3 tags for each note in `inbox/` using `.sortierung/regeln.md`, moves the notes, commits, pushes (SSH remote) and shows a macOS notification. Notes changed in the last hour are skipped; notes the model leaves in `inbox/` are remembered in `.sortierung/geprueft.json` until they change. Tags you set by hand are kept. Log: `~/Library/Logs/notizen-inbox-sort.log`.

Run now / pause:

```bash
launchctl kickstart gui/$(id -u)/app.notizen.inbox-sort
launchctl bootout gui/$(id -u)/app.notizen.inbox-sort
```

The same script imported the old Zettelkasten (`copy`, `topics`, `sort`, `apply`); it never prints note content, only counts.

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
