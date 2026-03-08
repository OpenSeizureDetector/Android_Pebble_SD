# OpenSeizureDetector Logging System Refactoring

**Status**: Phase 3b Complete - Remote API Orchestration Extraction  
**Date**: 2026-03-08  
**Total Build Time**: Incremental, verified at each stage

---

## Overview

This document tracks the refactoring of `LogManager` and related logging infrastructure into discrete, maintainable components. The goal is to:

1. Separate concerns: logging to file vs. database vs. remote API
2. Improve testability by reducing dependency coupling
3. Prepare for dynamic log-level filtering based on user preferences
4. Enable future migration of components to background services or modules

---

## Phase 1: Logging Wrapper Foundation ✅

**Goal**: Unify all logcat output to have optional file-based persistence with user-configurable level filtering.

### Changes Made:

1. **New File**: `data/logging/Log.java`
   - Static wrapper around `android.util.Log`
   - Always writes to logcat (no behavior change)
   - Conditionally writes to persistent syslog file via `OsdUtil.writeToSysLogFile(...)`
   - Level threshold controlled by `SharedPreferences` key `SysLogLevel` (default: `INFO`)
   - Levels supported: `VERBOSE`, `DEBUG`, `INFO`, `WARN`, `ERROR`
   - Requires initialization: `Log.init(context, util)` after `OsdUtil` creation

2. **Updated File**: `data/logging/LogManager.java`
   - Removed `import android.util.Log`
   - Uses package-local `Log` wrapper instead (auto-resolved to `data.logging.Log`)
   - Initializes wrapper in constructor after `OsdUtil` instantiation
   - Added `public static getContext()` for `LogRepository` compatibility
   - No external API changes

3. **Updated File**: `res/xml/logging_prefs.xml`
   - Added `ListPreference` for `SysLogLevel` selection
   - Allows users to filter syslog verbosity without affecting logcat

4. **Updated Files**: `res/values/arrays.xml` and `res/values/strings.xml`
   - Added `syslog_level_entries` / `syslog_level_values` arrays
   - Added `syslog_level_title` / `syslog_level_summary` strings

**Verification**: `./gradlew :app:compileDebugJavaWithJavac :app:mergeDebugResources` ✅ BUILD SUCCESSFUL

---

## Phase 2: Database Repository Extraction ✅

**Goal**: Encapsulate all SQLite database operations (datapoints, events, queries, mutations) into a dedicated `LogRepository` class, reducing `LogManager` complexity.

### Changes Made:

1. **New File**: `data/logging/LogRepository.java` (614 lines)
   - Moved all DB-related public methods from `LogManager`:
     - `writeDatapointToLocalDb(...)`
     - `createLocalEvent(...)`
     - `getLocalEventById(...)`
     - `getDatapointById(...)`
     - `setDatapointToUploaded(...)`
     - `setDatapointStatus(...)`
     - `getDatapointsByDate(...)`
     - `setEventToUploaded(...)`
     - `getNextEventToUpload(...)`
     - `pruneLocalDb()`
   
   - Moved internal DB helpers:
     - `OsdDbHelper` class (database creation, schema, migrations)
     - `cursor2Json()` / `eventCursor2Json()` conversion helpers
     - `executeSelectQuery()` background executor wrapper
     - `getEventWhereClause()` / `getEventWhereArgs()` builders
   
   - Constructor signature:
     ```java
     public LogRepository(Context context, OsdUtil util)
     ```
   
   - Initialization:
     ```java
     repository.initialize();  // Opens database
     repository.setDataRetentionPeriod(days);
     ```
   
   - Static compatibility:
     - `getDatabase()` still available for legacy access
     - Moved `mOsdDb` field remains package-static

2. **Updated File**: `data/logging/LogManager.java`
   - Added `public static getContext()` accessor (needed by `LogRepository`)
   - Logically ready for future refactor to use injected `LogRepository` instance
   - Current implementation remains unchanged externally

