package uk.org.openseizuredetector;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;

import androidx.fragment.app.Fragment;

import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.Timer;
import java.util.TimerTask;


public class FragmentSdDataViewer extends Fragment {
    String TAG = "FragmentSdDataViewer";
    Context mContext;
    OsdUtil mUtil;
    SdServiceConnection mConnection;
    final Handler updateUiHandler = new Handler();
    Timer mUiTimer;
    protected View mRootView;

    protected int okColour = Color.BLUE;
    protected int warnColour = Color.MAGENTA;
    protected int alarmColour = Color.RED;
    protected int okTextColour = Color.WHITE;
    protected int warnTextColour = Color.WHITE;
    protected int alarmTextColour = Color.BLACK;



    public FragmentSdDataViewer() {
        // Required empty public constructor
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        Log.i(TAG,"onCreate()");
        mContext = getContext();
        mUtil = new OsdUtil(mContext, updateUiHandler);
        mConnection = new SdServiceConnection(mContext);


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
        updateUiHandler.post(new Runnable(){
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
        Log.d(TAG,"updateUi()");
        TextView tv;
        tv = (TextView)mRootView.findViewById(R.id.fragment_sddata_viewer_tv1);
        if (mConnection.mBound) {
            tv.setText("Bound to Server");
        } else {
            tv.setText("****NOT BOUND TO SERVER***");
        }

    }

}