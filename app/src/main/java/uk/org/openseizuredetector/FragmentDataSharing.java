package uk.org.openseizuredetector;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

public class FragmentDataSharing extends FragmentOsdBaseClass {
    String TAG = "FragmentDataSharing";

    public FragmentDataSharing() {
        // Required empty public constructor
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_data_sharing, container, false);
    }

    @Override
    protected void updateUi() {
        Log.d(TAG, "updateUi()");
        TextView tv;
        tv = (TextView) mRootView.findViewById(R.id.fragment_data_sharing_tv1);
        if (mConnection.mBound) {
            tv.setText("Bound to Server");
        } else {
            tv.setText("****NOT BOUND TO SERVER***");
            return;
        }


    }
}
