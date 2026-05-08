# OpenSeizureDetector Android App Structure

This document gives new contributors a fast, high-level map of how the Android application is organised: the major Java classes, resource folders, the startup / shutdown lifecycle, and the data + alarm flow.

## 1. Top-Level Overview
OpenSeizureDetector is an Android foreground-service based application that:
- Collects motion (acceleration) and physiological (heart rate, optionally SpO₂) data from a wearable (Garmin, Pebble, BLE devices, phone sensors, etc.).
- Analyses incoming samples to detect tonic–clonic seizure patterns.
- Raises local (audible) and remote (SMS / phone call) alarms and optionally shares anonymised data with a central server.
- Provides a swipe-based main UI (MainActivity2 + fragments) and a startup checklist screen (StartupActivity) to ensure prerequisites are satisfied before normal operation.

Core runtime logic lives in the `SdServer` foreground service; Activities and Fragments mostly visualize status and manipulate preferences.

## 2. Startup Lifecycle (Happy Path)
1. User taps the launcher icon -> Android launches `StartupActivity` (declared with MAIN/LAUNCHER intent filter in `AndroidManifest.xml`).
2. `StartupActivity`:
   - Applies default preference values from XML (alarm, general, datasource, logging, etc.).
   - Requests / validates required runtime permissions (notifications, SMS, location, Bluetooth, activity recognition, etc.).
   - Starts (or restarts) the foreground service `SdServer` via `OsdUtil.startServer()` if not already running.
   - Binds to the service through `SdServiceConnection` to monitor status (watch connection, settings received, data flowing).
   - Displays a checklist (ProgressBars + TextViews) updated by a periodic timer until all conditions are OK.
   - When all OK, transitions to either `MainActivity2` (new UI) or legacy `MainActivity` depending on the `UseNewUi` preference.
3. `SdServer.onStartCommand()`:
   - Calls `updatePrefs()`; selects concrete `SdDataSource*` implementation based on `DataSource` preference.
   - Instantiates and starts the chosen data source (e.g., `SdDataSourceGarmin`, `SdDataSourceBLE`, `SdDataSourcePhone`, etc.).
   - Initialises logging (`LogManager`), location (`LocationFinder` if SMS alarms enabled), timers, embedded HTTP server (`SdWebServer`), wake lock, and notification channels; enters foreground (persistent notification).
   - Begins receiving data; populates `SdData` and runs analysis algorithms (e.g., seizure + heart rate) to update alarm state.
4. `MainActivity2` binds to the already running `SdServer` to present live status via Fragments.

## 3. Shutdown Lifecycle
There are several ways the service stops:
- User selects an "Exit" / stop option (menu action triggers `OsdUtil.stopServer()`), which calls `stopService` for `SdServer`.
- System kills the service (low memory or user force-stop) -> `SdServer.onDestroy()` releases wake lock, stops data source, timers, web server, and cleans up.
- Device reboot triggers `BootBroadcastReceiver` which, if `AutoStart` preference is true, launches `StartupActivity` to restart.

## 4. Major Java Components
### Activities
- `StartupActivity`: Launcher activity; initial permission + readiness checklist; starts/binds the service; routes to main UI.
- `MainActivity2`: Modern, swipe-based interface using `ViewPager2` + Fragments; shows system/algorithm status, data sharing, web server info, heart rate, ML algorithm, battery, watch signal etc.
- `MainActivity`: Legacy UI retained for backward compatibility (optionally used if `UseNewUi` is false).
- `PrefActivity`: Preferences editor (headers + fragments defined in `res/xml/*prefs.xml`).
- `BLEScanActivity`: Discovers and selects BLE devices when using BLE/BLE2 data source.
- `AuthenticateActivity`: Handles login for data sharing / remote API.
- `LogManagerControlActivity`, `ExportDataActivity`, `RemoteDbActivity`: Data sharing, viewing, exporting, pruning local DB.
- `ReportSeizureActivity`, `EditEventActivity`: Manual event reporting / editing.

