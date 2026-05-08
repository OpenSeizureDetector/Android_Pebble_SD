#!/bin/bash
# Debug script for Graph History Persistence
# Run this after the app has been running for a few minutes and restarted

echo "=========================================="
echo "Graph History Persistence Debug Script"
echo "=========================================="
echo ""

# Check if adb is available
if ! command -v adb &> /dev/null; then
    echo "❌ ERROR: adb not found in PATH"
    exit 1
fi

# Get package name (adjust if different)
PACKAGE="uk.org.openseizuredetector"

echo "1. Checking database file existence..."
adb shell ls -la /data/data/$PACKAGE/databases/ 2>/dev/null | grep -i "osd"
echo ""

echo "2. Querying database schema for datapoints table..."
adb shell sqlite3 /data/data/$PACKAGE/databases/OsdData.db ".schema datapoints" 2>/dev/null
echo ""

echo "3. Checking if new history columns exist..."
adb shell sqlite3 /data/data/$PACKAGE/databases/OsdData.db ".columns datapoints" 2>/dev/null
echo ""

echo "4. Counting datapoints in database..."
adb shell sqlite3 /data/data/$PACKAGE/databases/OsdData.db "SELECT COUNT(*) FROM datapoints;" 2>/dev/null
echo ""

echo "5. Checking if any records have history data (watchBattHist NOT NULL)..."
adb shell sqlite3 /data/data/$PACKAGE/databases/OsdData.db \
    "SELECT COUNT(*) FROM datapoints WHERE watchBattHist IS NOT NULL;" 2>/dev/null
echo ""

echo "6. Checking most recent datapoint with history..."
adb shell sqlite3 /data/data/$PACKAGE/databases/OsdData.db \
    "SELECT dataTime, LENGTH(watchBattHist) as hist_len FROM datapoints WHERE watchBattHist IS NOT NULL ORDER BY dataTime DESC LIMIT 1;" 2>/dev/null
echo ""

echo "7. Sample of watch battery history JSON (first 200 chars)..."
adb shell sqlite3 /data/data/$PACKAGE/databases/OsdData.db \
    "SELECT SUBSTR(watchBattHist, 1, 200) FROM datapoints WHERE watchBattHist IS NOT NULL ORDER BY dataTime DESC LIMIT 1;" 2>/dev/null
echo ""

echo "8. Checking LogCat for history loader messages..."
echo "   (Last 20 lines mentioning CircBufHistoryLoader or history)"
adb logcat -d | grep -i "circbuf\|history" | tail -20
echo ""

echo "9. Checking LogCat for LogManager errors (last 10 lines)..."
adb logcat -d | grep "LogManager.*writeDatapoint\|LogManager.*Error" | tail -10
echo ""

echo "=========================================="
echo "Debug Complete"
echo "=========================================="
