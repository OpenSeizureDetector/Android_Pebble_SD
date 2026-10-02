# SdDataSourceBLE2 — Out-of-Range Reconnection Analysis

**Source of Report**:  OpenCode using Qwen3.8_MAX model and the following prompt: "Review the SdDataSourceBLE2 code (app/src/main/java/uk/org/openseizuredetector/datasource/SdDataSourceBLE2.java) to see how it handles the watch going out of range for a period then coming back into range.  We find that short term interruptions work correctly with the watch reconnecting and resuming data transfer, but longer interruptoins result in the watch not reconnecting and the system has to be re-started to restore function.  You should review how the reconnection process works and identify deficiencies with recommended improvements (and their likelihood of successfully identifying the issue).  You can also consider the device firmware at /home/graham/osd/PineTimeSD in case changes to the device firmware would improve performance.  Write up your analysis to a file so it can be shared for wider review."

**Status:** Phase 0 instrumentation + Phase 1/2 fixes implemented — awaiting overnight validation run
**Component:** `app/src/main/java/uk/org/openseizuredetector/datasource/SdDataSourceBLE2.java`
**Library:** `com.github.weliem:blessed-android:2.5.0` (`app/build.gradle`)
**Firmware reviewed:** `/home/graham/osd/PineTimeSD` (`develop`, `fce434be`)
**Build verified:** `:app:compileDebugJavaWithJavac` and `:app:testDebugUnitTest` both pass
**Field evidence:** two on-device captures, 2026-10-01 — see section 11

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
| **`BluetoothCentralManager.cancelConnection(p)` is the only complete undo.** If the address is in `reconnectPeripheralAddresses` it removes it from that list *and* from `reconnectCallbacks` *and* from `unconnectedPeripherals`, calls the private `stopAutoconnectScan()`, and posts a callback; otherwise, if the peripheral is in the unconnected or connected map, it delegates to `peripheral.cancelConnection()`; otherwise it just logs `cannot cancel connection to unknown peripheral %s`. `BluetoothPeripheral.cancelConnection()` alone does **not** clear the reconnect bookkeeping and does **not** stop the autoconnect scanner. | `cancelConnection()` |
| **The 180 s scan restart is posted as two separate messages.** `timeoutRunnable` (`$8`) logs `scanning timeout, restarting scan`, captures `currentCallback` + `currentFilters`, calls `stopScan()`, then `postDelayed($8$1, SCAN_RESTART_DELAY)` — and `$8$1` is what actually calls `startScan(...)` again. Calling `stopScan()` in the ~1 s between the two removes the *timer* but not the already-queued *restart*, and `close()` cancels neither, so a closed manager can start a live system scan that nothing can ever stop. | `$8`, `$8$1`, `setScanTimer()` |
| Scan settings: the manager's own `scanSettings` (used by `scanForPeripheralsWithAddresses`) is built from `ScanMode.LOW_LATENCY` (Android `SCAN_MODE_LOW_LATENCY = 2`); `autoConnectScanSettings` is built from `ScanMode.LOW_POWER` (Android `SCAN_MODE_LOW_POWER = 0`) | constructor, `getScanSettings()` |
| `autoConnectPeripheral()` has **four** silent early-return branches, none of which produces a callback: `already connected to %s`, `already issued autoconnect for '%s'`, `cannot connect to peripheral because Bluetooth is off`, and `peripheral does not support Bluetooth LE` | `autoConnectPeripheral()` |

**BLE2 originally overrode only** `onDiscoveredPeripheral`, `onConnectedPeripheral`,
`onConnectionFailed`, `onDisconnectedPeripheral`. As of Phase 0 it also overrides
`onScanFailed`, `onBluetoothAdapterStateChanged`, `onConnectingPeripheral` and
`onDisconnectingPeripheral`; as of Phase 2 the first two take recovery action rather
than only logging. **[V]**

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

> **Status: items 1–3 are IMPLEMENTED.** See section 8 for what was added and
> section 9 for the test plan and procedure that uses it. Items 4–5 are test
> activities, not code, and are covered by section 9.

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
   ✅ *Implemented in `bleConnect()`.*
2. **Override and log** `onScanFailed`, `onBluetoothAdapterStateChanged`,
   `onConnectingPeripheral`, `onDisconnectingPeripheral`.
   ✅ *Implemented — all four overridden, log-only, no recovery action taken yet so
   that behaviour is unchanged until Phase 2.*
3. **Log a lifecycle counter**: number of `BluetoothCentralManager` instances
   created, number of `forceCleanup()` calls, number of `autoConnectPeripheral()`
   calls, plus `mConnectionState`, `mBlePeripheral == null`, `isScanning()` and
   both shutdown flags at every transition.
   ✅ *Implemented as process-lifetime `AtomicInteger` counters plus a full
   `STATE-DUMP` snapshot on every callback and a 60 s heartbeat.*
4. **The decisive measurement** — while the phone is in the stuck `FAULT` state,
   before restarting the app:
   ```
   adb shell dumpsys bluetooth_manager
   ```
   Inspect the per-app **registered GATT clients** and **active scans**. A large
   and growing count confirms D1/D3 conclusively. Also capture `adb bugreport`.
5. Reproduce on a schedule: 2 min / 30 min / 2 h out of range, plus a
   Bluetooth off/on test, capturing (4) at each stage. See section 9.

### Phase 1 — Stop the leak (highest expected value, low risk)

> **Status: IMPLEMENTED except D1(1).** See section 12. D1(1) — one manager per
> datasource lifetime — was deliberately *not* adopted: the field evidence in
> section 11 showed the per-cycle `create`/`close` pair is balanced (receiver
> registered ↔ unregistered, scan started ↔ stopped) and leaks nothing on its own,
> so changing the lifecycle would have added risk without addressing a measured
> problem. Teardown was instead made provably complete, which closes every leak
> vector `close()` was implicated in.

- D1(1): one `BluetoothCentralManager` per datasource lifetime; never `close()` +
  recreate on fault-restart. ❌ *Not adopted — see note above.*
