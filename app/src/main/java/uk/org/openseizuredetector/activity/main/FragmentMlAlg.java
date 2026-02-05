package uk.org.openseizuredetector.activity.main;
import uk.org.openseizuredetector.R;

import uk.org.openseizuredetector.data.SdData;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import androidx.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

public class FragmentMlAlg extends FragmentOsdBaseClass {
    String TAG = "FragmentMlAlg";
    private GraphView historyChart;

    public FragmentMlAlg() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View rootView = inflater.inflate(R.layout.fragment_ml_alg, container, false);
        historyChart = rootView.findViewById(R.id.seizure_history_chart);
        setupChart();
        return rootView;
    }

    @Override
    protected void updateUi() {
        Log.d(TAG, "updateUi()");
        TextView tv = (TextView) mRootView.findViewById(R.id.fragment_ml_alg_tv1);

        if (!mConnection.mBound) {
            tv.setText("NOT BOUND TO SERVER");
            return;
        }

        tv.setText("Machine Learning Algorithm Status");

        try {
            if (mConnection.mSdServer == null || mConnection.mSdServer.mSdData == null) {
                return;
            }

            SdData sdData = mConnection.mSdServer.mSdData;

            // Display model name
            String modelName = getSelectedModelName();
            ((TextView) mRootView.findViewById(R.id.fragment_ml_alg_model_name))
                    .setText("Model: " + modelName);

            // Display current seizure probability as progress bar
            long pSeizurePc = (long) (sdData.mPseizure * 100);

            ((TextView) mRootView.findViewById(R.id.fragment_ml_alg_probability_label))
                    .setText(String.format("%s %d%%, AccStDev %.1f%%",
                            getString(R.string.seizure_probability), pSeizurePc, sdData.mAccelMagStdDev));
            ProgressBar pb = ((ProgressBar) mRootView.findViewById(R.id.fragment_ml_alg_progress_bar));
            pb.setMax(100);
            pb.setProgress((int) pSeizurePc);

            // Color code the progress bar based on seizure probability
            Drawable pbDrawable = mContext.getDrawable(R.drawable.progress_bar_blue);
            if (pSeizurePc > 30)
                pbDrawable = mContext.getDrawable(R.drawable.progress_bar_yellow);
            if (pSeizurePc > 50)
                pbDrawable = mContext.getDrawable(R.drawable.progress_bar_red);
            pb.setProgressDrawable(pbDrawable);

            ((TextView) mRootView.findViewById(R.id.fragment_ml_alg_probability_value))
                    .setText(String.format("%.1f%%", sdData.mPseizure * 100));

            // Update history chart
            displayHistoryChart(sdData);

        } catch (Exception e) {
            Log.e(TAG, "Error updating UI: " + e.getMessage(), e);
        }
    }

    private String getSelectedModelName() {
        try {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);
            String selectedModel = sp.getString("CnnModelName", "Bundled Model");
            // Remove file extension and format nicely
            if (selectedModel.contains(".")) {
                selectedModel = selectedModel.substring(0, selectedModel.lastIndexOf("."));
            }
            return selectedModel;
        } catch (Exception e) {
            Log.w(TAG, "Error getting model name: " + e.getMessage());
            return "unknown";
        }
    }

    private void setupChart() {
        if (historyChart == null) {
            Log.w(TAG, "Chart view is null");
            return;
        }

        // Configure chart appearance
        historyChart.getViewport().setYAxisBoundsManual(true);
        historyChart.getViewport().setMinY(0);
        historyChart.getViewport().setMaxY(100);

        // Set X-axis to show full 10 minutes
        // 120 samples * 5 seconds = 600 seconds = 10 minutes
        historyChart.getViewport().setXAxisBoundsManual(true);
        historyChart.getViewport().setMinX(0);
        historyChart.getViewport().setMaxX(600); // 10 minutes in seconds

        historyChart.getViewport().setScalable(true);
        historyChart.getViewport().setScrollable(true);

        // Configure number of tick marks and grid lines
        historyChart.getGridLabelRenderer().setNumHorizontalLabels(6);
        historyChart.getGridLabelRenderer().setNumVerticalLabels(6);   // 0, 20, 40, 60, 80, 100%

        // Ensure labels don't get culled
        historyChart.getGridLabelRenderer().setHumanRounding(false);

        // Format x-axis labels to show minutes - return empty string for intermediate values
        historyChart.getGridLabelRenderer().setLabelFormatter(new com.jjoe64.graphview.DefaultLabelFormatter() {
            @Override
            public String formatLabel(double value, boolean isValueX) {
                if (isValueX) {
                    // Convert seconds to minutes
                    double minutesAgo = (600 - value) / 60.0;

                    // Only show labels at 0, 2, 4, 6, 8, 10 minute marks
                    // Check if we're close to one of these values (within 0.5 minutes)
                    double rounded = Math.round(minutesAgo);
                    if (Math.abs(minutesAgo - rounded) < 0.3 && rounded % 2 == 0 && rounded >= 0 && rounded <= 10) {
                        return String.format("%d", (int)rounded);
                    }
                    return ""; // Hide other labels
                } else {
                    return super.formatLabel(value, isValueX);
                }
            }
        });

        // Set text color for better visibility
        try {
            int textColor = mContext.getResources().getColor(R.color.okTextColor, null);
            historyChart.getGridLabelRenderer().setHorizontalLabelsColor(textColor);
            historyChart.getGridLabelRenderer().setVerticalLabelsColor(textColor);
            historyChart.getGridLabelRenderer().setGridColor(Color.GRAY);

            // Set axis title colors to match labels
            historyChart.getGridLabelRenderer().setHorizontalAxisTitleColor(textColor);
        } catch (Exception e) {
            Log.w(TAG, "Error setting chart colors: " + e.getMessage());
        }

        // Set horizontal axis title only (vertical title takes up too much space and hides the 0 label)
        historyChart.getGridLabelRenderer().setHorizontalAxisTitle("Time (minutes ago)");

        Log.d(TAG, "Chart view initialized");
    }

    private void displayHistoryChart(SdData sdData) {
        try {
            if (historyChart == null) {
                Log.w(TAG, "Chart view is null, cannot display chart");
                return;
            }

            // Get seizure probability history data from circular buffer
            double[] seizureHistoryData = mConnection.mSdServer.mSdDataHistory.mPseizureHistBuf.getVals();

            if (seizureHistoryData == null || seizureHistoryData.length == 0) {
                Log.d(TAG, "No seizure probability history data available yet");
                historyChart.removeAllSeries();
                return;
            }

            // Create data points for seizure probability with time-based x-axis
            DataPoint[] seizureDataPoints = new DataPoint[seizureHistoryData.length];
            int validSeizurePoints = 0;

            for (int i = 0; i < seizureHistoryData.length; i++) {
                double timeInSeconds = i * 5.0; // 5 seconds per sample

                if (seizureHistoryData[i] >= 0.0) {
                    seizureDataPoints[validSeizurePoints] = new DataPoint(timeInSeconds, seizureHistoryData[i] * 100.0);
                    validSeizurePoints++;
                }
            }

            if (validSeizurePoints == 0) {
                Log.d(TAG, "No valid seizure data points in history");
                historyChart.removeAllSeries();
                return;
            }

            // Create seizure probability series with only valid points
            DataPoint[] validSeizureDataPoints = new DataPoint[validSeizurePoints];
            System.arraycopy(seizureDataPoints, 0, validSeizureDataPoints, 0, validSeizurePoints);

            LineGraphSeries<DataPoint> seizureSeries = new LineGraphSeries<>(validSeizureDataPoints);

            try {
                int lineColor = mContext.getResources().getColor(R.color.okTextColor, null);
                seizureSeries.setColor(lineColor);
            } catch (Exception e) {
                seizureSeries.setColor(Color.BLUE);
            }

            seizureSeries.setThickness(3);
            seizureSeries.setDrawDataPoints(false);
            seizureSeries.setDrawBackground(false);

            // Remove old series
            historyChart.removeAllSeries();

            // Add seizure probability series
            historyChart.addSeries(seizureSeries);

            // Get acceleration magnitude standard deviation history data
            double[] accelStdDevData = mConnection.mSdServer.mSdDataHistory.mAccelMagStdDevHistBuf.getVals();

            if (accelStdDevData != null && accelStdDevData.length > 0) {
                // Create data points for acceleration std dev
                DataPoint[] accelDataPoints = new DataPoint[accelStdDevData.length];
                int validAccelPoints = 0;

                for (int i = 0; i < accelStdDevData.length; i++) {
                    double timeInSeconds = i * 5.0; // 5 seconds per sample

                    if (accelStdDevData[i] >= 0.0) {
                        accelDataPoints[validAccelPoints] = new DataPoint(timeInSeconds, accelStdDevData[i]);
                        validAccelPoints++;
                    }
                }

                if (validAccelPoints > 0) {
                    // Create acceleration std dev series with only valid points
                    DataPoint[] validAccelDataPoints = new DataPoint[validAccelPoints];
                    System.arraycopy(accelDataPoints, 0, validAccelDataPoints, 0, validAccelPoints);

                    LineGraphSeries<DataPoint> accelSeries = new LineGraphSeries<>(validAccelDataPoints);

                    // Set faint color for acceleration std dev (gray or light color)
                    accelSeries.setColor(Color.argb(180, 150, 150, 150)); // Light gray with slight transparency

                    accelSeries.setThickness(1); // Thinner line to make it less prominent
                    accelSeries.setDrawDataPoints(false);
                    accelSeries.setDrawBackground(false);

                    // Add acceleration series to the chart
                    historyChart.addSeries(accelSeries);

                    Log.d(TAG, "Chart updated with " + validSeizurePoints + " seizure points and " + validAccelPoints + " accel points");
                } else {
                    Log.d(TAG, "No valid accel data points in history");
                }
            } else {
                Log.d(TAG, "No acceleration std dev history data available yet");
            }

        } catch (Exception e) {
            Log.e(TAG, "Error updating chart: " + e.getMessage(), e);
        }
    }
}
