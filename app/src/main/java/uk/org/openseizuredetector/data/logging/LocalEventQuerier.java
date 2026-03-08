/*
  OpenSeizureDetector - Local Event Querier

  Encapsulates all local database query operations for events and datapoints.

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
import android.database.Cursor;
import android.database.SQLException;
import android.net.Uri;
import android.os.Handler;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;

import uk.org.openseizuredetector.R;
import uk.org.openseizuredetector.comms.WebApiConnection;
import uk.org.openseizuredetector.utils.BackgroundTaskExecutor;
import uk.org.openseizuredetector.utils.OsdUtil;

/**
 * LocalEventQuerier encapsulates all local database query operations.
 *
 * Responsibilities:
 * - Query events and datapoints with various filters
 * - Export data to CSV files
 * - Count records in database
 * - Find nearest records by date
 */
public class LocalEventQuerier {
    private static final String TAG = "LocalEventQuerier";

    private Context mContext;
    private OsdUtil mUtil;
    private LogRepository mRepository;

    // Database table names
    private static final String DP_TABLE_NAME = "datapoints";
    private static final String EVENTS_TABLE_NAME = "events";

    /**
     * Callback interface for cursor operations
     */
    public interface CursorCallback {
        void accept(Cursor retVal);
    }

    /**
     * Constructor
     *
     * @param context    Android context
     * @param util       OsdUtil instance
     * @param repository LogRepository instance
     */
    public LocalEventQuerier(Context context, OsdUtil util, LogRepository repository) {
        mContext = context;
        mUtil = util;
        mRepository = repository;
    }

    /**
     * Get a list of events from the local database
     */
    public boolean getEventsList(boolean includeWarnings,
                                 LogManager.ArrayListCallback callback) {
        Log.v(TAG, "getEventsList - includeWarnings=" + includeWarnings);
        ArrayList<HashMap<String, String>> eventsList = new ArrayList<>();

        String[] whereArgs = getEventWhereArgs(includeWarnings);
        String whereClause = getEventWhereClause(includeWarnings);
        String[] columns = {"*"};

        executeSelectQuery(EVENTS_TABLE_NAME, columns, whereClause, whereArgs,
                null, null, "dataTime DESC", (Cursor cursor) -> {
            Log.v(TAG, "getEventsList - returned " + cursor);
            try {
                if (cursor != null) {
                    Log.v(TAG, "getEventsList - returned " + cursor.getCount() + " records");
                    while (!cursor.isAfterLast()) {
                        HashMap<String, String> event = new HashMap<>();
                        event.put("dataTime", cursor.getString(cursor.getColumnIndex("dataTime")));
                        int status = cursor.getInt(cursor.getColumnIndex("status"));
                        String statusStr = mUtil.alarmStatusToString(status);
                        event.put("status", statusStr);
                        event.put("uploaded", cursor.getString(cursor.getColumnIndex("uploaded")));
                        event.put("type", cursor.getString(cursor.getColumnIndex("type")));
                        event.put("subType", cursor.getString(cursor.getColumnIndex("subType")));

                        if (cursor.getColumnIndex("alarmCause") != -1) {
                            event.put("alarmCause", cursor.getString(cursor.getColumnIndex("alarmCause")));
                        }

                        eventsList.add(event);
                        cursor.moveToNext();
                    }
                }
                callback.accept(eventsList);
            } finally {
                if (cursor != null) {
                    try {
                        cursor.close();
                    } catch (Exception e) {
                        Log.w(TAG, "getEventsList: error closing cursor: " + e.toString());
                    }
                }
            }
        });
        return true;
    }

    /**
     * Get count of events in the local database
     */
    public boolean getLocalEventsCount(boolean includeWarnings,
                                       WebApiConnection.LongCallback callback) {
        Log.v(TAG, "getLocalEventsCount - includeWarnings=" + includeWarnings);

        String[] whereArgs = getEventWhereArgs(includeWarnings);
        String whereClause = getEventWhereClause(includeWarnings);
        String[] columns = {"*"};

        executeSelectQuery(EVENTS_TABLE_NAME, columns, whereClause, whereArgs,
                null, null, null, (Cursor cursor) -> {
            Long eventCount = 0L;
            try {
                if (cursor != null) {
                    eventCount = (long) cursor.getCount();
                    Log.v(TAG, "getLocalEventsCount - returned " + eventCount + " records");
                }
                callback.accept(eventCount);
            } finally {
                if (cursor != null) {
                    try {
                        cursor.close();
                    } catch (Exception e) {
                        Log.w(TAG, "getLocalEventsCount: error closing cursor: " + e.toString());
                    }
                }
            }
        });
        return true;
    }

