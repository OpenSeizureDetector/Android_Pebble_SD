package uk.org.openseizuredetector.datasource;

import uk.org.openseizuredetector.utils.SdData;

public interface SdDataReceiver {
    public void onSdDataReceived(SdData sdData);

    public void onSdDataFault(SdData sdData);
}
