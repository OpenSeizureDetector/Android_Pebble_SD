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
import java.util.function.Consumer;

import static android.database.sqlite.SQLiteDatabase.openOrCreateDatabase;

/**
 * LogManager is a class to handle all aspects of Data Logging within OpenSeizureDetector.
 * It performs several functions:
 * - It will store seizure detector data to a local database on demand (it is called by the SdServer background service)
 * - <FIXME: Not Yet Implemented> It will retrieve data from the local database for display on the user interface
 * - <FIXME: Not Yet Implemented> It will periodically trim the local database to retain only a specified number of days worth of data
 * to avoid the local storage use increasing continuously.
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
 */
public class LogManager implements AuthCallbackInterface, EventCallbackInterface, DatapointCallbackInterface {
    private String TAG = "LogManager";
    private String mDbName = "osdData";
    private String mDbTableName = "datapoints";
    private boolean mLogRemote;
    private boolean mLogRemoteMobile;
    private OsdDbHelper mOSDDb;
    private RemoteLogTimer mRemoteLogTimer;
    private Context mContext;
    private OsdUtil mUtil;
    public WebApiConnection mWac;

    private boolean mUploadInProgress;
    private long mEventDuration = 120;   // event duration in seconds - uploads datapoints that cover this time range centred on the event time.
    public long mDataRetentionPeriod = 1; // Prunes the local db so it only retains data younger than this duration (in days)
    private long mRemoteLogPeriod = 60; // Period in seconds between uploads to the remote server.
    private ArrayList<JSONObject> mDatapointsToUploadList;
    private int mCurrentEventId;
    private int mCurrentDatapointId;
    private long mAutoPrunePeriod = 3600;  // Prune the database every hour
    private boolean mAutoPruneDb;
    private AutoPruneTimer mAutoPruneTimer;