### Foreground Service
- `SdServer`: Heart of the application. Manages:
  - Data source selection and life-cycle.
  - Alarm evaluation (seizure, heart rate, fall, etc.).
  - Notification updates (different channels for service status, events, data sharing issues).
  - SMS & phone call alarm orchestration (timers to allow cancellation).
  - Logging & data sharing upload scheduling.
  - Embedded HTTP server (`SdWebServer`) for local access.
  - Wake lock to keep CPU active during monitoring.

### Data Sources (inherit from / similar pattern to `SdDataSource`)
Each encapsulates a communication protocol + parsing logic:
- `SdDataSourcePebble` – Legacy Pebble watch integration.
- `SdDataSourceAw` – Android Wear devices.
- `SdDataSourceGarmin` – Garmin watch app (acceleration + heart rate streams).
- `SdDataSourceBLE` / `SdDataSourceBLE2` – Generic BLE device integrations (v1 / v2 protocols for devices like PineTime / BangleJS).
- `SdDataSourceNetwork` – Pulls detector data over network from a remote instance.
- `SdDataSourcePhone` – Uses phone onboard sensors as the detector.

### Algorithms & Data Structures
- `SdData`: Aggregates current sample values, processing results, and metadata (data source name, versions, etc.).
- `SdAlgHr`: Heart rate alarm algorithms (simple threshold, adaptive, average-based).
- `SdAlgNn`: Neural network / machine learning based seizure detection (see `FragmentMlAlg` for UI).
- `CircBuf`: Circular buffer used for windowed averaging and historical metrics.

#### Core Analysis Pipeline (`SdDataSource.doAnalysis()`)
The principal seizure detection loop lives in the protected method `SdDataSource.doAnalysis()`, called each time a fresh window of accelerometer samples arrives (vector magnitude or derived from 3D data):
1. Phone/watch battery percentages are updated and appended to rolling buffers.
2. (Currently hard-coded) sample frequency set (e.g. 25 Hz) and frequency resolution derived from window length (`mNsamp`).
3. FFT performed on the raw acceleration window using JTransforms `DoubleFFT_1D.realForward`.
4. Overall spectrum "power" (`specPower`) accumulated up to a cutoff frequency (`FreqCutoff`), zeroing higher bins to reduce noise.
5. Region of Interest (ROI) defined by preferences `AlarmFreqMin`/`AlarmFreqMax`; average ROI power (`roiPower`) and the ratio `roiRatio = 10 * roiPower / specPower` computed.
6. Simplified spectrum (`simpleSpec[]`) built in 1 Hz bins for UI visualisation (scaled by `ACCEL_SCALE_FACTOR` to align with historic Pebble scaling).
7. Populates fields in `mSdData` (timestamps, `specPower`, `roiPower`, thresholds, ROI freq bounds, simplified spectrum array, flags) and marks `haveData`.
8. If the CNN alarm feature is enabled (`mCnnAlarmActive`) then `nnAnalysis()` updates `mPseizure` probability.
9. Secondary narrow-band motion check via `flapCheck()` (arm flapping detection) producing a boolean fed into `alarmCheck()`.
10. `alarmCheck()` applies thresholds (`AlarmThresh`, `AlarmRatioThresh`) and enabled algorithm flags (classic OSD, flap, CNN) to set alarm cause/state.
11. Additional modalities processed: `hrCheck()` (heart rate alarms / frozen HR detection), `o2SatCheck()` (oxygen saturation), `fallCheck()` (fall detection), and `muteCheck()` (user-induced mute logic).
12. Result dispatched upstream via `mSdDataReceiver.onSdDataReceived(mSdData)` (SdServer consumes to raise notifications / alarms).

Key preferences influencing `doAnalysis()`:
- `AlarmFreqMin` / `AlarmFreqMax`: ROI frequency band.
- `AlarmThresh` / `AlarmRatioThresh`: Power and ratio thresholds for alarm state.
- Flap detection thresholds (`FlapThresh`, `FlapRatioThresh`, min/max flap band) when flap alarm active.
- Flags enabling algorithms: `mOsdAlarmActive`, `mFlapAlarmActive`, `mCnnAlarmActive`.

