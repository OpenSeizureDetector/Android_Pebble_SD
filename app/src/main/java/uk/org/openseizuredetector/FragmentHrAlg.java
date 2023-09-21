package uk.org.openseizuredetector;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Objects;

public class FragmentHrAlg extends FragmentOsdBaseClass {
    Context context = getContext();
    Looper looper = context.getMainLooper();
    Handler mHandler = new Handler(looper);
    TextView tv = (TextView) mRootView.findViewById(R.id.fragment_hr_alg_tv1);
    String TAG = "FragmentOsdAlg";
    public FragmentHrAlg() {
        // Required empty public constructor
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (mUtil.isServerRunning()) {
            mUtil.writeToSysLogFile("MainActivity.onStart - Binding to Server");
            if (Objects.nonNull(mConnection)) {
                if (!mConnection.mBound) mUtil.bindToServer(context, mConnection);
                mUtil.waitForConnection(mConnection);
                connectUiLiveDataRunner();

            }
        } else {
            Log.i(TAG, "onStart() - Server Not Running");
            mUtil.writeToSysLogFile("MainActivity.onStart - Server Not Running");
        }
    }

    void connectUiLiveDataRunner(){
        if (mConnection.mBound && Objects.nonNull(mConnection.mSdServer))
        {
            if (!mConnection.mSdServer.uiLiveData.isListeningInContext(this)) {
                mConnection.mSdServer.uiLiveData.observe(this, this::onChangedObserver);
                mConnection.mSdServer.uiLiveData.observeForever(this::onChangedObserver);
                mConnection.mSdServer.uiLiveData.addToListening(this);
            }
        }else {
            mHandler.postDelayed(this::connectUiLiveDataRunner,100);
        }
    }

    private void onChangedObserver(Object o) {
        tv.setText("Current HeartRate: " + mConnection.mSdServer.mSdData.mHR +
                "\nCurrent average: " + mConnection.mSdServer.mSdData.mHRAvg);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_hr_alg, container, false);
    }

    @Override
    protected void updateUi() {
        Log.d(TAG,"updateUi()");
        if (mConnection.mBound) {
            tv.setText("Bound to Server");
        } else {
            tv.setText("****NOT BOUND TO SERVER***");
            return;
        }


    }
}
