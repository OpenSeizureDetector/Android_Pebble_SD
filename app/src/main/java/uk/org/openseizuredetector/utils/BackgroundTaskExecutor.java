package uk.org.openseizuredetector.utils;

import android.os.Handler;
import android.os.Looper;
import uk.org.openseizuredetector.data.logging.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Utility class to replace deprecated AsyncTask.
 * Provides simple background task execution with main thread callbacks.
 *
 * Usage:
 * <pre>
 * BackgroundTaskExecutor.execute(
 *     () -> {
 *         // Background work here
 *         return downloadData();
 *     },
 *     new BackgroundTaskExecutor.Callback<Result>() {
 *         public void onSuccess(Result result) {
 *             // UI update here
 *         }
 *         public void onError(Exception e) {
 *             // Error handling here
 *         }
 *     }
 * );
 * </pre>
 */
public class BackgroundTaskExecutor {
    private static final String TAG = "BackgroundTaskExecutor";
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * Interface for background work that returns a result
     * @param <T> The type of result returned
     */
    public interface BackgroundTask<T> {
        T doInBackground() throws Exception;
    }

    /**
     * Interface for handling task results on the main thread
     * @param <T> The type of result
     */
    public interface Callback<T> {
        void onSuccess(T result);
        void onError(Exception e);
    }

    /**
     * Interface for background work that doesn't return a result (fire and forget)
     */
    public interface VoidBackgroundTask {
        void doInBackground() throws Exception;
    }

    /**
     * Callback for void tasks
     */
    public interface VoidCallback {
        void onSuccess();
        void onError(Exception e);
    }

    /**
     * Execute a background task with a result
     *
     * @param task The background work to perform
     * @param callback Called on main thread with result or error
     * @param <T> The type of result
     */
    public static <T> void execute(BackgroundTask<T> task, Callback<T> callback) {
        executor.execute(() -> {
            try {
                T result = task.doInBackground();
                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onSuccess(result);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Background task failed", e);
                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onError(e);
                    }
                });
            }
        });
    }

    /**
     * Execute a void background task (fire and forget)
     *
     * @param task The background work to perform
     * @param callback Called on main thread when complete or error
     */
    public static void execute(VoidBackgroundTask task, VoidCallback callback) {
        executor.execute(() -> {
            try {
                task.doInBackground();
                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onSuccess();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Background task failed", e);
                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onError(e);
                    }
                });
            }
        });
    }

    /**
     * Execute a background task without a callback (true fire and forget)
     *
     * @param task The background work to perform
     * @param <T> The type of result (ignored)
     */
    public static <T> void executeAndForget(BackgroundTask<T> task) {
        executor.execute(() -> {
            try {
                task.doInBackground();
            } catch (Exception e) {
                Log.e(TAG, "Background task failed", e);
            }
        });
    }

    /**
     * Execute a void background task without a callback
     *
     * @param task The background work to perform
     */
    public static void executeAndForget(VoidBackgroundTask task) {
        executor.execute(() -> {
            try {
                task.doInBackground();
            } catch (Exception e) {
                Log.e(TAG, "Background task failed", e);
            }
        });
    }

    /**
     * Post a task to run on the main thread
     *
     * @param runnable The work to perform on main thread
     */
    public static void runOnMainThread(Runnable runnable) {
        mainHandler.post(runnable);
    }

    /**
     * Shutdown the executor (call when app is closing)
     * Note: Normally not needed as executor can run for app lifetime
     */
    public static void shutdown() {
        executor.shutdown();
    }
}
