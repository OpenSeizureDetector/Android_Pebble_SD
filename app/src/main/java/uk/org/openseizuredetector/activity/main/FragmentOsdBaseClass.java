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
        mUtil.unbindFromServer(mContext, mConnection);
    }

    private void updateUiOnUiThread() {
        updateUiHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mContext != null && mRootView != null) {
                    try {
                        updateUi();
                    } catch (Exception e) {
                        Log.e(TAG,"upateUiOnUiThread() - exception updating UI - "+e.getMessage());
                    }
                }
            }
        });
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

}
