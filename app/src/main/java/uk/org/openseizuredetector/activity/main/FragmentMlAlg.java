package uk.org.openseizuredetector.activity.main;
import uk.org.openseizuredetector.R;

import uk.org.openseizuredetector.data.SdData;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import androidx.preference.PreferenceManager;
import android.util.Log;
import android.util.TypedValue;
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

            String modelName = getSelectedModelName();
            if (sdData.mlNumModels > 1) {
                modelName = sdData.mlNumModels + " Models Active";
            }
            ((TextView) mRootView.findViewById(R.id.fragment_ml_alg_model_name))
                    .setText("Model: " + modelName);

            long pSeizurePc = (long) (sdData.mPseizure * 100);

            ((TextView) mRootView.findViewById(R.id.fragment_ml_alg_probability_label))
                    .setText(String.format("%s %d%%, AccStDev %.1f%%",
                            getString(R.string.seizure_probability), pSeizurePc, sdData.mAccelMagStdDev));
            ProgressBar pb = ((ProgressBar) mRootView.findViewById(R.id.fragment_ml_alg_progress_bar));
            pb.setMax(100);
            pb.setProgress((int) pSeizurePc);

            Drawable pbDrawable = mContext.getDrawable(R.drawable.progress_bar_blue);
            if (pSeizurePc > 30)
                pbDrawable = mContext.getDrawable(R.drawable.progress_bar_yellow);
            if (pSeizurePc > 50)
                pbDrawable = mContext.getDrawable(R.drawable.progress_bar_red);
            pb.setProgressDrawable(pbDrawable);

            ((TextView) mRootView.findViewById(R.id.fragment_ml_alg_probability_value))
                    .setText(String.format("%.1f%%", sdData.mPseizure * 100));

            updateIndividualModelDisplay(sdData);
            displayHistoryChart(sdData);

        } catch (Exception e) {
            Log.e(TAG, "Error updating UI: " + e.getMessage(), e);
        }
    }

    private void updateIndividualModelDisplay(SdData sdData) {
        ViewGroup modelsContainer = mRootView.findViewById(R.id.individual_models_container);
        if (modelsContainer == null) return;

        modelsContainer.removeAllViews();

        if (sdData.mlNumModels <= 1) {
            modelsContainer.setVisibility(View.GONE);
            return;
        }

        modelsContainer.setVisibility(View.VISIBLE);

        for (int i = 0; i < sdData.mlNumModels && i < 5; i++) {
            if (!sdData.mlModelActive[i]) continue;

            android.widget.LinearLayout modelRow = new android.widget.LinearLayout(mContext);
            modelRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            modelRow.setPadding(0, 8, 0, 8);

            TextView nameView = new TextView(mContext);
            nameView.setText(sdData.mlModelNames[i] + ":");
            nameView.setTextColor(okTextColour);
            nameView.setTextSize(14);
            nameView.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
            modelRow.addView(nameView);

            TextView probView = new TextView(mContext);
            probView.setText(String.format("%.1f%%", sdData.mlModelProbs[i] * 100));
            probView.setTextColor(okTextColour);
            probView.setTextSize(14);
            probView.setGravity(android.view.Gravity.END);
            probView.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
            probView.setPadding(16, 0, 16, 0);
            modelRow.addView(probView);

            TextView statusView = new TextView(mContext);
            String statusText;
            int bgColor;
            int textColor;

            switch (sdData.mlModelStates[i]) {
                case 2: // ALARM
                    statusText = "ALARM";
                    bgColor = Color.RED;
                    textColor = Color.BLACK;
                    break;
                case 1: // WARNING
                    statusText = "WARN";
                    bgColor = Color.MAGENTA;
                    textColor = Color.WHITE;
                    break;
                default: // OK
                    statusText = "OK";
                    bgColor = Color.BLUE;
                    textColor = Color.WHITE;
                    break;
            }

            statusView.setText(statusText);
            statusView.setBackgroundColor(bgColor);
            statusView.setTextColor(textColor);
            statusView.setTextSize(12);
            statusView.setPadding(16, 4, 16, 4);
            statusView.setGravity(android.view.Gravity.CENTER);
            statusView.setMinWidth(80);
            modelRow.addView(statusView);

            modelsContainer.addView(modelRow);
        }
    }

    private String getSelectedModelName() {
        try {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);
            String selectedModel = sp.getString("CnnModelName", "Bundled Model");
            if (selectedModel.contains(".")) {
                selectedModel = selectedModel.substring(0, selectedModel.lastIndexOf("."));
            }
            return selectedModel;
        } catch (Exception e) {
            return "unknown";
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
                        return String.format("%d", (int)rounded);
                    }
                    return ""; 
                } else {
                    return super.formatLabel(value, isValueX);
                }
            }
        });

        // Use okTextColour from base class which is resolved from theme
        historyChart.getGridLabelRenderer().setHorizontalLabelsColor(okTextColour);
        historyChart.getGridLabelRenderer().setVerticalLabelsColor(okTextColour);
        historyChart.getGridLabelRenderer().setGridColor(Color.GRAY);
        historyChart.getGridLabelRenderer().setHorizontalAxisTitleColor(okTextColour);
        historyChart.getGridLabelRenderer().setHorizontalAxisTitle("Time (minutes ago)");

        Log.d(TAG, "Chart view initialized with theme-aware colors");
    }

    private void displayHistoryChart(SdData sdData) {
        try {
            if (historyChart == null) return;

            double[] seizureHistoryData = mConnection.mSdServer.mSdDataHistory.mPseizureHistBuf.getVals();

            if (seizureHistoryData == null || seizureHistoryData.length == 0) {
                historyChart.removeAllSeries();
                return;
            }

            DataPoint[] seizureDataPoints = new DataPoint[seizureHistoryData.length];
            int validSeizurePoints = 0;

            for (int i = 0; i < seizureHistoryData.length; i++) {
                double timeInSeconds = i * 5.0; 
                if (seizureHistoryData[i] >= 0.0) {
                    seizureDataPoints[validSeizurePoints] = new DataPoint(timeInSeconds, seizureHistoryData[i] * 100.0);
                    validSeizurePoints++;
                }
            }

            if (validSeizurePoints == 0) {
                historyChart.removeAllSeries();
                return;
            }

            DataPoint[] validSeizureDataPoints = new DataPoint[validSeizurePoints];
            System.arraycopy(seizureDataPoints, 0, validSeizureDataPoints, 0, validSeizurePoints);

            LineGraphSeries<DataPoint> seizureSeries = new LineGraphSeries<>(validSeizureDataPoints);
            seizureSeries.setColor(Color.BLUE);
            seizureSeries.setThickness(3);
            seizureSeries.setDrawDataPoints(false);
            seizureSeries.setDrawBackground(false);

            historyChart.removeAllSeries();
            historyChart.addSeries(seizureSeries);

            double[] accelStdDevData = mConnection.mSdServer.mSdDataHistory.mAccelMagStdDevHistBuf.getVals();

            if (accelStdDevData != null && accelStdDevData.length > 0) {
                DataPoint[] accelDataPoints = new DataPoint[accelStdDevData.length];
                int validAccelPoints = 0;

                for (int i = 0; i < accelStdDevData.length; i++) {
                    double timeInSeconds = i * 5.0; 
                    if (accelStdDevData[i] >= 0.0) {
                        accelDataPoints[validAccelPoints] = new DataPoint(timeInSeconds, accelStdDevData[i]);
                        validAccelPoints++;
                    }
                }

                if (validAccelPoints > 0) {
                    DataPoint[] validAccelDataPoints = new DataPoint[validAccelPoints];
                    System.arraycopy(accelDataPoints, 0, validAccelDataPoints, 0, validAccelPoints);

                    LineGraphSeries<DataPoint> accelSeries = new LineGraphSeries<>(validAccelDataPoints);
                    accelSeries.setColor(Color.argb(180, 150, 150, 150)); 
                    accelSeries.setThickness(1); 
                    accelSeries.setDrawDataPoints(false);
                    accelSeries.setDrawBackground(false);
                    historyChart.addSeries(accelSeries);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error updating chart: " + e.getMessage(), e);
        }
    }
}
