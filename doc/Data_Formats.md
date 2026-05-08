# OpenSeizureDetector Data Formats Summary

This document describes the data formats used by each data source to populate the `SdData` app state object. It reflects the current implementation in the `datasource/` package.

---

## 1. SdDataSourceAw (Android Wear / WearOS)

Communicates via the Wearable Data Layer API using JSON or binary messages sent to well-known path URIs.

| Path | Direction | Format |
|---|---|---|
| `/osd/accel_data` | Watch → Phone | **Format A (array):** `{"samples": [double, ...]}` (vector magnitude values). **Format B (3D):** `{"x": double, "y": double, "z": double}`. **Binary:** 16-bit little-endian shorts. |
| `/osd/settings` | Watch → Phone | `{"battery": int, "version": string, "name": string, "sample_freq": int}` |
| `/osd/hr_data` | Watch → Phone | `{"hr": int}` (or a plain integer string). |
| `/osd/alarm_state` | Phone → Watch | `{"alarm_state": long, "alarm_phrase": string}` |

---

## 2. SdDataSourceGarmin (via SdWebServer)

Uses a **passive/server model**: the Garmin watch app *POSTs* data to the phone's embedded web server (`SdWebServer`) at port 8080. The server routes the request to `SdDataSource.updateFromJSON()`.

### Data POST (`POST /data`)

```json
{
  "dataType": "raw",
  "HR":       72.0,
  "O2sat":    98.0,
  "Mute":     0,
  "data3D":   [0.0, 0.0, 1000.0, ...],
  "data":     [1000.0, 1001.0, ...]
}
```

`data` is an array of vector-magnitude acceleration samples in **milli-g** (1000 = 1 g).  
`data3D` is an array of X, Y, Z acceleration triplets, also in **milli-g**.

### Settings POST (`POST /settings`)

```json
{
  "dataType":      "settings",
  "analysisPeriod": 5,
  "sampleFreq":     25,
  "battery":        80,
  "watchPartNo":   "Garmin Vivoactive 4",
  "watchFwVersion": "11.20",
  "sdVersion":      "1.2.3",
  "sdName":        "OSD_Garmin"
}
```

---

## 3. SdDataSourceBLE2 (Bluetooth Low Energy)

Uses binary GATT notifications over the custom OSD GATT service and standard BLE services. See **`BLE_Datasource_Specification.md`** for the full GATT service/characteristic UUID table and encoding details.

Summary of data received:

| Service | Data | Encoding |
|---|---|---|
| OSD Service (`000085e9-…`) | Acceleration samples | Binary: `int8` (8-bit magnitude), `int16` LE (16-bit magnitude), or `int16` LE X/Y/Z triplets — format code from `CHAR_OSD_ACC_FMT` |
| OSD Service | Battery level | 1-byte unsigned integer, percent |
| OSD Service | Watch name / firmware version | UTF-8 strings (read once on connection) |
| Heart Rate Service (`0000180d-…`) | Heart rate | Standard BLE HR Measurement: flags byte + `uint8` or `uint16` BPM |
| Battery Service (`0000180f-…`) | Battery level | 1-byte unsigned integer, percent |
| Device Information Service (`0000180a-…`) | Manufacturer, model, firmware | UTF-8 strings (read once on connection) |

Data written **from** the phone to the device:

| Characteristic | Encoding | Description |
|---|---|---|
| `CHAR_OSD_STATUS` / `CHAR_INFINITIME_OSD_STATUS` | 1-byte unsigned integer | Alarm state code (0=OK … 7=NETFAULT) written after each analysis cycle |

> **Note:** `SdDataSourceBLE` (BLE v1) is deprecated. New device firmware should use the BLE2 protocol.

---

## 4. SdDataSourceNetwork

Retrieves the full app state from a remote OpenSeizureDetector instance via `HTTP GET /data`. The response is parsed by `SdData.fromJSON()`.

The JSON object includes all real-time fields from `SdData.toDataString()`. Required fields:

