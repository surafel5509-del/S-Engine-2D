#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# S Engine – local verification script (workstation only, not shipped).
#
#   tools/localtest.sh            compile everything + run the test suite
#   tools/localtest.sh --fast     compile the engine core only (no Android UI)
#   tools/localtest.sh --tests    compile + run tests only
#
# It type-checks the *entire* application (engine + editor UI) against the Android 34 platform
# jar, using the same Kotlin 1.9.23 compiler version as Gradle, then executes the JUnit suite in
# app/src/test. This is the fast inner loop; the authoritative build is `./gradlew assembleDebug`
# (see .github/workflows/android.yml).
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS="$ROOT/tools"
KTHOME="${SENGINE_KT_HOME:-$HOME/.local/kt}"
ANDROID_JAR="$KTHOME/sdk/android-34/android.jar"

if [ -x "$KTHOME/jdk17wheel/jdk4py/java-runtime/bin/java" ]; then
  export JAVA_HOME="$KTHOME/jdk17wheel/jdk4py/java-runtime"
  export PATH="$JAVA_HOME/bin:$KTHOME/kc/bin:$PATH"
fi

if ! command -v kotlinc >/dev/null 2>&1; then
  echo "kotlinc not found. Install with:" >&2
  echo "  npm install kotlin-compiler@1.9.23   # or sdkman / brew kotlin" >&2
  exit 2
fi
if [ ! -f "$ANDROID_JAR" ]; then
  echo "android.jar not found at $ANDROID_JAR – set SENGINE_KT_HOME" >&2
  exit 2
fi

OUT="$ROOT/build/local"
mkdir -p "$OUT"
MODE="${1:-all}"

SRC_MAIN=()
while IFS= read -r f; do SRC_MAIN+=("$f"); done < <(find "$ROOT/app/src/main/java" -name '*.kt' | sort)
SRC_TEST=()
while IFS= read -r f; do SRC_TEST+=("$f"); done < <(find "$ROOT/app/src/test/java" -name '*.kt' | sort)
SRC_STUB=("$TOOLS/stubs/rhino.kt" "$TOOLS/stubs/junit.kt" "$TOOLS/LocalTest.kt")

if [ "$MODE" = "--fast" ]; then
  SRC_MAIN=()
  while IFS= read -r f; do SRC_MAIN+=("$f"); done < <(find "$ROOT/app/src/main/java/com/sengine/engine" "$ROOT/app/src/main/java/com/sengine/platform" "$ROOT/app/src/main/java/com/sengine/ui" -name "*.kt" 2>/dev/null | sort)
fi

echo "▸ compiling ${#SRC_MAIN[@]} main sources + ${#SRC_TEST[@]} test sources"
rm -rf "$OUT/classes" && mkdir -p "$OUT/classes"

set +e
kotlinc "${SRC_MAIN[@]}" "${SRC_TEST[@]}" "${SRC_STUB[@]}" \
  -classpath "$ANDROID_JAR" \
  -jvm-target 17 -nowarn -J-Xmx3g \
  -d "$OUT/classes" > "$OUT/compile.log" 2>&1
STATUS=$?
set -e
if [ "$STATUS" != "0" ]; then
  grep -E "error:|Exception while analyzing|error\b" "$OUT/compile.log" | head -60 || true
  echo "✖ compilation failed – full log: $OUT/compile.log" >&2
  head -3 "$OUT/compile.log" >&2
  exit 1
fi

if [ "$MODE" != "--compile" ]; then
  echo "▸ running tests"
  java -cp "$OUT/classes:$ANDROID_JAR:$KTHOME/kc/lib/kotlin-stdlib.jar" com.sengine.tools.LocalTest "$OUT/classes" "${2:-}"
else
  echo "✔ compiled"
fi
