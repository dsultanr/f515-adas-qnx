#!/usr/bin/env bash
set -euo pipefail
base=$(cd "$(dirname "$0")/.." && pwd)
out="$base/build/host-tests"
mkdir -p "$out/android/util"
cat > "$out/android/util/Base64.java" <<'JAVA'
package android.util;
public final class Base64 {
 public static final int DEFAULT=0;
 public static byte[] decode(String value,int flags){return java.util.Base64.getDecoder().decode(value);}
}
JAVA
jdk=${JDK_BIN:-${JAVA_HOME:+$JAVA_HOME/bin}}
if [ -z "$jdk" ]; then jdk=$(dirname "$(command -v javac)"); fi
src="$base/app/src/su/dsr/f515/qnx_adas"
"$jdk/javac" --release 8 -d "$out" "$out/android/util/Base64.java" "$src/AdbClient.java" "$src/QnxPayload.java" "$src/ReleaseMetadata.java" "$base/tests/AccessTransportTest.java" "$base/tests/PayloadReleaseTest.java"
"$jdk/java" -ea -cp "$out" su.dsr.f515.qnx_adas.PayloadReleaseTest
"$jdk/java" -ea -cp "$out" su.dsr.f515.qnx_adas.AccessTransportTest
"$jdk/javac" --release 8 -classpath "$out" -d "$out" "$src/CalibrationFrame.java" "$src/CalibrationTransfer.java" "$base/tests/CalibrationTest.java"
"$jdk/java" -ea -cp "$out" su.dsr.f515.qnx_adas.CalibrationTest
