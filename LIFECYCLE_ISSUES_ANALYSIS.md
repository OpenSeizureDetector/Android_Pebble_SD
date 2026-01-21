# Lifecycle Issues Analysis - LogManager and EditEventActivity

## Date: January 21, 2026

## Executive Summary

Analysis of LogManager and EditEventActivity reveals several lifecycle management issues similar to those found in SdAlgNn. These issues can cause background operations to continue after activities are destroyed, leading to crashes, resource leaks, and the app not shutting down cleanly.

## Critical Issues Found

### 1. **EditEventActivity - Missing Callback Lifecycle Management**

**Problem**: EditEventActivity makes asynchronous network calls via WebApiConnection callbacks that can complete after the activity is destroyed.

**Location**: `EditEventActivity.java` lines 145-438

**Specific Issues**:
- `initialiseServiceConnection()` (line 145) makes two async calls:
  - `mWac.getEventTypes()` - can call `updateUi()` after activity destroyed
  - `mWac.getEvent()` - can call `updateUi()` and `finish()` after activity destroyed
- `onOK` click listener (line 313) makes async `updateEvent()` calls that can:
  - Show toasts on destroyed activity
  - Call `finish()` on destroyed activity
- `updateGroupEventsSequentially()` (line 360) creates a chain of recursive async calls with no cancellation mechanism

**Symptoms**:
- "Can't toast on a thread that has not called Looper.prepare()" errors
- Activity attempts to update UI after being destroyed
- Callback chains continue executing even after user exits activity

**Risk Level**: HIGH - User-reported issues with starting EditEventActivity

---

### 2. **LogManager - Timers Continue After stop()**

**Problem**: CountDownTimer instances may continue executing even after `stop()` is called, and callbacks from ongoing uploads can occur after shutdown.

**Location**: `LogManager.java` lines 1445-1536

**Specific Issues**:

a) **RemoteLogTimer** (line 1445):
   - Calls `writeToRemoteServer()` -> `uploadSdData()` 
   - Upload process involves multiple async callbacks
   - No mechanism to cancel ongoing uploads when `stop()` is called
   - Can show toasts via `mUtil.showToast()` from background threads

b) **NDATimer** (line 1468):
   - Modifies SharedPreferences on timer thread
   - Can continue running after `stop()` called

c) **AutoPruneTimer** (line 1515):
   - Database operations may continue after `stop()`

**Upload Chain Issues** (lines 995-1230):
- `uploadSdData()` -> `createEventCallback()` -> `uploadNextDatapoint()` -> `datapointCallback()`
- This creates a chain of async callbacks with no cancellation mechanism
- `mUploadInProgress` flag doesn't prevent callbacks from executing
- Toast notifications can occur from background threads (lines 1126, 1180)

**Risk Level**: HIGH - Likely cause of app not shutting down cleanly

---

### 3. **Static Database and WebApiConnection**

**Problem**: LogManager uses static database (`mOsdDb`) and context (`mContext`), but WebApiConnection (`mWac`) is not static, creating potential for stale references.

**Location**: `LogManager.java` lines 102-110

```java
private static Context mContext;
private static OsdUtil mUtil;
public static WebApiConnection mWac;
public static final boolean USE_FIREBASE_BACKEND = false;
```

**Issues**:
- Static context can cause memory leaks
- `close()` is static but `stop()` is not - confusing lifecycle management
- Multiple LogManager instances share the same database but have separate timers
- Comment at line 1234 acknowledges this is wrong but not fixed

**Risk Level**: MEDIUM - Can cause subtle memory leaks and crashes

---

### 4. **Toast on Background Threads**

**Problem**: Multiple locations attempt to show toasts from background threads without proper Handler usage.

**Locations**:
- `LogManager.java`: lines 390, 858, 863, 929, 935, 941, 1126, 1180
- These use `mUtil.showToast()` from callback threads

**Symptoms**:
- "Can't toast on a thread that has not called Looper.prepare()" crashes
- Same issue seen in SdAlgNn

**Risk Level**: MEDIUM - Causes crashes when network operations complete

---

### 5. **No Activity Lifecycle State Checking**

**Problem**: EditEventActivity doesn't check if it's in a valid state before executing callbacks.

**Missing Checks**:
- No `isFinishing()` checks before calling `finish()`
- No `isDestroyed()` checks before calling `updateUi()`
- No check before showing toasts
- No cancellation of pending operations in `onStop()` or `onDestroy()`

