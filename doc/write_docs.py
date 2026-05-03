#!/usr/bin/env python3
"""Writes the three OSD onboarding setup guides to the doc subfolders."""
import os

BASE = "/home/graham/osd/Android_Pebble_SD/doc"

# ---------------------------------------------------------------------------
# PineTime guide
# ---------------------------------------------------------------------------
PINETIME = r"""# Setting Up OpenSeizureDetector with a PineTime Watch

This guide walks you through setting up OpenSeizureDetector using a **PineTime** smartwatch
as the motion sensor. The PineTime is a low-cost, open-source wrist watch specifically
supported for reliable tonic-clonic seizure detection.

## Before You Start

You will need:
- An Android phone running Android 8.0 or later
- A PineTime watch (available from [Pine64](https://www.pine64.org/pinetime/))
- Bluetooth enabled on your phone

---

## Step 1 - Welcome Screen

When you first install and launch OpenSeizureDetector, the setup wizard starts automatically.

![Welcome screen](images/01_welcome.png)

The wizard guides you through:
- Choosing your data source (the watch)
- Configuring the data source (pairing)
- Selecting seizure detection algorithms

Press **Next** to continue, or **Skip** to configure manually via Settings later.

---

## Step 2 - Choose Data Source

On the *Choose Data Source* screen, select **PineTime Watch (Recommended)**.

![Data source selection - PineTime selected](images/02_datasource_pinetime.png)

| Option | Description |
|--------|-------------|
| Phone (Demo Mode) | Uses the phone accelerometer - for testing only, not real seizure detection |
| **PineTime Watch (Recommended)** | Low-cost wrist watch - reliable seizure detection |
| Garmin Watch | Garmin smart watch - also supports heart rate monitoring |
| Network (Remote Monitoring) | Receives alarms from another OSD device on your Wi-Fi |

Press **Next** to continue.

---

## Step 3 - Configure PineTime Watch

The PineTime configuration screen guides you through three sub-steps.

![PineTime configuration screen](images/03_datasource_config_pinetime.png)

### 3a - Install the PineTime Updater App

The **PineTime Updater** companion app is needed to flash the OpenSeizureDetector firmware
onto your watch.

- If the updater is **not installed**: an **Install PineTime Updater App** button appears.
  Tap it to open Google Play, install the app, then press Back to return here.
- If it is **already installed**: you will see a green tick:
  *PineTime Updater app is installed*.

### 3b - Install OpenSeizureDetector Firmware onto the Watch

Tap **Install PineTime Firmware** to launch the PineTime Updater app.

**Note:** The updater will request Bluetooth permissions and a notification permission.
Please grant both so the firmware transfer can complete.

The updater scans for nearby PineTime watches, transfers the custom OpenSeizureDetector
firmware, then returns you to this screen automatically. The watch Bluetooth address is
recorded automatically - no manual entry needed.

### 3c - Connect (Scan) for the Watch

Tap **Scan for PineTime Watch** to search for your watch over Bluetooth. A list of nearby
Bluetooth devices appears - select your PineTime.

Once selected, the screen shows the device name and MAC address in green, for example:

    PineTime   MAC: AB:CD:EF:12:34:56

If *No device selected* is shown in orange, go back and scan again.

Press **Next** when your watch appears in green.

---

## Step 4 - Select Detection Algorithms

Choose which seizure detection algorithms to enable. You can select **more than one**.

![Algorithm selection screen](images/04_algorithms.png)

| Algorithm | Description |
|-----------|-------------|
| **ML Algorithm (Recommended)** | Machine Learning / AI detection. Good sensitivity with fewer false alarms. Improves over time via community data sharing. |
| Heart Rate Alerts | Detects abnormal heart rate. Currently requires a Garmin watch for reliable HR measurement. |
| **OSD Algorithm** | Original proven algorithm. Good for overnight use; may false-alarm on repetitive movements (brushing teeth, washing dishes etc.). |
| OSD with Flap Detection | Enhanced OSD that also detects arm flapping for maximum night-time tonic-clonic detection. |

**At least one algorithm must be selected** before Next is enabled.

**Recommended choice for PineTime:**
- ML Algorithm - best balance of sensitivity and false-alarm rate
- OSD Algorithm - proven reliable backup, especially overnight

### Algorithm configuration dialogs

After pressing Next, a short confirmation dialog appears for each enabled algorithm:

![OSD Algorithm configuration dialog](images/04b_algorithm_osd_dialog.png)

- **OSD Algorithm** - default settings applied automatically. Tap **OK**.
- **OSD with Flap Detection** - default settings applied. Tap **OK**.
- **ML Algorithm** - the recommended ML model is downloaded automatically. If no model is
  available, ML is gracefully disabled and can be re-enabled from Settings later.
- **Heart Rate Alerts** - default HR thresholds applied. Tap **OK**.

---

## Step 5 - Setup Complete

The final screen confirms your configuration.

![Setup complete screen](images/05_complete.png)

The summary shows:
- **Data Source** - your PineTime name and Bluetooth MAC address
- **Enabled Algorithms** - the algorithms that will run

Press **Get Started** to launch the main monitoring screen.

---

## What Happens Next

1. OpenSeizureDetector starts its background monitoring service
2. The app connects to your PineTime over Bluetooth
3. Wrist movement data streams continuously to the phone
4. If a seizure pattern is detected, the app raises an alarm and (if configured) sends
   notifications to your carers

All settings can be changed at any time from the **Settings** menu - you do not need to
re-run the wizard.

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| Watch not found during scan | Ensure watch is charged, on wrist, and phone Bluetooth is enabled |
| Firmware update fails | Keep watch within 1 metre of phone during the update |
| App not connecting after setup | Force-stop the app and restart; or re-scan via Settings - Bluetooth |
| PineTime Updater not on Play Store | Check your regional store or see the OpenSeizureDetector GitHub releases page |

For more information visit https://openseizuredetector.org.uk
"""

