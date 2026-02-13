package uk.org.openseizuredetector.activity.main;
import uk.org.openseizuredetector.R;

import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

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
                long tnow = System.currentTimeMillis();
                double tdiff;
                tdiff = (tnow - mConnection.mSdServer.mSdData.dataTimeMillis) / 1000.;
                SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
                String timeStr = timeFormat.format(new Date(mConnection.mSdServer.mSdData.dataTimeMillis));
                tv.setText("Time =" + timeStr
                        + "  (" + String.format("%.1f s, %.0f s)",mConnection.mSdServer.mSdData.timeDiff, tdiff));
                tv.setBackgroundColor(okColour);
                tv.setTextColor(okTextColour);


                // Update overall alarm status (now in a card)
                tv = (TextView) mRootView.findViewById(R.id.alarmTv);
                com.google.android.material.card.MaterialCardView alarmCard =
                        (com.google.android.material.card.MaterialCardView) mRootView.findViewById(R.id.alarmStatusCard);

                if ((mConnection.mSdServer.mSdData.alarmState == 0)
                        && !mConnection.mSdServer.mSdData.alarmStanding
                        && !mConnection.mSdServer.mSdData.fallAlarmStanding) {
                    tv.setText(getString(R.string.okBtnTxt));
                    alarmCard.setCardBackgroundColor(okColour);
                    tv.setTextColor(okTextColour);
                }
                if ((mConnection.mSdServer.mSdData.alarmState == 1)
                        && !mConnection.mSdServer.mSdData.alarmStanding
                        && !mConnection.mSdServer.mSdData.fallAlarmStanding) {
                    tv.setText(R.string.Warning);
                    alarmCard.setCardBackgroundColor(warnColour);
                    tv.setTextColor(warnTextColour);
                }
                if (mConnection.mSdServer.mSdData.alarmState == 6) {
                    tv.setText(R.string.Mute);
                    alarmCard.setCardBackgroundColor(warnColour);
                    tv.setTextColor(warnTextColour);
                }
                if (mConnection.mSdServer.mSdData.alarmStanding) {
                    tv.setText(getString(R.string.Alarm) + "\n" + mConnection.mSdServer.mSdData.alarmCause);
                    alarmCard.setCardBackgroundColor(alarmColour);
                    tv.setTextColor(alarmTextColour);
                }
                if (mConnection.mSdServer.mSdData.fallAlarmStanding) {
                    tv.setText(R.string.Fall);
                    alarmCard.setCardBackgroundColor(alarmColour);
                    tv.setTextColor(alarmTextColour);
                }
                if (mConnection.mSdServer.mSdData.alarmState == 4) {
                    tv.setText(R.string.Fault);
                    alarmCard.setCardBackgroundColor(warnColour);
                    tv.setTextColor(warnTextColour);
                }
                if (mConnection.mSdServer.mSdData.alarmState == 7) {
                    tv.setText(R.string.NetFault);
                    alarmCard.setCardBackgroundColor(warnColour);
                    tv.setTextColor(warnTextColour);
                }


                // Update algorithm status display with color-coded individual algorithm states
                updateAlgorithmStatusDisplay();

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
        }


        // deal with latch alarms button
        Button acceptAlarmButton = (Button) mRootView.findViewById(R.id.acceptAlarmButton);

        if (mConnection.mBound) {
            if ((mConnection.mSdServer.mSmsTimer != null)
                    && (mConnection.mSdServer.mSmsTimer.mTimeLeft > 0)) {
                acceptAlarmButton.setText(getString(R.string.SMSWillBeSentIn) + " " +
                        mConnection.mSdServer.mSmsTimer.mTimeLeft / 1000 +
                        " s - " + getString(R.string.Cancel));
                // REMOVED manual background color setting - use Material state handling
                acceptAlarmButton.setEnabled(true);
            } else {
                acceptAlarmButton.setText(R.string.AcceptAlarm);
                // REMOVED manual background color setting - use Material state handling
                if (mConnection.mBound)
                    if ((mConnection.mSdServer.isLatchAlarms())
                            || mConnection.mSdServer.mSdData.mFallActive) {
                        acceptAlarmButton.setEnabled(true);
                    } else {
                        acceptAlarmButton.setEnabled(false);
                    }
            }
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

    private void updateAlgorithmStatusDisplay() {
        ViewGroup algorithmsContainer = mRootView.findViewById(R.id.algorithms_status_container);
        if (algorithmsContainer == null || !mConnection.mBound) {
            return;
        }

        algorithmsContainer.removeAllViews();

        try {
            uk.org.openseizuredetector.data.SdData sdData = mConnection.mSdServer.mSdData;

            // Display OSD algorithm status
            if (sdData.mOsdAlarmActive) {
                addAlgorithmStatusRow(algorithmsContainer, "OSD", sdData.osdAlgState);
            }

            // Display FLAP algorithm status
            if (sdData.mFlapAlarmActive) {
                addAlgorithmStatusRow(algorithmsContainer, "FLAP", sdData.flapAlgState);
            }

            // Display FALL algorithm status
            if (sdData.mFallActive) {
                addAlgorithmStatusRow(algorithmsContainer, "FALL", sdData.fallAlgState);
            }

            // Display ML algorithm(s) status - now consistently using "ML" labeling
            if (sdData.mCnnAlarmActive) {
                for (int i = 0; i < sdData.mlNumModels && i < 5; i++) {
                    if (sdData.mlModelActive[i]) {
                        String modelLabel = sdData.mlModelNames[i];
                        // If for some reason the name isn't set, fallback to MLx
                        if (modelLabel == null || modelLabel.isEmpty() || modelLabel.equals("CNN")) {
                            modelLabel = "ML" + (i + 1);
                        }
                        addAlgorithmStatusRow(algorithmsContainer, modelLabel, sdData.mlModelStates[i]);
                    }
                }
            }

            // Display HR algorithm status
            if (sdData.mHRAlarmActive) {
                addAlgorithmStatusRow(algorithmsContainer, "HR", sdData.hrAlgState);
            }

            // Display O2Sat algorithm status if active
            if (sdData.mO2SatAlarmActive) {
                // O2Sat doesn't have individual state tracking yet, show as active
                addAlgorithmStatusRow(algorithmsContainer, "O2", 0);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error updating algorithm status: " + e.getMessage(), e);
        }
    }

    private void addAlgorithmStatusRow(ViewGroup container, String algorithmName, int state) {
        // Create a compact badge with the algorithm name and color-coded background
        TextView badge = new TextView(mContext);
        badge.setText(algorithmName);
        badge.setTextSize(11);
        badge.setTypeface(null, android.graphics.Typeface.BOLD);
        badge.setPadding(12, 4, 12, 4);
        badge.setGravity(android.view.Gravity.CENTER);

        // Set background color based on state
        int bgColor;
        int textColor;

        switch (state) {
            case 2: // ALARM
                bgColor = alarmColour;
                textColor = alarmTextColour;
                break;
            case 1: // WARNING
                bgColor = warnColour;
                textColor = warnTextColour;
                break;
            default: // OK
                bgColor = okColour;
                textColor = okTextColour;
                break;
        }

        badge.setTextColor(textColor);

        // Create rounded background
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(bgColor);
        background.setCornerRadius(16f);
        badge.setBackground(background);

        // Add margin between badges
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(2, 0, 2, 0);
        badge.setLayoutParams(params);

        container.addView(badge);
    }
}
