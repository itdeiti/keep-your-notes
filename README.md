# KYN – Keep Your Notes

**A Google Keep replacement for Android that stores every note as Markdown in your own Obsidian vault, synced through a private Git repository.**

[![CI](https://github.com/itdeiti/keep-your-notes/actions/workflows/ci.yml/badge.svg)](https://github.com/itdeiti/keep-your-notes/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/itdeiti/keep-your-notes?label=release)](https://github.com/itdeiti/keep-your-notes/releases/latest)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)
![Android 12+](https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)
![Obsidian](https://img.shields.io/badge/Obsidian-compatible-7C3AED?logo=obsidian&logoColor=white)

Quick capture like Keep, but no cloud account and no lock-in: notes are plain `.md` files in a Git repo you own. On the desktop you open the same repo in [Obsidian](https://obsidian.md); the phone app commits, pulls and pushes on its own, offline first.

> Available in **English** and **German**. Adding a language is one translated folder, see [Translations](#translations).

## Features

- **Capture fast** – home-screen widgets for a quick note, a checklist, dictation and a task with reminder; share a web page, a quote or selected text from any app.
- **Keep-style list** – the whole vault as cards, newest first, pinned on top, with search, tag chips and folder filter.
- **Markdown editor** – highlighting, toolbar, list continuation, nested lists, tables, and a read view with tappable checkboxes and links (`https://…`, `[[wiki links]]`, relative `.md` links).
- **Tasks and reminders** – due dates, reminders and recurring tasks in the [Obsidian Tasks](https://github.com/obsidian-tasks-group/obsidian-tasks) format, a task overview (overdue, today, next 7 days …) and "Erledigt" right from the notification.
- **Configurable widgets** – a note list and a task list (grouped by due date), each limited to (or hiding) any mix of folders, tags and single notes; tick tasks right on the home screen.
- **Web clipping** – shared pages are reduced to the article (Firefox reader mode) and converted to Markdown, like the Obsidian Web Clipper.
- **Git sync that survives conflicts** – every save is a commit (works offline), push when online, pull on start and every 3 hours; a conflict never loses text but leaves a visible conflict copy.
- **Privacy** – PIN lock, no recent-apps preview, vault either shared with Obsidian on the phone or private to the app, no analytics, no backend besides your Git host.
- **Properties and tags** – edit frontmatter fields (with date picker) and tags in the app; Obsidian sees the same properties.
- **Light/dark, text size and language** – follow the device or set per app (English, German; more via [Translations](#translations)).
- **Optional: nightly inbox sorting** – a local LLM on the Mac sorts new notes from `inbox/` into folders and tags them. Nothing leaves the machine.

## How it works

```
 Android app  ──commit/pull/push──▶  private Git repo  ◀──Obsidian Git plugin──  Obsidian (desktop)
 (KYN)                                (GitHub, any host)                           + optional nightly sorting
```

- The vault is an ordinary Obsidian vault: Markdown files, YAML frontmatter, folders. No database, no proprietary format.
- The app uses [JGit](https://www.eclipse.org/jgit/) for Git and a fine-grained token (HTTPS) that only has access to the vault repo.
- Everybody pulls before pushing, so phone, Obsidian and the nightly job can run side by side. A conflict keeps the server version in place and saves the phone version next to it as `… (Konflikt Handy <time>).md`.

## Quick start

Requirements: Android 12 or newer, a **private** Git repository for the vault (GitHub tested), optionally Obsidian on the desktop.

1. **Repository and token** – create a private repo, e.g. `vault`, and a fine-grained token: GitHub → Settings → Developer settings → Fine-grained tokens, repository access *only* `vault`, permission **Contents: Read and write**.
2. **Install** – download `kyn.apk` from the [latest release](https://github.com/itdeiti/keep-your-notes/releases/latest) and open it on the phone, or `adb install -r kyn.apk`.
3. **Set up** – in the app choose where the vault lives (shared with Obsidian or private), allow the requested permissions, enter repo URL, token, name and email for commits, then "Save & clone".
4. **Widgets** – add "Quick note", "Task", "Note list" or "Task list" from the home-screen widget list.
5. **Desktop** – clone the same repo and open it in Obsidian, see [Desktop with Obsidian](#desktop-with-obsidian).

## Usage reference

<details>
<summary><b>Tasks, reminders, recurrence</b></summary>

Tasks use the Obsidian Tasks/Reminder format:

```markdown
- [ ] Zahnarzt anrufen 📅 2026-11-20 ⏰ 2026-11-20 09:00
- [ ] Müll raus 📅 2026-11-24 ⏰ 2026-11-24 07:00 🔁 every week
```

- A task with only `📅` reminds at the default time (09:00, configurable). "Done" in the notification ticks the task (`✅ date`), commits and pushes.
- Ticking a recurring task (editor, overview, widget or notification) adds the next occurrence above it with all dates (`📅 ⏳ 🛫 ⏰`) moved; unticking right after removes it again.
- Rules: `every [N] day(s)/week(s)/month(s)/year(s)`, `every weekday`, `every monday, friday`, `every 2 weeks on monday`, each optionally with `when done` (counts from the day it was ticked). 🔁 in the toolbar sets the rule for the current line.
- Task overview (☑ in the list): every open task of the vault grouped as "Overdue", "Today", "Next 7 days", "Later" and "No date". The date comes from `📅`, else `⏳`, else `⏰`; tasks in code blocks are skipped.
- Widget "Task": type the task, pick "Today" / "Tomorrow" / "Date …" and a time. The task is appended to `Aufgaben.md` in the vault root, committed and synced; the reminder is set right away.

</details>

<details>
<summary><b>Widgets</b></summary>

- **Quick note** – new note, checklist or dictation in one tap.
- **Task** – quick task with date and reminder (see above). Resizable down to one cell (then only ＋⏰).
- **Note list** – Keep-style cards; tap a checkbox to tick it without opening the app, tap text to edit. Each widget has its own settings (on placing it, or long-press → widget settings): title, content (all / tasks / open tasks / titles) and any mix of folders, tags and single notes, combined with OR, either shown alone or hidden ("Hide these").
- **Task list** – every open task grouped like the task overview (overdue, today, next 7 days, later, no date) with due date, time, 🔁 rule and the note it is in; tick the checkbox to complete a task, tap the text to open its note, ＋ adds a task like the "Task" widget. Optional settings (long-press → widget settings): title and any mix of folders, tags and single notes to show alone or to hide (e.g. packing lists).

</details>

<details>
<summary><b>Editor</b></summary>

- Markdown highlighting, toolbar (task, indent/outdent, due date, recurrence, dictation, bold, italic, strikethrough, heading, bullet list, link, table).
- Enter continues lists; Enter on an empty nested item goes one level up.
- Read view: rendered Markdown, tappable checkboxes and links, tables as a grid. Links open in the browser only for `http(s)`, `mailto`, `tel` and `geo`; `[[wiki links]]` and relative `.md` links open the note.
- Properties: every frontmatter field except `tags`/`pinned`, editable inline. Date fields open a date picker and store `YYYY-MM-DD`; new notes get `created:` with date and time.
- Tags as chips under the title (`tags: [a, b]` in the frontmatter); the folder chip moves the note to another folder.

</details>

<details>
<summary><b>Web clipping</b></summary>

Clips go to `Clippings/` with `source:` in the frontmatter.

- Share a page from the browser → the app downloads it, keeps the article ([Readability4J](https://github.com/dankito/Readability4J)) and converts it to Markdown: headings, lists, links, quotes, code, tables, images as remote links.
- Select text, then "Share" in Chrome → quote plus a link back to the highlighted passage.
- Select text in any app → "As note" in the selection menu.

</details>

<details>
<summary><b>Folders and conflicts</b></summary>

- 📁 in the list opens all folders with note counts; tap filters (subfolders included), "Move" moves a folder with everything in it.
- A conflict copy shows a banner with buttons to open the original, keep the phone version (replaces the original) or discard it. A notification and a "⚠ conflicts" chip in the list point to it.
- The root folder `templates/` (Obsidian templates) is hidden in the app.

</details>

## Privacy and security

- **Storage** (Settings → 1): *Share with Obsidian* keeps the vault in `Documents/<folder>` so Obsidian on the phone can open it – but every app with "all files" access can read it. *Only in this app* keeps it in app-private storage (Android sandbox, device encryption); other apps and Obsidian on the phone cannot read it. Switching moves the vault and warns if files are left behind.
- **App lock** (Settings → 4): PIN with increasing delays after wrong attempts, also for the widget settings and for shared content (nothing is written before the PIN). While a PIN is set, screenshots and the recent-apps preview are blocked.
- **Not covered by the PIN**: the content shown by the note-list widget and reminder notifications on the lock screen.
- **Token**: stored encrypted with a key in the Android Keystore. Use a fine-grained token limited to the vault repo.
- No analytics, no ads, no server of its own. Network access only for Git and for downloading pages you share.

Found a security issue? Please open a [private security advisory](https://github.com/itdeiti/keep-your-notes/security/advisories/new) instead of a public issue.

## Desktop with Obsidian

```bash
git clone git@github.com:<you>/vault.git ~/vault
```

1. Obsidian → "Open folder as vault" → `~/vault`.
2. Settings → Community plugins: install and enable **Git** (Vinzent03); auto commit-and-sync every 5 minutes, pull on startup.
3. If your SSH key has a passphrase, store it in the macOS keychain (`ssh-add --apple-use-keychain ~/.ssh/<key>` and `UseKeychain yes` in `~/.ssh/config`); Obsidian cannot ask for it.
4. Optional plugins that understand the same task format: **Tasks** and **Reminder**.

`.obsidian/` is committed on purpose; ignore only `.obsidian/workspace*.json` and `.trash/` (the app writes this `.gitignore` when it clones an empty repo).

Templates that give new notes the same frontmatter as the app: [USAGE-WITH-OBSIDIAN.md](USAGE-WITH-OBSIDIAN.md) (German). Complete Mac setup from scratch: [SETUP-MAC.md](SETUP-MAC.md) (German).

The example vault structure follows PARA (Tiago Forte): `projekte/`, `bereiche/`, `wissen/`, `archiv/`, plus `inbox/` as entry point and a fixed tag list. Nothing in the app depends on these names except that the nightly sorting reads from `inbox/`.

## Nightly inbox sorting (optional, Mac, local LLM)

Every evening the Mac sorts new notes from `inbox/` into folders and tags them with a local model via [Ollama](https://ollama.com). Note content never leaves the machine.

<details>
<summary><b>What a run does, rules, install, operate</b></summary>

`python3 tools/vault_import.py inbox <vault>`:

1. Commits pending local changes in the vault, then `git pull` (stops with a notification if the pull fails).
2. For each `inbox/*.md`: skipped if changed in the last 60 minutes or already checked and unchanged (`.sortierung/geprueft.json`); otherwise `qwen2.5:32b` gets `.sortierung/regeln.md` plus the note and answers with one folder and 1–3 tags from the list.
3. Notes for another folder get `tags: [...]` (tags set by hand stay) and are moved with `git mv`.
4. Commit `Inbox sortiert: N verschoben`, push, macOS notification. Output and log contain counts only.

**Rules** – `<vault>/.sortierung/regeln.md`:

```markdown
## Ordner (genau einer pro Notiz)
- projekte: hat ein Ziel und ein Ende
## Tags (1 bis 3 pro Notiz, nur aus dieser Liste)
- kochen: Rezepte, Einkauf, Essen
```

Only lines `- name: description` under `## Ordner` and `## Tags` are parsed; the whole file is passed to the model. Answers outside the lists are dropped.

**Install** – Ollama with `qwen2.5:32b` as a service (`brew services start ollama`), the vault cloned with an SSH remote, Python 3 without extra packages:

```bash
sed -e "s#__APP__#$HOME/kyn#g" -e "s#__VAULT__#$HOME/vault#g" -e "s#__HOME__#$HOME#g" \
    tools/app.kyn.inbox-sort.plist > ~/Library/LaunchAgents/app.kyn.inbox-sort.plist
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/app.kyn.inbox-sort.plist
```

Adjust both paths in the `sed` line; the time is `StartCalendarInterval` (default 21:00). Another model: `VAULT_LLM=...` in the plist's `EnvironmentVariables`.

**Operate**

```bash
launchctl kickstart gui/$(id -u)/app.kyn.inbox-sort      # run now
tail ~/Library/Logs/kyn-inbox-sort.log                     # last runs (counts only)
launchctl bootout gui/$(id -u)/app.kyn.inbox-sort        # disable
```

Undo a run: `git revert` the `Inbox sortiert: ...` commit in the vault.

| Log line | Meaning |
|---|---|
| `zu_frisch N` | changed in the last hour, next run |
| `bleibt_in_inbox N` | model chose `inbox`, remembered until the note changes |
| `ordner_ungueltig N` | model answered a folder that is not in the rules |
| `ollama_nicht_erreichbar` | Ollama not running, nothing changed |
| `pull_fehlgeschlagen` | merge conflict or no network, nothing changed |
| `push_fehler 1` | sorted and committed locally, push failed (next run retries) |

The same script can import an existing vault (`copy` → `topics` → `sort` → `apply`, see the docstring in `tools/vault_import.py`); it only prints counts, never note content or file names.

</details>

## Development

Requirements: Android Studio (JDK 17 and SDK 36 included).

```bash
./gradlew testDebugUnitTest   # unit tests: Markdown, tasks, recurrence, clipping, Git merge/conflicts against a local bare repo
./gradlew installDebug        # separate app "KYN Debug" (app.kyn.debug) next to the real one
```

The debug build has its own settings and vault folder (`Documents/Vault-Debug`), so development never touches your real notes. It is signed with the public debug key in the repo.

**Release builds** are signed with your own private key, never committed:

```bash
tools/make-release-key.sh     # once: key in ~/.config/kyn/, password in the macOS keychain, keystore.properties (git-ignored)
./gradlew installRelease      # install the signed app
tools/release.sh              # build and publish a GitHub release (tag v0.<commit count>, notes = commits since the last tag)
```

Back up the key file and its password: without them no update can be installed over an existing app. The version (`0.<number of commits>`) is shown at the bottom of the settings.

Stack: Kotlin, Jetpack Compose (Material 3, dynamic colors), JGit 5.13 (last Java 8 line, runs on Android), WorkManager, AlarmManager, jsoup, Readability4J.

| File | What |
|---|---|
| `GitSync.kt` | clone, commit, fetch + merge + conflict copies, push |
| `Conflicts.kt` | conflict copies: names, notification, keep/discard |
| `Vault.kt` | reading/writing notes, frontmatter, storage location |
| `Markdown.kt`, `MarkdownView.kt` | highlighting, rendering, link handling, editing helpers, read view |
| `Clipper.kt` | share parsing, page download, HTML → Markdown |
| `Reminders.kt`, `Recurrence.kt`, `Tasks.kt` | alarms and notifications, 🔁 rules, task overview |
| `QuickTask.kt`, `WidgetConfig.kt`, `Background.kt` | widgets, widget settings, app start, sync worker |
| `AppLock.kt`, `LockUi.kt` | PIN logic and screens |
| `AppVM.kt`, `Screens.kt`, `Theme.kt` | UI state, screens, theme |
| `Dates.kt`, `Language.kt` | dates and times in the app language, per-app language |
| `res/values*/strings*.xml` | all UI text (English default, `values-de/` German) |

## Contributing

Issues and pull requests are welcome.

- Run `./gradlew testDebugUnitTest` before opening a PR; add a test for logic changes (pure Kotlin helpers are easy to test, see `app/src/test`).
- Keep the vault format compatible with Obsidian: plain Markdown, YAML frontmatter, Obsidian Tasks syntax.
- New languages are very welcome, see below.

### Translations

All text is in Android string resources. English is the default (`app/src/main/res/values/`), every other language is a folder `values-<code>/` with the same files.

1. Copy the folder: `cp -r app/src/main/res/values-de app/src/main/res/values-fr` (or start from `values/` for English source text). Only the `strings*.xml` files are needed in the new folder.
2. Translate the text between the tags. Keep the `name` attributes, placeholders like `%1$s` / `%1$d` and emoji as they are; `<plurals>` need the quantities your language uses (`one`, `few`, `many`, `other` …).
3. Check: `python3 tools/check-translations.py fr` lists missing or extra strings and placeholder mismatches (CI runs it for all languages).
4. Build and switch: `./gradlew installDebug`, then in the app Settings → Language (Android 13+), or set the phone language.

The language list in the app and in Android's per-app language setting is generated from the `values-<code>/` folders, no code change needed. Not translated on purpose, because they are part of the vault format and must be the same on every device: conflict copy names (`… (Konflikt Handy …).md`), `Aufgaben.md`, `Clippings/` and the Obsidian Tasks syntax (`📅`, `⏰`, `🔁 every week`). [USAGE-WITH-OBSIDIAN.md](USAGE-WITH-OBSIDIAN.md) and [SETUP-MAC.md](SETUP-MAC.md) are in German; English versions are welcome too.

## License

[GPL-3.0](LICENSE). You may use, study, change and share the app; changed versions that you pass on must be released under the same license, with source code.