- D1(2): assign `mBlePeripheral` in `onDiscoveredPeripheral()` / override
  `onConnectingPeripheral()`. ✅ *Implemented.*
- D1(3): if `close()` is retained anywhere, drain peripherals first. ✅ *Implemented
  as a four-step ordered teardown: `stopScan()` → manager-level `cancelConnection()`
  → `close()` → delayed zombie sweep.*
- D6: remove the main-thread busy-wait in `stop()`. ✅ *Implemented — teardown is now
  synchronous, so there is nothing to wait for.*

### Phase 2 — Guarantee a retry always exists

> **Status: IMPLEMENTED.** See section 12.

- D2: independent 30–45 s connection supervisor using manager-level
  `cancelConnection()` + re-scan. ✅ *Implemented as a 30 s tick with five rules.*
- D7: call `scheduleReconnection()` unconditionally from `onDisconnectedPeripheral`
  and `onConnectionFailed`; make it idempotent; reset the counter only on success.
  ✅ *Implemented, with one deviation: `onDisconnectedPeripheral` now re-scans
  immediately rather than going through the backoff, because that is the path field
  evidence proved works; `onConnectionFailed` uses the backoff so a device that is
  refusing connections is not hammered.*
- D4: override `onScanFailed`. ✅ *Implemented — now schedules a backoff retry.*
- D5: override `onBluetoothAdapterStateChanged`. ✅ *Implemented — re-scans 2 s after
  `STATE_ON`.*

Also adopted from Phase 2's evidence: **discovery now leads to `connectPeripheral()`
rather than `autoConnectPeripheral()`**, because the device is advertising at that
moment and a direct connect is bounded by blessed's 35 s timer, so every attempt is
guaranteed to report an outcome.

### Phase 3 — Robustness and hygiene

> **Status: D3 and D10 IMPLEMENTED; D8, D9 and the firmware items still open.**

- D3 ownership guard (if Phase 1(1) is not adopted). ✅ *Implemented as
  `isCallbackStale()` on every manager callback, plus the post-teardown zombie sweep.*
- D8 flag ownership; D9 drop `LE_CODED`/`S8`, consider `BALANCED`;
  D10 UUID-based `setNotify` and null characteristics on disconnect.
  ⚠️ *D10 partially implemented — characteristics are now nulled on disconnect and at
  teardown; UUID-based `setNotify` not changed. D8 and D9 deliberately untouched: D9
  changes PHY negotiation, which would add an uncontrolled variable to the overnight
  validation run.*
- Firmware F1–F7 as a separate PineTimeSD change set. ❌ *Open.*

### Phase 4 — Environment

