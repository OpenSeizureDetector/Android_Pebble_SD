package uk.org.openseizuredetector.activity.main;
import uk.org.openseizuredetector.R;

import uk.org.openseizuredetector.client.SdServiceConnection;
import uk.org.openseizuredetector.utils.OsdUtil;
import uk.org.openseizuredetector.utils.PreferenceUtils;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import android.os.Handler;
import android.os.Looper;
import uk.org.openseizuredetector.data.logging.Log;
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
    // Advanced Mode shows many fragments in one scrolling list, all of them resumed at once.
    // Only fragments that are actually on screen redraw (saves battery); see isOnScreen().
    private boolean mWasOnScreen = false;
    private final Rect mVisibleRect = new Rect();
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
        mWasOnScreen = false;
        mUtil.unbindFromServer(mContext, mConnection);
    }

    private void updateUiOnUiThread() {
        updateUiHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mContext != null && mRootView != null) {
                    try {
                        // Skip all drawing while this fragment is scrolled out of view. When it
                        // scrolls back in, force a full refresh instead of waiting up to 5 s
                        // for the next data period.
                        if (!isOnScreen()) {
                            mWasOnScreen = false;
                            return;
                        }
                        if (!mWasOnScreen) {
                            mWasOnScreen = true;
                            mLastDataTime = 0;
                            mLastThrottledUpdateMillis = 0;
                        }

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

    /**
     * True if any part of this fragment's view is currently visible on screen (i.e. not hidden
     * and not scrolled out of the visible part of the page).
     */
    protected boolean isOnScreen() {
        return mRootView != null && mRootView.isShown()
                && mRootView.getGlobalVisibleRect(mVisibleRect);
    }

    /**
     * Advanced Mode option "graphs over 10 minutes". True: graphs show the history of the last
     * 10 minutes. False (default): graphs show "real time" data, i.e. the latest 5 second period.
     */
    protected boolean isHistoryMode() {
        if (mContext == null) {
            return false;
        }
        return AdvancedSections.isHistoryMode(PreferenceManager.getDefaultSharedPreferences(mContext));
    }

    /**
     * Used by the algorithm fragments. If the algorithm is not selected by the user (it is only
     * shown because of the Advanced Mode option "show all algorithms") the fragment's content
     * (view R.id.alg_content) is hidden and a note (view R.id.alg_inactive_tv) is shown instead.
     *
     * @return true if the algorithm is active and the fragment should draw its normal content.
     */
    protected boolean applyAlgorithmActiveState(boolean active) {
        return applyAlgorithmState(active, false);
    }

    /**
     * As applyAlgorithmActiveState(), for the algorithms that can run in "display only" mode
     * (OSD, Flap, Fall, HR: see SeizureDetector). If the algorithm is not selected but
     * displayOnly is true, the content is shown together with a note that the algorithm
     * is for display only and can never raise an alarm.
     */
    protected boolean applyAlgorithmState(boolean selected, boolean displayOnly) {
        boolean showContent = selected || displayOnly;
        if (mRootView == null) {
            return showContent;
        }
        TextView note = mRootView.findViewById(R.id.alg_inactive_tv);
        View content = mRootView.findViewById(R.id.alg_content);
        if (note != null) {
            note.setText(displayOnly ? R.string.algorithm_display_only : R.string.algorithm_not_selected);
            note.setVisibility(selected ? View.GONE : View.VISIBLE);
        }
        if (content != null) {
            content.setVisibility(showContent ? View.VISIBLE : View.GONE);
        }
        return showContent;
    }

    /** True if the Advanced Mode option "show all algorithms" is on. */
    protected boolean isShowAllAlgs() {
        if (mContext == null) {
            return false;
        }
        return AdvancedSections.isShowAllAlgs(PreferenceManager.getDefaultSharedPreferences(mContext));
    }

    protected boolean isBasicMode() {
        if (mContext == null) {
            return true;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        return !PreferenceUtils.getBooleanFromXml(prefs, "pref_advanced_mode");
    }

 }
