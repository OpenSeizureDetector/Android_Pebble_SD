package uk.org.openseizuredetector;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

public class FragmentWebServer extends FragmentOsdBaseClass {
    String TAG = "FragmentWebServer";

    public FragmentWebServer() {
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
        return inflater.inflate(R.layout.fragment_web_server, container, false);
    }

    @Override
    protected void updateUi() {
        Log.d(TAG, "updateUi()");
        TextView tv;
        tv = (TextView) mRootView.findViewById(R.id.fragment_web_server_tv1);
        if (mConnection.mBound) {
            tv.setText("Bound to Server");
        } else {
            tv.setText("****NOT BOUND TO SERVER***");
            return;
        }


    }
}
