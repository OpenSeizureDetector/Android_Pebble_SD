package uk.org.openseizuredetector;

public interface DatapointCallbackInterface {
        // Interface is called when a new datapoint is created in the database.
        void datapointCallback(boolean success, String eventStr);
    }