```json
{
  "maxVal": 0, "maxFreq": 0,
  "specPower": 0, "roiPower": 0,
  "batteryPc": 80, "phoneBatteryPc": 95,
  "watchConnected": true, "watchAppRunning": true, "haveSettings": true,
  "alarmState": 0, "alarmPhrase": "OK", "alarmCause": "", "faultCause": "",
  "sdMode": 0, "sampleFreq": 25, "analysisPeriod": 5,
  "alarmFreqMin": 1, "alarmFreqMax": 10,
  "alarmThresh": 500, "alarmRatioThresh": 60,
  "hrAlarmActive": false, "hrAlarmStanding": false,
  "hrThreshMin": 40.0, "hrThreshMax": 150.0,
  "hr": 72.0, "adaptiveHrAv": 70.0, "averageHrAv": 71.0,
  "o2SatAlarmActive": false, "o2SatAlarmStanding": false,
  "o2SatThreshMin": 80.0, "o2Sat": 98.0,
  "cnnAlarmActive": true, "pSeizure": 0.02,
  "OsdAlarmActive": true, "FlapAlarmActive": false, "CnnAlarmActive": true,
  "osdAlgState": 0, "flapAlgState": 0, "fallAlgState": 0,
  "hrAlgState": 0, "cnnAlgState": 0,
  "mlNumModels": 1,
  "mlModelNames": ["OSD v3"], "mlModelProbs": [0.02],
  "mlModelStates": [0], "mlModelActive": [true],
  "simpleSpec": [0, 0, 0, 0, 0, 0, 0, 0, 0, 0]
}
```

**Note on raw accelerometer data:** `rawData` (125-element array) and `rawData3D` (375-element array) are accepted by `fromJSON()` if present, but `SdData.toDataString()` — which produces the `GET /data` response — never includes them. They are therefore not present in normal network source operation. All values are in **milli-g** (1000 = 1 g).

The network source sets `alarmState = 7` (NETFAULT) if the remote fetch fails.

---

## 5. SdDataSourcePebble

Uses the PebbleKit SDK and `PebbleDictionary` (proprietary binary key-value mapping). **No JSON is used** in the watch-to-phone direction. The Pebble watch performs its own FFT-based seizure detection on-device and sends the pre-computed results (including `alarmState`, spectrum data, HR).

Key constants (defined in the Pebble watch app):

| Key | Value |
|---|---|
| KEY_MAXVAL | 3 |
| KEY_SPEC_DATA | 14 |
| (alarm state, HR, battery, etc.) | Various integer keys |

Because analysis runs on the watch, `SeizureDetector` is **not** invoked for Pebble data; the received `alarmState` is used directly.

---

## 6. SdDataSourcePhone

Uses the Android `SensorManager` API. No network communication.

- Subscribes to `TYPE_ACCELEROMETER` sensor events.
- Sensor events provide raw `float[3]` (X, Y, Z values in m/s²), which are downsampled from the native sensor rate (~50 Hz) to 25 Hz.
- Values are converted to **milli-g** (`mg = 1000 × m/s² / 9.81`) before storing into `rawData` and `rawData3D`.
- Once 125 samples are buffered, vector magnitude is computed and `SeizureDetector.processData()` is called.

---

## SdData Serialisation Methods

`SdData` has three JSON serialisation methods used in different contexts:

| Method | Used by | Includes raw accel? | Notes |
|---|---|---|---|
| `toDataString()` | `GET /data` web server endpoint; `SdDataSourceNetwork` (as source data) | **No** | Full real-time state snapshot. The `includeRawData` parameter in the signature is unused (dead code). |
| `toDatapointJSON()` | `LogRepository` — stored as the `dataJSON` column for every **datapoint** row in the SQLite `datapoints` table | **Yes** — always includes `rawData` (125 doubles, milli-g) and `rawData3D` (375 doubles, milli-g) | Smaller field set: omits per-algorithm states, ML arrays, watch connection flags, battery. Adds `roiRatio`. |
| `toSettingsJSON()` | `GET /settings` web server endpoint; `LogRepository` — stored as `dataJSON` in the SQLite `events` table on alarm transitions | **No** | Settings/configuration-oriented snapshot: algorithm thresholds, watch identity, version strings, HR/O₂ settings. |

---

## SdData JSON Reference (GET /data)

The following is the full set of fields returned by `GET /data` (produced by `SdData.toDataString()`):

