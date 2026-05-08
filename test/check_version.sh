#!/bin/bash
# Check database version and schema WITHOUT deleting it

echo "=========================================="
echo "Database Version & Migration Check"
echo "=========================================="
echo ""

PACKAGE="uk.org.openseizuredetector"
DB_PATH="/data/data/$PACKAGE/databases/OsdData.db"

echo "1. Checking database version..."
adb shell sqlite3 $DB_PATH "PRAGMA user_version;" 2>/dev/null
echo ""

echo "2. Checking datapoints table schema..."
adb shell sqlite3 $DB_PATH ".schema datapoints" 2>/dev/null
echo ""

echo "3. Checking if history columns exist..."
adb shell sqlite3 $DB_PATH ".columns datapoints" 2>/dev/null | grep -i "hist"
if [ $? -eq 0 ]; then
    echo "✅ History columns FOUND"
else
    echo "❌ History columns NOT FOUND"
fi
echo ""

echo "4. Sample of column names..."
adb shell sqlite3 $DB_PATH ".columns datapoints" 2>/dev/null
echo ""

echo "=========================================="
