#!/bin/bash
set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

ANDROID_JAR="$HOME/android-sdk/platforms/android-33/android.jar"
JAVAC="/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk/bin/javac"
BUILD_DIR="$PROJECT_DIR/build"
GEN_DIR="$BUILD_DIR/gen"
OBJ_DIR="$BUILD_DIR/obj"
DEX_DIR="$BUILD_DIR/dex"
APK_UNSIGNED="$BUILD_DIR/voiceportal-unsigned.apk"
APK_SIGNED="$BUILD_DIR/voiceportal.apk"
KEYSTORE="$PROJECT_DIR/debug.keystore"

echo "=== VoicePortal Launcher Build ==="

# Clean
rm -rf "$BUILD_DIR"
mkdir -p "$GEN_DIR" "$OBJ_DIR" "$DEX_DIR"

# Step 1: Compile resources with aapt2
echo "[1/6] Compiling resources..."
COMPILED_DIR="$BUILD_DIR/compiled"
mkdir -p "$COMPILED_DIR"

for dir in res/values res/layout res/xml res/drawable res/mipmap-hdpi; do
    if [ -d "$dir" ]; then
        for f in "$dir"/*; do
            if [ -f "$f" ]; then
                aapt2 compile "$f" -o "$COMPILED_DIR/" 2>&1
            fi
        done
    fi
done

echo "   Compiled $(ls "$COMPILED_DIR"/ | wc -l) resource files"

# Step 2: Link resources to generate R.java and base APK
echo "[2/6] Linking resources..."
aapt2 link \
    -I "$ANDROID_JAR" \
    --manifest AndroidManifest.xml \
    --java "$GEN_DIR" \
    -o "$APK_UNSIGNED" \
    --auto-add-overlay \
    -A assets \
    "$COMPILED_DIR"/*.flat

echo "   R.java generated"

# Step 3: Compile Java source (use JDK 17 - d8 has bugs with JDK 21 bytecode)
echo "[3/6] Compiling Java sources..."
JAVA_FILES=$(find src -name "*.java")
JAVA_FILES="$JAVA_FILES $(find "$GEN_DIR" -name "*.java")"

"$JAVAC" \
    --release 11 \
    -classpath "$ANDROID_JAR" \
    -d "$OBJ_DIR" \
    $JAVA_FILES

echo "   Compiled $(find "$OBJ_DIR" -name "*.class" | wc -l) class files"

# Step 4: Convert to DEX
echo "[4/6] Converting to DEX bytecode..."
d8 \
    --lib "$ANDROID_JAR" \
    --min-api 29 \
    --output "$DEX_DIR" \
    $(find "$OBJ_DIR" -name "*.class")

echo "   DEX file created"

# Step 5: Add DEX to APK
echo "[5/6] Packaging APK..."
cd "$DEX_DIR"
zip -j "$APK_UNSIGNED" classes.dex
cd "$PROJECT_DIR"

echo "   DEX added to APK"

# Step 6: Sign APK
echo "[6/6] Signing APK..."

if [ ! -f "$KEYSTORE" ]; then
    echo "   Creating debug keystore..."
    keytool -genkeypair \
        -dname "CN=Debug,O=VoicePortal,C=US" \
        -keystore "$KEYSTORE" \
        -storepass android \
        -keypass android \
        -alias debug \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000
fi

apksigner sign \
    --ks "$KEYSTORE" \
    --ks-pass pass:android \
    --ks-key-alias debug \
    --key-pass pass:android \
    --out "$APK_SIGNED" \
    "$APK_UNSIGNED"

echo ""
echo "=== BUILD SUCCESSFUL ==="
echo "APK: $APK_SIGNED"
echo "Size: $(du -h "$APK_SIGNED" | cut -f1)"
echo ""
echo "Install with:"
echo "  cp $APK_SIGNED /sdcard/"
echo "  # Then open with file manager, or:"
echo "  pm install $APK_SIGNED"
