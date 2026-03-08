package uk.org.openseizuredetector.activity.main;
import uk.org.openseizuredetector.R;

import android.graphics.Color;
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

import uk.org.openseizuredetector.data.AlarmState;

public class FragmentCommon extends FragmentOsdBaseClass {
    String TAG = "FragmentCommon";

    // Store mode state for dynamic UI generation (e.g. algorithm badges)
    private boolean mIsBasicMode = true;

    private String mLastAlarmText;
    private int mLastAlarmCardColor = Color.TRANSPARENT;
    private int mLastAlarmTextColor = Color.TRANSPARENT;
    private final java.util.Map<String, Integer> mAlgorithmStateCache = new java.util.LinkedHashMap<>();

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
        // Choose layout based on the current mode so advanced stays compact.
        boolean basicMode = isBasicMode();
        mIsBasicMode = basicMode;
        int layoutRes = basicMode ? R.layout.fragment_common : R.layout.fragment_common_advanced;
        return inflater.inflate(layoutRes, container, false);
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
                    // Force immediate UI update so user sees status change right away
                    updateUi();
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
                    // Force immediate UI update so user sees MUTE status right away
                    updateUi();
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

        // Update data time if we have a connection
        if (mConnection.mBound) {
            tv = (TextView) mRootView.findViewById(R.id.data_time_tv);
            long tnow = System.currentTimeMillis();
            double tdiff;
            tdiff = (tnow - mConnection.mSdServer.mSdData.dataTimeMillis) / 1000.;
            SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            String timeStr = timeFormat.format(new Date(mConnection.mSdServer.mSdData.dataTimeMillis));
            if (mIsBasicMode) {
                tv.setText("Time =" + timeStr);
            } else {
                tv.setText("Time =" + timeStr
                        + "  (" + String.format(Locale.getDefault(), "%.1f s, %.0f s", mConnection.mSdServer.mSdData.timeDiff, tdiff) + ")");
            }
            tv.setBackgroundColor(okColour);
            tv.setTextColor(okTextColour);

            // Update overall alarm status (now in a card)
            tv = (TextView) mRootView.findViewById(R.id.alarmTv);
            com.google.android.material.card.MaterialCardView alarmCard =
                    (com.google.android.material.card.MaterialCardView) mRootView.findViewById(R.id.alarmStatusCard);

            long currentState = mConnection.mSdServer.mSdData.alarmState;

            String alarmText = getString(R.string.okBtnTxt);
            int alarmCardColor = okColour;
            int alarmTextColor = okTextColour;

            if ((currentState == AlarmState.WARNING)
                    && !mConnection.mSdServer.mSdData.alarmStanding
                    && !mConnection.mSdServer.mSdData.fallAlarmStanding) {
                alarmText = getString(R.string.Warning);
                alarmCardColor = warnColour;
                alarmTextColor = warnTextColour;
            }
            if (currentState == AlarmState.MUTE) {
                alarmText = getString(R.string.Mute);
                alarmCardColor = warnColour;
                alarmTextColor = warnTextColour;
            }
            if (mConnection.mSdServer.mSdData.alarmStanding) {
                alarmText = getString(R.string.Alarm) + " : " + mConnection.mSdServer.mSdData.alarmCause;
                alarmCardColor = alarmColour;
                alarmTextColor = alarmTextColour;
            }
            if (mConnection.mSdServer.mSdData.fallAlarmStanding) {
                alarmText = getString(R.string.Fall);
                alarmCardColor = alarmColour;
                alarmTextColor = alarmTextColour;
            }
            if (currentState == AlarmState.FAULT) {
                String errorDetails = mConnection.mSdServer.mSdData.alarmCause;
                if (errorDetails != null && !errorDetails.isEmpty()) {
                     alarmText = getString(R.string.Fault) + " : " + errorDetails;
                } else {
                     alarmText = getString(R.string.Fault);
                }
                alarmCardColor = warnColour;
                alarmTextColor = warnTextColour;
            }
            if (currentState == AlarmState.NETFAULT) {
                alarmText = getString(R.string.NetFault);
                alarmCardColor = warnColour;
                alarmTextColor = warnTextColour;
            }

            applyAlarmDisplay(tv, alarmCard, alarmText, alarmCardColor, alarmTextColor);

            // Update algorithm status display with color-coded individual algorithm states
            updateAlgorithmStatusDisplay();

            // The dataSourceInfoTv and serverStatusTv are now shown in FragmentSystem

        }


