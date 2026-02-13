package uk.org.openseizuredetector.activity.main;
import uk.org.openseizuredetector.R;

import uk.org.openseizuredetector.data.SdData;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import java.util.ArrayList;

public class FragmentMlAlg extends FragmentOsdBaseClass {
    String TAG = "FragmentMlAlg";
    private GraphView historyChart;

    public FragmentMlAlg() {
        // Required empty public constructor
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_ml_alg, container, false);
        historyChart = rootView.findViewById(R.id.seizure_history_chart);
        setupChart();
        return rootView;
    }

    @Override
    protected void updateUi() {
        if (mRootView == null || !mConnection.mBound || mConnection.mSdServer == null) {
            return;
        }

        try {
            SdData sdData = mConnection.mSdServer.mSdData;

            // Simplified status text: "N models active"
            TextView modelStatusTv = mRootView.findViewById(R.id.fragment_ml_alg_model_name);
            modelStatusTv.setText(sdData.mlNumModels + " model(s) active");

            // Update the 2-column grid of models
            updateIndividualModelDisplay(sdData);

            // Update history chart
            displayHistoryChart(sdData);

        } catch (Exception e) {
            Log.e(TAG, "Error updating UI: " + e.getMessage(), e);
        }
    }

    private void updateIndividualModelDisplay(SdData sdData) {
        GridLayout grid = mRootView.findViewById(R.id.individual_models_grid);
        if (grid == null) return;

        grid.removeAllViews();

        for (int i = 0; i < sdData.mlNumModels && i < 6; i++) {
            if (!sdData.mlModelActive[i]) continue;

            // Create a compact vertical layout for each model cell
            android.widget.LinearLayout cell = new android.widget.LinearLayout(mContext);
            cell.setOrientation(android.widget.LinearLayout.VERTICAL);
            cell.setPadding(8, 8, 8, 8);
            
            // Header: "ML1 (OK)"
            TextView headerView = new TextView(mContext);
            String statusText = getStatusText(sdData.mlModelStates[i]);
            headerView.setText(sdData.mlModelNames[i] + " (" + statusText + ")");
            headerView.setTextColor(okTextColour);
            headerView.setTextSize(12);
            headerView.setTypeface(null, android.graphics.Typeface.BOLD);
            cell.addView(headerView);

            // Probability: "15.2%"
            TextView probView = new TextView(mContext);
            float probPc = (float) (sdData.mlModelProbs[i] * 100.0);
            probView.setText(String.format("%.1f%%", probPc));
            probView.setTextColor(okTextColour);
            probView.setTextSize(11);
            cell.addView(probView);

            // Mini Progress Bar
            ProgressBar pb = new ProgressBar(mContext, null, android.R.attr.progressBarStyleHorizontal);
            pb.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 12)); // fixed height
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
                        return String.format("%d", (int)rounded);
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
                if (seizureHistoryData[i] >= 0.0) {
                    seizureDataPoints[validSeizurePoints] = new DataPoint(i * 5.0, seizureHistoryData[i] * 100.0);
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
            historyChart.removeAllSeries();
            historyChart.addSeries(seizureSeries);

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
                    accelSeries.setColor(Color.argb(180, 150, 150, 150)); 
                    accelSeries.setThickness(1); 
                    historyChart.addSeries(accelSeries);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating chart: " + e.getMessage(), e);
        }
    }
}
