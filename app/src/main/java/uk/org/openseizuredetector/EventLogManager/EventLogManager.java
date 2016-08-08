/**
 * Database manager for logging events and associated seizure detector data.
 */
package uk.org.openseizuredetector.EventLogManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

public class EventLogManager {
    final static String TAG = "EventLogManager";
	private SQLiteDatabase db; // a reference to the database manager class.
	private static final String DB_NAME = "eventlog"; // the name of our database
	private static final int DB_VERSION = 1; // the version of the database

	private static final String TABLE_NAME = "events";// table name

	// the names for our database columns
	private static final String TABLE_ROW_ID = "_id";
	private static final String TABLE_ROW_DATE = "event_date";
	private static final String TABLE_ROW_ALARM_STATE = "alarm_state";
	private static final String TABLE_ROW_DATA_JSON = "data_json";
	private static final String TABLE_ROW_NOTE = "note";
	private Context context;

	public EventLogManager(Context context) {
		this.context = context;

		// create or open the database
		CustomSQLiteOpenHelper helper = new CustomSQLiteOpenHelper(context);
		this.db = helper.getWritableDatabase();

		helper.onCreate(this.db);
	}

	// the beginnings our SQLiteOpenHelper class
	private class CustomSQLiteOpenHelper extends SQLiteOpenHelper {

		public CustomSQLiteOpenHelper(Context context) {
			super(context, DB_NAME, null, DB_VERSION);
		}

		@Override
		public void onCreate(SQLiteDatabase db) {
			// the SQLite query string that will create our column database
			// table.
			String newTableQueryString = "create table " + TABLE_NAME + " ("
					+ TABLE_ROW_ID
					+ " integer primary key autoincrement not null,"
					+ TABLE_ROW_DATE + " timestamp not null," + TABLE_ROW_ALARM_STATE
					+ " integer not null," + TABLE_ROW_NOTE + " text not null,"
					+ TABLE_ROW_DATA_JSON + " text not null" + ");";

			// execute the query string to the database.
			db.execSQL(newTableQueryString);
		}

		@Override
		public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {

			// LATER, WE WOULD SPECIFIY HOW TO UPGRADE THE DATABASE
			// FROM OLDER VERSIONS.
			String DROP_TABLE = "DROP TABLE IF EXISTS " + TABLE_NAME;
			db.execSQL(DROP_TABLE);
			onCreate(db);

		}

	}

	public void addRow(LogEntryModel eventObj) {
		ContentValues values = prepareData(eventObj);
		// ask the database object to insert the new data
		try {
			db.insert(TABLE_NAME, null, values);
		} catch (Exception e) {
			Log.e("DB ERROR", e.toString()); // prints the error message to
			// the log
			e.printStackTrace(); // prints the stack trace to the log
		}
	}

    private String getDateTime(Date date) {
        SimpleDateFormat dateFormat = new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        if (date==null)
            return "";
        else
            return dateFormat.format(date);
    }

	private ContentValues prepareData(LogEntryModel eventObj) {

		ContentValues values = new ContentValues();
		values.put(TABLE_ROW_ALARM_STATE, eventObj.getAlarmState());
		values.put(TABLE_ROW_DATE, getDateTime(eventObj.getDate()));
		values.put(TABLE_ROW_NOTE, eventObj.getNote());
		values.put(TABLE_ROW_DATA_JSON, eventObj.getDataJSON());
		return values;
	}

	// Returns row data in form of LogEntryModel object
	public LogEntryModel getRowAsObject(int rowID) {

		LogEntryModel rowContactObj = new LogEntryModel();
		Cursor cursor;

		try {

			cursor = db.query(TABLE_NAME, new String[] { TABLE_ROW_ID,
					TABLE_ROW_ALARM_STATE, TABLE_ROW_DATE, TABLE_ROW_NOTE,
					TABLE_ROW_DATA_JSON }, TABLE_ROW_ID + "=" + rowID, null,
					null, null, null, null);
			
			cursor.moveToFirst();
			prepareSendObject(rowContactObj, cursor);
			
		} catch (SQLException e) {
			Log.e("DB ERROR", e.toString());
			e.printStackTrace();
		}

		return rowContactObj;
	}

	// Returns all the rows data in form of LogEntryModel object list

	public ArrayList<LogEntryModel> getAllData() {

		ArrayList<LogEntryModel> allRowsObj = new ArrayList<LogEntryModel>();
		Cursor cursor;
		LogEntryModel rowContactObj;

		String[] columns = new String[] { TABLE_ROW_ID, TABLE_ROW_ALARM_STATE,
				TABLE_ROW_DATE, TABLE_ROW_NOTE, TABLE_ROW_DATA_JSON };

		try {

			cursor = db
					.query(TABLE_NAME, columns, null, null, null, null, null);
			cursor.moveToFirst();

			if (!cursor.isAfterLast()) {
				do {
					rowContactObj = new LogEntryModel();
					rowContactObj.setId(cursor.getInt(0));
					prepareSendObject(rowContactObj, cursor);
					allRowsObj.add(rowContactObj);

				} while (cursor.moveToNext()); // try to move the cursor's
				// pointer forward one position.
			}
		} catch (SQLException e) {
			Log.e("DB ERROR", e.toString());
			e.printStackTrace();
		}

		return allRowsObj;

	}

	private void prepareSendObject(LogEntryModel rowObj, Cursor cursor) {
		rowObj.setId(cursor.getInt(cursor.getColumnIndexOrThrow(TABLE_ROW_ID)));
		rowObj.setAlarmState(cursor.getInt(cursor
				.getColumnIndexOrThrow(TABLE_ROW_ALARM_STATE)));
		String dateTimeStr = cursor.getString(cursor
				.getColumnIndexOrThrow(TABLE_ROW_DATE));
        Log.v(TAG,"dateTimeStr = "+dateTimeStr);
        Date dateVal;
        try { dateVal = new Date(dateTimeStr); }
        catch (IllegalArgumentException e) { dateVal = null; }
        rowObj.setDate(dateVal);
		rowObj.setNote(cursor.getString(cursor
				.getColumnIndexOrThrow(TABLE_ROW_NOTE)));
		rowObj.setDataJSON(cursor.getString(cursor
				.getColumnIndexOrThrow(TABLE_ROW_DATA_JSON)));
	}

	public void deleteRow(int rowID) {
		// ask the database manager to delete the row of given id
		try {
			db.delete(TABLE_NAME, TABLE_ROW_ID + "=" + rowID, null);
		} catch (Exception e) {
			Log.e("DB ERROR", e.toString());
			e.printStackTrace();
		}
	}

	public void updateRow(int rowId, LogEntryModel contactObj) {

		ContentValues values = prepareData(contactObj);

		String whereClause = TABLE_ROW_ID + "=?";
		String whereArgs[] = new String[] { String.valueOf(rowId) };

		db.update(TABLE_NAME, values, whereClause, whereArgs);

	}


}
