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
    private RemoteLogTimer mRemoteLogTimer;
    private boolean mLogNDA;
    public NDATimer mNDATimer;
    private long mNDATimerStartTime;  // milliseconds
    public double mNDATimeRemaining; // hours
    public double mNDALogPeriodHours = 24.0;  // hours
    private static Context mContext;
    private static OsdUtil mUtil;
    public static WebApiConnection mWac;
    public static final boolean USE_FIREBASE_BACKEND = false;

    private boolean mUploadInProgress;
    private volatile boolean mShutdownRequested = false;
    private final Object mUploadLock = new Object();
    private final Handler mUiHandler = new Handler(android.os.Looper.getMainLooper());
    private long mEventDuration = 120;   // event duration in seconds - uploads datapoints that cover this time range centred on the event time.
    public long mDataRetentionPeriod = 1; // Prunes the local db so it only retains data younger than this duration (in days)
    private long mRemoteLogPeriod = 10; // Period in seconds between uploads to the remote server.
    private ArrayList<JSONObject> mDatapointsToUploadList;
    private String mCurrentEventRemoteId;
    private long mCurrentEventLocalId = -1;
    private int mCurrentDatapointId;
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
        openDb();
        Log.i(TAG, "Starting Remote Database Interface");
        if (USE_FIREBASE_BACKEND) {
            mWac = new WebApiConnection_firebase(mContext);
        } else {
            mWac = new WebApiConnection_osdapi(mContext);
        }

        mWac.setStoredToken(mAuthToken);

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
        return mOsdDb;
    }


    /**
     * Write data to local database including history CircBuf data
     * Executes on background thread to avoid blocking UI
     * History buffers are now stored in SdDataHistory instead of SdData
     */
    public void writeDatapointToLocalDb(SdData sdData, uk.org.openseizuredetector.data.SdDataHistory sdDataHistory) {
        //Log.v(TAG, "writeDatapointToLocalDb()");
        Date curDate = new Date();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        final String dateStr = dateFormat.format(curDate);

        if (mOsdDb == null) {
            Log.e(TAG, "writeDatapointToLocalDb(): mOsdDb is null - doing nothing");
            return;
        }

        // Execute database write on background thread to avoid UI blocking
        BackgroundTaskExecutor.executeAndForget(() -> {
            try {
                //Log.d(TAG, "writeDatapointToLocalDb(): Starting to serialize history buffers");

                // Serialize history CircBuf objects to JSON
                String watchBattHist = "";
                String phoneBattHist = "";
                String signalStrengthHist = "";
                String pseudSeizureHist = "";
                String accelMagStdDevHist = "";
                String hrHist = "";

                // Build INSERT statement with history columns
                String SQLStr = "INSERT INTO " + mDpTableName
                        + "(dataTime, status, dataJSON, uploaded, watchBattHist, phoneBattHist, "
                        + "signalStrengthHist, pseudSeizureHist, accelMagStdDevHist, hrHist)"
                        + " VALUES("
                        + "'" + dateStr + "',"
                        + sdData.alarmState + ","
                        + DatabaseUtils.sqlEscapeString(sdData.toDatapointJSON()) + ","
                        + "0,"  // uploaded = 0 (not uploaded)
                        + DatabaseUtils.sqlEscapeString(watchBattHist) + ","
                        + DatabaseUtils.sqlEscapeString(phoneBattHist) + ","
                        + DatabaseUtils.sqlEscapeString(signalStrengthHist) + ","
                        + DatabaseUtils.sqlEscapeString(pseudSeizureHist) + ","
                        + DatabaseUtils.sqlEscapeString(accelMagStdDevHist) + ","
                        + DatabaseUtils.sqlEscapeString(hrHist)
                        + ")";

                mOsdDb.execSQL(SQLStr);
                Log.v(TAG, "writeDatapointToLocalDb(): datapoint with history written to database");

                if (sdData.alarmState != uk.org.openseizuredetector.data.AlarmState.OK) {
                    Log.i(TAG, "writeDatapointToLocalDb(): adding event to local DB");
                    // Use sdData.alarmCause for subType, and infer type from alarmState
                    //String type = "Unknown";
                    //if (sdData.alarmState == 2) type = "Seizure";
                    //if (sdData.alarmState == 1) type = "Warning";
                    //if (sdData.alarmState == 3) type = "Fall";
                    //if (sdData.alarmState == 4) type = "Manual";

                    // We now store alarmCause in its own column, and leave subType null
                    createLocalEvent(dateStr, sdData.alarmState, null, null, sdData.alarmCause, null, sdData.toSettingsJSON());
                }
            } catch (SQLException e) {
                Log.e(TAG, "writeDatapointToLocalDb(): SQL Error Writing Data: " + e.toString());
                e.printStackTrace();
            } catch (NullPointerException e) {
                Log.e(TAG, "writeDatapointToLocalDb(): Null Pointer Exception: " + e.toString());
                e.printStackTrace();
            } catch (Exception e) {
                Log.e(TAG, "writeDatapointToLocalDb(): Unexpected error: " + e.toString());
                e.printStackTrace();
            }
        });
    }

    public boolean createLocalEvent(String dataTime, long status) {
        return (createLocalEvent(dataTime, status, null, null, null, null, null));
    }

    public boolean createLocalEvent(String dataTime, long status, String type, String subType, String alarmCause, String desc, String dataJSON) {
        // Expects dataTime to be in format: SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        Log.d(TAG, "createLocalEvent() - dataTime=" + dataTime + ", status=" + status + ", dataJSON=" + dataJSON);
        // Write Event to database
        ContentValues values = new ContentValues();
        values.put("dataTime", dataTime);
        values.put("status", status);
        values.put("type", type);
        values.put("subType", subType);
        values.put("alarmCause", alarmCause);
        values.put("notes", desc);
        values.put("dataJSON", dataJSON);

        if (mOsdDb != null) {
            long newRowId = mOsdDb.insert(mEventsTableName, null, values);
            Log.d(TAG, "createLocalEvent(): Created Row ID" + newRowId);
            return true;
        } else {
            Log.e(TAG,"createLocalEvent() - mOsdDb is null");
            showToastSafe(mContext.getString(R.string.error_failed_to_create_local_event));
            return false;
        }
    }

    /**
     * Returns a json representation of locally stored event 'id'.
     *
     * @param id event id to return
     * @return JSON representation of requested event (single element JSON array)
     */
    public String getLocalEventById(long id) {
        Log.d(TAG, "getLocalEventById() - id=" + id);
        Cursor c;
        String retVal;
        try {
            String selectStr = "select * from " + mEventsTableName + " where id=" + id + ";";
            c = mOsdDb.rawQuery(selectStr, null);
            retVal = eventCursor2Json(c);
        } catch (Exception e) {
            Log.d(TAG, "getLocalEventById(): Error Querying Database: " + e.getLocalizedMessage());
            retVal = null;
        }
        Log.d(TAG, "getLocalEventById() - returning " + retVal);
        return (retVal);
    }


    /**
     * Returns a json representation of datapoint 'id'.
     *
     * @param id datapoint id to return
     * @return JSON representation of requested datapoint (single element JSON array)
     */
    public String getDatapointById(long id) {
        Log.d(TAG, "getDatapointById() - id=" + id);
        Cursor c;
        String retVal;
        try {
            String selectStr = "select * from " + mDpTableName + " where id=" + id + ";";
            c = mOsdDb.rawQuery(selectStr, null);
            retVal = cursor2Json(c);
        } catch (Exception e) {
            Log.d(TAG, "getDatapointById(): Error Querying Database: " + e.getLocalizedMessage());
            retVal = null;
        }
        return (retVal);
    }

    /**
     * setDatapointToUploaded
     *
     * @param id      - datapoint ID to change
     * @param eventId - the eventId associated with the uploaded datapoint - the 'uploaded' field is set to this value.
     * @return True on success or False on failure.
     */
    public boolean setDatapointToUploaded(int id, String eventId) {
        Log.d(TAG, "setDatapointToUploaded() - id=" + id);
        if (mOsdDb == null) {
            Log.e(TAG, "setDatapointToUploaded() - mOsdDb is null - not doing anything");
            return false;
        }
        ContentValues cv = new ContentValues();
        cv.put("uploaded", eventId);
        int nRowsUpdated = mOsdDb.update(mDpTableName, cv, "id = ?",
                new String[]{String.format("%d", id)});
        return (nRowsUpdated == 1);
    }

    /**
     * setDatapointStatus() - Update the status of data point id.
     *
     * @param id        datapont id
     * @param statusVal the status to set for the datapoint.
     * @return true on success or false on failure
     */
    public boolean setDatapointStatus(Long id, int statusVal) {
        Log.d(TAG, "setDatapointStatus() - id=" + id + ", statusVal=" + statusVal);
        //Cursor c = null;
        ContentValues cv = new ContentValues();
        cv.put("status", statusVal);
        int nRowsUpdated = mOsdDb.update(mDpTableName, cv, "id = ?",
                new String[]{String.format("%d", id)});

        return (nRowsUpdated == 1);
    }


    /**
     * Return a JSON string representing all the datapoints between startDate and endDate
     *
     * @return True on successful start or false if call fails.
     */
    public boolean getDatapointsByDate(String startDateStr, String endDateStr, WebApiConnection.StringCallback callback) {
        Log.d(TAG, "getDatapointsbyDate() - startDateStr=" + startDateStr + ", endDateStr=" + endDateStr);
        String[] columns = {"*"};
        String whereClause = "DataTime>? AND DataTime<?";
        String[] whereArgs = {startDateStr, endDateStr};
        executeSelectQuery(mDpTableName, columns, whereClause, whereArgs,
                null, null, "dataTime DESC", (Cursor cursor) -> {
            Log.v(TAG, "getDataPointsByDate - returned " + cursor);
            try {
                if (cursor != null) {
                    callback.accept(cursor2Json(cursor));
                } else {
                    Log.w(TAG, "getDatapointsByDate() - returned null result");
                    callback.accept(null);
                }
            } finally {
                // cursor closed inside cursor2Json/eventCursor2Json; nothing to do here
            }
        });
        return (true);
    }

    /**
     * exportToCsvFile - export datapoints data to a csv file on the android device.
     *
     * @param endDate  end date of period to export (Date type)
     * @param duration duration in hours of period to export (double)
     * @param uri      uri of file to save.
     * @param callback function to be called on completion of the task (returns true on success, false on error)
     */
    public void exportToCsvFile(Date endDate, double duration, Uri uri, BooleanCallback callback) {
        Log.v(TAG, "exportToCsvFile(): uri=" + uri.toString());
        executeExportData(endDate, duration, uri, (boolean retVal) -> {
            Log.v(TAG, "exportToCsvFile - returned " + retVal);
            callback.accept(retVal);
        });
    }


    /**
     * Return an array list of objects representing the events in the database by calling the specified callback function.
     *
     * @param includeWarnings - whether to include warnings in the list of events, or just alarm conditions.
     * @return True on successful start or false if call fails.
     */
    public boolean getEventsList(boolean includeWarnings, ArrayListCallback
            callback) {
        Log.v(TAG, "getEventsList - includeWarnings=" + includeWarnings);
        ArrayList<HashMap<String, String>> eventsList = new ArrayList<>();

        String[] whereArgs = getEventWhereArgs(includeWarnings);
        String whereClause = getEventWhereClause(includeWarnings);
        //sqlStr = "SELECT * from " + mDbTableName + " where Status in (" + statusListStr + ") order by dataTime desc;";
        String[] columns = {"*"};
        executeSelectQuery(mEventsTableName, columns, whereClause, whereArgs,
                null, null, "dataTime DESC", (Cursor cursor) -> {
            Log.v(TAG, "getEventsList - returned " + cursor);
            // use outer eventsList declared above
            try {
                if (cursor != null) {
                    Log.v(TAG, "getEventsList - returned " + cursor.getCount() + " records");
                    while (!cursor.isAfterLast()) {
                        HashMap<String, String> event = new HashMap<>();
                        //event.put("id", cursor.getString(cursor.getColumnIndex("id")));
                        event.put("dataTime", cursor.getString(cursor.getColumnIndex("dataTime")));
                        int status = cursor.getInt(cursor.getColumnIndex("status"));
                        String statusStr = mUtil.alarmStatusToString(status);
                        event.put("status", statusStr);
                        event.put("uploaded", cursor.getString(cursor.getColumnIndex("uploaded")));
                        // Add type and subType
                        event.put("type", cursor.getString(cursor.getColumnIndex("type")));
                        event.put("subType", cursor.getString(cursor.getColumnIndex("subType")));
                        // Add alarmCause if column exists
                        if (cursor.getColumnIndex("alarmCause") != -1) {
                            event.put("alarmCause", cursor.getString(cursor.getColumnIndex("alarmCause")));
                        }
                        //event.put("dataJSON", cursor.getString(cursor.getColumnIndex("dataJSON")));
                        eventsList.add(event);
                        cursor.moveToNext();
                    }
                }
                callback.accept(eventsList);
            } finally {
                if (cursor != null) {
                    try { cursor.close(); } catch (Exception e) { Log.w(TAG, "getEventsList: error closing cursor: " + e.toString()); }
                }
            }
        });
        return (true);
    }


    /**
     * pruneLocalDb() removes data that is older than mLocalDbMaxAgeDays days
     */
    public int pruneLocalDb() {
        Log.d(TAG, "pruneLocalDb()");
        int retVal = 0;
        long currentDateMillis = new Date().getTime();
        long endDateMillis = currentDateMillis - 24 * 3600 * 1000 * mDataRetentionPeriod;
        //long endDateMillis = currentDateMillis - 3600*1000* mDataRetentionPeriod;  // Using hours rather than days for testing
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String endDateStr = dateFormat.format(new Date(endDateMillis));
        String[] tableNames = new String[]{mDpTableName, mEventsTableName};
        for (String tableName : tableNames) {
            Log.i(TAG, "pruneLocalDb - pruning table " + tableName);
            try {
                String selectStr = "DataTime<=?";
                String[] selectArgs = {endDateStr};
                retVal = mOsdDb.delete(tableName, selectStr, selectArgs);
            } catch (Exception e) {
                Log.d(TAG, "Error deleting data " + e.toString());
                retVal = 0;
            }
            Log.d(TAG, String.format("pruneLocalDb() - deleted %d records from table %s", retVal, tableName));
        }
        return (retVal);
    }

    /**
     * setEventToUploaded
     *
     * @param localEventId  - local Event ID to change
     * @param remoteEventId - the remote eventId associated with the uploaded datapoint - the 'uploaded' field is set to this value.
     * @return True on success or False on failure.
     */
    public boolean setEventToUploaded(long localEventId, String remoteEventId) {
        Log.d(TAG, "setEventToUploaded() - local id=" + localEventId + " remote id=" + remoteEventId);
        if (mOsdDb == null) {
            Log.e(TAG, "setEventToUploaded() - mOsdDb is null - not doing anything");
            return false;
        }
        ContentValues cv = new ContentValues();
        cv.put("uploaded", remoteEventId);
        int nRowsUpdated = mOsdDb.update(mEventsTableName, cv, "id = ?",
                new String[]{String.format("%d", localEventId)});
        return (nRowsUpdated == 1);
    }


    /**
     * Return the ID of the next event (alarm, warning, fall etc that needs to be uploaded (alarm or warning condition and has not yet been uploaded.
     *
     * @param includeWarnings - whether to include warnings in the list of events, or just alarm conditions.
     * @return True on successful start or false if call fails.
     */
    public boolean getNextEventToUpload(boolean includeWarnings, WebApiConnection.
            LongCallback callback) {
        Log.v(TAG, "getNextEventToUpload - includeWarnings=" + includeWarnings);

        String[] whereArgsStatus = getEventWhereArgs(includeWarnings);
        String whereClauseStatus = getEventWhereClause(includeWarnings);
        String[] columns = {"*"};

        // Do not try to upload very recent events so that we have chance to record the post-event data before uploading it.
        long currentDateMillis = new Date().getTime();
        long endDateMillis = currentDateMillis - 1000 * mEventDuration / 2;
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String endDateStr = dateFormat.format(new Date(endDateMillis));
        String whereClauseUploaded = "uploaded is null";
        String whereClauseDate = "DataTime<?";
        String whereClause = whereClauseStatus + " AND " + whereClauseUploaded + " AND " + whereClauseDate;

        String[] whereArgs = new String[whereArgsStatus.length + 1];
        for (int i = 0; i < whereArgsStatus.length; i++) {
            whereArgs[i] = whereArgsStatus[i];
        }
        whereArgs[whereArgsStatus.length] = endDateStr;
        executeSelectQuery(mEventsTableName, columns, whereClause, whereArgs,
                null, null, "dataTime DESC", (Cursor cursor) -> {
            long recordId = -1;
            try {
                if (cursor != null) {
                    Log.v(TAG, "getNextEventToUpload - returned " + cursor.getCount() + " records");
                    cursor.moveToFirst();
                    if (cursor.getCount() == 0) {
                        Log.v(TAG, "getNextEventToUpload() - no events to Upload - exiting");
                        recordId = -1;
                    } else {
                        recordId = cursor.getLong(0);
                        Log.d(TAG, "getNextEventToUpload(): id=" + recordId);
                    }
                }
                callback.accept(recordId);
            } finally {
                if (cursor != null) {
                    try { cursor.close(); } catch (Exception e) { Log.w(TAG, "getNextEventToUpload: error closing cursor: " + e.toString()); }
                }
            }
        });
        return (true);
    }


    /**
     * Return the ID of the datapoint that is closest to date/time string dateStr
     * Based on https://stackoverflow.com/questions/45749046/sql-get-nearest-date-record
     *
     * @return True on successful start or false if call fails.
     */
    public boolean getNearestDatapointToDate(String
                                                     dateStr, WebApiConnection.LongCallback callback) {
        Log.v(TAG, "getNextEventToDate - dateStr=" + dateStr);
        String[] columns = {"*", "(julianday(dataTime)-julianday(datetime('" + dateStr + "'))) as ddiff"};
        //SQLStr = "SELECT *, (julianday(dataTime)-julianday(datetime('" + dateStr + "'))) as ddiff from " + mDbTableName + " order by ABS(ddiff) asc;";
        String orderByStr = "ABS(ddiff) asc";
        executeSelectQuery(mDpTableName, columns, null, null,
                null, null, orderByStr, (Cursor cursor) -> {
            Log.v(TAG, "getEventsNearestDatapointToDate - returned " + cursor);
            long recordId = -1;
            try {
                if (cursor != null) {
                    Log.v(TAG, "getNearestDatapointToDate - returned " + cursor.getCount() + " records");
                    cursor.moveToFirst();
                    if (cursor.getCount() == 0) {
                        Log.v(TAG, "getNearestDatapointToDate() - no events to Upload - exiting");
                        recordId = -1;
                    } else {
                        String recordStr = cursor.getString(3);
                        recordId = cursor.getLong(0);
                        Log.d(TAG, "getNearestDatapointToDate(): id=" + recordId + ", recordStr=" + recordStr);
                    }
                }
                callback.accept(recordId);
            } finally {
                if (cursor != null) {
                    try { cursor.close(); } catch (Exception e) { Log.w(TAG, "getNearestDatapointToDate: error closing cursor: " + e.toString()); }
                }
            }
        });
        return (true);
    }


    /**
     * Return the number of events stored in the local database (via a callback).
     *
     * @param includeWarnings - whether to include warnings in the list of events, or just alarm conditions.
     * @return True on successful start or false if call fails.
     */
    public boolean getLocalEventsCount(boolean includeWarnings, WebApiConnection.
            LongCallback callback) {
        //Log.v(TAG, "getLocalEventsCount- includeWarnings=" + includeWarnings);
        String[] whereArgs = getEventWhereArgs(includeWarnings);
        String whereClause = getEventWhereClause(includeWarnings);
        String[] columns = {"*"};
        executeSelectQuery(mEventsTableName, columns, whereClause, whereArgs,
                null, null, null, (Cursor cursor) -> {
            Long eventCount = Long.valueOf(0);
            try {
                if (cursor != null) {
                    eventCount = Long.valueOf(cursor.getCount());
                    Log.v(TAG, "getLocalEventsCount - returned " + eventCount + " records");
                }
                callback.accept(eventCount);
            } finally {
                if (cursor != null) {
                    try { cursor.close(); } catch (Exception e) { Log.w(TAG, "getLocalEventsCount: error closing cursor: " + e.toString()); }
                }
            }
        });
        return (true);
    }

    /**
     * Return the number of datapoints stored in the local database (via a callback).
     *
     * @return True on successful start or false if call fails.
     */
    public boolean getLocalDatapointsCount(WebApiConnection.LongCallback callback) {
        //Log.v(TAG, "getLocalDatapointsCount");
        String[] whereArgs = null;
        String whereClause = null;
        String[] columns = {"*"};
        executeSelectQuery(mDpTableName, columns, whereClause, whereArgs,
                null, null, null, (Cursor cursor) -> {
            Long eventCount = Long.valueOf(0);
            try {
                if (cursor != null) {
                    eventCount = Long.valueOf(cursor.getCount());
                    Log.v(TAG, "getLocalDatapointsCount - returned " + eventCount + " records");
                }
                callback.accept(eventCount);
            } finally {
                if (cursor != null) {
                    try { cursor.close(); } catch (Exception e) { Log.w(TAG, "getLocalDatapointsCount: error closing cursor: " + e.toString()); }
                }
            }
        });
        return (true);
    }


    /**
     * Executes the sqlite query (=SELECT statement) using BackgroundTaskExecutor
     */
    static private void executeSelectQuery(String table, String[] columns, String selection,
                                          String[] selectionArgs, String groupBy, String having,
                                          String orderBy, CursorCallback callback) {
        BackgroundTaskExecutor.execute(
            () -> {
                Log.v(TAG, "SelectQuery: table=" + table + ", columns=" + Arrays.toString(columns)
                        + ", selection=" + selection + ", selectionArgs=" + Arrays.toString(selectionArgs)
                        + ", groupBy=" + groupBy + ", having=" + having + ", orderBy=" + orderBy);

                try {
                    Cursor resultSet = mOsdDb.query(table, columns, selection,
                            selectionArgs, groupBy, having, orderBy);
                    resultSet.moveToFirst();
                    return resultSet;
                } catch (SQLException e) {
                    Log.e(TAG, "SelectQuery: Error selecting Data: " + e.toString());
                    return null;
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "SelectQuery: Illegal Argument Exception: " + e.toString());
                    return null;
                } catch (NullPointerException e) {
                    Log.e(TAG, "SelectQuery: Null Pointer Exception: " + e.toString());
                    return null;
                }
            },
            new BackgroundTaskExecutor.Callback<Cursor>() {
                @Override
                public void onSuccess(Cursor result) {
                    callback.accept(result);
                }

                @Override
                public void onError(Exception e) {
                    Log.e(TAG, "SelectQuery error", e);
                    callback.accept(null);
                }
            }
        );
    }

    //query(String table, String[] columns, String selection, String[] selectionArgs,
    // String groupBy, String having, String orderBy)

    /**
     * Exports the contents of the local datapoints table between given dates
     * to a .csv file using BackgroundTaskExecutor
     */
    static private void executeExportData(Date endDate, double duration, Uri uri, BooleanCallback callback) {
        Log.i(TAG, "executeExportData()");

        BackgroundTaskExecutor.execute(
            () -> {
                Log.v(TAG, "ExportData.doInBackground()");
                long endDateMillis = endDate.getTime();
                long durationMillis = (long) (duration * 3600. * 1000);
                long startDateMillis = endDateMillis - durationMillis;
                Log.v(TAG, "exportData() - endDateMillis=" + endDateMillis + ", startDateMillis=" + startDateMillis + ", durationMillis=" + durationMillis);
                DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                String sDateStr = dateFormat.format(new Date(startDateMillis));
                String eDateStr = dateFormat.format(new Date(endDateMillis));
                Log.v(TAG, "ExportData - sDateStr=" + sDateStr + " eDateStr=" + eDateStr);
                String[] columns = {"*"};
                String whereClause = "DataTime>? AND DataTime<?";
                String[] whereArgs = {sDateStr, eDateStr};

                try {
                    Cursor cursor = mOsdDb.query(mDpTableName, columns, whereClause,
                            whereArgs, null, null, "dataTime DESC");
                    cursor.moveToFirst();

                    Log.v(TAG, "ExportData - returned " + cursor);
                    if (cursor != null) {
                        Log.d(TAG, "ExportData - query complete - writing to file....");
                        try {
                            ParcelFileDescriptor pfd = mContext.getContentResolver().
                                    openFileDescriptor(uri, "w");
                            FileOutputStream fileOutputStream =
                                    new FileOutputStream(pfd.getFileDescriptor());
                            fileOutputStream.write(("# dataTime, alarmState, hr, o2sat, accel*125\n").getBytes());
                            int nRec = writeDatapointsToFile(cursor, fileOutputStream);
                            // Let the document provider know you're done by closing the stream.
                            fileOutputStream.close();
                            pfd.close();
                            Log.d(TAG, "ExportData - file written ok");
                            return true;
                        } catch (FileNotFoundException e) {
                            e.printStackTrace();
                            // Show toast on UI thread
                            BackgroundTaskExecutor.runOnMainThread(() ->
                                mUtil.showToast(mContext.getString(R.string.error_exporting_data))
                            );
                            Log.e(TAG, "ExportData - FileNotFoundException: " + e.toString());
                            return false;
                        } catch (IOException e) {
                            e.printStackTrace();
                            // Show toast on UI thread
                            BackgroundTaskExecutor.runOnMainThread(() ->
                                mUtil.showToast(mContext.getString(R.string.error_exporting_data))
                            );
                            Log.e(TAG, "ExportData - IOException: " + e.toString());
                            return false;
                        }

                    } else {
                        Log.w(TAG, "ExportData - returned null result");
                        return false;
                    }
                } catch (SQLException e) {
                    Log.e(TAG, "ExportData: Error selecting Data: " + e.toString());
                    return false;
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "ExportData: Illegal Argument Exception: " + e.toString());
                    return false;
                } catch (NullPointerException e) {
                    Log.e(TAG, "ExportData: Null Pointer Exception: " + e.toString());
                    return false;
                }
            },
            new BackgroundTaskExecutor.Callback<Boolean>() {
                @Override
                public void onSuccess(Boolean result) {
                    Log.i(TAG, "ExportData.onSuccess() - result: " + result);
                    callback.accept(result);
                }

                @Override
                public void onError(Exception e) {
                    Log.e(TAG, "ExportData error", e);
                    callback.accept(false);
                }
            }
        );
    }

    private static int writeDatapointsToFile(Cursor c, FileOutputStream fileOutputStream) {
            Log.v(TAG, "writeDatapointsToFile()");
            int nRec = 0;
            JSONArray dataObj;
            String dataJsonStr;
            JSONObject dataJsonObj;
            JSONArray rawDataArr;
            Log.d(TAG, "writeDatapointsToFile()" + c.getColumnNames());
            //for (int i=0;i<c.getColumnCount();i++) {
            //    Log.d(TAG,"  Column"+i+" = "+c.getColumnName(i));
            //}
            try {
                Log.d(TAG, "writeDatapointsToFile() - writing query result to csv file....");
                while (c.moveToNext()) {
                    nRec += 1;
                    //Log.d(TAG,"writeDatapointsToFile - row="+c.getString(0)+", "+c.getString(1));
                    dataJsonStr = c.getString(3);   // dataJSON is index 3
                    //Log.v(TAG, "exportToFile() - i=" + i + "dataJsonStr=" + dataJsonStr);
                    dataJsonObj = new JSONObject(dataJsonStr);
                    rawDataArr = dataJsonObj.getJSONArray("rawData");
                    try {
                        //fileOutputStream.write(dataJsonObj.getString("dataTime").getBytes(StandardCharsets.UTF_8));
                        fileOutputStream.write(c.getString(1).getBytes(StandardCharsets.UTF_8));  // We use the database record date rather than datajson date because it is formatted yyyy-mm-dd
                        fileOutputStream.write(", ".getBytes(StandardCharsets.UTF_8));
                        fileOutputStream.write(dataJsonObj.getString("alarmState").getBytes(StandardCharsets.UTF_8));
                        fileOutputStream.write(", ".getBytes(StandardCharsets.UTF_8));
                        fileOutputStream.write(dataJsonObj.getString("hr").getBytes(StandardCharsets.UTF_8));
                        fileOutputStream.write(", ".getBytes(StandardCharsets.UTF_8));
                        fileOutputStream.write(dataJsonObj.getString("o2Sat").getBytes(StandardCharsets.UTF_8));
                        for (int j = 0; j < 125; j++) {  // FIXME Hard Coded array length, but rawDataArr.length() is 125*3 so we don't want to use that.
                            fileOutputStream.write(", ".getBytes(StandardCharsets.UTF_8));
                            fileOutputStream.write(rawDataArr.getString(j).getBytes(StandardCharsets.UTF_8));
                        }
                        fileOutputStream.write("\n".getBytes(StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        Log.e(TAG, "exportToFile() - ERROR Writing File: " + e.toString());
                        // Show toast on UI thread
                        new Handler(android.os.Looper.getMainLooper()).post(() ->
                            mUtil.showToast("ERROR WRITING FILE")
                        );
                        return (-1);
                    }

                }
                Log.d(TAG, "writeDatapointsToFile() - data written to file ok");
                // Show toast on UI thread
                final int finalNRec = nRec;
                new Handler(android.os.Looper.getMainLooper()).post(() ->
                    mUtil.showToast(mContext.getString(R.string.data_exported_ok) + " " + finalNRec)
                );
                return nRec;

            } catch (JSONException | NullPointerException e) {
                Log.v(TAG, "createEventCallback(): Error Creating JSON Object from string ");
                dataObj = null;
                // Show toast on UI thread
                new Handler(android.os.Looper.getMainLooper()).post(() ->
                    mUtil.showToast(mContext.getString(R.string.error_exporting_data))
                );
                Log.e(TAG, "exportToFile() - JSONException: " + e.toString());
                return (-1);
            }
        }



    private String getEventWhereClause(boolean includeWarnings) {
        /**
         * * Event statuses:
         *  *    0 - OK
         *  *    1 - WARNING
         *  *    2 - ALARM
         *  *    3 - FALL
         *  *    4 - FAULT
         *  *    5 - Manual Alarm
         *  *    6 - NDA (Normal Daily Activities)
         */
        String whereClause;
        if (includeWarnings) {
            whereClause = "Status in (?, ?, ?, ?, ?)";
        } else {
            whereClause = "Status in (?, ?, ?, ?)";
        }
        return (whereClause);
    }

    private String[] getEventWhereArgs(boolean includeWarnings) {
        /**
         * * Event statuses:
         *  *    0 - OK
         *  *    1 - WARNING
         *  *    2 - ALARM
         *  *    3 - FALL
         *  *    4 - FAULT
         *  *    5 - Manual Alarm
         *  *    6 - NDA (Normal Daily Activities)
         */
        String[] whereArgs;
        if (includeWarnings) {
            whereArgs = new String[]{"1", "2", "3", "5", "6"};
        } else {
            whereArgs = new String[]{"2", "3", "5", "6"};
        }
        return (whereArgs);
    }


    /***************************************************************************************
     * Remote Database Part
     */

    /**
     * Trigger an immediate upload attempt, typically called when network state changes
     * or after successful authentication. This bypasses the normal timer cycle.
     * Note: We don't call checkServerConnection() here to avoid unnecessary data usage -
     * the actual upload attempt will verify connectivity if there's data to upload.
     */
    public void triggerImmediateUpload() {
        Log.i(TAG, "triggerImmediateUpload() - triggered by network change or authentication");

        // Refresh the network request queue to ensure we're using current network state
        if (!USE_FIREBASE_BACKEND && mWac != null) {
            if (mWac instanceof WebApiConnection_osdapi) {
                ((WebApiConnection_osdapi) mWac).onNetworkChange();
            }
        }

        // Attempt to upload immediately (if there's data, this will verify connectivity)
        writeToRemoteServer();
    }

    public void writeToRemoteServer() {
        Log.v(TAG, "writeToRemoteServer()");
        if (!mLogRemote) {
            Log.v(TAG, "writeToRemoteServer(): mLogRemote not set, not doing anything");
            return;
        }

        if (!mLogRemoteMobile) {
            // Check network state - are we using mobile data?
            if (mUtil.isMobileDataActive()) {
                Log.v(TAG, "writeToRemoteServer(): Using mobile data, so not doing anything");
                return;
            }
        }

        if (!mUtil.isNetworkConnected()) {
            Log.v(TAG, "writeToRemoteServer(): No network connection - doing nothing");
            return;
        }

        if (mUploadInProgress) {
            Log.v(TAG, "writeToRemoteServer(): Upload already in progress, not starting another upload");
            return;
        }

        Log.d(TAG, "writeToRemoteServer(): calling UploadSdData()");
        uploadSdData();
    }

    /**
     * Handle network state changes.
     * Called when network connectivity changes (WiFi to mobile data, connected to disconnected, etc.)
     * This method attempts to restart logging/uploading when a suitable network becomes available.
     */
    public void onNetworkStateChanged() {
        Log.i(TAG, "onNetworkStateChanged() - Network state has changed, attempting to resume operations");

        if (mShutdownRequested) {
            Log.d(TAG, "onNetworkStateChanged() - Shutdown requested, not attempting any operations");
            return;
        }

        // Check if we have network connectivity
        if (!mUtil.isNetworkConnected()) {
            Log.d(TAG, "onNetworkStateChanged() - No network connection available");
            return;
        }

        // Check if we can upload (based on mLogRemoteMobile setting)
        if (!mLogRemoteMobile) {
            if (mUtil.isMobileDataActive()) {
                Log.v(TAG, "onNetworkStateChanged() - Using mobile data but mLogRemoteMobile is false");
                return;
            }
        }

        // If we reach here, we have a valid network connection
        Log.i(TAG, "onNetworkStateChanged() - Valid network connection available");

        // If upload flag is stuck from a previous failure, reset it to allow retry
        if (mUploadInProgress) {
            Log.w(TAG, "onNetworkStateChanged() - Upload flag was stuck as true, resetting it to allow retry");
            mUploadInProgress = false;
        }

        // Attempt to upload immediately
        if (mLogRemote) {
            Log.i(TAG, "onNetworkStateChanged() - Triggering immediate upload");
            writeToRemoteServer();
        }
    }


    /**
     * Upload a batch of seizure detector data records to the server..
     * Uses the webApiConnection class to upload the data in the background.
     * It searches the local database for the oldest event that has not been uploaded and uploads it.
     * eventCallback is called when the event is created.
     */
    public void uploadSdData() {
        //int eventId = -1;
        //Log.v(TAG, "uploadSdData()");
        // First try uploading full alarms, and only if we do not have any of those, upload warnings.
        //boolean warningsArr[] = {false, true};
        // Upload everything - alarms and warnings - we can sort it out in post-processing the data!
        boolean warningsArr[] = {true};
        for (int n = 0; n < warningsArr.length; n++) {
            boolean warningsVal = warningsArr[n];
            Log.i(TAG, "uploadSdData(): warningsVal=" + warningsVal);
            synchronized (mUploadLock) {
                if (mUploadInProgress) {
                    Log.d(TAG, "uploadSdData - upload already in progress - not doing anything");
                    return;
                }
                mUploadInProgress = true;
            }
            getNextEventToUpload(warningsVal, (Long eventId) -> {
                if (eventId != -1) {
                    Log.i(TAG, "uploadSdData() - next Event to Upload eventId=" + eventId);
                    String eventJsonStr = getLocalEventById(eventId);
                    Log.v(TAG, "uploadSdData() - event to upload eventJsonStr=" + eventJsonStr);
                    //int eventType;
                    JSONObject eventObj;
                    int eventAlarmStatus;
                    String eventDateStr;
                    Date eventDate;
                    String eventType;
                    String eventSubType;
                    String eventDesc;
                    String eventDataJSON;
                    //String eventAlarmCause;
                    try {
                        JSONArray datapointJsonArr = new JSONArray(eventJsonStr);
                        eventObj = datapointJsonArr.getJSONObject(0);  // We only look at the first (and hopefully only) item in the array.
                        eventAlarmStatus = Integer.parseInt(eventObj.getString("status"));
                        eventDateStr = eventObj.getString("dataTime");
                        eventType = eventObj.getString("type");
                        eventSubType = eventObj.getString("subType");
                        //if (eventObj.has("alarmCause")) {
                        //    eventAlarmCause = eventObj.getString("alarmCause");
                        //} else {
                        //    eventAlarmCause = "";
                        //}

                        // User requested to REMOVE fallback:
                        // "The alarmCause has nothing to do with subType, so do not use that as a fall back - use null or an empty string for the default."

                        // Fallback logic REMOVED.
                        // if ((eventSubType == null || eventSubType.isEmpty()) && !eventAlarmCause.isEmpty()) {
                        //    Log.i(TAG, "uploadSdData: Using alarmCause as subType for upload: " + eventAlarmCause);
                        //    eventSubType = eventAlarmCause;
                        // }

                        if (eventObj.has("desc"))
                            eventDesc = eventObj.getString("desc");
                        else
                            eventDesc = "";
                        eventDataJSON = eventObj.getString("dataJSON");
                        Log.d(TAG, "uploadSdData - data from local DB is:" + eventJsonStr + ", eventAlarmStatus="
                                + eventAlarmStatus + ", eventDateStr=" + eventDateStr);
                    } catch (JSONException e) {
                        Log.e(TAG, "uploadSdData(): ERROR parsing event JSON Data" + eventJsonStr);
                        e.printStackTrace();
                        mUploadInProgress = false;
                        return;
                    } catch (NullPointerException e) {
                        Log.e(TAG, "uploadSdData(): ERROR null pointer exception parsing event JSON Data: " + eventJsonStr);
                        e.printStackTrace();
                        mUploadInProgress = false;
                        return;
                    }
                    try {
                        eventDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(eventDateStr);
                    } catch (ParseException e) {
                        Log.e(TAG, "UploadSdData(): Error parsing date " + eventDateStr);
                        mUploadInProgress = false;
                        return;
                    }

                    Log.i(TAG, "uploadSdData - calling mWac.createEvent");
                    mCurrentEventLocalId = eventId;
                    mWac.createEvent(eventAlarmStatus, eventDate, eventType, eventSubType, eventDesc, eventDataJSON, this::createEventCallback);
                } else {
                    Log.v(TAG, "uploadSdData - no data to upload "); //(warnings="+warningsVal+")");
                    synchronized (mUploadLock) {
                        mUploadInProgress = false;
                    }
                }
            });
        }
    }


    // Mark the relevant member variables to show we are not cuurrently doing an upload, so a new one can be
    // started if necessary.
    public void finishUpload() {
        mCurrentEventRemoteId = null;
        mCurrentEventLocalId = -1;
        mCurrentDatapointId = -1;
        mDatapointsToUploadList = null;
        synchronized (mUploadLock) {
            mUploadInProgress = false;
        }
    }

    // Called by WebApiConnection when a new event record is created.
    // Once the event is created it queries the local database to find the datapoints associated with the event
    // and uploads those as a batch of data points.
    public void createEventCallback(String eventId) {
        Log.v(TAG, "createEventCallback(): " + eventId);
        if (mShutdownRequested) {
            Log.v(TAG, "createEventCallback(): Shutdown requested, ignoring callback");
            finishUpload();
            return;
        }
        Log.v(TAG, "createEventCallback(): Retrieving remote event details");
        mWac.getEvent(eventId, new WebApiConnection.JSONObjectCallback() {
            @Override
            public void accept(JSONObject eventObj) {
                if (mShutdownRequested) {
                    Log.v(TAG, "createEventCallback.accept(): Shutdown requested, ignoring callback");
                    finishUpload();
                    return;
                }
                if (eventObj == null) {
                    Log.e(TAG, "createEventCallback() - eventObj is null - failed to create event (network error?)");
                    //showToastSafe(mContext.getString(R.string.error_creating_remote_event_msg));
                    Log.i(TAG, "createEventCallback() - Resetting upload flag to allow retry on next network connection");
                    finishUpload();
                } else {
                    Log.v(TAG, "createEventCallback() - eventObj=" + eventObj.toString());
                    Date eventDate;
                    String eventDateStr = "";
                    try {
                        String dateStr = eventObj.getString("dataTime");
                        eventDate = mUtil.string2date(dateStr);
                    } catch (JSONException | NullPointerException e) {
                        Log.e(TAG, "createEventCallback() - Error parsing JSONObject: " + eventObj.toString());
                        finishUpload();
                        return;
                    }
                    if (eventDate != null) {
                        Log.v(TAG, "createEventCallback() EventId=" + eventId + ", eventDateStr=" + eventDateStr + ", eventDate=" + eventDate);
                        mUploadInProgress = true;
                        long eventDateMillis = eventDate.getTime();
                        long startDateMillis = eventDateMillis - 1000 * mEventDuration / 2;
                        long endDateMillis = eventDateMillis + 1000 * mEventDuration / 2;
                        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

                        getDatapointsByDate(
                                dateFormat.format(new Date(startDateMillis)),
                                dateFormat.format(new Date(endDateMillis)),
                                (String datapointsJsonStr) -> {
                                    if (mShutdownRequested) {
                                        Log.v(TAG, "createEventCallback.getDatapointsByDate(): Shutdown requested, ignoring callback");
                                        finishUpload();
                                        return;
                                    }
                                    //Log.v(TAG, "createEventCallback() - datapointsJsonStr=" + datapointsJsonStr);
                                    JSONArray dataObj;
                                    mDatapointsToUploadList = new ArrayList<JSONObject>();

                                    try {
                                        //DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
                                        dataObj = new JSONArray(datapointsJsonStr);
                                        Log.v(TAG, "createEventCallback() - datapointsObj length=" + dataObj.length());
                                        for (int i = 0; i < dataObj.length(); i++) {
                                            mDatapointsToUploadList.add(dataObj.getJSONObject(i));
                                        }
                                    } catch (JSONException |
                                             NullPointerException e) {
                                        Log.v(TAG, "createEventCallback(): Error Creating JSON Object from string " + datapointsJsonStr);
                                        dataObj = null;
                                        finishUpload();
                                    }
                                    // This starts the process of uploading the datapoints, one at a time.
                                    mCurrentEventRemoteId = eventId;
                                    int listLen = 0;
                                    if (mDatapointsToUploadList != null)
                                        listLen = mDatapointsToUploadList.size();
                                    Log.v(TAG, "createEventCallback() - starting datapoints upload with eventId " + mCurrentEventRemoteId +
                                            " Uploading " + listLen + " datapoints");
                                    uploadNextDatapoint();

                                });
                    } else {
                        Log.e(TAG, "createEventCallback() - Error - event date is null - not doing anything");
                        showToastSafe(mContext.getString(R.string.error_uploading_event_msg));
                        finishUpload();
                    }
                }
            }
        });
    }

    // takes the next datapoint of the list mDatapointsToUploadList and uploads it to the remote server.
    // datapointCallback is called when the upload is complete.
    public void uploadNextDatapoint() {
        //Log.v(TAG, "uploadNextDatapoint()");
        if (mShutdownRequested) {
            Log.v(TAG, "uploadNextDatapoint(): Shutdown requested, aborting upload");
            finishUpload();
            return;
        }
        if (mDatapointsToUploadList != null) {
            if (mDatapointsToUploadList.size() > 0) {
                mUploadInProgress = true;
                try {
                    mCurrentDatapointId = mDatapointsToUploadList.get(0).getInt("id");
                } catch (JSONException | NullPointerException e) {
                    Log.e(TAG, "uploadNextDatapoint(): Error reading currentDatapointID from mDatapointsToUploadList[0]" + e.getMessage());
                    Log.e(TAG, "uploadNextDatapoint(): Removing mDatapointsToUploadList[0] and trying the next datapoint");
                    mDatapointsToUploadList.remove(0);
                    uploadNextDatapoint();
                    return;
                }

                Log.v(TAG, "uploadNextDatapoint() - " + mDatapointsToUploadList.size() + " datapoints to upload.  Uploading datapoint ID:" + mCurrentDatapointId);
                mWac.createDatapoint(mDatapointsToUploadList.get(0), mCurrentEventRemoteId, this::datapointCallback);

            } else {
                Log.i(TAG, "uploadNextDatapoint() - All datapoints uploaded!");
                setEventToUploaded(mCurrentEventLocalId, mCurrentEventRemoteId);
                finishUpload();
            }
        } else {
            Log.w(TAG, "uploadNextDatapoint - mDatapointsToUploadList is null - I don't thin this should have happened!");
        }
    }

    // Called by WebApiConnection when a new datapoint is created.   It assumes that we have just created
    // a datapoint based on mDatapointsToUploadList(0) so removes that from the list and calls UploadDatapoint()
    // to upload the next one.
    public void datapointCallback(String datapointStr) {
        Log.v(TAG, "datapointCallback() dataPointId=" + mCurrentDatapointId + " remote datapointID=" + datapointStr + ", mCurrentEventId=" + mCurrentEventRemoteId);
        if (mShutdownRequested) {
            Log.v(TAG, "datapointCallback(): Shutdown requested, aborting upload");
            finishUpload();
            return;
        }
        if (mDatapointsToUploadList != null) {
            if (mDatapointsToUploadList.size() > 0) {
                mDatapointsToUploadList.remove(0);
            }
        } else {
            Log.w(TAG, "datapointCallback - mDatapointsToUploadList is null - I don't thin this should have happened!");
        }
        setDatapointToUploaded(mCurrentDatapointId, mCurrentEventRemoteId);
        uploadNextDatapoint();
    }


    /**
     * close() - shut down the logging system
     * WARNING - this should only be called by the final destructor of the app (not individual class destructors)
     * because it will close the DB for all instances of LogManger, not just the one on which it is called.
     * FIXME:  If I was keen I would keep a count of how many instances of LogManager there are, and have this function do nothing
     * unless it was the last instance.
     */
    public static void close() {
        mOsdDb.close();
        mOsdDb = null;
        if (mWac != null) {
            Log.i(TAG, "Stopping Remote Database Interface");
            mWac.close();
        }
    }

    public void stop() {
        // Stop the timers and shutdown the remote API connection.
        Log.i(TAG, "stop() - initiating shutdown");
        mShutdownRequested = true;

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

        // Wait for ongoing upload to complete or timeout
        if (mUploadInProgress) {
            Log.i(TAG, "stop() - waiting for upload to complete");
            int waitCount = 0;
            while (mUploadInProgress && waitCount < 50) { // 5 second max wait
                try {
                    Thread.sleep(100);
                    waitCount++;
                } catch (InterruptedException e) {
                    Log.w(TAG, "stop() - interrupted while waiting for upload");
                    break;
                }
            }

            if (mUploadInProgress) {
                Log.w(TAG, "stop() - forcing upload termination after timeout");
                finishUpload();
            }
        }

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
