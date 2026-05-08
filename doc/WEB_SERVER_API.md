# Web Server API

This document describes the built-in HTTP endpoints served by the app's NanoHTTPD server on port 8080.

## Base

- Default port: `8080`
- Default base URL: `http://<device-ip>:8080`
- Default response MIME type: `application/json` unless noted

## Endpoints

### `GET /data`

Returns the current `SdData` payload (JSON).

Response (JSON):
- JSON string produced by `SdData.toString()`.

### `POST /data`

Accepts data updates (typically from a Garmin device). If the configured data source is not `Garmin`, the payload is ignored.

Request body:
- JSON string, passed to `mSdServer.mSdDataSource.updateFromJSON(...)`.
- The server accepts either:
  - form field `dataObj`, or
  - raw POST body (available as `postData`).

Response (JSON):
- String returned by `updateFromJSON(...)` on the data source.

### `GET /settings`

Returns the current settings JSON from `SdData`.

Response (JSON):
- JSON string produced by `mSdData.toSettingsJSON()`.

### `POST /settings`

Accepts settings updates and forwards them to the current data source.

Request body:
- JSON string, passed to `mSdServer.mSdDataSource.updateFromJSON(...)`.
- The server accepts either:
  - form field `dataObj`, or
  - raw POST body (available as `postData`).

Response (JSON):
- String returned by `updateFromJSON(...)` on the data source.

### `GET /spectrum`

Returns the simple spectrum array from the current `SdData`.

Response (JSON):
```json
{
  "simpleSpec": [0, 0, 0, 0, 0, 0, 0, 0, 0, 0]
}
```

### `POST /acceptalarm`

Accepts and clears the current alarm.

Response (JSON):
```json
{"msg":"Alarm Accepted"}
```

### `GET /config`

Returns the current `SharedPreferences` values defined in XML preference files.

Response (JSON):
- Key/value object of preferences (allowlisted by XML keys).
- Example:
```json
{
  "DataSource": "Phone",
  "LogData": true
}
```

### `POST /config`

Updates `SharedPreferences` from the JSON payload and restarts the server if any changes were applied.

Request body:
- JSON object of preference key/value pairs.
- Keys must exist in the XML preference files; unknown keys are ignored.
- `null` values remove the preference key.

Response (JSON):
```json
{
  "updatedKeys": ["LogData"],
  "clearedKeys": [],
  "ignoredKeys": ["UnknownPref"],
  "failedKeys": []
}
```

Notes:
- If the JSON payload is invalid, the response status is `400` and the body contains an error message.
- If at least one key was updated or cleared, the server restarts after the response is prepared.

### `GET /logs`

Lists available log files.

Response (JSON, MIME `text/html` in current implementation):
```json
{
  "logFileList": ["log1.txt", "log2.txt"]
}
```

### `GET /logs/<filename>`

Returns the requested log file from the app data directory.

Response:
- File stream (MIME `text/html` in current implementation).

### Static assets

The following paths serve files from the app assets folder under `assets/www`:

- `/index.html`
- `/logfiles.html`
- `/favicon.ico`
- `/js/*`
- `/css/*`
- `/img/*`

## Error handling

- Unknown routes return:
```json
{"msg":"Unknown URI: <path>"}
```
- JSON errors on `/config` return HTTP `400` with a plain-text error message.