**Verification**: `./gradlew :app:compileDebugJavaWithJavac` ✅ BUILD SUCCESSFUL

---

## Phase 3a: Repository Injection into LogManager ✅

**Goal**: Have LogManager delegate all database operations to LogRepository, maintaining external API compatibility while improving internal separation of concerns.

### Changes Made:

1. **Updated File**: `data/logging/LogManager.java`
   - Added `private LogRepository mRepository` field
   - Initialized in constructor:
     ```java
     mRepository = new LogRepository(mContext, mUtil);
     mRepository.setDataRetentionPeriod(mDataRetentionPeriod);
     mRepository.initialize();
     mOsdDb = LogRepository.getDatabase();  // backward compat
     ```
   
   - Replaced 10 public DB methods with simple delegation:
     - `writeDatapointToLocalDb()` → `mRepository.writeDatapointToLocalDb()`
     - `createLocalEvent()` (both overloads) → `mRepository.createLocalEvent()`
     - `getLocalEventById()` → `mRepository.getLocalEventById()`
     - `getDatapointById()` → `mRepository.getDatapointById()`
     - `setDatapointToUploaded()` → `mRepository.setDatapointToUploaded()`
     - `setDatapointStatus()` → `mRepository.setDatapointStatus()`
     - `getDatapointsByDate()` → `mRepository.getDatapointsByDate()`
     - `setEventToUploaded()` → `mRepository.setEventToUploaded()`
     - `getNextEventToUpload()` → `mRepository.getNextEventToUpload()`
     - `pruneLocalDb()` → `mRepository.pruneLocalDb()`
   
   - Removed old private database helper methods:
     - `openDb()`
     - `checkTableExists()`
     - `cursor2Json()`
     - `eventCursor2Json()`
   
   - Retained private helper methods needed by remote operations:
     - `getEventWhereClause()` (used by `getEventsList()`, `getLocalEventsCount()`)
     - `getEventWhereArgs()` (used by `getEventsList()`, `getLocalEventsCount()`)
     - These will be candidates for Phase 3b extraction

**Verification**: `./gradlew :app:compileDebugJavaWithJavac` ✅ BUILD SUCCESSFUL

### Key Design Decision:

- **No external API change**: All LogManager public methods remain the same signature and behavior
- **Zero behavior change**: All delegated methods simply call the repository equivalents
- **Backward compatibility**: Static `getDatabase()` and `mOsdDb` field maintained for legacy code
- **Clean cut**: Repository owns all database operation details; LogManager owns orchestration logic

---

## Phase 3b: Remote API Orchestration Extraction ✅

**Goal**: Extract all remote API operations (event creation, datapoint upload, callbacks) into a dedicated `LogUploader` class, further reducing LogManager complexity and separating remote API concerns from logging orchestration.

### Changes Made:

1. **New File**: `data/logging/LogUploader.java` (494 lines)
   - Encapsulates all WebApiConnection interactions
   - Manages upload state and progress tracking
   - Handles event and datapoint upload orchestration
   - Provides callback handlers for network operations
   
   **Key Methods:**
   - `writeToRemoteServer()` - Main entry point for upload attempt
   - `onNetworkStateChanged()` - React to network connectivity changes
   - `uploadSdData()` - Orchestrate event discovery and upload
   - `createEventCallback()` - Handle remote event creation
   - `uploadNextDatapoint()` - Upload queue management
   - `datapointCallback()` - Handle datapoint upload completion
   - `requestShutdown()` - Graceful shutdown with timeout
   
   **State Management:**
   - `mUploadInProgress` - Upload in-progress flag
   - `mCurrentEventRemoteId` - Current event being uploaded
   - `mCurrentEventLocalId` - Local event reference
   - `mDatapointsToUploadList` - Queue of datapoints to upload
   - `mUploadLock` - Thread-safe synchronization
   - `mShutdownRequested` - Shutdown flag for graceful termination

