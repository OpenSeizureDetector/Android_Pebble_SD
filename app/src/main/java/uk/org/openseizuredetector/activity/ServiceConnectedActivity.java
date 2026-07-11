package uk.org.openseizuredetector.activity;

import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import uk.org.openseizuredetector.R;
import uk.org.openseizuredetector.client.SdServiceConnection;
import uk.org.openseizuredetector.data.logging.Log;
import uk.org.openseizuredetector.utils.OsdUtil;

/**
 * Base Activity for activities that need to connect to the OpenSeizureDetector service.
 * 
 * This class provides:
 * - Automatic service connection lifecycle management
 * - Null safety checks for service objects (mLm, mWac)
 * - Graceful shutdown handling (prevents NPE when service stops)
 * - Lifecycle guards (isFinishing/isDestroyed checks)
 * - Consistent system bar configuration
 * 
 * Subclasses must implement:
 * - onServiceConnected(LogManager): Called when service connection is established
 * 
 * Subclasses can optionally override:
 * - onServiceConnectionFailed(): Called when service connection fails
 * - shouldFinishOnServerStop(): Return false to keep activity alive when server stops
 * 
 * Usage:
 * 1. Extend ServiceConnectedActivity
 * 2. Implement onServiceConnected(LogManager) to initialize your UI
 * 3. Use mConnection, mLm, mUtil as needed
 * 4. Call super.onStart() and super.onStop() if you override them
 */
public abstract class ServiceConnectedActivity extends AppCompatActivity {
    private static final String TAG = "ServiceConnectedActivity";
    
    // Protected fields available to subclasses
    protected SdServiceConnection mConnection;
    protected OsdUtil mUtil;
    protected final Handler serverStatusHandler = new Handler(Looper.getMainLooper());
    
    // Connection retry configuration
    private static final int CONNECTION_RETRY_DELAY_MS = 100;
    private static final int MAX_CONNECTION_RETRIES = 50; // 5 seconds total
    private int mConnectionRetries = 0;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Initialize utility and connection
        mUtil = new OsdUtil(getApplicationContext(), serverStatusHandler);
        mConnection = new SdServiceConnection(getApplicationContext());
        
