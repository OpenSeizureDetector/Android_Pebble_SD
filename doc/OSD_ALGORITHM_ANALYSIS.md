# OSD Algorithm Sensitivity Analysis: Master vs Beta

## Summary

This document reports the differences found between the original OSD algorithm
implementation in the **master** branch (`SdDataSource.doAnalysis()` /
`SdDataSource.alarmCheck()`) and the refactored implementation in the **beta**
branch (`alg/SeizureDetector.java`, `alg/SdAlgOsd.java`, `alg/SdAlgFlap.java`).

**Key finding:** The core FFT-based seizure detection maths (specPower, roiPower,
roiRatio calculations) and the OSD alarm state machine (OK → WARNING → ALARM
accumulation) are **mathematically and logically identical** between the two
implementations. No difference in the number of consecutive alarm detections
needed to reach WARNING or ALARM was found for the OSD algorithm itself.

The most likely cause of any perceived higher sensitivity in beta is a combination
of **different code-level defaults for the Flap alarm thresholds** and a
**structural difference in how HR alarms are handled** — both explained below.

---

## Difference 1: Flap Alarm Threshold Code Defaults (most significant)

**Master** reads all alarm thresholds from SharedPreferences, using
`"SET_FROM_XML"` as the code fallback (which causes an exception if the XML
preference has not been loaded by the time the code runs, so in practice it
relies entirely on the XML default values).

| Parameter | Master — XML default | Beta `SdAlgFlap.java` — code default |
|---|---|---|
| `FlapAlarmThresh` | **5000** | **10** |
| `FlapAlarmRatioThresh` | **90** | **55** |
| `FlapAlarmFreqMin` | **2.0** Hz | **2.5** Hz |

The Flap alarm in `SdAlgFlap.processSdData()` compares `roiPower` (already
divided by `ACCEL_SCALE_FACTOR = 1000`) against `mFlapThresh`. With the beta's
code default of **10**, the Flap alarm would fire whenever
`raw_roiPower / 1000 > 10`, i.e., `raw_roiPower > 10,000`. With the XML default
of **5000**, the threshold is `raw_roiPower > 5,000,000` — **500 times higher**.

Similarly, the ratio threshold is **55** (beta code) vs **90** (master XML),
making the ratio check more easily satisfied in beta if XML preferences are not
loaded.

### Why this matters

When the Android app is first installed or if `PreferenceManager.setDefaultValues()`
has not been called before `SdAlgFlap` is instantiated, the beta's Flap algorithm
would use these very permissive code defaults and fire far more frequently than
the master would. This would show up as apparent over-sensitivity even though the
OSD algorithm itself is unchanged.

### Proposed fix

Change the code defaults in `SdAlgFlap.updatePrefs()` to match the XML defaults:
```java
String threshStr = mSP.getString("FlapAlarmThresh", "5000");   // was "10"
mFlapThresh = Short.parseShort(threshStr);
String ratioStr = mSP.getString("FlapAlarmRatioThresh", "90"); // was "55"
mFlapRatioThresh = Short.parseShort(ratioStr);
mFlapFreqMin = readDoublePref("FlapAlarmFreqMin", "2.0");      // was "2.5"
```

---

## Difference 2: HR Alarm Bypasses the Timer in Master

**Master (`SdDataSource.hrCheck()`, line 820):**
```java
if (mSdData.mHRAlarmStanding | mSdData.mAdaptiveHrAlarmStanding | mSdData.mAverageHrAlarmStanding) {
    mSdData.alarmState = 2;   // immediately ALARM — no WarnTime/AlarmTime delay
}
```
A single HR threshold crossing immediately sets the alarm state to 2 (ALARM),
completely bypassing the `mAlarmCount` / `mWarnTime` / `mAlarmTime` timer that
OSD and Flap detections must satisfy.

