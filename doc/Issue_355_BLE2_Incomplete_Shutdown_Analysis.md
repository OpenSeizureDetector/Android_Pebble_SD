# Issue #355 — V5.1.3 BLE2 DataSource Incomplete Shutdown — Analysis

**Issue:** https://github.com/OpenSeizureDetector/Android_Pebble_SD/issues/355
**Version analysed:** V5.1.3 (`5693dbc Build V5.1.3 for Play Store`)
**Status:** Fixes F1–F5 IMPLEMENTED on branch `355-ble2-datasource-incomplete-shutdown` — see section 9. Awaiting log confirmation (section 7) and on-device validation.
**Components:** `SdServer.java`, `SdDataSourceBLE2.java`, `SdDataSource.java`, `OsdUtil.java`,
`MainActivity2.java`, `StartupActivity.java`
**Related document:** `doc/BLE2_Reconnection_Analysis.md` (out-of-range reconnection while
*running* — a different failure mode; this issue is about resurrection *after shutdown*.
The BLE2DIAG instrumentation added for that work is what makes this issue confirmable
from existing logs.)

---

## 1. Problem statement (from the issue report)

1. System running normally.
2. Watch taken out of range of the phone.
3. When the phone started to give off fault 'pips', the app was shut down using **menu → Exit**.
4. Phone left for several hours.
5. Watch brought back in range; phone app started.
6. **App got stuck in the start-up screen** (i.e. it tried to connect to the data source but
   never received data and/or settings), **but the watch appeared to be connected and showed
   'FAULT' on its screen**.
7. App shut down again.
8. Watch Bluetooth switched off and on → **'Watch Connected' toast appeared on the phone**
   even though the app had no notification showing and was not in the foreground — indicating
   a live `SdDataSourceBLE2` was still running somewhere.
9. **Force-stop** of the phone app, then restart → everything worked correctly.

---

## 2. What the observations tell us

| Observation | Inference |
|---|---|
| 'Watch Connected' toast seen hours after Exit, with no service notification | The toast can only come from `SdDataSourceBLE2.onConnectedPeripheral()` (`SdDataSourceBLE2.java:930`). That callback is only delivered if a **live** `BluetoothCentralManager` exists — `isCallbackStale()` (`:1816`) drops every callback once teardown has nulled the manager. Therefore a **started, scanning BLE2 instance** existed in a live process with **no SdServer service and no notification**. |
| Toasts work, notifications and pips do not | Consistent with a *destroyed* `SdServer` object still being referenced: `showNotification()` returns early on `mIsDestroying` (`SdServer.java:1082-1088`) and `startFaultBeepLoop()` is gated the same way (`:2772`), but `mUtil.showToast()` in BLE2 has no such gate. |
| Watch showed **Connected + FAULT** | Something was connected to the watch but was writing (or leaving) the FAULT alarm state — see section 4.4. |
| Only a **force-stop** fixed it | The surviving state was inside the app *process* (a zombie object plus its Bluetooth stack registrations). Killing the process released everything. |
| Stuck start-up screen = no data/settings received | `StartupActivity` only proceeds to `MainActivity2` when `hasSdData()` (`mSdData.haveData`) and `hasSdSettings()` (`mSdData.haveSettings`) are true (`StartupActivity.java:698-737`, `SdServiceConnection.java:74-97`). The new data source never received either, because the watch's single BLE link was already held by the zombie — PineTime stops advertising while connected, so the new instance's address-filtered scan could never find it. (Note the "Watch Connected" line on the start-up screen can even show OK, because `SdDataSource.getStatus()` sets `mSdData.watchConnected = true` unconditionally — `SdDataSource.java:407`.) |

**Conclusion:** this is not a teardown that failed to release resources — the BLE2 teardown
(`bleDisconnect()` → `releaseBleResources()`, `SdDataSourceBLE2.java:1385-1641`) is
synchronous and complete (stopScan → manager-level cancelConnection → close → zombie sweep).
The evidence points instead to the data source being **re-started after the service was
destroyed** — a zombie datasource resurrected by a delayed callback that outlived
`SdServer.onDestroy()`.

