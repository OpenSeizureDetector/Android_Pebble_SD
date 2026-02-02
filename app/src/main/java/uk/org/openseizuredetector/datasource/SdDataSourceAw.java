/*
  Android_Pebble_sd - Android alarm client for openseizuredetector..

  See http://openseizuredetector.org for more information.

  Copyright Graham Jones, 2015, 2016

  This file is part of pebble_sd.

  Android_Pebble_sd is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  Android_Pebble_sd is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with Android_pebble_sd.  If not, see <http://www.gnu.org/licenses/>.

*/
package uk.org.openseizuredetector.datasource;
import uk.org.openseizuredetector.R;

import uk.org.openseizuredetector.datasource.SdDataSource;
import uk.org.openseizuredetector.datasource.SdDataSourceAw;
import android.content.Context;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.text.format.Time;
import android.util.Log;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;


/**
 * Android Wear data source that receives accelerometer and heart rate data from a
 * companion watch app via the Wearable Data Layer API.
 *
 * This implementation expects the watch app to send messages on the following paths:
 * - "/osd/accel_data" - Accelerometer data (raw samples)
 * - "/osd/settings" - Watch settings and battery status
 * - "/osd/hr_data" - Heart rate measurements
 */
public class SdDataSourceAw extends SdDataSource implements MessageClient.OnMessageReceivedListener {
    private String TAG = "SdDataSourceAw";

    // Message paths for Wearable Data Layer communication
    private static final String PATH_ACCEL_DATA = "/osd/accel_data";
    private static final String PATH_SETTINGS = "/osd/settings";
    private static final String PATH_HR_DATA = "/osd/hr_data";
    private static final String PATH_REQUEST_DATA = "/osd/request_data";
    private static final String PATH_ALARM_STATE = "/osd/alarm_state";
    private static final String PATH_SEND_SETTINGS = "/osd/send_settings";

    // Raw data storage
    private int MAX_RAW_DATA = 125;  // 5 seconds at 25 Hz
    private double[] rawData = new double[MAX_RAW_DATA];
    private int nRawData = 0;

    private MessageClient mMessageClient;
    private boolean mIsStarted = false;

    public SdDataSourceAw(Context context, Handler handler,
                          SdDataReceiver sdDataReceiver) {
        super(context, handler, sdDataReceiver);
        mName = "Android Wear";
        // Set default settings from XML files (mContext is set by super().
        PreferenceManager.setDefaultValues(mContext,
                R.xml.seizure_detector_prefs, true);
    }


    /**
     * Start the datasource updating - initialises from sharedpreferences first to
     * make sure any changes to preferences are taken into account.
     */
    @Override
    public void start() {
        Log.v(TAG, "start()");
        mUtil.writeToSysLogFile("SdDataSourceAw.start()");
        super.start();

        // Register message listener
        mMessageClient = Wearable.getMessageClient(mContext);
        mMessageClient.addListener(this);
        mIsStarted = true;

        mSdData.watchConnected = true;
        mSdData.watchAppRunning = false;

        // Request initial data from watch
        sendMessageToWatch(PATH_SEND_SETTINGS, "start".getBytes(StandardCharsets.UTF_8));

        Log.v(TAG, "start(): Android Wear message listener registered");
        mUtil.writeToSysLogFile("SdDataSourceAw.start() - message listener registered");
    }

    /**
     * Stop the datasource from updating
     */
    @Override
    public void stop() {
        Log.v(TAG, "stop()");
        mUtil.writeToSysLogFile("SdDataSourceAw.stop()");

        try {
            if (mMessageClient != null && mIsStarted) {
                mMessageClient.removeListener(this);
                mIsStarted = false;
                Log.v(TAG, "stop(): message listener removed");
                mUtil.writeToSysLogFile("SdDataSourceAw.stop() - message listener removed");
            }
        } catch (Exception e) {
            Log.v(TAG, "Error in stop() - " + e.toString());
            mUtil.writeToSysLogFile("SdDataSourceAw.stop() - error - " + e.toString());
        }

        super.stop();
    }

