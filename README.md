# Notizen

Small Android app that replaces Google Keep and keeps every note as a Markdown file in an Obsidian vault, synced with Git.

- Widget: quick note, checklist or dictation.
- List of the whole vault, newest first, pinned on top, search and tag filter.
- Editor with Markdown highlighting, toolbar, list continuation, read view with tappable checkboxes.
- Tasks with due date and reminder in the Obsidian Tasks/Reminder format:
  `- [ ] Zahnarzt anrufen 📅 2026-11-20 ⏰ 2026-11-20 09:00`
  A task with only `📅` reminds at the default time (09:00, configurable). "Erledigt" in the notification ticks the task (`✅ date`), commits and pushes.
- Web clipping into `Clippings/` (with `source:` in the frontmatter, like the Obsidian Web Clipper):
  - Share a page from the browser → the app downloads it, keeps the article (Readability, Firefox reader mode) and converts it to Markdown: headings, lists, links, quotes, code, tables, images as remote links.
  - Select text, then "Teilen" in Chrome → quote plus link back to the highlighted passage.
  - Select text in any app → "Als Notiz" in the selection menu.
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
