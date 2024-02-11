package uk.org.openseizuredetector;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.appcompat.widget.SwitchCompat;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.YAxis;

import java.util.Objects;

public class FragmentSystem extends FragmentOsdBaseClass {
    String TAG = "FragmentSystem";
    private SwitchCompat switchWatchGraphToPhoneGraph;
    private LineChart lineChartPowerLevel;

    public FragmentSystem() {
        // Required empty public constructor
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Objects.nonNull(mConnection)) {
            if (!mConnection.mBound)
                mUtil.bindToServer((Context) this.getActivity(), mConnection);
            mUtil.waitForConnection(mConnection);
            connectUiLiveDataRunner();
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_system, container, false);
    }

    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
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
                    Log.i(TAG, "exception starting settings activity " + ex.toString(), ex);
                }

            }
        });
        lineChartPowerLevel = mRootView.findViewById(R.id.lineChartBattery);
        lineChartPowerLevel.setDescriptionColor(R.color.okTextColor);
        YAxis yAxisLeft = lineChartPowerLevel.getAxisLeft();
        yAxisLeft.setTextColor(R.color.okTextColor);
        YAxis yAxisRight = lineChartPowerLevel.getAxisRight();
        yAxisRight.setTextColor(R.color.okTextColor);
        Legend legendOfGraph = lineChartPowerLevel.getLegend();
        legendOfGraph.setTextColor(R.color.okTextColor);
    }

    void connectUiLiveDataRunner() {
        if (mConnection.mBound && Objects.nonNull(mConnection.mSdServer)) {
            switchWatchGraphToPhoneGraph = mRootView.findViewById(R.id.switchToPowerGraph);
            if (!mConnection.mSdServer.uiLiveData.isListeningInContext(this)) {
                mConnection.mSdServer.uiLiveData.observe(this, this::onChangedObserver);
                mConnection.mSdServer.uiLiveData.observeForever(this::onChangedObserver);
                mConnection.mSdServer.uiLiveData.addToListening(this);
                switchWatchGraphToPhoneGraph = mRootView.findViewById(R.id.switchToPowerGraph);
                switchWatchGraphToPhoneGraph.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        if (Objects.nonNull(lineChartPowerLevel)) {
                            lineChartPowerLevel.clear();
                            lineChartPowerLevel.postInvalidate();
                        }
                        mUtil.runOnUiThread(()->updateUi());
                    }
                });
                mUtil.runOnUiThread(this::updateUi);
            }
        } else {
            updateUiHandler.postDelayed(this::connectUiLiveDataRunner, 100);
        }
    }


    private void onChangedObserver(Object o) {
        mUtil.runOnUiThread(this::updateUi);
    }

    @Override
    protected void updateUi() {
        //Log.d(TAG,"updateUi()");
        TextView tv;

        if (Objects.isNull(mRootView)||!isAdded()||!isVisible()) return;
        tv = (TextView) mRootView.findViewById(R.id.fragment_bound_to_server_tv);
        if (mConnection.mBound) {
            tv.setText("Bound to Server");
            tv.setTextColor(okTextColour);
        } else {
            tv.setText("****NOT BOUND TO SERVER***");
            tv.setTextColor(warnTextColour);
            return;
        }
        LinearLayoutCompat ll = (LinearLayoutCompat) mRootView.findViewById(R.id.fragment_ll);
        if (mUtil.isServerRunning()) {
            ll.setBackgroundColor(okColour);

            tv = (TextView) mRootView.findViewById(R.id.serverStatusTv);
            if (mConnection.mBound) {
                if (mConnection.mSdServer.mSdDataSourceName.equals("Phone")) {
                    if (mConnection.mSdServer.mLogNDA)
                        tv.setText(getString(R.string.ServerRunningOK) + getString(R.string.DataSource) + " = " + "Phone" + "\n" + "(Demo Mode)" + "\nNDA Logging");
                    else
                        tv.setText(getString(R.string.ServerRunningOK) + getString(R.string.DataSource) + " = " + "Phone" + "\n" + "(Demo Mode)");
                    tv.setBackgroundColor(warnColour);
                    tv.setTextColor(warnTextColour);
                } else {
                    if (mConnection.mSdServer.mLogNDA)
                        tv.setText(getString(R.string.ServerRunningOK) + getString(R.string.DataSource) + " = " + mConnection.mSdServer.mSdDataSourceName + "\nNDA Logging");
                    else
                        tv.setText(getString(R.string.ServerRunningOK) + getString(R.string.DataSource) + " = " + mConnection.mSdServer.mSdDataSourceName);
                    tv.setBackgroundColor(okColour);
                    tv.setTextColor(okTextColour);
                }
            }
            //Log.v(TAG,"UpdateUi() - displaying server IP address");
            tv = (TextView) mRootView.findViewById(R.id.serverIpTv);
            tv.setText(getString(R.string.AccessServerAt) + " http://"
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
                tv.setText(getString(R.string.WatchBatteryEquals) + String.valueOf(mConnection.mSdServer.mSdData.batteryPc) + "%");
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

                if (Objects.nonNull(mConnection.mSdServer.getLineData(switchWatchGraphToPhoneGraph.isChecked()))) {
                    if (Objects.nonNull(lineChartPowerLevel)){
                        lineChartPowerLevel.clear();
                        if (mConnection.mSdServer.getLineDataSet(switchWatchGraphToPhoneGraph.isChecked()).getYVals().size() > 0) {


                            lineChartPowerLevel.setData(mConnection.mSdServer.getLineData(switchWatchGraphToPhoneGraph.isChecked()));

                            lineChartPowerLevel.getData().notifyDataChanged();
                            lineChartPowerLevel.notifyDataSetChanged();
                            lineChartPowerLevel.refreshDrawableState();
                            if (mConnection.mSdServer.mBound) {
                                lineChartPowerLevel.postInvalidate();
                            }
                        }
                    }

                }
            }
        } catch (Exception e) {
        Log.e(TAG, "UpdateUi: Exception - ",e);
        e.printStackTrace();
    }
    }@Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (Objects.nonNull(mConnection)&&Objects.nonNull(mUtil)) {
            connectUiLiveDataRunner();
            mUtil.setBound(true, mConnection);
            if (viewCreated) updateUi();
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();

        if (Objects.nonNull(mConnection)&&Objects.nonNull(mUtil))
            mUtil.setBound(false,mConnection);
    }
}