        // deal with latch alarms button (Accept Alarm)
        Button acceptAlarmButton = (Button) mRootView.findViewById(R.id.acceptAlarmButton);

        if (mConnection.mBound) {
            // Check if latch alarms preference is enabled
            boolean latchAlarmsEnabled = mConnection.mSdServer.isLatchAlarms();
            boolean fallActive = mConnection.mSdServer.mSdData.mFallActive;

            // Show/hide Accept Alarm button based on preferences
            if (latchAlarmsEnabled || fallActive) {
                //Log.d(TAG, "latchAlarmsEnabled: " + latchAlarmsEnabled + ", fallActive: " + fallActive+ " - enabling acceptAlarm button");
                acceptAlarmButton.setEnabled(true);
                acceptAlarmButton.setVisibility(View.VISIBLE);
            } else {
                //Log.d(TAG,"latchAlarmsEnabled: " + latchAlarmsEnabled + ", fallActive: " + fallActive+ " - disabling acceptAlarm button");
                acceptAlarmButton.setVisibility(View.GONE);
            }

            // Handle the  SMS countdown timer button
            if ((mConnection.mSdServer.mSmsTimer != null)
                && (mConnection.mSdServer.mSmsTimer.mTimeLeft > 0)) {
                    acceptAlarmButton.setText(getString(R.string.SMSWillBeSentIn) + " " +
                            mConnection.mSdServer.mSmsTimer.mTimeLeft / 1000 +
                            " s - " + getString(R.string.Cancel));
                    acceptAlarmButton.setVisibility(View.VISIBLE);
                    acceptAlarmButton.setEnabled(true);
            } else {
                    acceptAlarmButton.setText(R.string.AcceptAlarm);
                    acceptAlarmButton.setEnabled(true);
            }
        }