Not a code fix, but materially affects every hypothesis above: request
`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and document the OEM-specific
steps (Samsung *Adaptive battery* / *Sleeping apps*, Xiaomi *Autostart* +
*battery saver*, Huawei *Protected apps*). The service already resolves
`FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` for BLE2 (`SdServer.java:395-400`),
which is correct and has no Android 15 time cap.

---

## 8. Instrumentation implemented (Phase 0)

All changes are confined to `SdDataSourceBLE2.java`. **They were log-only at the time
— no control flow, timing, retry policy or state machine behaviour was altered** — so
that build reproduced the original defect while making it observable, which is what
produced the field evidence in section 11. The Phase 1/2 behavioural fixes have since
been applied on top of it, as described in section 12; all of this instrumentation is
retained. Two things changed as a result: `onScanFailed` and
`onBluetoothAdapterStateChanged` now take recovery action rather than only logging,
and the `D1 PATH TAKEN` message no longer fires when nothing was actually pending.

Build status: `:app:compileDebugJavaWithJavac` and `:app:testDebugUnitTest` both
pass with no new warnings attributable to this file.

### 8.1 New callback overrides

Four `BluetoothCentralManagerCallback` methods that BLE2 previously did not
implement are now overridden. Each **logs and then calls `super`** — deliberately
no recovery action, so behaviour is unchanged until Phase 2.

| Override | Why it matters | Deficiency |
|---|---|---|
| `onScanFailed(ScanFailure)` | blessed calls `stopScan()` before delivering this, so scanning is definitively over and will not be restarted. Previously silent. | D4 |
| `onBluetoothAdapterStateChanged(int)` | blessed tears everything down on `STATE_TURNING_OFF` and restarts nothing on `STATE_ON`. Previously invisible. | D5 |
| `onConnectingPeripheral(BluetoothPeripheral)` | Distinguishes "blessed is trying" from "blessed has silently given up". | D2 |
| `onDisconnectingPeripheral(BluetoothPeripheral)` | Brackets the shutdown sequence so the 5.5 s stall can be timed. | D6 |

### 8.2 Process-lifetime counters

`static AtomicInteger` / `AtomicLong` — deliberately static, because the D1 leak is
a **per-process** resource budget, so the numbers must accumulate across
`SdDataSourceBLE2` instances as well as across `stop()`/`start()` cycles. Atomic
because `stop()` (hence `forceCleanup()`) can run on `SdServer.onDestroy()`'s worker
thread.

| Counter | Meaning | Anomaly that implicates |
|---|---|---|
| `mgrCreated` | `BluetoothCentralManager` instances created | **D1/D3** — rises without bound across an outage |
| `forceCleanup` | `forceCleanup()` calls | D1 — tracks manager churn |
| `scans` | scans started | D4 — compare against `scanFailed` |
| `scanFailed` | `onScanFailed` callbacks | **D4** — any non-zero value is significant |
| `discovered` | `onDiscoveredPeripheral` callbacks | baseline |
| `autoConnect` | `autoConnectPeripheral` calls issued | **D2** — rises while `connected` stays flat |
| `connected` / `connFailed` / `disconnected` | connection outcomes | D2 — `autoConnect` rising with none of these |
| `adapterChanges` | adapter state changes | **D5** |
| `start` / `stop` | datasource lifecycle pairs | correlates with `SdServer`'s 60 s restart |
| `accBursts` | completed 5 s bursts handed to `doAnalysis()` | confirms data actually resumed |
| `droppedUpdates` | notifications discarded while shut down / not running | **D6/D8** — a burst shows data thrown away |

Plus `sLastAccDataMillis`, so every dump reports `msSinceLastAccBurst`.

### 8.3 State tracking

| Field | Purpose |
|---|---|
| `mManagerGeneration` / `sManagerGeneration` | id per manager instance, so a callback from a superseded manager shows up as an out-of-sequence generation |
| `mScanActive` | our own view of whether our address-filtered scan is running |
| `mConnectAttemptPending`, `mConnectAttemptStartMillis` | age of the outstanding connection attempt |

Two automatic anomaly detectors run inside `logState()` and the heartbeat:

- `scanning(blessed)=true` while `scanActive(ours)=false` →
  `ANOMALY ... A superseded manager is probably still scanning and routing
  callbacks here (D3)`.
- `connectPending` older than 60 s →
  `ANOMALY ... blessed arms no timer for autoConnect, so this will never resolve on
  its own (D2)`.

And in `onDiscoveredPeripheral`, a discovery delivered while `mScanActive=false`
logs `SUSPECT ZOMBIE CALLBACK` (D3).

### 8.4 Other instrumentation

- `setConnectionState(newState, reason)` — every one of the 10 call sites now
  records *why* the transition happened, so a state trace reads as cause and effect.
  A no-op transition is also logged (`STATE unchanged at ...`).
- `logState(context)` — full snapshot: state, generation, manager identity hash,
  `mBlePeripheral` identity hash (or `null`), our scan flag vs blessed's
  `isScanning()`, pending-attempt age, `mServicesDiscovered`, `mShutdown`,
  `mIsShuttingDown`, `mDisconnected`, `isRunning()`, `mReconnectionAttempt`,
  `msSinceLastAccBurst`, thread name, and all counters. Called on entry to
  `bleDisconnect()` and `stop()`, and from the heartbeat.
- 60 s diagnostic heartbeat on a dedicated `mDiagHandler` (so it survives
  `removeCallbacksAndMessages(null)` on the other two handlers). While connected
  and receiving data it emits one compact line; otherwise a full `STATE-DUMP`.
  Started in `bleConnect()` **before** the scan attempt, so a repeatedly failing
  scan still produces periodic dumps. Stopped in `bleDisconnect()` and
  `forceCleanup()`.
- `mBluetoothCentralManager.enableLogging()` on manager creation.
- `mUtil.writeMemoryLog(...)` / `writeExceptionLog(...)` at manager creation,
  connect, connection-failed, disconnect, scan-failed, adapter-state change, the D1
  path, and busy-wait expiry — so the key events survive in the on-device syslog
  even when logcat is not attached.
- Explicit `D1 PATH TAKEN` error log at the `mBlePeripheral == null` branch of
  `bleDisconnect()`, and a pre-`close()` log of
  `getConnectedPeripherals().size()` in `forceCleanup()`.

### 8.5 Log line signatures to grep for

Every diagnostic line is prefixed `BLE2DIAG:` under tag `SdDataSourceBLE2`.

```
adb logcat -v time | grep BLE2DIAG
```

| Signature | Meaning |
|---|---|
| `BluetoothCentralManager CREATED - generation=N, process-lifetime total=M` | manager churn — the D1 leak counter |
| `manager REUSED - generation=N` | good — no new manager this cycle |
| `D1 PATH TAKEN - mBlePeripheral is null` | **a GATT client is about to be orphaned** |
| `closing manager generation=N connectedPeripherals=K` | what blessed is dropping; pending peripherals are not enumerable |
| `SUSPECT ZOMBIE CALLBACK` | D3 |
| `ANOMALY - blessed reports an active scan but ...` | D3 |
| `ANOMALY - connection attempt pending for Ns` | D2 |
| `onScanFailed #N failure=...` | D4 |
| `stop() is running on the MAIN thread` | D6 precondition |
| `BUSY-WAIT EXPIRED after Nms` | **D6 confirmed** — main thread blocked that long |
| `onCharacteristicUpdate DROPPED #N` | D6/D8 — data discarded |
| `scheduleReconnection SUPPRESSED by shutdown flags` | D8 |
| `backoff retry FIRING` | D7 — note how rarely this appears |
| `disconnect reason is CONNECTION_TIMEOUT` | confirms the watch went out of range |
| `acc burst #N accepted` | data flowing again — recovery confirmed |

blessed's own messages appear under logcat tags `BluetoothCentralManager` and
`BluetoothPeripheral` — **logcat only**, they do not reach the app's syslog file:

```
adb logcat -v time -s SdDataSourceBLE2:* BluetoothCentralManager:* BluetoothPeripheral:*
```

The strings to look for are `already issued autoconnect for`,
`scanning timeout, restarting scan`,
`peripheral with address '%s' not in Bluetooth cache`,
`scan failed with error code`, and
`cannot cancel connection because no connection attempt is made yet`.

---

## 9. Test plan and procedure

### 9.1 Objectives

1. **Confirm or refute D1** — that Bluetooth stack registrations accumulate during
   a long outage until Android refuses new ones.
2. Identify which of D2–D8 also fire, so Phase 1/2 effort is spent correctly.
3. Establish a **baseline** that the same procedure can be re-run against after the
   fixes, to prove the leak is gone rather than merely that "it reconnected once".

Objective 3 is the important one. "It reconnected" is not a pass criterion; a
bounded registration count across a 2 h outage is.

### 9.2 Prerequisites

