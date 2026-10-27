#!/usr/bin/env bash
# Source from build scripts. No machine-specific paths or credentials.
if [ -n "${JAVA_HOME:-}" ]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
if [ -z "$SDK" ]; then echo 'Set ANDROID_SDK_ROOT (see docs/BUILD.md)' >&2; exit 1; fi
BT=${ANDROID_BUILD_TOOLS:-$SDK/build-tools/34.0.0}
PLATFORM=${ANDROID_PLATFORM:-$SDK/platforms/android-30/android.jar}
for tool in aapt2 d8 zipalign apksigner; do
    test -x "$BT/$tool" || { echo "Missing $BT/$tool" >&2; exit 1; }
done
test -f "$PLATFORM" || { echo "Missing $PLATFORM" >&2; exit 1; }
command -v javac >/dev/null
KEY_DIR="$PROJ/.local/keys"
KEYSTORE=${SIGNING_KEYSTORE:-$KEY_DIR/signing.jks}
STORE_PASS_FILE=${SIGNING_STORE_PASS_FILE:-$KEY_DIR/signing.password}
KEY_PASS_FILE=${SIGNING_KEY_PASS_FILE:-$STORE_PASS_FILE}
ADB_KEY=${APP_ADB_KEY:-$KEY_DIR/adbkey}
ADB_PUB=${APP_ADB_PUB:-$KEY_DIR/adbkey.pub}

SIGN_ARGS=(--ks "$KEYSTORE" --ks-pass "file:$STORE_PASS_FILE")
# apksigner consumes subsequent lines if the same password file is supplied twice.
# For equal passwords it automatically retries the store password for the key.
if [ ! "$KEY_PASS_FILE" -ef "$STORE_PASS_FILE" ]; then
    SIGN_ARGS+=(--key-pass "file:$KEY_PASS_FILE")
fi
