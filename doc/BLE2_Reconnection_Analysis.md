# SdDataSourceBLE2 — Out-of-Range Reconnection Analysis

**Source of Report**:  OpenCode using Qwen3.8_MAX model and the following prompt: "Review the SdDataSourceBLE2 code (app/src/main/java/uk/org/openseizuredetector/datasource/SdDataSourceBLE2.java) to see how it handles the watch going out of range for a period then coming back into range.  We find that short term interruptions work correctly with the watch reconnecting and resuming data transfer, but longer interruptoins result in the watch not reconnecting and the system has to be re-started to restore function.  You should review how the reconnection process works and identify deficiencies with recommended improvements (and their likelihood of successfully identifying the issue).  You can also consider the device firmware at /home/graham/osd/PineTimeSD in case changes to the device firmware would improve performance.  Write up your analysis to a file so it can be shared for wider review."

**Status:** Draft for review
**Component:** `app/src/main/java/uk/org/openseizuredetector/datasource/SdDataSourceBLE2.java`
**Library:** `com.github.weliem:blessed-android:2.5.0` (`app/build.gradle`)
**Firmware reviewed:** `/home/graham/osd/PineTimeSD` (`develop`, `fce434be`)

---

## 1. Problem statement

Short out-of-range interruptions recover correctly: the watch reconnects and data
transfer resumes. Longer interruptions (reported threshold varies by phone,
sometimes > 1 hour) never recover. The app stays in `FAULT` and only a full
application restart restores function.

Two properties of the report are the most useful diagnostic clues:

1. **There is a duration threshold.** Something *accumulates* or *expires* over
   time; it is not a simple logic error that would break every time.
2. **Only a process restart fixes it.** This points at a resource that is
   registered with the Android Bluetooth stack and is never released. Restarting
   the *activity* or toggling a setting would not clear it; killing the process
   would.

Both point away from "the reconnection logic has a wrong branch" and towards
"**the reconnection logic leaks Bluetooth stack registrations until the app is
no longer allowed to register**".

---

## 2. Evidence base

Findings below are labelled:

- **[V]** — verified by reading source (BLE2, `SdDataSource`, `SdServer`, or
  blessed-android 2.5.0 as published at tag `2.5.0`).
- **[I]** — inferred from documented Android/BLE behaviour; needs on-device
  confirmation.

blessed-android 2.5.0 behaviour was confirmed by reading the published
`BluetoothCentralManager.java` and `BluetoothPeripheral.java` for that exact tag,
and by cross-checking the constant pool of the shipped
`blessed-android-2.5.0.aar` in the local Gradle cache (method names
`onScanFailed`, `autoConnectRunnable`, `cancelAutoConnectTimer`,
`MAX_CONNECTION_RETRIES`, `SCAN_TIMEOUT`, `SCAN_RESTART_DELAY` all present).

---

## 3. As-built reconnection flow

### 3.1 Normal operation

```
SdDataSourceBLE2.start()                          :163
  -> SdDataSource.start()  (setRunning(true), 5s getStatus timer, 1s faultCheck)
  -> bleConnect()                                 :188
       mIsShuttingDown=false; mShutdown=false      :192
       new BluetoothCentralManager(...) if null    :199
       setConnectionState(SCANNING)                :210
       scanForPeripheralsWithAddresses({addr})     :213

onDiscoveredPeripheral()                          :222
  -> setConnectionState(CONNECTING)                :240
  -> stopScan(); autoConnectPeripheral(p, cb)      :242
  -> mReconnectionAttempt = 0                      :246

onConnectedPeripheral()                           :259  -> CONNECTED
onServicesDiscovered()                            :345  -> mBlePeripheral = p  :358
                                                        -> setNotify(...) etc.
onCharacteristicUpdate()                          :496  -> doAnalysis()
  -> SdServer.onSdDataReceived()                  SdServer:1238
```

### 3.2 Fault detection (two independent paths)

| Path | Where | Trigger |
|---|---|---|
| Data-status timer | `SdDataSource.getStatus()` :403 | no 5 s accel burst for `mDataUpdatePeriod + mFaultTimerPeriod` ≈ **35 s** → `onSdDataFault()` :413 |
| Watchdog thread | `SdServer.WatchdogThread` :3000 | no data for **120 s** → `onSdDataFault()` :3063 |

### 3.3 Fault → datasource restart

`SdServer.onSdDataFault()` :1575 → `doFaultPipCheck()` :2606 → `startFaultTimer()`
(30 s `CountDownTimer`, :2628). Once `mFaultTimerCompleted` is true, the restart
block at :1615 runs, gated by:

- `!mDataSourceRestartInProgress`
- `now - mLastDatasourceRestartMillis > 60000`

then `mHandler.postDelayed(1 s)` → `mSdDataSource.stop()` → `postDelayed(3 s)` →
`mSdDataSource.start()`. **All of this runs on the main looper.**

