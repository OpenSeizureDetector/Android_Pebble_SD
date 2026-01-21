#!/bin/bash
# Fix 16KB alignment for native libraries in APK
# Usage: ./fix_16kb_alignment.sh [input.apk] [output.apk]

set -e

INPUT_APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
OUTPUT_APK="${2:-app/build/outputs/apk/debug/app-debug-aligned.apk}"

echo "=== 16KB APK Alignment Tool ==="
echo "Input APK: $INPUT_APK"
echo "Output APK: $OUTPUT_APK"
echo ""

# Find zipalign tool
if [ -n "$ANDROID_HOME" ]; then
    ZIPALIGN="$ANDROID_HOME/build-tools/*/zipalign"
    ZIPALIGN=$(ls $ZIPALIGN 2>/dev/null | tail -1)
fi

if [ -z "$ZIPALIGN" ] || [ ! -f "$ZIPALIGN" ]; then
    echo "ERROR: zipalign not found. Please set ANDROID_HOME environment variable."
    echo "Example: export ANDROID_HOME=~/Android/Sdk"
    exit 1
fi

echo "Using zipalign: $ZIPALIGN"
echo ""

# Check current alignment
echo "=== Checking current alignment ==="
$ZIPALIGN -c -p 16384 "$INPUT_APK" 2>&1 || true
echo ""

# Align the APK with 16KB (16384 bytes) page alignment
echo "=== Aligning APK to 16KB boundaries ==="
$ZIPALIGN -f -p 16384 "$INPUT_APK" "$OUTPUT_APK"
echo ""

# Verify alignment
echo "=== Verifying 16KB alignment ==="
if $ZIPALIGN -c -p 16384 "$OUTPUT_APK"; then
    echo "✅ SUCCESS: APK is properly aligned for 16KB devices"
    echo ""
    echo "Aligned APK: $OUTPUT_APK"
    ls -lh "$OUTPUT_APK"
else
    echo "❌ FAILED: APK alignment verification failed"
    exit 1
fi

