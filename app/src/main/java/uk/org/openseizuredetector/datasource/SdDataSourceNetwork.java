package uk.org.openseizuredetector.datasource;

import uk.org.openseizuredetector.data.SdData;
import uk.org.openseizuredetector.data.AlarmState;
import uk.org.openseizuredetector.utils.BackgroundTaskExecutor;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import uk.org.openseizuredetector.data.logging.Log;

import androidx.preference.PreferenceManager;

import org.json.JSONException;

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
    private final static String TAG = "SdDataSourceNetwork";
    private Timer mDataUpdateTimer;
    private int mDataUpdatePeriod = 2000;
    private int mConnnectTimeoutPeriod = 5000;
    private int mReadTimeoutPeriod = 5000;
    private String mServerIP = "unknown";

    // Test hook: if set, use this base URL (including protocol and trailing slash if desired)
    private String mServerBaseUrl = null;

    public SdDataSourceNetwork(Context context, Handler handler, SdDataReceiver sdDataReceiver) {
        super(context, handler, sdDataReceiver);
        mName = "Network";
    }

    @Override
    public void start() {
        Log.i(TAG, "start()");
        // Update preferences.
        updatePrefs();
        // Mark as running so background callbacks know it's active
        setRunning(true);

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
        Log.i(TAG, "stop()");
        // Stop the data update timer
        if (mDataUpdateTimer != null) {
            Log.v(TAG, "stop(): cancelling status timer");
            mDataUpdateTimer.cancel();
            mDataUpdateTimer.purge();
            mDataUpdateTimer = null;
        }
        // Mark as stopped so background tasks won't post results
        setRunning(false);

    }


    /**
     * updatePrefs() - update basic settings from the SharedPreferences
     * - defined in res/xml/prefs.xml
     */
    public void updatePrefs() {
        Log.i(TAG, "updatePrefs()");
        SharedPreferences SP = PreferenceManager
                .getDefaultSharedPreferences(mContext);
        mServerIP = SP.getString("ServerIP", "SET_FROM_XML");
        Log.v(TAG, "updatePrefs() - mServerIP = " + mServerIP);
        try {
            String dataUpdatePeriodStr = SP.getString("DataUpdatePeriod", "SET_FROM_XML");
            mDataUpdatePeriod = Integer.parseInt(dataUpdatePeriodStr);
            Log.v(TAG, "updatePrefs() - mDataUpdatePeriod = " + mDataUpdatePeriod);
            String connectTimeoutPeriodStr = SP.getString("ConnectTimeoutPeriod", "SET_FROM_XML");
            mConnnectTimeoutPeriod = Integer.parseInt(connectTimeoutPeriodStr);
            Log.v(TAG, "updatePrefs() - mConnectTimeoutPeriod = " + mConnnectTimeoutPeriod);
            String readTimeoutPeriodStr = SP.getString("ReadTimeoutPeriod", "SET_FROM_XML");
            mReadTimeoutPeriod = Integer.parseInt(readTimeoutPeriodStr);
            Log.v(TAG, "updatePrefs() - mReadTimeoutPeriod = " + mReadTimeoutPeriod);
        } catch (Exception ex) {
            Log.e(TAG, "updatePrefs() - " + ex.toString());
            showToast("Problem Parsing Preferences - Something won't work");
        }
    }

    /**
     * Retrieve the current Seizure Detector Data from the server.
     * Uses BackgroundTaskExecutor to download the data in the background.
     */
    public void downloadSdData() {
        Log.v(TAG, "downloadSdData()");

        BackgroundTaskExecutor.execute(
            () -> {
                // Background work
                SdData sdData = new SdData();
                try {
                    String url = makeDataUrl();
                    String result = downloadUrl(url);
                    if (result.startsWith("Unable to retrieve web page")) {
                        Log.e(TAG, "downloadSdData() - Unable to retrieve data");
                        sdData.serverOK = false;
                        sdData.watchConnected = false;
                        sdData.watchAppRunning = false;
                        sdData.alarmState = AlarmState.NETFAULT;
                        sdData.alarmPhrase = "Warning - No Connection to Server";
                        Log.e(TAG, "downloadSdData() - No Connection to Server - sdData = " + sdData.toString());
                    } else {
                        Log.v(TAG, "result = " + result);
                        try {
                            sdData.fromJSON(result);
                            // Populate mSdData using the received data.
                            sdData.serverOK = true;
                            if (sdData.batteryPc > 0) {
                                sdData.haveSettings = true;
                            }
                            Log.v(TAG, "sdData = " + sdData.toString());
                        } catch (JSONException je) {
                            Log.e(TAG, "downloadSdData() - JSON parsing error in downloadSdData: " + je.toString());
                            sdData.serverOK = false;
                            sdData.watchConnected = false;
                            sdData.watchAppRunning = false;
                            sdData.alarmState = AlarmState.NETFAULT;
                            sdData.alarmPhrase = "Warning - Invalid data from server";
                        }
                    }
                    return sdData;
                } catch (IOException e) {
                    sdData.serverOK = false;
                    sdData.watchConnected = false;
                    sdData.watchAppRunning = false;
                    sdData.alarmState = AlarmState.NETFAULT;
                    sdData.alarmPhrase = "Warning - No Connection to Server";
                    Log.e(TAG, "downloadSdData() - IOException - " + e.toString());
                    return sdData;
                }
            },
            new BackgroundTaskExecutor.Callback<SdData>() {
                @Override
                public void onSuccess(SdData sdData) {
                    Log.v(TAG, "onSuccess() - sdData = " + sdData.toString());
                    // We seem to call onSuccess even if we have errors downloading etc. because the network call does return,
                    // so we check for fault here to decide which callback to call.
                    if (!isRunning()) {
                        Log.i(TAG, "onSuccess() - datasource stopped, ignoring result");
                        return;
                    }
                    if (sdData.alarmState != AlarmState.NETFAULT && sdData.alarmState != AlarmState.FAULT) {
                        mSdDataReceiver.onSdDataReceived(sdData);
                    } else {
                        Log.e(TAG, "onSuccess() - sdData.alarmState = " + sdData.alarmState + " initiating fault.");
                        mSdDataReceiver.onSdDataFault(sdData);
                    }
                }

                @Override
                public void onError(Exception e) {
                    Log.e(TAG, "onError() - Error downloading data", e);
                    if (!isRunning()) {
                        Log.i(TAG, "onError() - datasource stopped, ignoring error");
                        return;
                    }
                    SdData errorData = new SdData();
                    errorData.serverOK = false;
                    errorData.watchConnected = false;
                    errorData.watchAppRunning = false;
                    errorData.alarmState = AlarmState.NETFAULT;
                    errorData.alarmPhrase = "Warning - Error downloading data";
                    //mSdDataReceiver.onSdDataReceived(errorData);
                    mSdDataReceiver.onSdDataFault(errorData);
                }
            }
        );
    }

    /**
     * Synchronous version of downloadSdData() for use in unit tests.
     * Performs the same network call and parsing but returns SdData directly.
     */
    public SdData downloadSdDataSync() throws Exception {
        SdData sdData = new SdData();
        String url = makeDataUrl();
        String result = downloadUrl(url);
        System.out.println("DEBUG: downloadSdDataSync() result='" + result + "'");
        if (result.startsWith("Unable to retrieve web page")) {
            sdData.serverOK = false;
            sdData.watchConnected = false;
            sdData.watchAppRunning = false;
            sdData.alarmState = AlarmState.NETFAULT;
            sdData.alarmPhrase = "Warning - No Connection to Server";
            return sdData;
        } else {
            // Let fromJSON propagate JSONException if the data is malformed (strict mode)
            sdData.fromJSON(result);
            sdData.serverOK = true;
            if (sdData.batteryPc > 0) sdData.haveSettings = true;
            return sdData;
        }
    }

    /**
     * Accept an alarm remotely using a http GET request.
     */
    @Override
    public void acceptAlarm() {
        Log.v(TAG, "acceptAlarm()");

        BackgroundTaskExecutor.executeAndForget(() -> {
            try {
                String url = makeAcceptAlarmUrl();
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

    // Setter for tests to override server URL (uses MockWebServer URL)
    public void setServerBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            this.mServerBaseUrl = null;
        } else {
            this.mServerBaseUrl = baseUrl;
        }
    }

    private String makeDataUrl() {
        if (mServerBaseUrl != null && mServerBaseUrl.length() > 0) {
            // Ensure ends with /data or append
            if (mServerBaseUrl.endsWith("/")) return mServerBaseUrl + "data";
            else return mServerBaseUrl + "/data";
        }
        return "http://" + mServerIP + ":8080/data";
    }

    private String makeAcceptAlarmUrl() {
        if (mServerBaseUrl != null && mServerBaseUrl.length() > 0) {
            if (mServerBaseUrl.endsWith("/")) return mServerBaseUrl + "acceptalarm";
            else return mServerBaseUrl + "/acceptalarm";
        }
        return "http://" + mServerIP + ":8080/acceptalarm";
    }

}
