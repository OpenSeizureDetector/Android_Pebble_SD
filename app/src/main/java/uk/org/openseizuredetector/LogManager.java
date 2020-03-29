/*
  Android_SD - Android host for Garmin or Pebble watch based seizure detectors.
  See http://openseizuredetector.org for more information.

  Copyright Graham Jones, 2019.

  This file is part of Android_SD.

  Android_SD is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  Android_SD is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with Android_SD.  If not, see <http://www.gnu.org/licenses/>.

*/
package uk.org.openseizuredetector;

import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.AsyncTask;
import android.os.CountDownTimer;
import android.os.Handler;
import android.text.format.Time;
import android.util.Log;

import org.apache.commons.codec.binary.Base64;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static android.database.sqlite.SQLiteDatabase.openOrCreateDatabase;

/**
 * LogManager is a class to handle all aspects of Data Logging within OpenSeizureDetector.
 */
public class LogManager {
    private String TAG = "LogManager";
    private String mDbName = "osdData";
    private String mDbTableName = "datapoints";
    private boolean mLogRemote;
    private boolean mLogRemoteMobile;
    private String mOSDUrl = "https://https://osd.dynu.net/";
    private String mApiToken;
    private OsdDbHelper mOSDDb;
    private RemoteLogTimer mRemoteLogTimer;
    private Context mContext;
    private OsdUtil mUtil;


    public LogManager(Context context) {
        mLogRemote = false;
        mLogRemoteMobile = false;
        mOSDUrl = null;
        mContext = context;

        Handler handler = new Handler();
        mUtil = new OsdUtil(mContext, handler);

        startRemoteLogTimer();
    }

    private boolean openDb() {
        try {
            mOSDDb = new OsdDbHelper(mDbTableName, mContext);
            if (!checkTableExists(mOSDDb, mDbTableName)) {
                Log.e(TAG,"ERROR - Table does not exist");
                return false;
            }
            return true;
        } catch (SQLException e) {
            Log.e(TAG, "Failed to open Database: " + e.toString());
            mOSDDb = null;
            return false;
        }
    }

    private boolean checkTableExists(OsdDbHelper osdDb, String osdTableName) {
        Cursor c = null;
        boolean tableExists = false;
        try {
            c = osdDb.getWritableDatabase().query(osdTableName, null,
                    null, null, null, null, null);
            tableExists = true;
        }
        catch (Exception e) {
            Log.d(TAG, osdTableName+" doesn't exist :(((");
        }
        return tableExists;
    }


    /**
     * Write data to local database
     * FIXME - I am sure we should not be using raw SQL Srings to do this!
     */
    public void writeToLocalDb(SdData sdData) {
        Log.v(TAG, "writeToLocalDb()");
        Time tnow = new Time(Time.getCurrentTimezone());
        tnow.setToNow();
        String dateStr = tnow.format("%Y-%m-%d");
        String SQLStr = "SQLStr";

        try {
            SQLStr = "INSERT INTO "+ mDbTableName
                    + "(dataTime, wearer_id, BattPC, specPow, roiRatio, avAcc, sdAcc, hr, status, dataJSON, uploaded)"
                    + " VALUES("
                    +"CURRENT_TIMESTAMP,"
                    + -1 + ","
                    + sdData.batteryPc + ","
                    + sdData.specPower + ","
                    + 10. * sdData.roiPower / sdData.specPower + ","
                    + sdData.getAvAcc() + ","
                    + sdData.getSdAcc() + ","
                    + sdData.mHR + ","
                    + sdData.alarmState + ","
                    + DatabaseUtils.sqlEscapeString(sdData.toCSVString(true)) + ","
                    + 0
                    +")";
            mOSDDb.getWritableDatabase().execSQL(SQLStr);

        } catch (SQLException e) {
            Log.e(TAG,"writeToLocalDb(): Error Writing Data: " + e.toString());
            Log.e(TAG,"SQLStr was "+SQLStr);
        }

    }

