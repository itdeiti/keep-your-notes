#!/bin/sh
# Build the signed release APK and publish it as a GitHub release (tag v0.<commits>, asset kyn.apk).
# Needs the private release key (tools/make-release-key.sh). Notes: all commits since the last tag.
#
#   tools/release.sh            # gh account: GH_USER, default the repo owner
#   GH_USER=other tools/release.sh
#
# Install on a phone without building:
#   gh release download --repo <owner>/keep-your-notes --pattern kyn.apk --dir /tmp --clobber
#   adb install -r /tmp/kyn.apk
set -eu
cd "$(dirname "$0")/.."

if [ -n "$(git status --porcelain)" ]; then
    echo "Uncommitted changes - commit first, the release is tagged on HEAD." >&2
    exit 1
fi
git fetch -q origin
if [ "$(git rev-parse HEAD)" != "$(git rev-parse @{u})" ]; then
    echo "HEAD is not pushed (or behind origin) - push/pull first." >&2
    exit 1
fi

# owner/name from the origin URL (works with ssh host aliases like git@github-personal:owner/name.git)
repo=$(git remote get-url origin | sed -E 's#^.*[:/]([^/:]+/[^/]+)$#\1#; s#\.git$##')
count=$(git rev-list --count HEAD)
tag="v0.$count"
user="${GH_USER:-${repo%%/*}}"

if [ -z "${JAVA_HOME:-}" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
    export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
if [ ! -f keystore.properties ]; then
    echo "No keystore.properties - create the release key first: tools/make-release-key.sh" >&2
    exit 1
fi
./gradlew -q assembleRelease
apk=app/build/outputs/apk/release/app-release.apk
if [ ! -f "$apk" ]; then
    echo "No signed release APK (key file or keychain password missing?)." >&2
    exit 1
fi

# release notes: every commit since the previous tag, newest first
prev=$(git describe --tags --abbrev=0 2>/dev/null || true)
notes=$(git log --no-merges --pretty='### %s%n%n%b' ${prev:+"$prev"..HEAD})

out=$(mktemp -d)
cp "$apk" "$out/kyn.apk"

GH_TOKEN=$(gh auth token --user "$user")
export GH_TOKEN
gh release create "$tag" "$out/kyn.apk" --repo "$repo" --target "$(git rev-parse HEAD)" \
    --title "KYN 0.$count" --notes "$notes"
rm -rf "$out"
echo "Released $tag: https://github.com/$repo/releases/tag/$tag"
