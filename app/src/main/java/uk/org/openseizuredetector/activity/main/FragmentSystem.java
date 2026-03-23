package uk.org.openseizuredetector.activity.main;
import uk.org.openseizuredetector.R;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import uk.org.openseizuredetector.data.logging.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.widget.LinearLayoutCompat;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;

import uk.org.openseizuredetector.activity.logging.LogManagerControlActivity;
import uk.org.openseizuredetector.activity.settings.PrefActivity;
import uk.org.openseizuredetector.data.AlarmState;

public class FragmentSystem extends FragmentOsdBaseClass {
    String TAG = "FragmentSystem";
    private static final int HISTORY_LINE_COLOR = Color.BLUE;
    private static final int HISTORY_LINE_THICKNESS = 4;
    private static final double SAMPLE_PERIOD_SECONDS = 5.0;
    private static final double BATTERY_X_MAX_SECONDS = 86400.0;
    private static final double SIGNAL_X_MAX_SECONDS = 600.0;

    private GraphView mBattLineChart;
    private GraphView mSignalLineChart;

    public FragmentSystem() {
        // Required empty public constructor
    }


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_system, container, false);
    }

    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Setup battery history chart
        mBattLineChart = mRootView.findViewById(R.id.system_batt_line_chart);
        if (mBattLineChart != null) {
            setupBatteryChart();
        }

        // Setup signal strength history chart
        mSignalLineChart = mRootView.findViewById(R.id.system_signal_line_chart);
        if (mSignalLineChart != null) {
            setupSignalChart();
        }

        // Handle Edit Settings Button
        ImageButton button = (ImageButton) mRootView.findViewById(R.id.settingsButton);
        button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Log.i(TAG, "settingsButton.onClick()");
                try {
                    Intent prefsIntent = new Intent(
                            mContext,
                            PrefActivity.class);
                    mContext.startActivity(prefsIntent);
                } catch (Exception ex) {
                    Log.i(TAG, "exception starting settings activity " + ex.toString());
                }

            }
        });

        // Handle View System Logs Button
        ImageButton systemLogsButton = (ImageButton) mRootView.findViewById(R.id.systemLogsButton);
        systemLogsButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Log.i(TAG, "systemLogsButton.onClick()");
                try {
                    Intent logsIntent = new Intent(
                            mContext,
                            LogManagerControlActivity.class);
                    logsIntent.putExtra(LogManagerControlActivity.EXTRA_INITIAL_TAB,
                            LogManagerControlActivity.TAB_SYSTEM_LOGS);
                    mContext.startActivity(logsIntent);
                } catch (Exception ex) {
                    Log.i(TAG, "exception starting log manager activity " + ex.toString());
                }
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    private void setupBatteryChart() {
        mBattLineChart.getViewport().setYAxisBoundsManual(true);
        mBattLineChart.getViewport().setMinY(0);
        mBattLineChart.getViewport().setMaxY(100);

        mBattLineChart.getViewport().setXAxisBoundsManual(true);
        mBattLineChart.getViewport().setMinX(0);
        mBattLineChart.getViewport().setMaxX(86400); 

        mBattLineChart.getViewport().setScalable(true);
        mBattLineChart.getViewport().setScrollable(true);
        mBattLineChart.getGridLabelRenderer().setNumVerticalLabels(6);
        mBattLineChart.getGridLabelRenderer().setNumHorizontalLabels(4);
        mBattLineChart.getGridLabelRenderer().setHumanRounding(false);

        // Use okTextColour from base class which is resolved from theme
        mBattLineChart.getGridLabelRenderer().setHorizontalLabelsColor(okTextColour);
        mBattLineChart.getGridLabelRenderer().setVerticalLabelsColor(okTextColour);
        mBattLineChart.getGridLabelRenderer().setGridColor(Color.GRAY);
        mBattLineChart.getGridLabelRenderer().setHorizontalAxisTitle("Time (hours ago)");
        mBattLineChart.getGridLabelRenderer().setHorizontalAxisTitleColor(okTextColour);

        mBattLineChart.getGridLabelRenderer().setLabelFormatter(new com.jjoe64.graphview.DefaultLabelFormatter() {
            @Override
            public String formatLabel(double value, boolean isValueX) {
                if (isValueX) {
                    // Convert seconds to hours ago (24 hour buffer = 86400 seconds)
                    double secondsAgo = 86400.0 - value;
                    double stepSeconds = 28800.0; // 8 hours
                    double snapped = Math.round(secondsAgo / stepSeconds) * stepSeconds;
                    if (Math.abs(secondsAgo - snapped) < 10.0 && snapped >= 0 && snapped <= 86400.0) {
                        return String.format("%.0f", snapped / 3600.0);
                    }
                    return "";
                } else {
                    return super.formatLabel(value, isValueX);
                }
            }
        });
    }

    private void setupSignalChart() {
        mSignalLineChart.getViewport().setYAxisBoundsManual(true);
        mSignalLineChart.getViewport().setMinY(-100);
        mSignalLineChart.getViewport().setMaxY(-50);

        mSignalLineChart.getViewport().setXAxisBoundsManual(true);
        mSignalLineChart.getViewport().setMinX(0);
        mSignalLineChart.getViewport().setMaxX(600); 

        mSignalLineChart.getViewport().setScalable(true);
        mSignalLineChart.getViewport().setScrollable(true);
        mSignalLineChart.getGridLabelRenderer().setNumVerticalLabels(6);
        mSignalLineChart.getGridLabelRenderer().setNumHorizontalLabels(6);
        mSignalLineChart.getGridLabelRenderer().setHumanRounding(false);

        // Use okTextColour from base class which is resolved from theme
        mSignalLineChart.getGridLabelRenderer().setHorizontalLabelsColor(okTextColour);
        mSignalLineChart.getGridLabelRenderer().setVerticalLabelsColor(okTextColour);
        mSignalLineChart.getGridLabelRenderer().setGridColor(Color.GRAY);
        mSignalLineChart.getGridLabelRenderer().setHorizontalAxisTitle("Time (minutes ago)");
        mSignalLineChart.getGridLabelRenderer().setHorizontalAxisTitleColor(okTextColour);

        mSignalLineChart.getGridLabelRenderer().setLabelFormatter(new com.jjoe64.graphview.DefaultLabelFormatter() {
            @Override
            public String formatLabel(double value, boolean isValueX) {
                if (isValueX) {
                    // Convert seconds to minutes ago (10 minute buffer = 600 seconds)
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
    }


    @Override
    protected void updateUi() {
        TextView tv;

        LinearLayoutCompat ll = (LinearLayoutCompat) mRootView.findViewById(R.id.fragment_ll);
        if (mUtil.isServerRunning()) {
            ll.setBackgroundColor(okColour);

            tv = (TextView) mRootView.findViewById(R.id.serverStatusTv);
            if (mConnection.mBound) {
                // Show server running status, adding NDA suffix if enabled
                if (mConnection.mSdServer.mLogNDA) {
                    tv.setText(getString(R.string.ServerRunningOK) + " - NDA Logging");
                } else {
                    tv.setText(getString(R.string.ServerRunningOK));
                }
                tv.setBackgroundColor(okColour);
                tv.setTextColor(okTextColour);

                // Set data source info text and color (moved from FragmentCommon)
                TextView dsTv = (TextView) mRootView.findViewById(R.id.dataSourceInfoTv);
                dsTv.setBackgroundColor(okColour);
                dsTv.setTextColor(okTextColour);
                if (mConnection.mSdServer.mSdDataSourceName.equals("Phone")) {
                    dsTv.setText(getString(R.string.DataSource) + " = " + "Phone (Demo Mode)");
                    dsTv.setBackgroundColor(warnColour);
                    dsTv.setTextColor(warnTextColour);
                } else if (mConnection.mSdServer.mSdDataSourceName.equals("BLE")
                    || mConnection.mSdServer.mSdDataSourceName.equals("BLE2")) {
                    dsTv.setText(getString(R.string.DataSource) + " = " + mConnection.mSdServer.mSdDataSourceName
                            + " ("+ mConnection.mSdServer.mSdData.watchSdName + ", "
                            + mConnection.mSdServer.mSdData.watchSerNo+")");
                } else {
                    dsTv.setText(getString(R.string.DataSource) + " = " + mConnection.mSdServer.mSdDataSourceName);
                }
            }
            tv = (TextView) mRootView.findViewById(R.id.serverIpTv);
            tv.setText(getString(R.string.AccessServerAt) + "\nhttp://" + mUtil.getLocalIpAddress() + ":8080");
            tv.setBackgroundColor(okColour);
            tv.setTextColor(okTextColour);
        } else {
            ll.setBackgroundColor(warnColour);
            tv = (TextView) mRootView.findViewById(R.id.serverStatusTv);
            tv.setText(R.string.ServerStopped);
            tv.setBackgroundColor(warnColour);
            tv.setTextColor(warnTextColour);
            // When server stopped, clear data source info and serverIp
            TextView dsTv = (TextView) mRootView.findViewById(R.id.dataSourceInfoTv);
            if (dsTv != null) {
                dsTv.setText("--");
                dsTv.setBackgroundColor(warnColour);
                dsTv.setTextColor(warnTextColour);
            }
            tv = (TextView) mRootView.findViewById(R.id.serverIpTv);
            tv.setText("--");
            tv.setBackgroundColor(warnColour);
            tv.setTextColor(warnTextColour);
        }


        try {
            if (mConnection.mBound) {
                tv = (TextView) mRootView.findViewById(R.id.data_time_tv);
                SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
                String timeStr = timeFormat.format(new Date(mConnection.mSdServer.mSdData.dataTimeMillis));
                tv.setText(timeStr);
                tv.setBackgroundColor(okColour);
                tv.setTextColor(okTextColour);


                tv = (TextView) mRootView.findViewById(R.id.fragment_watch_app_status_tv);
                if (mConnection.mSdServer.mSdData.watchAppRunning) {
                    tv.setText(R.string.WatchAppOK);
                    tv.setBackgroundColor(okColour);
                    tv.setTextColor(okTextColour);
                } else {
                    tv.setText(R.string.WatchAppNotRunning);
                    tv.setBackgroundColor(warnColour);
                    tv.setTextColor(warnTextColour);
                }
                
                tv = (TextView) mRootView.findViewById(R.id.battTv);
                tv.setText(getString(R.string.WatchBatteryEquals)
                        + String.valueOf(mConnection.mSdServer.mSdData.batteryPc) + "% / "
                        + String.valueOf(mConnection.mSdServer.mSdData.phoneBatteryPc) + "%");
                if (mConnection.mSdServer.mSdData.batteryPc <= 10) {
                    tv.setBackgroundColor(alarmColour);
                    tv.setTextColor(alarmTextColour);
                } else if (mConnection.mSdServer.mSdData.batteryPc < 20) {
                    tv.setBackgroundColor(warnColour);
                    tv.setTextColor(warnTextColour);
                } else {
                    tv.setBackgroundColor(okColour);
                    tv.setTextColor(okTextColour);
                }

                tv = (TextView) mRootView.findViewById(R.id.watch_manuf_tv);
                tv.setText(mConnection.mSdServer.mSdData.watchManuf);
                tv.setTextColor(okTextColour);
                tv = (TextView) mRootView.findViewById(R.id.watch_partno_tv);
                tv.setText(mConnection.mSdServer.mSdData.watchPartNo);
                tv.setTextColor(okTextColour);
                tv = (TextView) mRootView.findViewById(R.id.watch_fwver_tv);
                tv.setText(mConnection.mSdServer.mSdData.watchFwVersion);
                tv.setTextColor(okTextColour);
                tv = (TextView) mRootView.findViewById(R.id.watch_sdname_tv);

                tv.setText(mConnection.mSdServer.mSdData.watchSdName);
                tv.setTextColor(okTextColour);
                tv = (TextView) mRootView.findViewById(R.id.watch_sdver_tv);
                tv.setText(mConnection.mSdServer.mSdData.watchSdVersion);
                tv.setTextColor(okTextColour);

                tv = (TextView) mRootView.findViewById(R.id.alarmTv);
                if ((mConnection.mSdServer.mSdData.alarmState == AlarmState.OK) && !mConnection.mSdServer.mSdData.alarmStanding && !mConnection.mSdServer.mSdData.fallAlarmStanding) {
                    tv.setText(getString(R.string.okBtnTxt));
                    tv.setBackgroundColor(okColour);
                    tv.setTextColor(okTextColour);
                } else if (mConnection.mSdServer.mSdData.alarmStanding || mConnection.mSdServer.mSdData.fallAlarmStanding) {
                    tv.setText(mConnection.mSdServer.mSdData.fallAlarmStanding ? getString(R.string.Fall) : getString(R.string.Alarm) + "\n" + mConnection.mSdServer.mSdData.alarmCause);
                    tv.setBackgroundColor(alarmColour);
                    tv.setTextColor(alarmTextColour);
                } else {
                    tv.setText(mConnection.mSdServer.mSdData.alarmState == AlarmState.MUTE ? getString(R.string.Mute) : (mConnection.mSdServer.mSdData.alarmState == AlarmState.FAULT ? getString(R.string.Fault) : getString(R.string.Warning)));
                    tv.setBackgroundColor(warnColour);
                    tv.setTextColor(warnTextColour);
                }

                tv = (TextView) mRootView.findViewById(R.id.watch_batt_tv);
                tv.setText(mConnection.mSdServer.mSdData.batteryPc+" %");
                tv.setTextColor(okTextColour);
                tv = (TextView) mRootView.findViewById(R.id.watch_signal_tv);
                tv.setText(String.format("%.0f dB", mConnection.mSdServer.mSdData.watchSignalStrength));
                tv.setTextColor(okTextColour);
            }
        } catch (Exception e) {
            Log.e(TAG, "UpdateUi: Exception - " + e.getMessage());
        }
    }

    @Override
    protected void updateUiFast() {
        updateUi();
        if (mConnection.mBound) {
            // Keep checking for async-restored history so charts populate promptly on startup.
            updateBatteryGraph();
            updateSignalGraph();
        }
    }

    @Override
    protected void updateUiOnNewData() {
        // Keep regular text/status updates immediate and refresh graphs once on new/bound data
        // so persisted history is visible without waiting for the throttled timer.
        updateUi();
        if (mConnection.mBound) {
            updateBatteryGraph();
            updateSignalGraph();
        }
    }

    @Override
    protected void updateUiThrottled() {
        if (!mConnection.mBound) {
            return;
        }
        updateBatteryGraph();
        updateSignalGraph();
    }

    private void updateBatteryGraph() {
        if (mBattLineChart == null || !mConnection.mBound) return;
        try {
            double watchBattArr[] = mConnection.mSdServer.mSdDataHistory.watchBattBuff.getVals();
            if (Objects.nonNull(watchBattArr) && watchBattArr.length > 0) {
                DataPoint[] dataPoints = new DataPoint[watchBattArr.length];
                int validPoints = 0;
                for (int i = 0; i < watchBattArr.length; i++) {
                    if (watchBattArr[i] >= 0.0) {
                        dataPoints[validPoints] = new DataPoint(0, watchBattArr[i]);
                        validPoints++;
                    }
                }
                if (validPoints == 0) {
                    mBattLineChart.removeAllSeries();
                    return;
                }
                DataPoint[] validDataPoints = new DataPoint[validPoints];
                double xStart = Math.max(0.0, BATTERY_X_MAX_SECONDS - ((validPoints - 1) * SAMPLE_PERIOD_SECONDS));
                for (int i = 0; i < validPoints; i++) {
                    validDataPoints[i] = new DataPoint(xStart + (i * SAMPLE_PERIOD_SECONDS), dataPoints[i].getY());
                }
                LineGraphSeries<DataPoint> series = new LineGraphSeries<>(validDataPoints);
                series.setColor(HISTORY_LINE_COLOR);
                series.setThickness(HISTORY_LINE_THICKNESS);
                mBattLineChart.removeAllSeries();
                mBattLineChart.addSeries(series);

                float xSpan = (float) ((validPoints * SAMPLE_PERIOD_SECONDS) / 3600.0f);
                mBattLineChart.setTitle(getString(R.string.watch_batt_hist) + " " + String.format("%.1f", xSpan) + " hours");
                mBattLineChart.setTitleTextSize(24f);
                mBattLineChart.setTitleColor(okTextColour);
            }
        } catch (Exception e) {
            Log.e(TAG, "updateBatteryGraph: Exception - " + e.getMessage());
        }
    }

    private void updateSignalGraph() {
        if (mSignalLineChart == null || !mConnection.mBound) return;
        try {
            double signalArr[] = mConnection.mSdServer.mSdDataHistory.watchSignalStrengthBuff.getVals();
            if (Objects.nonNull(signalArr) && signalArr.length > 0) {
                DataPoint[] dataPoints = new DataPoint[signalArr.length];
                int validPoints = 0;
                for (int i = 0; i < signalArr.length; i++) {
                    if (signalArr[i] >= -100.0) {
                        dataPoints[validPoints] = new DataPoint(0, signalArr[i]);
                        validPoints++;
                    }
                }
                if (validPoints == 0) {
                    mSignalLineChart.removeAllSeries();
                    return;
                }
                DataPoint[] validDataPoints = new DataPoint[validPoints];
                double xStart = Math.max(0.0, SIGNAL_X_MAX_SECONDS - ((validPoints - 1) * SAMPLE_PERIOD_SECONDS));
                for (int i = 0; i < validPoints; i++) {
                    validDataPoints[i] = new DataPoint(xStart + (i * SAMPLE_PERIOD_SECONDS), dataPoints[i].getY());
                }
                LineGraphSeries<DataPoint> series = new LineGraphSeries<>(validDataPoints);
                series.setColor(HISTORY_LINE_COLOR);
                series.setThickness(HISTORY_LINE_THICKNESS);
                mSignalLineChart.removeAllSeries();
                mSignalLineChart.addSeries(series);

                float xSpan = (float) ((validPoints * SAMPLE_PERIOD_SECONDS) / 60.0f);
                mSignalLineChart.setTitle("Signal Strength History " + String.format("%.1f", xSpan) + " minutes");
                mSignalLineChart.setTitleTextSize(24f);
                mSignalLineChart.setTitleColor(okTextColour);
            }
        } catch (Exception e) {
            Log.e(TAG, "updateSignalGraph: Exception - " + e.getMessage());
        }
    }
}
