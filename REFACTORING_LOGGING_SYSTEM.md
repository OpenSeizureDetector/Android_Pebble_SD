# OpenSeizureDetector Logging System Refactoring

**Status**: Phase 2 Complete - Foundation + Repository Layer  
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

1. **New File**: `data/logging/LogRepository.java`
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

## Phase 3: Next Steps (Future)

**3a. Inject Repository into LogManager**
   - Create `LogRepository mRepository` field in `LogManager`
   - Initialize during constructor
   - Update all `LogManager` public DB methods to delegate to repository
   - Keep external API unchanged
   - Goal: Verify no regressions in existing callers

**3b. Separate Remote API Orchestration**
   - Extract event upload orchestration logic into `LogUploader` or `EventSynchronizer`
   - Isolate `WebApiConnection` interaction
   - Keep timer-driven retry loop separate from DB access
   - Goal: Reduce `LogManager` from 1967 lines to ~600 lines

**3c. Centralize Local Event Queries**
   - Create `LocalEventQuerier` helper
   - Extract `getEventsList()`, `getLocalEventsCount()`, `getLocalDatapointsCount()`, etc.
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

---

## Compilation Verification

### After Phase 1
```bash
./gradlew :app:compileDebugJavaWithJavac :app:mergeDebugResources --console=plain
# Result: BUILD SUCCESSFUL
```

### After Phase 2
```bash
./gradlew :app:compileDebugJavaWithJavac --console=plain
# Result: BUILD SUCCESSFUL
```

---

## Current File Sizes (Phase 2)

- `LogManager.java`: 1967 lines (unchanged externally, ready for internal refactor)
- `LogRepository.java`: 614 lines (new, encapsulates all DB operations)
- `Log.java`: 107 lines (new, logging wrapper)

---

## Known Limitations & TODOs

1. **Log.java**: Uses static volatile fields for `sContext` and `sUtil`
   - Android lint warns about potential memory leaks
   - Acceptable for now since these are app-singleton instances
   - Future: Consider weak references or dependency injection

2. **LogRepository**: Still holds static `mOsdDb` field
   - Maintains compatibility with existing `getDatabase()` calls
   - Future: Make it instance-owned or inject via constructor

3. **Migration Strategy**: Existing `LogManager` methods not yet using repository
   - Phase 3a will inject and delegate
   - Need to verify no performance regression during switchover

4. **Error Handling**: Some catch-all `Exception` handlers could be more specific
   - Phase 3b refactor can improve granularity

---

## How to Continue

1. Create a `LogRepository` instance in `LogManager` constructor
2. Delegate existing DB methods to the repository
3. Verify no test failures
4. Then extract remote API orchestration
5. Add integration tests for the complete pipeline

---

## References

- Phase 1 Summary: Logging wrapper with conditional file persistence
- Phase 2 Summary: Repository pattern for database encapsulation
- Related: `PersistentFileLogger.java`, `OsdUtil.writeToSysLogFile()`

