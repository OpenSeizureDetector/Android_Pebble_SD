/*
  OpenSeizureDetector - Log Repository

  Encapsulates all local SQLite database operations for events and datapoints.

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

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;

import uk.org.openseizuredetector.R;
import uk.org.openseizuredetector.comms.WebApiConnection;
import uk.org.openseizuredetector.data.SdData;
import uk.org.openseizuredetector.utils.BackgroundTaskExecutor;
import uk.org.openseizuredetector.utils.OsdUtil;

/**
 * Repository for local SQLite database operations (datapoints and events).
 * Handles all CRUD operations, queries, and migrations.
 */
public class LogRepository {
    private static final String TAG = "LogRepository";
    private static final String DP_TABLE_NAME = "datapoints";
    private static final String EVENTS_TABLE_NAME = "events";

    private Context mContext;
    private OsdUtil mUtil;
    private static SQLiteDatabase mOsdDb = null;
    private long mDataRetentionPeriod = 1;  // days

    public interface CursorCallback {
        void accept(Cursor retVal);
    }

    public interface ArrayListCallback {
        void accept(java.util.ArrayList<java.util.HashMap<String, String>> retVal);
    }

    public interface BooleanCallback {
        void accept(boolean retVal);
    }

    public LogRepository(Context context, OsdUtil util) {
        mContext = context;
        mUtil = util;
    }

    /**
     * Initialize the database.
     */
    public void initialize() {
        openDb();
    }

    /**
     * Set the data retention period (days).
     */
    public void setDataRetentionPeriod(long days) {
        mDataRetentionPeriod = days;
    }

    /**
     * Returns the SQLiteDatabase instance (for compatibility with existing code).
     */
    public static SQLiteDatabase getDatabase() {
        return mOsdDb;
    }

    /**
     * Write a datapoint to local database.
     */
    public void writeDatapointToLocalDb(SdData sdData, uk.org.openseizuredetector.data.SdDataHistory sdDataHistory) {
        Date curDate = new Date();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        final String dateStr = dateFormat.format(curDate);

        if (mOsdDb == null) {
            Log.e(TAG, "writeDatapointToLocalDb(): mOsdDb is null - doing nothing");
            return;
        }

        BackgroundTaskExecutor.executeAndForget(() -> {
            try {
                String SQLStr = "INSERT INTO " + DP_TABLE_NAME
                        + "(dataTime, status, dataJSON, uploaded, watchBattHist, phoneBattHist, "
                        + "signalStrengthHist, pseudSeizureHist, accelMagStdDevHist, hrHist)"
                        + " VALUES("
                        + "'" + dateStr + "',"
                        + sdData.alarmState + ","
                        + DatabaseUtils.sqlEscapeString(sdData.toDatapointJSON()) + ","
                        + "0,"
                        + DatabaseUtils.sqlEscapeString("") + ","
                        + DatabaseUtils.sqlEscapeString("") + ","
                        + DatabaseUtils.sqlEscapeString("") + ","
                        + DatabaseUtils.sqlEscapeString("") + ","
                        + DatabaseUtils.sqlEscapeString("") + ","
                        + DatabaseUtils.sqlEscapeString("")
                        + ")";

                mOsdDb.execSQL(SQLStr);
                Log.v(TAG, "writeDatapointToLocalDb(): datapoint written to database");

                if (sdData.alarmState != uk.org.openseizuredetector.data.AlarmState.OK) {
                    Log.i(TAG, "writeDatapointToLocalDb(): adding event to local DB");
                    createLocalEvent(dateStr, sdData.alarmState, null, null, sdData.alarmCause, null, sdData.toSettingsJSON());
                }
            } catch (SQLException e) {
                Log.e(TAG, "writeDatapointToLocalDb(): SQL Error: " + e.toString());
            } catch (Exception e) {
                Log.e(TAG, "writeDatapointToLocalDb(): Unexpected error: " + e.toString());
            }
        });
    }

    /**
     * Create a local event record.
     */
    public boolean createLocalEvent(String dataTime, long status) {
        return createLocalEvent(dataTime, status, null, null, null, null, null);
    }

    /**
     * Create a local event record with full details.
     */
    public boolean createLocalEvent(String dataTime, long status, String type, String subType, String alarmCause, String desc, String dataJSON) {
        Log.d(TAG, "createLocalEvent() - dataTime=" + dataTime + ", status=" + status);
        ContentValues values = new ContentValues();
        values.put("dataTime", dataTime);
        values.put("status", status);
        values.put("type", type);
        values.put("subType", subType);
        values.put("alarmCause", alarmCause);
        values.put("notes", desc);
        values.put("dataJSON", dataJSON);

        if (mOsdDb != null) {
            long newRowId = mOsdDb.insert(EVENTS_TABLE_NAME, null, values);
            Log.d(TAG, "createLocalEvent(): Created Row ID " + newRowId);
            return true;
        } else {
            Log.e(TAG, "createLocalEvent() - mOsdDb is null");
            return false;
        }
    }