Performance / Extension Notes:
- Current implementation re-allocates FFT arrays each call; optimisation could reuse buffers.
- Sample frequency is hard-coded (25 Hz) inside analysis; aligning it with dynamic settings from watch would improve fidelity.
- Multiple ROIs could be generalised (current flapCheck duplicates spectral processing).
- Scaling (`ACCEL_SCALE_FACTOR`) is applied post-hoc; future refactor could normalise early and adopt floating-point consistently for UI.

#### `doAnalysis()` Invocation & Alarm Propagation
`doAnalysis()` is not called in a tight loop by the service; instead each concrete `SdDataSource` decides when a complete window of samples is ready and then invokes it:
- BLE (`SdDataSourceBLE` / `SdDataSourceBLE2`): Acceleration notifications fill a raw buffer (`rawData` length 125 = 5s @25Hz). When full, the datasource copies buffered samples into `mSdData`, sets `mNsamp`, calls `doAnalysis()`, then sends a one–byte alarm state back to the device via a status GATT characteristic.
- Phone (`SdDataSourcePhone`): Collects accelerometer sensor events, performs crude downsampling from ~50Hz to 25Hz. Once `rawData` is full it triggers `doAnalysis()`, resets counters and continues.
- Pebble (`SdDataSourcePebble`): The watch app performs analysis on-device and sends already processed results (including `alarmState`, spectrum) – so `doAnalysis()` is NOT used for Pebble; received data directly calls `mSdDataReceiver.onSdDataReceived`.
- Network (`SdDataSourceNetwork`): Fetches remote JSON; if successful passes parsed `SdData` upward. Remote faults set `alarmState` to NET FAULT (7). Local `doAnalysis()` not used.
- Garmin (`SdDataSourceGarmin`): Similar pattern (buffer fill -> `doAnalysis()`).

After `doAnalysis()` completes in a source that uses it:
1. Spectral metrics (`roiPower`, `specPower`, simplified spectrum) and timing fields are populated.
2. `flapCheck()` optionally computes a narrow-band flap detection boolean.
3. `alarmCheck(flapDetected)` applies power & ratio thresholds and accumulates time in alarm (`mAlarmCount += mSamplePeriod`) to transition through:
   - OK (0) -> WARNING (1) after `mWarnTime` seconds of continuous in-alarm condition.
   - WARNING (1) -> ALARM (2) after `mAlarmTime` seconds.
   - Recovery logic: leaving in-alarm state downgrades from ALARM (2) to WARNING (1) (simulating a just-entered warning), or from WARNING (1) to OK (0).
4. Other modality checks may elevate alarmState:
   - `hrCheck()`: If any heart rate alarm stands (simple / adaptive / average) sets `alarmState = 2` and appends cause tags (`HR`, `HR_ADAPT`, `HR_AVG`). Null HR may either cause alarm or fault depending on `mHRNullAsAlarm`.
   - `o2SatCheck()`: Low or null oxygen saturation (with null-as-alarm enabled) sets standing flags and may escalate to ALARM.
   - `fallCheck()`: Sets `fallAlarmStanding` and may signal FALL alarm state (3).
   - `muteCheck()`: Watch/user mute sets `alarmState = 6` (MUTE) overriding other transient states.
   - Fault timers (`faultCheck()` elsewhere) may set FAULT (4) or NET FAULT (7).
5. The datasource calls `mSdDataReceiver.onSdDataReceived(mSdData)` (implemented by `SdServer`).

##### Alarm State Codes (as observed in code)
| Code | Meaning | Origin / Trigger |
|------|---------|------------------|
| 0 | OK | No current alarm condition or post-recovery. |
| 1 | WARNING | Thresholds exceeded for > `warnTime` but < `alarmTime`. |
| 2 | ALARM | Thresholds exceeded for > `alarmTime`, or HR/O₂/fall promoted, or HR adaptive/average thresholds stand. |
| 3 | FALL | Fall detection logic sets `fallAlarmStanding` or explicit fall state. |
| 4 | FAULT | Internal fault (e.g., missing data, HR sensor failure without null-as-alarm). |
| 5 | MANUAL ALARM | Raised manually (e.g., `SdServer.raiseManualAlarm()`). |
| 6 | MUTE | User/watch initiated mute; prevents audible alarm but maintains monitoring. |
| 7 | NET FAULT | Network datasource error / fault condition (`SdDataSourceNetwork`). |