| Item | Requirement |
|---|---|
| Phone | The model that reproduces the fault. If more than one reproduces, test the fastest-failing one first. Record make/model/Android version. |
| Watch | PineTime running the PineTimeSD firmware, OSD app streaming. |
| Build | Debug APK containing the section 8 instrumentation. |
| Host | PC with `adb`, USB debugging authorised, ~10 GB free for bugreports. |
| Range control | A way to put the watch reliably out of range — a different floor, a car, or a microwave/Faraday bag. Verify by watching RSSI drop before starting the timer. |
| Battery optimisation | **Leave as the user has it.** Do not whitelist the app for the baseline run — OEM power management is part of the suspected mechanism. Record the setting. |
| Time | Tests T1–T5 ≈ half a day. T6 (overnight) adds one night. T9 (24 h soak) adds a day. |

### 9.3 Setup procedure

1. Install the instrumented build:
   ```
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
2. In the app, set **Settings → Logging → System Log Level** to `VERBOSE` so the
   diagnostic lines reach the on-device syslog as well as logcat. (Key
   `SysLogLevel`, `app/src/main/res/xml/logging_prefs.xml:55`; files are written
   daily to `/sdcard/Android/data/uk.org.openseizuredetector/files/logs/`.)
3. Select the **BLE2** data source and pair with the watch via the BLE scan screen.
4. Confirm the healthy baseline before starting any test — all three of:
   ```
   adb logcat -c
   adb logcat -v time -s SdDataSourceBLE2:* BluetoothCentralManager:* BluetoothPeripheral:* \
       | tee baseline.log
   ```
   - `BLE2DIAG: heartbeat OK - state=CONNECTED` appearing once per minute
   - `acc burst #N accepted` with `N` increasing by ~12 per minute (5 s bursts)
   - `mgrCreated=1`, `forceCleanup=0`, `scanFailed=0`
5. Open a second shell for the registration measurements:
   ```
   adb shell dumpsys bluetooth_manager > dumpsys_t0.txt
   ```
   Note the app's entry: **registered GATT clients**, **active scans**, and
   **registered receivers**. Record the three numbers in the sheet (9.6). This is
   the single most important measurement in the whole plan.

### 9.4 Capture commands

Run these at every observation point defined below. Keep the filenames — the
before/after comparison *is* the result.

```
# registration snapshot (the decisive measurement)
adb shell dumpsys bluetooth_manager > dumpsys_<test>_<point>.txt

# full system state, for OEM power-management and ANR evidence
adb bugreport bugreport_<test>_<point>.zip

# on-device syslog copy
adb pull /sdcard/Android/data/uk.org.openseizuredetector/files/logs/ logs_<test>/

# extract just the diagnostic trace from a captured logcat
grep BLE2DIAG <logfile> > <logfile>.diag

# count manager creations and cleanups across a whole capture
grep -c "BluetoothCentralManager CREATED" <logfile>
grep -c "forceCleanup #" <logfile>
grep -c "D1 PATH TAKEN" <logfile>
```

### 9.5 Test cases

For each test: start logcat capture, take a `dumpsys` snapshot at every observation
point, and **do not restart the app** unless the test says so. If the app reaches
the stuck `FAULT` state, take the snapshot *before* touching it — that snapshot is
the evidence.

---

**T1 — Short outage (regression guard, must already pass)**

| | |
|---|---|
| Purpose | Confirm the known-good path still works with instrumentation in place, and record its counter signature as the reference. |
| Procedure | 1. Connected and streaming. 2. Move the watch out of range. 3. Wait **2 minutes**. 4. Return it to range. 5. Wait 3 minutes. |
| Observe at | disconnect, +35 s (first fault), +95 s, on return, +3 min |
| Pass | `disconnect reason is CONNECTION_TIMEOUT` at step 2; `acc burst #N accepted` resumes within 60 s of step 4; `mgrCreated` ≤ 2; no `onScanFailed`. |
| Record | `mgrCreated`, `forceCleanup`, `autoConnect`, `connected` at end. |

---

**T2 — Medium outage (30 min)**

| | |
|---|---|
| Purpose | Find the point at which behaviour diverges from T1. |
| Procedure | As T1 but wait **30 minutes** out of range. |
| Observe at | disconnect, +5 min, +15 min, +30 min, on return, +3 min |
| Pass (baseline build) | May or may not recover — record which. |
| Key evidence | Does `mgrCreated` rise roughly linearly at ~1 per minute? Does `autoConnect` rise while `connected` stays flat (D2)? Any `ANOMALY - connection attempt pending for Ns`? |

---

**T3 — Long outage (2 h) — the primary test**

| | |
|---|---|
| Purpose | Reproduce the reported failure and measure the registration budget directly. **This is the test that decides D1.** |
| Procedure | As T1 but wait **2 hours** out of range. Do not touch the phone. |
| Observe at | disconnect, +5 min, +15 min, +30 min, +60 min, +90 min, +120 min, on return, +5 min |
| Primary evidence | `dumpsys bluetooth_manager` at **every** observation point. Plot the app's registered GATT client count against time. |
| **Fail (confirms D1)** | GATT-client count rises monotonically (~1 per restart cycle), `mgrCreated` rises with it, `D1 PATH TAKEN` appears repeatedly, and after return the app does **not** reconnect. `onScanFailed failure=APPLICATION_REGISTRATION_FAILED` or `OUT_OF_HARDWARE_RESOURCES` is the smoking gun. |
| **Refutes D1** | GATT-client count stays flat across the whole 2 h yet recovery still fails → D1 is not the mechanism; pivot to D2/D4/D5 using the same captures. |
| Then | Only after the final snapshot: force-stop and restart the app. If it reconnects immediately, that confirms per-process registration exhaustion. Record the result — it is diagnostic, not a fix. |

---

**T4 — Bluetooth off → on while connected**

| | |
|---|---|
| Purpose | D5. |
| Procedure | 1. Connected and streaming. 2. Turn phone Bluetooth **off**. 3. Wait 60 s. 4. Turn it **on**. 5. Wait 5 minutes. |
| Pass (baseline build) | Expected to **fail** — record it. |
| Key evidence | `onBluetoothAdapterStateChanged #N state=STATE_TURNING_OFF` then `STATE_ON`, followed by **no** `scan started` line and no recovery. blessed will log `cannot connect to peripheral because Bluetooth is off` if BLE2 tried to autoConnect in the window. |

