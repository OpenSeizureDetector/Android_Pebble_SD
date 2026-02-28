package uk.org.openseizuredetector.activity.main;
import uk.org.openseizuredetector.R;

import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;
import androidx.preference.PreferenceManager;

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
        adjustChartHeightForMode(mLineChart);
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

        mLineChart.getViewport().setXAxisBoundsManual(true);
        mLineChart.getViewport().setMinX(0);
        mLineChart.getViewport().setMaxX(600);

        mLineChart.getViewport().setScalable(true);
        mLineChart.getViewport().setScrollable(true);

        mLineChart.getGridLabelRenderer().setNumVerticalLabels(6);
        mLineChart.getGridLabelRenderer().setNumHorizontalLabels(6);
        mLineChart.getGridLabelRenderer().setHumanRounding(false);

        // Use okTextColour from base class which is resolved from theme
        mLineChart.getGridLabelRenderer().setHorizontalLabelsColor(okTextColour);
        mLineChart.getGridLabelRenderer().setVerticalLabelsColor(okTextColour);
        mLineChart.getGridLabelRenderer().setGridColor(Color.GRAY);
        mLineChart.getGridLabelRenderer().setHorizontalAxisTitle("Time (minutes ago)");
        mLineChart.getGridLabelRenderer().setHorizontalAxisTitleColor(okTextColour);

        mLineChart.getGridLabelRenderer().setLabelFormatter(new com.jjoe64.graphview.DefaultLabelFormatter() {
            @Override
            public String formatLabel(double value, boolean isValueX) {
                if (isValueX) {
                    double secondsAgo = 600.0 - value;
                    double stepSeconds = 120.0; // 2 minutes
                    double snapped = Math.round(secondsAgo / stepSeconds) * stepSeconds;
                    if (Math.abs(secondsAgo - snapped) < 1.0 && snapped >= 0 && snapped <= 600.0) {
                        return String.format("%.0f", snapped / 60.0);
                    }
                    return "";
                } else {
                    return super.formatLabel(value, isValueX);
                }
            }
        });

        Log.d(TAG, "Chart view initialized with theme-aware colors");
    }

    @Override
    protected void updateUi() {
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

                int nHistArr = mConnection.mSdServer.mSdDataHistory.mHrHistBuf.getNumVals();
                double hrHistArr[] = mConnection.mSdServer.mSdDataHistory.mHrHistBuf.getVals();

                if (Objects.nonNull(hrHistArr) && nHistArr > 0) {
                    displayHistoryChart(hrHistArr, nHistArr);
                }
            }
        } else {
            tv.setText("****NOT BOUND TO SERVER***");
        }
    }

    @Override
    protected void updateUiOnNewData() {
        updateUi();
    }

    @Override
    protected void updateUiFast() {
        // HR graph updates are tied to new data to avoid flicker.
    }

    private void displayHistoryChart(double[] historyData, int length) {
        try {
            if (mLineChart == null || historyData == null || length == 0) {
                if (mLineChart != null) {
                    mLineChart.removeAllSeries();
                }
                return;
            }

            DataPoint[] dataPoints = new DataPoint[length];
            int validPoints = 0;

            for (int i = 0; i < length; i++) {
                double timeInSeconds = i * 5.0; 
                if (historyData[i] >= 0.0) {
                    dataPoints[validPoints] = new DataPoint(timeInSeconds, historyData[i]);
                    validPoints++;
                }
            }

            if (validPoints == 0) {
                mLineChart.removeAllSeries();
                return;
            }

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

            mLineChart.removeAllSeries();
            mLineChart.addSeries(series);

            float xSpan = (length * 5.0f) / 60.0f; // minutes
            mLineChart.setTitle(getString(R.string.heart_rate_history_bpm)
                    + " " + String.format("%.1f", xSpan) + " minutes");
            mLineChart.setTitleTextSize(36f);
            mLineChart.setTitleColor(okTextColour);

        } catch (Exception e) {
            Log.e(TAG, "Error updating chart: " + e.getMessage(), e);
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
        params.height = Math.max(params.height - shrinkPx, Math.round(170 * getResources().getDisplayMetrics().density));
        chart.setLayoutParams(params);
    }

    private boolean isBasicMode() {
        if (mContext == null) {
            return true;
        }
        return PreferenceManager.getDefaultSharedPreferences(mContext).getBoolean("pref_basic_mode", true);
    }
}
