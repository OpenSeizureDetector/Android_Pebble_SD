#!/usr/bin/env python3
"""
Script to take screenshots of the OpenSeizureDetector onboarding wizard
for documentation purposes.
"""

import subprocess
import time
import os
import xml.etree.ElementTree as ET

ADB = "/usr/local/Android_SDKs/platform-tools/adb"
PKG = "uk.org.openseizuredetector"
IMG_PINETIME = "/home/graham/osd/Android_Pebble_SD/doc/setup_pinetime/images"
IMG_GARMIN   = "/home/graham/osd/Android_Pebble_SD/doc/setup_garmin/images"
IMG_NETWORK  = "/home/graham/osd/Android_Pebble_SD/doc/setup_network/images"

def adb(*args):
    result = subprocess.run([ADB] + list(args), capture_output=True, text=True)
    return result.stdout.strip()

def adb_raw(*args):
    """Run adb and return raw bytes"""
    result = subprocess.run([ADB] + list(args), capture_output=True)
    return result.stdout

def tap(x, y):
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(0.5)

def screenshot(path):
    """Take a screenshot and save to path"""
    data = adb_raw("exec-out", "screencap", "-p")
    with open(path, 'wb') as f:
        f.write(data)
    size = os.path.getsize(path)
    print(f"  Screenshot saved: {path} ({size} bytes)")

def get_ui():
    """Dump UI hierarchy and return parsed XML root"""
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    data = adb_raw("pull", "/sdcard/ui.xml", "/dev/stdout")
    # the pull command mixes stderr/stdout oddly with /dev/stdout, use a temp file
    adb("pull", "/sdcard/ui.xml", "/tmp/osd_ui.xml")
    time.sleep(0.3)
    tree = ET.parse("/tmp/osd_ui.xml")
    return tree.getroot()

def find_button(root, text):
    """Find a button/node with the given text and return its center coords"""
    for node in root.iter('node'):
        if node.get('text', '').upper() == text.upper():
            bounds = node.get('bounds', '')
            # bounds format: [x1,y1][x2,y2]
            parts = bounds.replace('][', ',').replace('[', '').replace(']', '').split(',')
            if len(parts) == 4:
                x = (int(parts[0]) + int(parts[2])) // 2
                y = (int(parts[1]) + int(parts[3])) // 2
                return x, y
    return None

def find_radio(root, text_contains):
    """Find a radio button containing the text"""
    for node in root.iter('node'):
        text = node.get('text', '')
        if text_contains.lower() in text.lower():
            bounds = node.get('bounds', '')
            parts = bounds.replace('][', ',').replace('[', '').replace(']', '').split(',')
            if len(parts) == 4:
                x = (int(parts[0]) + int(parts[2])) // 2
                y = (int(parts[1]) + int(parts[3])) // 2
                return x, y, text
    return None

def reset_app():
    print("Resetting app data...")
    adb("shell", "pm", "clear", PKG)
    time.sleep(0.5)

def launch_app():
    print("Launching app...")
    adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(4)

def click_next(root=None):
    """Click the Next button"""
    if root is None:
        root = get_ui()
    coords = find_button(root, "NEXT")
    if coords:
        tap(coords[0], coords[1])
        time.sleep(2)
        return True
    # Try "GET STARTED" for last page
    coords = find_button(root, "GET STARTED")
    if coords:
        tap(coords[0], coords[1])
        time.sleep(2)
        return True
    # Fallback to known position
    print("  Warning: Next button not found via UI dump, using fallback position")
    tap(922, 2232)
    time.sleep(2)
    return True

def take_pinetime_screenshots():
    print("\n=== PineTime Setup Screenshots ===")
    reset_app()
    launch_app()

    # 1. Welcome screen
    print("Step 1: Welcome screen")
    screenshot(f"{IMG_PINETIME}/01_welcome.png")

    # Click Next
    root = get_ui()
    click_next(root)

    # 2. Data Source screen - PineTime should be default or we need to select it
    print("Step 2: Data Source screen")
    root = get_ui()
    pinetime = find_radio(root, "PineTime")
    if pinetime:
        print(f"  Selecting PineTime at {pinetime[0]},{pinetime[1]}: {pinetime[2]!r}")
        tap(pinetime[0], pinetime[1])
        time.sleep(1)
    screenshot(f"{IMG_PINETIME}/02_datasource_pinetime.png")

    # Click Next -> DataSource Config
    root = get_ui()
    click_next(root)

    # 3. PineTime config screen
    print("Step 3: PineTime configuration screen")
    time.sleep(1)
    screenshot(f"{IMG_PINETIME}/03_datasource_config_pinetime.png")

    # Click Next -> Algorithms
    root = get_ui()
    click_next(root)

    # 4. Algorithm selection screen
    print("Step 4: Algorithm selection screen")
    time.sleep(1)
    screenshot(f"{IMG_PINETIME}/04_algorithms.png")

    # Click Next -> may show dialog for OSD config
    root = get_ui()
    click_next(root)
    time.sleep(1)

    # Check if dialog appeared and dismiss it
    root = get_ui()
    ok_btn = find_button(root, "OK")
    if ok_btn:
        print("  OSD Algorithm dialog appeared - dismissing")
        screenshot(f"{IMG_PINETIME}/04b_algorithm_osd_dialog.png")
        tap(ok_btn[0], ok_btn[1])
        time.sleep(1)

    # 5. Complete screen
    print("Step 5: Complete screen")
    time.sleep(1)
    root = get_ui()
    # May be on complete, or may need to click next once more
    screenshot(f"{IMG_PINETIME}/05_complete.png")