Net effect during an outage: a `stop()`/`start()` cycle roughly every **60 s or
more**, i.e. **~60 cycles per hour**.

### 3.4 Disconnect handling

```java
onDisconnectedPeripheral(p, status)                          :287
  mServicesDiscovered = false;
  if (mShutdown) { CLEANUP; mDisconnected = true; forceCleanup(); }   :292
  else {
    IDLE; toast "WATCH CONNECTION LOST";                     :304
    mShutdown = false; mDisconnected = false;                :318
    if (manager != null && !mIsShuttingDown)
        manager.autoConnectPeripheral(p, cb);                :324
    else scheduleReconnection();                             :331
  }
```

`scheduleReconnection()` (:893, backoff `{1,2,4,8,16}s` then 16 s forever) is
**only reachable from `catch` blocks and the null-manager branch.** In the normal
case — manager exists, no exception — it is never called.

### 3.5 Teardown

```java
stop()                                              :801
  super.stop()               // setRunning(false), cancels timers
  mShutdown = true; mIsShuttingDown = true;          :807
  bleDisconnect()                                    :664
     DISCONNECTING; mShutdown = true
     remove reconnection callbacks
     manager.stopScan()
     mTimeoutHandler.postDelayed(forceCleanup, 5000)  :685   (main looper)
     mBlePeripheral.setNotify(mOsdChar/mHrChar/mBattChar, false)
     mBlePeripheral.cancelConnection()                :728
     else forceCleanup()                              :735
  busy-wait: while(!mDisconnected && waitTime < 5500) Thread.sleep(100)   :827
  if (!mDisconnected) forceCleanup()                  :839

forceCleanup()                                      :747
  remove timeout + reconnection callbacks
  mOsdChar/mStatusChar/mHrChar/mBattChar = null
  mBlePeripheral.cancelConnection(); mBlePeripheral = null    :773
  manager.close(); manager = null                              :783
  mDisconnected = true
```

---

## 4. What blessed-android 2.5.0 actually does

These facts are load-bearing for the analysis. All **[V]**.