    /**
     * Get count of datapoints in the local database
     */
    public boolean getLocalDatapointsCount(WebApiConnection.LongCallback callback) {
        Log.v(TAG, "getLocalDatapointsCount");
        String[] columns = {"*"};

        executeSelectQuery(DP_TABLE_NAME, columns, null, null,
                null, null, null, (Cursor cursor) -> {
            Long datapointCount = 0L;
            try {
                if (cursor != null) {
                    datapointCount = (long) cursor.getCount();
                    Log.v(TAG, "getLocalDatapointsCount - returned " + datapointCount + " records");
                }
                callback.accept(datapointCount);
            } finally {
                if (cursor != null) {
                    try {
                        cursor.close();
                    } catch (Exception e) {
                        Log.w(TAG, "getLocalDatapointsCount: error closing cursor: " + e.toString());
                    }
                }
            }
        });
        return true;
    }

    /**
     * Find the datapoint nearest to a given date/time
     */
    public boolean getNearestDatapointToDate(String dateStr,
                                             WebApiConnection.LongCallback callback) {
        Log.v(TAG, "getNearestDatapointToDate - dateStr=" + dateStr);
        String[] columns = {"*", "(julianday(dataTime)-julianday(datetime('" + dateStr + "'))) as ddiff"};
        String orderByStr = "ABS(ddiff) asc";

        executeSelectQuery(DP_TABLE_NAME, columns, null, null,
                null, null, orderByStr, (Cursor cursor) -> {
            long recordId = -1;
            try {
                if (cursor != null) {
                    Log.v(TAG, "getNearestDatapointToDate - returned " + cursor.getCount() + " records");
                    cursor.moveToFirst();
                    if (cursor.getCount() > 0) {
                        recordId = cursor.getLong(0);
                        Log.d(TAG, "getNearestDatapointToDate(): id=" + recordId);
                    }
                }
                callback.accept(recordId);
            } finally {
                if (cursor != null) {
                    try {
                        cursor.close();
                    } catch (Exception e) {
                        Log.w(TAG, "getNearestDatapointToDate: error closing cursor: " + e.toString());
                    }
                }
            }
        });
        return true;
    }

    /**
     * Export datapoints to CSV file
     */
    public void exportToCsvFile(Date endDate, double duration, Uri uri,
                                LogManager.BooleanCallback callback) {
        Log.v(TAG, "exportToCsvFile(): uri=" + uri.toString());
        executeExportData(endDate, duration, uri, (boolean retVal) -> {
            Log.v(TAG, "exportToCsvFile - returned " + retVal);
            callback.accept(retVal);
        });
    }

    /**
     * Helper: Build WHERE clause for event status filter
     */
    private String getEventWhereClause(boolean includeWarnings) {
        if (includeWarnings) {
            return "Status in (?, ?, ?, ?, ?)";
        } else {
            return "Status in (?, ?, ?, ?)";
        }
    }

    /**
     * Helper: Build WHERE clause arguments for event status filter
     */
    private String[] getEventWhereArgs(boolean includeWarnings) {
        if (includeWarnings) {
            return new String[]{"1", "2", "3", "5", "6"};
        } else {
            return new String[]{"2", "3", "5", "6"};
        }
    }

