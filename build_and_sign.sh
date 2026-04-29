#!/bin/bash
# Headless build and sign script for DT2S Module

set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Dynamic SDK Detection
if [ -n "$ANDROID_HOME" ]; then
    SDK_PATH="$ANDROID_HOME"
elif [ -n "$ANDROID_SDK_ROOT" ]; then
    SDK_PATH="$ANDROID_SDK_ROOT"
elif [ -d "$HOME/Android/Sdk" ]; then
    SDK_PATH="$HOME/Android/Sdk"
elif [ -d "/usr/lib/android-sdk" ]; then
    SDK_PATH="/usr/lib/android-sdk"
elif [ -d "$PROJECT_DIR/../Android/Sdk" ]; then
    SDK_PATH="$(cd "$PROJECT_DIR/../Android/Sdk" && pwd)"
else
    echo "Error: Android SDK not found. Please set ANDROID_HOME."
    exit 1
fi

# Find latest build-tools
BUILD_TOOLS=$(ls -d $SDK_PATH/build-tools/* | sort -V | tail -n 1)
if [ -z "$BUILD_TOOLS" ]; then
    echo "Error: No build-tools found in $SDK_PATH"
    exit 1
fi

KEYSTORE="$PROJECT_DIR/debug.keystore"
KS_PASS="android"

echo "Using SDK: $SDK_PATH"
echo "Using Build Tools: $BUILD_TOOLS"

echo "[1/4] Cleaning and building APK..."
cd "$PROJECT_DIR"
./gradlew clean assembleDebug --quiet

APK_RAW=$(find "$PROJECT_DIR/build/outputs/apk/debug" -name "*.apk" | head -n 1)

if [ -z "$APK_RAW" ]; then
    echo "Error: APK not found."
    exit 1
fi

echo "[2/4] Aligning APK..."
"$BUILD_TOOLS/zipalign" -f 4 "$APK_RAW" "$PROJECT_DIR/dt2s-aligned.apk"

echo "[3/4] Signing APK..."
# Force regeneration if keystore is missing
if [ ! -f "$KEYSTORE" ]; then
    echo "Generating new debug keystore (PKCS12)..."
    keytool -genkey -v -keystore "$KEYSTORE" -storepass "$KS_PASS" -alias androiddebugkey -keypass "$KS_PASS" -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" -storetype PKCS12
fi

"$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" --out "$PROJECT_DIR/double-tap-sleep-final.apk" "$PROJECT_DIR/dt2s-aligned.apk"

echo "[4/4] Verifying APK..."
"$BUILD_TOOLS/apksigner" verify "$PROJECT_DIR/double-tap-sleep-final.apk"

rm "$PROJECT_DIR/dt2s-aligned.apk"

echo "------------------------------------------------"
echo "Build Successful: $PROJECT_DIR/double-tap-sleep-final.apk"
echo "------------------------------------------------"
