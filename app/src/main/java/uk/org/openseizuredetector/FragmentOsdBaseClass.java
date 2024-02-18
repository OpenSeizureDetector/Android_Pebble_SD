package uk.org.openseizuredetector;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Point;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;


public class FragmentOsdBaseClass extends Fragment {
    String TAG = "FragmentOsdBaseClass";
    Context mContext;
    OsdUtil mUtil;
    SdServiceConnection mConnection;
    final Handler updateUiHandler = new Handler();

    protected boolean viewCreated;
    Timer mUiTimer;
    protected View mRootView;

    protected int okColour = Color.BLUE;
    protected int warnColour = Color.MAGENTA;
    protected int alarmColour = Color.RED;
    protected int okTextColour = Color.WHITE;
    protected int warnTextColour = Color.WHITE;
    protected int alarmTextColour = Color.BLACK;


    public FragmentOsdBaseClass() {
        // Required empty public constructor
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        Log.i(TAG, "onCreate()");
        if (Objects.isNull(mContext)) mContext = getContext();
        if (Objects.isNull(mUtil)) mUtil = new OsdUtil(mContext, updateUiHandler);
        if (Objects.isNull(mConnection)) mConnection = new SdServiceConnection(mContext);


    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment

        return inflater.inflate(R.layout.fragment_sd_data_viewer, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mRootView = view;
    }

    @Override
    public void onStart() {
        super.onStart();
        Log.i(TAG, "onStart()");
        if (mUtil.isServerRunning()) {
            Log.i(TAG, "onStart() - Binding to Server");
            if (Objects.nonNull(mUtil))
                if (Objects.nonNull(mConnection))
                    if (!mConnection.mBound)
                        mUtil.bindToServer(mContext, mConnection);
        } else {
            Log.i(TAG, "onStart() - Server Not Running");
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.i(TAG, "onResume()");
        mUiTimer = new Timer();
        mUiTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (Objects.nonNull(mConnection))
                    if (Objects.nonNull(mConnection.mSdServer))
                        if (mConnection.mBound)
                            updateUiOnUiThread();
            }
        }, 0, 1000);
    }

    @Override
    public void onPause() {
        super.onPause();
        Log.i(TAG, "onPause()");
        mUiTimer.cancel();
    }

    @Override
    public void onStop() {
        super.onStop();
        Log.i(TAG, "onStop()");
        mUtil.unbindFromServer(mContext, mConnection);
    }


    /**
     * If we don't use this .post() trick, we get errors about the wrong thread trying to
     * update the user interface views...
     */
    private void updateUiOnUiThread() {
        updateUiHandler.post(new Runnable() {
            @Override
            public void run() {
                updateUi();
            }
        });
    }

    /**
     * The subclasses should override this to draw their own UI.
     */
    protected void updateUi() {
        Log.d(TAG, "updateUi()");
        TextView tv;
        tv = (TextView) mRootView.findViewById(R.id.fragment_sddata_viewer_tv1);
        if (mConnection.mBound) {
            tv.setText("Bound to Server");
        } else {
            tv.setText("****NOT BOUND TO SERVER***");
        }

    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (Objects.nonNull(mConnection))
            if (Objects.nonNull(mConnection.mSdServer))
               mUtil.setBound(true,mConnection);
        updateUiHandler.post(this::updateUi);
    }

    @Override
    public void onDetach() {
        super.onDetach();

        if (Objects.nonNull(mConnection))
            if (Objects.nonNull(mConnection.mSdServer))
               mUtil.setBound(false,mConnection);
        updateUiHandler.post(this::updateUi);
    }

}