    /**
     * Get a local event by ID as JSON.
     */
    public String getLocalEventById(long id) {
        Log.d(TAG, "getLocalEventById() - id=" + id);
        Cursor c;
        String retVal;
        try {
            String selectStr = "select * from " + EVENTS_TABLE_NAME + " where id=" + id + ";";
            c = mOsdDb.rawQuery(selectStr, null);
            retVal = eventCursor2Json(c);
        } catch (Exception e) {
            Log.d(TAG, "getLocalEventById(): Error Querying Database: " + e.getLocalizedMessage());
            retVal = null;
        }
        return retVal;
    }

    /**
     * Get a datapoint by ID as JSON.
     */
    public String getDatapointById(long id) {
        Log.d(TAG, "getDatapointById() - id=" + id);
        Cursor c;
        String retVal;
        try {
            String selectStr = "select * from " + DP_TABLE_NAME + " where id=" + id + ";";
            c = mOsdDb.rawQuery(selectStr, null);
            retVal = cursor2Json(c);
        } catch (Exception e) {
            Log.d(TAG, "getDatapointById(): Error Querying Database: " + e.getLocalizedMessage());
            retVal = null;
        }
        return retVal;
    }

    /**
     * Mark a datapoint as uploaded.
     */
    public boolean setDatapointToUploaded(int id, String eventId) {
        Log.d(TAG, "setDatapointToUploaded() - id=" + id);
        if (mOsdDb == null) {
            Log.e(TAG, "setDatapointToUploaded() - mOsdDb is null");
            return false;
        }
        ContentValues cv = new ContentValues();
        cv.put("uploaded", eventId);
        int nRowsUpdated = mOsdDb.update(DP_TABLE_NAME, cv, "id = ?",
                new String[]{String.format("%d", id)});
        return nRowsUpdated == 1;
    }

    /**
     * Update datapoint status.
     */
    public boolean setDatapointStatus(Long id, int statusVal) {
        Log.d(TAG, "setDatapointStatus() - id=" + id + ", statusVal=" + statusVal);
        ContentValues cv = new ContentValues();
        cv.put("status", statusVal);
        int nRowsUpdated = mOsdDb.update(DP_TABLE_NAME, cv, "id = ?",
                new String[]{String.format("%d", id)});
        return nRowsUpdated == 1;
    }

    /**
     * Get datapoints by date range.
     */
    public boolean getDatapointsByDate(String startDateStr, String endDateStr, WebApiConnection.StringCallback callback) {
        Log.d(TAG, "getDatapointsByDate() - startDateStr=" + startDateStr + ", endDateStr=" + endDateStr);
        String[] columns = {"*"};
        String whereClause = "DataTime>? AND DataTime<?";
        String[] whereArgs = {startDateStr, endDateStr};
        executeSelectQuery(DP_TABLE_NAME, columns, whereClause, whereArgs, null, null, "dataTime DESC", (Cursor cursor) -> {
            try {
                if (cursor != null) {
                    callback.accept(cursor2Json(cursor));
                } else {
                    Log.w(TAG, "getDatapointsByDate() - returned null result");
                    callback.accept(null);
                }
            } finally {
                // cursor closed inside cursor2Json
            }
        });
        return true;
    }

    /**
     * Mark an event as uploaded.
     */
    public boolean setEventToUploaded(long localEventId, String remoteEventId) {
        Log.d(TAG, "setEventToUploaded() - local id=" + localEventId + " remote id=" + remoteEventId);
        if (mOsdDb == null) {
            Log.e(TAG, "setEventToUploaded() - mOsdDb is null");
            return false;
        }
        ContentValues cv = new ContentValues();
        cv.put("uploaded", remoteEventId);
        int nRowsUpdated = mOsdDb.update(EVENTS_TABLE_NAME, cv, "id = ?",
                new String[]{String.format("%d", localEventId)});
        return nRowsUpdated == 1;
    }

