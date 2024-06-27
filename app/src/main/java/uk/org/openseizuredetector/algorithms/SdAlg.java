package uk.org.openseizuredetector.algorithms;

import android.content.Context;
import android.util.Log;

import uk.org.openseizuredetector.SdData;

public class SdAlg {
    protected String TAG = "SdAlg";
    protected Context mContext;
    SdAlg(Context context) {
        Log.v(TAG,"SdAlg Constructor");
        mContext = context;

        if (mContext == null) {
            Log.w(TAG,"SdAlg Constructor - context is null, so needs initialising manually");
        } else {
            Log.i(TAG,"SdAlg Constructor - Initialising from shared preferences");
            initialiseFromSharedPreferences();
        }

    }

    public void close() {
        Log.v(TAG,"close()");
    }

    public boolean initialiseFromSharedPreferences() {
        Log.e(TAG,"initialiseFromSharedPreferences() - THIS FUNCTION SHOULD BE OVERWRITTEN IN SUB CLASS");
        return true;
    }

    /**
     * Analyse the seizure detector data sdData, and return true if it represents a seizure
     * or false if not.
     * sdData may be updated to include additional information on return.
     * @param sdData  Seizure detector measured data
     * @return  true if seizure detected, otherwise false.
     */
    public boolean analyse(SdData sdData) {
        Log.v(TAG,"SdAlg.analyse()");
        // FIXME Subclass implementations should override this and determine the seizure state
        // based on the sdData provided.
        // They may update sdData to record additional information.
        return false;
    }


}
