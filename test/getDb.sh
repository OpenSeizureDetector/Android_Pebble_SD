#!/bin/sh
# This script copies the osdData database off the connected phone to a file called osdData.db in the current
# working directory.
# from https://stackoverflow.com/a/30377688
#
adb shell run-as uk.org.openseizuredetector chmod 777 /data/data/uk.org.openseizuredetector/databases
adb shell run-as uk.org.openseizuredetector chmod 777 /data/data/uk.org.openseizuredetector/databases/OsdData.db
adb shell run-as uk.org.openseizuredetector cp /data/data/uk.org.openseizuredetector/databases/OsdData.db /sdcard 
adb pull /sdcard/OsdData.db ./OsdData.db
adb shell rm /sdcard/OsdData.db



