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
package uk.org.openseizuredetector;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.AsyncTask;
import android.os.CountDownTimer;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.text.format.Time;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
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
 *
 * Event statuses:
 *    0 - OK
 *    1 - WARNING
 *    2 - ALARM
 *    3 - FALL
 *    4 - FAULT
 *    5 - Manual Alarm
 *    6 - NDA (Normal Daily Activities)
 *
 *    NDA Timer creates an event periodically to record Normal Daily Activities (NDA),
 *    irrespective of the alarm state.   This will upload a lot of data, so it will only run
 *    for 24 hours after being activated before shutting down requring the user to re-select
 *    the option to log NDA to re-start it.
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
    public boolean mLogNDA;
    public NDATimer mNDATimer;
    private long mNDATimerStartTime;  // milliseconds
    public double mNDATimeRemaining; // hours
    public double mNDALogPeriodHours = 24.0;  // hours
    private static Context mContext;
    private OsdUtil mUtil;
    public static WebApiConnection mWac;
    public static final boolean USE_FIREBASE_BACKEND = false;

    private boolean mUploadInProgress;
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

    public interface CursorCallback {
        void accept(Cursor retVal);
    }

    public interface ArrayListCallback {
        void accept(ArrayList<HashMap<String, String>> retVal);
    }

    public LogManager(Context context,
                      boolean logRemote, boolean logRemoteMobile, String authToken,
                      long eventDuration, long remoteLogPeriod,
                      boolean logNDA,
                      boolean autoPruneDb, long dataRetentionPeriod,
                      SdData sdSettingsData) {
        Log.d(TAG, "LogManger Constructor");
        mContext = context;
        Handler handler = new Handler();

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
        openDb();
        Log.i(TAG, "Starting Remote Database Interface");
        if (USE_FIREBASE_BACKEND) {
            mWac = new WebApiConnection_firebase(mContext);
        } else {
            mWac = new WebApiConnection_osdapi(mContext);
        }

        mWac.setStoredToken(mAuthToken);

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
    }

    /**
     * Returns a JSON String representing an array of events that are selected from sqlite cursor c.
     *
     * @param c sqlite cursor pointing to events query result.
     * @return JSON String.
     * from https://stackoverflow.com/a/20488153/2104584
     */
    private String eventCursor2Json(Cursor c) {
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
                event.put("id", val==null ? "" : val );
                val = c.getString(c.getColumnIndex("dataTime"));
                event.put("dataTime", val==null ? "" : val);
                val = c.getString(c.getColumnIndex("status"));
                event.put("status", val==null ? "" : val);
                val = c.getString(c.getColumnIndex("type"));
                event.put("type", val==null ? "" : val);
                val = c.getString(c.getColumnIndex("subType"));
                event.put("subType", val==null ? "" : val);
                val = c.getString(c.getColumnIndex("notes"));
                event.put("desc", val==null ? "" : val);
                val = c.getString(c.getColumnIndex("dataJSON"));
                event.put("dataJSON", val==null ? "" : val);
                val = c.getString(c.getColumnIndex("uploaded"));
                event.put("uploaded", val==null ? "" : val);
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
    }


    private static boolean openDb() {
        Log.d(TAG, "openDb");
        try {
            if (mOsdDb == null) {
                Log.i(TAG, "openDb: mOsdDb is null - initialising");
                mOsdDb = new OsdDbHelper(mContext).getWritableDatabase();
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
     * Write data to local database
     * FIXME - I am sure we should not be using raw SQL Srings to do this!
     */
    public void writeDatapointToLocalDb(SdData sdData) {
        //Log.v(TAG, "writeDatapointToLocalDb()");
        Date curDate = new Date();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        String dateStr = dateFormat.format(curDate);
        String SQLStr = "SQLStr";

        if (mOsdDb == null) {
            Log.e(TAG, "writeDatapointToLocalDb(): mOsdDb is null - doing nothing");
            return;
        }
        try {
            // Write Datapoint to database
            SQLStr = "INSERT INTO " + mDpTableName
                    + "(dataTime, status, dataJSON, uploaded)"
                    + " VALUES("
                    + "'" + dateStr + "',"
                    + sdData.alarmState + ","
                    + DatabaseUtils.sqlEscapeString(sdData.toDatapointJSON()) + ","
                    + 0
                    + ")";
            mOsdDb.execSQL(SQLStr);
            Log.v(TAG, "writeDatapointToLocalDb(): datapoint written to database");

            if (sdData.alarmState != 0) {
                Log.d(TAG, "writeDatapointToLocalDb(): adding event to local DB");
                createLocalEvent(dateStr,sdData.alarmState,null, null, null, sdData.toSettingsJSON());
            }
        } catch (SQLException e) {
            Log.e(TAG, "writeToLocalDb(): Error Writing Data: " + e.toString());
            Log.e(TAG, "SQLStr was " + SQLStr);
        } catch (NullPointerException e) {
            Log.e(TAG, "writeToLocalDb(): Null Pointer Exception: " + e.toString());
        }
    }

    public boolean createLocalEvent(String dataTime, long status) {
        return (createLocalEvent(dataTime, status, null, null, null, null));
    }

    public boolean createLocalEvent(String dataTime, long status, String type, String subType, String desc, String dataJSON) {
        // Expects dataTime to be in format: SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        Log.d(TAG, "createLocalEvent() - dataTime=" + dataTime + ", status=" + status + ", dataJSON="+dataJSON);
        // Write Event to database
        ContentValues values = new ContentValues();
        values.put("dataTime", dataTime);
        values.put("status", status);
        values.put("type", type);
        values.put("subType",subType);
        values.put("notes",desc);
        values.put("dataJSON", dataJSON);

        long newRowId = mOsdDb.insert(mEventsTableName, null, values);
        Log.d(TAG, "createLocalEvent(): Created Row ID"+newRowId);
        return true;
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
        new SelectQueryTask(mDpTableName, columns, whereClause, whereArgs,
                null, null, "dataTime DESC", (Cursor cursor) -> {
            Log.v(TAG, "getDataPointsByDate - returned " + cursor);
            if (cursor != null) {
                callback.accept(cursor2Json(cursor));
            } else {
                callback.accept(null);
            }
        }).execute();
        return (true);
    }


    /**
     * Return an array list of objects representing the events in the database by calling the specified callback function.
     *
     * @param includeWarnings - whether to include warnings in the list of events, or just alarm conditions.
     * @return True on successful start or false if call fails.
     */
    public boolean getEventsList(boolean includeWarnings, ArrayListCallback callback) {
        Log.d(TAG, "getEventsList - includeWarnings=" + includeWarnings);
        ArrayList<HashMap<String, String>> eventsList = new ArrayList<>();

        String[] whereArgs = getEventWhereArgs(includeWarnings);
        String whereClause = getEventWhereClause(includeWarnings);
        //sqlStr = "SELECT * from " + mDbTableName + " where Status in (" + statusListStr + ") order by dataTime desc;";
        String[] columns = {"*"};
        new SelectQueryTask(mEventsTableName, columns, whereClause, whereArgs,
                null, null, "dataTime DESC", (Cursor cursor) -> {
            Log.v(TAG, "getEventsList - returned " + cursor);
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
                    //event.put("dataJSON", cursor.getString(cursor.getColumnIndex("dataJSON")));
                    eventsList.add(event);
                    cursor.moveToNext();
                }
            }
            callback.accept(eventsList);
        }).execute();
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
                Log.e(TAG, "Error deleting data " + e.toString());
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
    public boolean getNextEventToUpload(boolean includeWarnings, WebApiConnection.LongCallback callback) {
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
        new SelectQueryTask(mEventsTableName, columns, whereClause, whereArgs,
                null, null, "dataTime DESC", (Cursor cursor) -> {
            Long recordId = new Long(-1);
            if (cursor != null) {
                Log.v(TAG, "getNextEventToUpload - returned " + cursor.getCount() + " records");
                cursor.moveToFirst();
                if (cursor.getCount() == 0) {
                    Log.d(TAG, "getNextEventToUpload() - no events to Upload - exiting");
                    recordId = new Long(-1);
                } else {
                    recordId = cursor.getLong(0);
                    Log.d(TAG, "getNextEventToUpload(): id=" + recordId);
                }
            }
            callback.accept(recordId);
        }).execute();
        return (true);
    }


    /**
     * Return the ID of the datapoint that is closest to date/time string dateStr
     * Based on https://stackoverflow.com/questions/45749046/sql-get-nearest-date-record
     *
     * @return True on successful start or false if call fails.
     */
    public boolean getNearestDatapointToDate(String dateStr, WebApiConnection.LongCallback callback) {
        Log.v(TAG, "getNextEventToDate - dateStr=" + dateStr);
        String[] columns = {"*", "(julianday(dataTime)-julianday(datetime('" + dateStr + "'))) as ddiff"};
        //SQLStr = "SELECT *, (julianday(dataTime)-julianday(datetime('" + dateStr + "'))) as ddiff from " + mDbTableName + " order by ABS(ddiff) asc;";
        String orderByStr = "ABS(ddiff) asc";
        new SelectQueryTask(mDpTableName, columns, null, null,
                null, null, orderByStr, (Cursor cursor) -> {
            Log.v(TAG, "getEventsNearestDatapointToDate - returned " + cursor);
            Long recordId = new Long(-1);
            if (cursor != null) {
                Log.v(TAG, "getNearestDatapointToDate - returned " + cursor.getCount() + " records");
                cursor.moveToFirst();
                if (cursor.getCount() == 0) {
                    Log.d(TAG, "getNearestDatapointToDate() - no events to Upload - exiting");
                    recordId = new Long(-1);
                } else {
                    String recordStr = cursor.getString(3);
                    recordId = cursor.getLong(0);
                    Log.d(TAG, "getNearestDatapointToDate(): id=" + recordId + ", recordStr=" + recordStr);
                }
            }
            callback.accept(recordId);
        }).execute();
        return (true);
    }


    /**
     * Return the number of events stored in the local database (via a callback).
     *
     * @param includeWarnings - whether to include warnings in the list of events, or just alarm conditions.
     * @return True on successful start or false if call fails.
     */
    public boolean getLocalEventsCount(boolean includeWarnings, WebApiConnection.LongCallback callback) {
        //Log.v(TAG, "getLocalEventsCount- includeWarnings=" + includeWarnings);
        String[] whereArgs = getEventWhereArgs(includeWarnings);
        String whereClause = getEventWhereClause(includeWarnings);
        String[] columns = {"*"};
        new SelectQueryTask(mEventsTableName, columns, whereClause, whereArgs,
                null, null, null, (Cursor cursor) -> {
            //Log.v(TAG, "getLocalEventsCount - returned " + cursor);
            Long eventCount = Long.valueOf(0);
            if (cursor != null) {
                eventCount = Long.valueOf(cursor.getCount());
                Log.v(TAG, "getLocalEventsCount - returned " + eventCount + " records");
            }
            callback.accept(eventCount);
        }).execute();
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
        new SelectQueryTask(mDpTableName, columns, whereClause, whereArgs,
                null, null, null, (Cursor cursor) -> {
            //Log.v(TAG, "getLocalDatapointsCount - returned " + cursor);
            Long eventCount = Long.valueOf(0);
            if (cursor != null) {
                eventCount = Long.valueOf(cursor.getCount());
                Log.v(TAG, "getLocalDatapointsCount - returned " + eventCount + " records");
            }
            callback.accept(eventCount);
        }).execute();
        return (true);
    }


    /**
     * Executes the sqlite query (=SELECT statement)
     * Use as new SelectQueryTask(xxx,xxx,xx,xxxx).execute()
     */
    static private class SelectQueryTask extends AsyncTask<Void, Void, Cursor> {
        // Based on https://stackoverflow.com/a/21120199/2104584
        String mTable;
        String[] mColumns;
        String mSelection;
        String[] mSelectionArgs;
        String mGroupBy;
        String mHaving;
        String mOrderBy;
        CursorCallback mCallback;

        //query(String table, String[] columns, String selection, String[] selectionArgs,
        // String groupBy, String having, String orderBy)
        SelectQueryTask(String table, String[] columns, String selection, String[] selectionArgs,
                        String groupBy, String having, String orderBy, CursorCallback callback) {
            // list all the parameters like in normal class define
            this.mTable = table;
            this.mColumns = columns;
            this.mSelection = selection;
            this.mSelectionArgs = selectionArgs;
            this.mGroupBy = groupBy;
            this.mHaving = having;
            this.mOrderBy = orderBy;
            this.mCallback = callback;

        }

        @Override
        protected Cursor doInBackground(Void... params) {
            //Log.v(TAG, "runSelect.doInBackground()");
            Log.v(TAG, "SelectQueryTask.doInBackground: mTable=" + mTable + ", mColumns=" + Arrays.toString(mColumns)
                    + ", mSelection=" + mSelection + ", mSelectionArgs=" + Arrays.toString(mSelectionArgs) + ", mGroupBy=" + mGroupBy
                    + ", mHaving =" + mHaving + ", mOrderBy=" + mOrderBy);

            try {
                Cursor resultSet = mOsdDb.query(mTable, mColumns, mSelection,
                        mSelectionArgs, mGroupBy, mHaving, mOrderBy);
                resultSet.moveToFirst();
                return (resultSet);
            } catch (SQLException e) {
                Log.e(TAG, "SelectQueryTask.doInBackground(): Error selecting Data: " + e.toString());
                return (null);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "SelectQueryTask.doInBackground(): Illegal Argument Exception: " + e.toString());
                return (null);
            } catch (NullPointerException e) {
                Log.e(TAG, "SelectQueryTask.doInBackground(): Null Pointer Exception: " + e.toString());
                return (null);
            }
        }

        @Override
        protected void onPostExecute(final Cursor result) {
            mCallback.accept(result);
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
            if (mUploadInProgress) {
                Log.d(TAG, "uploadSdData - upload already in progress - not doing anything");
                return;
            }
            mUploadInProgress = true;
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
                    try {
                        JSONArray datapointJsonArr = new JSONArray(eventJsonStr);
                        eventObj = datapointJsonArr.getJSONObject(0);  // We only look at the first (and hopefully only) item in the array.
                        eventAlarmStatus = Integer.parseInt(eventObj.getString("status"));
                        eventDateStr = eventObj.getString("dataTime");
                        eventType = eventObj.getString("type");
                        eventSubType = eventObj.getString("subType");
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
                    mUploadInProgress = false;
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
        mUploadInProgress = false;
    }

    // Called by WebApiConnection when a new event record is created.
    // Once the event is created it queries the local database to find the datapoints associated with the event
    // and uploads those as a batch of data points.
    public void createEventCallback(String eventId) {
        Log.v(TAG, "createEventCallback(): " + eventId);
        Log.v(TAG, "createEventCallback(): Retrieving remote event details");
        mWac.getEvent(eventId, new WebApiConnection.JSONObjectCallback() {
            @Override
            public void accept(JSONObject eventObj) {
                if (eventObj == null) {
                    Log.e(TAG, "createEventCallback() - eventObj is null - failed to create event");
                    mUtil.showToast("Error Creating Remote Event");
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
                                    } catch (JSONException | NullPointerException e) {
                                        Log.v(TAG, "createEventCallback(): Error Creating JSON Object from string " + datapointsJsonStr);
                                        dataObj = null;
                                        finishUpload();
                                    }
                                    // This starts the process of uploading the datapoints, one at a time.
                                    mCurrentEventRemoteId = eventId;
                                    Log.v(TAG, "createEventCallback() - starting datapoints upload with eventId " + mCurrentEventRemoteId +
                                            " Uploading " + mDatapointsToUploadList.size() + " datapoints");
                                    uploadNextDatapoint();

                                });
                    } else {
                        Log.e(TAG, "createEventCallback() - Error - event date is null - not doing anything");
                        mUtil.showToast("Error uploading event - date is null");
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
        stopRemoteLogTimer();
        stopAutoPruneTimer();
        stopNDATimer();
    }

    /*
     * Start the timer that will upload data to the remote server after a given period.
     */
    private void startRemoteLogTimer() {
        if (mRemoteLogTimer != null) {
            Log.i(TAG, "startRemoteLogTimer -timer already running - cancelling it");
            mRemoteLogTimer.cancel();
            mRemoteLogTimer = null;
        }
        Log.i(TAG, "startRemoteLogTimer() - starting RemoteLogTimer");
        mRemoteLogTimer =
                new RemoteLogTimer(mRemoteLogPeriod * 1000, 1000);
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
        mLogNDA = true;

        // If we do not have a stored start time for NDA logging, set it to current time
        // and store it.
        SharedPreferences SP = PreferenceManager
                .getDefaultSharedPreferences(mContext);
        mNDATimerStartTime = SP.getLong("NDATimerStartTime", 0);
        if (mNDATimerStartTime == 0) {
            Time timeNow = new Time(Time.getCurrentTimezone());
            timeNow.setToNow();
            mNDATimerStartTime = timeNow.toMillis(true);
            SharedPreferences.Editor editor = SP.edit();
            editor.putLong("NDATimerStartTime", mNDATimerStartTime);
            editor.putBoolean("LogNDA", mLogNDA);
            editor.apply();
        }
        Time timeNow = new Time(Time.getCurrentTimezone());
        timeNow.setToNow();
        long tNow = timeNow.toMillis(true);
        long tDiffMillis = (tNow - mNDATimerStartTime);
        mNDATimeRemaining = mNDALogPeriodHours - tDiffMillis / (3600.*1000.);
    }

    /*
     * Cancel the Normal Daily Actity Log timer
     */
    public void stopNDATimer() {
        if (mNDATimer != null) {
            Log.i(TAG, "stopNDATimer(): cancelling Normal Daily Activity timer");
            mNDATimer.cancel();
            mNDATimer = null;
            mLogNDA = false;
        }
    }

    public void disableNDATimer() {
        stopNDATimer();
        SharedPreferences SP = PreferenceManager
                .getDefaultSharedPreferences(mContext);
        SharedPreferences.Editor editor = SP.edit();
        editor.putBoolean("LogNDA", false);
        editor.apply();
    }

    public void enableNDATimer() {
        //startNDATimer();
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
        Log.i(TAG,"createNDAEvent()");
        Date curDate = new Date();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String dateStr = dateFormat.format(curDate);
        createLocalEvent(dateStr,6,"nda", null, null,
                mSdSettingsData.toSettingsJSON());
    }

    public void updateSdData(SdData sdData) {
        mSdSettingsData = sdData;
    }



    public static class OsdDbHelper extends SQLiteOpenHelper {
        // If you change the database schema, you must increment the database version.
        public static final int DATABASE_VERSION = 1;
        public static final String DATABASE_NAME = "OsdData.db";
        private static final String TAG = "LogManager.OsdDbHelper";

        public OsdDbHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
            Log.d(TAG, "OsdDbHelper constructor");
        }

        public void onCreate(SQLiteDatabase db) {
            Log.i(TAG, "onCreate - TableName=" + mDpTableName);
            String SQLStr = "CREATE TABLE IF NOT EXISTS " + mDpTableName + "("
                    + "id INTEGER PRIMARY KEY,"
                    + "dataTime DATETIME,"
                    + "status INT,"
                    + "dataJSON TEXT,"
                    + "uploaded TEXT"  // Stores the ID of the datapoint in the remote database if uploaded, otherwise empty
                    + ");";
            db.execSQL(SQLStr);
            Log.i(TAG, "onCreate - TableName=" + mEventsTableName);
            SQLStr = "CREATE TABLE IF NOT EXISTS " + mEventsTableName + "("
                    + "id INTEGER PRIMARY KEY,"
                    + "dataTime DATETIME,"
                    + "status INT,"
                    + "type TEXT,"
                    + "subType TEXT,"
                    + "notes TEXT,"    // avoiding using 'desc' as that is an sql name.
                    + "dataJSON TEXT,"
                    + "uploaded TEXT"  // stores the id of the event in the remote dabase if uploaded, otherwise empty
                    + ");";
            db.execSQL(SQLStr);
        }

        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // This database is only a cache for online data, so its upgrade policy is
            // to simply to discard the data and start over
            Log.i(TAG, "onUpgrade()");
            db.execSQL("Drop table if exists " + mDpTableName + ";");
            onCreate(db);
        }

        public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.i(TAG, "onDowngrade()");
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
            Log.d(TAG, "mRemoteLogTimer - onFinish - uploading data to remote database");
            writeToRemoteServer();
            // Restart this timer.
            start();
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
            Log.d(TAG, "mNDATimer - onFinish - Recording a Normal Daily Activity Event");
            createNDAEvent();
            // Check if we have been logging NDA events for more than the set limit.  If it has, we disable it
            // and set the start time to zero so it is re-set next time NDA logging is enabled.
            Time timeNow = new Time(Time.getCurrentTimezone());
            timeNow.setToNow();
            long tNow = timeNow.toMillis(true);
            long tDiffMillis = (tNow - mNDATimerStartTime);
            double tDiffHrs = tDiffMillis / (3600.*1000.);
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
                Log.i(TAG,"NDATimer - tDiffMillis="+tDiffMillis+", tdiffHrs = "+tDiffHrs+ ", tnow="+tNow+", tstart="+mNDATimerStartTime+", NDALogPeriod="+mNDALogPeriodHours);
                Log.i(TAG,"NDATimer - re-starting NDA timer");
                start();
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
            Log.d(TAG, "mAutoPruneTimer - onFinish - Pruning Local Database");
            pruneLocalDb();
            // Restart this timer.
            start();
        }

    }


}