**Beta (`SeizureDetector.processData()`):**
The HR algorithm result is added to the combined `algorithmResults` list exactly
like OSD and Flap results. It then passes through the same `mAlarmTime` timer
state machine, so an HR alarm also requires `WarnTime` seconds before WARNING and
`AlarmTime` seconds before ALARM.

### Consequence

In master, any momentary HR threshold crossing causes an **immediate full ALARM**
regardless of how long it has been detected. In beta, an HR alarm requires the
same time accumulation as any other algorithm.

This makes the **master more sensitive for HR alarms**, not the beta. However, it
also means that in master an HR alarm can leave `alarmState = 2` in `mSdData`,
and because `alarmCheck()` does not explicitly reset `alarmState` to 0 when
`mAlarmCount <= mWarnTime`, a subsequent single OSD/Flap detection while the
counter is still low will leave the state incorrectly elevated at 2 (from the
prior HR alarm) rather than resetting it to 0. This cross-contamination between
HR and OSD is absent in the beta.

### Proposed fix

Either route HR alarms through the same timer state machine in master (matching
beta), or ensure that `alarmCheck()` in master explicitly resets `alarmState = 0`
in the `inAlarm == true, mAlarmCount <= mWarnTime` path, to prevent contamination
from prior HR alarms.

---

## Difference 3: `mSamplePeriod` — Hard-coded vs Configurable

**Master:** `mSamplePeriod` is read from the SharedPreferences key `"SamplePeriod"`
(XML default: **5** seconds). It is also overridden at runtime from the watch's
JSON data (`analysisPeriod`). For example, the JSON from an Android Wear watch
shows `"analysisPeriod": 10`, which would set `mSamplePeriod = 10`.

**Beta (`SeizureDetector.java`):**
```java
mSamplePeriod = 5;   // hard-coded, never read from preferences or sdData
```

### Consequence

With `mSamplePeriod = 10` (as reported by some watches) in master and the alarm
thresholds of `WarnTime = 5` s, `AlarmTime = 10` s:

Assuming the physical watch data arrives every 10 s (as reported by some watches):

| | Cycles to WARNING | Wall-clock time (data every 10 s) | Cycles to ALARM | Wall-clock time (data every 10 s) |
|---|---|---|---|---|
| **Master** (`period = 10 s` from watch) | 1 | 10 s | 2 | 20 s |
| **Beta** (`period = 5 s`, hard-coded) | 2 | 20 s | 3 | 30 s |

Because beta treats every data packet as representing only 5 s regardless of the
actual watch interval, it under-counts elapsed alarm time when data arrives less
frequently than every 5 s, causing it to take longer (in wall-clock time) to
escalate to WARNING or ALARM.

In this scenario the **master is more sensitive** (reaches ALARM faster) because
it correctly accounts for the actual sample period from the watch, while the beta
under-counts elapsed alarm time. If the watch always delivers data every 5 s
(Pebble default), both implementations behave identically.

### Proposed fix

Read `mSamplePeriod` from `sdData.analysisPeriod` (or from the preference) in
`SeizureDetector.updatePrefs()`, rather than hard-coding it to 5.

---

## Difference 4: `AlarmTime` Code Default

**Master:** `SP.getString("AlarmTime", "SET_FROM_XML")` — relies on the XML
default of **10 seconds**.

**Beta (`SeizureDetector.updatePrefs()`):**
```java
String alarmTimeStr = mSP.getString("AlarmTime", "15");  // code default is 15, not 10
```

### Consequence

If XML preferences have not been loaded, beta requires `mAlarmTime > 15` before
reaching ALARM, while master uses `> 10`. With `SamplePeriod = 5` s:

- **Beta** (AlarmTime code default = 15): ALARM after `mAlarmTime > 15`, i.e.,
  after mAlarmTime = 20 → **4 consecutive 5-second alarm detections (20 s total)**
- **Master** (AlarmTime from XML = 10): ALARM after `mAlarmCount > 10`, i.e.,
  after mAlarmCount = 15 → **3 consecutive 5-second alarm detections (15 s total)**

