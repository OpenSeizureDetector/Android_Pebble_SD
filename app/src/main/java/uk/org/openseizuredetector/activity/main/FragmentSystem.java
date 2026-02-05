package uk.org.openseizuredetector.activity.main;
import uk.org.openseizuredetector.R;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
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

import uk.org.openseizuredetector.activity.settings.PrefActivity;

public class FragmentSystem extends FragmentOsdBaseClass {
    String TAG = "FragmentSystem";

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
    }

    @Override
    public void onResume() {
        super.onResume();
        // History is loaded by SdServer during initialization, so we just display what's there
        // No need to reload history here
    }

    private void setupBatteryChart() {
        mBattLineChart.getViewport().setYAxisBoundsManual(true);
        mBattLineChart.getViewport().setMinY(0);
        mBattLineChart.getViewport().setMaxY(100);

        // Set X-axis to show full 24 hours (in seconds, will display as hours)
        mBattLineChart.getViewport().setXAxisBoundsManual(true);
        mBattLineChart.getViewport().setMinX(0);
        mBattLineChart.getViewport().setMaxX(86400); // 24 hours = 86400 seconds

        mBattLineChart.getViewport().setScalable(true);
        mBattLineChart.getViewport().setScrollable(true);
        mBattLineChart.getGridLabelRenderer().setNumVerticalLabels(6);
        mBattLineChart.getGridLabelRenderer().setNumHorizontalLabels(7);

        try {
            int textColor = mContext.getResources().getColor(R.color.okTextColor, null);
            mBattLineChart.getGridLabelRenderer().setHorizontalLabelsColor(textColor);
            mBattLineChart.getGridLabelRenderer().setVerticalLabelsColor(textColor);
            mBattLineChart.getGridLabelRenderer().setGridColor(Color.GRAY);
            mBattLineChart.getGridLabelRenderer().setHorizontalAxisTitle("Time (hours)");
            mBattLineChart.getGridLabelRenderer().setHorizontalAxisTitleColor(textColor);

            // Format X-axis to show hours ago instead of hours from start
            mBattLineChart.getGridLabelRenderer().setLabelFormatter(new com.jjoe64.graphview.DefaultLabelFormatter() {
                @Override
                public String formatLabel(double value, boolean isValueX) {
                    if (isValueX) {
                        // Convert seconds to hours ago (24 hour buffer = 86400 seconds)
                        double hoursAgo = (86400.0 - value) / 3600.0;
                        return String.format("%.0f", hoursAgo);
                    } else {
                        return super.formatLabel(value, isValueX);
                    }
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "Error setting battery chart colors: " + e.getMessage());
        }
    }

    private void setupSignalChart() {
        mSignalLineChart.getViewport().setYAxisBoundsManual(true);
        mSignalLineChart.getViewport().setMinY(-100);
        mSignalLineChart.getViewport().setMaxY(-50);

        // Set X-axis to show full 10 minutes (in seconds, will display as minutes)
        mSignalLineChart.getViewport().setXAxisBoundsManual(true);
        mSignalLineChart.getViewport().setMinX(0);
        mSignalLineChart.getViewport().setMaxX(600); // 10 minutes = 600 seconds

        mSignalLineChart.getViewport().setScalable(true);
        mSignalLineChart.getViewport().setScrollable(true);
        mSignalLineChart.getGridLabelRenderer().setNumVerticalLabels(6);
        mSignalLineChart.getGridLabelRenderer().setNumHorizontalLabels(6);

        try {
            int textColor = mContext.getResources().getColor(R.color.okTextColor, null);
            mSignalLineChart.getGridLabelRenderer().setHorizontalLabelsColor(textColor);
            mSignalLineChart.getGridLabelRenderer().setVerticalLabelsColor(textColor);
            mSignalLineChart.getGridLabelRenderer().setGridColor(Color.GRAY);
            mSignalLineChart.getGridLabelRenderer().setHorizontalAxisTitle("Time (minutes)");
            mSignalLineChart.getGridLabelRenderer().setHorizontalAxisTitleColor(textColor);

            // Format X-axis to show minutes ago instead of minutes from start
            mSignalLineChart.getGridLabelRenderer().setLabelFormatter(new com.jjoe64.graphview.DefaultLabelFormatter() {
                @Override
                public String formatLabel(double value, boolean isValueX) {
                    if (isValueX) {
                        // Convert seconds to minutes ago (10 minute buffer = 600 seconds)
                        double minutesAgo = (600.0 - value) / 60.0;
                        return String.format("%.0f", minutesAgo);
                    } else {
                        return super.formatLabel(value, isValueX);
                    }
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "Error setting signal chart colors: " + e.getMessage());
        }
    }


    @Override
    protected void updateUi() {
        //Log.d(TAG,"updateUi()");
        TextView tv;


        LinearLayoutCompat ll = (LinearLayoutCompat) mRootView.findViewById(R.id.fragment_ll);
        if (mUtil.isServerRunning()) {
            ll.setBackgroundColor(okColour);

            tv = (TextView) mRootView.findViewById(R.id.serverStatusTv);
            if (mConnection.mBound) {
                if (mConnection.mSdServer.mSdDataSourceName.equals("Phone")) {
                    if (mConnection.mSdServer.mLogNDA)
                        tv.setText( getString(R.string.DataSource) + " = " + "Phone" + " " + "(Demo Mode)" + "\nNDA Logging");
                    else
                        tv.setText(getString(R.string.DataSource) + " = " + "Phone" + " " + "(Demo Mode)");
                    tv.setBackgroundColor(warnColour);
                    tv.setTextColor(warnTextColour);
                } else {
                    if (mConnection.mSdServer.mLogNDA)
                        tv.setText(getString(R.string.DataSource) + " = " + mConnection.mSdServer.mSdDataSourceName + ": NDA Logging");
                    else
                        tv.setText(getString(R.string.DataSource) + " = " + mConnection.mSdServer.mSdDataSourceName);
                    tv.setBackgroundColor(okColour);
                    tv.setTextColor(okTextColour);
                }
            }
            //Log.v(TAG,"UpdateUi() - displaying server IP address");
            tv = (TextView) mRootView.findViewById(R.id.serverIpTv);
            tv.setText(getString(R.string.AccessServerAt) + "\nhttp://"
                    + mUtil.getLocalIpAddress()
                    + ":8080");
            tv.setBackgroundColor(okColour);
            tv.setTextColor(okTextColour);
        } else {
            ll.setBackgroundColor(warnColour);

            tv = (TextView) mRootView.findViewById(R.id.serverStatusTv);
            tv.setText(R.string.ServerStopped);
            tv.setBackgroundColor(warnColour);
            tv.setTextColor(warnTextColour);
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
                }
                if (mConnection.mSdServer.mSdData.batteryPc > 10) {
                    tv.setBackgroundColor(warnColour);
                    tv.setTextColor(warnTextColour);
                }
                if (mConnection.mSdServer.mSdData.batteryPc >= 20) {
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
                tv = (TextView) mRootView.findViewById(R.id.alarmTv);
                if ((mConnection.mSdServer.mSdData.alarmState == 0)
                        && !mConnection.mSdServer.mSdData.alarmStanding
                        && !mConnection.mSdServer.mSdData.fallAlarmStanding) {
                    tv.setText(getString(R.string.okBtnTxt));
                    tv.setBackgroundColor(okColour);
                    tv.setTextColor(okTextColour);
                }
                if ((mConnection.mSdServer.mSdData.alarmState == 1)
                        && !mConnection.mSdServer.mSdData.alarmStanding
                        && !mConnection.mSdServer.mSdData.fallAlarmStanding) {
                    tv.setText(R.string.Warning);
                    tv.setBackgroundColor(warnColour);
                    tv.setTextColor(warnTextColour);
                }
                if (mConnection.mSdServer.mSdData.alarmState == 6) {
                    tv.setText(R.string.Mute);
                    tv.setBackgroundColor(warnColour);
                    tv.setTextColor(warnTextColour);
                }
                if (mConnection.mSdServer.mSdData.alarmStanding) {
                    tv.setText(R.string.Alarm);
                    tv.setBackgroundColor(alarmColour);
                    tv.setTextColor(alarmTextColour);
                }
                if (mConnection.mSdServer.mSdData.fallAlarmStanding) {
                    tv.setText(R.string.Fall);
                    tv.setBackgroundColor(alarmColour);
                    tv.setTextColor(alarmTextColour);
                }
                tv = (TextView) mRootView.findViewById(R.id.battTv);
                tv.setText(getString(R.string.WatchBatteryEquals)
                        + String.valueOf(mConnection.mSdServer.mSdData.batteryPc) + "% / "
                        + String.valueOf(mConnection.mSdServer.mSdData.phoneBatteryPc) + "%");
                if (mConnection.mSdServer.mSdData.batteryPc <= 10) {
                    tv.setBackgroundColor(alarmColour);
                    tv.setTextColor(alarmTextColour);
                }
                if (mConnection.mSdServer.mSdData.batteryPc > 10) {
                    tv.setBackgroundColor(warnColour);
                    tv.setTextColor(warnTextColour);
                }
                if (mConnection.mSdServer.mSdData.batteryPc >= 20) {
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
                tv = (TextView) mRootView.findViewById(R.id.watch_batt_tv);
                tv.setText(mConnection.mSdServer.mSdData.batteryPc+" %");
                tv.setTextColor(okTextColour);
                tv = (TextView) mRootView.findViewById(R.id.watch_signal_tv);
                tv.setText(String.format("%.0f dB", mConnection.mSdServer.mSdData.watchSignalStrength));
                tv.setTextColor(okTextColour);
                // Update battery history graph
                updateBatteryGraph();
                // Update signal strength graph
                updateSignalGraph();
            }
        } catch (Exception e) {
            Log.e(TAG, "UpdateUi: Exception - ");
            e.printStackTrace();
        }
    }
    private void updateBatteryGraph() {
        if (mBattLineChart == null || !mConnection.mBound) {
            return;
        }
        try {
            // Read from SdDataHistory which maintains persistent history across SdData replacements
            double watchBattArr[] = mConnection.mSdServer.mSdDataHistory.watchBattBuff.getVals();
            int nWatchBattArr = watchBattArr.length;
            if (Objects.nonNull(watchBattArr) && nWatchBattArr > 0) {

                // Create data points with forward time axis (like ML graph)
                // Index 0 = oldest data (left side, time=0)
                // Index N-1 = newest data (right side, time=max)
                DataPoint[] dataPoints = new DataPoint[nWatchBattArr];
                int validPoints = 0;

                for (int i = 0; i < nWatchBattArr; i++) {
                    // Calculate time in seconds from oldest sample
                    double timeInSeconds = i * 5.0; // 5 seconds per sample

                    // Filter out error values (-1.0) but keep zeros
                    if (watchBattArr[i] >= 0.0) {
                        dataPoints[validPoints] = new DataPoint(timeInSeconds, watchBattArr[i]);
                        validPoints++;
                    }
                }

                if (validPoints == 0) {
                    Log.d(TAG, "No valid battery data points");
                    mBattLineChart.removeAllSeries();
                    return;
                }

                // Create series with only valid points
                DataPoint[] validDataPoints = new DataPoint[validPoints];
                System.arraycopy(dataPoints, 0, validDataPoints, 0, validPoints);

                LineGraphSeries<DataPoint> series = new LineGraphSeries<>(validDataPoints);
                series.setColor(Color.RED);
                series.setThickness(2);
                series.setDrawDataPoints(false);
                series.setDrawBackground(false);
                mBattLineChart.removeAllSeries();
                mBattLineChart.addSeries(series);

                float xSpan = (nWatchBattArr * 5.0f) / 3600.0f; // hours
                mBattLineChart.setTitle(getString(R.string.watch_batt_hist)
                        + " " + String.format("%.1f", xSpan) + " hours");
                mBattLineChart.setTitleTextSize(24f);
                mBattLineChart.setTitleColor(Color.WHITE);

                // If we have a large time span, scroll to show the most recent data (right side)
                if (validPoints > 10) {
                    double maxTime = validPoints * 5.0;
                    double viewportWidth = 86400; // 24 hours in seconds
                    if (maxTime > viewportWidth) {
                        // Scroll to show the end (most recent data)
                        mBattLineChart.getViewport().setMaxX(maxTime);
                        mBattLineChart.getViewport().setMinX(Math.max(0, maxTime - viewportWidth));
                    }
                }

                Log.d(TAG, "Battery chart updated with " + validPoints + " data points");
            }
        } catch (Exception e) {
            Log.e(TAG, "updateBatteryGraph: Exception - " + e.getMessage());
        }
    }
    private void updateSignalGraph() {
        if (mSignalLineChart == null || !mConnection.mBound) {
            return;
        }
        try {
            // Read from SdDataHistory which maintains persistent history across SdData replacements
            double signalArr[] = mConnection.mSdServer.mSdDataHistory.watchSignalStrengthBuff.getVals();
            int nSignalArr = signalArr.length;
            if (Objects.nonNull(signalArr) && nSignalArr > 0) {

                // Create data points with forward time axis (like ML graph)
                // Index 0 = oldest data (left side, time=0)
                // Index N-1 = newest data (right side, time=max)
                DataPoint[] dataPoints = new DataPoint[nSignalArr];
                int validPoints = 0;

                for (int i = 0; i < nSignalArr; i++) {
                    // Calculate time in seconds from oldest sample
                    double timeInSeconds = i * 5.0; // 5 seconds per sample

                    // Filter out error values (-1.0) but keep zeros
                    if (signalArr[i] >= -100.0) { // Signal strength is negative, so check >= -100
                        dataPoints[validPoints] = new DataPoint(timeInSeconds, signalArr[i]);
                        validPoints++;
                    }
                }

                if (validPoints == 0) {
                    Log.d(TAG, "No valid signal data points");
                    mSignalLineChart.removeAllSeries();
                    return;
                }

                // Create series with only valid points
                DataPoint[] validDataPoints = new DataPoint[validPoints];
                System.arraycopy(dataPoints, 0, validDataPoints, 0, validPoints);

                LineGraphSeries<DataPoint> series = new LineGraphSeries<>(validDataPoints);
                series.setColor(Color.GREEN);
                series.setThickness(2);
                series.setDrawDataPoints(false);
                series.setDrawBackground(false);
                mSignalLineChart.removeAllSeries();
                mSignalLineChart.addSeries(series);

                float xSpan = (nSignalArr * 5.0f) / 60.0f; // minutes
                mSignalLineChart.setTitle("Signal Strength History "
                        + String.format("%.1f", xSpan) + " minutes");
                mSignalLineChart.setTitleTextSize(24f);
                mSignalLineChart.setTitleColor(Color.WHITE);

                // If we have more than ~200 samples (10 minutes), scroll to show the most recent data
                if (validPoints > 200) {
                    double maxTime = validPoints * 5.0;
                    double viewportWidth = 600; // 10 minutes in seconds
                    if (maxTime > viewportWidth) {
                        // Scroll to show the end (most recent data)
                        mSignalLineChart.getViewport().setMaxX(maxTime);
                        mSignalLineChart.getViewport().setMinX(Math.max(0, maxTime - viewportWidth));
                    }
                }

                Log.d(TAG, "Signal chart updated with " + validPoints + " data points");
            }
        } catch (Exception e) {
            Log.e(TAG, "updateSignalGraph: Exception - " + e.getMessage());
            e.printStackTrace();
        }
    }
}