---

## 3. The code paths involved

### 3.1 Exit path

`MainActivity2.onOptionsItemSelected(action_exit)` (`MainActivity2.java:402-406`)
→ `OsdUtil.shutdownApp(caller, restart=false, setStoppedFlag=true, …)` (`OsdUtil.java:777-831`):

- sets `user_stopped_service = true` (prevents auto-restart),
- at **+200 ms**: `stopServer()` → `SdServer.setPlannedShutdown()` + `stopService()`,
- at **+1700 ms**: `caller.finish()` → activity `onStop()` → `unbindFromServer()` →
  (service was started *and* bound, so `onDestroy()` only runs after this unbind).

So `SdServer.onDestroy()` typically runs **~2 s after the Exit click**.

### 3.2 Fault → datasource restart cycle (the resurrection vector)

While the watch is out of range, `SdDataSource.getStatus()` raises `onSdDataFault()` roughly
every 5 s (`SdDataSource.java:403-414`). In `SdServer.onSdDataFault()` (`SdServer.java:1592-1675`):

- `doFaultPipCheck()` starts a 30 s `FaultTimer`; when it completes,
  `mFaultTimerCompleted = true` and **the fault pips begin** (`FaultTimer.onFinish()` →
  `startFaultBeepLoop()`, `:2806-2816`).
- On the *next* fault callback, because `mFaultTimerCompleted` is now true, a datasource
  restart is scheduled (`:1626-1664`) — gated only by `!mDataSourceRestartInProgress` and
  `timeSinceLastRestart > 60000`:

```java
mHandler.postDelayed(() -> {                       // +1 s
    if (mSdDataSource != null) mSdDataSource.stop();
    mHandler.postDelayed(() -> {                   // +3 s more
        if (mSdDataSource != null) mSdDataSource.start();   // <-- resurrection vector
    }, 3000);
}, 1000);
```

`mHandler` is the **main looper** handler (`SdServer.java:344`). These are **anonymous
runnables**, posted with no token, and:

- they **do not check `mIsDestroying`**, and
- `SdServer.onDestroy()` (`:724-919`) **never removes them** — it removes only
  `mFaultBeepLoop` and `mAlarmToneVolumeRestore` individually, cancels timers/threads,
  stops the datasource (on a worker thread with a 6 s join, `:765-795`), cancels the
  notification and calls `stopSelf()`. There is **no `mHandler.removeCallbacksAndMessages(null)`**,
  and **`mSdDataSource` is not nulled** after being stopped.

### 3.3 The race

The fault pips and the restart schedule are armed by the *same* event
(`mFaultTimerCompleted`). The restart cycle then repeats every ~60 s while the fault
persists, with a **4-second-wide window** (stop@+1 s, start@+4 s) each cycle.

The user did exactly what the report says: **pressed Exit when the pips started** — i.e. at
the moment the first restart cycle was being scheduled, and with further cycles every ~60 s
after that. If Exit lands so that the queued `start()` fires *after* `onDestroy()` has
completed (~2 s after the click), the result is:

```
t0        fault pip starts; restart scheduled (stop@t0+1s, start@t0+4s)
t0+~2s    Exit clicked
t0+~4s    onDestroy(): datasource stopped, notification cancelled, stopSelf()
t0+4s     queued runnable fires: mSdDataSource.start()   <-- ZOMBIE CREATED
```

Rough probability per Exit: the dangerous overlap is a few seconds within each ~60 s cycle
(≈5–10 % at random timing, considerably higher if the user exits within a few seconds of pip
onset — which is precisely the reported behaviour). This matches "I don't know how easy this
is to repeat".

An aggravating detail: `onDestroy()` stops the datasource on a **worker thread** while the
pending `start()` runs on the **main thread**, so `stop()` and `start()` can even interleave;
`bleConnect()` clears `mShutdown`/`mIsShuttingDown` (`SdDataSourceBLE2.java:494-495`), so a
start that lands after a completed stop fully "un-does" the teardown.

---

