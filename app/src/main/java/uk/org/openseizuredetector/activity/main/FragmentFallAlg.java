package uk.org.openseizuredetector.activity.main;

import uk.org.openseizuredetector.R;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.os.Bundle;
import uk.org.openseizuredetector.data.logging.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;
import com.jjoe64.graphview.series.PointsGraphSeries;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;


/**
 * FragmentFallAlg - Debug UI tab for the fall detection algorithm.
 *
 * Displays a 10-minute rolling graph of:
 *   - Window min acceleration (solid accent-colour line) – the lowest acceleration seen in
 *     any sliding window during each analysis period.  Values near/below FallThreshMin
 *     indicate a free-fall phase.
 *   - Window max acceleration (solid red line) – the highest acceleration seen in any
 *     sliding window.  Values near/above FallThreshMax indicate an impact phase.
 *   - FallThreshMin (dashed accent-colour line) – the configured free-fall threshold.
 *   - FallThreshMax (dashed red line) – the configured impact threshold.
 *   - Fall event markers (orange circles) – large dots plotted at the FallThreshMax level
 *     at times when a fall alarm was raised, making alarm events easy to spot.
 */
public class FragmentFallAlg extends FragmentOsdBaseClass {
    private static final String TAG = "FragmentFallAlg";

    /** X-axis span in seconds (10 minutes = 120 samples × 5 s/sample). */
    private static final double X_MAX_SECONDS = 600.0;

    private GraphView mLineChart;
    private TextView tvStatus;
    private TextView tvWindowMin;
    private TextView tvWindowMax;