---

**T5 — Watch rebooted while out of range**

| | |
|---|---|
| Purpose | Distinguish a phone-side stall from a watch-side one, and exercise firmware F1/F2. |
| Procedure | 1. Move the watch out of range. 2. Reboot the watch. 3. Wait 10 minutes. 4. Return to range. |
| Pass | Reconnect within 60 s of return. |
| Key evidence | If the phone's counters look healthy (`scanning(blessed)=true`, no `onScanFailed`) but no discovery occurs, the watch is not advertising — check firmware F2 (`StartAdvertising()` gated on `IsConnected()`). |

---

**T6 — Overnight outage (8 h)**

| | |
|---|---|
| Purpose | Firmware F1 (advertising degrades to ~1.02 s intervals after ~8.3 h) plus long-run phone behaviour. |
| Procedure | Leave the watch out of range overnight (~8 h). Snapshot `dumpsys` before sleeping and immediately on waking. Return the watch and wait 10 minutes. |
| Key evidence | Compare time-to-rediscovery against T3. A markedly longer time-to-reconnect at 8 h vs 2 h, with healthy phone-side counters, implicates F1. |

---

**T7 — Permissions revoked mid-run**

| | |
|---|---|
| Purpose | D8 / robustness of the `SecurityException` path in `bleConnect()`. |
| Procedure | 1. Connected. 2. Revoke Bluetooth permission in system settings. 3. Wait 2 minutes. 4. Re-grant. 5. Wait 5 minutes. |
| Pass | No permanent stall; `scheduleReconnection SUPPRESSED by shutdown flags` must **not** appear and stay. |

---

**T8 — Main-thread stall measurement (D6)**

| | |
|---|---|
| Purpose | Quantify D6 without waiting for a long outage. |
| Procedure | 1. Move the watch out of range. 2. Wait for the fault-driven restart (~90 s). 3. Capture logcat throughout. |
| Pass (baseline build) | Expected to show `stop() is running on the MAIN thread` followed by `BUSY-WAIT EXPIRED after 5500ms`. Record the elapsed value and how often it recurs. |
| Cross-check | `adb bugreport` → look for ANR traces or `Skipped NN frames` around those timestamps. |

---

**T9 — 24 h connected soak (regression guard)**

| | |
|---|---|
| Purpose | Prove the instrumentation itself is not harmful, and guard the `mServicesDiscovered` double-subscribe check. |
| Procedure | Leave connected and in range for 24 h with logcat captured to file. |
| Pass | `acc burst #N` increases steadily at ~12/min with no gaps; `mgrCreated` stays at 1; no `onCharacteristicUpdate DROPPED` bursts; no `ANOMALY` lines; syslog file size growth acceptable (≈1 compact heartbeat line per minute). |

---

**T10 — Post-fix re-run (Phase 1/2 acceptance)**

Re-run **T1, T3, T4, T8, T9** against the fixed build. Acceptance criteria:

| Test | Acceptance |
|---|---|
| T1 | Unchanged — still passes. |
| **T3** | **Reconnects within one supervisor interval of return, AND the `dumpsys` GATT-client count stays flat across the whole 2 h.** `mgrCreated` stays at 1. No `D1 PATH TAKEN`. |
| T4 | Recovers without an app restart; `scan started` appears after `STATE_ON`. |
| T8 | No `BUSY-WAIT EXPIRED`; `stop()` completes in < 1 s. |
| T9 | Unchanged — still passes. |

### 9.6 Results recording sheet

Copy this table per test run. One row per observation point.

| Test | Point (elapsed) | UI state | `mgrCreated` | `forceCleanup` | `scans` | `scanFailed` | `autoConnect` | `connected` | `accBursts` | dumpsys: GATT clients | dumpsys: active scans | dumpsys: receivers | Notes / anomalies |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| T3 | 0 min | OK | | | | | | | | | | | |
| T3 | 5 min | FAULT | | | | | | | | | | | |
| T3 | 15 min | | | | | | | | | | | | |
| T3 | 30 min | | | | | | | | | | | | |
| T3 | 60 min | | | | | | | | | | | | |
| T3 | 90 min | | | | | | | | | | | | |
| T3 | 120 min | | | | | | | | | | | | |
| T3 | return +5 min | | | | | | | | | | | | |
| T3 | after app restart | | | | | | | | | | | | |

Also record once per run: phone make/model/Android version, firmware version,
battery-optimisation setting, `SysLogLevel`, APK version/commit, and whether the
screen was on or off.

### 9.7 Interpretation matrix

| Observation | Conclusion |
|---|---|
| GATT-client count rises ~1 per restart cycle; `D1 PATH TAKEN` repeats; recovery only after process restart | **D1 confirmed** — proceed to Phase 1 |
| `autoConnect` rises while `connected`/`connFailed`/`disconnected` stay flat; `ANOMALY - connection attempt pending for Ns` | **D2 confirmed** — proceed to Phase 2 supervisor |
| `SUSPECT ZOMBIE CALLBACK` or `ANOMALY - blessed reports an active scan but ...` | **D3 confirmed** — ownership guard required |
| `onScanFailed failure=APPLICATION_REGISTRATION_FAILED` / `OUT_OF_HARDWARE_RESOURCES` / `SCANNING_TOO_FREQUENTLY` | **D4 confirmed**, and almost certainly a consequence of D1/D3 |
| `STATE_ON` with no subsequent `scan started` | **D5 confirmed** |
| `BUSY-WAIT EXPIRED after 5500ms` on the main thread | **D6 confirmed** |
| `backoff retry FIRING` never appears across a 2 h outage | **D7 confirmed** — backoff is dead code |
| `scheduleReconnection SUPPRESSED by shutdown flags` and never resumes | **D8 confirmed** |
| blessed logs `already issued autoconnect for` | blessed is short-circuiting the retry — supports D2 |
| blessed logs `peripheral with address '...' not in Bluetooth cache` | blessed diverted to `LOW_POWER` autoconnect-by-scan — supports D3, slows rediscovery |
| All phone-side counters healthy, scanning active, no discovery | Look at the **watch** — firmware F1/F2 |