## 4. Root-cause hypothesis (H1, primary): fault-restart runnable resurrects the datasource after `onDestroy()`

### 4.1 Mechanism

After `onDestroy()`, the queued `mSdDataSource.start()` runs on a destroyed-but-referenced
`SdServer`'s datasource:

- `SdDataSource.start()` sets `mRunning = true` and restarts the status/fault/settings timers
  (`SdDataSource.java:119-167`);
- `SdDataSourceBLE2.start()` → `bleConnect()` resets `mShutdown = false`,
  `mIsShuttingDown = false`, creates a **new `BluetoothCentralManager`**, starts the 60 s
  diagnostic heartbeat and the 30 s connection supervisor, and starts the address-filtered
  scan (`SdDataSourceBLE2.java:460-545`).

Nothing will ever stop this instance: the service is gone (no `onDestroy` will run again),
`user_stopped_service` prevents auto-restart of a *service* (not of this object), and the
process stays alive (BLE scan callbacks and handler traffic keep it warm). The zombie scans
indefinitely — for the "several hours" of the report.

### 4.2 Why the new app instance got stuck in the start-up screen

When the app was restarted, `StartupActivity` started a fresh `SdServer` + BLE2 instance,
which scanned for the watch. But when the watch came back in range the **zombie won the
race** (it had been scanning continuously; also, once either instance connects, PineTime
stops advertising so the loser can never discover it). The new instance therefore never
received acceleration data or settings → `haveData`/`haveSettings` stayed false →
`serverStatusRunnable` never reached the "start MainActivity2" branch → **stuck on the
start-up screen** (matching the clarification that "stuck" means no data and/or settings).

### 4.3 Why the watch showed Connected + FAULT

The zombie **does** complete the connection and subscribe:

- `onConnectedPeripheral()` → toast 'Watch Connected' (`:930`) — the toast the user saw when
  toggling the watch's Bluetooth (the watch re-advertised, the zombie re-found and
  re-connected it).
- `onServicesDiscovered()` is gated only by `mIsShuttingDown || mShutdown || !isRunning()`
  (`:1048`) — all fine in the zombie — so notifications are subscribed and 5 s acceleration
  bursts flow.
- Each burst runs `doAnalysis()` → `mSdDataReceiver.onSdDataReceived()` on the **destroyed**
  `SdServer`: `mSeizureDetector` was closed and nulled in `onDestroy()` (`SdServer.java:798-802`),
  so the data is ignored — *"received data after shutting down seizuredetector - ignoring"*
  (`:1284-1292`) — and **`alarmState` is never recomputed**. It remains `AlarmState.FAULT`,
  set by the last pre-destroy `onSdDataFault()` (`:1611`).
- BLE2 then writes that stale state to the watch every burst:
  `peripheral.writeCharacteristic(mStatusChar, alarmState…)` (`SdDataSourceBLE2.java:1301-1305`).
  The watch therefore displays **Connected + FAULT** — exactly as reported.

### 4.4 Why there was no notification, no pips, but a toast

- `showNotification()` returns immediately on `mIsDestroying` (`SdServer.java:1082-1088`), and
  the foreground notification was cancelled in `onDestroy()` (`:879-883`) → nothing in the shade.
- `startFaultBeepLoop()` is likewise gated (`:2772`) → the zombie is silent.
- `OsdUtil.showToast()` is not gated at all → the toast is visible even from a background,
  service-less process. This asymmetry is exactly what the user observed.

### 4.5 Why the second Exit didn't help, but force-stop did

The second Exit only destroyed the *new* service instance (and its datasource); the zombie
is reachable only from the dead `SdServer`'s handler queue and has no service lifecycle.
(It may even have created a *second* zombie by the same mechanism, since the stuck instance
was itself in a fault/restart cycle.) **Force-stop kills the process**, releasing the zombie
and all its Bluetooth registrations, so the next start is clean.

---

## 5. Alternative hypotheses considered (less likely)

