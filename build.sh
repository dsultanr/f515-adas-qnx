#!/usr/bin/env bash
set -euo pipefail
umask 077
PROJ=$(cd "$(dirname "$0")" && pwd)
source "$PROJ/scripts/android-env.sh"
for f in "$KEYSTORE" "$STORE_PASS_FILE" "$KEY_PASS_FILE" "$ADB_KEY" "$ADB_PUB"; do
    test -s "$f" || { echo 'Missing local keys. Run scripts/init-keys.sh or configure key paths.' >&2; exit 1; }
done
python3 "$PROJ/scripts/qnx-assets.py"
APP="$PROJ/app"
OUT="$PROJ/build/android"
VER=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$APP/AndroidManifest.xml")
APK="$PROJ/build/F515AdasQnx-$VER.apk"
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/res" "$OUT/assets"
cp -a "$APP/assets/." "$OUT/assets/"
cp "$ADB_KEY" "$OUT/assets/adbkey"
cp "$ADB_PUB" "$OUT/assets/adbkey.pub"
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res"
"$BT/aapt2" link --manifest "$APP/AndroidManifest.xml" -I "$PLATFORM" -R "$OUT"/res/*.flat -A "$OUT/assets" \
    --min-sdk-version 26 --target-sdk-version 29 --auto-add-overlay -o "$OUT/base.apk"
find "$APP/src" -name '*.java' -print0 | xargs -0 javac -source 8 -target 8 -nowarn -Xlint:-options \
    -classpath "$PLATFORM" -d "$OUT/classes"
mapfile -d '' classes < <(find "$OUT/classes" -name '*.class' -print0)
"$BT/d8" --min-api 26 --lib "$PLATFORM" --output "$OUT/dex" "${classes[@]}"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q "$OUT/unsigned.apk" classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign "${SIGN_ARGS[@]}" \
    --v1-signing-enabled true --v2-signing-enabled true --out "$APK" "$OUT/aligned.apk"
"$BT/apksigner" verify "$APK"
echo "$APK"
