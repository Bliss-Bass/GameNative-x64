#!/usr/bin/env bash
# Create the release keystore for this fork and upload it as repository secrets.
#
#   .github/scripts/create-release-keystore.sh [keystore-dir]
#
# Run this yourself: it generates a private key and a password, so nothing here should
# be produced or held by tooling other than yours. It never writes inside the git tree,
# so the keystore cannot be committed by accident.
#
# Sets the four secrets the release workflows expect: SIGNING_KEY (base64 of the
# keystore), SIGNING_STORE_PASSWORD, SIGNING_KEY_ALIAS, SIGNING_KEY_PASSWORD.
#
# Keep the resulting directory backed up. Losing it means future releases are signed
# with a different key, and Android refuses to update an installed app across a key
# change -- users would have to uninstall and lose their game prefixes.
set -euo pipefail

KEYDIR="${1:-$HOME/.gamenative-x64-keys}"
REPO="${REPO:-Bliss-Bass/GameNative-x64}"
ALIAS="${ALIAS:-gamenative-x64}"
KEYSTORE="$KEYDIR/release.jks"

log() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

command -v keytool >/dev/null 2>&1 || die "keytool not found (install a JDK)"
command -v gh      >/dev/null 2>&1 || die "gh not found; needed to set the repository secrets"

mkdir -p "$KEYDIR"
chmod 700 "$KEYDIR"

if [ -f "$KEYSTORE" ]; then
    log "reusing existing keystore at $KEYSTORE"
    [ -f "$KEYDIR/password.txt" ] || die "found the keystore but no password.txt beside it"
    PASS="$(cat "$KEYDIR/password.txt")"
else
    # PKCS12 rather than the legacy JKS format. It has no meaningful separate key
    # password, so one value is used for both and Gradle does not warn about a mismatch.
    PASS="$(openssl rand -base64 24 | tr -dc 'A-Za-z0-9' | cut -c1-28)"
    log "generating a 4096-bit RSA key valid for 30 years"
    keytool -genkeypair -v -keystore "$KEYSTORE" -storetype PKCS12 \
        -keyalg RSA -keysize 4096 -validity 10950 -alias "$ALIAS" \
        -dname "CN=Bliss-Bass, OU=GameNative x64, O=Bliss-Bass, C=US" \
        -storepass "$PASS" -keypass "$PASS" >/dev/null
    printf '%s' "$PASS" > "$KEYDIR/password.txt"
    chmod 600 "$KEYDIR/password.txt" "$KEYSTORE"
    log "wrote $KEYSTORE and password.txt (mode 600)"
fi

log "certificate fingerprint"
keytool -list -v -keystore "$KEYSTORE" -storepass "$PASS" -alias "$ALIAS" \
    | grep -E 'SHA256:|Valid from' | sed 's/^/    /'

# Piped from stdin so the values never appear in argv, where any other process on the
# machine could read them out of /proc.
log "setting repository secrets on $REPO"
base64 -w0 "$KEYSTORE" | gh secret set SIGNING_KEY            --repo "$REPO"
printf '%s' "$PASS"     | gh secret set SIGNING_STORE_PASSWORD --repo "$REPO"
printf '%s' "$PASS"     | gh secret set SIGNING_KEY_PASSWORD   --repo "$REPO"
printf '%s' "$ALIAS"    | gh secret set SIGNING_KEY_ALIAS      --repo "$REPO"

log "done. Back up $KEYDIR somewhere safe, then tag a release:"
printf '    git tag -a v1.2.0-x64.1 -m "GameNative x64 1.2.0-x64.1"\n'
printf '    git push bliss v1.2.0-x64.1\n'
