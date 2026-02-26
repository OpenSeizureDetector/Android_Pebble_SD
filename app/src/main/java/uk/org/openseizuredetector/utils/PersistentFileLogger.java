/*
  OpenSeizureDetector - Persistent File Logger

  Logs to daily rotating files with 7-day retention.

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

package uk.org.openseizuredetector.utils;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Thread-safe file logger with daily rotation and 7-day retention.
 * Logs are written to external files directory with immediate flush.
 */
public class PersistentFileLogger {
    private static final String TAG = "PersistentFileLogger";
    private static final String LOG_PREFIX = "osd_log_";
    private static final String LOG_SUFFIX = ".txt";
    private static final int RETENTION_DAYS = 7;

    private final Context mContext;
    private final SimpleDateFormat mDateFormat;
    private final SimpleDateFormat mTimestampFormat;
    private FileWriter mWriter;
    private String mCurrentLogDate;
    private File mLogDir;

    public PersistentFileLogger(Context context) {
        mContext = context;
        mDateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        mTimestampFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
        mCurrentLogDate = "";

        // Get external files directory (survives app uninstall on some devices)
        mLogDir = context.getExternalFilesDir("logs");
        if (mLogDir == null) {
            // Fallback to internal files directory
            mLogDir = new File(context.getFilesDir(), "logs");
        }

        if (!mLogDir.exists()) {
            if (!mLogDir.mkdirs()) {
                Log.e(TAG, "Failed to create log directory: " + mLogDir.getAbsolutePath());
            }
        }

        Log.i(TAG, "PersistentFileLogger initialized. Log directory: " + mLogDir.getAbsolutePath());

        // Clean up old logs on init
        deleteOldLogs();
    }

    /**
     * Log a message with INFO level
     */
    public synchronized void log(String message) {
        log("INFO", message);
    }

    /**
     * Log a message with specified level
     */
    public synchronized void log(String level, String message) {
        try {
            ensureCurrentLogFile();

            if (mWriter != null) {
                String timestamp = mTimestampFormat.format(new Date());
                String threadName = Thread.currentThread().getName();

                mWriter.write(String.format("%s [%s] [%s] %s\n",
                    timestamp, level, threadName, message));
                mWriter.flush(); // Immediate write
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to write to log file", e);
        }
    }

    /**
     * Log an error with exception
     */
    public synchronized void logError(String message, Throwable throwable) {
        log("ERROR", message + ": " + throwable.getMessage());

        // Log stack trace
        if (throwable != null) {
            for (StackTraceElement element : throwable.getStackTrace()) {
                log("ERROR", "  at " + element.toString());
            }
        }
    }

    /**
     * Ensure we have a writer for today's log file
     */
    private void ensureCurrentLogFile() throws IOException {
        String today = mDateFormat.format(new Date());

        // Check if we need to rotate to a new file
        if (!today.equals(mCurrentLogDate)) {
            closeCurrentWriter();

            mCurrentLogDate = today;
            File logFile = new File(mLogDir, LOG_PREFIX + today + LOG_SUFFIX);

            Log.i(TAG, "Opening log file: " + logFile.getAbsolutePath());
            mWriter = new FileWriter(logFile, true); // Append mode

            // Write header
            mWriter.write("\n=== Log started: " + mTimestampFormat.format(new Date()) + " ===\n");
            mWriter.flush();

            // Clean up old logs
            deleteOldLogs();
        }
    }

    /**
     * Close current writer
     */
    private void closeCurrentWriter() {
        if (mWriter != null) {
            try {
                mWriter.write("=== Log closed: " + mTimestampFormat.format(new Date()) + " ===\n\n");
                mWriter.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing log file", e);
            }
            mWriter = null;
        }
    }

    /**
     * Delete logs older than RETENTION_DAYS
     */
    private void deleteOldLogs() {
        try {
            File[] logFiles = mLogDir.listFiles((dir, name) ->
                name.startsWith(LOG_PREFIX) && name.endsWith(LOG_SUFFIX));

            if (logFiles == null || logFiles.length == 0) {
                return;
            }

            long cutoffTime = System.currentTimeMillis() - (RETENTION_DAYS * 24L * 60 * 60 * 1000);

            for (File file : logFiles) {
                if (file.lastModified() < cutoffTime) {
                    Log.i(TAG, "Deleting old log file: " + file.getName());
                    if (!file.delete()) {
                        Log.w(TAG, "Failed to delete old log file: " + file.getName());
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error deleting old logs", e);
        }
    }

    /**
     * Get list of all log files
     */
    public File[] getLogFiles() {
        File[] logFiles = mLogDir.listFiles((dir, name) ->
            name.startsWith(LOG_PREFIX) && name.endsWith(LOG_SUFFIX));
        return logFiles != null ? logFiles : new File[0];
    }

    public File getLogDir() {
        return mLogDir;
    }

    public File getDataLogDir() {
        File dir = mContext.getExternalFilesDir(null);
        if (dir == null) {
            dir = mContext.getFilesDir();
        }
        return dir;
    }

    public File[] getDataLogFiles() {
        File dir = getDataLogDir();
        File[] files = dir.listFiles();
        return files != null ? files : new File[0];
    }

    public File getDataLogFile(String fileName) {
        return new File(getDataLogDir(), fileName);
    }

    /**
     * Get the current log file path
     */
    public String getCurrentLogPath() {
        String today = mDateFormat.format(new Date());
        return new File(mLogDir, LOG_PREFIX + today + LOG_SUFFIX).getAbsolutePath();
    }

    /**
     * Close logger (call from onDestroy)
     */
    public synchronized void close() {
        closeCurrentWriter();
    }
}
