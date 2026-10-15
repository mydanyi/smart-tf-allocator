#!/usr/bin/env bash
#
# Build the standalone gamepad UI QA instrumentation APK for the Armada TF
# companion app (target: io.github.mydanyi.armadatf).
#
# Uses only the standard Android SDK command-line tools that already ship inside
# the remote SDK zip: javac (from the JDK), plus d8 / aapt2 / zipalign /
# apksigner from $ANDROID_HOME/build-tools/<latest>. No Gradle, no new
# dependencies, no production test hooks.
#
# Run this script in an environment with JDK 17 and the Android SDK available.
# Invoke it as: bash qa/build-instrumentation.sh
# It creates ONLY its own output folders under $QA_OUTPUT (default
# /work/cache/ui-qa) and never deletes unrelated files.
set -euo pipefail

# --- configuration -----------------------------------------------------------

QA_OUTPUT="${QA_OUTPUT:-/work/cache/ui-qa}"
TARGET_PACKAGE="io.github.mydanyi.armadatf"
TEST_PACKAGE="io.github.mydanyi.armadatf.qa"
TEST_RUNNER="${TEST_PACKAGE}/${TEST_PACKAGE}.GamepadUiInstrumentation"

ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "${ANDROID_HOME}" ] || [ ! -d "${ANDROID_HOME}" ]; then
    echo "error: set ANDROID_HOME (or ANDROID_SDK_ROOT) to a valid Android SDK directory" >&2
    exit 2
fi

ANDROID_JAR="${ANDROID_HOME}/platforms/android-35/android.jar"
if [ ! -f "${ANDROID_JAR}" ]; then
    echo "error: missing ${ANDROID_JAR} (install the platforms;android-35 package)" >&2
    exit 2
fi

if [ ! -d "${ANDROID_HOME}/build-tools" ]; then
    echo "error: missing ${ANDROID_HOME}/build-tools" >&2
    exit 2
fi
BUILD_TOOLS_DIR="$(find "${ANDROID_HOME}/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
D8="${BUILD_TOOLS_DIR}/d8"
AAPT2="${BUILD_TOOLS_DIR}/aapt2"
ZIPALIGN="${BUILD_TOOLS_DIR}/zipalign"
APKSIGNER="${BUILD_TOOLS_DIR}/apksigner"
for tool in "${D8}" "${AAPT2}" "${ZIPALIGN}" "${APKSIGNER}"; do
    if [ ! -x "${tool}" ]; then
        echo "error: required SDK tool not found/executable: ${tool}" >&2
        exit 2
    fi
done

# Same app debug keystore. Primary location is $ANDROID_USER_HOME/debug.keystore.
# Fallback (only the standard remote location that already exists on the docker
# image) is $HOME/.android/debug.keystore.
KEYSTORE_DIR="${ANDROID_USER_HOME:-${HOME}/.android}"
KEYSTORE="${KEYSTORE_DIR}/debug.keystore"
if [ ! -f "${KEYSTORE}" ]; then
    echo "error: debug keystore not found at ${KEYSTORE}" >&2
    echo "       set ANDROID_USER_HOME to the directory that holds debug.keystore" >&2
    exit 2
fi
KS_ALIAS="androiddebugkey"
KS_PASS="android"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
QA_SOURCES=(
    "${SCRIPT_DIR}/GamepadUiInstrumentation.java"
    "${SCRIPT_DIR}/LayoutUiInstrumentation.java"
)
QA_MANIFEST="${SCRIPT_DIR}/AndroidManifest.xml"
for src in "${QA_SOURCES[@]}"; do
    if [ ! -f "${src}" ]; then
        echo "error: expected instrumentation source ${src} next to this script" >&2
        exit 2
    fi
done
if [ ! -f "${QA_MANIFEST}" ]; then
    echo "error: expected ${QA_MANIFEST} next to this script" >&2
    exit 2
fi

# --- own output folders only -------------------------------------------------

BUILD_DIR="${QA_OUTPUT}/gamepad-qa-build"
CLASSES_DIR="${BUILD_DIR}/classes"
DEX_DIR="${BUILD_DIR}/dex"
APK_OUT="${QA_OUTPUT}/armada-tf-gamepad-qa.apk"

rm -rf "${BUILD_DIR}"
mkdir -p "${CLASSES_DIR}" "${DEX_DIR}" "${QA_OUTPUT}"

echo "==> SDK:         ${ANDROID_HOME}"
echo "==> build-tools: ${BUILD_TOOLS_DIR}"
echo "==> output:      ${APK_OUT}"

# --- 1. compile --------------------------------------------------------------

echo "==> javac"
javac -encoding UTF-8 -source 17 -target 17 \
    -classpath "${ANDROID_JAR}" \
    -d "${CLASSES_DIR}" \
    "${QA_SOURCES[@]}"

# --- 2. dex ------------------------------------------------------------------

echo "==> d8"
"${D8}" \
    --lib "${ANDROID_JAR}" \
    --min-api 26 \
    --output "${DEX_DIR}" \
    $(find "${CLASSES_DIR}" -name '*.class')

# --- 3. link manifest (no app resources) -------------------------------------

echo "==> aapt2 link"
"${AAPT2}" link \
    -o "${BUILD_DIR}/base.apk" \
    -I "${ANDROID_JAR}" \
    --manifest "${QA_MANIFEST}" \
    --min-sdk-version 26 \
    --target-sdk-version 35 \
    --version-code 1 \
    --version-name 0.1.0

# --- 4. package classes.dex into the APK -------------------------------------

echo "==> package classes.dex"
cp "${BUILD_DIR}/base.apk" "${BUILD_DIR}/unsigned.apk"
# Prefer zip; fall back to the JDK's jar (always present alongside javac). Both
# only ever write into our own output folder.
if command -v zip >/dev/null 2>&1; then
    ( cd "${DEX_DIR}" && zip -q "${BUILD_DIR}/unsigned.apk" classes.dex )
else
    ( cd "${DEX_DIR}" && jar uf "${BUILD_DIR}/unsigned.apk" classes.dex )
fi

# --- 5. align + sign with the app debug keystore -----------------------------

echo "==> zipalign"
"${ZIPALIGN}" -f -p 4 "${BUILD_DIR}/unsigned.apk" "${BUILD_DIR}/aligned.apk"

echo "==> apksigner"
"${APKSIGNER}" sign \
    --ks "${KEYSTORE}" \
    --ks-key-alias "${KS_ALIAS}" \
    --ks-pass "pass:${KS_PASS}" \
    --key-pass "pass:${KS_PASS}" \
    --out "${APK_OUT}" \
    "${BUILD_DIR}/aligned.apk"

"${APKSIGNER}" verify --print-certs "${APK_OUT}" >/dev/null 2>&1 || true

cat <<EOF

Built QA APK: ${APK_OUT}

Install (target app io.github.mydanyi.armadatf must already be installed):
  adb install -r ${APK_OUT}

Run:
  adb shell am instrument -w ${TEST_RUNNER}
  adb shell am instrument -w -e suite layout -e locale zh \
      ${TEST_PACKAGE}/${TEST_PACKAGE}.LayoutUiInstrumentation
EOF
