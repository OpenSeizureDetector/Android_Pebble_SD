package uk.org.openseizuredetector.algorithms;

import android.util.Log;

import uk.org.openseizuredetector.SdData;

public class SdAlg {
    protected String TAG = "SdAlg";
    SdAlg() {
        Log.v(TAG,"SdAlg Constructor");
        // FIXME:  Subclass constructors should initialise the algorithm from shared preferences.
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