    /**
     * Get the next event to upload.
     */
    public boolean getNextEventToUpload(boolean includeWarnings, WebApiConnection.LongCallback callback) {
        Log.v(TAG, "getNextEventToUpload - includeWarnings=" + includeWarnings);

        String[] whereArgsStatus = getEventWhereArgs(includeWarnings);
        String whereClauseStatus = getEventWhereClause(includeWarnings);
        String[] columns = {"*"};

        long currentDateMillis = new Date().getTime();
        long endDateMillis = currentDateMillis - 120000;  // 2 minute event duration / 2
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

        executeSelectQuery(EVENTS_TABLE_NAME, columns, whereClause, whereArgs, null, null, "dataTime DESC", (Cursor cursor) -> {
            long recordId = -1;
            try {
                if (cursor != null) {
                    Log.v(TAG, "getNextEventToUpload - returned " + cursor.getCount() + " records");
                    cursor.moveToFirst();
                    if (cursor.getCount() > 0) {
                        recordId = cursor.getLong(0);
                        Log.d(TAG, "getNextEventToUpload(): id=" + recordId);
                    }
                }
                callback.accept(recordId);
            } finally {
                if (cursor != null) {
                    try { cursor.close(); } catch (Exception e) { Log.w(TAG, "error closing cursor: " + e.toString()); }
                }
            }
        });
        return true;
    }