| # | Hypothesis | Assessment |
|---|---|---|
| H2 | `stopService()` while activities are still bound → `onDestroy()` deferred or never called, service survives | Would leave the foreground notification visible (and it is re-posted every 15 s, `NOTIFICATION_REPOST_INTERVAL_MS`), contradicting "no notification showing". The shutdownApp sequence (stopServer@+200 ms, finish@+1700 ms → unbind → destroy) normally completes. Possible contributor only if an unbind is missed; the log check in section 7 will show whether `onDestroy()` ran. |
| H3 | BLE2's adapter-ON recovery lambda (`SdDataSourceBLE2.java:884-892`) is posted to `mSupervisorHandler` but `stopSupervisor()` only removes `mSupervisorTick` (`:616-626`) — the lambda is not removed by teardown | Real gap, but the lambda re-checks `!mIsShuttingDown && !mShutdown && isRunning()` when it fires, so after a clean `stop()` it is inert. Only dangerous in combination with a flag-clearing resurrection (i.e. H1). Worth closing anyway (F6 below). Note the report's BT toggle was on the *watch*, not the phone, so no phone adapter-state event was involved. |
| H4 | START_STICKY service resurrection after Exit | `stopServer()` writes the planned-shutdown flag *before* `stopService()` (`OsdUtil.java:316-324`), `user_stopped_service` blocks auto-start, and a resurrected service would show a notification. Does not fit. |
| H5 | blessed "zombie scan" on a closed manager (D3 of `BLE2_Reconnection_Analysis.md`) restarting itself after teardown | Cannot explain the toast: after teardown `isCallbackStale()` drops every callback from the closed manager (manager == null), so no `connectPeripheral()` and no toast could result; the +2 s zombie sweep also stops such scans. |
| H6 | BLE2 teardown itself incomplete (scan/connection left registered at Exit) | `releaseBleResources()` performs the full ordered release and is synchronous; field evidence for the reconnection work showed create/close pairs are balanced. A merely-leaked *scan* could not produce a 'Watch Connected' toast or hold a GATT connection showing FAULT on the watch. |

H1 is the only hypothesis that explains **all** of: toast without service/notification,
watch Connected+FAULT, stuck start-up (no data/settings), second Exit ineffective,
force-stop effective, and the difficulty of reproducing.

---

## 6. Recommended fixes

### F1 — Guard the fault-restart runnables against a destroyed service (essential, minimal)

In `SdServer.onSdDataFault()` (`:1637-1664`), check `mIsDestroying` inside **both** runnables:

```java
mHandler.postDelayed(() -> {
    if (mIsDestroying) { Log.w(TAG, "FAULT: restart cancelled - service destroyed"); 
                         mDataSourceRestartInProgress = false; return; }
    ...stop...
    mHandler.postDelayed(() -> {
        if (mIsDestroying) { Log.w(TAG, "FAULT: re-start cancelled - service destroyed");
                             mDataSourceRestartInProgress = false; return; }
        ...start...
    }, 3000);
}, 1000);
```

### F2 — Drain the main handler in `onDestroy()` (essential)

Early in `SdServer.onDestroy()` (after `mIsDestroying = true`):

```java
if (mHandler != null) mHandler.removeCallbacksAndMessages(null);
```

Note `mHandler` is passed into the datasource and `OsdUtil` constructors, so this also
clears anything they had pending — which is the desired behaviour at destroy time.
Alternatively (more surgical), hold the two restart runnables as named fields
(`mDatasourceRestartStopRunnable` / `mDatasourceRestartStartRunnable`) and
`removeCallbacks()` them explicitly in `onDestroy()` and in `stopFaultTimer()`.

### F3 — Null `mSdDataSource` in `onDestroy()` after stopping it

Both restart runnables already null-check `mSdDataSource`; nulling it after the stop thread
joins (`:765-795`) makes any surviving callback a no-op. (`onStartCommand()` already follows
this pattern, `:522-532`.)

### F4 — Defence-in-depth: make resurrection self-announcing

