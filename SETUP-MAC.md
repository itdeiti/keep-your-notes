# Mac neu aufsetzen

Alles, was nötig ist, damit nach einem neuen oder zurückgesetzten Mac wieder läuft: App bauen und aufs Handy bringen, Vault in Obsidian, abendliches Inbox-Sortieren mit lokaler KI.

Allgemeine Fassung. Ersetze `<konto>` durch dein GitHub-Konto und `<vault>` durch den Namen deines **privaten** Vault-Repos. Die Ordner `~/kyn` und `~/vault` sind Vorschläge; wer andere nimmt, passt die Befehle an. Tipp: Eine eigene Fassung mit deinen echten Werten gehört in den privaten Vault, nicht in ein öffentliches Repo.

Nur die App aufs Handy bringen, ohne etwas zu bauen? Dann reichen Schritt 1 (Homebrew, `android-platform-tools`) und Schritt 6 mit der APK aus den GitHub Releases.

## Was nur auf dem Mac liegt (vorher sichern)

| Was | Wo | Ersatz, falls verloren |
|---|---|---|
| SSH-Schlüssel für GitHub (privat) | `~/.ssh/id_ed25519_github` (+ `.pub`) und Eintrag `Host github.com` in `~/.ssh/config` | neuen Schlüssel erzeugen und bei GitHub eintragen (Schritt 2) |
| Release-Schlüssel der App | `~/.config/kyn/release.p12`, Passwort im Schlüsselbund (Eintrag `kyn-release-key`), Verweis in `~/kyn/keystore.properties` | **kein Ersatz**: ohne ihn nimmt das Handy keine Updates mehr an, die App müsste deinstalliert werden. Datei und Passwort zusätzlich im Passwort-Manager sichern |
| Ungepushte Commits | `~/kyn`, `~/vault` | vorher `git status` / `git push` in beiden |

Alles andere steht in den Repos oder lässt sich neu laden: Code, APK (GitHub Releases), LaunchAgent-Vorlage, Sortier-Regeln (`<vault>/.sortierung/`), Ollama-Modelle.

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

## 2. GitHub-Zugang (Konto `<konto>`)

Beide Repos pushen per SSH. Der Schlüssel wird für `github.com` hinterlegt.

Schlüssel zurückkopieren oder neu erzeugen:

```bash
ssh-keygen -t ed25519 -f ~/.ssh/id_ed25519_github -C <konto>
```

```bash
gh auth login
```
```bash
gh ssh-key add ~/.ssh/id_ed25519_github.pub --title "Mac"
```

Bei `gh auth login` mit dem Konto `<konto>` anmelden.

In `~/.ssh/config` ergänzen:

```
Host github.com
  AddKeysToAgent yes
  IdentityFile ~/.ssh/id_ed25519_github
  IdentitiesOnly yes
```

Prüfen, erwartet wird „Hi <konto>!“:

```bash
ssh -T git@github.com
```

Mehrere GitHub-Konten auf einem Mac (z. B. Arbeit und privat)? Dann statt `Host github.com` einen eigenen Alias anlegen, etwa `Host github-privat` mit `HostName github.com` und `User git`, und in allen Befehlen unten `git@github-privat:` statt `git@github.com:` verwenden. So nutzt der Push immer den richtigen Schlüssel, egal welches `gh`-Konto gerade aktiv ist.

## 3. Repos klonen

```bash
cd ~
```
```bash
git clone git@github.com:<konto>/keep-your-notes.git kyn
```
```bash
git clone git@github.com:<konto>/<vault>.git vault
```

Die LaunchAgent-Vorlage bekommt beide Pfade in Schritt 5 per `sed`.

## 4. Obsidian

Obsidian öffnen, dann „Ordner als Vault öffnen“ und `~/vault` wählen.

Für Sync aus Obsidian heraus das Community-Plugin **Git** (Vinzent03) installieren:
- Auto commit-and-sync interval: 5 Minuten
- Pull on startup: an

Wichtig, wenn der SSH-Schlüssel eine Passphrase hat: Obsidian kann sie nicht abfragen, Push und Pull aus dem Plugin scheitern dann ohne sichtbare Meldung. Deshalb die Passphrase einmal im Schlüsselbund ablegen:

```bash
ssh-add --apple-use-keychain ~/.ssh/id_ed25519_github
```

