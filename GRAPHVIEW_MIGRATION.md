# GraphView Chart Migration Summary

## Date: January 31, 2026 (Updated)

## Overview
Migrated FragmentMlAlg from Vico charting library to GraphView due to compilation errors and complexity issues with Vico's initialization. Subsequently enhanced with time-based x-axis and proper buffer initialization.

## Changes Made

### 1. Dependencies Updated (app/build.gradle)
- **Removed**: Vico charting library dependencies
  - `com.patrykandpatrick.vico:core:1.13.0`
  - `com.patrykandpatrick.vico:views:1.13.0`
- **Added**: GraphView library
  - `com.jjoe64:graphview:4.2.2`

### 2. Java Code Updated (FragmentMlAlg.java)
- **Replaced imports**:
  - Removed Vico imports (ChartView, ChartEntryModelProducer, FloatEntry, etc.)
  - Added GraphView imports (GraphView, DataPoint, LineGraphSeries)
  
- **Updated fields**:
  - Removed: `ChartView historyChartView` and `ChartEntryModelProducer`
  - Added: `GraphView historyChart`
  
- **Simplified initialization**:
  - No longer need complex ChartEntryModelProducer initialization in onCreate()
  - Chart setup is straightforward with GraphView API
  
- **Updated chart methods**:
  - `setupChart()`: Now uses GraphView's viewport and grid label renderer
  - `displayHistoryChart()`: Creates DataPoint arrays and LineGraphSeries directly

### 3. Layout Updated (fragment_ml_alg.xml)
- **Changed chart view type**:
  - From: `<com.patrykandpatrick.vico.views.chart.ChartView>`
  - To: `<com.jjoe64.graphview.GraphView>`

## Benefits of GraphView

1. **Simpler API**: No complex initialization required
2. **Better maintained**: Active development and bug fixes
3. **Lightweight**: Smaller library size
4. **Stable**: Well-tested with fewer breaking changes
5. **Easy data updates**: Direct DataPoint array manipulation

## Chart Features Implemented

- **Y-axis range**: 0-100% (seizure probability)
- **X-axis**: Time-based display showing "minutes ago" (0-10 minutes)
- **Viewport**: Automatically scales to show full 10 minutes of data
- **Sample rate**: 5 seconds per sample (120 samples = 10 minutes)
- **Scrollable and scalable**: User can zoom and pan
- **Colored line**: Uses app's okTextColor with fallback to blue
- **Axis labels**: "Time (minutes ago)" and "Seizure Probability (%)"
- **Initial data**: Buffer pre-initialized with zeros for immediate display
- **Real-time updates**: Chart refreshes as new seizure probability data arrives

## Recent Enhancements (Time-Based X-Axis)

### 1. Circular Buffer Initialization (SdData.java)
- **Changed error value**: From `Double.NaN` to `-1.0` for better filtering
- **Pre-filled buffer**: Initializes all 120 slots with zeros (0.0) in constructor
- **Benefit**: Chart displays immediately with baseline data instead of being empty

### 2. Time-Based X-Axis (FragmentMlAlg.java)
- **X-axis units**: Changed from sample indices to seconds (0-600)
- **Label formatting**: Custom formatter displays "minutes ago" (0.0 to 10.0)
- **Data mapping**: Each sample maps to its actual time: `sample_index * 5 seconds`
- **Viewport bounds**: Set to 0-600 seconds (10 minutes) by default
- **Reading order**: Oldest data (10 min ago) on left, newest (now) on right

### 3. Data Point Creation
- **Time calculation**: `timeInSeconds = sample_index * 5.0`
- **Valid data filter**: Accepts values >= 0.0 (includes zeros)
- **Error filtering**: Filters out -1.0 error values
- **No gaps**: Continuous line from initialization onwards

## Build Status

✅ Build successful
✅ No compilation errors
✅ Only minor warnings (string literals, deprecated PreferenceManager, etc.)
✅ APK installed successfully on device

## Testing Notes

The chart should now display:
1. Model name at the top
2. Current seizure probability as a progress bar
3. Historical seizure probability over the last 10 minutes as a line graph
4. X-axis showing "minutes ago" from 10.0 (left) to 0.0 (right)
5. Y-axis showing percentage from 0% to 100%
6. Baseline of zeros immediately after app startup
7. Real-time updates as new seizure probability values are calculated
8. Proper color coding (blue/yellow/red based on probability)

## User Experience Improvements

### Before
- Chart was empty until data accumulated
- X-axis showed meaningless sample indices (0, 1, 2, 3...)
- Difficult to understand time relationship
- Required mental calculation to determine "how long ago"

### After
- Chart shows baseline immediately (no empty graph)
- X-axis shows intuitive "minutes ago" (0.0 to 10.0)
- Easy to see temporal trends
- Full 10-minute window always visible
- Natural reading: older data on left, newer on right

## Future Improvements

1. Consider fixing deprecation warnings (PreferenceManager, string literals)
2. ~~Add time-based x-axis labels instead of sample indices~~ ✅ COMPLETED
3. Add legend or annotations for threshold levels
4. Consider adding multiple series (e.g., raw data vs smoothed)
5. Add horizontal threshold lines at warning/alarm levels (e.g., 30%, 50%)
