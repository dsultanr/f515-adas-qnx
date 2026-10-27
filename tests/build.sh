#!/usr/bin/env bash
set -euo pipefail
umask 077
PROJ=$(cd "$(dirname "$0")/.." && pwd)
source "$PROJ/scripts/android-env.sh"
out="$PROJ/build/autostart-test"
test -d "$PROJ/build/android/classes" || { echo 'Run ./build.sh first' >&2; exit 1; }
rm -rf "$out"
mkdir -p "$out/classes" "$out/dex"
"$BT/aapt2" link --manifest "$PROJ/tests/AndroidManifest.xml" -I "$PLATFORM" --min-sdk-version 26 --target-sdk-version 29 -o "$out/base.apk"
javac -source 8 -target 8 -nowarn -classpath "$PLATFORM:$PROJ/build/android/classes" -d "$out/classes" \
    "$PROJ/tests/AutostartDeviceTest.java" "$PROJ/tests/MaintenanceDeviceTest.java"
mapfile -d '' classes < <(find "$out/classes" -name '*.class' -print0)
"$BT/d8" --min-api 26 --lib "$PLATFORM" --classpath "$PROJ/build/android/classes" --output "$out/dex" "${classes[@]}"
cp "$out/base.apk" "$out/unsigned.apk"
(cd "$out/dex" && zip -q "$out/unsigned.apk" classes.dex)
"$BT/zipalign" -f -p 4 "$out/unsigned.apk" "$out/aligned.apk"
"$BT/apksigner" sign "${SIGN_ARGS[@]}" \
    --out "$out/AutostartTest.apk" "$out/aligned.apk"
"$BT/apksigner" verify "$out/AutostartTest.apk"
echo "$out/AutostartTest.apk"