def take_garmin_screenshots():
    print("\n=== Garmin Setup Screenshots ===")
    reset_app()
    launch_app()

    # 1. Welcome screen
    print("Step 1: Welcome screen")
    screenshot(f"{IMG_GARMIN}/01_welcome.png")

    # Click Next -> Data Source
    root = get_ui()
    click_next(root)

    # 2. Data Source screen - Select Garmin
    print("Step 2: Data Source screen - selecting Garmin")
    root = get_ui()
    garmin = find_radio(root, "Garmin")
    if garmin:
        print(f"  Selecting Garmin at {garmin[0]},{garmin[1]}: {garmin[2]!r}")
        tap(garmin[0], garmin[1])
        time.sleep(1)
    screenshot(f"{IMG_GARMIN}/02_datasource_garmin.png")

    # Click Next -> DataSource Config
    root = get_ui()
    click_next(root)

    # 3. Garmin config screen
    print("Step 3: Garmin configuration screen")
    time.sleep(1)
    screenshot(f"{IMG_GARMIN}/03_datasource_config_garmin.png")

    # Click Next -> Algorithms
    root = get_ui()
    click_next(root)

    # 4. Algorithm selection screen
    print("Step 4: Algorithm selection screen")
    time.sleep(1)
    screenshot(f"{IMG_GARMIN}/04_algorithms.png")

    # Click Next -> may show dialog
    root = get_ui()
    click_next(root)
    time.sleep(1)

    # Dismiss any dialogs
    root = get_ui()
    ok_btn = find_button(root, "OK")
    if ok_btn:
        print("  Algorithm dialog appeared - dismissing")
        screenshot(f"{IMG_GARMIN}/04b_algorithm_dialog.png")
        tap(ok_btn[0], ok_btn[1])
        time.sleep(1)

    # 5. Complete screen
    print("Step 5: Complete screen")
    time.sleep(1)
    screenshot(f"{IMG_GARMIN}/05_complete.png")


def take_network_screenshots():
    print("\n=== Network Setup Screenshots ===")
    reset_app()
    launch_app()

    # 1. Welcome screen
    print("Step 1: Welcome screen")
    screenshot(f"{IMG_NETWORK}/01_welcome.png")

    # Click Next -> Data Source
    root = get_ui()
    click_next(root)

    # 2. Data Source screen - Select Network
    print("Step 2: Data Source screen - selecting Network")
    root = get_ui()
    network = find_radio(root, "Network")
    if network:
        print(f"  Selecting Network at {network[0]},{network[1]}: {network[2]!r}")
        tap(network[0], network[1])
        time.sleep(1)
    screenshot(f"{IMG_NETWORK}/02_datasource_network.png")

    # Click Next -> DataSource Config (Network)
    root = get_ui()
    click_next(root)

    # 3. Network config screen
    print("Step 3: Network configuration screen (empty)")
    time.sleep(1)
    screenshot(f"{IMG_NETWORK}/03_datasource_config_network_empty.png")

    # Type an example IP address
    root = get_ui()
    for node in root.iter('node'):
        if 'EditText' in node.get('class', '') or node.get('hint', ''):
            bounds = node.get('bounds', '')
            parts = bounds.replace('][', ',').replace('[', '').replace(']', '').split(',')
            if len(parts) == 4:
                x = (int(parts[0]) + int(parts[2])) // 2
                y = (int(parts[1]) + int(parts[3])) // 2
                tap(x, y)
                time.sleep(0.5)
                break

    # Try to find input field by looking for text input
    # Type example IP - this will fail validation but shows the field
    adb("shell", "input", "text", "192.168.1.100")
    time.sleep(2)
    screenshot(f"{IMG_NETWORK}/03b_datasource_config_network_ip_entered.png")

    # The Next button is disabled until server validates, so just take final screenshot
    print("Note: Network config requires live server - capturing UI state only")


if __name__ == "__main__":
    take_pinetime_screenshots()
    take_garmin_screenshots()
    take_network_screenshots()
    print("\nAll screenshots complete!")