### 9.8 Reporting

For each completed test, attach to the issue:

1. The filled 9.6 sheet.
2. `dumpsys_<test>_<point>.txt` for every observation point.
3. The `.diag` extract (grep of `BLE2DIAG`) plus the blessed-tag lines.
4. `bugreport` for any test that reached the stuck state or showed an ANR.
5. A one-line verdict per deficiency from the 9.7 matrix.

Do not attach raw `adb bugreport` zips to a public issue — they contain
location and account data. Extract only `bugreport.txt` sections
`BLUETOOTH MANAGER`, `ACTIVITY MANAGER`, and the app's own log.

---

## 10. Summary

| ID | Deficiency | Likelihood (pre-evidence) | Likelihood (post-evidence) | Severity | Status |
|---|---|---|---|---|---|
| D1 | Pending GATT clients orphaned by `forceCleanup()`; registrations accumulate | ~65 % | **~25 %** — see section 11 | Critical | **FIXED** (D1(2) + ordered teardown). D1(1) not adopted |
| D2 | No independent supervisor; stalled `autoConnect` is a permanent no-op deadlock | ~55 % | **~70 % — CONFIRMED in the field** | Critical | **FIXED** (30 s supervisor + direct connect) |
| D3 | Closed manager keeps scanning and drives the live manager via the shared callback | ~30 % | **~40 % — CONFIRMED in the field** | High | **FIXED** (`isCallbackStale()` guard + zombie sweep) |
| D4 | `onScanFailed` not overridden — dead scan is silent | ~30 % | ~20 % (never fired in 17 cycles) | High | **FIXED** (backoff retry on failure) |
| D5 | `onBluetoothAdapterStateChanged` not overridden — no recovery after BT toggle | ~20 % | ~20 % (untested — `adapterChanges=0`) | High | **FIXED** (re-scan 2 s after `STATE_ON`) |
| D6 | `stop()` busy-waits on the main thread; guaranteed 5.5 s stall per cycle | ~20 % | **100 % — CONFIRMED at exactly 5500 ms** | High | **FIXED** (busy-wait removed) |
| D7 | `scheduleReconnection()` unreachable; backoff loop is dead code | ~20 % | **CONFIRMED — never fired in 17 cycles** | Med-High | **FIXED** (idempotent, now the retry owner) |
| D8 | Shutdown-flag races suppress retries permanently | ~10 % | ~10 % | Medium | Observable only |
| D9 | `LE_CODED`/`S8` unsupported on nRF52832; `HIGH` priority accepted by firmware | ~5 % | ~5 % | Low | Open — deliberately untouched |
| D10 | Stale characteristic objects reused across connections | ~5 % | ~5 % | Low | **Partly fixed** (nulled on disconnect/teardown) |

**Original working conclusion (pre-evidence).** D1 + D2 acting together:
`forceCleanup()` → `BluetoothCentralManager.close()` does not release pending GATT
clients, so each fault-driven restart during an outage leaks a registration against
a hard per-app cap; simultaneously, a stalled `autoConnect` cannot be retried
because blessed short-circuits repeat calls and BLE2 has no independent supervisor.

**Revised working conclusion (post-evidence, section 11).** The two on-device
captures confirm D2, D3, D6 and D7 and **do not** support D1 as the primary cause:
across 17 fault-restart cycles every registration was balanced and `scanFailed`
stayed at 0, and a watch that returned after 1000 s reconnected in 5.6 s. D1 is now
understood as a *consequence* of D2 rather than an independent defect — it only bites
when a stalled connect overlaps a teardown, because that is the only situation in
which blessed holds a `BluetoothGatt` that BLE2 cannot reference. **D2 is therefore
the primary defect**, and the >1 h duration threshold in the original report is most
probably supplied by an environmental factor — Doze/OEM scan suspension, or firmware
F1's advertising degradation after ~8.3 h — rather than by registration exhaustion.

**Current state.** Phase 0 instrumentation (section 8), the Phase 1/2 fixes
(section 12) and the test plan (section 9) are all in `SdDataSourceBLE2.java`;
`:app:compileDebugJavaWithJavac` and `:app:testDebugUnitTest` both pass. The
remaining validation is an overnight out-of-range run analysed against the acceptance
criteria in section 12.4. D8, D9 and firmware F1–F7 remain open.

---

## 11. Field evidence — on-device captures, 2026-10-01

Two logcat captures were taken on a real phone with the Phase 0 build, using a
PineTime at `EB:27:55:B7:3E:57`. They are the first measurements rather than
inference, and they changed the ranking in section 10.

### 11.1 Capture A — watch Bluetooth switched off while connected

| Time | Event | Verdict |
|---|---|---|
| 19:13:55.751 | `onDisconnectedPeripheral #1 status=REMOTE_USER_TERMINATED_CONNECTION`, `state=CONNECTED` | Clean termination, `mBlePeripheral` still valid |
| 19:13:55.763 | `onDisconnectedPeripheral issuing autoConnectPeripheral #2` | blessed arms `connectGatt(autoConnect=true)` |
| 19:13:55.797 | `onConnectingPeripheral ... state=IDLE` | blessed is genuinely attempting |
| 19:13:55 → 19:15:07 | `connectPending=true`, `connectPendingAgeMs=71360`, `scanActive(ours)=false`, `scanning(blessed)=false`, **no callback of any kind** | **D2 CONFIRMED.** For 71 s the datasource did literally nothing: no scan, no timer, no retry |
| 19:15:07 → 19:15:12 | `BUSY-WAIT EXPIRED after 5500ms on thread=main` | **D6 CONFIRMED** at exactly the predicted duration |
| 19:15:12.713 → .782 | `closing manager generation=1` at .713, then `onDisconnectingPeripheral` at .728 and `onDisconnectedPeripheral #2` at .782, both `generation=-1 state=CLEANUP`, re-entering `forceCleanup #2` | **D3 CONFIRMED.** A closed manager drove the state machine after its own teardown |
| every ~64 s | `mgrCreated` 1→17, `forceCleanup` 1→17, `scans` 1→17, `start`/`stop` 1:1 | **Churn confirmed** — one new manager and one `close()` per minute (`SdServer.java:1615-1647`) |
| cycles 2–17 | `D1 PATH TAKEN` every cycle, but with `peripheral=null`, `connectPending=false`, `scanning(blessed)=false`; `scanFailed=0` throughout | **D1 NOT proven.** Nothing was pending to orphan: the scan had been stopped before `close()` and no connect was outstanding. In cycle 1 the pending GATT *was* released correctly (`cancelConnection()` → `status=SUCCESS`) |