# ---------------------------------------------------------------------------
# Garmin guide
# ---------------------------------------------------------------------------
GARMIN = r"""# Setting Up OpenSeizureDetector with a Garmin Watch

This guide walks you through setting up OpenSeizureDetector using a **Garmin** smartwatch.
A Garmin watch provides reliable tonic-clonic seizure detection and, unlike the PineTime,
also delivers accurate continuous heart rate monitoring.

## Before You Start

You will need:
- An Android phone running Android 8.0 or later
- A supported Garmin watch (see https://openseizuredetector.org.uk for the supported device list)
- The **Garmin Connect** app installed on your phone
- Access to a **computer with a USB port** (required for initial watch app installation)

**Important:** The Garmin watch app file must be copied to the watch via a USB connection
from a computer - this step cannot be completed on a phone alone. Refer to the
*Garmin Seizure Detector* page on the OpenSeizureDetector website for the latest detailed
instructions: https://openseizuredetector.org.uk

---

## Step 1 - Welcome Screen

When you first install and launch OpenSeizureDetector, the setup wizard starts automatically.

![Welcome screen](images/01_welcome.png)

The wizard guides you through:
- Choosing your data source (the watch)
- Configuring the data source
- Selecting seizure detection algorithms

Press **Next** to continue, or **Skip** to configure manually via Settings later.

---

## Step 2 - Choose Data Source

On the *Choose Data Source* screen, select **Garmin Watch**.

![Data source selection - Garmin selected](images/02_datasource_garmin.png)

| Option | Description |
|--------|-------------|
| Phone (Demo Mode) | Uses the phone accelerometer - for testing only, not real seizure detection |
| PineTime Watch (Recommended) | Low-cost wrist watch - reliable seizure detection |
| **Garmin Watch** | Garmin smart watch - seizure detection plus heart rate monitoring |
| Network (Remote Monitoring) | Receives alarms from another OSD device on your Wi-Fi |

Press **Next** to continue.

---

## Step 3 - Configure Garmin Watch

The Garmin configuration screen summarises the steps needed to set up your watch.

![Garmin configuration screen](images/03_datasource_config_garmin.png)

**Note:** For full details refer to the *Garmin Seizure Detector* page on the
OpenSeizureDetector website: https://openseizuredetector.org.uk

### Step 3-1 - Pair the Watch with Garmin Connect

Pair your Garmin watch with your Android phone using the **Garmin Connect** app, following
Garmin's standard pairing instructions. This establishes the Bluetooth link between the
phone and watch that OpenSeizureDetector uses.

### Step 3-2 - Download the GarminSD Watch App File

On a **computer** (not the phone), download the GarminSD watch app `.prg` file from the
OpenSeizureDetector source code repository on GitHub:
https://github.com/OpenSeizureDetector

The installation of custom Garmin watch apps requires a computer with a USB connection -
it cannot currently be performed directly from the phone.

### Step 3-3 - Connect Your Watch to the Computer via USB

Plug your Garmin watch into the computer using its charging/data USB cable. The watch
will appear as a removable drive.

### Step 3-4 - Copy the Watch App File onto the Watch

Copy the downloaded `.prg` file into the `GARMIN/APPS` folder on the watch drive.
Safely eject the watch when the copy is complete.

### Step 3-5 - Launch the OpenSeizureDetector App on Your Watch

On the Garmin watch, navigate to **Apps** and launch the **OpenSeizureDetector** (GarminSD)
app. The watch app must be running before the phone app can connect.

**Important:** Make sure the Garmin watch app is running before pressing Next.

Press **Next** once the watch app is confirmed running.

---

## Step 4 - Select Detection Algorithms

Choose which seizure detection algorithms to enable. You can select **more than one**.

![Algorithm selection screen](images/04_algorithms.png)

| Algorithm | Description |
|-----------|-------------|
| **ML Algorithm (Recommended)** | Machine Learning / AI detection. Good sensitivity, fewer false alarms. Improves over time via community data sharing. |
| **Heart Rate Alerts** | Detects abnormal heart rate patterns. Garmin provides reliable continuous HR - highly recommended with a Garmin watch. |
| **OSD Algorithm** | Original proven algorithm. Good for overnight use; may false-alarm on repetitive movements (brushing teeth, washing dishes etc.). |
| OSD with Flap Detection | Enhanced OSD that also detects arm flapping for maximum night-time tonic-clonic detection. |

**At least one algorithm must be selected** before Next is enabled.

**Recommended choice for Garmin:**
- ML Algorithm - best balance of sensitivity and false-alarm rate
- Heart Rate Alerts - key advantage of a Garmin; uses the watch built-in HR sensor
- OSD Algorithm - proven reliable backup, especially overnight

### Algorithm configuration dialogs

After pressing Next, a short confirmation dialog appears for each enabled algorithm:

![Algorithm configuration dialog](images/04b_algorithm_dialog.png)

- **OSD Algorithm** - default settings applied. Tap **OK**.
- **OSD with Flap Detection** - default settings applied. Tap **OK**.
- **ML Algorithm** - the recommended ML model is downloaded automatically. If unavailable,
  ML is gracefully disabled and can be re-enabled from Settings once a model is available.
- **Heart Rate Alerts** - default HR thresholds are applied. Tap **OK**.
  You can fine-tune these thresholds in Settings after the wizard completes.

---

## Step 5 - Setup Complete

The final screen confirms your configuration.

![Setup complete screen](images/05_complete.png)

The summary shows:
- **Data Source** - Garmin Watch
- **Enabled Algorithms** - the algorithms that will run

Press **Get Started** to launch the main monitoring screen.

---

## What Happens Next

1. OpenSeizureDetector starts its background monitoring service
2. The Garmin watch app must be running on the watch for the phone to receive data
3. Wrist movement and heart rate data stream continuously to the phone
4. If a seizure pattern or abnormal heart rate is detected, the app raises an alarm and
   (if configured) sends notifications to your carers

All settings can be changed at any time from the **Settings** menu - you do not need to
re-run the wizard.

---

## Heart Rate Alert Configuration

Because Garmin provides reliable heart rate data, review the default HR alert thresholds
in Settings after setup:

| Setting | Default | Description |
|---------|---------|-------------|
| Max Heart Rate | 120 bpm | Alert if HR exceeds this value |
| Min Heart Rate | 40 bpm | Alert if HR drops below this value |

Adjust these to suit the person being monitored, based on advice from their medical team.

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| Watch app not found on USB drive | Navigate to the GARMIN/APPS folder; create it if it does not exist |
| Phone not receiving data from watch | Ensure the GarminSD app is actively running on the watch, not just installed |
| App shows Connecting indefinitely | Re-launch the GarminSD app on the watch and restart OSD on the phone |
| Heart rate not displayed | Wear the watch snugly; ensure the HR sensor window on the watch back is clean |
| Garmin Connect pairing fails | Follow Garmin official pairing instructions for your specific watch model |

For full Garmin setup instructions see: https://openseizuredetector.org.uk
"""

