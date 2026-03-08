/*
  OpenSeizureDetector - Log Uploader

  Encapsulates all remote API operations for uploading events and datapoints.

  Copyright Graham Jones, 2026.

  This file is part of OpenSeizureDetector.

  OpenSeizureDetector is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  OpenSeizureDetector is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with OpenSeizureDetector.  If not, see <http://www.gnu.org/licenses/>.
*/

package uk.org.openseizuredetector.data.logging;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

import uk.org.openseizuredetector.R;
import uk.org.openseizuredetector.comms.WebApiConnection;
import uk.org.openseizuredetector.utils.OsdUtil;

/**
 * LogUploader handles the orchestration of uploading local events and datapoints to the remote server.
 *
 * Responsibilities:
 * - Coordinate event creation and datapoint uploads with WebApiConnection
 * - Manage upload state and progress tracking
 * - Handle network connectivity and upload timing
 * - Provide callbacks for network state changes and external triggers
 */
public class LogUploader {
    private static final String TAG = "LogUploader";

    private Context mContext;
    private OsdUtil mUtil;
    private LogRepository mRepository;
    public WebApiConnection mWac;

    private boolean mUploadInProgress;
    private final Object mUploadLock = new Object();
    private volatile boolean mShutdownRequested = false;

    private String mCurrentEventRemoteId;
    private long mCurrentEventLocalId = -1;
    private int mCurrentDatapointId;
    private ArrayList<JSONObject> mDatapointsToUploadList;

    private long mEventDuration = 120;  // seconds
    private boolean mLogRemote;
    private boolean mLogRemoteMobile;

    /**
     * Callback interface for showing toast messages safely on the UI thread.
     */
    public interface ToastCallback {
        void showToast(String message);
    }

    private ToastCallback mToastCallback;

    /**
     * Constructor
     *
     * @param context           Android context
     * @param util              OsdUtil instance
     * @param repository        LogRepository instance
     * @param wac               WebApiConnection instance
     * @param eventDuration     Duration in seconds for event-associated datapoint window
     * @param logRemote         Whether remote logging is enabled
     * @param logRemoteMobile   Whether to log over mobile data
     * @param toastCallback     Callback for showing UI toasts
     */
    public LogUploader(Context context, OsdUtil util, LogRepository repository, WebApiConnection wac,
                       long eventDuration, boolean logRemote, boolean logRemoteMobile,
                       ToastCallback toastCallback) {
        mContext = context;
        mUtil = util;
        mRepository = repository;
        mWac = wac;
        mEventDuration = eventDuration;
        mLogRemote = logRemote;
        mLogRemoteMobile = logRemoteMobile;
        mToastCallback = toastCallback;
        mUploadInProgress = false;
    }

    /**
     * Update configuration parameters
     */
    public void updateConfig(boolean logRemote, boolean logRemoteMobile, long eventDuration) {
        mLogRemote = logRemote;
        mLogRemoteMobile = logRemoteMobile;
        mEventDuration = eventDuration;
    }

    /**
     * Check if an upload can proceed based on network and configuration state
     */
    public boolean canUpload() {
        if (!mLogRemote) {
            Log.v(TAG, "canUpload(): mLogRemote not set");
            return false;
        }

        if (!mLogRemoteMobile) {
            if (mUtil.isMobileDataActive()) {
                Log.v(TAG, "canUpload(): Using mobile data, but mLogRemoteMobile is false");
                return false;
            }
        }

        if (!mUtil.isNetworkConnected()) {
            Log.v(TAG, "canUpload(): No network connection");
            return false;
        }

        return true;
    }

    /**
     * Write data to remote server - the main upload orchestration method
     */
    public void writeToRemoteServer() {
        Log.v(TAG, "writeToRemoteServer()");

        if (!canUpload()) {
            Log.v(TAG, "writeToRemoteServer(): Upload conditions not met");
            return;
        }

        if (mUploadInProgress) {
            Log.v(TAG, "writeToRemoteServer(): Upload already in progress");
            return;
        }

        Log.d(TAG, "writeToRemoteServer(): Calling uploadSdData()");
        uploadSdData();
    }

    /**
     * Handle network state changes - attempt to resume operations when network becomes available
     */
    public void onNetworkStateChanged() {
        Log.i(TAG, "onNetworkStateChanged() - Network state has changed");

        if (mShutdownRequested) {
            Log.d(TAG, "onNetworkStateChanged() - Shutdown requested");
            return;
        }

        if (!canUpload()) {
            Log.d(TAG, "onNetworkStateChanged(): Upload conditions not met");
            return;
        }

        // If upload flag is stuck, reset it
        if (mUploadInProgress) {
            Log.w(TAG, "onNetworkStateChanged() - Upload flag was stuck, resetting");
            mUploadInProgress = false;
        }

        Log.i(TAG, "onNetworkStateChanged() - Triggering immediate upload");
        writeToRemoteServer();
    }