##### How `SdServer` Reacts (`onSdDataReceived`)
`SdServer.onSdDataReceived(sdData)` interprets `alarmState` plus standing flags and performs side-effects:
- OK (0): Clears `alarmStanding` unless latched (`mLatchAlarms`) from previous alarm or fall.
- MUTE (6): Sets phrase "MUTE", suppresses alarms and notifications severity.
- WARNING (1): Plays warning tone (`warningBeep()`), logs (if enabled), updates notification to warning channel/state.
- ALARM (2) or MANUAL ALARM (5): Sets phrase "ALARM", raises `alarmStanding`, plays alarm tone (`alarmBeep()`), shows main UI, posts high-severity notification, initiates latch timer (`startLatchTimer()`), and sends SMS / phone alarms if enabled (rate-limited to one per minute).
- FALL (3 or `fallAlarmStanding` true): Behaves similarly to ALARM but with phrase "FALL" (alarms + SMS sending). Fall may remain standing until cleared.
- HR / O₂ / Adaptive HR / Average HR: These set `alarmState = 2` when standing; `alarmCause` accumulates tokens; downstream handling identical to ALARM.
- FAULT (4, 7, HR fault, frozen HR fault): Plays fault warning beep (`faultWarningBeep()`), shows fault notification; may attempt datasource restart after timer (auto-restart currently disabled for BLE2 to prevent duplicate notifications).

##### Latching & Reset
With `mLatchAlarms` enabled, returning to OK does not immediately clear previous ALARM/FALL states; user must manually accept/reset (e.g., via UI actions) or wait for latch timer expiry (`mLatchAlarmTimer`). Without latching, state machine freely transitions downwards.

##### Data Sharing & Logging Post-Analysis
Upon each received dataset, `SdServer` updates internal `mSdData`, pushes it to `SdWebServer` for external viewing, and passes it to `LogManager` (`mLm.updateSdData(mSdData)`), which may create/append local events (especially on transitions into ALARM states) and schedule remote uploads.

##### Device Feedback
BLE/BLE2 write a single-byte alarm state back to the watch/device after analysis (`executeWriteCharacteristic(mStatusChar, statusVal)` or peripheral write) enabling haptic / on-watch UI feedback.
Pebble handles its own alarm transitions internally before sending results.

This separation lets wearable implementations stay lightweight (simple streaming) while centralizing threshold timing, multi-modal fusion, and alarm escalation logic on the phone (except for Pebble legacy analysis).

### UI Fragments (used in `MainActivity2` ViewPager)
- `FragmentCommon`: Overall status & key indicators.
- `FragmentOsdAlg`: Seizure algorithm metrics (spectrum ratio, thresholds, raw/processed values).
- `FragmentHrAlg`: Heart rate algorithm status & thresholds.
- `FragmentMlAlg`: ML model results / confidence scores.
- `FragmentBatt`: Watch + phone battery status.
- `FragmentSystem`: System info (permissions, service state, logging flags).
- `FragmentWatchSig`: Signal quality / connectivity indicators.
- `FragmentWebServer`: Local web server URL / status.
- `FragmentDataSharing`: Data sharing setup state, counts of local vs remote events.

