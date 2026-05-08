# Web Server API

This document describes the built-in HTTP endpoints served by the app's NanoHTTPD server (`SdWebServer`) on port 8080.

## Base

- Default port: `8080`
- Default base URL: `http://<device-ip>:8080`
- Default response MIME type: `application/json` unless noted otherwise

---

## Endpoints

### `GET /data`

Returns the current `SdData` payload as JSON (produced by `SdData.toDataString()`).

See **`Data_Formats.md` § SdData JSON Reference** for the full field list. Key fields include:

> **Note:** Raw accelerometer arrays (`rawData`, `rawData3D`) are **not** included. `SdData.toDataString()` never serialises them regardless of the `includeRawData` parameter (which is currently unused/dead code).

```json
{
  "dataTime": "08-05-2026 12:34:56",
  "alarmState": 0,
  "alarmPhrase": "OK",
  "alarmCause": "",
  "specPower": 1234,
  "roiPower": 56,
  "hr": 72.0,
  "o2Sat": 98.0,
  "pSeizure": 0.02,
  "mlNumModels": 1,
  "mlModelNames": ["OSD v3"],
  "mlModelProbs": [0.02],
  "mlModelStates": [0],
  "mlModelActive": [true],
  "simpleSpec": [0, 12, 44, 23, 8, 3, 1, 0, 0, 0],
  "watchConnected": true,
  "batteryPc": 85,
  "phoneBatteryPc": 72
}
```

---

### `POST /data`

Accepts data updates from the Garmin watch app. If the configured data source is not `Garmin`, the payload is silently ignored.

**Request body** (either `dataObj` form field or raw POST body):
```json
{
  "dataType": "raw",
  "HR": 72.0,
  "O2sat": 98.0,
  "Mute": 0,
  "data": [1000.0, 1001.0, ...]
}
```

`data` values are in **milli-g** (1000 = 1 g). `data3D` (optional) provides X, Y, Z triplets in **milli-g**.

**Response:** string returned by `SdDataSource.updateFromJSON()`.

---

### `GET /settings`

Returns the current settings snapshot as JSON (produced by `SdData.toSettingsJSON()`).

Key fields include:

```json
{
  "dataTime": "08-05-2026 12:34:56",
  "batteryPc": 85,
  "phoneBatteryPc": 72,
  "alarmState": 0,
  "alarmPhrase": "OK",
  "alarmCause": "",
  "faultCause": "",
  "sdMode": 0,
  "sampleFreq": 25,
  "analysisPeriod": 5,
  "alarmFreqMin": 1,
  "alarmFreqMax": 10,
  "alarmThresh": 500,
  "alarmRatioThresh": 60,
  "osdAlarmActive": true,
  "cnnAlarmActive": true,
  "hrAlarmActive": false,
  "hrThreshMin": 40.0,
  "hrThreshMax": 150.0,
  "adaptiveHrAlarmActive": false,
  "averageHrAlarmActive": false,
  "o2SatAlarmActive": false,
  "o2SatThreshMin": 80.0,
  "dataSourceName": "BLE2",
  "phoneAppVersion": "5.0.0",
  "watchManuf": "PINE64",
  "watchPartNo": "PineTime",
  "watchSerNo": "AA:BB:CC:DD:EE:FF",
  "watchSdName": "InfiniTime",
  "watchFwVersion": "1.14.0",
  "watchSdVersion": "1.14.0",
  "watchSignalStrength": -65.0
}
```

---

### `POST /settings`

Accepts settings updates and forwards them to the current data source via `SdDataSource.updateFromJSON()`.

**Request body** (either `dataObj` form field or raw POST body): JSON object with settings fields (same format as Garmin settings POST in `Data_Formats.md`).

**Response:** string returned by `updateFromJSON()`.

---

### `GET /spectrum`

Returns the simplified frequency spectrum from the current `SdData`.

**Response:**
```json
{
  "simpleSpec": [0, 12, 44, 23, 8, 3, 1, 0, 0, 0]
}
```

The array has 10 elements representing spectral power in 1 Hz bins.

---

### `POST /acceptalarm`

Accepts (clears) the current alarm by calling `SdServer.acceptAlarm()`.

**Response:**
```json
{"msg": "Alarm Accepted"}
```

---

### `GET /config`

Returns the current `SharedPreferences` values (allowlisted by the XML preference files via `SettingsUtil.getPreferencesAsJson()`).

**Response:**
```json
{
  "DataSource": "BLE2",
  "LogData": true,
  "AlarmThresh": "500",
  "HRThreshMin": "40"
}
```

String preferences are returned as strings; boolean preferences as booleans.

---

### `POST /config`

Updates one or more `SharedPreferences` values from the JSON payload. Keys must exist in the XML preference files; unknown keys are ignored. A `null` value removes the preference key. If any key is updated or cleared, `SdServer` is restarted automatically after the response is sent (after ~1.5 s delay).

**Request body:**
```json
{
  "DataSource": "Phone",
  "LogData": false,
  "UnknownPref": "ignored"
}
```

**Response:**
```json
{
  "updatedKeys": ["DataSource", "LogData"],
  "clearedKeys": [],
  "ignoredKeys": ["UnknownPref"],
  "failedKeys": []
}
```

If the JSON payload is malformed, returns HTTP `400` with a plain-text error message.

---

### `GET /logs`

Lists available log files from both the rotating data log and the persistent system log.

**Response** (MIME `text/html`):
```json
{
  "logFileList": ["osd_data_2026-05-08.txt", "osd_sys.log"]
}
```

---

### `GET /logs/<filename>`

Returns the contents of the named log file.

**Response:** file stream (MIME `text/html`).

Path traversal is rejected (filenames containing `/` or `..` return an error).

---

### Static Assets

The following paths serve files from the app `assets/www/` folder:

- `/index.html`
- `/logfiles.html`
- `/favicon.ico`
- `/js/*`
- `/css/*`
- `/img/*`

---

## Error Handling

| Situation | Response |
|---|---|
| Unknown URI | `{"msg": "Unknown URI: <path>"}` (HTTP 200) |
| Malformed JSON on `POST /config` | Plain-text error message (HTTP 400) |
| Internal server error | Plain-text error message (HTTP 500) |
| Log file not found | Plain-text error string (HTTP 200) |