| Field | Type | Description |
|---|---|---|
| `dataTime` | string | Formatted date-time (`dd-MM-yyyy HH:mm:ss`) |
| `dataTimeStr` | string | Compact date-time (`yyyyMMdd'T'HHmmss`) |
| `maxVal` | long | Peak spectral amplitude |
| `maxFreq` | long | Frequency of peak amplitude |
| `specPower` | long | Total spectral power (up to cutoff frequency) |
| `roiPower` | long | Power in the alarm frequency ROI |
| `batteryPc` | long | Watch battery % |
| `phoneBatteryPc` | int | Phone battery % |
| `watchConnected` | bool | Whether the wearable is connected |
| `watchAppRunning` | bool | Whether data is being received from the wearable |
| `haveSettings` | bool | Whether settings have been received from the wearable |
| `alarmState` | long | Current alarm state code (0–7) |
| `alarmPhrase` | string | Human-readable alarm state label |
| `alarmCause` | string | Space-separated cause tokens (e.g. `"OsdAlg HR"`) |
| `faultCause` | string | Specific fault reason when `alarmState=4` |
| `sdMode` / `sampleFreq` / `analysisPeriod` | long | Operating parameters |
| `alarmFreqMin` / `alarmFreqMax` | long | ROI frequency band (Hz) |
| `alarmThresh` / `alarmRatioThresh` | long | OSD algorithm thresholds |
| `hrAlarmActive` / `hrAlarmStanding` | bool | HR alarm enable and standing state |
| `hrThreshMin` / `hrThreshMax` | double | HR alarm bounds (bpm) |
| `hr` / `adaptiveHrAv` / `averageHrAv` | double | Heart rate values |
| `adaptiveHrAlarmStanding` / `averageHrAlarmStanding` | bool | HR alarm sub-states |
| `o2SatAlarmActive` / `o2SatAlarmStanding` / `o2SatThreshMin` / `o2Sat` | mixed | SpO₂ alarm values |
| `cnnAlarmActive` / `pSeizure` | bool / double | ML algorithm enable and combined seizure probability |
| `OsdAlarmActive` / `FlapAlarmActive` / `CnnAlarmActive` | bool | Algorithm enable flags |
| `osdAlgState` / `flapAlgState` / `fallAlgState` / `hrAlgState` / `cnnAlgState` | int | Per-algorithm alarm state (0=OK, 1=WARNING, 2=ALARM) |
| `mlNumModels` | int | Number of active ML models (up to 5) |
| `mlModelNames` | array[string] | Model name for each active model |
| `mlModelProbs` | array[double] | Seizure probability for each active model (0.0–1.0) |
| `mlModelStates` | array[int] | Alarm state for each active model |
| `mlModelActive` | array[bool] | Whether each slot is active |
| `simpleSpec` | array[int] | 10-element simplified frequency spectrum (1 Hz bins) |

---

## SQLite Datapoint JSON Reference (toDatapointJSON)

The `dataJSON` column of each row in the `datapoints` table is produced by `SdData.toDatapointJSON()`. It is a more compact format focused on the data needed for remote event upload and offline analysis. It **always includes raw accelerometer arrays**.

| Field | Type | Description |
|---|---|---|
| `dataTime` | string | Formatted date-time (`dd-MM-yyyy HH:mm:ss`) |
| `dataTimeStr` | string | Compact date-time (`yyyyMMdd'T'HHmmss`) |
| `maxVal` | long | Peak spectral amplitude |
| `maxFreq` | long | Frequency of peak amplitude |
| `specPower` | long | Total spectral power |
| `roiPower` | long | Power in the alarm frequency ROI |
| `roiRatio` | long | `10 × roiPower / specPower` (0 if `specPower == 0`) |
| `alarmState` | long | Alarm state code (0–7) |
| `alarmPhrase` | string | Human-readable alarm label |
| `alarmCause` | string | Space-separated cause tokens |
| `faultCause` | string | Specific fault reason |
| `hr` | double | Heart rate (bpm) |
| `adaptiveHrAv` | double | Adaptive HR algorithm moving average |
| `averageHrAv` | double | Average HR algorithm moving average |
| `o2Sat` | double | SpO₂ (%) |
| `pSeizure` | double | Combined ML seizure probability (0.0–1.0) |
| `simpleSpec` | array[int] | 10-element simplified spectrum |
| `rawData` | array[double] | 125 raw accelerometer vector magnitude samples in **milli-g** (1000 = 1 g, 25 Hz, 5-second window) |
| `rawData3D` | array[double] | 375 raw X/Y/Z accelerometer samples in **milli-g**, interleaved as x₁,y₁,z₁, x₂,y₂,z₂, … |

The `events` table stores the **settings snapshot** (`toSettingsJSON()`) in its `dataJSON` column — see the `GET /settings` response in `WEB_SERVER_API.md` for that field list.