### Utilities & Helpers
- `OsdUtil`: Starts/stops/binds the service; permission checks; logging helpers; system/environment utilities.
- `LogManager`: Handles local + remote logging, event packaging, pruning, and upload scheduling.
- `MlModelManager`: Manages loading / inference of ML models (if in use).
- `LocationFinder`: Acquires GPS coordinates for SMS alarms.
- `WebApiConnection` / `WebApiConnection_firebase` / `WebApiConnection_osdapi`: Remote data sharing / API integrations.
- `SdServiceConnection`: Wraps service binding / connection callbacks, exposes convenience methods (`watchConnected()`, `hasSdData()`, `hasSdSettings()`).
- `BootBroadcastReceiver`: Auto-start on device boot when preference enabled.
- `GattAttributes`: BLE UUID constants and attribute names.
- `SdWebServer`: Lightweight embedded HTTP server (for local status / data access).

### Data Sharing Module
Under `uk/org/openseizuredetector/data/...`:
- Repository pattern for authentication: `LoginRepository`, `LoginDataSource`, `LoggedInUser`, `Result` (standard wrapper around success/error).

## 5. Resource Folder Structure
```
res/
  layout/        Activity & Fragment UI XML (e.g., startup_activity, activity_main2, fragment_*).
  menu/          Action bar & overflow menus (e.g., main_activity_actions.xml).
  values/        Strings (`strings.xml`), styles, colors, dimensions; base resources.
  values-XX/     Localized strings (de, es, pl, ru, sl, sv, etc.).
  drawable/      Icons and graphics (e.g., star_of_life_48x48). Might also include vector assets.
  xml/           Preference definition files and network security config:
                 - alarm_prefs.xml
                 - basic_prefs.xml
                 - general_prefs.xml
                 - logging_prefs.xml
                 - pebble_datasource_prefs.xml
                 - network_datasource_prefs.xml
                 - network_passive_datasource_prefs.xml
                 - seizure_detector_prefs.xml
                 - preference_headers.xml (groups preferences)
                 - network_security_config.xml
```
Other notable folders:
- `assets/` (if present) – Additional static assets (not heavily used here).
- `libs/` – Third-party JARs (e.g., FFT / chart libraries) bundled with the app.

## 6. Preferences Flow
1. XML files under `res/xml` define keys and defaults.
2. `StartupActivity.onCreate()` calls `PreferenceManager.setDefaultValues(...)` for each preference file (once per install/version).
3. Classes such as `OsdUtil`, `SdServer`, `SdAlgHr` invoke `updatePrefs()` to read `SharedPreferences` (
   `PreferenceManager.getDefaultSharedPreferences(context)`), caching operational parameters (thresholds, window lengths, flags).
4. Preference changes may trigger service restarts or algorithm behavior changes (e.g., enabling SMS alarms requires location permission and `LocationFinder`).

## 7. Data & Alarm Flow
```
Wearable / Phone Sensors --> Concrete SdDataSource --> SdServer (receives callbacks) -->
  Algorithms (SdAlgNn, SdAlgHr, fall detection, etc.) --> Alarm State Transitions -->
    Audible ToneGenerator / MP3 playback
    Foreground Notification Updates
    Timed SMS / Phone Call Alerts (with cancellation window)
    Data Logging (local DB) / Remote Sharing (LogManager, WebApiConnection*)
    Web Server exposure (SdWebServer)
```
Heart rate buffering uses `CircBuf` windows for simple/adaptive thresholding; seizure analysis (frequency spectrum, ratio thresholds) executed inside data source analysis routines (see respective `SdDataSource*` classes).

## 8. Foreground Service & Resilience
- Service runs in foreground with a persistent notification (required for stable long-running monitoring on modern Android).
- Wake lock prevents CPU sleep during monitoring sessions (battery intensive but improves reliability).
- Timers manage periodic tasks (event validation checks, remote upload scheduling, alarm muting windows).
- Boot auto-start via broadcast receiver ensures continuity if user opted in.

## 9. Adding a New Data Source (High-Level Guide)
1. Create a new `SdDataSource<YourDevice>` class implementing the expected interface / callback pattern (see existing sources for template).
2. Handle connection, authentication/handshake, data parsing, and call back into `SdServer` with new samples.
3. Add a selection case in `SdServer.onStartCommand()` for your `DataSource` preference string.
4. Provide any additional preferences XML (e.g., update period, device address) and add them to default initialization in `StartupActivity`.
5. Update UI fragments if device supplies new metrics.

