#!/bin/sh
# Build the APK and publish it as a GitHub release (tag v0.<commits>, asset notizen.apk).
#
#   tools/release.sh            # account from GH_USER, default itdeiti
#   GH_USER=other tools/release.sh
#
# Install on a phone without building:
#   gh release download --repo itdeiti/notizen-app --pattern notizen.apk --dir /tmp --clobber
#   adb install -r /tmp/notizen.apk
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
user="${GH_USER:-itdeiti}"

if [ -z "${JAVA_HOME:-}" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
    export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
./gradlew -q assembleDebug

out=$(mktemp -d)
cp app/build/outputs/apk/debug/app-debug.apk "$out/notizen.apk"

GH_TOKEN=$(gh auth token --user "$user")
export GH_TOKEN
gh release create "$tag" "$out/notizen.apk" --repo "$repo" --target "$(git rev-parse HEAD)" \
    --title "Notizen 0.$count" --notes "$(git log -1 --pretty=%B)"
rm -rf "$out"
echo "Released $tag: https://github.com/$repo/releases/tag/$tag"
