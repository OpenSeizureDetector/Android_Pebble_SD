/*
  Android_SD - Android host for Garmin or Pebble watch based seizure detectors.
  See http://openseizuredetector.org for more information.

  Copyright Graham Jones, 2025.

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
package uk.org.openseizuredetector.utils;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import uk.org.openseizuredetector.data.logging.Log;
import uk.org.openseizuredetector.data.logging.LogRepository;

import uk.org.openseizuredetector.data.SdData;
import uk.org.openseizuredetector.data.SdDataHistory;

/**
 * CircBufHistoryLoader handles loading persisted CircBuf history data from the local
 * SQLite database and restoring it into SdData objects.
 *
 * This is called on app startup to restore graph history from the most recent datapoint
 * in the database, allowing the graphs to show the last 10 minutes of operation data
 * even after an app restart.
 *
 * All database operations are performed on background threads to avoid UI blocking.
 */
public class CircBufHistoryLoader {
    private static final String TAG = "CircBufHistoryLoader";
    private static final String DATAPOINTS_TABLE = "datapoints";

    /**
     * Callback interface for notifying when history loading is complete
     */
    public interface HistoryLoadCallback {
        /**
         * Called when history loading completes successfully
         * @param success true if history was loaded, false if loading failed or no history was available
         */
        void onHistoryLoaded(boolean success);
    }

    /**
     * Load the most recent CircBuf history from the database and restore it into sdData.
     * DEPRECATED: Use loadHistoryFromDatabase(SdDataHistory, ...) instead
     * This is typically called shortly after SdServer creates the SdData object.
     *
     * @param database The SQLiteDatabase instance to query
     * @param sdData The SdData object to populate with restored history
     * @param callback Called when loading completes (may be null)
     */
    /*
    public static void loadHistoryFromDatabase(SQLiteDatabase database, SdData sdData, HistoryLoadCallback callback) {
        Log.d(TAG, "loadHistoryFromDatabase: Starting background load of history");

        if (database == null || sdData == null) {
            Log.e(TAG, "loadHistoryFromDatabase: database or sdData is null");
            if (callback != null) {
                callback.onHistoryLoaded(false);
            }
            return;
        }

        // Execute on background thread
        BackgroundTaskExecutor.execute(
            () -> {
                return performHistoryLoad(database, sdData);
            },
            new BackgroundTaskExecutor.Callback<Boolean>() {
                @Override
                public void onSuccess(Boolean result) {
                    Log.d(TAG, "loadHistoryFromDatabase: History load completed, success=" + result);
                    if (callback != null) {
                        callback.onHistoryLoaded(result);
                    }
                }

                @Override
                public void onError(Exception e) {
                    Log.e(TAG, "loadHistoryFromDatabase: Error loading history", e);
                    if (callback != null) {
                        callback.onHistoryLoaded(false);
                    }
                }
            }
        );
    }
    */

    /**
     * Load history into SdDataHistory (persistent history buffers).
     * This is the preferred method - loads into a dedicated SdDataHistory object
     * that persists while SdData objects come and go.
     *
     * @param sdDataHistory The SdDataHistory object to populate with restored history
     * @param callback Called when loading completes (may be null)
     */
    public static void loadHistoryFromDatabase(SdDataHistory sdDataHistory, HistoryLoadCallback callback) {
        Log.i(TAG, "loadHistoryFromDatabase(SdDataHistory): Loading CircBuf history from database");

        if (sdDataHistory == null) {
            Log.e(TAG, "loadHistoryFromDatabase(SdDataHistory): sdDataHistory is null!");
            if (callback != null) {
                callback.onHistoryLoaded(false);
            }
            return;
        }

        try {
            SQLiteDatabase database = LogRepository.getDatabase();

            if (database == null) {
                Log.e(TAG, "loadHistoryFromDatabase(SdDataHistory): LogRepository.getDatabase() returned null");
                if (callback != null) {
                    callback.onHistoryLoaded(false);
                }
                return;
            }

            if (!database.isOpen()) {
                Log.e(TAG, "loadHistoryFromDatabase(SdDataHistory): Database is not open");
                if (callback != null) {
                    callback.onHistoryLoaded(false);
                }
                return;
            }

            Log.d(TAG, "loadHistoryFromDatabase(SdDataHistory): Database valid, loading history on background thread");
            performHistoryLoadIntoHistory(database, sdDataHistory, callback);
        } catch (Exception e) {
            Log.e(TAG, "loadHistoryFromDatabase(SdDataHistory): Exception: " + e.getMessage());
            e.printStackTrace();
            if (callback != null) {
                callback.onHistoryLoaded(false);
            }
        }
    }


