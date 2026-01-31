# Persistent CircBuf History - Analysis and Design

## Date: January 31, 2026

## Problem Statement

Currently, all circular buffer (CircBuf) history data is lost when the app restarts. This affects:
- **FragmentMlAlg**: Seizure probability history (10 minutes, 120 samples)
- **FragmentWatchSig**: Watch signal strength history (4 hours, 2,880 samples)
- **FragmentBatt**: Battery history for watch and phone (24 hours each, 17,280 samples each)

Users lose valuable trend data on every app restart, making it difficult to see patterns over time.

## Current Architecture

### CircBuf Class (`CircBuf.java`)
- Simple circular buffer implementation
- Stores: `double[] mBuff`, `mHead`, `mTail`, `mIsFull`, `mErrVal`, `mBuffLen`
- No serialization/deserialization methods
- No persistence logic

### SdData Class (`SdData.java`)
Contains multiple CircBuf instances:
```java
public CircBuf watchBattBuff = new CircBuf(24*3600/5, -1);      // 17,280 samples
public CircBuf phoneBattBuff = new CircBuf(24*3600/5, -1);      // 17,280 samples
public CircBuf watchSignalStrengthBuff = new CircBuf(4*3600/5, -1); // 2,880 samples
public CircBuf mPseizureHistBuf = new CircBuf(120, -1.0);       // 120 samples
```

### Lifecycle
- `SdData` is created fresh on each app start
- All CircBuf instances are initialized empty (or with zeros for mPseizureHistBuf)
- No persistence mechanism exists

## What Would Be Involved

### 1. **Add Serialization to CircBuf**

#### Complexity: **Low-Medium**

Add methods to serialize/deserialize CircBuf state:

```java
// In CircBuf.java

/**
 * Serialize buffer to JSON
 */
public JSONObject toJSON() throws JSONException {
    JSONObject json = new JSONObject();
    json.put("buffLen", mBuffLen);
    json.put("head", mHead);
    json.put("tail", mTail);
    json.put("isFull", mIsFull);
    json.put("errVal", mErrVal);
    
    JSONArray dataArray = new JSONArray();
    for (int i = 0; i < mBuffLen; i++) {
        dataArray.put(mBuff[i]);
    }
    json.put("data", dataArray);
    
    return json;
}

/**
 * Restore buffer from JSON
 */
public static CircBuf fromJSON(JSONObject json) throws JSONException {
    int buffLen = json.getInt("buffLen");
    double errVal = json.getDouble("errVal");
    
    CircBuf circBuf = new CircBuf(buffLen, errVal);
    circBuf.mHead = json.getInt("head");
    circBuf.mTail = json.getInt("tail");
    circBuf.mIsFull = json.getBoolean("isFull");
    
    JSONArray dataArray = json.getJSONArray("data");
    for (int i = 0; i < buffLen; i++) {
        circBuf.mBuff[i] = dataArray.getDouble(i);
    }
    
    return circBuf;
}
```

**Data Size Estimate:**
- Each double: ~8 bytes
- Metadata: ~50 bytes
- **ML History (120 samples)**: ~1 KB
- **Signal Strength (2,880 samples)**: ~23 KB
- **Battery (17,280 samples × 2)**: ~277 KB
- **Total per save**: ~**301 KB**

### 2. **Create Persistence Manager**

#### Complexity: **Medium**

Create a new class to manage saving/loading of buffer history:

```java
public class CircBufPersistenceManager {
    private static final String PREFS_NAME = "CircBufHistory";
    private static final String KEY_PSEIZURE_HIST = "pseizure_history";
    private static final String KEY_WATCH_SIGNAL = "watch_signal_history";
    private static final String KEY_WATCH_BATT = "watch_battery_history";
    private static final String KEY_PHONE_BATT = "phone_battery_history";
    private static final String KEY_LAST_SAVE_TIME = "last_save_time";
    
    private Context mContext;
    private SharedPreferences mPrefs;
    
    public CircBufPersistenceManager(Context context) {
        mContext = context;
        mPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
    
    /**
     * Save all CircBuf histories
     */
    public void saveHistories(SdData sdData) {
        try {
            SharedPreferences.Editor editor = mPrefs.edit();
            
            editor.putString(KEY_PSEIZURE_HIST, 
                sdData.mPseizureHistBuf.toJSON().toString());
            editor.putString(KEY_WATCH_SIGNAL, 
                sdData.watchSignalStrengthBuff.toJSON().toString());
            editor.putString(KEY_WATCH_BATT, 
                sdData.watchBattBuff.toJSON().toString());
            editor.putString(KEY_PHONE_BATT, 
                sdData.phoneBattBuff.toJSON().toString());
            editor.putLong(KEY_LAST_SAVE_TIME, System.currentTimeMillis());
            
            editor.apply(); // Async save
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to save CircBuf histories: " + e.getMessage());
        }
    }
    
    /**
     * Load all CircBuf histories
     */
    public void loadHistories(SdData sdData) {
        try {
            long lastSaveTime = mPrefs.getLong(KEY_LAST_SAVE_TIME, 0);
            long timeSinceLastSave = System.currentTimeMillis() - lastSaveTime;
            
            // Only restore if saved within last 48 hours (data still relevant)
            if (timeSinceLastSave < 48 * 3600 * 1000) {
                String pseizureJson = mPrefs.getString(KEY_PSEIZURE_HIST, null);
                if (pseizureJson != null) {
                    sdData.mPseizureHistBuf = CircBuf.fromJSON(
                        new JSONObject(pseizureJson));
                }
                
                // Similar for other buffers...
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to load CircBuf histories: " + e.getMessage());
        }
    }
}
```