    /**
     * Initiate upload of the next event and its associated datapoints
     */
    public void uploadSdData() {
        // Upload everything - alarms and warnings
        boolean includeWarnings = true;
        Log.i(TAG, "uploadSdData(): Starting upload with includeWarnings=" + includeWarnings);

        synchronized (mUploadLock) {
            if (mUploadInProgress) {
                Log.d(TAG, "uploadSdData - upload already in progress");
                return;
            }
            mUploadInProgress = true;
        }

        mRepository.getNextEventToUpload(includeWarnings, (Long eventId) -> {
            if (mShutdownRequested) {
                Log.d(TAG, "uploadSdData - shutdown requested during callback");
                finishUpload();
                return;
            }

            if (eventId != -1) {
                Log.i(TAG, "uploadSdData() - Found event to upload: id=" + eventId);
                handleEventUpload(eventId);
            } else {
                Log.v(TAG, "uploadSdData - no events to upload");
                synchronized (mUploadLock) {
                    mUploadInProgress = false;
                }
            }
        });
    }

    /**
     * Process a single event upload
     */
    private void handleEventUpload(long eventId) {
        String eventJsonStr = mRepository.getLocalEventById(eventId);
        Log.v(TAG, "handleEventUpload() - eventJsonStr=" + eventJsonStr);

        try {
            JSONArray datapointJsonArr = new JSONArray(eventJsonStr);
            JSONObject eventObj = datapointJsonArr.getJSONObject(0);

            int eventAlarmStatus = Integer.parseInt(eventObj.getString("status"));
            String eventDateStr = eventObj.getString("dataTime");
            String eventType = eventObj.getString("type");
            String eventSubType = eventObj.getString("subType");
            String eventDesc = eventObj.has("desc") ? eventObj.getString("desc") : "";
            String eventDataJSON = eventObj.getString("dataJSON");

            Log.d(TAG, "handleEventUpload - Parsed event: status=" + eventAlarmStatus +
                    ", dateStr=" + eventDateStr + ", type=" + eventType);

            Date eventDate;
            try {
                eventDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(eventDateStr);
            } catch (ParseException e) {
                Log.e(TAG, "handleEventUpload(): Error parsing date " + eventDateStr);
                finishUpload();
                return;
            }

            Log.i(TAG, "handleEventUpload - Calling mWac.createEvent");
            mCurrentEventLocalId = eventId;
            mWac.createEvent(eventAlarmStatus, eventDate, eventType, eventSubType, eventDesc,
                    eventDataJSON, this::createEventCallback);

        } catch (JSONException e) {
            Log.e(TAG, "handleEventUpload(): ERROR parsing event JSON" + eventJsonStr);
            finishUpload();
        } catch (NullPointerException e) {
            Log.e(TAG, "handleEventUpload(): ERROR null pointer exception: " + eventJsonStr);
            finishUpload();
        }
    }

    /**
     * Called by WebApiConnection when an event is successfully created on the remote server
     */
    public void createEventCallback(String eventId) {
        Log.v(TAG, "createEventCallback(): eventId=" + eventId);

        if (mShutdownRequested) {
            Log.v(TAG, "createEventCallback(): Shutdown requested");
            finishUpload();
            return;
        }

        Log.v(TAG, "createEventCallback(): Retrieving remote event details");
        mWac.getEvent(eventId, new WebApiConnection.JSONObjectCallback() {
            @Override
            public void accept(JSONObject eventObj) {
                if (mShutdownRequested) {
                    Log.v(TAG, "createEventCallback.accept(): Shutdown requested");
                    finishUpload();
                    return;
                }

                if (eventObj == null) {
                    Log.e(TAG, "createEventCallback() - Event creation failed (network error?)");
                    Log.i(TAG, "createEventCallback() - Will retry on next network connection");
                    finishUpload();
                } else {
                    handleCreatedEvent(eventObj, eventId);
                }
            }
        });
    }

    /**
     * Process the created event and fetch associated datapoints for upload
     */
    private void handleCreatedEvent(JSONObject eventObj, String eventId) {
        Log.v(TAG, "handleCreatedEvent() - eventObj=" + eventObj.toString());

        Date eventDate;
        try {
            String dateStr = eventObj.getString("dataTime");
            eventDate = mUtil.string2date(dateStr);
        } catch (JSONException | NullPointerException e) {
            Log.e(TAG, "handleCreatedEvent() - Error parsing event: " + eventObj.toString());
            finishUpload();
            return;
        }

        if (eventDate == null) {
            Log.e(TAG, "handleCreatedEvent() - Event date is null");
            showToastSafe(mContext.getString(R.string.error_uploading_event_msg));
            finishUpload();
            return;
        }

        Log.v(TAG, "handleCreatedEvent() - eventId=" + eventId + ", eventDate=" + eventDate);
        mUploadInProgress = true;
        long eventDateMillis = eventDate.getTime();
        long startDateMillis = eventDateMillis - 1000 * mEventDuration / 2;
        long endDateMillis = eventDateMillis + 1000 * mEventDuration / 2;
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        mRepository.getDatapointsByDate(
                dateFormat.format(new Date(startDateMillis)),
                dateFormat.format(new Date(endDateMillis)),
                (String datapointsJsonStr) -> {
                    if (mShutdownRequested) {
                        Log.v(TAG, "handleCreatedEvent.getDatapointsByDate(): Shutdown requested");
                        finishUpload();
                        return;
                    }

                    handleDatapointsForUpload(datapointsJsonStr, eventId);
                });
    }