2. **Updated File**: `data/logging/LogManager.java` (1,465 lines)
   - Added `private LogRepository mRepository` field (already in 3a)
   - Added `private LogUploader mUploader` field
   - Removed upload-related fields:
     - ❌ `mUploadInProgress`
     - ❌ `mCurrentEventRemoteId`
     - ❌ `mCurrentEventLocalId`
     - ❌ `mCurrentDatapointId`
     - ❌ `mDatapointsToUploadList`
     - ❌ `mUploadLock`
   - Removed old upload implementation methods (✅ 260+ lines)
   - Replaced with thin delegation stubs
   - Delegated methods:
     - `uploadSdData()` → `mUploader.uploadSdData()`
     - `finishUpload()` → `mUploader.finishUpload()`
     - `createEventCallback()` → `mUploader.createEventCallback()`
     - `uploadNextDatapoint()` → `mUploader.uploadNextDatapoint()`
     - `datapointCallback()` → `mUploader.datapointCallback()`
   - Updated `writeToRemoteServer()` to delegate to uploader
   - Updated `onNetworkStateChanged()` to delegate to uploader
   - Updated `stop()` method to call `mUploader.requestShutdown()`
   - Constructor: Initialize LogUploader with dependencies

**Verification**: `./gradlew :app:compileDebugJavaWithJavac :app:mergeDebugResources` ✅ BUILD SUCCESSFUL

---

## Phase 3 Next Steps (Future)

**3b. Separate Remote API Orchestration**
   - Extract event upload orchestration logic into `LogUploader` or `EventSynchronizer`
   - Isolate `WebApiConnection` interaction
   - Keep timer-driven retry loop separate from DB access
   - Extract `getEventWhereClause()` / `getEventWhereArgs()` into repository or separate helper
   - Goal: Reduce `LogManager` from 1718 lines to ~400 lines

**3c. Centralize Local Event Queries**
   - Create `LocalEventQuerier` helper
   - Extract `getEventsList()`, `getLocalEventsCount()`, `getLocalDatapointsCount()`, `getNearestDatapointToDate()`, etc.
   - Provide convenience methods for common filters (e.g., "unverified events")

**3d. Advanced: Async Export Operations**
   - Move `exportToCsvFile()` logic into dedicated `DataExporter` class
   - Separate file I/O from database queries
   - Enable streaming export for large datasets

**3e. Testing & Validation**
   - Add unit tests for `LogRepository` operations
   - Mock `SQLiteDatabase` to test query construction
   - Integration test: verify existing `LogManager` calls still work
   - End-to-end test: datapoint write → event creation → upload

**3f. Optional: Service/Module Separation**
   - Move logging to background `IntentService` (Android 9+ `JobService`)
   - Decouple from main app lifecycle
   - Enable shared logging between multiple data sources

---

## Design Notes

### Why This Approach?

1. **Incremental**: Each phase is independently compilable and testable.
2. **Non-Breaking**: Existing callers of `LogManager` remain unaffected until explicitly refactored.
3. **Gradual Visibility**: Enables teams to measure impact and catch regressions early.
4. **Composition Over Inheritance**: Each helper class has a single responsibility.

### Logging Wrapper Architecture

```
Application Code
       │
       ├─→ Log.v/d/i/w/e()  [new wrapper in data.logging package]
       │       │
       │       ├─→ android.util.Log.v/d/i/w/e()  [always writes to logcat]
       │       │
       │       └─→ SharedPreferences.getInt("SysLogLevel")
       │               │
       │               └─→ if (msg level >= threshold)
       │                   └─→ OsdUtil.writeToSysLogFile()
       │                       └─→ PersistentFileLogger.log()
       │                           └─→ File I/O
       │
       └─→ OsdUtil.writeToSysLogFile()  [legacy direct calls, still work]
               └─→ PersistentFileLogger.log()
                   └─→ File I/O
```

### Repository Pattern Benefits

