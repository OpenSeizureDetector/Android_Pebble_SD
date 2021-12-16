package uk.org.openseizuredetector;

public interface EventCallbackInterface {
    // Interface is called when a new event is created in the database.
        void eventCallback(boolean success, String eventStr);
    }
