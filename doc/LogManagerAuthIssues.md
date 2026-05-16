# Log Manager Authentication Issues Analysis

**Date:** 2026-05-16
**Version analysed:** 5.0.0
**Fixed in:** 5.0.1 (issues 1, 2, 10)

## Background

Users reported intermittent, unexpected authentication failures when connecting to the OSD remote
log server. Investigation identified several issues in the authentication and upload pipeline, some
of which can be directly triggered by a slow or temporarily overloaded server.

---

## Issues Found

### Issue 1 — No explicit HTTP timeout on authentication request (FIXED in 5.0.1)

**Severity:** High
**File:** `app/src/main/java/uk/org/openseizuredetector/comms/WebApiConnection_osdapi.java`
**Lines:** ~83–126 (`authenticate()`)

The `StringRequest` in `authenticate()` has no `setRetryPolicy()` call. Volley's built-in default
is a ~2.5 second socket timeout with 1 retry and a back-off multiplier of 1.0, giving an effective
worst-case wait of ~5–7 seconds before `onErrorResponse()` is called. For a slow or overloaded
server this is far too short and will trigger a spurious authentication failure.

**Fix applied:** Set an explicit `DefaultRetryPolicy` with a 15-second socket timeout, 1 retry,
and no back-off multiplier on the request in `authenticate()`.

---

### Issue 2 — Timeout and wrong-credentials errors are indistinguishable to the user (FIXED in 5.0.1)

**Severity:** Medium
**File:** `app/src/main/java/uk/org/openseizuredetector/comms/WebApiConnection_osdapi.java` and
`app/src/main/java/uk/org/openseizuredetector/activity/auth/AuthenticateActivity.java`

Both a network timeout (`com.android.volley.TimeoutError`) and a genuine 401 Unauthorized response
trigger `onErrorResponse()`, which calls `callback.accept(null)`. The activity then always displays
the generic message `"ERROR: Authentication Failed - Please Try Again"`, giving no indication of
whether the problem was wrong credentials or a network/server issue.

**Fix applied:**
- `WebApiConnection_osdapi.authenticate()` now passes an error-type string through a dedicated
  `AuthErrorCallback` so the caller can distinguish between `"TIMEOUT"`, `"NETWORK"`, and
  `"AUTH_FAILURE"` errors.
- `AuthenticateActivity` displays a specific message for each case:
  - Timeout: `"Server timeout - please check your connection and try again"`
  - Network error: `"Network error - please check your connection and try again"`
  - Auth failure (401): `"Authentication Failed - please check your username and password"`

---

### Issue 3 — `waitForConnection()` has no timeout or lifecycle guard

**Severity:** High
**File:** `app/src/main/java/uk/org/openseizuredetector/activity/auth/AuthenticateActivity.java`
**Lines:** ~169–184

The `waitForConnection()` method polls every 100 ms using a recursive `Handler.postDelayed()` loop
until `mConnection.mBound` becomes true. There is:
- No maximum wait count / deadline — will spin forever if `SdServer` never binds.
- No `isFinishing()` or `isDestroyed()` guard inside the `Runnable` — if the user navigates away
  before binding completes, the `Runnable` will still fire and attempt to call
  `initialiseServiceConnection()` on a dead activity, which can cause a NullPointerException.

**Proposed fix (not yet implemented):**
```java
private int mWaitCount = 0;
private void waitForConnection() {
    if (isFinishing() || isDestroyed() || mWaitCount++ > 100) return; // 10 s max
    if (mConnection.mBound) { initialiseServiceConnection(); return; }
    new Handler(Looper.getMainLooper()).postDelayed(this::waitForConnection, 100);
}
```

---

### Issue 4 — `updateUi()` creates an unbounded retry loop on persistent server failure

**Severity:** Medium
**File:** `app/src/main/java/uk/org/openseizuredetector/activity/auth/AuthenticateActivity.java`
**Lines:** ~238–246

If `getUserProfile()` keeps returning null (e.g., server is down), `updateUi()` schedules itself
again 1.5 seconds later via `Handler.postDelayed()`. There is no retry counter and no cap. Each
invocation fires a new Volley request. The only exit conditions are `isFinishing()` becoming true
or `isLoggedIn()` returning false, neither of which occurs from a server-side timeout.

**Proposed fix (not yet implemented):**
Add a retry counter and a maximum retry limit (e.g., 5 retries) before giving up and showing a
persistent error message.

---

### Issue 5 — Async upload callback chain has no per-step timeout guard

**Severity:** High
**File:** `app/src/main/java/uk/org/openseizuredetector/data/logging/LogUploader.java`

The upload sequence is a deeply-chained async callback ladder:

`uploadSdData` → `createEvent` → `getEvent` → `handleCreatedEvent` → `getDatapoints` →
`uploadNextDatapoint` (loop) → `finishUpload`

If any step stalls (server accepts the TCP connection but never sends a response body), Volley's
read timeout may not fire in all network scenarios and the callback is never invoked.
`finishUpload()` is never called, `mUploadInProgress` stays `true` permanently, and all subsequent
upload attempts for the lifetime of the `LogManager` instance are blocked silently.

**Proposed fix (not yet implemented):**
Add a watchdog `Handler` at the start of `uploadSdData()` that calls `finishUpload()` after a
configurable maximum upload duration (e.g., 60 seconds), cancelled on the `finishUpload()` path.

---

### Issue 6 — `mUploadInProgress` has unsynchronized writes

