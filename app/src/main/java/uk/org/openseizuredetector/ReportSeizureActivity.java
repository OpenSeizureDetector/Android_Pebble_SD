package uk.org.openseizuredetector;

//import androidx.appcompat.app.AppCompatActivity;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.TimePicker;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

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
    final Handler serverStatusHandler = new Handler();
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
        if (Objects.nonNull(mConnection))
            if (!mConnection.mBound) mUtil.bindToServer(this, mConnection);
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
        // We want the UI to update as soon as it is displayed, but it takes a finite time for
        // the mConnection to bind to the service, so we delay half a second to give it chance
        // to connect before trying to update the UI for the first time (it happens again periodically using the uiTimer)
        if (mConnection.mBound) {
            Log.v(TAG, "waitForConnection - Bound!");
            initialiseServiceConnection();
        } else {
            Log.v(TAG, "waitForConnection - waiting...");
            new Handler().postDelayed(new Runnable() {
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

            // Retrieve the JSONObject containing the standard event types.
            // Note this obscure syntax is to avoid having to create another interface, so it is worth it :)
            // See https://medium.com/@pra4mesh/callback-function-in-java-20fa48b27797
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
            new AlertDialog.Builder(mContext)
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
        //Log.v(TAG,"updateUi()");
        TextView tv;
        Button btn;
        RadioButton b;

        tv = (TextView)findViewById(R.id.date_day_tv);
        tv.setText(String.format("%02d",mDay));
        tv = (TextView)findViewById(R.id.date_mon_tv);
        tv.setText(String.format("%02d",mMonth+1));   // Month counted from zero
        tv = (TextView)findViewById(R.id.date_year_tv);
        tv.setText(String.format("%04d",mYear));
        tv = (TextView)findViewById(R.id.time_hh_tv);
        tv.setText(String.format("%02d",mHour));
        tv = (TextView)findViewById(R.id.time_mm_tv);
        tv.setText(String.format("%02d",mMinute));
        tv = (TextView)findViewById(R.id.msg_tv);
        tv.setText(mMsg);

        // Populate event type button group if necessary
        if (mEventTypesList != null && mRedrawEventTypesList) {
            Log.v(TAG, "updateUi: " + mEventTypesList.toString());
            mEventTypeRg.removeAllViews();
            for (String eventTypeStr : mEventTypesList) {
                b = new RadioButton(this);
                b.setText(eventTypeStr);
                mEventTypeRg.addView(b);
            }
            mRedrawEventTypesList = false;
        }


        String seizureTypeStr = null;
        // Find which seizure type is selected
        int checkedRadioButtonId = mEventTypeRg.getCheckedRadioButtonId();
        //Log.i(TAG,"updateUi(): checkedRadioButtonId="+checkedRadioButtonId);
        b = (RadioButton) findViewById(checkedRadioButtonId);
        if (b != null) {
            seizureTypeStr = b.getText().toString();
        }
        Log.i(TAG,"updateUi - SeizureType="+seizureTypeStr);

        // Populate the event sub-types radio button list.
        Log.v(TAG,"updateUi() - meventsubtypeshashmap="+mEventSubTypesHashMap+", mEventSubtypesListChanged="+mEventSubTypesListChanged);
        if (mEventSubTypesHashMap != null && mRedrawEventSubTypesList) {
            Log.v(TAG,"UpdateUi() - populating event sub types list");
            if (seizureTypeStr != null) {
                // based on https://androidexample.com/create-a-simple-listview
                ArrayList<String> subtypesArrayList = mEventSubTypesHashMap.get(seizureTypeStr);
                Log.v(TAG, "updateUi() - eventType=" + seizureTypeStr + ", subtypes=" + subtypesArrayList);
                mEventSubTypeRg.removeAllViews();
                for (String eventSubTypeStr : subtypesArrayList) {
                    b = new RadioButton(this);
                    b.setText(eventSubTypeStr);
                    mEventSubTypeRg.addView(b);
                }
                mRedrawEventSubTypesList = false;
            }
        }
    }

    View.OnClickListener onOk =
            new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    RadioButton b;
                    String seizureTypeStr = null;
                    String seizureSubTypeStr = null;
                    String notesStr = null;
                    Log.v(TAG, "onOk");
                    //SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    String dateStr=String.format("%4d-%02d-%02d %02d:%02d:30",mYear,mMonth+1,mDay, mHour, mMinute);
                    Log.v(TAG, "onOk() - dateSTr="+dateStr);

                    // Read seizure type from radio buttons
                    int checkedRadioButtonId = mEventTypeRg.getCheckedRadioButtonId();
                    b = (RadioButton) findViewById(checkedRadioButtonId);
                    if (b != null) {
                        seizureTypeStr = b.getText().toString();
                    }
                    Log.i(TAG,"onOk() - SeizureType="+seizureTypeStr);

                    checkedRadioButtonId = mEventSubTypeRg.getCheckedRadioButtonId();
                    b = (RadioButton) findViewById(checkedRadioButtonId);
                    if (b != null) {
                        seizureSubTypeStr = b.getText().toString();
                    }
                    Log.i(TAG,"onOk() - SeizureSubType="+seizureSubTypeStr);

                    TextView tv = (TextView)findViewById(R.id.eventNotesTv);
                    notesStr = tv.getText().toString();

                    mLm.createLocalEvent(dateStr,5,seizureTypeStr, seizureSubTypeStr, notesStr,
                            mConnection.mSdServer.mSdData.toSettingsJSON());
                    mUtil.showToast("Seizure Event Created");
                    finish();
                }
            };
    View.OnClickListener onCancel =
            new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Log.v(TAG, "onCancel");
                    finish();
                }
            };
    View.OnClickListener onSelectDate =
            new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Log.v(TAG, "onSelectDate()");
                    DatePickerDialog datePickerDialog = new DatePickerDialog(mContext,
                            new DatePickerDialog.OnDateSetListener() {
                                @Override
                                public void onDateSet(DatePicker view, int year,
                                                      int monthOfYear, int dayOfMonth) {

                                    mYear = year;
                                    mMonth = monthOfYear;
                                    mDay = dayOfMonth;
                                }
                            }, mYear, mMonth, mDay);
                    datePickerDialog.show();
                }
            };

    View.OnClickListener onSelectTime =
            new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Log.v(TAG, "onSelectTime()");
                    TimePickerDialog timePickerDialog = new TimePickerDialog(mContext,
                            new TimePickerDialog.OnTimeSetListener() {

                                @Override
                                public void onTimeSet(TimePicker view, int hourOfDay,
                                                      int minute) {

                                    mHour = hourOfDay;
                                    mMinute = minute;
                                }
                            }, mHour, mMinute, true);
                    timePickerDialog.show();
                }
            };


    RadioGroup.OnCheckedChangeListener onEventTypeChange =
            new RadioGroup.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(RadioGroup group, int checkedId) {
                    mRedrawEventSubTypesList = true;
                    updateUi();
                }
            };
    RadioGroup.OnCheckedChangeListener onEventSubTypeChange =
            new RadioGroup.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(RadioGroup group, int checkedId) {
                    updateUi();
                }
            };


    /*
     * Start the timer that will upload data to the remote server after a given period.
     */
    private void startUiTimer() {
        if (mUiTimer != null) {
            Log.v(TAG, "startUiTimer -timer already running - cancelling it");
            mUiTimer.cancel();
            mUiTimer = null;
        }
        Log.v(TAG, "startUiTimer() - starting UiTimer");
        mUiTimer =
                new UiTimer(1000, 1000);
        mUiTimer.start();
    }

    /*
     * Cancel the remote logging timer to prevent attempts to upload to remote database.
     */
    public void stopUiTimer() {
        if (mUiTimer != null) {
            Log.v(TAG, "stopUiTimer(): cancelling Ui timer");
            mUiTimer.cancel();
            mUiTimer = null;
        }
    }

    /**
     * Update User Interface Periodically
     */
    private class UiTimer extends CountDownTimer {
        public UiTimer(long startTime, long interval) {
            super(startTime, interval);
        }

        @Override
        public void onTick(long l) {
            // Do Nothing
        }

        @Override
        public void onFinish() {
            //Log.v(TAG, "UiTimer - onFinish - Updating UI");
            updateUi();
            // Restart this timer.
            start();
        }

    }


}