## 10. Where to Look for Key Logic
- Service lifecycle & alarm orchestration: `SdServer.java` (`onStartCommand`, `onDestroy`, timers, notifications).
- Startup readiness checklist: `StartupActivity.serverStatusRunnable`.
- Data source selection: `SdServer.onStartCommand()` switch over `mSdDataSourceName`.
- Heart rate algorithms: `SdAlgHr.java`.
- ML / seizure algorithm UI: `FragmentMlAlg.java` + `SdAlgNn.java`.
- Permission checks: `StartupActivity` & `OsdUtil` (Bluetooth, activity recognition, SMS, location).
- Logging & data sharing: `LogManager.java`, `RemoteDbActivity.java`, `FragmentDataSharing.java`.
- Embedded web server logic: `SdWebServer.java`.

## 11. Key Preference Examples (Selected)
| Preference Key | Purpose |
| -------------- | ------- |
| `DataSource` | Selects which device source (Pebble, Garmin, BLE, Phone, Network). |
| `AlarmThresh` / `AlarmRatioThresh` | Seizure detection thresholds (spectrum amplitude / ratio). |
| `HRThreshMin` / `HRThreshMax` | Simple heart rate alarm bounds. |
| `HRAdaptiveAlarmWindowSecs` | Window size for adaptive HR average buffering. |
| `SMSAlarm` / `PhoneCallAlarm` | Enable remote alerts. |
| `LogData` / `LogDataRemote` | Enable local logging vs remote data sharing. |
| `UseNewUi` | Switch between legacy and modern main UI. |
| `AutoStart` | Auto-launch on device boot. |

(See `res/xml/*_prefs.xml` for full list.)

## 12. Notifications & Timers
- Multiple channels for service status and events (IDs inside `SdServer`).
- Timers: `FaultTimer`, `CheckEventsTimer`, SMS countdown (`SmsTimer`), latch alarm timer, etc., each controlling asynchronous transitions.

## 13. Data Sharing Flow
1. User authenticates (`AuthenticateActivity`) -> obtains token stored in preferences.
2. `LogManager` packages events (timestamped, with retention pruning) and attempts periodic uploads (`remoteLogPeriod`).
3. Unvalidated remote events prompt UI reminders (`FragmentDataSharing`).

## 14. Crash Handling
`UCEHandler` integrated in Activities and Service to capture uncaught exceptions and offer sending logs via email.

## 15. Embedded Web Server
`SdWebServer` exposes (read-only) status / logged data for local network access; started automatically by `SdServer` after data source initialisation.

## 16. Stopping / Restarting Service Programmatically
- Stop: `OsdUtil.stopServer()` -> calls `stopService(Intent(SdServer))`.
- Start: `OsdUtil.startServer()` -> `Context.startForegroundService(...)` (on modern Android) then service builds notification & begins monitoring.
- Restart triggered implicitly if critical permissions change (logic can call stop/start to reinitialise components).

## 17. Extending Alarms / Algorithms
To add new alarm logic (e.g., oxygen saturation):
1. Introduce algorithm class (`SdAlgO2` style) storing buffers and thresholds.
2. Update data source parsing to capture new metric.
3. Integrate into `SdServer` evaluation loop; amend notification text generation.
4. Provide preference keys + XML + UI Fragment display.

## 18. Glossary
- "Latch Alarm": Alarm remains active until explicitly reset (even if underlying condition clears) for a configured duration.
- "Adaptive HR Alarm": Builds a moving average; raises alarm when HR deviates beyond +/- configurable delta.
- "Foreground Service": Long-lived component with persistent notification; less likely to be killed.
- "Data Sharing": User-consented upload of anonymised seizure events / sensor data to central server for algorithm improvement.

## 19. Useful Entry Points for Debugging
- Set breakpoints in `SdServer.onStartCommand()` to inspect data source initialisation.
- Use logs emitted via `OsdUtil.writeToSysLogFile` to trace state transitions.
- Inspect `StartupActivity.serverStatusRunnable` for readiness gating issues.

