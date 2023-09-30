package uk.org.openseizuredetector;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.widget.LinearLayoutCompat;

public class FragmentSystem extends FragmentOsdBaseClass {
    String TAG = "FragmentSystem";
    public FragmentSystem() {
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
                    Log.i(TAG, "exception starting settings activity " + ex.toString());
                }

            }
        });
    }



    @Override
    protected void updateUi() {
        //Log.d(TAG,"updateUi()");
        TextView tv;

        tv = (TextView)mRootView.findViewById(R.id.fragment_bound_to_server_tv);
        if (mConnection.mBound) {
            tv.setText("Bound to Server");
            tv.setTextColor(okTextColour);
        } else {
            tv.setText("****NOT BOUND TO SERVER***");
            tv.setTextColor(warnTextColour);
            return;
        }
        LinearLayoutCompat ll = (LinearLayoutCompat)mRootView.findViewById(R.id.fragment_ll);
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
            }
        } catch (Exception e) {
        Log.e(TAG, "UpdateUi: Exception - ");
        e.printStackTrace();
    }
    }
}
