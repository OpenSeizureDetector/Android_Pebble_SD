package uk.org.openseizuredetector.activity.main;
import uk.org.openseizuredetector.R;

import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.widget.SwitchCompat;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import java.util.Objects;


public class FragmentBatt extends FragmentOsdBaseClass {
    String TAG = "FragmentBatt";

    private GraphView mLineChart;

    public FragmentBatt() {
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
        View rootView = inflater.inflate(R.layout.fragment_batt, container, false);
        mLineChart = rootView.findViewById(R.id.battLineChart);
        setupChart();
        return rootView;
    }

    private void setupChart() {
        if (mLineChart == null) {
            Log.w(TAG, "Chart view is null");
            return;
        }

        // Configure chart appearance
        mLineChart.getViewport().setYAxisBoundsManual(true);
        mLineChart.getViewport().setMinY(0);
        mLineChart.getViewport().setMaxY(100);

        mLineChart.getViewport().setScalable(true);
        mLineChart.getViewport().setScrollable(true);

        mLineChart.getGridLabelRenderer().setNumVerticalLabels(6);

        // Set text color for better visibility
        try {
            int textColor = mContext.getResources().getColor(R.color.okTextColor, null);
            mLineChart.getGridLabelRenderer().setHorizontalLabelsColor(textColor);
            mLineChart.getGridLabelRenderer().setVerticalLabelsColor(textColor);
            mLineChart.getGridLabelRenderer().setGridColor(Color.GRAY);
        } catch (Exception e) {
            Log.w(TAG, "Error setting chart colors: " + e.getMessage());
        }

        Log.d(TAG, "Chart view initialized");
    }

    @Override
    protected void updateUi() {
        Log.d(TAG, "updateUi()");
        
        if (mConnection.mBound) {
            int nWatchBattArr = mConnection.mSdServer.mSdData.watchBattBuff.getNumVals();
            double watchBattArr[] = mConnection.mSdServer.mSdData.watchBattBuff.getVals();
            
            Log.i(TAG,"updateUi() - nWatchBattArr="+nWatchBattArr);
            
            if (Objects.nonNull(mConnection.mSdServer.mSdData.watchBattBuff) && nWatchBattArr > 0) {
                displayHistoryChart(watchBattArr, nWatchBattArr);
            }
        }
    }

    private void displayHistoryChart(double[] historyData, int length) {
        try {
            if (mLineChart == null || historyData == null || length == 0) {
                if (mLineChart != null) {
                    mLineChart.removeAllSeries();
                }
                return;
            }

            // Create data points
            DataPoint[] dataPoints = new DataPoint[length];
            for (int i = 0; i < length; i++) {
                dataPoints[i] = new DataPoint(i, historyData[i]);
            }

            LineGraphSeries<DataPoint> series = new LineGraphSeries<>(dataPoints);

            series.setColor(Color.RED);
            series.setThickness(3);
            series.setDrawDataPoints(false);
            series.setDrawBackground(false);

            // Remove old series and add new one
            mLineChart.removeAllSeries();
            mLineChart.addSeries(series);

            float xSpan = (length * 5.0f) / 60.0f;
            mLineChart.setTitle(getString(R.string.watch_batt_hist)
                    + " " + String.format("%.1f", xSpan)
                    + " " + getString(R.string.minutes));
            mLineChart.setTitleTextSize(36f);
            mLineChart.setTitleColor(Color.WHITE);

            Log.v(TAG, "Chart updated with " + length + " data points");

        } catch (Exception e) {
            Log.e(TAG, "Error updating chart: " + e.getMessage(), e);
        }
    }
}
