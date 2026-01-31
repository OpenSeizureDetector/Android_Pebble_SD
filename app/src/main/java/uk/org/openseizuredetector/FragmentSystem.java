package uk.org.openseizuredetector;

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

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.utils.ValueFormatter;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Objects;

public class FragmentSystem extends FragmentOsdBaseClass {
    String TAG = "FragmentSystem";

    private LineChart mBattLineChart;
    private LineDataSet mBattLineDataSet;
    private LineChart mSignalLineChart;
    private LineDataSet mSignalLineDataSet;

    public FragmentSystem() {
        // Required empty public constructor
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize battery history graph dataset
        mBattLineDataSet = new LineDataSet(new ArrayList<Entry>(), "Battery History");
        mBattLineDataSet.setValueTextColor(Color.BLACK);
        mBattLineDataSet.setValueTextSize(12f);
        mBattLineDataSet.setDrawValues(false);
        mBattLineDataSet.setCircleSize(0f);
        mBattLineDataSet.setLineWidth(2f);
        mBattLineDataSet.setColor(Color.RED);

        // Initialize signal strength graph dataset
        mSignalLineDataSet = new LineDataSet(new ArrayList<Entry>(), "Signal Strength History");
        mSignalLineDataSet.setValueTextColor(Color.BLACK);
        mSignalLineDataSet.setValueTextSize(12f);
        mSignalLineDataSet.setDrawValues(false);
        mSignalLineDataSet.setCircleSize(0f);
        mSignalLineDataSet.setLineWidth(2f);
        mSignalLineDataSet.setColor(Color.GREEN);
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
            mBattLineChart.getLegend().setEnabled(false);
            mBattLineChart.setDescription("");

            XAxis xAxis = mBattLineChart.getXAxis();
            xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
            xAxis.setTextSize(8f);
            xAxis.setDrawAxisLine(true);
            xAxis.setDrawLabels(true);
            xAxis.setTextColor(Color.WHITE);

            YAxis yAxis = mBattLineChart.getAxisLeft();
            yAxis.setAxisMinValue(0f);
            yAxis.setAxisMaxValue(100f);
            yAxis.setDrawGridLines(true);
            yAxis.setDrawLabels(true);
            yAxis.setTextColor(Color.WHITE);
            yAxis.setTextSize(8f);
            yAxis.setValueFormatter(new ValueFormatter() {
                @Override
                public String getFormattedValue(float v) {
                    DecimalFormat format = new DecimalFormat("###");
                    return format.format(v);
                }
            });

            YAxis yAxis2 = mBattLineChart.getAxisRight();
            yAxis2.setDrawGridLines(false);
            yAxis2.setEnabled(false);
        }

        // Setup signal strength history chart
        mSignalLineChart = mRootView.findViewById(R.id.system_signal_line_chart);
        if (mSignalLineChart != null) {
            mSignalLineChart.getLegend().setEnabled(false);
            mSignalLineChart.setDescription("");

            XAxis xAxis = mSignalLineChart.getXAxis();
            xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
            xAxis.setTextSize(8f);
            xAxis.setDrawAxisLine(true);
            xAxis.setDrawLabels(true);
            xAxis.setTextColor(Color.WHITE);

            YAxis yAxis = mSignalLineChart.getAxisLeft();
            yAxis.setAxisMinValue(-100f);
            yAxis.setAxisMaxValue(0f);
            yAxis.setDrawGridLines(true);
            yAxis.setDrawLabels(true);
            yAxis.setTextColor(Color.WHITE);
            yAxis.setTextSize(8f);
            yAxis.setValueFormatter(new ValueFormatter() {
                @Override
                public String getFormattedValue(float v) {
                    DecimalFormat format = new DecimalFormat("###");
                    return format.format(v);
                }
            });

            YAxis yAxis2 = mSignalLineChart.getAxisRight();
            yAxis2.setDrawGridLines(false);
            yAxis2.setEnabled(false);
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
                tv.setText(mConnection.mSdServer.mSdData.dataTime.format("%H:%M:%S"));
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
            int nWatchBattArr = mConnection.mSdServer.mSdData.watchBattBuff.getNumVals();
            double watchBattArr[] = mConnection.mSdServer.mSdData.watchBattBuff.getVals();

            if (Objects.nonNull(mConnection.mSdServer.mSdData.watchBattBuff) && nWatchBattArr > 0) {
                Log.v(TAG, "Battery buffer contains " + nWatchBattArr + " values");
                mBattLineDataSet.clear();
                String xVals[] = new String[nWatchBattArr];

                for (int i = 0; i < nWatchBattArr; i++) {
                    xVals[i] = String.valueOf(i);
                    mBattLineDataSet.addEntry(new Entry((float) watchBattArr[i], i));
                }

                mBattLineDataSet.setColors(new int[]{0xffff0000});
                LineData battHistLineData = new LineData(xVals, mBattLineDataSet);

                mBattLineChart.setData(battHistLineData);
                mBattLineChart.getData().notifyDataChanged();
                mBattLineChart.notifyDataSetChanged();
                mBattLineChart.refreshDrawableState();

                float xSpan = (nWatchBattArr * 5.0f) / 60.0f;  // time in minutes assuming one point every 5 seconds
                mBattLineChart.setDescription(getString(R.string.watch_batt_hist)
                        + " " + String.format("%.1f", xSpan)
                        + " " + getString(R.string.minutes));
                mBattLineChart.setDescriptionTextSize(8f);
                mBattLineChart.setDescriptionColor(Color.WHITE);
                mBattLineChart.invalidate();
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
            int nSignalArr = mConnection.mSdServer.mSdData.watchSignalStrengthBuff.getNumVals();
            double signalArr[] = mConnection.mSdServer.mSdData.watchSignalStrengthBuff.getVals();

            if (Objects.nonNull(mConnection.mSdServer.mSdData.watchSignalStrengthBuff) && nSignalArr > 0) {
                Log.v(TAG, "Signal buffer contains " + nSignalArr + " values");
                mSignalLineDataSet.clear();
                String xVals[] = new String[nSignalArr];

                for (int i = 0; i < nSignalArr; i++) {
                    xVals[i] = String.valueOf(i);
                    mSignalLineDataSet.addEntry(new Entry((float) signalArr[i], i));
                }

                mSignalLineDataSet.setColors(new int[]{0xff00ff00});
                LineData signalHistLineData = new LineData(xVals, mSignalLineDataSet);

                mSignalLineChart.setData(signalHistLineData);
                mSignalLineChart.getData().notifyDataChanged();
                mSignalLineChart.notifyDataSetChanged();
                mSignalLineChart.refreshDrawableState();

                float xSpan = (nSignalArr * 5.0f) / 60.0f;  // time in minutes assuming one point every 5 seconds
                mSignalLineChart.setDescription("Signal Strength History "
                        + String.format("%.1f", xSpan)
                        + " minutes");
                mSignalLineChart.setDescriptionTextSize(8f);
                mSignalLineChart.setDescriptionColor(Color.WHITE);
                mSignalLineChart.invalidate();
            }
        } catch (Exception e) {
            Log.e(TAG, "updateSignalGraph: Exception - " + e.getMessage());
        }
    }
}
