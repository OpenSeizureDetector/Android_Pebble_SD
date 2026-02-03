package uk.org.openseizuredetector.datasource;

import uk.org.openseizuredetector.data.SdData;
import uk.org.openseizuredetector.utils.BackgroundTaskExecutor;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import androidx.preference.PreferenceManager;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Created by graham on 22/11/15.
 */
public class SdDataSourceNetwork extends SdDataSource {
    private String TAG = "SdDataSourceNetwork";
    private Timer mDataUpdateTimer;
    private int mDataUpdatePeriod = 2000;
    private int mConnnectTimeoutPeriod = 5000;
    private int mReadTimeoutPeriod = 5000;
    private String mServerIP = "unknown";

    private int ALARM_STATE_NETFAULT = 7;


    public SdDataSourceNetwork(Context context, Handler handler, SdDataReceiver sdDataReceiver) {
        super(context, handler, sdDataReceiver);
        mName = "Network";
    }

    @Override
    public void start() {
        // Update preferences.
        Log.v(TAG, "start(): calling updatePrefs()");
        mUtil.writeToSysLogFile("SdDataSourceNetwork().start()");
        updatePrefs();

        // Start timer to retrieve seizure detector data regularly.
        if (mDataUpdateTimer == null) {
            Log.v(TAG, "start(): starting data update timer");
            mDataUpdateTimer = new Timer();
            mDataUpdateTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    downloadSdData();
                }
            }, 0, mDataUpdatePeriod);
        } else {
            Log.v(TAG, "start(): data update timer already running.");
        }


    }

    @Override
    public void stop() {
        mUtil.writeToSysLogFile("SdDataSourceNetwork().stop()");
        // Stop the data update timer
        if (mDataUpdateTimer != null) {
            Log.v(TAG, "stop(): cancelling status timer");
            mDataUpdateTimer.cancel();
            mDataUpdateTimer.purge();
            mDataUpdateTimer = null;
        }

    }


    /**
     * updatePrefs() - update basic settings from the SharedPreferences
     * - defined in res/xml/prefs.xml
     */
    public void updatePrefs() {
        Log.v(TAG, "updatePrefs()");
        mUtil.writeToSysLogFile("SdDataSourceNetwork().updatePrefs()");
        SharedPreferences SP = PreferenceManager
                .getDefaultSharedPreferences(mContext);
        mServerIP = SP.getString("ServerIP", "192.168.1.175");
        Log.v(TAG, "updatePrefs() - mServerIP = " + mServerIP);
        try {
            String dataUpdatePeriodStr = SP.getString("DataUpdatePeriod", "2000");
            mDataUpdatePeriod = Integer.parseInt(dataUpdatePeriodStr);
            Log.v(TAG, "updatePrefs() - mDataUpdatePeriod = " + mDataUpdatePeriod);
            String connectTimeoutPeriodStr = SP.getString("ConnectTimeoutPeriod", "5000");
            mConnnectTimeoutPeriod = Integer.parseInt(connectTimeoutPeriodStr);
            Log.v(TAG, "updatePrefs() - mConnectTimeoutPeriod = " + mConnnectTimeoutPeriod);
            String readTimeoutPeriodStr = SP.getString("ReadTimeoutPeriod", "5000");
            mReadTimeoutPeriod = Integer.parseInt(readTimeoutPeriodStr);
            Log.v(TAG, "updatePrefs() - mReadTimeoutPeriod = " + mReadTimeoutPeriod);
        } catch (Exception ex) {
            Log.v(TAG, "updatePrefs() - Problem parsing preferences!");
            mUtil.writeToSysLogFile("SdDataSourceNetwork().updatePrefs() - " + ex.toString());
            showToast("Problem Parsing Preferences - Something won't work");
        }
    }

    /**
     * Retrive the current Seizure Detector Data from the server.
     * Uses BackgroundTaskExecutor to download the data in the background.
     */
    public void downloadSdData() {
        Log.v(TAG, "downloadSdData()");

        BackgroundTaskExecutor.execute(
            () -> {
                // Background work
                SdData sdData = new SdData();
                try {
                    String url = "http://" + mServerIP + ":8080/data";
                    String result = downloadUrl(url);
                    if (result.startsWith("Unable to retrieve web page")) {
                        Log.v(TAG, "doInBackground() - Unable to retrieve data");
                        sdData.serverOK = false;
                        sdData.watchConnected = false;
                        sdData.watchAppRunning = false;
                        sdData.alarmState = ALARM_STATE_NETFAULT;
                        sdData.alarmPhrase = "Warning - No Connection to Server";
                        Log.v(TAG, "No Connection to Server - sdData = " + sdData.toString());
                    } else {
                        Log.v(TAG, "result = " + result);
                        sdData.fromJSON(result);
                        // Populate mSdData using the received data.
                        sdData.serverOK = true;
                        if (sdData.batteryPc > 0) {
                            sdData.haveSettings = true;
                        }
                        Log.v(TAG, "sdData = " + sdData.toString());
                    }
                    return sdData;
                } catch (IOException e) {
                    sdData.serverOK = false;
                    sdData.watchConnected = false;
                    sdData.watchAppRunning = false;
                    sdData.alarmState = ALARM_STATE_NETFAULT;
                    sdData.alarmPhrase = "Warning - No Connection to Server";
                    Log.v(TAG, "IOException - " + e.toString());
                    return sdData;
                }
            },
            new BackgroundTaskExecutor.Callback<SdData>() {
                @Override
                public void onSuccess(SdData sdData) {
                    Log.v(TAG, "onSuccess() - sdData = " + sdData.toString());
                    mSdDataReceiver.onSdDataReceived(sdData);
                }

                @Override
                public void onError(Exception e) {
                    Log.e(TAG, "Error downloading data", e);
                    SdData errorData = new SdData();
                    errorData.serverOK = false;
                    errorData.watchConnected = false;
                    errorData.watchAppRunning = false;
                    errorData.alarmState = ALARM_STATE_NETFAULT;
                    errorData.alarmPhrase = "Warning - Error downloading data";
                    mSdDataReceiver.onSdDataReceived(errorData);
                }
            }
        );
    }

    /**
     * Accept an alarm remotely using a http GET request.
     */
    @Override
    public void acceptAlarm() {
        Log.v(TAG, "acceptAlarm()");

        BackgroundTaskExecutor.executeAndForget(() -> {
            try {
                String url = "http://" + mServerIP + ":8080/acceptalarm";
                String result = downloadUrl(url);
                if (result.startsWith("Unable to retrieve web page")) {
                    Log.v(TAG, "Error accepting alarm");
                } else {
                    Log.v(TAG, "Alarm Accepted");
                }
            } catch (IOException e) {
                Log.v(TAG, "IOException accepting alarm - " + e.toString());
            }
        });
    }


    // Given a URL, establishes an HttpUrlConnection and retrieves
    // the web page content as a InputStream, which it returns as
    // a string.
    private String downloadUrl(String myurl) throws IOException {
        InputStream is = null;
        // Only retrieve the first 2048 characters of the retrieved
        // web page content.
        int len = 2048;

        try {
            URL url = new URL(myurl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setReadTimeout(mReadTimeoutPeriod /* milliseconds */);
            conn.setConnectTimeout(mConnnectTimeoutPeriod /* milliseconds */);
            conn.setRequestMethod("GET");
            conn.setDoInput(true);
            // Starts the query
            conn.connect();
            int response = conn.getResponseCode();
            Log.d(TAG, "downloadUrl(): The response is: " + response);
            is = conn.getInputStream();

            // Convert the InputStream into a string
            String contentAsString = readInputStream(is, len);
            return contentAsString;

            // Makes sure that the InputStream is closed after the app is
            // finished using it.
        } finally {
            if (is != null) {
                is.close();
            }
        }
    }

    // Reads an InputStream and converts it to a String.
    public String readInputStream(InputStream stream, int len) throws IOException, UnsupportedEncodingException {
        Reader reader = null;
        reader = new InputStreamReader(stream, "UTF-8");
        char[] buffer = new char[len];
        reader.read(buffer);
        return new String(buffer);
    }


}