    public LogManager(Context context) {
        String prefVal;
        Log.d(TAG, "LogManger Constructor");
        mContext = context;
        Handler handler = new Handler();

        SharedPreferences prefs;
        prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        mLogRemote = (prefs.getBoolean("LogDataRemote", false));
        Log.v(TAG, "mLogRemote=" + mLogRemote);
        mLogRemoteMobile = (prefs.getBoolean("LogDataRemoteMobile", false));
        Log.v(TAG, "mLogRemoteMobile=" + mLogRemoteMobile);

        prefVal = prefs.getString("EventDurationSec", "300");
        mEventDuration = Integer.parseInt(prefVal);
        Log.v(TAG, "mEventDuration=" + mEventDuration);

        mAutoPruneDb = prefs.getBoolean("AutoPruneDb", false);
        Log.v(TAG, "mAutoPruneDb=" + mAutoPruneDb);

        prefVal = prefs.getString("DataRetentionPeriod", "28");
        mDataRetentionPeriod = Integer.parseInt(prefVal);
        Log.v(TAG, "mDataRetentionPeriod=" + mDataRetentionPeriod);

        prefVal = prefs.getString("RemoteLogPeriod", "60");
        mRemoteLogPeriod = Integer.parseInt(prefVal);
        Log.v(TAG, "mRemoteLogPeriod=" + mRemoteLogPeriod);


        mUtil = new OsdUtil(mContext, handler);
        openDb();
        mWac = new WebApiConnection(mContext, this, this, this);

        startRemoteLogTimer();

        if (mAutoPruneDb) {
            Log.v(TAG, "Starting Auto Prune Timer");
            startAutoPruneTimer();
        } else {
            Log.v(TAG, "AutoPruneDB is not set");
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
        String retVal = "";
        c.moveToFirst();
        //JSONObject Root = new JSONObject();
        JSONArray dataPointArray = new JSONArray();
        int i = 0;
        while (!c.isAfterLast()) {
            JSONObject datapoint = new JSONObject();
            try {
                datapoint.put("id", c.getString(c.getColumnIndex("id")));
                datapoint.put("dataTime", c.getString(c.getColumnIndex("dataTime")));
                datapoint.put("status", c.getString(c.getColumnIndex("Status")));
                datapoint.put("dataJSON", c.getString(c.getColumnIndex("dataJSON")));
                datapoint.put("uploaded", c.getString(c.getColumnIndex("uploaded")));
                //Log.v(TAG,"cursor2json() - datapoint="+datapoint.toString());
                c.moveToNext();
                dataPointArray.put(i, datapoint);
                i++;
            } catch (JSONException e) {
                Log.e(TAG, "cursor2Json(): error creating JSON Object");
                e.printStackTrace();
            }
        }
        return dataPointArray.toString();
    }


    private boolean openDb() {
        Log.d(TAG, "openDb");
        try {
            mOSDDb = new OsdDbHelper(mDbTableName, mContext);
            if (!checkTableExists(mOSDDb, mDbTableName)) {
                Log.e(TAG, "ERROR - Table does not exist");
                return false;
            } else {
                Log.d(TAG, "table " + mDbTableName + " exists ok");
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
        Log.d(TAG, "checkTableExists()");
        try {
            c = osdDb.getWritableDatabase().query(osdTableName, null,
                    null, null, null, null, null);
            tableExists = true;
        } catch (Exception e) {
            Log.d(TAG, osdTableName + " doesn't exist :(((");
        }
        return tableExists;
    }


    /**
     * Write data to local database
     * FIXME - I am sure we should not be using raw SQL Srings to do this!
     */
    public void writeToLocalDb(SdData sdData) {
        Log.v(TAG, "writeToLocalDb()");
        Date curDate = new Date();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        String dateStr = dateFormat.format(curDate);
        String SQLStr = "SQLStr";

        try {
            double roiRatio = -1;
            if (sdData.specPower != 0)
                roiRatio = 10. * sdData.roiPower / sdData.specPower;
            SQLStr = "INSERT INTO " + mDbTableName
                    + "(dataTime, status, dataJSON, uploaded)"
                    + " VALUES("
                    + "'" + dateStr + "',"
                    + sdData.alarmState + ","
                    + DatabaseUtils.sqlEscapeString(sdData.toJSON(true)) + ","
                    + 0
                    + ")";
            mOSDDb.getWritableDatabase().execSQL(SQLStr);
            Log.d(TAG, "data written to database");

        } catch (SQLException e) {
            Log.e(TAG, "writeToLocalDb(): Error Writing Data: " + e.toString());
            Log.e(TAG, "SQLStr was " + SQLStr);
        }

    }

    /**
     * Returns a json representation of datapoint 'id'.
     *
     * @param id datapoint id to return
     * @return JSON representation of requested datapoint (single element JSON array)
     */
    public String getDatapointById(long id) {
        Log.d(TAG, "getDatapointById() - id=" + id);
        Cursor c = null;
        String retVal;
        try {
            String selectStr = "select * from " + mDbTableName + " where id=" + id + ";";
            String[] selectArgs = new String[]{String.format("%d", id)};
            //c = mOSDDb.getWritableDatabase().query(mDbTableName, null,
            //        selectStr, selectArgs, null, null, null);
            c = mOSDDb.getWritableDatabase().rawQuery(selectStr, null);
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
    public boolean setDatapointToUploaded(int id, int eventId) {
        Log.d(TAG, "setDatapointToUploaded() - id=" + id);
        Cursor c = null;
        ContentValues cv = new ContentValues();
        cv.put("uploaded", eventId);
        int nRowsUpdated = mOSDDb.getWritableDatabase().update(mDbTableName, cv, "id = ?",
                new String[]{String.format("%d", id)});

        return (nRowsUpdated == 1);

    }

    /**
     * setDatapointStatus() - Update the status of data point id.
     *
     * @param id
     * @param statusVal
     * @return true on success or false on failure
     */
    public boolean setDatapointStatus(int id, int statusVal) {
        Log.d(TAG, "setDatapointStatus() - id=" + id + ", statusVal=" + statusVal);
        Cursor c = null;
        ContentValues cv = new ContentValues();
        cv.put("status", statusVal);
        int nRowsUpdated = mOSDDb.getWritableDatabase().update(mDbTableName, cv, "id = ?",
                new String[]{String.format("%d", id)});

        return (nRowsUpdated == 1);

    }


    /**
     * getDatapointsJSON() Returns a JSON Object of all of the datapoints in the local database
     * between endDateStr-duration and endDateStr
     *
     * @param endDateStr String representation of the period end date
     * @param duration   Duration in minutes.
     * @return JSONObject of all the datapoints in the range.
     */
    public String getDatapointsbyDate(String startDateStr, String endDateStr) {
        Log.d(TAG, "getDatapointsbyDate() - startDateStr=" + startDateStr + ", endDateStr=" + endDateStr);
        Cursor c = null;
        String retVal;
        try {
            //String selectStr = "DataTime>=? and DataTime<=?";
            //String[] selectArgs = {startDateStr, endDateStr};
            //c = mOSDDb.getWritableDatabase().query(mDbTableName, null,
            //        null, null, null, null, null);
            c = mOSDDb.getWritableDatabase().rawQuery(
                    "Select * from " + mDbTableName
                            + " where dataTime>= '" + startDateStr
                            + "' and dataTime<= '" + endDateStr + "'",
                    null);
            retVal = cursor2Json(c);
        } catch (Exception e) {
            Log.d(TAG, "Error selecting datapoints" + e.toString());
            retVal = null;
        }
        return (retVal);
    }

    //Consumer<JSONObject> callback

    /**
     * Return an array list of objects representing the events in the database by calling the specified callback function.
     *
     * @param includeWarnings - whether to include warnings in the list of events, or just alarm conditions.
     * @return True on successful start or false if call fails.
     */
    public boolean getEventsList(boolean includeWarnings, Consumer<ArrayList<HashMap<String, String>>> callback) {
        Log.v(TAG, "getEventsList - includeWarnings=" + includeWarnings);
        ArrayList<HashMap<String, String>> eventsList = new ArrayList<>();

        String[] whereArgs;
        String whereClause;
        if (includeWarnings) {
            String[] whereArgsWarnings = { "1", "2", "3", "5"};
            whereClause = "Status in (?, ?, ?, ?)";
            whereArgs = whereArgsWarnings;
        } else {
            whereClause = "Status in (?, ?, ?)";
            String[] whereArgsNoWarnings = { "2", "3", "5"};
            whereArgs = whereArgsNoWarnings;
        }
        //sqlStr = "SELECT * from " + mDbTableName + " where Status in (" + statusListStr + ") order by dataTime desc;";
        String[] columns = {"*"};
        new SelectQueryTask(mDbTableName, columns, whereClause, whereArgs,
                null, null, "dataTime DESC", (Cursor cursor) -> {
            Log.v(TAG, "getEventsList - returned " + cursor);
            if (cursor != null) {
                Log.v(TAG, "getEventsList - returned " + cursor.getCount() + " records");
                while (cursor.moveToNext()) {
                    HashMap<String, String> event = new HashMap<>();
                    //event.put("id", cursor.getString(cursor.getColumnIndex("id")));
                    event.put("dataTime", cursor.getString(cursor.getColumnIndex("dataTime")));
                    int status = cursor.getInt(cursor.getColumnIndex("Status"));
                    String statusStr = mUtil.alarmStatusToString(status);
                    event.put("status", statusStr);
                    event.put("uploaded", cursor.getString(cursor.getColumnIndex("uploaded")));
                    //event.put("dataJSON", cursor.getString(cursor.getColumnIndex("dataJSON")));
                    eventsList.add(event);
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
        Cursor c = null;
        int retVal;
        long currentDateMillis = new Date().getTime();
        long endDateMillis = currentDateMillis - 24 * 3600 * 1000 * mDataRetentionPeriod;
        //long endDateMillis = currentDateMillis - 3600*1000* mDataRetentionPeriod;  // Using hours rather than days for testing
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        String endDateStr = dateFormat.format(new Date(endDateMillis));
        try {
            String selectStr = "DataTime<=?";
            String[] selectArgs = {endDateStr};
            retVal = mOSDDb.getWritableDatabase().delete(mDbTableName, selectStr, selectArgs);
        } catch (Exception e) {
            Log.d(TAG, "Error deleting datapoints" + e.toString());
            retVal = 0;
        }
        Log.d(TAG, String.format("pruneLocalDb() - deleted %d records", retVal));
        return (retVal);
    }


    public boolean getNextEventToUpload(Consumer<Long> callback) {
        Log.v(TAG, "getNextEventToUpload");
        // Based on https://stackoverflow.com/questions/24827312/is-a-good-practice-create-anonymous-asynctask-for-parallel-small-known-freeze-pr
        new AsyncTask<Void, Void, Long>() {
            @Override
            protected Long doInBackground(Void... params) {
                Log.v(TAG, "getEventsNextEventToUpload.doInBackground");
                Long eventId;
                eventId = _getNextEventToUpload(false);
                if (eventId == -1) {
                    eventId = _getNextEventToUpload(true);
                }
                return (eventId);
            }

            @Override
            protected void onPostExecute(final Long result) {
                callback.accept(result);
            }
        }.execute();
        return (true);
    }


    /**
     * Return the ID of the next event (alarm, warning, fall etc that needs to be uploaded (alarm or warning condition and has not yet been uploaded.
     */
    public Long _getNextEventToUpload(boolean includeWarnings) {
        Log.v(TAG, "getNextEventToUpload(" + includeWarnings + ")");
        Time tnow = new Time(Time.getCurrentTimezone());
        tnow.setToNow();
        String dateStr = tnow.format("%Y-%m-%d");
        String SQLStr = "SQLStr";
        String statusListStr;
        String recordStr;
        Long recordId;

        if (includeWarnings) {
            statusListStr = "1,2,3,5";   // Warning, Alarm, Fall, Manual Alarm
        } else {
            statusListStr = "2,3,5";    // Alarm, Fall, Manual Alarm
        }

        // Do not try to upload very recent events so that we have chance to record the post-event data before uploading it.
        long currentDateMillis = new Date().getTime();
        long endDateMillis = currentDateMillis - 1000 * mEventDuration;
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String endDateStr = dateFormat.format(new Date(endDateMillis));
        try {
            SQLStr = "SELECT * from " + mDbTableName + " where uploaded=false and Status in (" + statusListStr + ") and DataTime<'" + endDateStr + "';";
            Cursor resultSet = mOSDDb.getWritableDatabase().rawQuery(SQLStr, null);
            resultSet.moveToFirst();
            if (resultSet.getCount() == 0) {
                Log.v(TAG, "getNextEventToUpload() - no events to Upload - exiting");
                recordId = new Long(-1);
            } else {
                recordStr = resultSet.getString(3);
                recordId = resultSet.getLong(0);
                Log.d(TAG, "getNextEventToUpload(): id=" + recordId + ", recordStr=" + recordStr);
            }
        } catch (SQLException e) {
            Log.e(TAG, "getNextEventToUpload(): Error selecting Data: " + e.toString());
            Log.e(TAG, "SQLStr was " + SQLStr);
            recordStr = "ERROR";
            recordId = new Long(-1);
        }
        return (recordId);
    }


    /**
     * Return the ID of the datapoint that is closest to date/time string dateStr
     * Based on https://stackoverflow.com/questions/45749046/sql-get-nearest-date-record
     */
    public int getNearestDatapointToDate(String dateStr) {
        Log.v(TAG, "getNearestDatapointToDate()");
        String SQLStr = "SQLStr";
        //String recordStr;
        int recordId;

        try {
            SQLStr = "SELECT *, (julianday(dataTime)-julianday(datetime('" + dateStr + "'))) as ddiff from " + mDbTableName + " order by ABS(ddiff) asc;";
            //SQLStr = "SELECT * from "+ mDbTableName + " ;";
            Cursor resultSet = mOSDDb.getWritableDatabase().rawQuery(SQLStr, null);
            resultSet.moveToFirst();
            if (resultSet.getCount() == 0) {
                Log.v(TAG, "getNearestDatapointToDate() - no datapoints found - exiting");
                recordId = -1;
            } else {
                recordId = resultSet.getInt(0);
                //resultSet.moveToFirst();
                //recordStr = cursor2Json(resultSet); //getDatapointById(recordId);
                Log.d(TAG, "getNearestDatapointToDate(): id=" + recordId + ", count=" + resultSet.getCount());
            }
        } catch (SQLException e) {
            Log.e(TAG, "getNearestDatapointToDate(): Error selecting Data: " + e.toString());
            Log.e(TAG, "SQLStr was " + SQLStr);
            //recordStr = "ERROR";
            recordId = -1;
        }
        return (recordId);
    }


    /**
     * Return the number of events stored in the local database
     */
    public int getLocalEventsCount(boolean includeWarnings) {
        //Log.v(TAG, "getLocalEventsCount()");
        String SQLStr = "SQLStr";
        String statusListStr;

        if (includeWarnings) {
            statusListStr = "1,2,3,5";   // Warning, Alarm, Fall, Manual Alarm
        } else {
            statusListStr = "2,3,5";    // Alarm, Fall, Manual Alarm
        }
        try {
            SQLStr = "SELECT * from " + mDbTableName + " where Status in (" + statusListStr + ");";
            Cursor resultSet = mOSDDb.getWritableDatabase().rawQuery(SQLStr, null);
            resultSet.moveToFirst();
            return (resultSet.getCount());
        } catch (SQLException e) {
            Log.e(TAG, "getLocalEventsCount(): Error selecting Data: " + e.toString());
            Log.e(TAG, "SQLStr was " + SQLStr);
            return (0);
        }
    }

    /**
     * Return the number of datapoints stored in the local database
     */
    public int getLocalDatapointsCount() {
        //Log.v(TAG, "getLocalDatapointsCount()");
        String SQLStr = "SQLStr";
        String statusListStr;

        try {
            SQLStr = "SELECT * from " + mDbTableName + ";";
            Cursor resultSet = mOSDDb.getWritableDatabase().rawQuery(SQLStr, null);
            resultSet.moveToFirst();
            return (resultSet.getCount());
        } catch (SQLException e) {
            Log.e(TAG, "getLocalDatapointsCount(): Error selecting Data: " + e.toString());
            Log.e(TAG, "SQLStr was " + SQLStr);
            return (0);
        }
    }

    /**
     * Executes the sqlite query (=SELECT statement)
     * Use as new SelectQueryTask(xxx,xxx,xx,xxxx).execute()
     *
     * @param table         - table name to query
     * @param columns       - array of strings of column names to return
     * @param selection     - where clause
     * @param selectionArgs - arguments for where clause (array of strings)
     * @param groupBy;
     * @param having;
     * @param orderBy;
     * @param callback
     * @return A Cursor object containing the result of the query.
     */
    private class SelectQueryTask extends AsyncTask<Void, Void, Cursor> {
        // Based on https://stackoverflow.com/a/21120199/2104584
        String mTable;
        String[] mColumns;
        String mSelection;
        String[] mSelectionArgs;
        String mGroupBy;
        String mHaving;
        String mOrderBy;
        Consumer<Cursor> mCallback;

        //query(String table, String[] columns, String selection, String[] selectionArgs,
        // String groupBy, String having, String orderBy)
        SelectQueryTask(String table, String[] columns, String selection, String[] selectionArgs,
                        String groupBy, String having, String orderBy, Consumer<Cursor> callback) {
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
            Log.v(TAG, "runSelect.doInBackground()");
            Log.v(TAG, "SelectQueryTask.doInBackground: mTable=" + mTable + ", mColumns=" + Arrays.toString(mColumns)
                    + ", mSelection=" + mSelection + ", mSelectionArgs=" + Arrays.toString(mSelectionArgs) + ", mGroupBy=" + mGroupBy
                    + ", mHaving =" + mHaving + ", mOrderBy=" + mOrderBy);

            try {
                Cursor resultSet = mOSDDb.getWritableDatabase().query(mTable, mColumns, mSelection,
                        mSelectionArgs, mGroupBy, mHaving, mOrderBy);
                resultSet.moveToFirst();
                return (resultSet);
            } catch (SQLException e) {
                Log.e(TAG, "SelectQueryTask.doInBackground(): Error selecting Data: " + e.toString());
                return (null);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "SelectQueryTask.doInBackground(): Illegal Argument Exception: " + e.toString());
                return (null);
            }
        }

        @Override
        protected void onPostExecute(final Cursor result) {
            mCallback.accept(result);
        }
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
        Log.v(TAG, "uploadSdData()");
        // First try uploading full alarms, and only if we do not have any of those, upload warnings.
        getNextEventToUpload((Long eventId) -> {
            if (eventId != -1) {
                Log.v(TAG, "uploadSdData() - eventId=" + eventId);
                String eventJsonStr = getDatapointById(eventId);
                Log.v(TAG, "uploadSdData() - eventJsonStr=" + eventJsonStr);
                int eventType;
                JSONObject eventObj;
                int eventAlarmStatus;
                String eventDateStr;
                Date eventDate;
                try {
                    JSONArray datapointJsonArr = new JSONArray(eventJsonStr);
                    eventObj = datapointJsonArr.getJSONObject(0);  // We only look at the first (and hopefully only) item in the array.
                    eventAlarmStatus = Integer.parseInt(eventObj.getString("status"));
                    eventDateStr = eventObj.getString("dataTime");
                    Log.v(TAG, "uploadSdData - data from local DB is:" + eventJsonStr + ", eventAlarmStatus="
                            + eventAlarmStatus + ", eventDateStr=" + eventDateStr);
                } catch (JSONException e) {
                    Log.e(TAG, "ERROR parsing event JSON Data" + eventJsonStr);
                    e.printStackTrace();
                    return;
                } catch (NullPointerException e) {
                    Log.e(TAG, "ERROR null pointer exception parsing event JSON Data" + eventJsonStr);
                    e.printStackTrace();
                    return;
                }
                try {
                    eventDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(eventDateStr);
                } catch (ParseException e) {
                    Log.e(TAG, "Error parsing date " + eventDateStr);
                    return;
                }
                mWac.createEvent(eventAlarmStatus, eventDate, "Uploaded by OpenSeizureDetector Android App");
            } else {
                Log.v(TAG, "UploadSdData - no data to upload");
            }
        });
    }

    public void authCallback(boolean authSuccess, String tokenStr) {
        Log.v(TAG, "authCallback");
    }


    // Mark the relevant member variables to show we are not currently doing an upload, so a new one can be
    // started if necessary.
    public void finishUpload() {
        mCurrentEventId = -1;
        mDatapointsToUploadList = null;
        mUploadInProgress = false;
    }

    // Called by WebApiConnection when a new event record is created.
    // Once the event is created it queries the local database to find the datapoints associated with the event
    // and uploads those as a batch of data points.
    public void eventCallback(boolean success, String eventStr) {
        Log.v(TAG, "eventCallback(): " + eventStr);
        Date eventDate;
        String eventDateStr;
        int eventId;
        try {
            JSONObject eventObj = new JSONObject(eventStr);
            eventDateStr = eventObj.getString("dataTime");
            eventId = eventObj.getInt("id");
        } catch (JSONException e) {
            Log.e(TAG, "eventCallback() - Error parsing eventStr: " + eventStr);
            finishUpload();
            return;
        }
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        try {
            eventDate = dateFormat.parse(eventDateStr);
        } catch (ParseException e) {
            Log.e(TAG, "eventCallback() - error parsing date string " + eventDateStr);
            finishUpload();
            return;
        }
        Log.v(TAG, "eventCallback() EventId=" + eventId + ", eventDateStr=" + eventDateStr + ", eventDate=" + eventDate.toString());

        long eventDateMillis = eventDate.getTime();
        long startDateMillis = eventDateMillis - 1000 * mEventDuration / 2;
        long endDateMillis = eventDateMillis + 1000 * mEventDuration / 2;
        dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        String datapointsJsonStr = getDatapointsbyDate(
                dateFormat.format(new Date(startDateMillis)),
                dateFormat.format(new Date(endDateMillis)));
        Log.v(TAG, "eventCallback() - datapointsJsonStr=" + datapointsJsonStr);

        JSONArray dataObj;
        mDatapointsToUploadList = new ArrayList<JSONObject>();
        try {
            //DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            dataObj = new JSONArray(datapointsJsonStr);
            for (int i = 0; i < dataObj.length(); i++) {
                mDatapointsToUploadList.add(dataObj.getJSONObject(i));
            }
            //dataObj.put("dataTime", dateFormat.format(new Date()));
            //Log.v(TAG, "eventCallback(): Creating Datapoint...SdData="+dataObj.toString());
            //mWac.createDatapoint(dataObj,eventId);
        } catch (JSONException e) {
            Log.v(TAG, "Error Creating JSON Object from string " + datapointsJsonStr);
            dataObj = null;
            finishUpload();
        }
        // This starts the process of uploading the datapoints, one at a time.
        mCurrentEventId = eventId;
        mUploadInProgress = true;
        Log.v(TAG, "eventCallback() - starting datapoints upload with eventId " + mCurrentEventId);
        uploadNextDatapoint();
    }

    // takes the next datapoint of the list mDatapointsToUploadList and uploads it to the remote server.
    // datapointCallback is called when the upload is complete.
    public void uploadNextDatapoint() {
        Log.v(TAG, "uploadDatapoint()");
        if (mDatapointsToUploadList.size() > 0) {
            mUploadInProgress = true;
            try {
                mCurrentDatapointId = mDatapointsToUploadList.get(0).getInt("id");
            } catch (JSONException e) {
                Log.e(TAG, "Error reading currentDatapointID from mDatapointsToUploadList[0]" + e.getMessage());
                Log.e(TAG, "Removing mDatapointsToUploadList[0] and trying the next datapoint");
                mDatapointsToUploadList.remove(0);
                uploadNextDatapoint();
            }

            Log.v(TAG, "uploadDatapoint() - uploading datapoint with local id of " + mCurrentDatapointId);
            mWac.createDatapoint(mDatapointsToUploadList.get(0), mCurrentEventId);

        } else {
            mCurrentEventId = -1;
            mCurrentDatapointId = -1;
            mUploadInProgress = false;
        }
    }

    // Called by WebApiConnection when a new datapoint is created.   It assumes that we have just created
    // a datapoint based on mDatapointsToUploadList(0) so removes that from the list and calls UploadDatapoint()
    // to upload the next one.
    public void datapointCallback(boolean success, String datapointStr) {
        Log.v(TAG, "datapointCallback() " + datapointStr + ", mCurrentEventId=" + mCurrentEventId);
        mDatapointsToUploadList.remove(0);
        setDatapointToUploaded(mCurrentDatapointId, mCurrentEventId);
        uploadNextDatapoint();
    }


    /**
     * close() - shut down the logging system
     */
    public void close() {
        mOSDDb.close();
        stopRemoteLogTimer();
    }

    /*
     * Start the timer that will upload data to the remote server after a given period.
     */
    private void startRemoteLogTimer() {
        if (mRemoteLogTimer != null) {
            Log.v(TAG, "startRemoteLogTimer -timer already running - cancelling it");
            mRemoteLogTimer.cancel();
            mRemoteLogTimer = null;
        }
        Log.v(TAG, "startRemoteLogTimer() - starting RemoteLogTimer");
        mRemoteLogTimer =
                new RemoteLogTimer(mRemoteLogPeriod * 1000, 1000);
        mRemoteLogTimer.start();
    }


    /*
     * Cancel the remote logging timer to prevent attempts to upload to remote database.
     */
    public void stopRemoteLogTimer() {
        if (mRemoteLogTimer != null) {
            Log.v(TAG, "stopRemoteLogTimer(): cancelling Remote Log timer");
            mRemoteLogTimer.cancel();
            mRemoteLogTimer = null;
        }
    }


    /*
     * Start the timer that will Auto Prune the database
     */
    private void startAutoPruneTimer() {
        if (mAutoPruneTimer != null) {
            Log.v(TAG, "startAutoPruneTimer -timer already running - cancelling it");
            mAutoPruneTimer.cancel();
            mAutoPruneTimer = null;
        }
        Log.v(TAG, "startAutoPruneTimer() - starting AutoPruneTimer");
        mAutoPruneTimer =
                new AutoPruneTimer(mAutoPrunePeriod * 1000, 1000);
        mAutoPruneTimer.start();
    }


    /*
     * Cancel the auto prune timer to prevent attempts to upload to remote database.
     */
    public void stopAutoPruneTimer() {
        if (mAutoPruneTimer != null) {
            Log.v(TAG, "stopAutoPruneTimer(): cancelling Auto Prune timer");
            mAutoPruneTimer.cancel();
            mAutoPruneTimer = null;
        }
    }


    public class OsdDbHelper extends SQLiteOpenHelper {
        // If you change the database schema, you must increment the database version.
        public static final int DATABASE_VERSION = 1;
        public static final String DATABASE_NAME = "OsdData.db";
        private String mOsdTableName;
        private String TAG = "LogManager.OsdDbHelper";

        public OsdDbHelper(String osdTableName, Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
            Log.d(TAG, "OsdDbHelper constructor");
            mOsdTableName = osdTableName;
        }

        public void onCreate(SQLiteDatabase db) {
            Log.v(TAG, "onCreate - TableName=" + mOsdTableName);
            String SQLStr = "CREATE TABLE IF NOT EXISTS " + mOsdTableName + "("
                    + "id INTEGER PRIMARY KEY,"
                    + "dataTime DATETIME,"
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
            Log.v(TAG, "mRemoteLogTimer - onFinish - uploading data to remote database");
            writeToRemoteServer();
            // Restart this timer.
            start();
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
            Log.v(TAG, "mAutoPruneTimer - onFinish - Pruning Local Database");
            pruneLocalDb();
            // Restart this timer.
            start();
        }

    }


}
