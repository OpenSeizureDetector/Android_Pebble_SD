package uk.org.openseizuredetector;

import android.content.Context;

/**
 * Created by graham on 22/11/15.
 */
public class SdDataSourceNetwork extends SdDataSource {

    public SdDataSourceNetwork(Context context, SdDataReceiver sdDataReceiver) {
        super(context,sdDataReceiver);
        mName = "Network";
    }
}