**Risk Level**: HIGH - Direct cause of crashes

---

### 6. **Database Operations on Main Thread**

**Problem**: LogManager performs database operations synchronously on the caller's thread.

**Location**: `LogManager.java` - multiple methods including:
- `writeDatapointToLocalDb()` (line 337)
- `getDatapointsByDate()` 
- `createLocalEvent()`
- Database queries in upload methods

**Risk Level**: MEDIUM - Can cause ANR (Application Not Responding) errors

---

## Comparison with SdAlgNn Issues

| Issue | SdAlgNn | LogManager | EditEventActivity |
|-------|---------|------------|-------------------|
| Background thread operations continue after shutdown | ✓ | ✓ | ✓ |
| Toast on non-UI thread | ✓ | ✓ | ✓ |
| No lifecycle state checking | ✓ | N/A | ✓ |
| Static references causing leaks | - | ✓ | - |
| Async callback chains without cancellation | ✓ | ✓ | ✓ |

---

## Proposed Solutions

### Solution 1: Add Lifecycle State Management to EditEventActivity

**Implementation**:

1. Add a flag to track if activity is active:
```java
private volatile boolean mIsActive = false;
```

2. Update lifecycle methods:
```java
@Override
protected void onStart() {
    super.onStart();
    mIsActive = true;
    // existing code
}

@Override
protected void onStop() {
    mIsActive = false;
    super.onStop();
    // existing code
}
```

3. Wrap all callbacks with state checks:
```java
if (!mIsActive || isFinishing() || isDestroyed()) {
    Log.w(TAG, "Activity not active, ignoring callback");
    return;
}
```

4. Add proper Handler for UI operations:
```java
private final Handler mUiHandler = new Handler(Looper.getMainLooper());
```

**Benefit**: Prevents crashes from callbacks executing on destroyed activities

---

### Solution 2: Implement Proper Shutdown for LogManager

**Implementation**:

1. Add shutdown flag:
```java
private volatile boolean mShutdownRequested = false;
```

2. Update `stop()` method:
```java
public void stop() {
    Log.i(TAG, "stop() - initiating shutdown");
    mShutdownRequested = true;
    stopRemoteLogTimer();
    stopAutoPruneTimer();
    stopNDATimer();
    
    // Wait for ongoing upload to complete or timeout
    int waitCount = 0;
    while (mUploadInProgress && waitCount < 50) { // 5 second max wait
        try {
            Thread.sleep(100);
            waitCount++;
        } catch (InterruptedException e) {
            break;
        }
    }
    
    if (mUploadInProgress) {
        Log.w(TAG, "stop() - forcing upload termination after timeout");
        finishUpload();
    }
}
```

3. Check shutdown flag in all callbacks:
```java
if (mShutdownRequested) {
    Log.v(TAG, "Shutdown requested, ignoring callback");
    return;
}
```

4. Use Handler for all toast operations:
```java
private void showToastSafe(final String message) {
    if (mShutdownRequested) return;
    mUtil.showToast(message); // mUtil already has proper Handler
}
```

**Benefit**: Ensures clean shutdown and prevents background operations

---

### Solution 3: Fix Static References in LogManager

**Implementation**:

1. Make database and utilities instance variables:
```java
private SQLiteDatabase mInstanceDb = null;
private Context mInstanceContext;
private OsdUtil mInstanceUtil;
```

2. Implement reference counting:
```java
private static int sInstanceCount = 0;
private static final Object sLock = new Object();

public LogManager(...) {
    synchronized (sLock) {
        sInstanceCount++;
        if (mOsdDb == null) {
            openDb();
        }
    }
}

public void close() {
    synchronized (sLock) {
        sInstanceCount--;
        if (sInstanceCount <= 0) {
            if (mOsdDb != null) {
                mOsdDb.close();
                mOsdDb = null;
            }
        }
    }
}
```

**Benefit**: Prevents memory leaks and properly manages shared resources

---

### Solution 4: Move Database Operations to Background Thread

**Implementation**:

1. Create dedicated executor:
```java
private static final ExecutorService sDbExecutor = 
    Executors.newSingleThreadExecutor();
```

2. Wrap database operations:
```java
public void writeDatapointToLocalDb(SdData sdData) {
    sDbExecutor.execute(() -> {
        if (mShutdownRequested) return;
        // existing database code
    });
}
```