In `SdDataSource.start()` (or `SdDataSourceBLE2.start()`), log the calling stack when a
start occurs while the receiver is a destroyed `SdServer`, e.g. expose
`SdServer.isDestroying()` and log an error (plus `writeExceptionLog`) if it is true. This
turns any future resurrection path — known or unknown — into an explicit syslog signature
instead of a silent zombie. Optionally refuse to start in that case.

### F5 — Close the handler-gap in BLE2 teardown (H3)

In `bleDisconnect()`, replace the targeted supervisor removal with a full drain of the
supervisor handler (`mSupervisorHandler.removeCallbacksAndMessages(null)`), so the 2 s
adapter-ON recovery lambda can never outlive teardown.

### F6 — Consider serialising start/stop

`onDestroy()` stops the datasource on a worker thread while main-looper callbacks can call
`start()` concurrently. With F1–F3 the known race is closed, but making `start()`/`stop()`
in `SdDataSourceBLE2` mutually `synchronized` (or always posting both to the main looper)
would remove the interleaving class entirely.

---

## 7. Is further diagnosis required to confirm?

**Yes — but confirmation should be possible from logs that already exist on the affected
phone; no new build is needed** (V5.1.3 already contains the BLE2DIAG instrumentation, the
memory log and the shutdown-reason diagnostics).

### 7.1 Check the incident syslog (decisive)

Pull the daily log files covering the incident:

```
adb pull /sdcard/Android/data/uk.org.openseizuredetector/files/logs/ logs_355/
```

H1 is **confirmed** if, in the Exit timeframe, the following appear **in this order**:

1. `FAULT: Timer completed - starting fault beep loop` (pips begin)
2. `FAULT: Attempting to restart data source to recover from fault`
3. `SdServer.onDestroy()` … `onDestroy(): datasource stopped successfully`
4. **after** step 3: `FAULT: Restarting data source` ← *smoking gun*
5. **after** step 3: `BLE2DIAG: start() #N` / memory-log `SdDataSourceBLE2.start`
6. for the following hours: `BLE2DIAG: heartbeat-NOT-HEALTHY` + `STATE-DUMP` once per minute
   (zombie scanning while the watch is out of range), and **no** `closing manager` line
7. when the watch returned: `BLE2DIAG: onConnectedPeripheral #N` and then, every ~5 s,
   `onSdDataReceived() - received data after shutting down seizuredetector - ignoring`
   ← *confirms the zombie was connected and receiving watch data after destroy*

Quick filter:

```
grep -nE "onDestroy|FAULT: (Attempting|Stopping|Restarting)|BLE2DIAG: (start|heartbeat|onConnectedPeripheral)|shutting down seizuredetector" logs_355/*.txt
```

If step 4/5 do **not** appear after `onDestroy`, H1 is refuted and the alternatives in
section 5 need to be pursued — starting with H2 (check whether `SdServer.onDestroy()` ran at
all after the Exit, and `dumpsys activity services` evidence of a surviving service).

### 7.2 If the fault state recurs live (before touching anything)

With the app exited (no notification) and the watch back in range:

```
adb shell dumpsys bluetooth_manager > dumpsys_355.txt      # app holds registered scans/GATT clients?
adb shell dumpsys activity services uk.org.openseizuredetector > services_355.txt   # SdServer NOT running?
adb logcat -v time | grep BLE2DIAG                          # live zombie activity
```

"BLE registrations + BLE2DIAG traffic **without** a running SdServer" is the definitive
zombie-datasource signature.

### 7.3 Reproduction recipe (to validate the fix as well as the cause)

1. Watch out of range; wait for the first fault pip (~35 s data timeout + 30 s fault timer).
2. Press **menu → Exit within ~1–5 s of pip onset** (this is when the first 1 s/3 s restart
   window is open; if it does not reproduce, retry, varying the delay 0–60 s to hit later
   cycles).
3. Watch logcat for `FAULT: Restarting data source` **after** `SdServer.onDestroy()`.
4. Bring the watch in range → expect a 'Watch Connected' toast with the app closed, and the
   watch showing Connected + FAULT.
5. After applying F1–F3, the same procedure must instead show
   `FAULT: re-start cancelled - service destroyed` and **no** BLE2DIAG activity after
   `onDestroy()`.

