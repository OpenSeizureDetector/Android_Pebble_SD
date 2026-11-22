package uk.org.openseizuredetector.datasource;

import uk.org.openseizuredetector.data.SdData;

public interface SdDataReceiver {
    public void onSdDataReceived(SdData sdData);

    public void onSdDataFault(SdData sdData);

    /**
     * Issue #355: returns true when the receiver (normally SdServer) is being - or has
     * been - destroyed. Data sources check this in start() so that a delayed restart
     * callback which outlives the service shutdown cannot resurrect a "zombie" datasource
     * that keeps scanning/connecting with no service and no notification behind it.
     * Defaults to false so existing receivers (including test fakes) are unaffected.
     */
    default boolean isDestroying() {
        return false;
    }
}
