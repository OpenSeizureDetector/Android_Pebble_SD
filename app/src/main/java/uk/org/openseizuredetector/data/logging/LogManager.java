/*
  Android_SD - Android host for Garmin or Pebble watch based seizure detectors.
  See http://openseizuredetector.org for more information.

  Copyright Graham Jones, 2019, 2021.

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
package uk.org.openseizuredetector.data.logging;
import uk.org.openseizuredetector.R;

import uk.org.openseizuredetector.comms.WebApiConnection;
import uk.org.openseizuredetector.comms.WebApiConnection_firebase;
import uk.org.openseizuredetector.comms.WebApiConnection_osdapi;
import uk.org.openseizuredetector.data.SdData;
import uk.org.openseizuredetector.utils.BackgroundTaskExecutor;
import uk.org.openseizuredetector.utils.OsdUtil;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;

//import static android.database.sqlite.SQLiteDatabase.openOrCreateDatabase;

/**
 * LogManager is a class to handle all aspects of Data Logging within OpenSeizureDetector.
 * It performs several functions:
 * - It will store seizure detector data to a local database on demand (it is called by the SdServer background service)
 * - It will store system log data to the local database on demand (called by any part of OSD via the osdUtil functions)
 * - It will periodically attempt to upload the oldest logged data to the osdApi remote database - the interface to the
 * remote database is handled by the WebApiConnection class.   It only tries to do one transaction with the external database
 * at a time - if the periodic timer times out and an upload is in progress it will not do anything and wait for the next timeout.*
 * <p>
 * The data upload process is as follows:
 * - Select the oldest non-uploaded datapoint that is marked as an alarm or warning state.
 * - Create an Event in the remote database based on that datapoint date and alarm type, and note the Event ID.
 * - Query the local database to return all datapoints within +/- EventDuration/2 minutes of the event.
 * - Upload the datapoints, linking them to the new eventID.
 * - Mark all the uploaded datapoints as uploaded.
 * <p>
 * Event statuses:
 * 0 - OK
 * 1 - WARNING
 * 2 - ALARM
 * 3 - FALL
 * 4 - FAULT
 * 5 - Manual Alarm
 * 6 - NDA (Normal Daily Activities)
 * <p>
 * NDA Timer creates an event periodically to record Normal Daily Activities (NDA),
 * irrespective of the alarm state.   This will upload a lot of data, so it will only run
 * for 24 hours after being activated before shutting down requring the user to re-select
 * the option to log NDA to re-start it.
 */
public class LogManager {
    static final private String TAG = "LogManager";
    //private String mDbName = "osdData";
    final static private String mDpTableName = "datapoints";
    final static private String mEventsTableName = "events";
    private boolean mLogRemote;
    private boolean mLogRemoteMobile;
    private String mAuthToken;
    static private SQLiteDatabase mOsdDb = null;   // SQLite Database for data and log entries.
    private LogRepository mRepository;  // Repository for all DB operations
    private LogUploader mUploader;  // Uploader for remote API orchestration
    private LocalEventQuerier mQuerier;  // Querier for local database queries
    private RemoteLogTimer mRemoteLogTimer;
    private boolean mLogNDA;
    public NDATimer mNDATimer;
    private long mNDATimerStartTime;  // milliseconds
    public double mNDATimeRemaining; // hours
    public double mNDALogPeriodHours = 24.0;  // hours
    private static Context mContext;
    private static OsdUtil mUtil;
    public WebApiConnection mWac;   // Instance variable — each LogManager owns its own connection
    public static final boolean USE_FIREBASE_BACKEND = false;

    private volatile boolean mShutdownRequested = false;
    private final Handler mUiHandler = new Handler(android.os.Looper.getMainLooper());
    private long mEventDuration = 120;   // event duration in seconds
    public long mDataRetentionPeriod = 1; // Prunes the local db so it only retains data younger than this duration (in days)
    private long mRemoteLogPeriod = 10; // Period in seconds between uploads to the remote server.
    private long mAutoPrunePeriod = 3600;  // Prune the database every hour
    private boolean mAutoPruneDb;
    private AutoPruneTimer mAutoPruneTimer;
    private SdData mSdSettingsData;
    private ConnectivityManager.NetworkCallback mNetworkCallback;

    public interface CursorCallback {
        void accept(Cursor retVal);
    }

    public interface ArrayListCallback {
        void accept(ArrayList<HashMap<String, String>> retVal);
    }

    public interface BooleanCallback {
        void accept(boolean retVal);
    }

    /**
     * Getter for mContext (used by LogRepository).
     */
    public static Context getContext() {
        return mContext;
    }

    /**
     * Show a toast message safely on the UI thread, but only if not shutting down
     */
    private void showToastSafe(final String message) {
        if (mShutdownRequested) {
            Log.v(TAG, "showToastSafe: Shutdown requested, not showing toast: " + message);
            return;
        }
        mUiHandler.post(() -> {
            if (!mShutdownRequested) {
                mUtil.showToast(message);
            }
        });
    }


