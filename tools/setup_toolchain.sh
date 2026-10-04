#!/usr/bin/env bash
# Recreates the local Kotlin/JDK/Android toolchain used by tools/localtest.sh.
#   tools/setup_toolchain.sh
# Needs network access to the npm registry, PyPI and github.com (codeload).
set -euo pipefail
HOME_KT="${SENGINE_KT_HOME:-$HOME/.local/kt}"
mkdir -p "$HOME_KT/kc/bin" "$HOME_KT/sdk/android-34"
cd /tmp

echo "▸ JDK 17 (jdk4py wheel from PyPI)"
pip3 download jdk4py==17.0.9.2 --no-deps -d /tmp/sengine_dl >/dev/null
rm -rf "$HOME_KT/jdk17wheel"; mkdir -p "$HOME_KT/jdk17wheel"
unzip -q -o /tmp/sengine_dl/jdk4py-17.0.9.2-*.whl -d "$HOME_KT/jdk17wheel"

echo "▸ Kotlin 1.9.23 compiler (npm package)"
rm -rf /tmp/sengine_kc; mkdir -p /tmp/sengine_kc; cd /tmp/sengine_kc
npm pack kotlin-compiler@1.9.23 >/dev/null
tar xzf kotlin-compiler-1.9.23.tgz
rm -rf "$HOME_KT/kc"; mkdir -p "$HOME_KT/kc" && mv package/* "$HOME_KT/kc/" && rm -rf package

echo "▸ android.jar (API 33 stubs, GitHub mirror)"
cd /tmp
curl -sSL -o sengine_ap.tar.gz "https://codeload.github.com/JordanSamhi/Android-platforms/tar.gz/refs/heads/master"
tar xzf sengine_ap.tar.gz -C /tmp "Android-platforms-master/jars/stubs/android-33/android.jar"
cp /tmp/Android-platforms-master/jars/stubs/android-33/android.jar "$HOME_KT/sdk/android-34/android.jar"
rm -rf /tmp/sengine_ap.tar.gz /tmp/Android-platforms-master

cat > "$HOME_KT/env.sh" <<ENV
export SENGINE_KT_HOME=$HOME_KT
export JAVA_HOME="\$SENGINE_KT_HOME/jdk17wheel/jdk4py/java-runtime"
export PATH="\$JAVA_HOME/bin:\$SENGINE_KT_HOME/kc/bin:\$PATH"
ENV
echo "✔ toolchain ready — source $HOME_KT/env.sh"
