package uk.org.openseizuredetector.activity.main;
import uk.org.openseizuredetector.R;

import uk.org.openseizuredetector.data.SdData;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.os.Bundle;
import uk.org.openseizuredetector.data.logging.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import java.util.Locale;

import androidx.preference.PreferenceManager;
import uk.org.openseizuredetector.alg.MlModelManager;

public class FragmentMlAlg extends FragmentOsdBaseClass {
    String TAG = "FragmentMlAlg";
    private GraphView historyChart;

    // Define colors for up to 6 models in the graph
    private final int[] SERIES_COLORS = {
        Color.BLUE, Color.RED, Color.GREEN, Color.MAGENTA, Color.CYAN, Color.parseColor("#FF8C00") // Orange
    };
    private static final int STDDEV_COLOR = Color.rgb(180, 180, 180);

    public FragmentMlAlg() {
        // Required empty public constructor
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_ml_alg, container, false);
        historyChart = rootView.findViewById(R.id.seizure_history_chart);
        setupChart();
        adjustChartHeightForMode(historyChart);
        return rootView;
    }

    @Override
    protected void updateUi() {
        if (isBasicMode()) {
            return;
        }
        if (mRootView == null || !mConnection.mBound || mConnection.mSdServer == null) {
            return;
        }

        try {
            SdData sdData = mConnection.mSdServer.mSdData;

            // Header row: "N models active | AccStDev: X%"
            TextView modelStatusTv = mRootView.findViewById(R.id.fragment_ml_alg_model_name);
            String statusText = String.format(Locale.getDefault(), "%d model(s) active  |  AccStDev: %.1f%%", 
                                            sdData.mlNumModels, sdData.mAccelMagStdDev);
            modelStatusTv.setText(statusText);

            // Update the 2-column grid of model statuses
            updateIndividualModelDisplay(sdData);

            // Update history chart with multiple model lines
            displayHistoryChart(sdData);

        } catch (Exception e) {
            Log.e(TAG, "Error updating UI: " + e.getMessage(), e);
        }
    }

    @Override
    protected void updateUiOnNewData() {
        updateUi();
    }

    @Override
    protected void updateUiFast() {
        // ML graph updates are tied to new data to avoid flicker.
    }

    private void updateIndividualModelDisplay(SdData sdData) {
        if (isBasicMode()) {
            return;
        }
        GridLayout grid = mRootView.findViewById(R.id.individual_models_grid);
        if (grid == null) return;

        grid.removeAllViews();

        for (int i = 0; i < sdData.mlNumModels && i < 6; i++) {
            if (!sdData.mlModelActive[i]) continue;

            // Create a compact vertical layout for each model cell
            android.widget.LinearLayout cell = new android.widget.LinearLayout(mContext);
            cell.setOrientation(android.widget.LinearLayout.VERTICAL);
            cell.setPadding(8, 4, 8, 4);
            
            // Header: "ML1 (OK)"
            TextView headerView = new TextView(mContext);
            String statusText = getStatusText(sdData.mlModelStates[i]);
            headerView.setText(String.format(Locale.getDefault(), "%s (%s)", sdData.mlModelNames[i], statusText));
            headerView.setTextColor(okTextColour);
            headerView.setTextSize(12);
            headerView.setTypeface(null, android.graphics.Typeface.BOLD);
            cell.addView(headerView);

            // Probability: "15.2%"
            TextView probView = new TextView(mContext);
            float probPc = (float) (sdData.mlModelProbs[i] * 100.0);
            probView.setText(String.format(Locale.getDefault(), "%.1f%%", probPc));
            probView.setTextColor(okTextColour);
            probView.setTextSize(11);
            cell.addView(probView);

            // Mini Progress Bar
            ProgressBar pb = new ProgressBar(mContext, null, android.R.attr.progressBarStyleHorizontal);
            pb.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 12));
            pb.setMax(100);
            pb.setProgress((int) probPc);
            pb.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#EEEEEE")));
            
            // Set bar color based on probability
            if (probPc > 50) pb.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.RED));
            else if (probPc > 30) pb.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.YELLOW));
            else pb.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.BLUE));
            
            cell.addView(pb);

            // Add cell to grid with weighted layout to ensure 2 columns
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            params.width = 0;
            cell.setLayoutParams(params);
            
            grid.addView(cell);
        }
    }

    private String getStatusText(int state) {
        switch (state) {
            case 2: return "ALARM";
            case 1: return "WARN";
            default: return "OK";
        }
    }

    private void setupChart() {
        if (historyChart == null) return;

        historyChart.getViewport().setYAxisBoundsManual(true);
        historyChart.getViewport().setMinY(0);
        historyChart.getViewport().setMaxY(100);
        historyChart.getViewport().setXAxisBoundsManual(true);
        historyChart.getViewport().setMinX(0);
        historyChart.getViewport().setMaxX(600); 
        historyChart.getViewport().setScalable(true);
        historyChart.getViewport().setScrollable(true);

        historyChart.getGridLabelRenderer().setNumHorizontalLabels(6);
        historyChart.getGridLabelRenderer().setNumVerticalLabels(6);   
        historyChart.getGridLabelRenderer().setHumanRounding(false);

        historyChart.getGridLabelRenderer().setLabelFormatter(new com.jjoe64.graphview.DefaultLabelFormatter() {
            @Override
            public String formatLabel(double value, boolean isValueX) {
                if (isValueX) {
                    double minutesAgo = (600 - value) / 60.0;
                    double rounded = Math.round(minutesAgo);
                    if (Math.abs(minutesAgo - rounded) < 0.3 && rounded % 2 == 0 && rounded >= 0 && rounded <= 10) {
                        return String.format(Locale.getDefault(), "%d", (int)rounded);
                    }
                    return ""; 
                }
                return super.formatLabel(value, isValueX);
            }
        });

        historyChart.getGridLabelRenderer().setHorizontalLabelsColor(okTextColour);
        historyChart.getGridLabelRenderer().setVerticalLabelsColor(okTextColour);
        historyChart.getGridLabelRenderer().setGridColor(Color.GRAY);
        historyChart.getGridLabelRenderer().setHorizontalAxisTitleColor(okTextColour);
        historyChart.getGridLabelRenderer().setHorizontalAxisTitle("Time (minutes ago)");

        // Disable GraphView's built-in legend due to sizing issues
        // We use a custom legend below instead
        historyChart.getLegendRenderer().setVisible(false);
    }

    private void displayHistoryChart(SdData sdData) {
        if (isBasicMode()) {
            return;
        }
        try {
            if (historyChart == null) return;

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
            double probThresholdPct = getPercentPreference(
                    prefs,
                    MlModelManager.PREF_ML_SEIZURE_PROB_THRESHOLD_PCT,
                    MlModelManager.DEFAULT_ML_SEIZURE_PROB_THRESHOLD_PCT);
            double stdThresholdPct = getPercentPreference(
                    prefs,
                    MlModelManager.PREF_ML_ACCEL_STD_THRESHOLD_PCT,
                    MlModelManager.DEFAULT_ML_ACCEL_STD_THRESHOLD_PCT);

            // Clear existing series to redraw with updated history
            historyChart.removeAllSeries();

            int probThresholdColor = getPrimaryProbabilityColor(sdData);

            // Draw threshold overlays first so measured traces remain visually dominant.
            historyChart.addSeries(createThresholdSeries(probThresholdPct, probThresholdColor, "Prob Thresh"));
            historyChart.addSeries(createThresholdSeries(stdThresholdPct, STDDEV_COLOR, "Std Thresh"));

            // 1. Draw Acceleration StdDev as a faint background line
            double[] accelStdDevData = mConnection.mSdServer.mSdDataHistory.mAccelMagStdDevHistBuf.getVals();
            if (accelStdDevData != null && accelStdDevData.length > 0) {
                DataPoint[] accelDataPoints = new DataPoint[accelStdDevData.length];
                int validAccelPoints = 0;
                for (int i = 0; i < accelStdDevData.length; i++) {
                    if (accelStdDevData[i] >= 0.0) {
                        accelDataPoints[validAccelPoints] = new DataPoint(i * 5.0, accelStdDevData[i]);
                        validAccelPoints++;
                    }
                }
                if (validAccelPoints > 0) {
                    DataPoint[] validAccelDataPoints = new DataPoint[validAccelPoints];
                    System.arraycopy(accelDataPoints, 0, validAccelDataPoints, 0, validAccelPoints);
                    LineGraphSeries<DataPoint> accelSeries = new LineGraphSeries<>(validAccelDataPoints);
                    accelSeries.setColor(Color.argb(100, Color.red(STDDEV_COLOR), Color.green(STDDEV_COLOR), Color.blue(STDDEV_COLOR))); // Very faint gray
                    accelSeries.setThickness(2);
                    accelSeries.setTitle("Std");
                    historyChart.addSeries(accelSeries);
                }
            }

            // 2. Draw lines for each ML model using the new history buffs
            boolean addedAnyModelSeries = false;
            int maxModels = Math.min(5, sdData.mlNumModels);
            for (int i = 0; i < maxModels; i++) {
                // Only include models that are marked active
                if (sdData.mlModelActive == null || i >= sdData.mlModelActive.length || !sdData.mlModelActive[i]) continue;

                double[] modelHistory = mConnection.mSdServer.mSdDataHistory.mlModelProbBuffs[i].getVals();
                if (modelHistory != null && modelHistory.length > 0) {
                    DataPoint[] modelPoints = new DataPoint[modelHistory.length];
                    int validPoints = 0;
                    for (int j = 0; j < modelHistory.length; j++) {
                        if (modelHistory[j] >= 0.0) {
                            modelPoints[validPoints] = new DataPoint(j * 5.0, modelHistory[j] * 100.0);
                            validPoints++;
                        }
                    }
                    if (validPoints > 0) {
                        DataPoint[] validPointsArr = new DataPoint[validPoints];
                        System.arraycopy(modelPoints, 0, validPointsArr, 0, validPoints);
                        LineGraphSeries<DataPoint> series = new LineGraphSeries<>(validPointsArr);
                        series.setColor(SERIES_COLORS[i % SERIES_COLORS.length]);
                        series.setThickness(4);
                        // Prefer descriptive name if available, else fallback to MLx
                        String title = (sdData.mlModelNames != null && i < sdData.mlModelNames.length && sdData.mlModelNames[i] != null && !sdData.mlModelNames[i].isEmpty())
                                ? sdData.mlModelNames[i]
                                : ("ML" + (i + 1));
                        series.setTitle(title);
                        historyChart.addSeries(series);
                        addedAnyModelSeries = true;
                    }
                }
            }

            // Show legend only if we added at least one model series (movement line alone doesn't require legend)
            // Instead of using GraphView's broken legend, we use a custom legend
            if (addedAnyModelSeries) {
                updateCustomLegend(sdData);
            } else {
                clearCustomLegend();
            }

        } catch (Exception e) {
            Log.e(TAG, "Error updating chart: " + e.getMessage(), e);
        }
    }

    /**
     * Updates the custom legend display with model names and colored indicators
     * This replaces GraphView's built-in legend which has sizing issues
     */
    private void updateCustomLegend(SdData sdData) {
        if (isBasicMode()) {
            return;
        }
        if (mRootView == null) return;

        android.widget.LinearLayout legendLayout = mRootView.findViewById(R.id.custom_legend_layout);
        if (legendLayout == null) return;

        legendLayout.removeAllViews();

        // Add acceleration StdDev indicator first
        addLegendItem(legendLayout, Color.argb(100, Color.red(STDDEV_COLOR), Color.green(STDDEV_COLOR), Color.blue(STDDEV_COLOR)), "Std", false);

        // Add colored square + model name for each active model
        int maxModels = Math.min(5, sdData.mlNumModels);
        for (int i = 0; i < maxModels; i++) {
            if (sdData.mlModelActive == null || i >= sdData.mlModelActive.length || !sdData.mlModelActive[i]) {
                continue;
            }

            String modelName = (sdData.mlModelNames != null && i < sdData.mlModelNames.length &&
                    sdData.mlModelNames[i] != null && !sdData.mlModelNames[i].isEmpty())
                    ? sdData.mlModelNames[i]
                    : ("ML" + (i + 1));
            addLegendItem(legendLayout, SERIES_COLORS[i % SERIES_COLORS.length], modelName, false);
        }

        // Threshold entries are listed after primary parameters/series.
        addLegendItem(legendLayout, getPrimaryProbabilityColor(sdData), "Prob Thresh", true);
        addLegendItem(legendLayout, STDDEV_COLOR, "Std Thresh", true);
    }

    private int getPrimaryProbabilityColor(SdData sdData) {
        int maxModels = Math.min(5, sdData.mlNumModels);
        for (int i = 0; i < maxModels; i++) {
            if (sdData.mlModelActive != null && i < sdData.mlModelActive.length && sdData.mlModelActive[i]) {
                return SERIES_COLORS[i % SERIES_COLORS.length];
            }
        }
        return SERIES_COLORS[0];
    }

    private LineGraphSeries<DataPoint> createThresholdSeries(double yValue, int baseColor, String title) {
        LineGraphSeries<DataPoint> thresholdSeries = new LineGraphSeries<>(new DataPoint[] {
                new DataPoint(0, yValue),
                new DataPoint(600, yValue)
        });
        thresholdSeries.setTitle(title);
        thresholdSeries.setColor(Color.argb(96, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)));

        Paint dashedPaint = new Paint();
        dashedPaint.setStyle(Paint.Style.STROKE);
        dashedPaint.setStrokeWidth(3f);
        dashedPaint.setColor(Color.argb(96, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)));
        dashedPaint.setPathEffect(new DashPathEffect(new float[]{14f, 10f}, 0f));
        thresholdSeries.setCustomPaint(dashedPaint);
        return thresholdSeries;
    }

    private double getPercentPreference(SharedPreferences prefs, String key, String fallback) {
        String raw = prefs.getString(key, fallback);
        try {
            double parsed = Double.parseDouble(raw);
            if (parsed < 0.0) return 0.0;
            if (parsed > 100.0) return 100.0;
            return parsed;
        } catch (Exception ex) {
            Log.w(TAG, "Invalid threshold preference for " + key + "='" + raw + "', using " + fallback);
            try {
                return Double.parseDouble(fallback);
            } catch (Exception ignored) {
                return 0.0;
            }
        }
    }

    private void addLegendItem(android.widget.LinearLayout legendLayout, int color, String label, boolean dashed) {
        android.widget.LinearLayout legendItem = new android.widget.LinearLayout(mContext);
        legendItem.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        legendItem.setGravity(android.view.Gravity.CENTER_VERTICAL);
        legendItem.setPadding(12, 4, 12, 4);

        android.view.View indicator = new android.view.View(mContext);
        android.widget.LinearLayout.LayoutParams indicatorParams = new android.widget.LinearLayout.LayoutParams(24, 24);
        indicatorParams.rightMargin = 8;
        indicator.setLayoutParams(indicatorParams);
        if (dashed) {
            android.graphics.drawable.GradientDrawable dashedBox = new android.graphics.drawable.GradientDrawable();
            dashedBox.setColor(Color.TRANSPARENT);
            dashedBox.setStroke(3, color, 8f, 4f);
            indicator.setBackground(dashedBox);
        } else {
            indicator.setBackgroundColor(color);
        }
        legendItem.addView(indicator);

        TextView textView = new TextView(mContext);
        textView.setText(label);
        textView.setTextSize(12);
        textView.setTextColor(okTextColour);
        legendItem.addView(textView);

        legendLayout.addView(legendItem);
    }

    /**
     * Clears the custom legend display
     */
    private void clearCustomLegend() {
        if (isBasicMode()) {
            return;
        }
        if (mRootView == null) return;
        android.widget.LinearLayout legendLayout = mRootView.findViewById(R.id.custom_legend_layout);
        if (legendLayout != null) {
            legendLayout.removeAllViews();
        }
    }

    private void adjustChartHeightForMode(GraphView chart) {
        if (chart == null || isBasicMode()) {
            return;
        }
        ViewGroup.LayoutParams params = chart.getLayoutParams();
        if (params == null || params.height <= 0) {
            return;
        }
        int shrinkPx = Math.round(20 * getResources().getDisplayMetrics().density);
        params.height = Math.max(params.height - shrinkPx, Math.round(180 * getResources().getDisplayMetrics().density));
        chart.setLayoutParams(params);
    }
}