This makes beta **less** sensitive in this edge case (needs 4 detections vs
master's 3), not more.

### Proposed fix

Change the code default to `"10"` to match the XML default:
```java
String alarmTimeStr = mSP.getString("AlarmTime", "10");  // was "15"
```

---

## Difference 5: `OsdAlarmActive` / `FlapAlarmActive` Code Defaults (Minor)

| Parameter | Master code default | Beta code default | XML default |
|---|---|---|---|
| `OsdAlarmActive` | `false` | `true` | `true` |
| `FlapAlarmActive` | `true` | `false` | `true` |

These code-level defaults are only used if the XML preferences have not been
loaded by `PreferenceManager.setDefaultValues()`. In normal operation the XML
default (`true` for both) applies, so there is no real-world difference. However,
if preferences are ever reset or not initialised, the beta would have OSD active
but Flap inactive, while master would have Flap active but OSD inactive.

### Proposed fix

Align the code defaults with the XML defaults (`true` for both) in both branches.

---

## OSD FFT and Power Calculations — No Differences Found

The following aspects of the OSD algorithm were compared line-by-line and found
to be **identical** in both implementations:

| Aspect | Master (`SdDataSource.doAnalysis()`) | Beta (`SdAlgOsd.processSdData()`) |
|---|---|---|
| FFT computation | `DoubleFFT_1D.realForward()` | identical |
| `getMagnitude(fft, i)` | `fft[2i]² + fft[2i+1]²` | identical |
| `freq2FftBin(f, fs, N)` | `(int)(N * f / fs)` | identical |
| specPower loop | `i = 1` to `< mNsamp/2` | identical |
| specPower normalisation | `/ mNsamp / 2` | identical |
| roiPower loop | `i = nMin` to `< nMax` | identical |
| roiPower normalisation | `/ (nMax - nMin)` | identical |
| Scaling to long | `(long) x / ACCEL_SCALE_FACTOR` | identical |
| Alarm threshold check | `roiPower > thresh && 10*roi/spec > ratioThresh` | identical |
| Default `AlarmThresh` | 100 (XML) | 100 (code default) | identical |
| Default `AlarmRatioThresh` | 57 (XML) | 57 (code default) | identical |

---

## Alarm State Machine — No Differences Found (under normal conditions)

A Python simulation was run comparing the master `alarmCheck()` and beta
`SeizureDetector.processData()` state machines across seven distinct alarm
sequences. In every case the two produced **identical state transitions**.

With the XML default values (`WarnTime = 5 s`, `AlarmTime = 10 s`,
`SamplePeriod = 5 s`), both implementations require:

- **2 consecutive alarm detections** (10 s) to reach WARNING
- **3 consecutive alarm detections** (15 s) to reach ALARM

One structural difference exists: when the alarm is accumulating but
`mAlarmCount <= mWarnTime`, the master leaves `alarmState` **unchanged** while
the beta explicitly sets it to **OK**. Under normal OSD-only operation this
produces identical results. It only differs if another alarm source (such as an
HR alarm in master) has previously set `alarmState` to a higher value — see
Difference 2 above.

---

## Summary Table

| # | Difference | Direction | Conditions |
|---|---|---|---|
| 1 | FlapAlarm threshold defaults (`10` vs `5000`) | **Beta more sensitive** | Only if XML prefs not loaded |
| 2 | HR alarm bypasses timer in master | **Master more sensitive for HR** | Whenever HR alarm is active |
| 3 | `mSamplePeriod` hard-coded to 5 in beta | **Master more sensitive** (fewer cycles) | When watch reports period > 5 s |
| 4 | `AlarmTime` code default `15` vs XML `10` | **Beta less sensitive** | Only if XML prefs not loaded |
| 5 | `OsdAlarmActive` / `FlapAlarmActive` code defaults | Minor / opposite effects | Only if XML prefs not loaded |
| 6 | OSD FFT maths | **No difference** | Always |
| 7 | OSD alarm state machine counting | **No difference** | Always (normal operation) |