| Fact | Source |
|---|---|
| `SCAN_TIMEOUT = 180_000L`, `SCAN_RESTART_DELAY = 1000`, `MAX_CONNECTION_RETRIES = 1` | `BluetoothCentralManager` constants |
| A scan is stopped and restarted every 180 s to dodge Android 9 scan restrictions (`setScanTimer()` / `timeoutRunnable`, on blessed's own main-looper `Handler`) | `setScanTimer()` |
| `close()` clears the internal maps and unregisters the adapter-state receiver. **It does not stop scans, does not cancel `timeoutRunnable` / `autoConnectRunnable`, does not null `bluetoothScanner` / `autoConnectScanner`, and does not cancel or close any peripheral.** | `close()` |
| blessed's own Bluetooth-off handler *does* call `peripheral.cancelConnection()` on every connected **and unconnected** peripheral — so the author knew this was required; `close()` simply omits it | `handleAdapterState(STATE_TURNING_OFF)` / `cancelAllConnectionsWhenBluetoothOff()` |
| `autoConnectPeripheral()` returns immediately (logs `already issued autoconnect for '%s'`) if the address is already in `unconnectedPeripherals` | `autoConnectPeripheral()` |
| `autoConnectPeripheral()` on an **uncached** peripheral (`getType() == PeripheralType.UNKNOWN`) diverts to `autoConnectPeripheralByScan()`, which scans with `ScanMode.LOW_POWER` (~0.5 Hz) instead of the `LOW_LATENCY` used for normal scans | `autoConnectPeripheral()`, `autoConnectScanSettings` |
| `BluetoothPeripheral.autoConnect()` calls `connectGatt(device, /*autoConnect=*/true, cb)` and **starts no connection timer**. Its own comment: *"this will only work for devices that are known! After turning BT on/off Android doesn't know the device anymore!"* | `autoConnect()` |
| `BluetoothPeripheral.connect()` (autoConnect=false) *does* arm `CONNECTION_TIMEOUT_IN_MS = 35000` | `connect()`, `startConnectionTimer()` |
| `cancelConnection()` returns **silently, with no callback**, if `bluetoothGatt == null` or state is already `DISCONNECTED`/`DISCONNECTING` | `cancelConnection()` |
| Every `connect()` / `autoConnect()` registers **two** `BroadcastReceiver`s (bond-state, pairing-request). They are unregistered only in `completeDisconnect()`, i.e. only when the gatt is actually closed | `registerBondingBroadcastReceivers()`, `completeDisconnect()` |
| `onScanFailed(ScanFailure)` is delivered to the app callback for `ALREADY_STARTED`, `APPLICATION_REGISTRATION_FAILED`, `INTERNAL_ERROR`, `FEATURE_UNSUPPORTED`, `OUT_OF_HARDWARE_RESOURCES`, `SCANNING_TOO_FREQUENTLY`, `UNKNOWN` — and blessed calls `stopScan()` first, so scanning is definitively over | `defaultScanCallback.onScanFailed`, `ScanFailure` |
| `onBluetoothAdapterStateChanged(int)`, `onConnectingPeripheral()`, `onDisconnectingPeripheral()` are all available to override | `BluetoothCentralManagerCallback` |
| `internalCallback.disconnected()` calls `removePeripheralFromCaches(addr)` before invoking `onDisconnectedPeripheral` | `internalCallback` |
| On `STATE_TURNING_OFF`, blessed stops all scans and sets `currentCallback = null`; on `STATE_ON` it restarts nothing | `handleAdapterState()` |

**BLE2 overrides only** `onDiscoveredPeripheral`, `onConnectedPeripheral`,
`onConnectionFailed`, `onDisconnectedPeripheral`. It does **not** override
`onScanFailed`, `onBluetoothAdapterStateChanged`, `onConnectingPeripheral` or
`onDisconnectingPeripheral`. **[V]**

---

## 5. Deficiency register

Ranked by likelihood of being *the* cause of the reported symptom. Percentages
are engineering judgement, not measurements; the top three are interlocking and
should be treated as one failure mode.

---

### D1 — Pending GATT clients are orphaned by `forceCleanup()`; registrations accumulate until Android refuses new ones
**Likelihood: ~65 % (primary hypothesis)** · Severity: critical · Evidence: **[V]** mechanism, **[I]** threshold

**Mechanism.**

`mBlePeripheral` is assigned in exactly one place — `onServicesDiscovered()`
:358 — and is set to `null` by `forceCleanup()` :777. It is *not* assigned in
`onDiscoveredPeripheral()`. Therefore, for the whole `CONNECTING` phase, BLE2 has
**no reference to the peripheral that blessed is actively connecting**, while
blessed holds a live `BluetoothGatt` from `connectGatt(autoConnect=true)` plus two
registered `BroadcastReceiver`s.

Now trace a long outage:

1. Watch goes out of range → `onDisconnectedPeripheral` → BLE2 calls
   `autoConnectPeripheral(p, cb)` → peripheral is cached (type came from a scan
   result) → blessed calls `p.autoConnect()` → **gatt #1 registered**, state
   `CONNECTING`, `unconnectedPeripherals[addr] = p`, **no timer armed**.
2. ~35 s later `getStatus()` raises the fault; ~65 s later `SdServer` restarts the
   datasource.
3. `stop()` → `bleDisconnect()` → `mBlePeripheral` is non-null here (same cached
   object), so `cancelConnection()` does eventually close gatt #1. First cycle is
   survivable.
4. `forceCleanup()` sets `mBlePeripheral = null` and `manager = null`; `start()`
   → `bleConnect()` builds **manager #2** and starts scanning.
5. The watch is still out of range, so `onServicesDiscovered` never runs and
   `mBlePeripheral` **stays null**. Any transient discovery, stale scan result, or
   half-open connection attempt now produces a pending gatt that BLE2 cannot see.
6. The next `stop()` → `bleDisconnect()` takes the `mBlePeripheral == null` branch
   :733 → **`forceCleanup()` directly** → `manager.close()`.
   `close()` only does `unconnectedPeripherals.clear()`. **The pending
   `BluetoothGatt` is never `disconnect()`ed or `close()`d, and its two
   `BroadcastReceiver`s are never unregistered.** It is now unreachable and
   permanently registered against the app.
7. Repeat every ~60 s. After an hour: on the order of **60 orphaned GATT client
   registrations and ~120 leaked receivers**.

Android enforces a per-application cap on registered GATT clients (commonly ~32,
OEM-dependent) and on concurrent scan registrations. Once the cap is hit,
`registerApp()` / `startScan()` fail — typically surfacing as
`SCAN_FAILED_APPLICATION_REGISTRATION_FAILED`, GATT error 133, or **nothing at
all**. From that point the app cannot scan and cannot connect, and because BLE2
does not override `onScanFailed` (D4) it has no idea. Only killing the process
releases the registrations.

**Why it fits the report exactly.**

| Observation | Explanation |
|---|---|
| Short interruptions recover | Few or zero restart cycles; the cap is never approached. If the watch returns within ~90 s the fault timer never even triggers a restart. |
| Long interruptions never recover | ~60 cycles/hour steadily consumes the registration budget. |
| Threshold varies between phones | The GATT-client and scan-registration caps are OEM/stack specific. |
| Requires a full app restart | Leaked registrations are per-process; nothing short of process death frees them. |

**Fix.**

1. **Never recreate the manager.** Create `BluetoothCentralManager` once per
   datasource lifetime. On fault-restart, do `stopScan()` + `cancelConnection()`,
   not `close()` + `new`. This single change removes the whole leak class.
2. **Track the connecting peripheral.** Assign `mBlePeripheral = peripheral` in
   `onDiscoveredPeripheral()` (and/or override `onConnectingPeripheral()`), so a
   pending attempt is always cancellable.
3. **If `close()` must be kept**, drain blessed's own bookkeeping first:
   ```java
   for (BluetoothPeripheral p : manager.getConnectedPeripherals()) p.cancelConnection();
   if (mBlePeripheral != null) mBlePeripheral.cancelConnection();
   manager.stopScan();
   manager.close();
   ```
   Note `getConnectedPeripherals()` does **not** include pending/unconnected
   peripherals, which is precisely why (2) is mandatory.
4. Remove the `@NotNull` annotation on `mBlePeripheral` :340 — it is uninitialised
   and explicitly nulled at :777, so the annotation is false.

---

### D2 — No independent connection supervisor: a stalled `autoConnect` is a permanent deadlock
**Likelihood: ~55 % (co-primary with D1)** · Severity: critical · Evidence: **[V]**

**Mechanism.** blessed's `autoConnect()` arms **no timer** and its own source
comment warns it only works for devices Android still knows. If Android drops the
pending background connection request — which it does after Bluetooth toggles,
after long idle periods, and under OEM power management — **no callback of any
kind is delivered**: not `onConnectionFailed`, not `onDisconnectedPeripheral`.

Worse, the address remains in blessed's `unconnectedPeripherals`, so every
subsequent `autoConnectPeripheral()` call short-circuits:

```java
if (unconnectedPeripherals.get(peripheral.getAddress()) != null) {
    Logger.w(TAG,"already issued autoconnect for '%s' ", peripheral.getAddress());
    return;                       // <-- permanent no-op
}
```

The only escapes are `cancelConnection()` (which removes the map entry) or a brand
new manager. BLE2 has neither on a timer:

- `scheduleReconnection()` is unreachable in this path (D7).
- `onConnectionFailed` never fires.
- The sole driver is `SdServer`'s 60 s restart, which is itself gated and runs on
  the main looper.

So BLE2 can sit indefinitely in `ConnectionState.IDLE` believing a connection
attempt is in flight, when in reality nothing is.

**Fix.** Add an independent supervisor inside BLE2 that does not depend on any
blessed callback:

```java
// 30 s tick, started in bleConnect(), cancelled in stop()
if (mIsShuttingDown || mShutdown) return;
if (mConnectionState != ConnectionState.CONNECTED) {
    if (now - mLastConnectionProgressMillis > SUPERVISOR_TIMEOUT_MS) {   // e.g. 45 s
        if (mBluetoothCentralManager != null && mBlePeripheral != null)
            mBluetoothCentralManager.cancelConnection(mBlePeripheral);   // clears blessed's map
        mBluetoothCentralManager.stopScan();
        bleConnect();                                                     // clean re-scan
    }
}
```

Reset `mLastConnectionProgressMillis` in `onDiscoveredPeripheral`,
`onConnectingPeripheral`, `onConnectedPeripheral`, `onServicesDiscovered` and on
every accepted accel notification. Use the manager-level `cancelConnection(p)`,
not `p.cancelConnection()` — only the manager version also clears
`reconnectPeripheralAddresses` / `reconnectCallbacks`.

---

### D3 — A closed manager keeps scanning and drives the *live* manager through the shared callback
**Likelihood: ~30 %** · Severity: high · Evidence: **[V]**

**Mechanism.** blessed posts `timeoutRunnable` (180 s scan restart) on **its own**
main-looper `Handler`, and `close()` does not cancel it. `stopScan()` does cancel
it — and `bleDisconnect()` calls `stopScan()` before `close()`, so the common
`stop()` path is protected. But `forceCleanup()` is also reachable *without* a
preceding `stopScan()`:

- `onDisconnectedPeripheral()` with `mShutdown == true` → `forceCleanup()` :301
- the 5 s timeout post in `bleDisconnect()` :685
- every `catch` path in `bleDisconnect()` :731/:740

If a scan was live at that moment, the closed manager's timer keeps firing:
`stopScan()` → `postDelayed(startScan, 1000)` → forever. `sendScanResult()` then
delivers `onDiscoveredPeripheral(...)` — into **BLE2's single shared
`mBluetoothCentralManagerCallback` field**, which the *new* manager also uses.
BLE2 cannot tell which manager the event came from, so it executes:

```java
mBluetoothCentralManager.stopScan();                              // stops the LIVE manager's scan
mBluetoothCentralManager.autoConnectPeripheral(peripheral, cb);   // live manager + zombie's peripheral
```

Consequences: the live manager's `LOW_LATENCY` address scan is stopped and never
restarted, and the peripheral handed over was built by `getPeripheral(addr)` →
`getRemoteDevice(addr)` → `peripheralType == UNKNOWN` → `isUncached()` true →
blessed diverts to `autoConnectPeripheralByScan()` at `ScanMode.LOW_POWER`
(~0.5 Hz). Recovery probability drops sharply and the app's own scan is gone.

A related sub-case: if blessed is in autoconnect-by-scan mode when `close()` runs,
`close()` clears `reconnectPeripheralAddresses` but leaves `autoConnectRunnable`
posted. On firing, `scanForAutoConnectPeripherals()` builds a filter list from the
now-empty list and calls `startScan(emptyFilters, ...)` — an **unfiltered scan**,
re-armed every 180 s indefinitely.

**Fix.** D1's "one manager for the datasource lifetime" removes this entirely.
If that is not adopted, add an ownership guard:

```java
@Override
public void onDiscoveredPeripheral(BluetoothPeripheral peripheral, ScanResult r) {
    final BluetoothCentralManager mgr = mBluetoothCentralManager;
    if (mgr == null || mgr != mOwnerOfCallback) return;                  // stale manager
    if (!peripheral.getAddress().equalsIgnoreCase(mBleDeviceAddr)) return;
    ...
}
```

and always `stopScan()` before `close()` inside `forceCleanup()`.

---

### D4 — `onScanFailed` is not overridden: a dead scan is silent
**Likelihood: ~30 % as a direct cause, ~90 % as the reason nothing self-heals** · Severity: high · Evidence: **[V]**

blessed calls `stopScan()` and then delivers `onScanFailed(ScanFailure)`. After
that, scanning is definitively over and blessed will not restart it. BLE2 does not
override the method, so the datasource sits in `ConnectionState.SCANNING` forever
with no scan running and no retry scheduled.

The failure codes that matter here — `APPLICATION_REGISTRATION_FAILED`,
`OUT_OF_HARDWARE_RESOURCES`, `SCANNING_TOO_FREQUENTLY` — are exactly what D1 and
D3 produce. This is the reason the leak becomes a *permanent* fault rather than a
transient one.

**Fix.** Cheap and high value:

```java
@Override
public void onScanFailed(ScanFailure failure) {
    Log.e(TAG, "onScanFailed() - " + failure);
    mUtil.writeMemoryLog("BLE2 onScanFailed " + failure);
    if (mIsShuttingDown || mShutdown) return;
    setConnectionState(ConnectionState.IDLE);
    scheduleReconnection();          // unconditional, see D7
}
```

Note blessed throws `SecurityException` out of `startScan` when `BLUETOOTH_SCAN` /
`BLUETOOTH_CONNECT` are not granted; `bleConnect()` already catches that at :214.

---

### D5 — `onBluetoothAdapterStateChanged` is not overridden: no recovery after any Bluetooth off/on
**Likelihood: ~20 %** · Severity: high · Evidence: **[V]**

On `STATE_TURNING_OFF` blessed cancels all connections, clears
`reconnectPeripheralAddresses` / `reconnectCallbacks`, stops all scans and sets
`currentCallback = null`. On `STATE_ON` it restarts nothing. If BLE2's
`autoConnectPeripheral()` lands in that window, blessed rejects it
(`!bluetoothAdapter.isEnabled()` → return, no callback). Nothing ever restarts the
scan.

This is triggered not only by the user toggling Bluetooth, but by airplane mode,
car mode, some OEM battery-saver routines, and Bluetooth stack crashes. It is a
plausible contributor to "varies between phones".

**Fix.**

```java
@Override
public void onBluetoothAdapterStateChanged(int state) {
    Log.i(TAG, "onBluetoothAdapterStateChanged() - " + state);
    if (state == BluetoothAdapter.STATE_ON && !mIsShuttingDown && !mShutdown) {
        mReconnectionAttempt = 0;
        scheduleReconnection();      // or bleConnect() directly
    }
}
```

---

### D6 — `stop()` busy-waits on the main thread and is guaranteed to time out
**Likelihood: ~20 % as a direct cause; certain as an aggravator** · Severity: high · Evidence: **[V]**

`SdServer.onSdDataFault()` posts the restart to `mHandler` (main looper), so
`mSdDataSource.stop()` runs **on the main thread**. `stop()` then busy-waits:

```java
while (!mDisconnected && waitTime < 5500) { Thread.sleep(100); waitTime += 100; }   :827
```

`mDisconnected` can only be set by:

- `onDisconnectedPeripheral()` — posted by blessed to `new Handler(Looper.getMainLooper())` (:202)
- the 5 s `forceCleanup()` post on `mTimeoutHandler` — also `new Handler(Looper.getMainLooper())` (:153)

**Both are on the thread that is currently blocked.** So whenever the watch is
unreachable the wait always expires by timeout: a **5.5 s main-thread stall on
every restart cycle**, roughly once a minute for the duration of the outage.
Consequences: ANR exposure, blessed callbacks queued and delivered late or
batched, and OEM "app is not responding" kills — which would look exactly like
"stopped reconnecting".

Additionally, `bleDisconnect()` issues `setNotify(false)` and `cancelConnection()`
against a peripheral that is already disconnected; blessed's `cancelConnection()`
returns silently with **no callback** when `bluetoothGatt == null` or state is
`DISCONNECTED`, so those calls cannot set `mDisconnected` either.

**Fix.** Make shutdown asynchronous — delete the busy-wait, rely on the disconnect
callback plus the existing 5 s force-cleanup timer, and have `SdServer` not block
on `stop()` completing. `SdServer.onDestroy()` already tolerates an async stop
(it joins a worker thread with a 6 s cap, :771).

---

### D7 — `scheduleReconnection()` is unreachable in the normal path; the backoff design is dead code
**Likelihood: ~20 %** · Severity: medium-high · Evidence: **[V]**

The class advertises "exponential backoff, retry indefinitely" (:145-146, :891),
but every call site is inside a `catch` or a null/shutting-down `else`:
:216, :251, :255, :278, :282, :327, :331. In the normal case — manager present,
no exception — the backoff loop **never runs**. Below `SdServer`'s 60 s restart
there is therefore *no* periodic retry at all.

Also, `mReconnectionAttempt = 0` is reset in `onDiscoveredPeripheral()` :246,
*before* any connection succeeds, so a discovery that never leads to a connection
still resets the counter — the backoff can never escalate.

**Fix.** Call `scheduleReconnection()` **unconditionally** at the end of the
non-shutdown branch of `onDisconnectedPeripheral()` and in `onConnectionFailed()`,
in addition to (not instead of) `autoConnectPeripheral()`. Make it idempotent
(`mReconnectionHandler.removeCallbacksAndMessages(null)` before posting) so it
cannot stack. Reset `mReconnectionAttempt` only in `onConnectedPeripheral()` /
`onServicesDiscovered()`.

---

### D8 — Shutdown-flag handling is fragile
**Likelihood: ~10 %** · Severity: medium · Evidence: **[V]**

- `onDisconnectedPeripheral()` sets `mShutdown = false; mDisconnected = false;`
  (:318-319) in the non-shutdown branch. A disconnect callback queued before
  `stop()` but delivered after it can therefore clear a shutdown flag mid-shutdown.
- `mIsShuttingDown` is cleared only in `bleConnect()` :192. `scheduleReconnection()`
  early-returns on either flag :895, so any path that leaves them set suppresses
  all retries permanently.
- `forceCleanup()` does not reset `mIsShuttingDown` / `mShutdown`, so a
  `forceCleanup()` reached from `onDisconnectedPeripheral` :301 leaves them set
  until the next `bleConnect()`.

**Fix.** Give the flags a single owner: set them only in `start()`/`stop()`, never
in a callback. Reset both in `start()` before anything else, and assert the
expected state at each transition.

---

### D9 — `LE_CODED` / `PhyOptions.S8` requested from an nRF52832
**Likelihood: ~5 % as a cause** · Severity: low · Evidence: **[V]** + **[I]**

`onServicesDiscovered()` :368 requests `PhyType.LE_CODED` with `PhyOptions.S8`.
PineTime is nRF52832, which has **no LE Coded PHY** (that is nRF52840 only). The
request fails and produces a failed `onPhyUpdate`. It is not fatal, but it
occupies a slot in blessed's serialised command queue, which is already carrying
`requestMtu(185)`, `requestConnectionPriority(HIGH)`, `readPhy()`,
`readRemoteRssi()` and ~10 `readCharacteristic()` calls issued back-to-back.

`requestConnectionPriority(HIGH)` (≈11.25–15 ms interval) is accepted by the
firmware — `OnGAPEvent` returns `0` for `BLE_GAP_EVENT_CONN_UPDATE_REQ`
(`NimbleController.cpp:237-245`, `:384`) — and substantially raises watch power
draw for a 25 Hz, 18-byte notify stream. `BALANCED` (30–50 ms) is ample.

**Fix.** Drop the `setPreferredPhy`/`readPhy` calls (or gate them on a capability
check), and consider `ConnectionPriority.BALANCED`.

---

### D10 — Stale characteristic objects reused across connections
**Likelihood: ~5 %** · Severity: low · Evidence: **[V]**

`mOsdChar`, `mStatusChar`, `mHrChar`, `mBattChar` are `BluetoothGattCharacteristic`
instances belonging to a specific (now closed) `BluetoothGatt`. `bleDisconnect()`
:695-724 passes them to `setNotify()` on a peripheral that may already be
disconnected or may be a *different* connection. On Android 13+ these are value
objects tied to a dead gatt, so the calls are meaningless at best. They are nulled
in `forceCleanup()` :765-768, which is correct — but the window between disconnect
and cleanup is not.

**Fix.** Prefer the UUID-based overloads (`setNotify(serviceUuid, charUuid, bool)`)
and null the characteristic references in `onDisconnectedPeripheral()`, not only in
`forceCleanup()`.

---

## 6. Firmware observations (`/home/graham/osd/PineTimeSD`)

The firmware is **not** the primary suspect: `MotionController::Update()` calls
`service->OnNewMotionValues()` unconditionally, and
`MotionService::OnNewMotionValues()` simply returns early when
`connHandle == BLE_HS_CONN_HANDLE_NONE` (`MotionService.cpp:113-127`). Streaming
therefore resumes as soon as the phone re-subscribes. `CheckOsdTimeout()`
(`MotionController.cpp:72-79`, 30 s) only changes the *displayed* `osdStatus`; it
does not gate transmission, so there is no firmware-side reconnect deadlock.
`BLE_GAP_SUBSCRIBE_REASON_TERM` correctly clears the notify flags
(`NimbleController.cpp:322-325`).

Worth fixing regardless:

### F1 — Advertising degrades to ~1 s intervals after ~8 hours
`StartAdvertising()` (`NimbleController.cpp:139-179`) fast-advertises
(`itvl 32–47` units ≈ 20–29 ms) for the first 15 calls, then drops to
`itvl 1636–1651` units ≈ **1.02–1.03 s**. Each advertising session is started with
`ble_gap_adv_start(addrType, NULL, 2000, ...)` — a **2000-second** duration — and
`fastAdvCount` increments once per session, so the switch to slow advertising
happens after ~15 × 2000 s ≈ **8.3 hours** of continuous advertising.
`fastAdvCount` is reset on disconnect (`:226`), on connect failure (`:202`) and on
`EnableRadio()` (`:406`), so a fresh disconnect gets fast advertising again — but
an overnight outage can still land in slow mode.

Combined with blessed's `ScanMode.LOW_POWER` autoconnect scan (~0.5 Hz, D3), the
discovery probability per second becomes low enough to matter.
**Recommendation:** keep fast advertising indefinitely while disconnected, or cap
the slow interval at ~200–400 ms rather than ~1 s.

### F2 — Restart path depends on `IsConnected()`
`BLE_GAP_EVENT_DISCONNECT` restarts advertising only
`if (bleController.IsConnected())` (`:224-228`). If a disconnect is ever delivered
without a preceding successful `bleController.Connect()` (`:206`), the watch stops
advertising until reboot and becomes invisible to the phone. Consider making the
restart unconditional (guarded only by `IsRadioEnabled()`).

### F3 — `ASSERT(rc == 0)` on `ble_gap_adv_start`
`:178` asserts on failure. `ble_gap_adv_start` returns `BLE_HS_EALREADY` if
advertising is already active — a recoverable condition that would instead cause a
fault/reset. Prefer logging and continuing.

### F4 — Dangling pointer in `MotionService`
```cpp
void MotionService::OnNewMotionValues(int16_t *fifo, uint16_t nFifo) {
  this->data = fifo;      // stores the caller's buffer pointer
  this->nData = nFifo;
  ...
}
```
`OnStepCountRequested()` later dereferences `this->data` on a characteristic
**read** (`MotionService.cpp:80-86`). The buffer belongs to the motion driver and
may have been reused. BLE2 only uses `setNotify` on this characteristic, so impact
is currently low, but it is a latent use-after-free. An unused member buffer
`accBuf[9]` is already declared in `MotionService.h:47` — copy into it instead.
Also `int16_t nData` is assigned from `uint16_t nFifo` (`MotionService.h:36`).

### F5 — Advertise payload carries no identifying information
The ADV payload contains only the DFU 128-bit UUID; the device name is only in the
scan response (`:160-168`). Address-filtered scanning still works, but adding the
OSD/Motion service UUID to the ADV payload would let the phone fall back to
service-UUID scanning if the stored MAC ever became stale — a useful safety net
given D1/D2.

### F6 — Misleading log
`:185` reads `event->connect.status` inside the `BLE_GAP_EVENT_ADV_COMPLETE` case —
a union misread. Harmless, but it corrupts the diagnostic that would be most useful
when investigating exactly this issue.

### F7 — Blindly accepting central connection parameters
`OnGAPEvent` returns `0` for `BLE_GAP_EVENT_CONN_UPDATE_REQ`, accepting whatever
the phone asks for. Consider rejecting intervals below ~15 ms to protect battery
and link stability (see D9).

---

## 7. Recommended action plan

### Phase 0 — Instrument first (do this before any behavioural change)

Several deficiencies are plausible; confirming which one fires is cheap and avoids
fixing the wrong thing.

1. **Enable blessed's own logging** — one line, and it surfaces the exact strings
   that discriminate between the hypotheses:
   ```java
   mBluetoothCentralManager.enableLogging();
   ```
   Watch for `already issued autoconnect for`, `scanning timeout, restarting scan`,
   `peripheral with address '%s' not in Bluetooth cache`, `scan failed with error
   code`, `cannot cancel connection because no connection attempt is made yet`.
2. **Override and log** `onScanFailed`, `onBluetoothAdapterStateChanged`,
   `onConnectingPeripheral`, `onDisconnectingPeripheral`.
3. **Log a lifecycle counter**: number of `BluetoothCentralManager` instances
   created, number of `forceCleanup()` calls, number of `autoConnectPeripheral()`
   calls, plus `mConnectionState`, `mBlePeripheral == null`, `isScanning()` and
   both shutdown flags at every transition.
4. **The decisive measurement** — while the phone is in the stuck `FAULT` state,
   before restarting the app:
   ```
   adb shell dumpsys bluetooth_manager
   ```
   Inspect the per-app **registered GATT clients** and **active scans**. A large
   and growing count confirms D1/D3 conclusively. Also capture `adb bugreport`.
5. Reproduce on a schedule: 2 min / 30 min / 2 h out of range, plus a
   Bluetooth off/on test, capturing (4) at each stage.

### Phase 1 — Stop the leak (highest expected value, low risk)

- D1(1): one `BluetoothCentralManager` per datasource lifetime; never `close()` +
  recreate on fault-restart.
- D1(2): assign `mBlePeripheral` in `onDiscoveredPeripheral()` / override
  `onConnectingPeripheral()`.
- D1(3): if `close()` is retained anywhere, drain peripherals first.
- D6: remove the main-thread busy-wait in `stop()`.

### Phase 2 — Guarantee a retry always exists

- D2: independent 30–45 s connection supervisor using manager-level
  `cancelConnection()` + re-scan.
- D7: call `scheduleReconnection()` unconditionally from `onDisconnectedPeripheral`
  and `onConnectionFailed`; make it idempotent; reset the counter only on success.
- D4: override `onScanFailed`.
- D5: override `onBluetoothAdapterStateChanged`.

### Phase 3 — Robustness and hygiene

- D3 ownership guard (if Phase 1(1) is not adopted).
- D8 flag ownership; D9 drop `LE_CODED`/`S8`, consider `BALANCED`;
  D10 UUID-based `setNotify` and null characteristics on disconnect.
- Firmware F1–F7 as a separate PineTimeSD change set.

### Phase 4 — Environment

Not a code fix, but materially affects every hypothesis above: request
`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and document the OEM-specific
steps (Samsung *Adaptive battery* / *Sleeping apps*, Xiaomi *Autostart* +
*battery saver*, Huawei *Protected apps*). The service already resolves
`FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` for BLE2 (`SdServer.java:395-400`),
which is correct and has no Android 15 time cap.

---

## 8. Verification plan

| # | Test | Expected after fix |
|---|---|---|
| 1 | Connect, 2 min out of range, return | Reconnect + data resume (already works — regression guard) |
| 2 | 30 min out of range, return | Reconnect within one supervisor interval |
| 3 | **2 h out of range, return** | **Reconnect**; `dumpsys bluetooth_manager` shows a *bounded* GATT-client count throughout |
| 4 | 8 h (overnight) out of range, return | Reconnect (also exercises firmware F1) |
| 5 | Bluetooth off → on while connected | Reconnect without app restart (D5) |
| 6 | Revoke Bluetooth permission mid-run, re-grant | No permanent stall |
| 7 | Watch rebooted while out of range | Reconnect |
| 8 | Monitor for 24 h connected | No duplicate data rate (guards the `mServicesDiscovered` double-subscribe check at :350) |

For test 3, the pass criterion is not just "it reconnects" but that the registered
GATT-client and scan counts stay flat across the whole outage. That is the
objective evidence that the leak is gone.

---

## 9. Summary

| ID | Deficiency | Likelihood | Severity |
|---|---|---|---|
| D1 | Pending GATT clients orphaned by `forceCleanup()`; registrations accumulate | ~65 % | Critical |
| D2 | No independent supervisor; stalled `autoConnect` is a permanent no-op deadlock | ~55 % | Critical |
| D3 | Closed manager keeps scanning and drives the live manager via the shared callback | ~30 % | High |
| D4 | `onScanFailed` not overridden — dead scan is silent | ~30 % | High |
| D5 | `onBluetoothAdapterStateChanged` not overridden — no recovery after BT toggle | ~20 % | High |
| D6 | `stop()` busy-waits on the main thread; guaranteed 5.5 s stall per cycle | ~20 % | High |
| D7 | `scheduleReconnection()` unreachable; backoff loop is dead code | ~20 % | Med-High |
| D8 | Shutdown-flag races suppress retries permanently | ~10 % | Medium |
| D9 | `LE_CODED`/`S8` unsupported on nRF52832; `HIGH` priority accepted by firmware | ~5 % | Low |
| D10 | Stale characteristic objects reused across connections | ~5 % | Low |

**Working conclusion.** The most probable explanation is D1 + D2 acting together:
`forceCleanup()` → `BluetoothCentralManager.close()` does not release pending GATT
clients, so each fault-driven restart during an outage leaks a registration against
a hard per-app cap; simultaneously, a stalled `autoConnect` cannot be retried
because blessed short-circuits repeat calls and BLE2 has no independent supervisor.
Once the cap is reached the app can neither scan nor connect, `onScanFailed` is not
handled so the failure is silent, and only a process restart releases the
registrations. D1's fix (a single long-lived manager plus tracking the connecting
peripheral) plus D2's supervisor should resolve the reported symptom; Phase 0
instrumentation will confirm it before the changes are made.