    public void writeToRemoteServer() {
        Log.v(TAG,"writeToRemoteServer()");
        if (!mLogRemote) {
            Log.v(TAG,"mLogRemote not set, not doing anything");
            return;
        }

        if (!mLogRemoteMobile) {
            // Check network state - are we using mobile data?
            if (mUtil.isMobileDataActive()) {
                Log.v(TAG,"Using mobile data, so not doing anything");
                return;
            }
        }

        if (!mUtil.isNetworkConnected()) {
            Log.v(TAG,"No network connection - doing nothing");
            return;
        }

        Log.v(TAG,"Requirements for remote logging met!");
        uploadSdData();
    }


    /**
     * Authenticate using the WebAPI to obtain a token for future API requests.
     * @param uname - user name
     * @param passwd - password
     */
    public void authenticate(String uname, String passwd) {
        Log.v(TAG, "authenticate()");
        // FIXME - this does not work!!!!
        String dataStr = "{'login':"+uname+", 'password':"+passwd+"}";
        //new PostDataTask().execute("http://" + mOSDUrl + ":8080/data", dataStr, mOSDUname, mOSDPasswd);
        String urlStr = mOSDUrl+"/api/accounts/login/";
        Log.v(TAG,"authenticate: url="+urlStr+", data="+dataStr);
        new PostDataTask().execute(
                urlStr, dataStr);
    }

    /**
     * Upload a batch of seizure detector data records to the server..
     * Uses the UploadSdDataTask class to upload the data in the
     * background.  DownloadSdDataTask.onPostExecute() is called on completion.
     */
    public void uploadSdData() {
        Log.v(TAG, "uploadSdData()");
        String dataStr = "data string to upload";
        //new PostDataTask().execute("http://" + mOSDUrl + ":8080/data", dataStr, mOSDUname, mOSDPasswd);
        //new PostDataTask().execute("http://192.168.43.175:8765/datapoints/add", dataStr, mOSDUname, mOSDPasswd);
    }

    private class PostDataTask extends AsyncTask<String, Void, String> {
        @Override
        protected String doInBackground(String... params) {
            // params comes from the execute() call:
            // params[0] is the url,
            // params[1] is the data to send.
            // params[2] is the user name (not used)
            // params[3] is the password (not used)
            int MAXLEN = 500;  // Maximum length of response that we will accept (bytes)
            InputStream is = null;
            String urlStr = params[0];
            String dataStr = params[1];
            String resultStr = "Not Initialised";
            Log.v(TAG,"doInBackgound(): url="+urlStr+" data="+dataStr);
            try {
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setReadTimeout(2000 /* milliseconds */);
                conn.setConnectTimeout(5000 /* milliseconds */);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; utf-8");
                conn.setRequestProperty("Accept", "application/json");
                //String auth = uname + ":" + passwd;
                //byte[] encodedAuth = Base64.encodeBase64(auth.getBytes("utf-8"));
                //String authHeaderValue = "Basic " + new String(encodedAuth);
                //conn.setRequestProperty("Authorization", authHeaderValue);
                conn.setDoInput(true);

                // Put our data into the outputstream associated with the connection.
                OutputStream os = conn.getOutputStream();
                byte[] input = dataStr.getBytes("utf-8");
                os.write(input, 0, input.length);

                // Starts the query
                conn.connect();
                int response = conn.getResponseCode();
                Log.d(TAG, "The response code is: " + response);
                is = conn.getInputStream();

                // Convert the InputStream into a string
                Reader reader = new InputStreamReader(is, "UTF-8");
                char[] buffer = new char[MAXLEN];
                reader.read(buffer);
                resultStr = new String(buffer);

            } catch (IOException e) {
                Log.v(TAG,"doInBackground(): IOException - "+e.toString());
                resultStr = "Error"+e.toString();

                // Makes sure that the InputStream is closed after the app is
                // finished using it.
            } finally {
                if (is != null) {
                    try {
                        is.close();
                    } catch (IOException e) {
                        Log.v(TAG,"doInBackground(): IOException - "+e.toString());
                        resultStr = "Error"+e.toString();
                    }
                }
            }

            if (resultStr.startsWith("Unable to retrieve web page")) {
                Log.v(TAG,"doInBackground() - Unable to retrieve data");
            } else {
                Log.v(TAG,"doInBackground(): result = "+resultStr);
            }
            return (resultStr);

        }
        // onPostExecute displays the results of the AsyncTask.
        @Override
        protected void onPostExecute(String result) {
            Log.v(TAG,"onPostExecute() - result = "+result);
        }
    }