        // Configure system bars
        configureSystemBars();
    }
    
    @Override
    protected void onStart() {
        super.onStart();
        Log.v(TAG, getClass().getSimpleName() + ".onStart()");
        
        // Check if server is running before trying to bind
        if (!mUtil.isServerRunning()) {
            Log.w(TAG, "onStart() - Server not running, finishing activity");
            mUtil.showToast(getString(R.string.error_server_not_running));
            finish();
            return;
        }
        
        // Reset retry counter
        mConnectionRetries = 0;
        
        // Bind to service and wait for connection
        mUtil.bindToServer(getApplicationContext(), mConnection);
        waitForConnection();
    }
    
    @Override
    protected void onStop() {
        Log.v(TAG, getClass().getSimpleName() + ".onStop()");
        super.onStop();
        
        // Unbind from service
        if (mConnection != null) {
            mUtil.unbindFromServer(getApplicationContext(), mConnection);
        }
    }
    
    /**
     * Waits for service connection to be established, then calls initialiseServiceConnection.
     * Implements retry logic with lifecycle checks.
     */
    private void waitForConnection() {
        // Check if activity is being destroyed or server is shutting down
        if (isFinishing() || isDestroyed()) {
            Log.w(TAG, "waitForConnection - Activity finishing, aborting connection attempt");
            return;
        }
        
        if (!mUtil.isServerRunning()) {
            Log.w(TAG, "waitForConnection - Server stopped, finishing activity");
            if (shouldFinishOnServerStop()) {
                finish();
            }
            return;
        }
        
        if (mConnection.mBound) {
            Log.v(TAG, "waitForConnection - Bound!");
            initialiseServiceConnection();
        } else {
            mConnectionRetries++;
            if (mConnectionRetries >= MAX_CONNECTION_RETRIES) {
                Log.e(TAG, "waitForConnection - Max retries reached, giving up");
                onServiceConnectionFailed();
                return;
            }
            
            Log.v(TAG, "waitForConnection - waiting... (attempt " + mConnectionRetries + "/" + MAX_CONNECTION_RETRIES + ")");
            new Handler(Looper.getMainLooper()).postDelayed(this::waitForConnection, CONNECTION_RETRY_DELAY_MS);
        }
    }
    
    /**
     * Initializes the service connection with comprehensive safety checks.
     * Calls onServiceConnected when ready, or handles failures gracefully.
     */
    private void initialiseServiceConnection() {
        // Check if activity is being destroyed
        if (isFinishing() || isDestroyed()) {
            Log.w(TAG, "initialiseServiceConnection() - Activity finishing, aborting");
            return;
        }
        
        // Check if connection and server are valid
        if (mConnection == null || !mConnection.mBound || mConnection.mSdServer == null) {
            Log.w(TAG, "initialiseServiceConnection() - Service not yet connected");
            
            // Check if we should retry or give up
            if (!mUtil.isServerRunning()) {
                Log.w(TAG, "initialiseServiceConnection() - Server stopped, finishing activity");
                if (shouldFinishOnServerStop()) {
                    finish();
                }
                return;
            }
            
            mConnectionRetries++;
            if (mConnectionRetries >= MAX_CONNECTION_RETRIES) {
                Log.e(TAG, "initialiseServiceConnection() - Max retries reached, giving up");
                onServiceConnectionFailed();
                return;
            }
            
            new Handler(Looper.getMainLooper()).postDelayed(this::initialiseServiceConnection, CONNECTION_RETRY_DELAY_MS);
            return;
        }
        
        // Get LogManager from service
        if (mConnection.mSdServer.mLm == null) {
            Log.e(TAG, "initialiseServiceConnection() - mLm is null, service may be shutting down");
            onServiceConnectionFailed();
            if (shouldFinishOnServerStop()) {
                finish();
            }
            return;
        }
        
        // Connection successful - notify subclass
        Log.d(TAG, "initialiseServiceConnection() - Success! Calling onServiceConnected()");
        onServiceConnected(mConnection.mSdServer.mLm);
    }
    
    /**
     * Called when service connection is successfully established and LogManager is available.
     * Subclasses should implement this to initialize their UI and start using the service.
     * 
     * @param logManager The LogManager instance from the service (guaranteed non-null)
     */
    protected abstract void onServiceConnected(uk.org.openseizuredetector.data.logging.LogManager logManager);
    
    /**
     * Called when service connection fails after retries.
     * Default implementation shows a toast and finishes the activity.
     * Subclasses can override to customize behavior.
     */
    protected void onServiceConnectionFailed() {
        Log.e(TAG, "onServiceConnectionFailed() - Unable to connect to service");
        mUtil.showToast(getString(R.string.error_failed_to_start_log_manager));
        finish();
    }
    
    /**
     * Determines whether the activity should finish when the server stops.
     * Default is true. Subclasses can override to keep the activity alive.
     * 
     * @return true if activity should finish when server stops
     */
    protected boolean shouldFinishOnServerStop() {
        return true;
    }
    
    /**
     * Configures the system bars to be transparent and content to extend edge-to-edge.
     * Uses correct light/dark mode for icons.
     * Subclasses can override to customize system bar behavior.
     */
    protected void configureSystemBars() {
        // Configure system bar appearance to be edge-to-edge
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
            WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            if (controller != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                boolean isLightMode = isLightTheme();
                controller.setAppearanceLightStatusBars(isLightMode);
                controller.setAppearanceLightNavigationBars(isLightMode);
            }
        }
    }
    
    /**
     * Check if the current theme is light mode.
     * Can be overridden by subclasses if they have specific needs.
     * 
     * @return true if in light theme mode
     */
    protected boolean isLightTheme() {
        int currentNightMode = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_NO;
    }
    
    /**
     * Helper method for subclasses to check if they should process callbacks/updates.
     * 
     * @return true if activity is in a valid state to process updates
     */
    protected boolean isActivityActive() {
        return !isFinishing() && !isDestroyed();
    }
}
