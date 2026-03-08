package uk.org.openseizuredetector.activity.events;
import uk.org.openseizuredetector.R;

//import androidx.appcompat.app.AppCompatActivity;

import uk.org.openseizuredetector.activity.logging.LogManager;
import uk.org.openseizuredetector.client.SdServiceConnection;
import uk.org.openseizuredetector.comms.WebApiConnection;
import uk.org.openseizuredetector.data.AlarmState;
import uk.org.openseizuredetector.utils.OsdUtil;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.TimePicker;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

/**
 * ReportSeizureActivity - Allows the user to report a seizure manually, which is saved in the database for
 * future analysis - particularlly useful if OpenSeizureDetector did not detect the seizure automatically as this
 * will ensure the data for the missed seizure is saved.
 * Based on: https://www.journaldev.com/9976/android-date-time-picker-dialog
 */
public class ReportSeizureActivity extends AppCompatActivity {
    private String TAG = "ReportSeizureActivity";
    private Context mContext;
    private UiTimer mUiTimer;
    private LogManager mLm;
    private WebApiConnection mWac;

    private int mYear, mMonth, mDay, mHour, mMinute;
    private String mMsg = "Messages";
    private SdServiceConnection mConnection;
    private OsdUtil mUtil;
    final Handler serverStatusHandler = new Handler(Looper.getMainLooper());
    private List<String> mEventTypesList = null;
    private HashMap<String, ArrayList<String>> mEventSubTypesHashMap = null;
    private String mEventTypeStr = null;
    private String mEventSubTypeStr = null;
    private String mEventNotes = "";
    private RadioGroup mEventTypeRg;
    private boolean mRedrawEventSubTypesList = false;
    private boolean mRedrawEventTypesList = false;
    private RadioGroup mEventSubTypeRg;
    private boolean mEventSubTypesListChanged = false;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.v(TAG, "onCreate()");
        super.onCreate(savedInstanceState);
        mContext = this;
        mUtil = new OsdUtil(this, serverStatusHandler);
        if (!mUtil.isServerRunning()) {
            mUtil.showToast(getString(R.string.error_server_not_running));
            finish();
            return;
        }
        mContext = this;
        mConnection = new SdServiceConnection(getApplicationContext());