---

## 8. Summary

The most probable cause of issue #355 is **not** an incomplete BLE2 teardown, but a
**post-shutdown resurrection**: the anonymous fault-restart runnables posted by
`SdServer.onSdDataFault()` (stop@+1 s, start@+3 s on the main-looper `mHandler`) are neither
cancelled by `SdServer.onDestroy()` nor gated by `mIsDestroying`, so an Exit pressed while
fault pips are sounding — exactly when those runnables are queued — can leave a queued
`mSdDataSource.start()` that fires after the service is destroyed. The resulting zombie
`SdDataSourceBLE2` scans indefinitely with no service or notification, wins the race for the
watch when it returns to range, holds the single BLE link (so the restarted app never
receives data/settings and sticks on the start-up screen), keeps the watch displaying a
stale FAULT state, and can only be killed by a force-stop. The fix is small and low-risk
(F1–F3: `mIsDestroying` guards, handler drain in `onDestroy()`, null the datasource
reference), and confirmation should be achievable directly from the existing on-device logs
using the signatures in section 7.1.

---

## 9. Implementation status

Implemented on branch `355-ble2-datasource-incomplete-shutdown` (based on `5693dbc`, V5.1.3):

| Fix | Status | Where |
|---|---|---|
| F1 — `mIsDestroying` guards in both fault-restart runnables | ✅ Implemented | `SdServer.onSdDataFault()` — outer runnable cancels and clears `mDataSourceRestartInProgress`; inner runnable cancels inside its try, flag still cleared in `finally` |
| F2 — drain the main handler in `onDestroy()` | ✅ Implemented | `SdServer.onDestroy()` — `mHandler.removeCallbacksAndMessages(null)` immediately after `mIsDestroying = true` |
| F3 — null `mSdDataSource` in `onDestroy()` | ✅ Implemented | after the stop-thread join; the stop thread now captures the datasource in a final local so the nulling is race-free; `acceptAlarm()` null-guards the field |
| F4 — resurrection is self-announcing (and refused) | ✅ Implemented | `SdDataReceiver.isDestroying()` default method; `SdServer.isDestroying()` override (`mIsDestroying` now `volatile`); `SdDataSourceBLE2.start()` refuses to start while the receiver is destroying and logs the caller's stack via `writeExceptionLog` (`start-refused-zombie-guard-#355`) |
| F5 — BLE2 supervisor-handler drain | ✅ Implemented | `stopSupervisor()` now calls `removeCallbacksAndMessages(null)`, so the 2 s adapter-ON recovery lambda can no longer outlive teardown |
| F6 — serialise `start()`/`stop()` | ❌ Not adopted | With F1–F5 every known resurrection path is closed; making the lifecycle methods mutually exclusive changes threading behaviour for all fault-restart cycles and was judged an uncontrolled risk for this fix. Revisit if field evidence ever shows an interleaved start/stop. |

New syslog signatures introduced by the fix (additions to section 7.1):

| Signature | Meaning |
|---|---|
| `onDestroy(): removed all pending main-handler callbacks (Issue #355)` | F2 ran — the handler queue was drained at destroy |
| `FAULT: datasource restart CANCELLED - service is being destroyed (Issue #355)` | F1 outer guard fired — a queued stop/restart was abandoned |
| `FAULT: datasource re-start CANCELLED - service is being destroyed (Issue #355)` | F1 inner guard fired — **the #355 resurrection was prevented** |
| `start() REFUSED - receiver (SdServer) is being destroyed` + `start-refused-zombie-guard-#355` exception log | F4 guard fired — an unknown path tried to resurrect the datasource; the logged stack identifies the caller |

Validation: after installing the fixed build, run the section 7.3 reproduction recipe. The
pass criterion is that Exit during fault pips produces either `restart CANCELLED` line (or a
clean stop with no post-destroy activity), **no** `BLE2DIAG:` output after
`SdServer.onDestroy()`, and no 'Watch Connected' toast when the watch returns to range with
the app exited.
