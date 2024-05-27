package uk.org.openseizuredetector;

import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import android.text.format.Time;

public class FragmentCommon extends FragmentOsdBaseClass {
    String TAG = "FragmentCommon";

    public FragmentCommon() {
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
        return inflater.inflate(R.layout.fragment_common, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Deal with the 'AcceptAlarm Button'
        Button button = (Button) mRootView.findViewById(R.id.acceptAlarmButton);
        button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Log.v(TAG, "acceptAlarmButton.onClick()");
                if (mConnection.mBound) {
                    if ((mConnection.mSdServer.mSmsTimer != null)
                            && (mConnection.mSdServer.mSmsTimer.mTimeLeft > 0)) {
                        Log.i(TAG, "acceptAlarmButton.onClick() - Stopping SMS Timer");
                        mUtil.showToast(getString(R.string.SMSAlarmCancelledMsg));
                        mConnection.mSdServer.stopSmsTimer();
                    } else {
                        Log.v(TAG, "acceptAlarmButton.onClick() - Accepting Alarm");
                        mConnection.mSdServer.acceptAlarm();
                    }
                }
            }
        });

        // Deal with the 'Cancel Audible Button'
        button = (Button) mRootView.findViewById(R.id.cancelAudibleButton);
        button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Log.v(TAG, "cancelAudibleButton.onClick()");
                if (mConnection.mBound) {
                    mConnection.mSdServer.cancelAudible();
                }
            }
        });

        // Deal with the 'Raise Alarm'
        button = (Button) mRootView.findViewById(R.id.manualAlarmButton);
        button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Log.v(TAG, "manualAlarmButton.onClick()");
                if (mConnection.mBound) {
                    mConnection.mSdServer.raiseManualAlarm();
                }
            }
        });
    }

    @Override
    protected void updateUi() {
        //Log.d(TAG,"updateUi()");
        TextView tv;

        if (mUtil.isServerRunning()) {
            tv = (TextView) mRootView.findViewById(R.id.serverStatusTv);
            if (mConnection.mBound) {
                if (mConnection.mSdServer.mLogNDA)
                    tv.setText(getString(R.string.ServerRunningOK) + " - NDA Logging");
                else
                    tv.setText(getString(R.string.ServerRunningOK));
                tv.setBackgroundColor(okColour);
                tv.setTextColor(okTextColour);

                tv = (TextView) mRootView.findViewById(R.id.data_time_tv);
                Time tnow = new Time(Time.getCurrentTimezone());
                tnow.setToNow();
                double tdiff;
                tdiff = (tnow.toMillis(false) - mConnection.mSdServer.mSdData.dataTime.toMillis(false))/1000.;
                tv.setText("Time =" + mConnection.mSdServer.mSdData.dataTime.format("%H:%M:%S")
                        + "  (" + String.format("%.0f s, %.1f s)",mConnection.mSdServer.mSdData.timeDiff, tdiff));
                tv.setBackgroundColor(okColour);
                tv.setTextColor(okTextColour);


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
                if (mConnection.mSdServer.mSdData.alarmState == 4) {
                    tv.setText(R.string.Fault);
                    tv.setBackgroundColor(warnColour);
                    tv.setTextColor(warnTextColour);
                }


                tv = (TextView) mRootView.findViewById(R.id.algsTv);
                tv.setText(R.string.algorithms);
                tv.setBackgroundColor(okColour);
                tv.setTextColor(okTextColour);
                tv = (TextView) mRootView.findViewById(R.id.osdAlgTv);
                tv.setText("OSD ");
                if (mConnection.mSdServer.mSdData.mOsdAlarmActive) {
                    tv.setBackgroundColor(okColour);
                    tv.setTextColor(okTextColour);
                    tv.setPaintFlags(tv.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
                } else {
                    tv.setBackgroundColor(warnColour);
                    tv.setTextColor(warnTextColour);
                    tv.setPaintFlags(tv.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                }
                tv = (TextView) mRootView.findViewById(R.id.cnnAlgTv);
                tv.setText("CNN ");
                if (mConnection.mSdServer.mSdData.mCnnAlarmActive) {
                    tv.setBackgroundColor(okColour);
                    tv.setTextColor(okTextColour);
                    tv.setPaintFlags(tv.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
                } else {
                    tv.setBackgroundColor(okColour);
                    tv.setTextColor(Color.GRAY);
                    tv.setPaintFlags(tv.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                }
                tv = (TextView) mRootView.findViewById(R.id.hrAlgTv);
                tv.setText("HR ");
                if (mConnection.mSdServer.mSdData.mHRAlarmActive) {
                    tv.setBackgroundColor(okColour);
                    tv.setTextColor(okTextColour);
                    tv.setPaintFlags(tv.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
                } else {
                    tv.setBackgroundColor(okColour);
                    tv.setTextColor(Color.GRAY);
                    tv.setPaintFlags(tv.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                }
                tv = (TextView) mRootView.findViewById(R.id.o2AlgTv);
                tv.setText("O2 ");
                if (mConnection.mSdServer.mSdData.mO2SatAlarmActive) {
                    tv.setBackgroundColor(okColour);
                    tv.setTextColor(okTextColour);
                    tv.setPaintFlags(tv.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
                } else {
                    tv.setBackgroundColor(okColour);
                    tv.setTextColor(Color.GRAY);
                    tv.setPaintFlags(tv.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                }

                tv = (TextView) mRootView.findViewById(R.id.dataSourceInfoTv);
                tv.setBackgroundColor(okColour);
                tv.setTextColor(okTextColour);
                if (mConnection.mSdServer.mSdDataSourceName.equals("Phone")) {
                    tv.setText(getString(R.string.DataSource) + " = " + "Phone (Demo Mode)");
                    tv.setBackgroundColor(warnColour);
                    tv.setTextColor(warnTextColour);
                } else if (mConnection.mSdServer.mSdDataSourceName.equals("BLE")
                    || mConnection.mSdServer.mSdDataSourceName.equals("BLE2")) {
                    tv.setText(getString(R.string.DataSource) + " = " + mConnection.mSdServer.mSdDataSourceName
                            + " ("+ mConnection.mSdServer.mSdData.watchSdName + ", "
                            + mConnection.mSdServer.mSdData.watchSerNo+")");
                } else {
                    tv.setText(getString(R.string.DataSource) + " = " + mConnection.mSdServer.mSdDataSourceName);
                }

            }
        } else {
            tv = (TextView) mRootView.findViewById(R.id.serverStatusTv);
            tv.setText(R.string.ServerStopped);
            tv.setBackgroundColor(warnColour);
            tv.setTextColor(warnTextColour);

            /**  FIXME - check this is not needed for this fragment
             tv = (TextView) mRootView.findViewById(R.id.serverIpTv);
             tv.setText("--");
             tv.setBackgroundColor(warnColour);
             tv.setTextColor(warnTextColour);
             */
        }


        // deal with latch alarms button
        Button acceptAlarmButton = (Button) mRootView.findViewById(R.id.acceptAlarmButton);

        if (mConnection.mBound) {
            if ((mConnection.mSdServer.mSmsTimer != null)
                    && (mConnection.mSdServer.mSmsTimer.mTimeLeft > 0)) {
                acceptAlarmButton.setText(getString(R.string.SMSWillBeSentIn) + " " +
                        mConnection.mSdServer.mSmsTimer.mTimeLeft / 1000 +
                        " s - " + getString(R.string.Cancel));
                acceptAlarmButton.setBackgroundColor(alarmColour);
                acceptAlarmButton.setEnabled(true);
            } else {
                acceptAlarmButton.setText(R.string.AcceptAlarm);
                acceptAlarmButton.setBackgroundColor(Color.GRAY);
                if (mConnection.mBound)
                    if ((mConnection.mSdServer.isLatchAlarms())
                            || mConnection.mSdServer.mSdData.mFallActive) {
                        acceptAlarmButton.setEnabled(true);
                    } else {
                        acceptAlarmButton.setEnabled(false);
                    }
            }
        } else {
            // acceptAlarmButton.setText(getString(R.string.AcceptAlarm));
            // acceptAlarmButton.setBackgroundColor(Color.DKGRAY);
            // acceptAlarmButton.setEnabled(false);
        }

        // Deal with Cancel Audible button
        Button cancelAudibleButton =
                (Button) mRootView.findViewById(R.id.cancelAudibleButton);
        if (mConnection.mBound)
            if (mConnection.mSdServer.isAudibleCancelled()) {
                cancelAudibleButton.setText(getString(R.string.AudibleAlarmsCancelledFor)
                        + " " + mConnection.mSdServer.
                        cancelAudibleTimeRemaining()
                        + " sec");
                cancelAudibleButton.setEnabled(true);
            } else {
                if (mConnection.mSdServer.mAudibleAlarm) {
                    cancelAudibleButton.setText(R.string.CancelAudibleAlarms);
                    cancelAudibleButton.setEnabled(true);
                } else {
                    cancelAudibleButton.setText(R.string.AudibleAlarmsOff);
                    cancelAudibleButton.setEnabled(false);
                }
            }


    }
}
