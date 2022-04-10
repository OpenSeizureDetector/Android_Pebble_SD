package uk.org.openseizuredetector;

//import androidx.appcompat.app.AppCompatActivity;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import androidx.appcompat.app.AppCompatActivity;

import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.TextView;
import android.widget.TimePicker;

import java.text.SimpleDateFormat;
import java.util.Calendar;

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
    private int mYear, mMonth, mDay, mHour, mMinute;
    private String mMsg = "Messages";
    private SdServiceConnection mConnection;
    private OsdUtil mUtil;
    final Handler serverStatusHandler = new Handler();

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
        // It takes a finite time for
        // the mConnection to bind to the service, so we delay half a second to give it chance
        // to connect before trying to get the SdServer LogManager instance)
        // FIXME:  We should probably implement a WaitForConnection function like we do in some of the other activities
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                Log.v(TAG,"onCreate(): setting mLm");
                mLm = mConnection.mSdServer.mLm;
            }
        }, 100);

        //mLm= new LogManager(mContext);

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


        updateUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        mUtil.bindToServer(getApplicationContext(), mConnection);
        //startUiTimer();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopUiTimer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startUiTimer();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mUtil.unbindFromServer(getApplicationContext(), mConnection);
    }


    private void updateUi() {
        //Log.v(TAG,"updateUi()");
        TextView tv;
        Button btn;

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
    }

    View.OnClickListener onOk =
            new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Log.v(TAG, "onOk");
                    //SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    String dateStr=String.format("%4d-%02d-%02d %02d:%02d:30",mYear,mMonth+1,mDay, mHour, mMinute);
                    Log.v(TAG, "onOk() - dateSTr="+dateStr);
                    mLm.createLocalEvent(dateStr,5);
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