## 20. Related Documents
- `README.md`: General project overview, build instructions.
- `DEV_NOTES.txt`: Developer notes / historical comments.
- `BLE_Datasource_Specification.md`: Protocol specifics for BLE devices.
- PDFs under `doc/` for algorithm assessment and app structure diagrams.

## 21. End-to-End Data -> Alarm Flow Diagram
Below are two complementary diagrams (ASCII and Mermaid) showing how a data window travels from the wearable to an alarm being raised.

PNG Version: See `FLOW_DIAGRAM.png` in the repository root for a downloadable image.

ASCII Flow
```
Wearable Sensors (Accel / HR / O₂) 
   |
   v
Watch Firmware / Device App
   |   (Pebble: does analysis + sends results)
   |   (BLE/Garmin/PineTime/etc.: streams raw samples)
   v
SdDataSource (Buffer + Parse + Downsample/Scale)
   |  (Collect ~125 samples = 5s @25Hz) 
   v (window full)
doAnalysis()
   |-> FFT (JTransforms) & Spectrum Power (specPower)
   |-> ROI Power & Ratio (roiPower / roiRatio)
   |-> Simplified Spectrum (simpleSpec[])
   |-> flapCheck() narrow band detection
   |-> nnAnalysis() (if CNN enabled)
   |-> hrCheck(), o2SatCheck(), fallCheck(), muteCheck()
   v
Populate mSdData (specPower, roiPower, simpleSpec, alarmState, alarmCause, metrics)
   v
mSdDataReceiver.onSdDataReceived(mSdData)
   v
SdServer.onSdDataReceived()
   |-> Alarm state machine (OK/WARNING/ALARM/FALL/FAULT/MUTE)
   |-> Latching logic (startLatchTimer if ALARM)
   |-> Notifications (foreground + event channels)
   |-> Tones (warningBeep / alarmBeep / faultWarningBeep)
   |-> SMS / Phone Call (rate-limited, if enabled)
   |-> Logging & Data Sharing (LogManager update, remote upload scheduling)
   |-> Web Server update (SdWebServer.setSdData)
   |-> Write alarmState byte back to device (BLE/Garmin)
   v
UI Fragments / Web Server / Remote API Consumers
```

Mermaid Diagram (optional rendering if supported):
```mermaid
flowchart TD
  W[Wearable Sensors\n(Accel / HR / O₂)] --> WF[Watch Firmware / Device App]
  WF --> DS{SdDataSource\nBuffer & Parse}
  DS -->|Window Full| AN[doAnalysis()]
  AN --> FFT[FFT + Spectrum]
  FFT --> ROI[ROI Power & Ratio]
  ROI --> ALG[flapCheck / nnAnalysis / hrCheck / o2SatCheck / fallCheck / muteCheck]
  ALG --> SD[SdData Populated\n(alarmState, metrics)]
  SD --> RCV[mSdDataReceiver.onSdDataReceived]
  RCV --> SRV[SdServer.onSdDataReceived]
  SRV --> ACT[Alarm Actions\nNotification / Tone / SMS / Phone / Latch / Log]
  SRV --> FEED[Write alarmState\nback to Device]
  ACT --> UI[UI Fragments / Web Server / Data Sharing]
  WF -. Pebble path (analysis on watch) .-> SD
```
Notes:
- Pebble path bypasses local `doAnalysis()`; analysis executes on the watch and sets `alarmState` before dispatch.
- Network datasource substitutes "Buffer & Parse" with remote JSON fetch and may directly set NET FAULT (7) on failure.
- Latching prevents immediate clearing of ALARM/FALL states until user intervention or timer expiry.

## 22. Related Documents
- `README.md`: General project overview, build instructions.
- `DEV_NOTES.txt`: Developer notes / historical comments.
- `BLE_Datasource_Specification.md`: Protocol specifics for BLE devices.
- PDFs under `doc/` for algorithm assessment and app structure diagrams.

---
Questions / Improvements: Feel free to open issues or pull requests on GitHub.
