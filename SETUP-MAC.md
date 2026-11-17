# Mac neu aufsetzen

Alles, was nötig ist, damit nach einem neuen oder zurückgesetzten Mac wieder läuft: App bauen und aufs Handy bringen, Vault in Obsidian, abendliches Inbox-Sortieren mit lokaler KI.

Nur die App aufs Handy bringen, ohne etwas zu bauen? Dann reichen Schritt 1 (Homebrew, `android-platform-tools`) und Schritt 6 mit `apk/notizen.apk`.

## Was nur auf dem Mac liegt (vorher sichern)

| Was | Wo | Ersatz, falls verloren |
|---|---|---|
| SSH-Schlüssel für GitHub (privat) | `~/.ssh/id_ed25519_gh_itdeiti` (+ `.pub`) und Eintrag `Host github-personal` in `~/.ssh/config` | neuen Schlüssel erzeugen und bei GitHub eintragen (Schritt 2) |
| Ungepushte Commits | `~/workspace/personal/notizen-app`, `~/workspace/personal/notes-vault` | vorher `git status` / `git push` in beiden |

Alles andere steht in den Repos oder lässt sich neu laden: Code, APK (GitHub Releases), Debug-Schlüssel der App, LaunchAgent-Vorlage, Sortier-Regeln (`notes-vault/.sortierung/`), Ollama-Modelle.

## 1. Programme

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

```bash
brew install git gh python ollama
```

```bash
brew install --cask android-studio obsidian android-platform-tools
```

- **Android Studio** einmal starten und den Setup-Assistenten durchlaufen. Er installiert das Android SDK nach `~/Library/Android/sdk`.
- Im SDK Manager anhaken: **Android SDK Platform 36** und **Build-Tools 36**.
- Das JDK bringt Android Studio mit (`jbr`), ein eigenes Java ist nicht nötig.

## 2. GitHub-Zugang (privates Konto `itdeiti`)

Beide Repos pushen per SSH über den Host-Alias `github-personal`. Das funktioniert unabhängig davon, welches `gh`-Konto gerade aktiv ist.

Schlüssel zurückkopieren oder neu erzeugen:

```bash
ssh-keygen -t ed25519 -f ~/.ssh/id_ed25519_gh_itdeiti -C itdeiti
```

```bash
gh auth login
```
```bash
gh ssh-key add ~/.ssh/id_ed25519_gh_itdeiti.pub --title "Mac"
```

Bei `gh auth login` mit dem Konto `itdeiti` anmelden.

In `~/.ssh/config` ergänzen:

```
Host github-personal
  AddKeysToAgent yes
  HostName github.com
  User git
  IdentityFile ~/.ssh/id_ed25519_gh_itdeiti
  IdentitiesOnly yes
```

Prüfen, erwartet wird „Hi itdeiti!“:

```bash
ssh -T git@github-personal
```

## 3. Repos klonen

```bash
mkdir -p ~/workspace/personal && cd ~/workspace/personal
```
```bash
git clone git@github-personal:itdeiti/notizen-app.git
```
```bash
git clone git@github-personal:itdeiti/notes-vault.git
```

Die Pfade sind wichtig: Die LaunchAgent-Vorlage erwartet genau `~/workspace/personal/notizen-app` und `~/workspace/personal/notes-vault`. Der alte `zettelkasten` wird nicht mehr gebraucht, er ist vollständig importiert.

## 4. Obsidian

Obsidian öffnen, dann „Ordner als Vault öffnen“ und `~/workspace/personal/notes-vault` wählen.

Für Sync aus Obsidian heraus das Community-Plugin **Git** (Vinzent03) installieren:
- Auto commit-and-sync interval: 5 Minuten
- Pull on startup: an

Wichtig, wenn der SSH-Schlüssel eine Passphrase hat: Obsidian kann sie nicht abfragen, Push und Pull aus dem Plugin scheitern dann ohne sichtbare Meldung. Deshalb die Passphrase einmal im Schlüsselbund ablegen:

```bash
ssh-add --apple-use-keychain ~/.ssh/id_ed25519_gh_itdeiti
```

und im Eintrag `Host github-personal` in `~/.ssh/config` die Zeile `UseKeychain yes` ergänzen, damit der Schlüssel auch nach einem Neustart geladen wird. Test: eine Notiz ändern, in Obsidian Cmd+P → „Git: Commit-and-sync“, keine Fehlermeldung.

Ohne das Plugin geht es auch, dann synct nur der Abend-Job.