        // Deal with Mute/Cancel Audible button - should ALWAYS be active regardless of audible alarms
        // This is because it also mutes SMS alerts
        Button cancelAudibleButton =
                (Button) mRootView.findViewById(R.id.cancelAudibleButton);
        if (mConnection.mBound) {
            if (mConnection.mSdServer.isAudibleCancelled()) {
                cancelAudibleButton.setText(getString(R.string.AudibleAlarmsCancelledFor)
                        + " " + mConnection.mSdServer.
                        cancelAudibleTimeRemaining()
                        + " sec");
            } else {
                cancelAudibleButton.setText(R.string.CancelAudibleAlarms);
            }
            // Always enable the mute button - it should be active regardless of audible alarm state
            // since it also mutes SMS messages
            cancelAudibleButton.setEnabled(true);
        }


    }

    @Override
    protected void updateUiFast() {
        updateUi();
    }

    @Override
    protected void updateUiOnNewData() {
        // FragmentCommon uses only fast updates to avoid flicker.
    }

    private void updateAlgorithmStatusDisplay() {
        ViewGroup algorithmsContainer = mRootView.findViewById(R.id.algorithms_status_container);
        if (algorithmsContainer == null || !mConnection.mBound) {
            return;
        }

        try {
            uk.org.openseizuredetector.data.SdData sdData = mConnection.mSdServer.mSdData;
            java.util.Map<String, Integer> currentStates = collectAlgorithmStates(sdData);
            if (currentStates.equals(mAlgorithmStateCache)) {
                return;
            }
            algorithmsContainer.removeAllViews();
            mAlgorithmStateCache.clear();
            mAlgorithmStateCache.putAll(currentStates);
            for (java.util.Map.Entry<String, Integer> entry : currentStates.entrySet()) {
                addAlgorithmStatusRow(algorithmsContainer, entry.getKey(), entry.getValue());
            }

        } catch (Exception e) {
            Log.e(TAG, "Error updating algorithm status: " + e.getMessage(), e);
        }
    }

    private void addAlgorithmStatusRow(ViewGroup container, String algorithmName, int state) {
        // Create a compact badge with the algorithm name and color-coded background
        TextView badge = new TextView(mContext);
        badge.setText(algorithmName);

        // Scale badge size for Basic Mode
        if (mIsBasicMode) {
            badge.setPadding(24, 8, 24, 8);
        } else {
            badge.setPadding(12, 4, 12, 4);
        }

        badge.setTypeface(null, android.graphics.Typeface.BOLD);
        badge.setGravity(android.view.Gravity.CENTER);

        // Set background color based on state
        int bgColor;
        int textColor;

        switch (state) {
            case AlarmState.ALARM: // ALARM
                bgColor = alarmColour;
                textColor = alarmTextColour;
                break;
            case AlarmState.WARNING: // WARNING
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

        if (mIsBasicMode) {
            params.setMargins(8, 0, 8, 0);
            badge.setTextSize(18);
        } else {
            params.setMargins(2, 0, 2, 0);
            badge.setTextSize(11);
        }
        badge.setLayoutParams(params);

        container.addView(badge);
    }

    private java.util.Map<String, Integer> collectAlgorithmStates(uk.org.openseizuredetector.data.SdData sdData) {
        java.util.Map<String, Integer> states = new java.util.LinkedHashMap<>();
        if (sdData.mOsdAlarmActive) {
            states.put("OSD", sdData.osdAlgState);
        }
        if (sdData.mFlapAlarmActive) {
            states.put("FLAP", sdData.flapAlgState);
        }
        if (sdData.mFallActive) {
            states.put("FALL", sdData.fallAlgState);
        }
        if (sdData.mCnnAlarmActive) {
            for (int i = 0; i < sdData.mlNumModels && i < 5; i++) {
                if (sdData.mlModelActive[i]) {
                    String modelLabel = sdData.mlModelNames[i];
                    if (modelLabel == null || modelLabel.isEmpty() || modelLabel.equals("CNN")) {
                        modelLabel = "ML" + (i + 1);
                    }
                    states.put(modelLabel, sdData.mlModelStates[i]);
                }
            }
        }
        if (sdData.mHRAlarmActive) {
            states.put("HR", sdData.hrAlgState);
        }
        if (sdData.mO2SatAlarmActive) {
            states.put("O2", 0);
        }
        return states;
    }

    private void applyAlarmDisplay(TextView tv, com.google.android.material.card.MaterialCardView card,
                                   String text, int cardColor, int textColor) {
        if (tv != null && (mLastAlarmText == null || !mLastAlarmText.equals(text))) {
            tv.setText(text);
            mLastAlarmText = text;
        }
        if (card != null && cardColor != mLastAlarmCardColor) {
            card.setCardBackgroundColor(cardColor);
            mLastAlarmCardColor = cardColor;
        }
        if (tv != null && textColor != mLastAlarmTextColor) {
            tv.setTextColor(textColor);
            mLastAlarmTextColor = textColor;
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateLayoutMode();
    }

    private void updateLayoutMode() {
        if (mContext == null || mRootView == null) return;

        boolean basicMode = isBasicMode();

        // Update class member so dynamic UI generation knows the mode
        if (mIsBasicMode == basicMode) {
            return;
        }
        mIsBasicMode = basicMode;

        // Refresh algorithm badges immediately in case the mode changed in settings
        updateAlgorithmStatusDisplay();
    }
}