    /**
     * Prune local database.
     */
    public int pruneLocalDb() {
        Log.d(TAG, "pruneLocalDb()");
        int retVal = 0;
        long currentDateMillis = new Date().getTime();
        long endDateMillis = currentDateMillis - 24 * 3600 * 1000 * mDataRetentionPeriod;
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String endDateStr = dateFormat.format(new Date(endDateMillis));
        String[] tableNames = new String[]{DP_TABLE_NAME, EVENTS_TABLE_NAME};
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
            Log.d(TAG, "pruneLocalDb() - deleted " + retVal + " records from table " + tableName);
        }
        return retVal;
    }

    // ===== Private helper methods =====

    private static void openDb() {
        Log.d(TAG, "openDb");
        try {
            if (mOsdDb == null) {
                Log.i(TAG, "openDb: mOsdDb is null - initialising");
                OsdDbHelper helper = new OsdDbHelper(LogManager.getContext());
                mOsdDb = helper.getWritableDatabase();
                Log.i(TAG, "openDb: Database opened successfully");
            } else {
                Log.i(TAG, "openDb: mOsdDb has been initialised already");
            }
            String[] tableNames = new String[]{DP_TABLE_NAME, EVENTS_TABLE_NAME};
            for (String tableName : tableNames) {
                if (!checkTableExists(mOsdDb, tableName)) {
                    Log.e(TAG, "ERROR - Table " + tableName + " does not exist");
                } else {
                    Log.d(TAG, "table " + tableName + " exists ok");
                }
            }
        } catch (SQLException e) {
            Log.e(TAG, "Failed to open Database: " + e.toString());
        }
    }

    private static boolean checkTableExists(SQLiteDatabase osdDb, String osdTableName) {
        Cursor c = null;
        try {
            c = osdDb.query(osdTableName, null, null, null, null, null, null);
            return true;
        } catch (Exception e) {
            Log.d(TAG, osdTableName + " doesn't exist");
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    private String cursor2Json(Cursor c) {
        try {
            c.moveToFirst();
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
                    c.moveToNext();
                    dataPointArray.put(i, datapoint);
                    i++;
                } catch (JSONException | NullPointerException e) {
                    Log.e(TAG, "cursor2Json(): error creating JSON Object");
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

    private String eventCursor2Json(Cursor c) {
        try {
            c.moveToFirst();
            Log.v(TAG, "eventCursor2Json: size of cursor=" + c.getCount());
            JSONArray eventsArray = new JSONArray();
            int i = 0;
            while (!c.isAfterLast()) {
                JSONObject event = new JSONObject();
                try {
                    String val;
                    val = c.getString(c.getColumnIndex("id"));
                    event.put("id", val == null ? "" : val);
                    val = c.getString(c.getColumnIndex("dataTime"));
                    event.put("dataTime", val == null ? "" : val);
                    val = c.getString(c.getColumnIndex("status"));
                    event.put("status", val == null ? "" : val);
                    val = c.getString(c.getColumnIndex("type"));
                    event.put("type", val == null ? "" : val);
                    val = c.getString(c.getColumnIndex("subType"));
                    event.put("subType", val == null ? "" : val);
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

    private static void executeSelectQuery(String table, String[] columns, String selection,
                                           String[] selectionArgs, String groupBy, String having,
                                           String orderBy, CursorCallback callback) {
        BackgroundTaskExecutor.execute(
            () -> {
                Log.v(TAG, "SelectQuery: table=" + table + ", columns=" + Arrays.toString(columns)
                        + ", selection=" + selection);
                try {
                    Cursor resultSet = mOsdDb.query(table, columns, selection,
                            selectionArgs, groupBy, having, orderBy);
                    resultSet.moveToFirst();
                    return resultSet;
                } catch (SQLException e) {
                    Log.e(TAG, "SelectQuery: Error selecting Data: " + e.toString());
                    return null;
                } catch (Exception e) {
                    Log.e(TAG, "SelectQuery: Exception: " + e.toString());
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

    private String getEventWhereClause(boolean includeWarnings) {
        if (includeWarnings) {
            return "Status in (?, ?, ?, ?, ?)";
        } else {
            return "Status in (?, ?, ?, ?)";
        }
    }

    private String[] getEventWhereArgs(boolean includeWarnings) {
        if (includeWarnings) {
            return new String[]{"1", "2", "3", "5", "6"};
        } else {
            return new String[]{"2", "3", "5", "6"};
        }
    }

    /**
     * SQLiteOpenHelper for database creation and migration.
     */
    public static class OsdDbHelper extends SQLiteOpenHelper {
        public static final int DATABASE_VERSION = 4;
        public static final String DATABASE_NAME = "OsdData.db";
        private static final String TAG = "LogRepository.OsdDbHelper";

        public OsdDbHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
            Log.d(TAG, "OsdDbHelper constructor - DATABASE_VERSION=" + DATABASE_VERSION);
        }

        public void onCreate(SQLiteDatabase db) {
            Log.i(TAG, "onCreate");
            String SQLStr = "CREATE TABLE IF NOT EXISTS " + DP_TABLE_NAME + "("
                    + "id INTEGER PRIMARY KEY,"
                    + "dataTime DATETIME,"
                    + "status INT,"
                    + "dataJSON TEXT,"
                    + "uploaded TEXT,"
                    + "watchBattHist TEXT,"
                    + "phoneBattHist TEXT,"
                    + "signalStrengthHist TEXT,"
                    + "pseudSeizureHist TEXT,"
                    + "accelMagStdDevHist TEXT,"
                    + "hrHist TEXT"
                    + ");";
            db.execSQL(SQLStr);

            SQLStr = "CREATE TABLE IF NOT EXISTS " + EVENTS_TABLE_NAME + "("
                    + "id INTEGER PRIMARY KEY,"
                    + "dataTime DATETIME,"
                    + "status INT,"
                    + "type TEXT,"
                    + "subType TEXT,"
                    + "alarmCause TEXT,"
                    + "notes TEXT,"
                    + "dataJSON TEXT,"
                    + "uploaded TEXT"
                    + ");";
            db.execSQL(SQLStr);
            Log.i(TAG, "onCreate - tables created");
        }

        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.i(TAG, "onUpgrade() - oldVersion=" + oldVersion + ", newVersion=" + newVersion);

            if (oldVersion < 4 && newVersion == 4) {
                boolean v3ColumnsExist = false;
                try {
                    Cursor c = db.rawQuery("PRAGMA table_info(" + DP_TABLE_NAME + ")", null);
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
                    try {
                        Log.i(TAG, "onUpgrade() - Adding missing history columns");
                        String[] cols = {"watchBattHist", "phoneBattHist", "signalStrengthHist",
                                        "pseudSeizureHist", "accelMagStdDevHist", "hrHist"};
                        for (String col : cols) {
                            try {
                                db.execSQL("ALTER TABLE " + DP_TABLE_NAME + " ADD COLUMN " + col + " TEXT;");
                            } catch (SQLException e) {
                                // Ignore if column exists
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "onUpgrade() - Error adding history columns: " + e.getMessage());
                    }
                }

                try {
                    Log.i(TAG, "onUpgrade() - Adding alarmCause column");
                    db.execSQL("ALTER TABLE " + EVENTS_TABLE_NAME + " ADD COLUMN alarmCause TEXT;");
                    Log.i(TAG, "onUpgrade() - alarmCause column added successfully");
                } catch (SQLException e) {
                    Log.e(TAG, "onUpgrade() - Error adding alarmCause column: " + e.getMessage());
                }

                Log.i(TAG, "onUpgrade() - Upgrade to v4 complete");
                return;
            }

            Log.w(TAG, "onUpgrade() - Unsupported version change, recreating tables");
            db.execSQL("DROP TABLE IF EXISTS " + DP_TABLE_NAME + ";");
            onCreate(db);
        }

        public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.i(TAG, "onDowngrade()");
            onUpgrade(db, oldVersion, newVersion);
        }
    }
}