    /**
     * Internal method to perform history loading into SdDataHistory
     */
    private static void performHistoryLoadIntoHistory(SQLiteDatabase database, SdDataHistory sdDataHistory, final HistoryLoadCallback callback) {
        // Execute on background thread
        BackgroundTaskExecutor.execute(
            () -> {
                return performHistoryLoadIntoHistoryImpl(database, sdDataHistory);
            },
            new BackgroundTaskExecutor.Callback<Boolean>() {
                @Override
                public void onSuccess(Boolean result) {
                    Log.d(TAG, "performHistoryLoadIntoHistory: completed, success=" + result);
                    if (callback != null) {
                        callback.onHistoryLoaded(result);
                    }
                }

                @Override
                public void onError(Exception e) {
                    Log.e(TAG, "performHistoryLoadIntoHistory: Error: " + e.getMessage());
                    if (callback != null) {
                        callback.onHistoryLoaded(false);
                    }
                }
            }
        );
    }

    /**
     * Actual implementation of history loading into SdDataHistory on background thread
     */
    private static Boolean performHistoryLoadIntoHistoryImpl(SQLiteDatabase database, SdDataHistory sdDataHistory) {
        Cursor cursor = null;
        try {
            // Check if table has records
            try {
                Cursor countCursor = database.rawQuery("SELECT COUNT(*) FROM " + DATAPOINTS_TABLE, null);
                countCursor.moveToFirst();
                int totalRecords = countCursor.getInt(0);
                countCursor.close();
                Log.i(TAG, "performHistoryLoadIntoHistoryImpl: Found " + totalRecords + " total records");

                if (totalRecords == 0) {
                    Log.i(TAG, "performHistoryLoadIntoHistoryImpl: No records found (first run)");
                    return false;
                }
            } catch (Exception e) {
                Log.e(TAG, "performHistoryLoadIntoHistoryImpl: Error counting records: " + e.getMessage());
                return false;
            }

            // Check how many records have history data
            try {
                Cursor historyCountCursor = database.rawQuery(
                    "SELECT COUNT(*) FROM " + DATAPOINTS_TABLE + " WHERE watchBattHist IS NOT NULL", null);
                historyCountCursor.moveToFirst();
                int recordsWithHistory = historyCountCursor.getInt(0);
                historyCountCursor.close();

                if (recordsWithHistory == 0) {
                    Log.i(TAG, "performHistoryLoadIntoHistoryImpl: No records with history data found");
                    return false;
                }
            } catch (Exception e) {
                Log.e(TAG, "performHistoryLoadIntoHistoryImpl: Error counting history records: " + e.getMessage());
                return false;
            }

            // Query for most recent datapoint with history
            cursor = database.query(
                DATAPOINTS_TABLE,
                new String[]{"watchBattHist", "phoneBattHist", "signalStrengthHist",
                             "pseudSeizureHist", "accelMagStdDevHist", "hrHist"},
                "watchBattHist IS NOT NULL",
                null, null, null,
                "dataTime DESC",
                "1"
            );

            if (cursor == null || !cursor.moveToFirst()) {
                Log.i(TAG, "performHistoryLoadIntoHistoryImpl: No history data found");
                return false;
            }

            boolean anyLoaded = false;

            // Load each history buffer into SdDataHistory
            try {
                String watchBattJson = cursor.getString(cursor.getColumnIndex("watchBattHist"));
                if (watchBattJson != null && !watchBattJson.isEmpty()) {
                    CircBuf tempBuf = CircBufPersistenceManager.deserializeCircBuf(watchBattJson, 24 * 3600 / 5, -1);
                    double[] vals = tempBuf.getVals();
                    for (double v : vals) {
                        sdDataHistory.watchBattBuff.add(v);
                    }
                    Log.d(TAG, "performHistoryLoadIntoHistoryImpl: Loaded " + vals.length + " watch battery values");
                    anyLoaded = true;
                }
            } catch (Exception e) {
                Log.w(TAG, "performHistoryLoadIntoHistoryImpl: Failed to load watch battery: " + e.getMessage());
            }

            try {
                String phoneBattJson = cursor.getString(cursor.getColumnIndex("phoneBattHist"));
                if (phoneBattJson != null && !phoneBattJson.isEmpty()) {
                    CircBuf tempBuf = CircBufPersistenceManager.deserializeCircBuf(phoneBattJson, 24 * 3600 / 5, -1);
                    double[] vals = tempBuf.getVals();
                    for (double v : vals) {
                        sdDataHistory.phoneBattBuff.add(v);
                    }
                    Log.d(TAG, "performHistoryLoadIntoHistoryImpl: Loaded " + vals.length + " phone battery values");
                    anyLoaded = true;
                }
            } catch (Exception e) {
                Log.w(TAG, "performHistoryLoadIntoHistoryImpl: Failed to load phone battery: " + e.getMessage());
            }

            try {
                String signalJson = cursor.getString(cursor.getColumnIndex("signalStrengthHist"));
                if (signalJson != null && !signalJson.isEmpty()) {
                    CircBuf tempBuf = CircBufPersistenceManager.deserializeCircBuf(signalJson, 10 * 60 / 5, -1);
                    double[] vals = tempBuf.getVals();
                    for (double v : vals) {
                        sdDataHistory.watchSignalStrengthBuff.add(v);
                    }
                    Log.d(TAG, "performHistoryLoadIntoHistoryImpl: Loaded " + vals.length + " signal strength values");
                    anyLoaded = true;
                }
            } catch (Exception e) {
                Log.w(TAG, "performHistoryLoadIntoHistoryImpl: Failed to load signal strength: " + e.getMessage());
            }

            try {
                String seizureJson = cursor.getString(cursor.getColumnIndex("pseudSeizureHist"));
                if (seizureJson != null && !seizureJson.isEmpty()) {
                    CircBuf tempBuf = CircBufPersistenceManager.deserializeCircBuf(seizureJson, 120, -1.0);
                    double[] vals = tempBuf.getVals();
                    for (double v : vals) {
                        sdDataHistory.mPseizureHistBuf.add(v);
                    }
                    Log.d(TAG, "performHistoryLoadIntoHistoryImpl: Loaded " + vals.length + " seizure probability values");
                    anyLoaded = true;
                }
            } catch (Exception e) {
                Log.w(TAG, "performHistoryLoadIntoHistoryImpl: Failed to load seizure probability: " + e.getMessage());
            }

            try {
                String accelJson = cursor.getString(cursor.getColumnIndex("accelMagStdDevHist"));
                if (accelJson != null && !accelJson.isEmpty()) {
                    CircBuf tempBuf = CircBufPersistenceManager.deserializeCircBuf(accelJson, 120, -1.0);
                    double[] vals = tempBuf.getVals();
                    for (double v : vals) {
                        sdDataHistory.mAccelMagStdDevHistBuf.add(v);
                    }
                    Log.d(TAG, "performHistoryLoadIntoHistoryImpl: Loaded " + vals.length + " accel std dev values");
                    anyLoaded = true;
                }
            } catch (Exception e) {
                Log.w(TAG, "performHistoryLoadIntoHistoryImpl: Failed to load accel std dev: " + e.getMessage());
            }

            try {
                String hrJson = cursor.getString(cursor.getColumnIndex("hrHist"));
                if (hrJson != null && !hrJson.isEmpty()) {
                    CircBuf tempBuf = CircBufPersistenceManager.deserializeCircBuf(hrJson, 120, -1.0);
                    double[] vals = tempBuf.getVals();
                    for (double v : vals) {
                        sdDataHistory.mHrHistBuf.add(v);
                    }
                    Log.d(TAG, "performHistoryLoadIntoHistoryImpl: Loaded " + vals.length + " heart rate history values");
                    anyLoaded = true;
                }
            } catch (Exception e) {
                Log.w(TAG, "performHistoryLoadIntoHistoryImpl: Failed to load heart rate history: " + e.getMessage());
            }

            if (anyLoaded) {
                Log.i(TAG, "performHistoryLoadIntoHistoryImpl: Successfully loaded history from database");
                return true;
            } else {
                Log.i(TAG, "performHistoryLoadIntoHistoryImpl: No valid history data found");
                return false;
            }

        } catch (Exception e) {
            Log.e(TAG, "performHistoryLoadIntoHistoryImpl: Exception: " + e.getMessage());
            e.printStackTrace();
            return false;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }
}
