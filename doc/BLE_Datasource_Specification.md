# BLE Data Source Specification (BLE2)

> **Current implementation:** `SdDataSourceBLE2.java`  
> **BLE library:** [BLESSED for Android](https://github.com/weliem/blessed-android) v2  
> **Status of BLE v1:** `SdDataSourceBLE` is retained in the codebase but is **deprecated** and will be removed in a future release. All new device firmware should target the BLE2 protocol documented here.

This document describes the GATT services and characteristics that a wearable device must provide for `SdDataSourceBLE2` to work correctly. It also describes the alarm-state feedback characteristic that the phone writes back to the device after each analysis cycle.

---

## Overview

Communication is primarily binary (GATT notifications/writes). The phone acts as a **central** (GATT client); the wearable is the **peripheral** (GATT server).

On connection the phone:
1. Discovers all services and subscribes to relevant notification characteristics.
2. Reads one-shot characteristics (battery level, device identity, firmware version, acceleration format).
3. Buffers incoming acceleration notifications until a full 5-second window (125 samples at 25 Hz) is assembled.
4. Runs seizure detection algorithms and writes the resulting alarm-state byte back to the status characteristic.

BLE2 also starts an Android-side **Current Time Service** (GATT server) so that devices can synchronise their RTC over BLE.

---

## Required Services

### 1. OSD Service (mandatory)

The OSD Service carries all OpenSeizureDetector-specific data.

| UUID | `000085e9-0000-1000-8000-00805f9b34fb` |

| Characteristic | UUID | Dir | Description |
|---|---|---|---|
| `CHAR_OSD_ACC_DATA` | `000085e9-0001-1000-8000-00805f9b34fb` | Notify → phone | Acceleration samples. Format determined by `CHAR_OSD_ACC_FMT` (see §Acceleration Format below). The phone subscribes to notifications; each notification may contain multiple samples. |
| `CHAR_OSD_BATT_DATA` | `000085e9-0002-1000-8000-00805f9b34fb` | Notify/Read → phone | Watch battery level. One unsigned byte; value is battery percentage (0–100). |
| `CHAR_OSD_WATCH_ID` | `000085e9-0003-1000-8000-00805f9b34fb` | Read → phone | UTF-8 string identifier for the watch type (e.g. `"BangleJS"`). |
| `CHAR_OSD_WATCH_FW` | `000085e9-0004-1000-8000-00805f9b34fb` | Read → phone | UTF-8 string firmware version (e.g. `"V0.12.3"`). |
| `CHAR_OSD_ACC_FMT` | `000085e9-0005-1000-8000-00805f9b34fb` | Read → phone | One byte; specifies the encoding of `CHAR_OSD_ACC_DATA` samples (see §Acceleration Format). |
| `CHAR_OSD_STATUS` | `000085e9-0006-1000-8000-00805f9b34fb` | Write → device | Alarm state feedback. The phone writes one byte containing the current `AlarmState` integer after each analysis cycle (see §Alarm State Feedback). |

---

### 2. InfiniTime Motion Service (alternative to CHAR_OSD_ACC_DATA)

Devices running InfiniTime firmware (e.g. PineTime) expose motion data on a different service instead of the OSD service above. The phone recognises either; both cannot be present simultaneously on the same device.

| UUID | `00030000-78fc-48fe-8e23-433b3a1942d0` |

| Characteristic | UUID | Dir | Description |
|---|---|---|---|
| `CHAR_INFINITIME_ACC_DATA` | `00030002-78fc-48fe-8e23-433b3a1942d0` | Notify → phone | 16-bit little-endian signed X, Y, Z triplets. Format is implicitly `ACC_FMT_3D` regardless of `CHAR_OSD_ACC_FMT`. |
| `CHAR_INFINITIME_OSD_STATUS` | `00030078-78fc-48fe-8e23-433b3a1942d0` | Write → device | Alarm state feedback (same single-byte format as `CHAR_OSD_STATUS`). |

---

### 3. Standard Heart Rate Service (optional but recommended)

| UUID | `0000180d-0000-1000-8000-00805f9b34fb` |

| Characteristic | UUID | Dir | Description |
|---|---|---|---|
| `CHAR_HEART_RATE_MEASUREMENT` | `00002a37-0000-1000-8000-00805f9b34fb` | Notify → phone | Standard BLE Heart Rate Measurement format. Byte 0: flags. If bit 0 of flags = 0, HR is a `uint8` in byte 1; if bit 0 = 1, HR is a `uint16` in bytes 1–2 (little-endian). |

---

### 4. Standard Battery Service (optional)

| UUID | `0000180f-0000-1000-8000-00805f9b34fb` |

| Characteristic | UUID | Dir | Description |
|---|---|---|---|
| `CHAR_BATT_DATA` | `00002a19-0000-1000-8000-00805f9b34fb` | Notify/Read → phone | Battery level percentage as one `uint8`. Equivalent to `CHAR_OSD_BATT_DATA`; either may be present. |

---

### 5. Device Information Service (optional)

Provides manufacturer and version strings. All characteristics are UTF-8 strings.

| UUID | `0000180a-0000-1000-8000-00805f9b34fb` |

| Characteristic | UUID | Field populated in app |
|---|---|---|
| Manufacturer Name | `00002a29-0000-1000-8000-00805f9b34fb` | `SdData.watchManuf` |
| Model Number | `00002a24-0000-1000-8000-00805f9b34fb` | `SdData.watchPartNo` |
| Serial Number | `00002a25-0000-1000-8000-00805f9b34fb` | (read but not stored; MAC address is used instead) |
| Firmware Revision | `00002a26-0000-1000-8000-00805f9b34fb` | `SdData.watchSdVersion` |
| Hardware Revision | `00002a27-0000-1000-8000-00805f9b34fb` | `SdData.watchFwVersion` |
| Software Revision | `00002a28-0000-1000-8000-00805f9b34fb` | `SdData.watchSdName` |

---

## Acceleration Format

The format of `CHAR_OSD_ACC_DATA` notification payloads is controlled by the one-byte value read from `CHAR_OSD_ACC_FMT`. If this characteristic is not present the phone defaults to `ACC_FMT_8BIT`.

| Code | Constant | Payload encoding                                                                              | Scaling |
|---|---|-----------------------------------------------------------------------------------------------|---|
| `0` | `ACC_FMT_8BIT` | Array of signed `int8` values; each value is the vector magnitude of the 3-axis acceleration. | `mg = 1000 × raw / 64` (1 g ≈ 44 raw units) |
| `1` | `ACC_FMT_16BIT` | Array of signed `int16` (little-endian); each value is the vector magnitude in **milli-g**. | No scaling needed. |
| `3` | `ACC_FMT_3D` | Array of signed `int16` (little-endian) in X, Y, Z triplets, already in **milli-g**.          | No scaling. The phone computes the vector magnitude: `mag = sqrt(x²+y²+z²)` in milli-g. The raw X/Y/Z values (milli-g) are also stored in `SdData.rawData3D`. |

InfiniTime motion data always uses the 3D format regardless of `CHAR_OSD_ACC_FMT`.

Multiple samples may be packed into a single notification. The phone accumulates samples until the buffer reaches 125 samples (5 seconds at 25 Hz) before running analysis.

---

## Alarm State Feedback

After each analysis cycle the phone writes a single unsigned byte to `CHAR_OSD_STATUS` (OSD service) or `CHAR_INFINITIME_OSD_STATUS` (InfiniTime) containing the current alarm state. The device can use this to drive haptic feedback, display updates, etc.

| Value | Meaning |
|---|---|
| `0` | OK |
| `1` | WARNING |
| `2` | ALARM |
| `3` | FALL |
| `4` | FAULT |
| `5` | MANUAL ALARM |
| `6` | MUTE |
| `7` | NET FAULT |

---

## Connection Behaviour

- The phone scans by MAC address (stored in the `BleDeviceAddress` preference, set via `BLEScanActivity`).
- On service discovery the phone requests MTU 185 and HIGH connection priority, and attempts Bluetooth 5 Long Range (LE Coded PHY, S8 option) if available.
- On unexpected disconnection, the phone retries with **exponential back-off** (1 s, 2 s, 4 s, 8 s, 16 s, then 16 s indefinitely) until reconnection succeeds or the service is stopped.
- The phone also starts an Android-side **Current Time Service** GATT server so the device can read the current time via the standard BLE Current Time Service.

---

## Summary of UUIDs

| Name | UUID |
|---|---|
| OSD Service | `000085e9-0000-1000-8000-00805f9b34fb` |
| CHAR_OSD_ACC_DATA | `000085e9-0001-1000-8000-00805f9b34fb` |
| CHAR_OSD_BATT_DATA | `000085e9-0002-1000-8000-00805f9b34fb` |
| CHAR_OSD_WATCH_ID | `000085e9-0003-1000-8000-00805f9b34fb` |
| CHAR_OSD_WATCH_FW | `000085e9-0004-1000-8000-00805f9b34fb` |
| CHAR_OSD_ACC_FMT | `000085e9-0005-1000-8000-00805f9b34fb` |
| CHAR_OSD_STATUS | `000085e9-0006-1000-8000-00805f9b34fb` |
| InfiniTime Motion Service | `00030000-78fc-48fe-8e23-433b3a1942d0` |
| CHAR_INFINITIME_ACC_DATA | `00030002-78fc-48fe-8e23-433b3a1942d0` |
| CHAR_INFINITIME_OSD_STATUS | `00030078-78fc-48fe-8e23-433b3a1942d0` |
| Heart Rate Service | `0000180d-0000-1000-8000-00805f9b34fb` |
| CHAR_HEART_RATE_MEASUREMENT | `00002a37-0000-1000-8000-00805f9b34fb` |
| Battery Service | `0000180f-0000-1000-8000-00805f9b34fb` |
| CHAR_BATT_DATA | `00002a19-0000-1000-8000-00805f9b34fb` |
| Device Information Service | `0000180a-0000-1000-8000-00805f9b34fb` |