        setContentView(R.layout.activity_report_seizure);
        // Handle system window insets
        View rootView = findViewById(R.id.root_layout_report_seizure);
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
            int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            LinearLayout content = findViewById(R.id.report_seizure_content_layout);
            if (content != null) {
                content.setPadding(16, top + 16, 16, bottom + 16);
            }
            return WindowInsetsCompat.CONSUMED;
        });


        mEventTypeRg = findViewById(R.id.eventTypeRg);
        mEventTypeRg.setOnCheckedChangeListener(onEventTypeChange);
        mEventSubTypeRg = findViewById(R.id.eventSubTypeRg);
        mEventSubTypeRg.setOnCheckedChangeListener(onEventSubTypeChange);

        Button okBtn =
                (Button) findViewById(R.id.loginBtn);
        okBtn.setOnClickListener(onOk);

        Button cancelBtn =
                (Button) findViewById(R.id.cancelBtn);
        cancelBtn.setOnClickListener(onCancel);

        Button setDateBtn =
                (Button) findViewById(R.id.select_date_button);
        setDateBtn.setOnClickListener(onSelectDate);

        Button setTimeBtn =
                (Button) findViewById(R.id.select_time_button);
        setTimeBtn.setOnClickListener(onSelectTime);

        // Get Current Date
        final Calendar c = Calendar.getInstance();
        mYear = c.get(Calendar.YEAR);
        mMonth = c.get(Calendar.MONTH);
        mDay = c.get(Calendar.DAY_OF_MONTH);
        mHour = c.get(Calendar.HOUR_OF_DAY);
        mMinute = c.get(Calendar.MINUTE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mUtil.bindToServer(getApplicationContext(), mConnection);
        waitForConnection();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopUiTimer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        //startUiTimer();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mUtil.unbindFromServer(getApplicationContext(), mConnection);
    }

    private void waitForConnection() {
        if (mConnection.mBound) {
            Log.v(TAG, "waitForConnection - Bound!");
            initialiseServiceConnection();
        } else {
            Log.v(TAG, "waitForConnection - waiting...");
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    waitForConnection();
                }
            }, 100);
        }
    }

    private void initialiseServiceConnection() {
        mLm = mConnection.mSdServer.mLm;
        mWac = mConnection.mSdServer.mLm.mWac;

        if (mWac.isLoggedIn()) {
            mWac.getEventTypes(new WebApiConnection.JSONObjectCallback() {
                @Override
                public void accept(JSONObject eventTypesObj) {
                    Log.v(TAG, "initialiseServiceConnection().onEventTypesReceived");
                    if (eventTypesObj == null) {
                        Log.e(TAG, "initialiseServiceConnection().getEventTypes Callback:  Error Retrieving event types");
                        mUtil.showToast("Error Retrieving Event Types from Server - Please Try Again Later!");
                    } else {
                        Iterator<String> keys = eventTypesObj.keys();
                        mEventTypesList = new ArrayList<String>();
                        mEventSubTypesHashMap = new HashMap<String, ArrayList<String>>();
                        while (keys.hasNext()) {
                            String key = keys.next();
                            Log.v(TAG, "initialiseServiceConnection().getEventTypes Callback: key=" + key);
                            mEventTypesList.add(key);
                            try {
                                JSONArray eventSubTypes = eventTypesObj.getJSONArray(key);
                                ArrayList<String> eventSubtypesList = new ArrayList<String>();
                                for (int i = 0; i < eventSubTypes.length(); i++) {
                                    eventSubtypesList.add(eventSubTypes.getString(i));
                                }
                                mEventSubTypesHashMap.put(key, eventSubtypesList);
                                mRedrawEventSubTypesList = true;
                            } catch (JSONException e) {
                                Log.e(TAG, "initialiseServiceConnection().getEventTypes Callback: Error parsing JSONObject" + e.getMessage() + e.toString());
                            }
                        }
                        mRedrawEventTypesList = true;
                        updateUi();
                    }
                }
            });
        } else {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.not_logged_in_dialog_title)
                    .setMessage(R.string.not_logged_in_dialog_message)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int whichButton) {
                            finish();
                        }
                    })
                    .show();

        }
    }


    private void updateUi() {
        TextView tv;
        Button btn;
        RadioButton b;

        tv = (TextView) findViewById(R.id.date_day_tv);
        tv.setText(String.format("%02d", mDay));
        tv = (TextView) findViewById(R.id.date_mon_tv);
        tv.setText(String.format("%02d", mMonth + 1));
        tv = (TextView) findViewById(R.id.date_year_tv);
        tv.setText(String.format("%04d", mYear));
        tv = (TextView) findViewById(R.id.time_hh_tv);
        tv.setText(String.format("%02d", mHour));
        tv = (TextView) findViewById(R.id.time_mm_tv);
        tv.setText(String.format("%02d", mMinute));
        tv = (TextView) findViewById(R.id.msg_tv);
        tv.setText(mMsg);

        // Populate event type button group
        if (mEventTypesList != null && mRedrawEventTypesList) {
            Log.v(TAG, "updateUi: " + mEventTypesList.toString());
            mEventTypeRg.removeAllViews();
            for (String eventTypeStr : mEventTypesList) {
                b = new RadioButton(this);
                b.setText(eventTypeStr);
                // REMOVED manual text color setting - let theme handle it
                mEventTypeRg.addView(b);
            }
            mRedrawEventTypesList = false;
        }


        String seizureTypeStr = null;
        int checkedRadioButtonId = mEventTypeRg.getCheckedRadioButtonId();
        b = (RadioButton) findViewById(checkedRadioButtonId);
        if (b != null) {
            seizureTypeStr = b.getText().toString();
        }

        // Populate the event sub-types radio button list.
        if (mEventSubTypesHashMap != null && mRedrawEventSubTypesList) {
            if (seizureTypeStr != null) {
                ArrayList<String> subtypesArrayList = mEventSubTypesHashMap.get(seizureTypeStr);
                mEventSubTypeRg.removeAllViews();
                for (String eventSubTypeStr : subtypesArrayList) {
                    b = new RadioButton(this);
                    b.setText(eventSubTypeStr);
                    // REMOVED manual text color setting - let theme handle it
                    mEventSubTypeRg.addView(b);
                }
                mRedrawEventSubTypesList = false;
            }
        }
    }

    View.OnClickListener onOk = view -> {
        RadioButton b;
        String seizureTypeStr = null;
        String seizureSubTypeStr = null;
        String notesStr = null;
        String dateStr = String.format("%4d-%02d-%02d %02d:%02d:30", mYear, mMonth + 1, mDay, mHour, mMinute);

        int checkedRadioButtonId = mEventTypeRg.getCheckedRadioButtonId();
        b = (RadioButton) findViewById(checkedRadioButtonId);
        if (b != null) {
            seizureTypeStr = b.getText().toString();
        }

        checkedRadioButtonId = mEventSubTypeRg.getCheckedRadioButtonId();
        b = (RadioButton) findViewById(checkedRadioButtonId);
        if (b != null) {
            seizureSubTypeStr = b.getText().toString();
        }

        TextView tv = (TextView) findViewById(R.id.eventNotesTv);
        notesStr = tv.getText().toString();

        mLm.createLocalEvent(dateStr, AlarmState.MANUAL, seizureTypeStr, seizureSubTypeStr, "Manual", notesStr,
                mConnection.mSdServer.mSdData.toSettingsJSON());
        mUtil.showToast("Seizure Event Created");
        finish();
    };

    View.OnClickListener onCancel = view -> finish();

    View.OnClickListener onSelectDate = view -> {
        DatePickerDialog datePickerDialog = new DatePickerDialog(mContext,
                (view1, year, monthOfYear, dayOfMonth) -> {
                    mYear = year;
                    mMonth = monthOfYear;
                    mDay = dayOfMonth;
                    updateUi();
                }, mYear, mMonth, mDay);
        datePickerDialog.show();
    };

    View.OnClickListener onSelectTime = view -> {
        TimePickerDialog timePickerDialog = new TimePickerDialog(mContext,
                (view1, hourOfDay, minute) -> {
                    mHour = hourOfDay;
                    mMinute = minute;
                    updateUi();
                }, mHour, mMinute, true);
        timePickerDialog.show();
    };


    RadioGroup.OnCheckedChangeListener onEventTypeChange = (group, checkedId) -> {
        mRedrawEventSubTypesList = true;
        updateUi();
    };
    RadioGroup.OnCheckedChangeListener onEventSubTypeChange = (group, checkedId) -> updateUi();


    public void stopUiTimer() {
        if (mUiTimer != null) {
            mUiTimer.cancel();
            mUiTimer = null;
        }
    }

    private class UiTimer extends CountDownTimer {
        public UiTimer(long startTime, long interval) {
            super(startTime, interval);
        }
        @Override
        public void onTick(long l) {}
        @Override
        public void onFinish() {
            updateUi();
            start();
        }
    }
}