## 5. Lokale KI für das Abend-Sortieren

```bash
brew services start ollama
```
```bash
ollama pull qwen2.5:32b
```

Das Modell ist etwa 19 GB groß und braucht rund 20 GB freien Arbeitsspeicher während des Laufs. Mit weniger RAM ein kleineres Modell nehmen, z.B. `qwen2.5:14b`, und im LaunchAgent unter `EnvironmentVariables` den Schlüssel `VAULT_LLM` setzen.

`bge-m3` braucht es nur für einen erneuten Import alter Notizen (`vault_import.py topics`), nicht für das tägliche Sortieren.

LaunchAgent einrichten (läuft täglich um 21 Uhr):

```bash
cd ~/workspace/personal/notizen-app
```
```bash
sed "s#__HOME__#$HOME#g" tools/app.notizen.inbox-sort.plist > ~/Library/LaunchAgents/app.notizen.inbox-sort.plist
```
```bash
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/app.notizen.inbox-sort.plist
```

Einmal testen:

```bash
launchctl kickstart gui/$(id -u)/app.notizen.inbox-sort
```
```bash
tail ~/Library/Logs/notizen-inbox-sort.log
```

Im Log soll eine Zeile `... verschoben N` stehen, keine Zeile `ollama_nicht_erreichbar` oder `pull_fehlgeschlagen`. Beim ersten Mal fragt macOS eventuell, ob Python Mitteilungen anzeigen darf, das bitte erlauben.

## 6. App aufs Handy

Am Handy einmalig: Einstellungen → Über das Telefon → 7× auf „Build-Nummer“ tippen. Dann unter Entwickleroptionen „USB-Debugging“ einschalten. Handy per USB anschließen und die Nachfrage „USB-Debugging zulassen?“ bestätigen.

**Fertige APK aus den GitHub Releases**, kein Bauen nötig:

```bash
GH_TOKEN=$(gh auth token --user itdeiti) gh release download --repo itdeiti/notizen-app --pattern notizen.apk --dir /tmp --clobber
```
```bash
adb install -r /tmp/notizen.apk
```

**Oder selbst bauen und installieren:**

```bash
cd ~/workspace/personal/notizen-app
```
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```
```bash
./gradlew installDebug
```

Die App ist mit dem Debug-Schlüssel aus dem Repo signiert (`app/debug.keystore`). Dadurch nimmt das Handy Updates von jedem Mac an, ohne dass die App deinstalliert werden muss. Ginge der Schlüssel verloren, müsste die App einmal deinstalliert werden. Dabei gehen Token und Einstellungen verloren, die Notizen nicht: Die liegen in `Documents/Vault` und auf GitHub.

Neue Handys oder eine Neuinstallation der App: in der App „Zugriff auf alle Dateien“ und Benachrichtigungen erlauben, dann Repo-URL `https://github.com/itdeiti/notes-vault.git` und einen fine-grained Token mit „Contents: Read and write“ nur für `notes-vault` eintragen. Mehr dazu in der `README.md`.

## 7. Neue Version veröffentlichen

Nach Änderungen an der App (committet und gepusht):

```bash
tools/release.sh
```

Das Skript baut die APK und legt ein GitHub Release `v0.<Anzahl Commits>` mit `notizen.apk` an, über das Konto `itdeiti` (egal, welches `gh`-Konto gerade aktiv ist). Die Versionsnummer kommt aus der Anzahl der Git-Commits; in der App steht sie unten in den Einstellungen.

Früher lag die APK im Repo (`apk/notizen.apk`, je Version etwa 27 MB). Sie ist seit dem Umzug auf Releases nicht mehr im Repo, die alten Versionen stecken aber noch in der Git-Historie. Wer das Repo verkleinern will, entfernt sie mit `git filter-repo --path apk/ --invert-paths` (schreibt die Historie um, danach `git push --force`).

## Checkliste

- [ ] `ssh -T git@github-personal` meldet „Hi itdeiti!“
- [ ] beide Repos unter `~/workspace/personal/` geklont
- [ ] Obsidian öffnet `notes-vault`
- [ ] `ollama list` zeigt `qwen2.5:32b`
- [ ] `launchctl print gui/$(id -u)/app.notizen.inbox-sort` findet den Job, Testlauf im Log ok
- [ ] `adb devices` zeigt das Handy, App installiert, App synchronisiert („Synchronisiert HH:MM“)
- [ ] In Obsidian klappt „Git: Commit-and-sync“ ohne Fehler (Passphrase im Schlüsselbund)