# ---------------------------------------------------------------------------
# Network guide
# ---------------------------------------------------------------------------
NETWORK = r"""# Setting Up OpenSeizureDetector as a Remote Alarm Receiver (Network Mode)

This guide explains how to set up a second Android phone (or tablet) as a **remote alarm
receiver** for an existing OpenSeizureDetector installation. This is useful when the person
being monitored is in a different room and you need alarm notifications on a second device,
for example a phone carried by a carer overnight.

## How Network Mode Works

```
[PineTime / Garmin watch]
         |
         | Bluetooth
         v
[Primary phone running OSD]  <-- detects seizures, raises alarms
         |
         | Wi-Fi (same local network)
         v
[This phone in Network mode] <-- receives alarm notifications remotely
```

Both devices must be on the **same Wi-Fi network**.

## Before You Start

You will need:
- A **second** Android phone or tablet running Android 8.0 or later
- An **existing** OpenSeizureDetector installation on a primary device (with a watch connected)
- Both devices on the same Wi-Fi network
- The IP address of the primary device (see below)

### Finding the Primary Device IP Address

On the primary phone, open OpenSeizureDetector and look at the **System** tab. You will see
a line like:

    Access Server at: http://192.168.1.50:8080

The four numbers separated by dots (e.g. `192.168.1.50`) are the IP address you need.
Alternatively, find the IP address in your router's connected devices list.

---

## Step 1 - Welcome Screen

Install OpenSeizureDetector on the second device. When you first launch it, the setup
wizard starts automatically.

![Welcome screen](images/01_welcome.png)

Press **Next** to continue.

---

## Step 2 - Choose Data Source

On the *Choose Data Source* screen, select **Network (Remote Monitoring)**.

![Data source selection - Network selected](images/02_datasource_network.png)

| Option | Description |
|--------|-------------|
| Phone (Demo Mode) | Uses the phone accelerometer - for testing only |
| PineTime Watch (Recommended) | PineTime wrist watch seizure detection |
| Garmin Watch | Garmin smart watch seizure detection |
| **Network (Remote Monitoring)** | Receives alarms from another OSD device on your Wi-Fi |

Press **Next** to continue.

---

## Step 3 - Configure Network Connection

The network configuration screen asks for the IP address of the primary device.

### Empty state (before entering IP)

![Network configuration - empty](images/03_datasource_config_network_empty.png)

The screen explains:
- This device will receive alarm notifications from the remote (primary) device
- Both devices must be on the same Wi-Fi network
- No seizure detection algorithms run on this device - those run on the primary device

### Entering the IP address

Tap the IP address field and type the IP address of the primary device.

![Network configuration - IP address entered and validated](images/03b_datasource_config_network_validated.png)

As you type a valid IP address (four numbers separated by dots, e.g. `192.168.1.50`),
the app automatically attempts to connect to the primary device on port 8080.

**Validation results:**

| Status | Meaning |
|--------|---------|
| Green: *Server validated successfully* | Connected - tap Next to continue |
| Orange: *Cannot reach server* | Check IP address and that both devices are on the same Wi-Fi |
| Red: *Invalid IP address format* | The address format is wrong - check you have typed it correctly |

A **Retry** button appears if validation fails - tap it after checking your settings.

**Note:** The **Next** button only becomes enabled once the primary device is successfully
reached. Algorithm selection is skipped entirely in Network mode - the algorithms are
configured on the primary device.

Press **Next** once validation succeeds (shown in green).

---

## Step 4 - Setup Complete

The wizard skips the algorithm selection step (since algorithms run on the primary device)
and goes straight to the completion screen.

![Setup complete screen](images/04_complete.png)

The summary shows:
- **Data Source** - Network (Remote Device)
- **Enabled Algorithms** - Configured on remote device

Press **Get Started** to launch the monitoring screen.

---

## What Happens Next

1. OpenSeizureDetector connects to the primary device over Wi-Fi
2. The primary device sends alarm status updates every 2 seconds
3. When the primary device raises a Warning or Alarm, this device:
   - Sounds the alarm tone
   - Displays a notification
   - Shows the alarm status on screen

The second device mirrors the alarm state of the primary device in near real-time.

---

## Important Notes

- The **primary device** must have OpenSeizureDetector running and connected to the watch
  at all times for remote monitoring to work
- If the Wi-Fi connection is lost, the secondary device will show a connection error
- The secondary device does **not** need a watch connected
- Alarm sensitivity and algorithm settings are controlled only on the primary device
- All settings can be changed at any time from the **Settings** menu

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| Cannot reach server | Confirm both phones are on the same Wi-Fi network (not mobile data) |
| IP address keeps changing | Set a static IP (DHCP reservation) for the primary phone in your router settings |
| Alarm notifications not appearing | Check Android notification permissions for OpenSeizureDetector on this device |
| Validation succeeds but no alarms received | Ensure OSD is running and connected to the watch on the primary device |
| Connection drops overnight | Disable Wi-Fi sleep/power-saving on both devices |

For more information visit https://openseizuredetector.org.uk
"""

files = {
    f"{BASE}/setup_pinetime/README.md": PINETIME,
    f"{BASE}/setup_garmin/README.md": GARMIN,
    f"{BASE}/setup_network/README.md": NETWORK,
}

for path, content in files.items():
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(content)
    print(f"Written: {path}  ({len(content)} chars)")