**The `D1 PATH TAKEN` message was over-eager.** It fired whenever `mBlePeripheral`
was null, regardless of whether blessed was holding anything. That has been
corrected: the message now distinguishes "a connect attempt was PENDING and
unreferenceable" (a real leak) from "no pending attempt, nothing to orphan".

### 11.2 Capture B — watch switched back on after ~1000 s

| Time | Event | Reading |
|---|---|---|
| 19:31:13.231 | `scan started - generation=17` | Fresh manager, LOW_LATENCY address-filtered scan |
| 19:31:49.926 | `onDiscoveredPeripheral #2 rssi=-78 generation=17 mScanActive=true` | Found **36.7 s** into the scan; generation matches and `mScanActive=true`, so **D3 did not fire** |
| 19:31:49.947 | `issuing autoConnectPeripheral #3` | **`mBlePeripheral` is still null at this point** |
| 19:31:52.049 | `onConnectedPeripheral #2 connectAttemptDurationMs=2100` | Connected in **2.1 s** |
| 19:31:52.063 | `onServicesDiscovered - assigning mBlePeripheral (was null)` | Reference acquired **2.116 s after** the connect request |
| 19:31:55.531 | `acc burst #24` | Data flowing 3.5 s later — full recovery in **5.6 s** |

### 11.3 What the two captures together establish

1. **The scan-only recovery path works.** A watch that reappears is found within tens
   of seconds and connected in ~2 s. Any fix must not break this.
2. **The D1 window is measurable: 2.116 s per successful connection, unbounded when a
   connect stalls.** That is the precise coupling between D1 and D2 — D1 can only leak
   when a stalled attempt survives until the next teardown.
3. **`autoConnect` is not broken, it is unbounded.** It connected in 2.1 s because the
   device was present; it stalled for 71 s because the device was absent and blessed
   arms no timer. Since a discovery *proves* the device is present, a direct
   `connectPeripheral()` is strictly better: same ~2 s success, plus a guaranteed
   ≤35 s outcome on failure.
4. **Nothing leaked across 17 cycles.** Per cycle the books balance — `registerReceiver`
   ↔ `close()`'s `unregisterReceiver`, `startScan` ↔ `stopScan`, no pending GATT in
   cycles 2–16. D1's likelihood is revised down accordingly.
5. **`rssi=-78` is already marginal.** A watch at −85…−90 will still be *discovered* by
   a LOW_LATENCY scan but may fail to *connect* — which produces exactly the
   discovery-then-stall cycle that opens the D1 window indefinitely. A clean
   switch-on/off test never produces it, which is why both clean tests recovered.
6. **Still untested:** `adapterChanges=0` (D5), no `onScanFailed` ever (D4), no
   stalled-connect-after-discovery (the D1+D2 interlock), no screen-off/Doze period,
   no run past the firmware's 8.3 h advertising-degradation threshold (F1).

### 11.4 Finding F8 — the fault-restart discards the armed autoConnect

After an unexpected disconnect, blessed's `autoConnect` was correctly armed and would
have reconnected at controller level whenever the watch returned, at no battery cost.
`SdServer.java:1625` killed it ~71 s later and replaced it with scan-only mode.

Capture B shows this is **not** fatal — the scan recovers fine — so F8 is stated here
in its corrected form: *the restart discards the armed autoConnect, after which
recovery depends entirely on the scan staying healthy; the scan is therefore the
single point of failure, and it is the component most exposed to Doze, OEM battery
management and Android scan throttling.* This is what the Phase 2 supervisor now
polices independently of both blessed and `SdServer`.

---

## 12. Phase 1/2 implementation

All changes are in `SdDataSourceBLE2.java`. Build status: `:app:compileDebugJavaWithJavac`
and `:app:testDebugUnitTest` both pass.

### 12.1 What changed

