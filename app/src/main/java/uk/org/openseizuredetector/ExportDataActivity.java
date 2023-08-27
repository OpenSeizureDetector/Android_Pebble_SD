package uk.org.openseizuredetector;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.SQLException;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.os.IBinder;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TimePicker;
import android.os.ParcelFileDescriptor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;

public class ExportDataActivity extends AppCompatActivity
        implements View.OnClickListener {
    public interface BooleanCallback {
        void accept(Boolean retVal);
    }


    String TAG = "ExportDataActivity";

    // Request code for creating a PDF document.
    private static final int FILE_REQUEST_CODE = 1353;

    Button mDateBtn;
    Button mTimeBtn;
    Button mExportBtn;
    EditText mDateTxt;
    EditText mTimeTxt;
    EditText mDurationTxt;

    Date mEndDate;

    int mYear;
    int mMonth;
    int mDay;
    int mHour;
    int mMinute;
    double mDuration;

    OsdUtil mUtil;
    Handler mHandler;

    SdServiceConnection mConnection;
    boolean mConnected;
    LogManager mLm;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dbquery);

        mHandler = new Handler();
        mUtil = new OsdUtil(this, mHandler);

        mDateBtn = (Button) findViewById(R.id.dateBtn);
        mDateBtn.setOnClickListener(this);
        mTimeBtn = (Button) findViewById(R.id.timeBtn);
        mTimeBtn.setOnClickListener(this);
        mExportBtn = (Button) findViewById(R.id.exportBtn);
        mExportBtn.setOnClickListener(this);
        mExportBtn.setEnabled(false);
        mDateTxt = (EditText) findViewById(R.id.endDateText);
        mTimeTxt = (EditText) findViewById(R.id.endTimeText);
        mDurationTxt = (EditText) findViewById(R.id.durationText);

        // Get Current Date
        final Calendar c = Calendar.getInstance();
        mYear = c.get(Calendar.YEAR);
        mMonth = c.get(Calendar.MONTH);
        mDay = c.get(Calendar.DAY_OF_MONTH);
        mHour = c.get(Calendar.HOUR_OF_DAY);
        mMinute = c.get(Calendar.MINUTE);

        mDateTxt.setText(String.format("%02d-%02d-%04d", mDay, mMonth + 1, mYear));
        mTimeTxt.setText(String.format("%02d:%02d:%02d", mHour, mMinute, 00));
        mDuration = 2.0;
        mDurationTxt.setText(String.format("%03.1f", mDuration));

        mConnection = new SdServiceConnection(getApplicationContext());
        mConnected = false;


    }

    @Override
    public void onStart() {
        super.onStart();
        mUtil.bindToServer(getApplicationContext(), mConnection);
        waitForConnection();

    }

    @Override
    protected void onStop() {
        Log.v(TAG, "onStop()");
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

    // FIXME - for some reason this never gets called, which is why we have the 'waitForConnection()'
    //         function that polls the connection until it is connected.
    public void onServiceConnected(ComponentName name, IBinder service) {
        Log.w(TAG, "onServiceConnected()");
        initialiseServiceConnection();
    }


    private void initialiseServiceConnection() {
        mConnected = true;
        mExportBtn.setEnabled(true);
        mLm = mConnection.mSdServer.mLm;

        //mUtil.showToast("Connected!!");
    }

    @Override
    public void onClick(View view) {
        Log.v(TAG, "onClick()");
        if (view == mDateBtn) {
            DatePickerDialog datePickerDialog = new DatePickerDialog(this,
                    new DatePickerDialog.OnDateSetListener() {
                        @Override
                        public void onDateSet(DatePicker view, int year,
                                              int monthOfYear, int dayOfMonth) {
                            mDay = dayOfMonth;
                            mMonth = monthOfYear;
                            mYear = year;
                            mDateTxt.setText(String.format("%02d-%02d-%04d", mDay, mMonth + 1, mYear));
                        }
                    }, mYear, mMonth, mDay);
            datePickerDialog.show();
        }
        if (view == mTimeBtn) {
            // Launch Time Picker Dialog
            TimePickerDialog timePickerDialog = new TimePickerDialog(this,
                    new TimePickerDialog.OnTimeSetListener() {
                        @Override
                        public void onTimeSet(TimePicker view, int hourOfDay,
                                              int minute) {
                            mHour = hourOfDay;
                            mMinute = minute;
                            mTimeTxt.setText(String.format("%02d:%02d:%02d", mHour, mMinute, 00));
                        }
                    }, mHour, mMinute, false);
            timePickerDialog.show();
        }
        if (view == mExportBtn) {
            mDateTxt.setText(String.format("%02d-%02d-%04d", mDay, mMonth + 1, mYear));
            mTimeTxt.setText(String.format("%02d:%02d:%02d", mHour, mMinute, 00));
            mDuration = Double.parseDouble(mDurationTxt.getText().toString());

            String dateTimeStr = String.format("%04d-%02d-%02dT%02d:%02d:%02dZ", mYear, mMonth + 1, mDay, mHour, mMinute, 00);
            //mUtil.showToast(dateTimeStr);
            mEndDate = mUtil.string2date(dateTimeStr);
            //mUtil.showToast(mEndDate.toString());

            //mUtil.showToast(String.format("EndDate=%s %s, Duration=%3.1f hrs",
            //        mDateTxt.getText().toString(), mTimeTxt.getText().toString(), mDuration));
            Log.d(TAG, String.format("EndDate=%s %s, Duration=%3.1f hrs",
                    mDateTxt.getText().toString(), mTimeTxt.getText().toString(), mDuration));
            ProgressBar pb = (ProgressBar) findViewById(R.id.exportPb);
            pb.setIndeterminate(true);
            pb.setVisibility(View.VISIBLE);

            mExportBtn.setEnabled(false);
            mExportBtn.setVisibility(View.INVISIBLE);
            this.openFile();

        }
    }

    private void openFile() {

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, "osd_data.csv");

        //intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, pickerInitialUri);
        Log.v(TAG, "openFile() - showing open dialog");
        startActivityForResult(intent, FILE_REQUEST_CODE);

    }

    // Called when the file picker created in openFile() is closed.
    @Override
    public void onActivityResult(int requestCode, int resultCode,
                                 Intent resultData) {
        Log.v(TAG, "onActivityResult - requestCode=" + requestCode);
        if (requestCode == FILE_REQUEST_CODE
                && resultCode == Activity.RESULT_OK) {
            // The result data contains a URI for the document or directory that
            // the user selected.
            Uri uri = null;
            if (resultData != null) {
                uri = resultData.getData();
                // Perform operations on the document using its URI.
                //mUtil.showToast("URI="+uri.toString());
                Log.v(TAG, "onActivityResult() - exporting to file " + uri.toString());
                exportToFile(uri);

            }
        }
        super.onActivityResult(requestCode, resultCode, resultData);
    }

    private void exportToFile(Uri uri) {
        Log.v(TAG, "exportToFile(): uri=" + uri.toString());
        long endDateMillis = mEndDate.getTime();
        long durationMillis = (long) (mDuration * 3600. * 1000);
        long startDateMillis = endDateMillis - durationMillis;
        Log.v(TAG, "exportToFile() - endDateMillis=" + endDateMillis + ", startDateMillis=" + startDateMillis + ", durationMillis=" + durationMillis);
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String sDateStr = dateFormat.format(new Date(startDateMillis));
        String eDateStr = dateFormat.format(new Date(endDateMillis));
        Log.v(TAG, "exportToFile() - sDateStr=" + sDateStr + " eDateStr=" + eDateStr);
        mLm.getDatapointsByDate(
                sDateStr, eDateStr, (String datapointsJsonStr) -> {
                    Log.v(TAG, "exportToFile() - datapoints=" + datapointsJsonStr);
                    // Open file for writing
                    try {
                        ParcelFileDescriptor pfd = this.getContentResolver().
                                openFileDescriptor(uri, "w");
                        FileOutputStream fileOutputStream =
                                new FileOutputStream(pfd.getFileDescriptor());
                        fileOutputStream.write(("# dataTime, alarmState, hr, o2sat, accel*125\n").getBytes());
                        JSONArray dataObj;
                        try {
                            dataObj = new JSONArray(datapointsJsonStr);
                            Log.v(TAG, "exportToFile() - dataObj length=" + dataObj.length());
                            JSONObject datapointJsonObj = null;
                            String dataJsonStr = null;
                            JSONObject dataJsonObj = null;
                            JSONArray rawDataArr = null;
                            for (int i = 0; i < dataObj.length(); i++) {
                                datapointJsonObj = dataObj.getJSONObject(i);
                                dataJsonStr = datapointJsonObj.getString("dataJSON");
                                Log.v(TAG, "exportToFile() - i=" + i + "dataJsonStr=" + dataJsonStr);
                                dataJsonObj = new JSONObject(dataJsonStr);
                                rawDataArr = dataJsonObj.getJSONArray("rawData");
                                try {
                                    fileOutputStream.write(dataJsonObj.getString("dataTime").getBytes(StandardCharsets.UTF_8));
                                    fileOutputStream.write(", ".getBytes(StandardCharsets.UTF_8));
                                    fileOutputStream.write(dataJsonObj.getString("alarmState").getBytes(StandardCharsets.UTF_8));
                                    fileOutputStream.write(", ".getBytes(StandardCharsets.UTF_8));
                                    fileOutputStream.write(dataJsonObj.getString("hr").getBytes(StandardCharsets.UTF_8));
                                    fileOutputStream.write(", ".getBytes(StandardCharsets.UTF_8));
                                    fileOutputStream.write(dataJsonObj.getString("o2Sat").getBytes(StandardCharsets.UTF_8));
                                    for (int j = 0; j < rawDataArr.length(); j++) {
                                        fileOutputStream.write(", ".getBytes(StandardCharsets.UTF_8));
                                        fileOutputStream.write(rawDataArr.getString(j).getBytes(StandardCharsets.UTF_8));
                                    }
                                    fileOutputStream.write("\n".getBytes(StandardCharsets.UTF_8));
                                } catch (IOException e) {
                                    Log.e(TAG, "exportToFile() - ERROR Writing File: " + e.toString());
                                    //mUtil.showToast("ERROR WRITING FILE");
                                }
                                datapointJsonObj = null;
                                dataJsonStr = null;
                                dataJsonObj = null;
                                rawDataArr = null;
                            }
                        } catch (JSONException | NullPointerException e) {
                            Log.v(TAG, "createEventCallback(): Error Creating JSON Object from string " + datapointsJsonStr);
                            dataObj = null;
                            mUtil.showToast(getString(R.string.error_exporting_data));
                            Log.e(TAG, "exportToFile() - JSONException: " + e.toString());
                        }
                        // Let the document provider know you're done by closing the stream.
                        fileOutputStream.close();
                        pfd.close();
                        mUtil.showToast(getString(R.string.data_exported_ok));

                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                        mUtil.showToast(getString(R.string.error_exporting_data));
                        Log.e(TAG, "exportToFile() - FileNotFoundException: " + e.toString());
                    } catch (IOException e) {
                        e.printStackTrace();
                        mUtil.showToast(getString(R.string.error_exporting_data));
                        Log.e(TAG, "exportToFile() - IOException: " + e.toString());
                    }
                    runOnUiThread(new Runnable() {
                        public void run() {
                            ProgressBar pb = (ProgressBar) findViewById(R.id.exportPb);
                            pb.setIndeterminate(true);
                            pb.setVisibility(View.INVISIBLE);
                            mExportBtn.setEnabled(true);
                            mExportBtn.setVisibility(View.VISIBLE);

                        }
                    });

                });
    }



}