und im Eintrag `Host github.com` in `~/.ssh/config` die Zeile `UseKeychain yes` ergänzen, damit der Schlüssel auch nach einem Neustart geladen wird. Test: eine Notiz ändern, in Obsidian Cmd+P → „Git: Commit-and-sync“, keine Fehlermeldung.

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
cd ~/kyn
```
```bash
sed -e "s#__APP__#$HOME/kyn#g" -e "s#__VAULT__#$HOME/vault#g" -e "s#__HOME__#$HOME#g" tools/app.kyn.inbox-sort.plist > ~/Library/LaunchAgents/app.kyn.inbox-sort.plist
```
```bash
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/app.kyn.inbox-sort.plist
```

Einmal testen:

```bash
launchctl kickstart gui/$(id -u)/app.kyn.inbox-sort
```
```bash
tail ~/Library/Logs/kyn-inbox-sort.log
```

Im Log soll eine Zeile `... verschoben N` stehen, keine Zeile `ollama_nicht_erreichbar` oder `pull_fehlgeschlagen`. Beim ersten Mal fragt macOS eventuell, ob Python Mitteilungen anzeigen darf, das bitte erlauben.

## 6. App aufs Handy

Am Handy einmalig: Einstellungen → Über das Telefon → 7× auf „Build-Nummer“ tippen. Dann unter Entwickleroptionen „USB-Debugging“ einschalten. Handy per USB anschließen und die Nachfrage „USB-Debugging zulassen?“ bestätigen.

**Fertige APK aus den GitHub Releases**, kein Bauen nötig:

```bash
gh release download --repo <konto>/keep-your-notes --pattern kyn.apk --dir /tmp --clobber
```
```bash
adb install -r /tmp/kyn.apk
```

**Oder selbst bauen und installieren** (braucht den Release-Schlüssel, siehe Schritt 7):

```bash
cd ~/kyn
```
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```
```bash
./gradlew installRelease
```

`./gradlew installDebug` installiert dagegen die getrennte App „KYN Debug“ (`app.kyn.debug`, signiert mit dem öffentlichen Debug-Schlüssel aus dem Repo). Sie hat eigene Einstellungen und einen eigenen Vault-Ordner und lässt die echte App in Ruhe. Zum Ausprobieren eignet sich der Emulator mit dem lokalen Test-Repo besser.

Neue Handys oder eine Neuinstallation der App: in der App „Zugriff auf alle Dateien“ und Benachrichtigungen erlauben, dann Repo-URL `https://github.com/<konto>/<vault>.git` und einen fine-grained Token mit „Contents: Read and write“ nur für `<vault>` eintragen. Mehr dazu in der `README.md` des App-Repos.

## 7. Release-Schlüssel und neue Version veröffentlichen

**Neuer Mac, Schlüssel vorhanden:** Datei nach `~/.config/kyn/release.p12` zurückkopieren, dann Passwort in den Schlüsselbund und Verweis anlegen:

```bash
security add-generic-password -U -s kyn-release-key -a "$USER" -w
```
```bash
printf 'storeFile=%s\nkeyAlias=kyn\nkeychainService=kyn-release-key\n' "$HOME/.config/kyn/release.p12" > ~/kyn/keystore.properties
```

**Allererster Schlüssel** (nur einmal überhaupt):

```bash
tools/make-release-key.sh
```

Danach Datei und Passwort im Passwort-Manager sichern.

**Veröffentlichen** nach Änderungen an der App (committet und gepusht):

```bash
tools/release.sh
```

Das Skript baut die signierte Release-APK und legt ein GitHub Release `v0.<Anzahl Commits>` mit `kyn.apk` an, über das Konto `<konto>` (egal, welches `gh`-Konto gerade aktiv ist). Die Notes listen alle Commits seit dem letzten Release. Die Versionsnummer kommt aus der Anzahl der Git-Commits; in der App steht sie unten in den Einstellungen.

Früher lag die APK im Repo (`apk/kyn.apk`, je Version etwa 27 MB). Sie ist seit dem Umzug auf Releases nicht mehr im Repo, die alten Versionen stecken aber noch in der Git-Historie. Wer das Repo verkleinern will, entfernt sie mit `git filter-repo --path apk/ --invert-paths` (schreibt die Historie um, danach `git push --force`).

## Checkliste

- [ ] `ssh -T git@github.com` meldet „Hi <konto>!“
- [ ] beide Repos geklont (`~/kyn`, `~/vault`)
- [ ] Obsidian öffnet `~/vault`
- [ ] `ollama list` zeigt `qwen2.5:32b`
- [ ] `launchctl print gui/$(id -u)/app.kyn.inbox-sort` findet den Job, Testlauf im Log ok
- [ ] `keystore.properties` vorhanden, `./gradlew assembleRelease` erzeugt `app-release.apk` (nicht `-unsigned`)
- [ ] `adb devices` zeigt das Handy, App installiert, App synchronisiert („Synchronisiert HH:MM“)
- [ ] In Obsidian klappt „Git: Commit-and-sync“ ohne Fehler (Passphrase im Schlüsselbund)