| # | Change | Deficiency | Mechanism |
|---|---|---|---|
| 1 | Peripheral tracked from the moment a connect is requested | D1 | `mBlePeripheral` assigned in `onDiscoveredPeripheral()` *before* the connect call, and in `onConnectingPeripheral()` if still null; cleared only at teardown. A stalled attempt can now always be cancelled |
| 2 | Connection supervisor | D2 | Own `Handler`, 30 s tick, five rules (below). Independent of blessed and of `SdServer`, so a silent stall can no longer be permanent |
| 3 | Discovery leads to `connectPeripheral()`, not `autoConnectPeripheral()` | D2 | blessed arms `CONNECTION_TIMEOUT_IN_MS = 35000` for a direct connect and none for autoConnect, so every attempt now reports an outcome |
| 4 | Ordered, synchronous, idempotent teardown | D1, D3 | `stopScan()` → **manager-level** `cancelConnection()` → `close()` → delayed zombie sweep, in `releaseBleResources()` |
| 5 | Zombie-callback guard | D3 | `isCallbackStale()` at the top of every manager callback drops events from a manager that has already been closed and released |
| 6 | Post-teardown zombie sweep | D3 | 2 s after teardown, `dying.isScanning()` is checked and stopped — covers blessed's `$8$1` restart runnable that outlives `close()` |
| 7 | Busy-wait removed from `stop()` | D6 | Teardown completes synchronously, so there is nothing to wait for; no more 5.5 s main-thread block per cycle |
| 8 | `onScanFailed` recovery | D4 | Schedules a backoff retry (deliberately not immediate, since `SCANNING_TOO_FREQUENTLY` means an immediate retry would extend Android's ban) |
| 9 | `onBluetoothAdapterStateChanged` recovery | D5 | Re-scans 2 s after `STATE_ON`; clears scan/connect tracking on `STATE_OFF` |
| 10 | `scheduleReconnection()` idempotent and live | D7 | `mReconnectionScheduled` allows at most one outstanding retry; counter reset only on success; toasts limited to the first 3 attempts |
| 11 | Characteristics nulled on disconnect | D10 | `mOsdChar`/`mStatusChar`/`mHrChar`/`mBattChar` cleared in `onDisconnectedPeripheral()` and at teardown, so a stale GATT object is never written to |
| 12 | `mDisconnected` reset in `bleConnect()` | — | It was left `true` by the previous cycle's teardown, which made the old busy-wait return immediately and made every `STATE-DUMP` misleading |

**Deliberately not changed:** the per-cycle manager lifecycle (D1(1)), PHY negotiation
(D9), shutdown-flag ownership (D8), and anything in `SdServer`. The fault-restart
cadence is untouched, so BLE2 now has to be — and is — resilient to being restarted
every ~64 s.

### 12.2 Supervisor rules

Evaluated in order on each 30 s tick. Every rule re-arms the tick; the tick is also
re-armed by the 60 s diagnostic heartbeat if it is ever found unscheduled while
unhealthy, so the watchdog is self-healing.

| Rule | Condition | Action |
|---|---|---|
| 1 `NO-MANAGER` | running, but the manager is null or generation < 0 | `bleConnect()` — rebuild manager, scan, watchdogs |
| 2 `CONNECTED-BUT-SILENT` | `CONNECTED` for ≥60 s **and** no acceleration burst for ≥60 s | `cancelConnectionAndRescan()`. The dual condition is required: `sLastAccDataMillis` is process-lifetime, so it still holds the previous connection's timestamp during the first seconds of a new one |
| 3 `CONNECT-STALLED` | a connect attempt pending ≥45 s with no callback | `manager.cancelConnection()` + re-scan. 45 s is above blessed's 35 s timer, so this only fires if blessed's own timeout failed |
| 4 `SCAN-DEAD` / `SCAN-STALLED` | we believe a scan is running but blessed reports none, **or** the scan has found nothing for ≥120 s | Restart the scan. One restart per 120 s is far below Android's 5-starts-per-30 s throttle |
| 5 `IDLE-NOTHING-PENDING` | no scan, no pending connect, no scheduled retry | Start a scan. This is the state the old code could sit in permanently. Skipped when a backoff retry is already outstanding, so the backoff is not preempted |

### 12.3 New counters and log signatures

Added to every `STATE-DUMP` and `countersToString()` line:

| Counter | Meaning | Healthy value over a long run |
|---|---|---|
| `directConnect` | `connectPeripheral()` requests issued | rises with each discovery |
| `autoConnect` | retained for continuity — **now always 0**, which is the proof that no unbounded attempt is ever issued | 0 |
| `supTicks` | supervisor ticks executed | ~2/min |
| `supInterventions` | corrective actions taken | 0, or a few per outage |
| `connectStalls` | attempts the supervisor had to break | **0** — anything else means a connect stalled |
| `scanRestarts` | scans restarted by the supervisor | low |
| `zombieDrops` | callbacks dropped from a closed manager | **0** — anything else means D3 is still occurring |
| `zombieScans` | scans found running on a closed manager and stopped by the sweep | **0** |

`STATE-DUMP` also gained `scanAgeMs`, `retryScheduled` and `supervisorScheduled`.

Grep for these to check the fixes are working:

```
SUPERVISOR[            # every intervention, with its rule name
CONNECT-STALLED        # a connect attempt had to be broken (should not appear)
ZOMBIE CALLBACK DROPPED# D3 still occurring
ZOMBIE SCAN FOUND      # blessed restarted a scan on a closed manager
D1 PATH TAKEN          # now only fires when an attempt was genuinely pending
D1 window CLOSED       # peripheral tracked from discovery - should appear on every discovery
```

### 12.4 Acceptance criteria for the overnight validation run

Capture with all three tags, since blessed's own diagnostics are where
`already issued autoconnect`, `scanning timeout, restarting scan` and
`peripheral with address '...' not in Bluetooth cache` appear:

```
adb logcat -v time -s SdDataSourceBLE2 BluetoothCentralManager BluetoothPeripheral
```

| Criterion | Pass condition |
|---|---|
| Recovery | Watch reconnects and `acc burst` resumes without any app restart, at any point in the night |
| No silent stall | `connectStalls=0`, or if non-zero, each is immediately followed by a re-scan and eventually `onConnectedPeripheral` |
| No zombie activity | `zombieDrops=0` and `zombieScans=0` |
| No main-thread block | no `BUSY-WAIT EXPIRED` line at all; every `stop() #N complete in Xms` shows a small X |
| No leak | `mgrCreated` still rises ~1/min (expected — D1(1) not adopted), but `scanFailed=0` and GATT-client registrations for `uk.org.openseizuredetector` in `dumpsys bluetooth_manager` stay flat |
| Supervisor alive | `supTicks` rises steadily all night (~2/min); `supervisorScheduled=true` in every unhealthy dump |
| Firmware F1 | if discovery latency grows dramatically after ~8.3 h while `scanning(blessed)=true`, that is F1, not a phone-side defect |

Capture `adb shell dumpsys bluetooth_manager` before the run, mid-run, and after —
that is still the decisive measurement for D1, and it is now the only hypothesis the
phone-side counters cannot settle on their own.


