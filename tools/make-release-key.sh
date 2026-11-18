#!/bin/sh
# Create the private release key once (macOS): key file outside the repo, password in the
# login keychain, keystore.properties (git-ignored) pointing to both.
#
#   tools/make-release-key.sh [key file]     # default ~/.config/kyn/release.p12
#
# Back up the key file and the password (e.g. in a password manager). Without them no update
# can be installed over the app anymore: it would have to be uninstalled (token, settings and a
# private vault on the phone are lost).
set -eu
cd "$(dirname "$0")/.."

key="${1:-$HOME/.config/kyn/release.p12}"
service="kyn-release-key"

if [ -e "$key" ] || [ -e keystore.properties ]; then
    echo "$key or keystore.properties already exists - not overwriting." >&2
    exit 1
fi
if [ -z "${JAVA_HOME:-}" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
    JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
keytool="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"

printf "Password for the release key (at least 8 characters): "
stty -echo; read -r pw; stty echo; echo
printf "Repeat: "
stty -echo; read -r pw2; stty echo; echo
[ "$pw" = "$pw2" ] || { echo "Passwords differ." >&2; exit 1; }
[ ${#pw} -ge 8 ] || { echo "Too short." >&2; exit 1; }

mkdir -p "$(dirname "$key")"
chmod 700 "$(dirname "$key")"
KYN_PW="$pw" "$keytool" -genkeypair -storetype PKCS12 -keystore "$key" -alias kyn \
    -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=KYN" \
    -storepass:env KYN_PW -keypass:env KYN_PW
chmod 600 "$key"

# -U: update if an old entry with this name exists
security add-generic-password -U -s "$service" -a "$USER" -w "$pw"

cat > keystore.properties <<EOF
storeFile=$key
keyAlias=kyn
keychainService=$service
EOF
chmod 600 keystore.properties

echo "Release key: $key"
echo "Password: login keychain, entry \"$service\""
echo "Now back up both. Then: ./gradlew assembleRelease"