3. Shutdown executor in `close()`:
```java
sDbExecutor.shutdown();
try {
    sDbExecutor.awaitTermination(5, TimeUnit.SECONDS);
} catch (InterruptedException e) {
    sDbExecutor.shutdownNow();
}
```

**Benefit**: Prevents ANR and improves responsiveness

---

### Solution 5: Add Cancellation to Group Edit Operations

**Implementation**:

1. Track ongoing operations:
```java
private final List<String> mPendingGroupUpdates = new ArrayList<>();
```

2. Add cancellation support:
```java
@Override
protected void onStop() {
    mIsActive = false;
    synchronized (mPendingGroupUpdates) {
        mPendingGroupUpdates.clear();
    }
    super.onStop();
}

private void updateGroupEventsSequentially(final int index) {
    if (!mIsActive || index >= mEventIds.size()) {
        return;
    }
    
    final String eventId = mEventIds.get(index);
    synchronized (mPendingGroupUpdates) {
        if (!mPendingGroupUpdates.contains(eventId)) {
            mPendingGroupUpdates.add(eventId);
        }
    }
    
    mWac.getEvent(eventId, eventObj -> {
        synchronized (mPendingGroupUpdates) {
            if (!mPendingGroupUpdates.contains(eventId) || !mIsActive) {
                return; // Operation cancelled
            }
            mPendingGroupUpdates.remove(eventId);
        }
        // existing code
    });
}
```

**Benefit**: Prevents runaway callback chains

---

## Implementation Priority

1. **CRITICAL (Do First)**:
   - Add lifecycle state management to EditEventActivity (Solution 1)
   - Implement proper shutdown for LogManager (Solution 2)

2. **HIGH (Do Soon)**:
   - Fix static references (Solution 3)
   - Add cancellation to group edit operations (Solution 5)

3. **MEDIUM (Technical Debt)**:
   - Move database operations to background thread (Solution 4)

---

## Testing Recommendations

1. **Test Activity Lifecycle**:
   - Start EditEventActivity, rotate screen immediately
   - Start EditEventActivity, press back immediately
   - Start EditEventActivity, press home immediately
   - Verify no crashes or toasts after activity destroyed

2. **Test Service Shutdown**:
   - Enable remote logging with slow network
   - Disable app while upload in progress
   - Verify service stops cleanly
   - Verify no notifications after service stopped

3. **Test Group Edit**:
   - Start group edit with 10+ events
   - Exit activity immediately
   - Verify operations are cancelled
   - Verify no memory leaks

4. **Stress Test**:
   - Rapidly start/stop EditEventActivity 20 times
   - Rapidly enable/disable service 20 times
   - Monitor for memory leaks with Android Profiler
   - Check logcat for leaked ServiceConnection warnings

---

## Root Cause Summary

The root cause is the same across SdAlgNn, LogManager, and EditEventActivity:

**Asynchronous operations (network, threading, timers) that don't respect Android component lifecycles.**

The Android framework can destroy activities and services at any time, but callback chains continue executing. Without proper lifecycle management, these callbacks attempt to:
- Update destroyed UI
- Show toasts from background threads
- Access freed resources
- Keep services alive when they should terminate

The solution in all cases is the same:
1. Track lifecycle state
2. Check state before executing callbacks
3. Provide cancellation mechanisms
4. Use proper threading (Handler for UI operations)
5. Clean up resources properly

---

## Files to Modify

1. `/app/src/main/java/uk/org/openseizuredetector/EditEventActivity.java` - 438 lines
2. `/app/src/main/java/uk/org/openseizuredetector/LogManager.java` - 1536 lines
3. `/app/src/main/java/uk/org/openseizuredetector/WebApiConnection_osdapi.java` - Consider adding cancellation support

## Estimated Effort

- Solution 1 (EditEventActivity): 2-3 hours
- Solution 2 (LogManager shutdown): 3-4 hours
- Solution 3 (Static references): 4-6 hours (requires careful refactoring)
- Solution 4 (Background threads): 2-3 hours
- Solution 5 (Group edit cancellation): 1-2 hours
- Testing: 4-6 hours

**Total: 16-24 hours**

---

## Additional Notes

- Similar issues likely exist in other activities that use WebApiConnection (e.g., RemoteDbActivity, LogManagerControlActivity)
- Consider creating a base activity class that handles lifecycle-aware callbacks
- WebApiConnection itself could be enhanced with cancellation support
- The use of Volley for networking is good as it handles threading, but callbacks still need lifecycle awareness