**Alternative: Use Files Instead of SharedPreferences**
- For large data (>1MB), files are better than SharedPreferences
- Could use internal storage: `context.getFilesDir()`
- Save as JSON or binary format

### 3. **Integrate into App Lifecycle**

#### Complexity: **Medium**

Modify `SdServer` to save/load histories:

```java
// In SdServer.java

private CircBufPersistenceManager mHistoryManager;

@Override
public void onCreate() {
    super.onCreate();
    mHistoryManager = new CircBufPersistenceManager(this);
    
    mSdData = new SdData();
    
    // Restore histories after creating SdData
    mHistoryManager.loadHistories(mSdData);
}

@Override
public void onDestroy() {
    // Save histories before shutting down
    if (mSdData != null) {
        mHistoryManager.saveHistories(mSdData);
    }
    super.onDestroy();
}

// Also save periodically (e.g., every 5 minutes)
private void schedulePeriodicSave() {
    mHandler.postDelayed(new Runnable() {
        @Override
        public void run() {
            if (mSdData != null) {
                mHistoryManager.saveHistories(mSdData);
            }
            mHandler.postDelayed(this, 5 * 60 * 1000); // 5 minutes
        }
    }, 5 * 60 * 1000);
}
```

### 4. **Handle Edge Cases**

#### Complexity: **Medium-High**

Important considerations:

**a) Data Staleness**
- If app was off for days, old battery data is meaningless
- Solution: Check timestamp, only restore if recent (e.g., <48 hours)

**b) Format Changes**
- If CircBuf size changes between app versions
- Solution: Version the saved data, migrate or discard incompatible data

**c) Corrupted Data**
- JSON parsing failures, incomplete saves
- Solution: Try-catch with fallback to fresh buffers

**d) Memory Pressure**
- Loading 300KB+ on startup
- Solution: Load asynchronously, show "Loading history..." indicator

**e) Migration from Old Versions**
- Users upgrading from non-persistent versions
- Solution: Gracefully handle missing data

**f) Background Termination**
- Android can kill app without calling onDestroy()
- Solution: Save periodically (every 5-10 minutes), not just on destroy

## Implementation Effort Estimate

### Time Breakdown

1. **CircBuf serialization** (toJSON/fromJSON): 2-3 hours
   - Write methods
   - Write unit tests
   - Handle edge cases

2. **CircBufPersistenceManager**: 3-4 hours
   - Design storage strategy
   - Implement save/load
   - Add timestamp checking
   - Error handling

3. **SdServer integration**: 2-3 hours
   - Hook into lifecycle
   - Add periodic saves
   - Background thread handling

4. **Testing**: 4-6 hours
   - Unit tests for CircBuf serialization
   - Integration tests for persistence
   - Test various restart scenarios
   - Test with low storage/corrupted data
   - Test migration from old versions

5. **Edge case handling**: 2-3 hours
   - Data staleness logic
   - Version compatibility
   - Crash recovery

**Total: 13-19 hours** of focused development time

### Complexity Rating: **Medium** (6/10)

Not overly complex, but requires careful attention to:
- Data integrity
- Performance (don't block UI thread)
- Battery impact (don't save too frequently)
- Storage management
- Backwards compatibility

## Alternative Approaches

### Option 1: SharedPreferences (Chosen Above)
**Pros:**
- Simple API
- Handles atomic writes
- Built-in Android component

**Cons:**
- Not ideal for large data (>1MB total)
- Loaded into memory entirely
- XML-based, somewhat inefficient

### Option 2: Internal Storage (JSON Files)
**Pros:**
- Better for large data
- Can stream read/write
- More flexible

**Cons:**
- Manual file management
- Need to handle file corruption
- More code required

### Option 3: SQLite Database
**Pros:**
- Excellent for structured data
- Query capabilities
- Efficient for large datasets
- Incremental saves (row-by-row)

**Cons:**
- Overkill for this use case
- More complex setup
- Harder to debug

### Option 4: Room Database (Modern SQLite)
**Pros:**
- Type-safe SQL queries
- LiveData integration
- Migration support
- Google recommended

**Cons:**
- Significant architecture change
- Learning curve
- Most complex option

### Recommendation
Start with **Option 1 (SharedPreferences)** for ML history (small), 
use **Option 2 (JSON Files)** for battery/signal history (large).

This hybrid approach balances simplicity and efficiency.

## Benefits of Implementation

### User Experience
✅ Charts show historical data immediately on app restart
✅ Trend analysis across app sessions
✅ Better understanding of patterns
✅ No "blank slate" after every restart

### Medical Value
✅ Continuous monitoring data even with app restarts
✅ Better seizure pattern detection over time
✅ More reliable for clinical review

### Performance
✅ Only ~300KB storage (negligible on modern phones)
✅ Fast load times (<100ms)
✅ No impact on ongoing monitoring

## Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Data corruption | Medium | Versioning, validation, fallback to empty |
| Storage exhaustion | Low | Regular cleanup, size limits |
| Battery drain | Low | Save periodically, not continuously |
| Load delays | Low | Async loading with progress indicator |
| Migration issues | Medium | Graceful degradation, version checks |

## Conclusion

Making CircBuf history persistent is **definitely achievable** and **not overly complicated**. 

The core work involves:
1. Adding 2 methods to CircBuf (toJSON/fromJSON) - **straightforward**
2. Creating a persistence manager - **moderate effort**
3. Hooking into lifecycle - **well-defined integration points**

The main complexity lies in **handling edge cases gracefully** rather than the core functionality.

**Recommendation:** Implement this feature. The user benefit is high, and the technical risk is manageable. Start with ML history (smallest dataset) as a proof-of-concept, then extend to battery and signal strength.
