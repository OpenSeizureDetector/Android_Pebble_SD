# OpenSeizureDetector Data Formats Summary

This document summarizes the different data formats used by various data sources to populate the `SdData` app state.

## 1. SdDataSourceAw (Android Wear)
Communicates via the Wearable Data Layer API using JSON or binary messages.

*   **Accelerometer Data (`/osd/accel_data`):**
    *   **Format A (Array):** `{"samples": [double, ... ]}` (magnitude samples)
    *   **Format B (3D):** `{"x": double, "y": double, "z": double}`
    *   **Binary:** 16-bit little-endian shorts.
*   **Settings (`/osd/settings`):** `{"battery": int, "version": string, "name": string, "sample_freq": int}`
*   **Heart Rate (`/osd/hr_data`):** `{"hr": int}` (or a simple string/integer).
*   **Outbound Alarm State (`/osd/alarm_state`):** `{"alarm_state": long, "alarm_phrase": string}`

## 2. SdDataSourceGarmin (via SdWebServer)
Uses a passive model where the wearable POSTs data to the phone's internal web server (`/data` or `/settings`). Handled by `SdDataSource.updateFromJSON()`.

*   **Raw Data POST:**
    ```json
    {
      "dataType": "raw",
      "HR": double,
      "O2sat": double,
      "Mute": int,
      "data3D": [double, ...],
      "data": [double, ...]
    }
    ```
*   **Settings POST:**
    ```json
    {
      "dataType": "settings",
      "analysisPeriod": int,
      "sampleFreq": int,
      "battery": int,
      "watchPartNo": string,
      "watchFwVersion": string,
      "sdVersion": string,
      "sdName": string
    }
    ```

## 3. SdDataSourceNetwork
Retrieves full app state from a remote OpenSeizureDetector instance via HTTP GET `/data`. Handled by `SdData.fromJSON()`.

*   **Format:** A comprehensive JSON object containing all state variables, including:
    *   `maxVal`, `maxFreq`, `specPower`, `roiPower`, `batteryPc`, `phoneBatteryPc`.
    *   `watchConnected`, `watchAppRunning`, `haveSettings`.
    *   `alarmState`, `alarmPhrase`, `alarmCause`.
    *   `hr`, `o2Sat`, `pSeizure`.
    *   `mlNumModels`, `mlModelNames`[], `mlModelProbs`[], `mlModelStates`[], `mlModelActive`[] (JSONArrays).
    *   `simpleSpec` (JSONArray of spectral summary).
    *   `rawData`, `rawData3D` (optional JSONArrays).

## 4. SdDataSourceBLE2 (Bluetooth Low Energy)
Uses standard BLE GATT services and characteristics. Communication is primarily binary.

*   **OSD Acceleration:** Notifications containing 8-bit or 16-bit binary samples (vector magnitude or 3D).
*   **Standard Heart Rate:** 0x180D service (binary GATT format).
*   **Battery:** 0x180F service (binary GATT format).
*   **Device Info:** 0x180A service (Strings for Manufacturer, Model, FW version).

## 5. SdDataSourcePebble
Uses the PebbleKit SDK and `PebbleDictionary` for communication.

*   **Format:** Proprietary binary key-value mapping (e.g., `KEY_MAXVAL = 3`, `KEY_SPEC_DATA = 14`). No JSON is used in the watch-to-phone communication.

## 6. SdDataSourcePhone
Uses the internal Android `SensorManager`.

*   **Format:** Native `SensorEvent` objects (raw float arrays for X, Y, Z accelerometer values). No JSON.