    public LogManager(Context context, Boolean logRemote, boolean logRemoteMobile, String authToken,
                      long eventDuration, long remoteLogPeriod,
                      boolean logNDA,
                      boolean autoPruneDb, long dataRetentionPeriod,
                      SdData sdSettingsData) {
        Log.d(TAG, "LogManger Constructor");
        mContext = context;
        Handler handler = new Handler(Looper.getMainLooper());

        mLogRemote = logRemote;
        mLogRemoteMobile = logRemoteMobile;
        mAuthToken = authToken;
        mEventDuration = eventDuration;
        mAutoPruneDb = autoPruneDb;
        mDataRetentionPeriod = dataRetentionPeriod;
        mRemoteLogPeriod = remoteLogPeriod;
        mLogNDA = logNDA;
        mSdSettingsData = sdSettingsData;
        Log.v(TAG, "mLogRemote=" + mLogRemote);
        Log.v(TAG, "mLogRemoteMobile=" + mLogRemoteMobile);
        Log.v(TAG, "mEventDuration=" + mEventDuration);
        Log.v(TAG, "mLogNDA=" + mLogNDA);
        Log.v(TAG, "mAutoPruneDb=" + mAutoPruneDb);
        Log.v(TAG, "mDataRetentionPeriod=" + mDataRetentionPeriod);
        Log.v(TAG, "mRemoteLogPeriod=" + mRemoteLogPeriod);

        mUtil = new OsdUtil(mContext, handler);
        Log.init(mContext, mUtil);

        // Initialize repository for all database operations
        mRepository = new LogRepository(mContext, mUtil);
        mRepository.setDataRetentionPeriod(mDataRetentionPeriod);
        mRepository.initialize();

        // For backward compatibility, maintain static reference
        mOsdDb = LogRepository.getDatabase();

        Log.i(TAG, "Starting Remote Database Interface");
        if (USE_FIREBASE_BACKEND) {
            mWac = new WebApiConnection_firebase(mContext);
        } else {
            mWac = new WebApiConnection_osdapi(mContext);
        }

        mWac.setStoredToken(mAuthToken);

        // Initialize uploader for remote API orchestration
        mUploader = new LogUploader(mContext, mUtil, mRepository, mWac, mEventDuration,
                mLogRemote, mLogRemoteMobile, this::showToastSafe);

        // Initialize querier for local database queries
        mQuerier = new LocalEventQuerier(mContext, mUtil, mRepository);

        // Register the network change callback to listen for connectivity changes.
        // Since minSdk is 26 (Android 8.0), registerDefaultNetworkCallback is always available (added in API 24).
        ConnectivityManager cm = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            mNetworkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    super.onAvailable(network);
                    Log.i(TAG, "NetworkCallback.onAvailable() - Network connectivity available");
                    triggerUploadIfAppropriate();
                }
            };
            cm.registerDefaultNetworkCallback(mNetworkCallback);
            Log.i(TAG, "DefaultNetworkCallback registered successfully");
        }

        if (mLogRemote) {
            Log.i(TAG, "Starting Remote Log Timer");
            startRemoteLogTimer();
        } else {
            Log.i(TAG, "mLogRemote is false - not starting remote log timer");
        }

        if (mAutoPruneDb) {
            Log.i(TAG, "Starting Auto Prune Timer");
            startAutoPruneTimer();
        } else {
            Log.i(TAG, "AutoPruneDB is not set - not starting Auto Prune Timer");
        }

        if (mLogNDA) {
            Log.i(TAG, "Starting Normal Daily Activity Log Timer");
            startNDATimer();
        } else {
            Log.i(TAG, "mLogNDA is false - not starting Normal Daily Activity Log timer");
        }

    }

    /**
     * Returns a JSON String representing an array of datapoints that are selected from sqlite cursor c.
     *
     * @param c sqlite cursor pointing to datapoints query result.
     * @return JSON String.
     * from https://stackoverflow.com/a/20488153/2104584
     */
    private String cursor2Json(Cursor c) {
        try {
            StringBuilder cNames = new StringBuilder();
            for (String n : c.getColumnNames()) {
                cNames.append(", ").append(n);
            }
            //Log.v(TAG,"cursor2Json() - c="+c.toString()+", columns="+cNames+", number of rows="+c.getCount());
            c.moveToFirst();
            //JSONObject Root = new JSONObject();
            JSONArray dataPointArray = new JSONArray();
            int i = 0;
            while (!c.isAfterLast()) {
                JSONObject datapoint = new JSONObject();
                try {
                    datapoint.put("id", c.getString(c.getColumnIndex("id")));
                    datapoint.put("dataTime", c.getString(c.getColumnIndex("dataTime")));
                    datapoint.put("status", c.getString(c.getColumnIndex("status")));
                    datapoint.put("dataJSON", c.getString(c.getColumnIndex("dataJSON")));
                    datapoint.put("uploaded", c.getString(c.getColumnIndex("uploaded")));
                    //Log.v(TAG,"cursor2json() - datapoint="+datapoint.toString());
                    c.moveToNext();
                    dataPointArray.put(i, datapoint);
                    i++;
                } catch (JSONException | NullPointerException e) {
                    Log.e(TAG, "cursor2Json(): error creating JSON Object");
                    e.printStackTrace();
                }
            }
            return dataPointArray.toString();
        } finally {
            try {
                if (c != null && !c.isClosed()) {
                    c.close();
                }
            } catch (Exception e) {
                Log.w(TAG, "cursor2Json(): error closing cursor: " + e.toString());
            }
        }
    }

    /**
     * Returns a JSON String representing an array of events that are selected from sqlite cursor c.
     *
     * @param c sqlite cursor pointing to events query result.
     * @return JSON String.
     * from https://stackoverflow.com/a/20488153/2104584
     */
    private String eventCursor2Json(Cursor c) {
        try {
            StringBuilder cNames = new StringBuilder();
            for (String n : c.getColumnNames()) {
                cNames.append(", ").append(n);
            }
            c.moveToFirst();
            Log.v(TAG, "eventCursor2Json: size of cursor=" + c.getCount());
            JSONArray eventsArray = new JSONArray();
            int i = 0;
            while (!c.isAfterLast()) {
                JSONObject event = new JSONObject();
                try {
                    String val;
                    val = c.getString(c.getColumnIndex("id"));
                    // We replace null values with empty string, otherwise they are completely excluded from output JSON.
                    event.put("id", val == null ? "" : val);
                    val = c.getString(c.getColumnIndex("dataTime"));
                    event.put("dataTime", val == null ? "" : val);
                    val = c.getString(c.getColumnIndex("status"));
                    event.put("status", val == null ? "" : val);
                    val = c.getString(c.getColumnIndex("type"));
                    event.put("type", val == null ? "" : val);
                    val = c.getString(c.getColumnIndex("subType"));
                    event.put("subType", val == null ? "" : val);
                    // Add alarmCause to JSON if present (it should be since v4)
                    if (c.getColumnIndex("alarmCause") != -1) {
                        val = c.getString(c.getColumnIndex("alarmCause"));
                        event.put("alarmCause", val == null ? "" : val);
                    }
                    val = c.getString(c.getColumnIndex("notes"));
                    event.put("desc", val == null ? "" : val);
                    val = c.getString(c.getColumnIndex("dataJSON"));
                    event.put("dataJSON", val == null ? "" : val);
                    val = c.getString(c.getColumnIndex("uploaded"));
                    event.put("uploaded", val == null ? "" : val);
                    c.moveToNext();
                    eventsArray.put(i, event);
                    i++;
                } catch (JSONException | NullPointerException e) {
                    Log.e(TAG, "eventCursor2Json(): error creating JSON Object");
                    e.printStackTrace();
                }
            }
            Log.v(TAG, "eventCursor2JSON(): returning " + eventsArray.toString());
            return eventsArray.toString();
        } finally {
            try {
                if (c != null && !c.isClosed()) {
                    c.close();
                }
            } catch (Exception e) {
                Log.w(TAG, "eventCursor2Json(): error closing cursor: " + e.toString());
            }
        }
    }


    private static boolean openDb() {
        Log.d(TAG, "openDb");
        try {
            if (mOsdDb == null) {
                Log.i(TAG, "openDb: mOsdDb is null - initialising");
                Log.d(TAG, "openDb: Creating OsdDbHelper and getting writable database");
                OsdDbHelper helper = new OsdDbHelper(mContext);
                mOsdDb = helper.getWritableDatabase();
                Log.i(TAG, "openDb: Database opened successfully");
                Log.d(TAG, "openDb: Database version is now: " + mOsdDb.getVersion());
            } else {
                Log.i(TAG, "openDb: mOsdDb has been initialised already so not doing anything");
            }
            String[] tableNames = new String[]{mDpTableName, mEventsTableName};
            for (String tableName : tableNames) {
                if (!checkTableExists(mOsdDb, tableName)) {
                    Log.e(TAG, "ERROR - Table " + tableName + " does not exist");
                    return false;
                } else {
                    Log.d(TAG, "table " + tableName + " exists ok");
                }
            }

            // Log the schema to help debug
            try {
                Cursor schemaCursor = mOsdDb.rawQuery("PRAGMA table_info(datapoints)", null);
                Log.d(TAG, "openDb: Datapoints table has " + schemaCursor.getCount() + " columns");
                int nameIdx = schemaCursor.getColumnIndex("name");
                schemaCursor.moveToFirst();
                StringBuilder columns = new StringBuilder("Columns: ");
                while (!schemaCursor.isAfterLast()) {
                    columns.append(schemaCursor.getString(nameIdx)).append(", ");
                    schemaCursor.moveToNext();
                }
                Log.d(TAG, "openDb: " + columns.toString());
                schemaCursor.close();
            } catch (Exception e) {
                Log.w(TAG, "openDb: Could not log schema info: " + e.getMessage());
            }

        } catch (SQLException e) {
            Log.e(TAG, "Failed to open Database: " + e.toString());
            return false;
        }
        return true;
    }

    private static boolean checkTableExists(SQLiteDatabase osdDb, String osdTableName) {
        Cursor c = null;
        boolean tableExists = false;
        Log.d(TAG, "checkTableExists()");
        try {
            c = osdDb.query(osdTableName, null,
                    null, null, null, null, null);
            tableExists = true;
            c.close();
        } catch (Exception e) {
            Log.d(TAG, osdTableName + " doesn't exist :(((");
        }
        return tableExists;
    }

    /**
     * Static getter to access the database for history loading operations.
     * Called by CircBufHistoryLoader to restore persisted history on app startup.
     *
     * @return The SQLiteDatabase instance, or null if not initialized
     */
    public static SQLiteDatabase getDatabase() {
        SQLiteDatabase db = LogRepository.getDatabase();
        mOsdDb = db;
        return db;
    }


    /**
     * Write data to local database including history CircBuf data
     * Delegates to repository
     */
    public void writeDatapointToLocalDb(SdData sdData, uk.org.openseizuredetector.data.SdDataHistory sdDataHistory) {
        mRepository.writeDatapointToLocalDb(sdData, sdDataHistory);
    }

    /**
     * Create a local event - simple overload with status only
     * Delegates to repository
     */
    public boolean createLocalEvent(String dataTime, long status) {
        return mRepository.createLocalEvent(dataTime, status);
    }

    /**
     * Create a local event with full details
     * Delegates to repository
     */
    public boolean createLocalEvent(String dataTime, long status, String type, String subType, String alarmCause, String desc, String dataJSON) {
        return mRepository.createLocalEvent(dataTime, status, type, subType, alarmCause, desc, dataJSON);
    }

    /**
     * Returns a json representation of locally stored event 'id'.
     * Delegates to repository
     */
    public String getLocalEventById(long id) {
        return mRepository.getLocalEventById(id);
    }

    /**
     * Returns a json representation of datapoint 'id'.
     * Delegates to repository
     */
    public String getDatapointById(long id) {
        return mRepository.getDatapointById(id);
    }

    /**
     * Mark datapoint as uploaded
     * Delegates to repository
     */
    public boolean setDatapointToUploaded(int id, String eventId) {
        return mRepository.setDatapointToUploaded(id, eventId);
    }

    /**
     * Update datapoint status
     * Delegates to repository
     */
    public boolean setDatapointStatus(Long id, int statusVal) {
        return mRepository.setDatapointStatus(id, statusVal);
    }

    /**
     * Return a JSON string representing all the datapoints between startDate and endDate
     * Delegates to repository
     */
    public boolean getDatapointsByDate(String startDateStr, String endDateStr, WebApiConnection.StringCallback callback) {
        return mRepository.getDatapointsByDate(startDateStr, endDateStr, callback);
    }

    /**
     * Export datapoints to a CSV file.
     * Delegates to LocalEventQuerier.
     */
    public void exportToCsvFile(Date endDate, double duration, Uri uri, BooleanCallback callback) {
        if (mQuerier != null) {
            mQuerier.exportToCsvFile(endDate, duration, uri, callback);
        }
    }

    /**
     * Get a list of events from the local database.
     * Delegates to LocalEventQuerier.
     */
    public boolean getEventsList(boolean includeWarnings, ArrayListCallback callback) {
        if (mQuerier != null) {
            return mQuerier.getEventsList(includeWarnings, callback);
        }
        return false;
    }

    /**
     * Get count of events in the local database.
     * Delegates to LocalEventQuerier.
     */
    public boolean getLocalEventsCount(boolean includeWarnings, WebApiConnection.LongCallback callback) {
        if (mQuerier != null) {
            return mQuerier.getLocalEventsCount(includeWarnings, callback);
        }
        return false;
    }

    /**
     * Get count of datapoints in the local database.
     * Delegates to LocalEventQuerier.
     */
    public boolean getLocalDatapointsCount(WebApiConnection.LongCallback callback) {
        if (mQuerier != null) {
            return mQuerier.getLocalDatapointsCount(callback);
        }
        return false;
    }

    /**
     * Find the datapoint nearest to a given date/time.
     * Delegates to LocalEventQuerier.
     */
    public boolean getNearestDatapointToDate(String dateStr, WebApiConnection.LongCallback callback) {
        if (mQuerier != null) {
            return mQuerier.getNearestDatapointToDate(dateStr, callback);
        }
        return false;
    }


    /**
     * pruneLocalDb() - removes data that is older than mLocalDbMaxAgeDays days
     * Delegates to repository
     */
    public int pruneLocalDb() {
        return mRepository.pruneLocalDb();
    }

    /**
     * Mark an event as uploaded
     * Delegates to repository
     */
    public boolean setEventToUploaded(long localEventId, String remoteEventId) {
        return mRepository.setEventToUploaded(localEventId, remoteEventId);
    }


    /**
     * Return the ID of the next event (alarm, warning, fall etc) that needs to be uploaded.
     * Delegates to repository
     */
    public boolean getNextEventToUpload(boolean includeWarnings, WebApiConnection.LongCallback callback) {
        return mRepository.getNextEventToUpload(includeWarnings, callback);
    }




    // ... existing code ...

    /***************************************************************************************
     * Remote Database Part
     */

    /**
     * Trigger an immediate upload attempt after successful authentication.
     * Does NOT refresh the Volley queue — the queue is healthy at this point and recycling it
     * would cancel any in-flight requests (e.g. getUserProfile) that were fired immediately
     * after login.  Queue recycling is handled separately by onNetworkStateChanged() when
     * an actual network transition occurs.
     */
    public void triggerImmediateUpload() {
        Log.i(TAG, "triggerImmediateUpload() - triggered by authentication");
        writeToRemoteServer();
    }

    /**
     * Write data to remote server.
     * Delegates to LogUploader.
     */
    public void writeToRemoteServer() {
        Log.v(TAG, "writeToRemoteServer()");
        if (mUploader != null) {
            mUploader.writeToRemoteServer();
        }
    }

    /**
     * Handle network state changes.
     * Delegates to LogUploader.
     */
    public void onNetworkStateChanged() {
        Log.i(TAG, "onNetworkStateChanged() - Network state has changed");
        if (mUploader != null) {
            mUploader.onNetworkStateChanged();
        }
    }


    /**
     * Upload seizure detector data to remote server.
     * Delegates to LogUploader.
     */
    public void uploadSdData() {
        Log.v(TAG, "uploadSdData() - delegating to LogUploader");
        if (mUploader != null) {
            mUploader.uploadSdData();
        }
    }

    /**
     * Mark upload as finished and reset state.
     * Delegates to LogUploader.
     */
    public void finishUpload() {
        if (mUploader != null) {
            mUploader.finishUpload();
        }
    }

    /**
     * Callback from WebApiConnection when an event is created on remote server.
     * Delegates to LogUploader.
     */
    public void createEventCallback(String eventId) {
        if (mUploader != null) {
            mUploader.createEventCallback(eventId);
        }
    }

    /**
     * Upload the next datapoint in the queue.
     * Delegates to LogUploader.
     */
    public void uploadNextDatapoint() {
        if (mUploader != null) {
            mUploader.uploadNextDatapoint();
        }
    }

    /**
     * Callback from WebApiConnection when a datapoint is uploaded.
     * Delegates to LogUploader.
     */
    public void datapointCallback(String datapointStr) {
        if (mUploader != null) {
            mUploader.datapointCallback(datapointStr);
        }
    }


    /**
     * closeNetworkConnection() - stop only the network (Volley) queue owned by THIS instance.
     * Safe to call during restart: it only touches this instance's mWac and never affects any
     * other LogManager instance that may have been created on the main thread concurrently.
     */
    public void closeNetworkConnection() {
        if (mWac != null) {
            Log.i(TAG, "closeNetworkConnection() - Stopping Remote Database Interface for this instance");
            mWac.close();
            mWac = null;
        }
    }

    /**
     * close() - shut down the logging system fully (network + database).
     * Should only be called on true app shutdown (onDestroy), not during a settings-triggered
     * restart.  For restart, call closeNetworkConnection() instead so that the database
     * (which is intentionally shared / static in LogRepository) stays open for the new instance.
     */
    public void close() {
        closeNetworkConnection();
        LogRepository.closeDatabase();
        mOsdDb = null;
    }

    public void stop() {
        // Stop the timers and shutdown the remote API connection.
        Log.i(TAG, "stop() - initiating shutdown");
        mShutdownRequested = true;

        // Shutdown uploader first (handles any ongoing uploads)
        if (mUploader != null) {
            mUploader.requestShutdown();
        }

        // Unregister the network change callback
        if (mNetworkCallback != null) {
            ConnectivityManager cm = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                try {
                    cm.unregisterNetworkCallback(mNetworkCallback);
                    Log.i(TAG, "stop() - NetworkCallback unregistered successfully");
                } catch (Exception e) {
                    Log.e(TAG, "stop() - Error unregistering NetworkCallback: " + e.getMessage());
                }
            }
        }

        stopRemoteLogTimer();
        stopAutoPruneTimer();
        stopNDATimer();

        Log.i(TAG, "stop() - shutdown complete");
    }

    /*
     * Start the timer that will upload data to the remote server after a given period.
     */
    private void startRemoteLogTimer() {
        startRemoteLogTimer(mRemoteLogPeriod);
    }

    private void startRemoteLogTimer(long delaySeconds) {
        if (delaySeconds <= 0) {
            Log.w(TAG, "startRemoteLogTimer - delaySeconds <= 0 (" + delaySeconds + "). Using default of 60s to prevent loop.");
            delaySeconds = 60;
        }
        if (mRemoteLogTimer != null) {
            Log.i(TAG, "startRemoteLogTimer -timer already running - cancelling it");
            mRemoteLogTimer.cancel();
            mRemoteLogTimer = null;
        }
        Log.i(TAG, "startRemoteLogTimer() - starting RemoteLogTimer with period " + delaySeconds + "s");
        mRemoteLogTimer =
                new RemoteLogTimer(delaySeconds * 1000, 1000);
        mRemoteLogTimer.start();
    }


    /*
     * Cancel the remote logging timer to prevent attempts to upload to remote database.
     */
    public void stopRemoteLogTimer() {
        if (mRemoteLogTimer != null) {
            Log.i(TAG, "stopRemoteLogTimer(): cancelling Remote Log timer");
            mRemoteLogTimer.cancel();
            mRemoteLogTimer = null;
        }
    }

    /*
     * Start the timer that will log Normal Daily Activity continuously.
     */
    private void startNDATimer() {
        if (mNDATimer != null) {
            Log.i(TAG, "startNDATimer -timer already running - cancelling it");
            mNDATimer.cancel();
            mNDATimer = null;
        }
        Log.i(TAG, "startNDATimer() - starting NDATimer");
        // We set the timer to timeout after the event duration, so that we record all data
        // without a gap.
        mNDATimer =
                new NDATimer(mEventDuration * 1000, 1000, mNDALogPeriodHours);
        mNDATimer.start();

        // If we do not have a stored start time for NDA logging, set it to current time
        // and store it.
        SharedPreferences SP = PreferenceManager
                .getDefaultSharedPreferences(mContext);
        mNDATimerStartTime = SP.getLong("NDATimerStartTime", 0);
        if (mNDATimerStartTime == 0) {
            mNDATimerStartTime = System.currentTimeMillis();
            SharedPreferences.Editor editor = SP.edit();
            editor.putLong("NDATimerStartTime", mNDATimerStartTime);
            editor.putBoolean("LogNDA", true);
            editor.apply();
        }
        long tNow = System.currentTimeMillis();
        long tDiffMillis = (tNow - mNDATimerStartTime);
        mNDATimeRemaining = mNDALogPeriodHours - tDiffMillis / (3600. * 1000.);


    }

    /*
     * Cancel the Normal Daily Actity Log timer
     */
    public void stopNDATimer() {
        if (mNDATimer != null) {
            Log.i(TAG, "stopNDATimer(): cancelling Normal Daily Activity timer");
            mNDATimer.cancel();
            mNDATimer = null;
        }
    }

    public void disableNDATimer() {
        SharedPreferences SP = PreferenceManager
                .getDefaultSharedPreferences(mContext);
        SharedPreferences.Editor editor = SP.edit();
        editor.putBoolean("LogNDA", false);
        editor.apply();
    }

    public void enableNDATimer() {
        SharedPreferences SP = PreferenceManager
                .getDefaultSharedPreferences(mContext);
        SharedPreferences.Editor editor = SP.edit();
        editor.putBoolean("LogNDA", true);
        editor.apply();
    }

    /*
     * Start the timer that will Auto Prune the database
     */
    private void startAutoPruneTimer() {
        if (mAutoPruneTimer != null) {
            Log.i(TAG, "startAutoPruneTimer -timer already running - cancelling it");
            mAutoPruneTimer.cancel();
            mAutoPruneTimer = null;
        }
        Log.i(TAG, "startAutoPruneTimer() - starting AutoPruneTimer");
        mAutoPruneTimer =
                new AutoPruneTimer(mAutoPrunePeriod * 1000, 1000);
        mAutoPruneTimer.start();
    }


    /*
     * Cancel the auto prune timer to prevent attempts to upload to remote database.
     */
    public void stopAutoPruneTimer() {
        if (mAutoPruneTimer != null) {
            Log.i(TAG, "stopAutoPruneTimer(): cancelling Auto Prune timer");
            mAutoPruneTimer.cancel();
            mAutoPruneTimer = null;
        }
    }


    public void createNDAEvent() {
        Log.i(TAG, "createNDAEvent()");
        Date curDate = new Date();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String dateStr = dateFormat.format(curDate);
        // Pass "nda" as alarmCause, null as subType
        createLocalEvent(dateStr, 6, "nda", null, "nda", null,
                mSdSettingsData.toSettingsJSON());
    }

    public void updateSdData(SdData sdData) {
        mSdSettingsData = sdData;
    }


    public static class OsdDbHelper extends SQLiteOpenHelper {
               // If you change the database schema, you must increment the database version.
        public static final int DATABASE_VERSION = 4;
        public static final String DATABASE_NAME = "OsdData.db";
        private static final String TAG = "LogManager.OsdDbHelper";

        public OsdDbHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
            Log.d(TAG, "OsdDbHelper constructor - DATABASE_VERSION=" + DATABASE_VERSION);
        }

        public void onCreate(SQLiteDatabase db) {
            Log.i(TAG, "onCreate - TableName=" + mDpTableName);
            String SQLStr = "CREATE TABLE IF NOT EXISTS " + mDpTableName + "("
                    + "id INTEGER PRIMARY KEY,"
                    + "dataTime DATETIME,"
                    + "status INT,"
                    + "dataJSON TEXT,"
                    + "uploaded TEXT,"  // Stores the ID of the datapoint in the remote database if uploaded, otherwise empty
                    // Circular buffer history columns (JSON-encoded)
                    + "watchBattHist TEXT,"           // Watch battery history (24 hour buffer, 1 sample per 5 sec)
                    + "phoneBattHist TEXT,"           // Phone battery history (24 hour buffer, 1 sample per 5 sec)
                    + "signalStrengthHist TEXT,"      // Watch signal strength history (10 minute buffer)
                    + "pseudSeizureHist TEXT,"        // ML seizure probability history (10 minute buffer)
                    + "accelMagStdDevHist TEXT,"      // Acceleration magnitude std dev history (10 minute buffer)
                    + "hrHist TEXT"                   // Heart rate history (10 minute buffer)
                    + ");";
            db.execSQL(SQLStr);
            Log.i(TAG, "onCreate - Created " + mDpTableName + " with history columns");
            Log.i(TAG, "onCreate - TableName=" + mEventsTableName);
            SQLStr = "CREATE TABLE IF NOT EXISTS " + mEventsTableName + "("
                    + "id INTEGER PRIMARY KEY,"
                    + "dataTime DATETIME,"
                    + "status INT,"
                    + "type TEXT,"
                    + "subType TEXT,"
                    + "alarmCause TEXT," // Add alarmCause column
                    + "notes TEXT,"    // avoiding using 'desc' as that is an sql name.
                    + "dataJSON TEXT,"
                    + "uploaded TEXT"  // stores the id of the event in the remote dabase if uploaded, otherwise empty
                    + ");";
            db.execSQL(SQLStr);
            Log.i(TAG, "onCreate - Created " + mEventsTableName);
        }

        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // This database is only a cache for online data, so its upgrade policy is
            // to add new columns if upgrading from v1 to v2, or discard and start over for other scenarios
            Log.i(TAG, "onUpgrade() - CALLED: oldVersion=" + oldVersion + ", newVersion=" + newVersion);

            if (oldVersion < 4 && newVersion == 4) {
                // If upgrading from any version < 4 to 4, we need to handle history columns AND alarmCause

                // First, ensure all v3 columns exist (history columns in datapoints)
                // This logic handles v1->4, v2->4 and v3->4
                boolean v3ColumnsExist = false;
                try {
                    Cursor c = db.rawQuery("PRAGMA table_info(" + mDpTableName + ")", null);
                    int nameIdx = c.getColumnIndex("name");
                    if (nameIdx != -1) {
                        while (c.moveToNext()) {
                            if (c.getString(nameIdx).equals("hrHist")) {
                                v3ColumnsExist = true;
                                break;
                            }
                        }
                    }
                    c.close();
                } catch (Exception e) {
                    Log.w(TAG, "onUpgrade: Error checking for v3 columns", e);
                }

                if (!v3ColumnsExist) {
                    // Assuming if hrHist is missing, we need to add all history columns (simplified approach)
                    // This covers v1->4 and v2->4 scenarios roughly
                    try {
                        Log.i(TAG, "onUpgrade() - Adding missing history columns for v4 upgrade");
                        // We use try-catch for each column to be safe if some exist
                        String[] cols = {"watchBattHist", "phoneBattHist", "signalStrengthHist",
                                         "pseudSeizureHist", "accelMagStdDevHist", "hrHist"};
                        for (String col : cols) {
                            try {
                                db.execSQL("ALTER TABLE " + mDpTableName + " ADD COLUMN " + col + " TEXT;");
                            } catch (SQLException e) {
                                // Ignore error if column exists
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "onUpgrade() - Error adding history columns: " + e.getMessage());
                    }
                }

                // Now add the new v4 column: alarmCause to events table
                try {
                    Log.i(TAG, "onUpgrade() - Adding alarmCause column to " + mEventsTableName);
                    db.execSQL("ALTER TABLE " + mEventsTableName + " ADD COLUMN alarmCause TEXT;");
                    Log.i(TAG, "✅ onUpgrade() - alarmCause column added successfully");
                } catch (SQLException e) {
                    Log.e(TAG, "❌ onUpgrade() - Error adding alarmCause column: " + e.getMessage());
                    e.printStackTrace();
                }

                Log.i(TAG, "✅ onUpgrade() - Upgrade to v4 complete");
                return;
            }

            // ... strict fallback for other cases ...
            Log.w(TAG, "onUpgrade() - Unsupported version change (v" + oldVersion + "->v" + newVersion + "), recreating tables");
            db.execSQL("DROP TABLE IF EXISTS " + mDpTableName + ";");
            onCreate(db);
        }

        public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.i(TAG, "onDowngrade() - oldVersion=" + oldVersion + ", newVersion=" + newVersion);
            onUpgrade(db, oldVersion, newVersion);
        }
    }


    /**
     * Upload recorded data to the remote database periodically.
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
            if (mShutdownRequested) {
                Log.d(TAG, "mRemoteLogTimer - onFinish - shutdown requested, not uploading");
                return;
            }
            Log.d(TAG, "mRemoteLogTimer - onFinish - uploading data to remote database");
            writeToRemoteServer();
            // Restart this timer.
            if (!mShutdownRequested) {
                // We restart by calling startRemoteLogTimer() which handles validation and new instance creation
                // instead of restarting the potentially invalid current timer instance
                startRemoteLogTimer();
            }
        }

    }

    /**
     * Log Normal Daily Activities periodically.
     */
    private class NDATimer extends CountDownTimer {
        double mNDALogPeriodHours = 0;

        public NDATimer(long startTime, long interval, double logPeriod) {
            super(startTime, interval);
            mNDALogPeriodHours = logPeriod;
        }

        @Override
        public void onTick(long l) {
            // Do Nothing
        }

        @Override
        public void onFinish() {
            if (mShutdownRequested) {
                Log.d(TAG, "mNDATimer - onFinish - shutdown requested, not logging NDA event");
                return;
            }
            Log.d(TAG, "mNDATimer - onFinish - Recording a Normal Daily Activity Event");
            createNDAEvent();
            // Check if we have been logging NDA events for more than the set limit.  If it has, we disable it
            // and set the start time to zero so it is re-set next time NDA logging is enabled.
            long tNow = Calendar.getInstance().getTimeInMillis();
            long tDiffMillis = (tNow - mNDATimerStartTime);
            double tDiffHrs = tDiffMillis / (3600. * 1000.);
            mNDATimeRemaining = mNDALogPeriodHours - tDiffHrs;
            if (tDiffHrs >= mNDALogPeriodHours) {
                Log.i(TAG, "mNDATimer - onFinish - NDA logging period completed - switching off NDA Logging");
                SharedPreferences SP = PreferenceManager
                        .getDefaultSharedPreferences(mContext);
                SharedPreferences.Editor editor = SP.edit();
                editor.putLong("NDATimerStartTime", 0);
                editor.putBoolean("LogNDA", false);
                editor.apply();
            } else {
                // Restart this timer.
                Log.i(TAG, "NDATimer - tDiffMillis=" + tDiffMillis + ", tdiffHrs = " + tDiffHrs + ", tnow=" + tNow + ", tstart=" + mNDATimerStartTime + ", NDALogPeriod=" + mNDALogPeriodHours);
                Log.i(TAG, "NDATimer - re-starting NDA timer");
                if (!mShutdownRequested) {
                    start();
                } else {
                    Log.i(TAG, "NDATimer - shutdown requested, not restarting timer");
                }
            }
        }

    }


    /**
     * Prune the database periodically.
     */
    private class AutoPruneTimer extends CountDownTimer {
        public AutoPruneTimer(long startTime, long interval) {
            super(startTime, interval);
        }

        @Override
        public void onTick(long l) {
        }

        @Override
        public void onFinish() {
            if (mShutdownRequested) {
                Log.d(TAG, "mAutoPruneTimer - onFinish - shutdown requested, not pruning");
                return;
            }
            Log.d(TAG, "mAutoPruneTimer - onFinish - Pruning Local Database");
            pruneLocalDb();
            // Restart this timer.
            if (!mShutdownRequested) {
                start();
            }
        }

    }


    /**
     * Helper to trigger upload. Extracted from NetworkChangeReceiver to be reused by NetworkCallback.
     */
    private void triggerUploadIfAppropriate() {
        if (mShutdownRequested) {
            Log.d(TAG, "triggerUploadIfAppropriate - Shutdown requested, not attempting upload");
            return;
        }

        // Check if we have network connectivity
        if (!mUtil.isNetworkConnected()) {
            Log.d(TAG, "triggerUploadIfAppropriate - No network connection");
            return;
        }

        Log.i(TAG, "triggerUploadIfAppropriate - Triggering immediate upload attempt");
        // We trigger the upload immediately, then restart the timer with the normal period
        // Just calling startRemoteLogTimer(0) causes a stack overflow because the timer
        // restarts itself with 0 delay in onFinish().
        writeToRemoteServer();
        startRemoteLogTimer();
    }


}