    /**
     * Prepare datapoints for upload
     */
    private void handleDatapointsForUpload(String datapointsJsonStr, String eventId) {
        Log.v(TAG, "handleDatapointsForUpload() - received " + (datapointsJsonStr != null ? "data" : "null"));

        mDatapointsToUploadList = new ArrayList<>();

        try {
            JSONArray dataObj = new JSONArray(datapointsJsonStr);
            Log.v(TAG, "handleDatapointsForUpload() - Found " + dataObj.length() + " datapoints");
            for (int i = 0; i < dataObj.length(); i++) {
                mDatapointsToUploadList.add(dataObj.getJSONObject(i));
            }
        } catch (JSONException | NullPointerException e) {
            Log.e(TAG, "handleDatapointsForUpload(): Error parsing datapoints: " + datapointsJsonStr);
            finishUpload();
            return;
        }

        mCurrentEventRemoteId = eventId;
        Log.v(TAG, "handleDatapointsForUpload() - Starting datapoints upload with " +
                mDatapointsToUploadList.size() + " datapoints for eventId=" + eventId);
        uploadNextDatapoint();
    }

    /**
     * Upload the next datapoint in the queue
     */
    public void uploadNextDatapoint() {
        Log.v(TAG, "uploadNextDatapoint()");

        if (mShutdownRequested) {
            Log.v(TAG, "uploadNextDatapoint(): Shutdown requested");
            finishUpload();
            return;
        }

        if (mDatapointsToUploadList == null || mDatapointsToUploadList.size() == 0) {
            Log.i(TAG, "uploadNextDatapoint() - All datapoints uploaded!");
            mRepository.setEventToUploaded(mCurrentEventLocalId, mCurrentEventRemoteId);
            finishUpload();
            return;
        }

        mUploadInProgress = true;
        try {
            mCurrentDatapointId = mDatapointsToUploadList.get(0).getInt("id");
        } catch (JSONException | NullPointerException e) {
            Log.e(TAG, "uploadNextDatapoint(): Error reading datapoint ID: " + e.getMessage());
            mDatapointsToUploadList.remove(0);
            uploadNextDatapoint();
            return;
        }

        Log.v(TAG, "uploadNextDatapoint() - Uploading datapoint ID=" + mCurrentDatapointId +
                " (" + mDatapointsToUploadList.size() + " remaining)");
        mWac.createDatapoint(mDatapointsToUploadList.get(0), mCurrentEventRemoteId, this::datapointCallback);
    }

    /**
     * Called by WebApiConnection when a datapoint is successfully uploaded
     */
    public void datapointCallback(String datapointId) {
        Log.v(TAG, "datapointCallback() - uploaded datapoint ID=" + mCurrentDatapointId +
                " remote ID=" + datapointId);

        if (mShutdownRequested) {
            Log.v(TAG, "datapointCallback(): Shutdown requested");
            finishUpload();
            return;
        }

        if (mDatapointsToUploadList != null && mDatapointsToUploadList.size() > 0) {
            mDatapointsToUploadList.remove(0);
        }

        mRepository.setDatapointToUploaded(mCurrentDatapointId, mCurrentEventRemoteId);
        uploadNextDatapoint();
    }

    /**
     * Mark upload as complete and reset state
     */
    public void finishUpload() {
        Log.v(TAG, "finishUpload()");
        mCurrentEventRemoteId = null;
        mCurrentEventLocalId = -1;
        mCurrentDatapointId = -1;
        mDatapointsToUploadList = null;
        synchronized (mUploadLock) {
            mUploadInProgress = false;
        }
    }

    /**
     * Check if upload is currently in progress
     */
    public boolean isUploadInProgress() {
        synchronized (mUploadLock) {
            return mUploadInProgress;
        }
    }

    /**
     * Request shutdown - stop all upload operations
     */
    public void requestShutdown() {
        Log.i(TAG, "requestShutdown()");
        mShutdownRequested = true;

        // Wait for ongoing upload to complete or timeout
        if (mUploadInProgress) {
            Log.i(TAG, "requestShutdown() - Waiting for upload to complete");
            int waitCount = 0;
            while (mUploadInProgress && waitCount < 50) { // 5 second max wait
                try {
                    Thread.sleep(100);
                    waitCount++;
                } catch (InterruptedException e) {
                    Log.w(TAG, "requestShutdown() - Interrupted");
                    break;
                }
            }

            if (mUploadInProgress) {
                Log.w(TAG, "requestShutdown() - Forcing termination after timeout");
                finishUpload();
            }
        }

        Log.i(TAG, "requestShutdown() - Shutdown complete");
    }

    /**
     * Show a toast message safely on the UI thread
     */
    private void showToastSafe(final String message) {
        if (mToastCallback != null) {
            mToastCallback.showToast(message);
        }
    }

    /**
     * Update WebApiConnection reference (needed if connection is recreated)
     */
    public void setWebApiConnection(WebApiConnection wac) {
        mWac = wac;
    }
}


