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
import android.text.format.Time;
import android.util.Log;

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
    private String mOSDUname;
    private String mOSDPasswd;
    private int mOSDWearerId;
    private String mOSDUrl;
    private OsdDbHelper mOSDDb;
    private Context mContext;

    public LogManager(boolean logRemote,
                      boolean logRemoteMobile,
                      String OSDUname,
                      String OSDPasswd,
                      int OSDWearerId,
                      String OSDUrl,
                      Context context) {
        mLogRemote = logRemote;
        mLogRemoteMobile = logRemoteMobile;
        mOSDUname = OSDUname;
        mOSDPasswd = OSDPasswd;
        mOSDWearerId = OSDWearerId;
        mOSDUrl = OSDUrl;
        mContext = context;

        try {
                mOSDDb = new OsdDbHelper(mDbTableName, mContext);
                if (!checkTableExists(mOSDDb, mDbTableName)) {
                    Log.e(TAG,"ERROR - Table does not exist");
             }
        } catch (SQLException e) {
            Log.e(TAG, "Failed to open Database: " + e.toString());
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
                    + mOSDWearerId + ","
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

    public void close() {
        mOSDDb.close();
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
}
