#!/usr/bin/env bash
# Generates independent local keys; never reads the developer's ~/.android keys.
set -euo pipefail
umask 077
PROJ=$(cd "$(dirname "$0")/.." && pwd)
if [ -n "${JAVA_HOME:-}" ]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
dir="$PROJ/.local/keys"
mkdir -p "$dir"
chmod 700 "$PROJ/.local" "$dir"
if [ ! -f "$dir/adbkey" ]; then
    command -v adb >/dev/null
    adb keygen "$dir/adbkey" >/dev/null
fi
test -f "$dir/adbkey.pub" || { echo 'Missing adbkey.pub; restore the matching pair' >&2; exit 1; }
# AdbClient requires unencrypted PKCS#8. The ADB public-key encoding stays unchanged.
openssl pkcs8 -topk8 -nocrypt -in "$dir/adbkey" -out "$dir/.adbkey.pkcs8"
mv "$dir/.adbkey.pkcs8" "$dir/adbkey"
if [ ! -f "$dir/signing.jks" ]; then
    if [ ! -f "$dir/signing.password" ]; then openssl rand -hex 32 > "$dir/signing.password"; fi
    keytool -genkeypair -keystore "$dir/signing.jks" -storetype JKS -alias f515-local \
        -storepass:file "$dir/signing.password" -keypass:file "$dir/signing.password" \
        -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=F515 local build' >/dev/null 2>&1
fi
test -s "$dir/signing.password"
chmod 600 "$dir"/*
echo 'Local ADB and signing keys ready in .local/keys (ignored by Git).'