    /**
     * Called when a message is received from the watch
     */
    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        String path = messageEvent.getPath();
        byte[] data = messageEvent.getData();

        Log.v(TAG, "onMessageReceived: " + path);

        // Mark that we've received data from the watch
        mWatchAppRunningCheck = true;
        mSdData.watchConnected = true;
        mSdData.watchAppRunning = true;
        mDataStatusTime = new Time(Time.getCurrentTimezone());
        mDataStatusTime.setToNow();

        try {
            if (path.equals(PATH_ACCEL_DATA)) {
                handleAccelData(data);
            } else if (path.equals(PATH_SETTINGS)) {
                handleSettings(data);
            } else if (path.equals(PATH_HR_DATA)) {
                handleHrData(data);
            } else {
                Log.w(TAG, "Unknown message path: " + path);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing message: " + e.toString());
            mUtil.writeToSysLogFile("SdDataSourceAw.onMessageReceived() - error: " + e.toString());
        }
    }

    /**
     * Handle accelerometer data from watch
     * Expected format: JSON with "samples" array or raw binary data
     */
    private void handleAccelData(byte[] data) {
        try {
            // Try to parse as JSON first
            String jsonStr = new String(data, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonStr);

            if (json.has("samples")) {
                // JSON format with samples array
                org.json.JSONArray samples = json.getJSONArray("samples");
                for (int i = 0; i < samples.length(); i++) {
                    if (nRawData < MAX_RAW_DATA) {
                        rawData[nRawData] = samples.getDouble(i);
                        nRawData++;
                    } else {
                        // Buffer full, process data
                        processAccelData();
                        nRawData = 0;
                    }
                }
            } else if (json.has("x") && json.has("y") && json.has("z")) {
                // Single 3D sample
                double x = json.getDouble("x");
                double y = json.getDouble("y");
                double z = json.getDouble("z");
                double magnitude = Math.sqrt(x * x + y * y + z * z);

                if (nRawData < MAX_RAW_DATA) {
                    rawData[nRawData] = magnitude;
                    nRawData++;
                } else {
                    processAccelData();
                    nRawData = 0;
                }
            }
        } catch (JSONException e) {
            // Not JSON, try parsing as binary data
            try {
                parseBinaryAccelData(data);
            } catch (Exception ex) {
                Log.e(TAG, "Error parsing accel data: " + ex.toString());
            }
        }
    }

    /**
     * Parse binary accelerometer data (16-bit little-endian shorts)
     */
    private void parseBinaryAccelData(byte[] data) {
        // Assume 16-bit little-endian signed integers
        short[] samples = new short[data.length / 2];
        ByteBuffer.wrap(data)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .get(samples);

        for (short sample : samples) {
            if (nRawData < MAX_RAW_DATA) {
                rawData[nRawData] = sample;
                nRawData++;
            } else {
                processAccelData();
                nRawData = 0;
                rawData[nRawData] = sample;
                nRawData++;
            }
        }
    }

    /**
     * Process buffered accelerometer data by calling doAnalysis
     */
    private void processAccelData() {
        Log.v(TAG, "processAccelData(): processing " + nRawData + " samples");

        // Copy to mSdData
        for (int i = 0; i < nRawData && i < mSdData.rawData.length; i++) {
            mSdData.rawData[i] = rawData[i];
        }
        mSdData.mNsamp = Math.min(nRawData, mSdData.rawData.length);

        // Run analysis
        doAnalysis();

        // Send alarm state back to watch
        sendAlarmStateToWatch();
    }

