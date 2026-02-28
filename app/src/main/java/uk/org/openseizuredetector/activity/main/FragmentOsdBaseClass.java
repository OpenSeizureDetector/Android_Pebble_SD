package uk.org.openseizuredetector.activity.main;
import uk.org.openseizuredetector.R;

import uk.org.openseizuredetector.client.SdServiceConnection;
import uk.org.openseizuredetector.utils.OsdUtil;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.os.Bundle;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Timer;
import java.util.TimerTask;


public class FragmentOsdBaseClass extends Fragment {
    String TAG = "FragmentOsdBaseClass";
    Context mContext;
    OsdUtil mUtil;
    SdServiceConnection mConnection;
    final Handler updateUiHandler = new Handler(Looper.getMainLooper());
    Timer mUiTimer;
    protected View mRootView;
    private ScrollView mCachedScrollView;
    private long mLastDataTime = 0;
    private long mLastAlarmState = Long.MIN_VALUE;
    private boolean mLastAlarmStanding = false;
    private boolean mLastFallAlarmStanding = false;
    private long mLastThrottledUpdateMillis = 0;
    private boolean mLastBoundState = false;
    private static final long THROTTLED_UPDATE_MS = 30000;

    // Use transparent for OK state by default to allow theme background to show through
    protected int okColour = Color.TRANSPARENT;
    protected int warnColour = Color.MAGENTA;
    protected int alarmColour = Color.RED;
    
    // Default text colors that work with the theme
    protected int okTextColour = Color.BLACK; 
    protected int warnTextColour = Color.WHITE;
    protected int alarmTextColour = Color.BLACK;


    public FragmentOsdBaseClass() {
        // Required empty public constructor
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        Log.i(TAG, "onCreate()");
        mContext = getContext();
        mUtil = new OsdUtil(mContext, updateUiHandler);
        mConnection = new SdServiceConnection(mContext);

        updateColorsFromTheme();
    }

    /**
     * Updates the status and text colors based on the current theme.
     * This ensures high contrast in both light and dark modes.
     */
    protected void updateColorsFromTheme() {
        if (mContext == null) return;
        
        try {
            okColour = Color.TRANSPARENT;
            warnColour = ContextCompat.getColor(mContext, R.color.status_warning_background);
            alarmColour = ContextCompat.getColor(mContext, R.color.status_error_background);
            warnTextColour = ContextCompat.getColor(mContext, R.color.status_warning_text);
            alarmTextColour = ContextCompat.getColor(mContext, R.color.status_error_text);
            
            // Resolve primary text color robustly from the theme
            okTextColour = getThemeColor(android.R.attr.textColorPrimary);
        } catch (Exception e) {
            Log.w(TAG, "Error loading colors from theme: " + e.getMessage());
        }
    }

    /**
     * Helper to resolve a color attribute from the current theme.
     */
    protected int getThemeColor(int attr) {
        int[] attrs = new int[] { attr };
        TypedArray ta = mContext.obtainStyledAttributes(attrs);
        int color = ta.getColor(0, Color.BLACK);
        ta.recycle();
        return color;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_sd_data_viewer, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mRootView = view;
        // Find and cache the ScrollView on first use
        mCachedScrollView = findScrollView(mRootView);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateColorsFromTheme(); // Refresh colors in case theme changed
        if (mUtil.isServerRunning()) {
            mUtil.bindToServer(mContext, mConnection);
        }
        mUiTimer = new Timer();
        mUiTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                updateUiOnUiThread();
            }
        }, 0, 1000);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mUiTimer != null) {
            mUiTimer.cancel();
        }
        // Clear cached ScrollView on pause
        mCachedScrollView = null;
        // Reset dataTime so UI updates when fragment is resumed
        mLastDataTime = 0;
        mUtil.unbindFromServer(mContext, mConnection);
    }

    private void updateUiOnUiThread() {
        updateUiHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mContext != null && mRootView != null) {
                    try {
                        boolean isBound = mConnection.mBound && mConnection.mSdServer != null
                                && mConnection.mSdServer.mSdData != null;

                        // Always do lightweight updates (time, alarm state, buttons).
                        updateUiFast();

                        if (isBound) {
                            long currentDataTime = mConnection.mSdServer.mSdData.dataTimeMillis;
                            long currentAlarmState = mConnection.mSdServer.mSdData.alarmState;
                            boolean currentAlarmStanding = mConnection.mSdServer.mSdData.alarmStanding;
                            boolean currentFallAlarmStanding = mConnection.mSdServer.mSdData.fallAlarmStanding;

                            boolean hasNewData = (currentDataTime != mLastDataTime);
                            boolean alarmChanged = (currentAlarmState != mLastAlarmState)
                                    || (currentAlarmStanding != mLastAlarmStanding)
                                    || (currentFallAlarmStanding != mLastFallAlarmStanding);

                            if (hasNewData || alarmChanged || !mLastBoundState) {
                                // Use cached ScrollView to avoid repeated searches
                                if (mCachedScrollView == null) {
                                    mCachedScrollView = findScrollView(mRootView);
                                }

                                // Save scroll position before UI update
                                final int scrollY = mCachedScrollView != null ? mCachedScrollView.getScrollY() : 0;

                                updateUiOnNewData();

                                // Always restore scroll position after UI update to prevent reset
                                if (mCachedScrollView != null && scrollY > 0) {
                                    mCachedScrollView.post(() -> mCachedScrollView.scrollTo(0, scrollY));
                                }
                            }

                            long now = System.currentTimeMillis();
                            if (now - mLastThrottledUpdateMillis >= THROTTLED_UPDATE_MS) {
                                mLastThrottledUpdateMillis = now;
                                updateUiThrottled();
                            }

                            mLastDataTime = currentDataTime;
                            mLastAlarmState = currentAlarmState;
                            mLastAlarmStanding = currentAlarmStanding;
                            mLastFallAlarmStanding = currentFallAlarmStanding;
                        }

                        mLastBoundState = isBound;
                    } catch (Exception e) {
                        Log.e(TAG, "updateUiOnUiThread() - exception updating UI - " + e.getMessage());
                    }
                }
            }
        });
    }

    /**
     * Find the first ScrollView in the view hierarchy.
     * This searches the root view and its ancestors for a ScrollView.
     */
    private ScrollView findScrollView(View view) {
        if (view instanceof ScrollView) {
            return (ScrollView) view;
        }

        // Check if parent is a ScrollView
        if (view.getParent() instanceof ScrollView) {
            return (ScrollView) view.getParent();
        }

        // Search child views
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                ScrollView found = findScrollView(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    protected void updateUi() {
        TextView tv = (TextView) mRootView.findViewById(R.id.fragment_sddata_viewer_tv1);
        if (tv != null) {
            if (mConnection.mBound) {
                tv.setText("Bound to Server");
            } else {
                tv.setText("****NOT BOUND TO SERVER***");
            }
        }
    }

    protected void updateUiFast() {
        // Override in subclasses for 1-second lightweight updates.
    }

    protected void updateUiOnNewData() {
        // Default to legacy behavior for fragments that only implement updateUi().
        updateUi();
    }

    protected void updateUiThrottled() {
        // Override in subclasses for low-frequency heavy updates (e.g., graphs).
    }

}


