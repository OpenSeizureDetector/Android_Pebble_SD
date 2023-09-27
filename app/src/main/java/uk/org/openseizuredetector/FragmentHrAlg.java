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
    Looper looper = null;
    Handler mHandler =null;
    TextView tv = null;
    String TAG = "FragmentOsdAlg";
    public FragmentHrAlg() {
        // Required empty public constructor
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Objects.isNull(looper) && Objects.nonNull((Context) this.getActivity()))
            looper = ((Context) this.getActivity()).getMainLooper();
        if (Objects.isNull(mHandler) && Objects.nonNull(looper))
            mHandler = new Handler(looper);
        if (mUtil.isServerRunning()) {
            mUtil.writeToSysLogFile("MainActivity.onStart - Binding to Server");
            if (Objects.nonNull(mConnection)) {
                if (!mConnection.mBound) mUtil.bindToServer((Context) this.getActivity(), mConnection);
                mUtil.waitForConnection(mConnection);
                connectUiLiveDataRunner();

            }
        } else {
            Log.i(TAG, "onStart() - Server Not Running");
            mUtil.writeToSysLogFile("MainActivity.onStart - Server Not Running");
        }
        if (Objects.nonNull(mRootView)){
            if (Objects.isNull(tv)) {
                tv = (TextView) mRootView.findViewById(R.id.fragment_hr_alg_tv1);
            }
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
        if (Objects.nonNull(mRootView)) {
            tv = (TextView) mRootView.findViewById(R.id.fragment_hr_alg_tv1);
            if (Objects.nonNull(tv)) {
                tv.setText("Current HeartRate: " + mConnection.mSdServer.mSdData.mHR +
                        "\nCurrent average: " + mConnection.mSdServer.mSdData.mHRAvg);
            }
        }
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
        tv = (TextView) mRootView.findViewById(R.id.fragment_hr_alg_tv1);
        if (mConnection.mBound) {
            tv.setText("Bound to Server");
        } else {
            tv.setText("****NOT BOUND TO SERVER***");
            return;
        }


    }
}