    public FragmentFallAlg() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_fall_alg, container, false);
        mLineChart = rootView.findViewById(R.id.fall_line_chart);
        setupChart();
        adjustChartHeightForMode(mLineChart);
        return rootView;
    }

    // -------------------------------------------------------------------------
    // Chart initialisation
    // -------------------------------------------------------------------------

    private void setupChart() {
        if (mLineChart == null) {
            Log.w(TAG, "Chart view is null");
            return;
        }

        // Y-axis: fixed range 0–6000 mg so the max threshold line (default 2000 mg) is always
        // clearly visible with headroom above it for impact spikes.
        mLineChart.getViewport().setYAxisBoundsManual(true);
        mLineChart.getViewport().setMinY(0);
        mLineChart.getViewport().setMaxY(6000);

        // X-axis: 0–600 s (10 minutes).
        mLineChart.getViewport().setXAxisBoundsManual(true);
        mLineChart.getViewport().setMinX(0);
        mLineChart.getViewport().setMaxX(X_MAX_SECONDS);

        mLineChart.getViewport().setScalable(true);
        mLineChart.getViewport().setScrollable(true);

        mLineChart.getGridLabelRenderer().setNumVerticalLabels(7);
        mLineChart.getGridLabelRenderer().setNumHorizontalLabels(6);
        mLineChart.getGridLabelRenderer().setHumanRounding(false);

        mLineChart.getGridLabelRenderer().setHorizontalLabelsColor(okTextColour);
        mLineChart.getGridLabelRenderer().setVerticalLabelsColor(okTextColour);
        mLineChart.getGridLabelRenderer().setGridColor(Color.GRAY);
        mLineChart.getGridLabelRenderer().setHorizontalAxisTitle("Time (minutes ago)");
        mLineChart.getGridLabelRenderer().setHorizontalAxisTitleColor(okTextColour);

        // Label formatter: X shows "minutes ago", Y shows value as-is.
        mLineChart.getGridLabelRenderer().setLabelFormatter(new com.jjoe64.graphview.DefaultLabelFormatter() {
            @Override
            public String formatLabel(double value, boolean isValueX) {
                if (isValueX) {
                    double secondsAgo = X_MAX_SECONDS - value;
                    double stepSeconds = 120.0; // label every 2 minutes
                    double snapped = Math.round(secondsAgo / stepSeconds) * stepSeconds;
                    if (Math.abs(secondsAgo - snapped) < 1.0 && snapped >= 0 && snapped <= X_MAX_SECONDS) {
                        return String.format(Locale.getDefault(), "%.0f", snapped / 60.0);
                    }
                    return "";
                } else {
                    return super.formatLabel(value, isValueX);
                }
            }
        });

        Log.d(TAG, "Fall chart initialised");
    }

    // -------------------------------------------------------------------------
    // UI update callbacks
    // -------------------------------------------------------------------------

    @Override
    protected void updateUi() {
        if (isBasicMode()) return;

        tvStatus = mRootView.findViewById(R.id.fall_status_tv);
        tvWindowMin = mRootView.findViewById(R.id.fall_window_min_tv);
        tvWindowMax = mRootView.findViewById(R.id.fall_window_max_tv);
        TextView tvHeader = mRootView.findViewById(R.id.fragment_fall_alg_tv1);

        if (!mConnection.mBound) {
            if (tvHeader != null) tvHeader.setText("****NOT BOUND TO SERVER***");
            return;
        }

        if (tvHeader != null) tvHeader.setText("Bound to Server");

        uk.org.openseizuredetector.data.SdData sdData = mConnection.mSdServer.mSdData;

        // Show whether fall detection is currently enabled
        String activeStatus = sdData.mFallActive ? "ACTIVE" : "INACTIVE";
        if (tvHeader != null) tvHeader.setText("Fall detection: " + activeStatus);

        // Read fall thresholds directly from SharedPreferences, the same way SdAlgFall does.
        // sdData.mFallThreshMin/Max are only populated from watch-protocol data and will be
        // zero on Android-only data sources, so we must go to preferences for the real values.
        double threshMin = 500.0;
        double threshMax = 2000.0;
        try {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);
            threshMin = Double.parseDouble(sp.getString("FallThreshMin", "500"));
            threshMax = Double.parseDouble(sp.getString("FallThreshMax", "2000"));
        } catch (Exception e) {
            Log.w(TAG, "Could not parse fall thresholds from preferences, using defaults: " + e.getMessage());
        }

        // Show current per-analysis-period window statistics
        if (tvWindowMin != null) {
            tvWindowMin.setText(sdData.mFallWindowMin >= 0
                    ? String.format(Locale.getDefault(), "%.0f", sdData.mFallWindowMin)
                    : "---");
        }
        if (tvWindowMax != null) {
            tvWindowMax.setText(sdData.mFallWindowMax >= 0
                    ? String.format(Locale.getDefault(), "%.0f", sdData.mFallWindowMax)
                    : "---");
        }

        // Status block: algorithm state + alarm standing flag + thresholds from preferences
        if (tvStatus != null) {
            tvStatus.setText(new StringBuilder()
                    .append("Algorithm state: ").append(sdData.fallAlgState)
                    .append("\nFall alarm standing: ").append(sdData.fallAlarmStanding)
                    .append("\nThresholds  Min=").append((int) threshMin)
                    .append(" mg  Max=").append((int) threshMax).append(" mg")
                    .toString());
        }

        // Refresh graph from history buffers
        int nVals = mConnection.mSdServer.mSdDataHistory.mFallWindowMinBuf.getNumVals();
        double[] minHistory = mConnection.mSdServer.mSdDataHistory.mFallWindowMinBuf.getVals();
        double[] maxHistory = mConnection.mSdServer.mSdDataHistory.mFallWindowMaxBuf.getVals();
        double[] eventHistory = mConnection.mSdServer.mSdDataHistory.mFallEventBuf.getVals();

        if (minHistory != null && maxHistory != null && nVals > 0) {
            displayHistoryChart(minHistory, maxHistory, eventHistory, nVals,
                    threshMin, threshMax);
        }
    }

    @Override
    protected void updateUiOnNewData() {
        updateUi();
    }

    @Override
    protected void updateUiFast() {
        // Graph updates are tied to new data to avoid flicker.
    }

    // -------------------------------------------------------------------------
    // Graph rendering
    // -------------------------------------------------------------------------

    /**
     * Build and display the fall acceleration history graph.
     *
     * Series drawn (in order, so fall-event dots appear on top):
     *  1. Window max acceleration – solid red line
     *  2. Window min acceleration – solid accent-colour line
     *  3. FallThreshMax           – dashed red line
     *  4. FallThreshMin           – dashed accent-colour line
     *  5. Fall event markers      – large orange dots at FallThreshMax height
     *
     * @param minHistory   Array of window-minimum acceleration values
     * @param maxHistory   Array of window-maximum acceleration values
     * @param eventHistory Array of fall-event flags (1.0 = fall detected, 0.0 = none)
     * @param length       Number of valid entries in the arrays
     * @param threshMin    Configured free-fall threshold (FallThreshMin)
     * @param threshMax    Configured impact threshold (FallThreshMax)
     */
    private void displayHistoryChart(double[] minHistory, double[] maxHistory,
                                     double[] eventHistory, int length,
                                     double threshMin, double threshMax) {
        if (isBasicMode()) return;
        if (mLineChart == null || length == 0) return;

        try {
            // Resolve accent colour; fall back to blue if the resource is unavailable.
            int accentColour;
            try {
                accentColour = mContext.getResources().getColor(R.color.colorAccent, null);
            } catch (Exception e) {
                accentColour = Color.BLUE;
            }

            // ---- 1 & 2: Window max and min acceleration history lines ----
            DataPoint[] minPoints = new DataPoint[length];
            DataPoint[] maxPoints = new DataPoint[length];
            for (int i = 0; i < length; i++) {
                double t = i * 5.0; // 5 seconds per sample
                minPoints[i] = new DataPoint(t, minHistory[i]);
                maxPoints[i] = new DataPoint(t, maxHistory[i]);
            }

            // Max: solid red
            LineGraphSeries<DataPoint> maxSeries = new LineGraphSeries<>(maxPoints);
            maxSeries.setColor(Color.RED);
            maxSeries.setThickness(3);
            maxSeries.setDrawDataPoints(false);

            // Min: solid accent colour
            LineGraphSeries<DataPoint> minSeries = new LineGraphSeries<>(minPoints);
            minSeries.setColor(accentColour);
            minSeries.setThickness(3);
            minSeries.setDrawDataPoints(false);

            // ---- 3 & 4: Threshold reference lines (dashed) ----
            // FallThreshMax – dashed red
            DataPoint[] threshMaxPoints = {
                    new DataPoint(0, threshMax),
                    new DataPoint(X_MAX_SECONDS, threshMax)
            };
            LineGraphSeries<DataPoint> threshMaxSeries = new LineGraphSeries<>(threshMaxPoints);
            Paint threshMaxPaint = buildDashedPaint(Color.RED, 4f);
            threshMaxSeries.setCustomPaint(threshMaxPaint);

            // FallThreshMin – dashed accent colour
            DataPoint[] threshMinPoints = {
                    new DataPoint(0, threshMin),
                    new DataPoint(X_MAX_SECONDS, threshMin)
            };
            LineGraphSeries<DataPoint> threshMinSeries = new LineGraphSeries<>(threshMinPoints);
            Paint threshMinPaint = buildDashedPaint(accentColour, 4f);
            threshMinSeries.setCustomPaint(threshMinPaint);

            // ---- 5: Fall event markers (orange circles at FallThreshMax level) ----
            // Only include time points where a fall was actually detected.
            // Drawing them at the FallThreshMax y-level places them right on the impact
            // threshold line, making them easy to relate to the thresholds visually.
            List<DataPoint> fallEventList = new ArrayList<>();
            if (eventHistory != null) {
                for (int i = 0; i < Math.min(length, eventHistory.length); i++) {
                    if (eventHistory[i] >= 1.0) {
                        fallEventList.add(new DataPoint(i * 5.0, threshMax));
                    }
                }
            }

            // ---- Commit all series to the chart ----
            mLineChart.removeAllSeries();
            mLineChart.addSeries(maxSeries);
            mLineChart.addSeries(minSeries);
            mLineChart.addSeries(threshMaxSeries);
            mLineChart.addSeries(threshMinSeries);

            // Add fall-event markers only if any events exist
            if (!fallEventList.isEmpty()) {
                DataPoint[] fallEventPoints = fallEventList.toArray(new DataPoint[0]);
                PointsGraphSeries<DataPoint> fallEventSeries = new PointsGraphSeries<>(fallEventPoints);
                fallEventSeries.setColor(Color.parseColor("#FF6600")); // Bright orange
                fallEventSeries.setSize(18f);  // Large visible dots
                fallEventSeries.setShape(PointsGraphSeries.Shape.POINT);
                mLineChart.addSeries(fallEventSeries);
            }

            // Update chart title to reflect the time span currently shown
            float xSpan = (length * 5.0f) / 60.0f;
            mLineChart.setTitle(getString(R.string.fall_accel_history_mg)
                    + String.format(Locale.getDefault(), "%.1f", xSpan) + " minutes");
            mLineChart.setTitleTextSize(36f);
            mLineChart.setTitleColor(okTextColour);

        } catch (Exception e) {
            Log.e(TAG, "Error updating fall chart: " + e.getMessage(), e);
        }
    }

    /**
     * Build a dashed {@link Paint} suitable for a {@link LineGraphSeries#setCustomPaint(Paint)}.
     *
     * @param color       Line colour
     * @param strokeWidth Stroke width in pixels
     * @return Configured Paint with a 10px dash / 6px gap pattern
     */
    private Paint buildDashedPaint(int color, float strokeWidth) {
        Paint paint = new Paint();
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeWidth(strokeWidth);
        paint.setAntiAlias(true);
        paint.setPathEffect(new DashPathEffect(new float[]{10f, 6f}, 0f));
        return paint;
    }

    private void adjustChartHeightForMode(GraphView chart) {
        if (chart == null || isBasicMode()) return;
        ViewGroup.LayoutParams params = chart.getLayoutParams();
        if (params == null || params.height <= 0) return;
        int shrinkPx = Math.round(20 * getResources().getDisplayMetrics().density);
        params.height = Math.max(params.height - shrinkPx,
                Math.round(170 * getResources().getDisplayMetrics().density));
        chart.setLayoutParams(params);
    }
}

