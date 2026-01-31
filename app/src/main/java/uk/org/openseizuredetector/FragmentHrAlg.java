package uk.org.openseizuredetector;

import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import java.util.Objects;


public class FragmentHrAlg extends FragmentOsdBaseClass {
    String TAG = "FragmentHrAlg";

    private GraphView mLineChart;
    private TextView tvAvgAHr;
    private TextView tvHr;
    private TextView tv;
    private TextView tvCurrent;
    private SwitchCompat switchAverages;

    public FragmentHrAlg() {
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
        View rootView = inflater.inflate(R.layout.fragment_hr_alg, container, false);
        mLineChart = rootView.findViewById(R.id.lineChart);
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
        mLineChart.getViewport().setMinY(40);
        mLineChart.getViewport().setMaxY(240);

        // Set X-axis to show full 10 minutes (in seconds, like ML graph)
        mLineChart.getViewport().setXAxisBoundsManual(true);
        mLineChart.getViewport().setMinX(0);
        mLineChart.getViewport().setMaxX(600); // 10 minutes = 600 seconds

        mLineChart.getViewport().setScalable(true);
        mLineChart.getViewport().setScrollable(true);

        mLineChart.getGridLabelRenderer().setNumVerticalLabels(6);
        mLineChart.getGridLabelRenderer().setNumHorizontalLabels(6);

        // Set text color for better visibility
        try {
            int textColor = mContext.getResources().getColor(R.color.okTextColor, null);
            mLineChart.getGridLabelRenderer().setHorizontalLabelsColor(textColor);
            mLineChart.getGridLabelRenderer().setVerticalLabelsColor(textColor);
            mLineChart.getGridLabelRenderer().setGridColor(Color.GRAY);
            mLineChart.getGridLabelRenderer().setHorizontalAxisTitle("Time (minutes)");
            mLineChart.getGridLabelRenderer().setHorizontalAxisTitleColor(textColor);

            // Format X-axis to show minutes instead of seconds
            mLineChart.getGridLabelRenderer().setLabelFormatter(new com.jjoe64.graphview.DefaultLabelFormatter() {
                @Override
                public String formatLabel(double value, boolean isValueX) {
                    if (isValueX) {
                        // Convert seconds to minutes
                        return String.format("%.0f", value / 60.0);
                    } else {
                        return super.formatLabel(value, isValueX);
                    }
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "Error setting chart colors: " + e.getMessage());
        }

        Log.d(TAG, "Chart view initialized");
    }

    @Override
    protected void updateUi() {
        Log.d(TAG, "updateUi()");
        tv = (TextView) mRootView.findViewById(R.id.fragment_hr_alg_tv1);
        tvHr = (TextView) mRootView.findViewById(R.id.current_hr_tv);
        tvAvgAHr = (TextView) mRootView.findViewById(R.id.adaptive_avg_hr_tv);

        if (mConnection.mBound) {
            tv.setText("Bound to Server");

            tvCurrent = mRootView.findViewById(R.id.textView2);
            if (Objects.nonNull(tvCurrent)) {
                if (Objects.nonNull(tvHr))
                    tvHr.setText(String.valueOf((short) mConnection.mSdServer.mSdData.mHR));
                if (Objects.nonNull(tvAvgAHr))
                    tvAvgAHr.setText(String.valueOf((short) mConnection.mSdServer.mSdData
                            .mAdaptiveHrAverage));
                tvCurrent.setText(new StringBuilder()
                        .append("\nResult of checks: Adaptive Hr Alarm Standing: ")
                        .append(mConnection.mSdServer.mSdData.mAdaptiveHrAlarmStanding)
                        .append("\nAverage Hr Alarm Standing: ")
                        .append(mConnection.mSdServer.mSdData.mAdaptiveHrAlarmStanding)
                        .toString());

                if (Objects.nonNull(mConnection.mSdServer.mSdDataSource.mSdAlgHr)) {
                    CircBuf hrHist = mConnection.mSdServer.mSdDataSource.mSdAlgHr.getHrHistBuff();
                    int nHistArr = hrHist.getNumVals();
                    double hrHistArr[] = hrHist.getVals();

                    if (Objects.nonNull(hrHist) && nHistArr > 0) {
                        displayHistoryChart(hrHistArr, nHistArr);
                    }
                }
            }
        } else {
            tv.setText("****NOT BOUND TO SERVER***");
            return;
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

            // Create data points with forward time axis (like ML graph)
            // Index 0 = oldest data (left side, time=0)
            // Index N-1 = newest data (right side, time=max)
            DataPoint[] dataPoints = new DataPoint[length];
            int validPoints = 0;

            for (int i = 0; i < length; i++) {
                // Calculate time in seconds from oldest sample
                double timeInSeconds = i * 5.0; // 5 seconds per sample

                // Filter out error values (-1.0) but keep zeros
                if (historyData[i] >= 0.0) {
                    dataPoints[validPoints] = new DataPoint(timeInSeconds, historyData[i]);
                    validPoints++;
                }
            }

            if (validPoints == 0) {
                Log.d(TAG, "No valid data points in history");
                mLineChart.removeAllSeries();
                return;
            }

            // Create series with only valid points
            DataPoint[] validDataPoints = new DataPoint[validPoints];
            System.arraycopy(dataPoints, 0, validDataPoints, 0, validPoints);

            LineGraphSeries<DataPoint> series = new LineGraphSeries<>(validDataPoints);

            try {
                int lineColor = mContext.getResources().getColor(R.color.colorAccent, null);
                series.setColor(lineColor);
            } catch (Exception e) {
                series.setColor(Color.RED);
            }

            series.setThickness(3);
            series.setDrawDataPoints(false);
            series.setDrawBackground(false);

            // Remove old series and add new one
            mLineChart.removeAllSeries();
            mLineChart.addSeries(series);

            float xSpan = (length * 5.0f) / 60.0f; // minutes
            mLineChart.setTitle(getString(R.string.heart_rate_history_bpm)
                    + " " + String.format("%.1f", xSpan) + " minutes");
            mLineChart.setTitleTextSize(36f);
            mLineChart.setTitleColor(Color.WHITE);

            Log.d(TAG, "Chart updated with " + validPoints + " data points");

        } catch (Exception e) {
            Log.e(TAG, "Error updating chart: " + e.getMessage(), e);
        }
    }
}