**Severity:** High
**File:** `app/src/main/java/uk/org/openseizuredetector/data/logging/LogUploader.java`
**Lines:** ~339, ~405

Writes to `mUploadInProgress` at lines ~339 (`handleCreatedEvent`) and ~405
(`uploadNextDatapoint`) are **not** wrapped in `synchronized(mUploadLock)`, while other
reads/writes use the lock. This is a race condition that could allow a partial upload to be
abandoned or duplicated if `onNetworkStateChanged()` runs concurrently.

**Proposed fix (not yet implemented):**
Wrap all writes to `mUploadInProgress` in `synchronized(mUploadLock)` consistently.

---

### Issue 7 — Static `LogManager.close()` races with new `LogManager` construction

**Severity:** High
**File:** `app/src/main/java/uk/org/openseizuredetector/SdServer.java` lines ~555–570 and
`app/src/main/java/uk/org/openseizuredetector/data/logging/LogManager.java` lines ~695–699

When `SdServer` recreates the `LogManager`, it starts a background thread to call
`oldLm.stop()` + `LogManager.close()` (which closes the shared static database handle) while
simultaneously constructing the new `LogManager` instance on the main thread. If the new
instance's constructor or `LogRepository.initialize()` runs before the background thread
calls `LogManager.close()`, the new instance's database handle is closed underneath it.

**Proposed fix (not yet implemented):**
Restructure the shutdown/restart sequence so `LogManager.close()` completes before the new
`LogManager` is constructed, or replace the static `close()` with instance-level resource
management.

---

### Issue 8 — `getParams()` adds Authorization as a spurious POST body key

**Severity:** Low
**File:** `app/src/main/java/uk/org/openseizuredetector/comms/WebApiConnection_osdapi.java`
**Lines:** ~203–209, ~414–419, ~500–505

In `createEvent`, `updateEvent`, and `createDatapoint`, the overridden `getParams()` method
adds the authorization string as a **key** in the POST body parameters map instead of in the
HTTP headers:

```java
params.put("Authorization: Token " + authToken, authToken);  // wrong
```

Since `getHeaders()` is also overridden and correctly sets the `Authorization` header, this is
harmless in practice (the server ignores the junk form field). However, if `getHeaders()` were
ever removed, authentication would silently break. The `getParams()` overrides should either
be corrected or removed entirely.

---

### Issue 9 — `saveAuthToken()` uses `commit()` instead of `apply()`

**Severity:** Low
**File:** `app/src/main/java/uk/org/openseizuredetector/activity/auth/AuthenticateActivity.java`
**Line:** ~398

`SharedPreferences.edit().putString(...).commit()` performs a synchronous disk write on the main
thread. This is an Android performance anti-pattern. `apply()` should be used instead.

---

## Summary Table

| # | Severity | Status | Issue |
|---|----------|--------|-------|
| 1 | High     | **Fixed 5.0.1** | No explicit Volley timeout on `authenticate()` — slow server causes spurious failure |
| 2 | Medium   | **Fixed 5.0.1** | Timeout and auth failures indistinguishable in UI |
| 3 | High     | Open | `waitForConnection()` infinite loop with no lifecycle guard |
| 4 | Medium   | Open | `updateUi()` unbounded retry on persistent `getUserProfile()` failure |
| 5 | High     | Open | No timeout guard on async upload callback chain — `mUploadInProgress` can be stuck |
| 6 | High     | Open | Unsynchronized writes to `mUploadInProgress` |
| 7 | High     | Open | Static `LogManager.close()` races with new `LogManager` construction |
| 8 | Low      | Open | `getParams()` adds Authorization as bogus POST body key |
| 9 | Low      | Open | `saveAuthToken()` uses `commit()` instead of `apply()` |
| 10 | High    | **Fixed 5.0.1** | `triggerImmediateUpload()` calls `onNetworkChange()` unconditionally, recycling the Volley queue and cancelling in-flight `getUserProfile` request immediately after login |

---

## Issue 10 — `triggerImmediateUpload()` destroys Volley queue immediately after login (FIXED in 5.0.1)

**Severity:** High
**File:** `app/src/main/java/uk/org/openseizuredetector/data/logging/LogManager.java`
**Lines:** ~605–616

### Symptom

After a successful login, users see a transient *"error retrieving user data"* toast and logcat
shows:

```
getUserProfile Error: com.android.volley.NoConnectionError:
  java.io.InterruptedIOException: thread interrupted
```

### Root Cause

In the login success callback in `AuthenticateActivity`, the sequence was:

1. `updateUi()` — calls `mWac.getUserProfile(...)`, which enqueues a Volley GET request.
2. `mLm.triggerImmediateUpload()` — which called `mWac.onNetworkChange()`, which called
   `mQueue.stop()` and recreated the queue.

Step 2 destroyed the still-in-flight `getUserProfile` request from step 1 by stopping the Volley
dispatcher threads, causing the `InterruptedIOException`. The `getUserProfile` callback then
received `null` and showed the error toast.

The queue recycle inside `triggerImmediateUpload()` was added to handle the case where the network
had changed (e.g., Wi-Fi reconnect) and the old queue held stale socket state. However, after a
login the network has not changed — the queue is healthy and does not need recycling.

### Fix applied

Removed the `onNetworkChange()` call from `triggerImmediateUpload()`. Queue recycling on an
actual network transition is already handled correctly by the separate `onNetworkStateChanged()`
path. `triggerImmediateUpload()` now only calls `writeToRemoteServer()`.