    /**
     * Execute a database SELECT query on background thread
     */
    private static void executeSelectQuery(String table, String[] columns, String selection,
                                           String[] selectionArgs, String groupBy, String having,
                                           String orderBy, CursorCallback callback) {
        BackgroundTaskExecutor.execute(
            () -> {
                Log.v(TAG, "SelectQuery: table=" + table + ", columns=" + Arrays.toString(columns)
                        + ", selection=" + selection);

                try {
                    Cursor resultSet = LogRepository.getDatabase().query(table, columns, selection,
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

    /**
     * Export datapoints to CSV file on background thread
     */
    private void executeExportData(Date endDate, double duration, Uri uri,
                                   LogManager.BooleanCallback callback) {
        Log.i(TAG, "executeExportData()");

        BackgroundTaskExecutor.execute(
            () -> {
                Log.v(TAG, "ExportData.doInBackground()");
                long endDateMillis = endDate.getTime();
                long durationMillis = (long) (duration * 3600. * 1000);
                long startDateMillis = endDateMillis - durationMillis;

                DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                String sDateStr = dateFormat.format(new Date(startDateMillis));
                String eDateStr = dateFormat.format(new Date(endDateMillis));
                Log.v(TAG, "ExportData - sDateStr=" + sDateStr + " eDateStr=" + eDateStr);

                String[] columns = {"*"};
                String whereClause = "DataTime>? AND DataTime<?";
                String[] whereArgs = {sDateStr, eDateStr};

                try {
                    Cursor cursor = LogRepository.getDatabase().query(DP_TABLE_NAME, columns, whereClause,
                            whereArgs, null, null, "dataTime DESC");
                    cursor.moveToFirst();

                    Log.v(TAG, "ExportData - returned " + cursor);
                    if (cursor != null) {
                        Log.d(TAG, "ExportData - query complete - writing to file....");
                        try {
                            android.content.ContentResolver cr = mContext.getContentResolver();
                            android.os.ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "w");
                            FileOutputStream fileOutputStream = new FileOutputStream(pfd.getFileDescriptor());
                            fileOutputStream.write(("# dataTime, alarmState, hr, o2sat, accel*125\n").getBytes());
                            int nRec = writeDatapointsToFile(cursor, fileOutputStream);
                            fileOutputStream.close();
                            pfd.close();
                            Log.d(TAG, "ExportData - file written ok");
                            return true;
                        } catch (FileNotFoundException e) {
                            Log.e(TAG, "ExportData - FileNotFoundException: " + e.toString());
                            BackgroundTaskExecutor.runOnMainThread(() ->
                                mUtil.showToast(mContext.getString(R.string.error_exporting_data))
                            );
                            return false;
                        } catch (IOException e) {
                            Log.e(TAG, "ExportData - IOException: " + e.toString());
                            BackgroundTaskExecutor.runOnMainThread(() ->
                                mUtil.showToast(mContext.getString(R.string.error_exporting_data))
                            );
                            return false;
                        }
                    } else {
                        Log.w(TAG, "ExportData - returned null result");
                        return false;
                    }
                } catch (SQLException e) {
                    Log.e(TAG, "ExportData: Error selecting Data: " + e.toString());
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

    /**
     * Write datapoints from cursor to CSV file
     */
    private int writeDatapointsToFile(Cursor c, FileOutputStream fileOutputStream) {
        Log.v(TAG, "writeDatapointsToFile()");
        int nRec = 0;

        try {
            Log.d(TAG, "writeDatapointsToFile() - writing query result to csv file....");
            while (c.moveToNext()) {
                nRec += 1;
                String dataJsonStr = c.getString(3);   // dataJSON is index 3
                JSONObject dataJsonObj = new JSONObject(dataJsonStr);
                JSONArray rawDataArr = dataJsonObj.getJSONArray("rawData");

                try {
                    fileOutputStream.write(c.getString(1).getBytes(StandardCharsets.UTF_8));
                    fileOutputStream.write(", ".getBytes(StandardCharsets.UTF_8));
                    fileOutputStream.write(dataJsonObj.getString("alarmState").getBytes(StandardCharsets.UTF_8));
                    fileOutputStream.write(", ".getBytes(StandardCharsets.UTF_8));
                    fileOutputStream.write(dataJsonObj.getString("hr").getBytes(StandardCharsets.UTF_8));
                    fileOutputStream.write(", ".getBytes(StandardCharsets.UTF_8));
                    fileOutputStream.write(dataJsonObj.getString("o2Sat").getBytes(StandardCharsets.UTF_8));

                    for (int j = 0; j < 125; j++) {
                        fileOutputStream.write(", ".getBytes(StandardCharsets.UTF_8));
                        fileOutputStream.write(rawDataArr.getString(j).getBytes(StandardCharsets.UTF_8));
                    }
                    fileOutputStream.write("\n".getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    Log.e(TAG, "writeDatapointsToFile() - ERROR Writing File: " + e.toString());
                    new Handler(android.os.Looper.getMainLooper()).post(() ->
                        mUtil.showToast("ERROR WRITING FILE")
                    );
                    return -1;
                }
            }

            Log.d(TAG, "writeDatapointsToFile() - data written to file ok");
            final int finalNRec = nRec;
            new Handler(android.os.Looper.getMainLooper()).post(() ->
                mUtil.showToast(mContext.getString(R.string.data_exported_ok) + " " + finalNRec)
            );
            return nRec;

        } catch (JSONException | NullPointerException e) {
            Log.e(TAG, "writeDatapointsToFile() - JSONException: " + e.toString());
            new Handler(android.os.Looper.getMainLooper()).post(() ->
                mUtil.showToast(mContext.getString(R.string.error_exporting_data))
            );
            return -1;
        }
    }
}