    /**
     * Handle settings data from watch (battery, version, etc.)
     */
    private void handleSettings(byte[] data) {
        try {
            String jsonStr = new String(data, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonStr);

            if (json.has("battery")) {
                mSdData.batteryPc = json.getInt("battery");
            }
            if (json.has("version")) {
                mSdData.watchSdVersion = json.getString("version");
            }
            if (json.has("name")) {
                mSdData.watchSdName = json.getString("name");
            }
            if (json.has("sample_freq")) {
                // Could validate sample frequency
                Log.v(TAG, "Watch sample frequency: " + json.getInt("sample_freq"));
            }

            mSdData.haveSettings = true;

            Log.v(TAG, "handleSettings(): battery=" + mSdData.batteryPc +
                    ", version=" + mSdData.watchSdVersion);

        } catch (JSONException e) {
            Log.e(TAG, "Error parsing settings: " + e.toString());
        }
    }

    /**
     * Handle heart rate data from watch
     */
    private void handleHrData(byte[] data) {
        try {
            String jsonStr = new String(data, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonStr);

            if (json.has("hr")) {
                mSdData.mHR = json.getInt("hr");
                Log.v(TAG, "handleHrData(): HR=" + mSdData.mHR);
            }
        } catch (JSONException e) {
            // Try parsing as simple integer
            try {
                String hrStr = new String(data, StandardCharsets.UTF_8);
                mSdData.mHR = Integer.parseInt(hrStr.trim());
                Log.v(TAG, "handleHrData(): HR=" + mSdData.mHR);
            } catch (Exception ex) {
                Log.e(TAG, "Error parsing HR data: " + ex.toString());
            }
        }
    }

    /**
     * Send alarm state back to watch
     */
    private void sendAlarmStateToWatch() {
        try {
            JSONObject json = new JSONObject();
            json.put("alarm_state", mSdData.alarmState);
            json.put("alarm_phrase", mSdData.alarmPhrase);

            byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
            sendMessageToWatch(PATH_ALARM_STATE, data);

        } catch (JSONException e) {
            Log.e(TAG, "Error creating alarm state message: " + e.toString());
        }
    }

    /**
     * Send a message to the watch app
     */
    private void sendMessageToWatch(String path, byte[] data) {
        Log.v(TAG, "sendMessageToWatch: " + path);
        if (mMessageClient == null) {
            Log.w(TAG, "sendMessageToWatch: mMessageClient is null");
            return;
        }

        // Get list of connected nodes (watches)
        Task<Integer> sendTask = Tasks.call(() -> {
            com.google.android.gms.wearable.NodeClient nodeClient =
                    Wearable.getNodeClient(mContext);

            try {
                for (com.google.android.gms.wearable.Node node :
                        Tasks.await(nodeClient.getConnectedNodes())) {
                    Task<Integer> sendMessageTask = mMessageClient.sendMessage(
                            node.getId(), path, data);
                    Tasks.await(sendMessageTask);
                    Log.d(TAG, "Message sent to " + node.getDisplayName() + ": " + path);
                }
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Error sending message: " + e.toString());
            }
            return 0;
        });
    }

    /**
     * Check status of watch connection
     * Called periodically by base class timer
     */
    @Override
    public void getStatus() {
        Time tnow = new Time(Time.getCurrentTimezone());
        tnow.setToNow();

        long tdiff = tnow.toMillis(false) - mDataStatusTime.toMillis(false);
        Log.v(TAG, "getStatus() - mWatchAppRunningCheck=" + mWatchAppRunningCheck +
                ", tdiff=" + tdiff);

        // Check if we've received data recently
        if (!mWatchAppRunningCheck && tdiff > 30000) {  // 30 seconds
            Log.w(TAG, "getStatus() - No data received from watch in 30 seconds");
            mSdData.watchAppRunning = false;

            if (tdiff > 60000) {  // 60 seconds - trigger fault
                mUtil.writeToSysLogFile("SdDataSourceAw.getStatus() - Watch app not responding");
                mSdDataReceiver.onSdDataFault(mSdData);
            }
        } else {
            mSdData.watchAppRunning = true;
        }

        // Reset check flag
        if (mWatchAppRunningCheck) {
            mWatchAppRunningCheck = false;
            mDataStatusTime.setToNow();
        }
    }
}