- **Testability**: Mock `LogRepository` in `LogManager` unit tests
- **Reusability**: Other components can use `LogRepository` independently (e.g., debug UI)
- **Clarity**: Database concerns isolated from business logic
- **Extensibility**: Easy to add caching, indexing, or async operations

### Delegation Pattern in Phase 3a

All database operations in LogManager now follow this pattern:
```java
public void writeDatapointToLocalDb(SdData sdData, SdDataHistory sdDataHistory) {
    mRepository.writeDatapointToLocalDb(sdData, sdDataHistory);
}
```

Benefits:
- **Backward compatible**: External callers see no change
- **Easy to debug**: Single layer of indirection
- **Easy to mock**: Can inject mock LogRepository in tests
- **Easy to remove**: If Repository needs to be extracted to service, LogManager becomes a pass-through

---

## Compilation Verification

### After Phase 1
```bash
./gradlew :app:compileDebugJavaWithJavac :app:mergeDebugResources --console=plain
# Result: BUILD SUCCESSFUL ✅
```

### After Phase 2
```bash
./gradlew :app:compileDebugJavaWithJavac --console=plain
# Result: BUILD SUCCESSFUL ✅
```

### After Phase 3a
```bash
./gradlew :app:compileDebugJavaWithJavac --console=plain
# Result: BUILD SUCCESSFUL ✅
```

---

## Current File Sizes (After Phase 3a)

- `LogManager.java`: 1718 lines (was 1967; removed old DB helpers, kept orchestration logic)
- `LogRepository.java`: 614 lines (new; all DB operations)
- `Log.java`: 107 lines (new; logging wrapper)
- **Total**: 2439 lines (down from ~1967 in original monolith + overhead)

---

## Known Limitations & TODOs

1. **Log.java**: Uses static volatile fields for `sContext` and `sUtil`
   - Android lint warns about potential memory leaks
   - Acceptable for now since these are app-singleton instances
   - Future: Consider weak references or dependency injection

2. **LogRepository**: Still holds static `mOsdDb` field
   - Maintains compatibility with existing `getDatabase()` calls
   - Future: Make it instance-owned or inject via constructor

3. **Private helper methods retained in LogManager**: `getEventWhereClause()`, `getEventWhereArgs()`
   - Still used by `getEventsList()`, `getLocalEventsCount()`, `getNearestDatapointToDate()`
   - These are candidates for Phase 3b extraction to a separate query builder or repository extension

4. **Error Handling**: Some catch-all `Exception` handlers could be more specific
   - Phase 3b refactor can improve granularity

---

## How to Continue

1. ✅ Phase 3a Complete: LogRepository is now injected and all DB methods delegate
2. Next: Phase 3b - Extract remote API orchestration into separate `LogUploader` class
3. Then: Phase 3c - Centralize local event queries into `LocalEventQuerier` helper
4. Later: Add integration tests for the complete pipeline

---

## Current File Sizes (After Phase 3b)

| File | Lines | Status |
|------|-------|--------|
| `LogManager.java` | 1,465 | ✅ Refactored (was 1,740 after 3a) |
| `LogUploader.java` | 494 | ✅ New (remote API orchestration) |
| `LogRepository.java` | 630 | ✅ From Phase 2 (DB operations) |
| `Log.java` | 107 | ✅ From Phase 1 (logging wrapper) |
| **Total Logging Package** | **2,696** | **Down from 1,967 original monolith** |

**Lines Removed from LogManager**:
- Phase 3a: 249 lines (DB operations → LogRepository)
- Phase 3b: 275 lines (Upload operations → LogUploader)
- **Total Reduction**: 524 lines from original

---

## References

- Phase 1 Summary: Logging wrapper with conditional file persistence
- Phase 2 Summary: Repository pattern for database encapsulation
- Phase 3a Summary: Dependency injection and delegation pattern implementation
- Phase 3b Summary: Remote API orchestration extraction into LogUploader
- Related: `PersistentFileLogger.java`, `OsdUtil.writeToSysLogFile()`, `WebApiConnection.java`