    public void close() {
        mOSDDb.close();
        stopRemoteLogTimer();
    }


    public JSONObject queryDatapoints(String endDateStr, Double duration) {
        Log.d(TAG,"queryDatapoints() - endDateStr="+endDateStr);
        Cursor c = null;
        try {
            c = mOSDDb.getWritableDatabase().query(mDbTableName, null,
                    null, null, null, null, null);
            //c.query("Select * from ? where DataTime < ?", mDbTableName, endDateStr);
        }
        catch (Exception e) {
            Log.d(TAG, mDbTableName+" doesn't exist :(((");
        }
        return(null);
    }

    public class OsdDbHelper extends SQLiteOpenHelper {
        // If you change the database schema, you must increment the database version.
        public static final int DATABASE_VERSION = 1;
        public static final String DATABASE_NAME = "OsdData.db";
        private String mOsdTableName;
        private String TAG = "OsdDbHelper";

        public OsdDbHelper(String osdTableName, Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
            mOsdTableName = osdTableName;
        }
        public void onCreate(SQLiteDatabase db) {
            Log.v(TAG,"onCreate - TableName="+mOsdTableName);
            String SQLStr = "CREATE TABLE IF NOT EXISTS "+mOsdTableName+"("
                    + "id INT AUTO_INCREMENT PRIMARY KEY,"
                    + "dataTime DATETIME,"
                    + "wearer_id INT NOT NULL,"
                    + "BattPC FLOAT,"
                    + "specPow FLOAT,"
                    + "roiRatio FLOAT,"
                    + "avAcc FLOAT,"
                    + "sdAcc FLOAT,"
                    + "HR FLOAT,"
                    + "Status INT,"
                    + "dataJSON TEXT,"
                    + "uploaded INT"
                    + ");";

            db.execSQL(SQLStr);
        }
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // This database is only a cache for online data, so its upgrade policy is
            // to simply to discard the data and start over
            db.execSQL("Drop table if exists " + mOsdTableName + ";");
            onCreate(db);
        }
        public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            onUpgrade(db, oldVersion, newVersion);
        }
    }

    /*
     * Start the timer that will send and SMS alert after a given period.
     */
    private void startRemoteLogTimer() {
        if (mRemoteLogTimer != null) {
            Log.v(TAG, "startRemoteLogTimer -timer already running - cancelling it");
            mRemoteLogTimer.cancel();
            mRemoteLogTimer = null;
        }
        Log.v(TAG, "startRemoteLogTimer() - starting RemoteLogTimer");
        mRemoteLogTimer =
                new RemoteLogTimer(10 * 1000, 1000);
        mRemoteLogTimer.start();
    }


    /*
     * Cancel the SMS timer to prevent the SMS message being sent..
     */
    public void stopRemoteLogTimer() {
        if (mRemoteLogTimer != null) {
            Log.v(TAG, "stopRemoteLogTimer(): cancelling Remote Log timer");
            mRemoteLogTimer.cancel();
            mRemoteLogTimer = null;
        }
    }
    /**
     * Inhibit fault alarm initiation for a period to avoid spurious warning
     * beeps caused by short term network interruptions.
     */
    private class RemoteLogTimer extends CountDownTimer {
        public RemoteLogTimer(long startTime, long interval) {
            super(startTime, interval);
        }

        @Override
        public void onTick(long l) {
            // Do Nothing
        }

        @Override
        public void onFinish() {
            //FIXME - make this do something!
            //Log.v(TAG, "mRemoteLogTimer - onFinish");
            //writeToRemoteServer();
            start();
        }

    }

}
