# ML Chart Time-Based X-Axis Update

## Date: January 31, 2026

## Summary
Enhanced the FragmentMlAlg seizure probability chart with a time-based x-axis and pre-initialized circular buffer for better user experience.

## Changes Made

### 1. SdData.java - Circular Buffer Initialization
**File**: `/app/src/main/java/uk/org/openseizuredetector/SdData.java`

**Changes**:
- Changed error value from `Double.NaN` to `-1.0` (easier to filter)
- Pre-initialize buffer with 120 zeros in constructor
- Provides immediate baseline chart display on app startup

**Code**:
```java
public CircBuf mPseizureHistBuf = new CircBuf(120, -1.0);

public SdData() {
    // ... existing initialization ...
    
    // Initialize the history buffer with zeros so we have initial data for the chart
    for (int i = 0; i < 120; i++) {
        mPseizureHistBuf.add(0.0);
    }
}
```

### 2. FragmentMlAlg.java - Time-Based X-Axis
**File**: `/app/src/main/java/uk/org/openseizuredetector/FragmentMlAlg.java`

#### Changes to `setupChart()`:
- Set X-axis bounds to 0-600 seconds (10 minutes)
- Added custom label formatter to display "minutes ago"
- Changed horizontal axis title from "Time (samples)" to "Time (minutes ago)"

**Key code**:
```java
// Set X-axis to show full 10 minutes
historyChart.getViewport().setXAxisBoundsManual(true);
historyChart.getViewport().setMinX(0);
historyChart.getViewport().setMaxX(600); // 10 minutes in seconds

// Format x-axis labels to show minutes
historyChart.getGridLabelRenderer().setLabelFormatter(new DefaultLabelFormatter() {
    @Override
    public String formatLabel(double value, boolean isValueX) {
        if (isValueX) {
            // Convert seconds to minutes, and show "minutes ago" (inverted)
            double minutesAgo = (600 - value) / 60.0;
            return String.format("%.1f", minutesAgo);
        } else {
            return super.formatLabel(value, isValueX);
        }
    }
});
```

#### Changes to `displayHistoryChart()`:
- Calculate time in seconds for each data point (index × 5 seconds)
- Filter accepts values >= 0.0 (includes zeros)
- Only filters out -1.0 error values

**Key code**:
```java
for (int i = 0; i < historyData.length; i++) {
    double timeInSeconds = i * 5.0; // 5 seconds per sample
    
    // Don't filter out zeros - they're valid initial values
    // Only filter out the error value (-1.0)
    if (historyData[i] >= 0.0) {
        dataPoints[validPoints] = new DataPoint(timeInSeconds, historyData[i] * 100.0);
        validPoints++;
    }
}
```

## How It Works

### Time Mapping
- **Sample rate**: 5 seconds per sample
- **Buffer size**: 120 samples
- **Total time**: 120 × 5 = 600 seconds = 10 minutes

### X-Axis Display
- **Scale**: 0 to 600 seconds
- **Labels**: Show "minutes ago" from 0.0 to 10.0
- **Reading order**: 
  - Left side (0 seconds) = 10 minutes ago
  - Right side (600 seconds) = now (0 minutes ago)

### Data Flow
1. App starts → Buffer filled with 120 zeros
2. Chart displays baseline immediately (flat line at 0%)
3. Every 5 seconds, new seizure probability added to buffer
4. Oldest value drops off, newest appears on right
5. Chart updates in real-time

## Benefits

### User Experience
- ✅ **Immediate feedback**: Chart shows data from app startup
- ✅ **Intuitive time display**: "minutes ago" is easier to understand than sample indices
- ✅ **Full context**: Always shows complete 10-minute window
- ✅ **Natural reading**: Time flows left (past) to right (present)

### Technical
- ✅ **No NaN handling needed**: Zeros are valid display values
- ✅ **Consistent viewport**: Always shows 0-600 second range
- ✅ **Smooth updates**: New data seamlessly appears as time advances

## Testing Results
- ✅ Build successful
- ✅ APK installed on device (SM-A166B)
- ✅ No compilation errors
- ✅ 16KB alignment maintained

## Related Files
- `GRAPHVIEW_MIGRATION.md` - Full migration documentation
- `FragmentMlAlg.java` - Chart display fragment
- `SdData.java` - Data model with circular buffer
- `SdDataSource.java` - Adds seizure probability to buffer every 5 seconds
