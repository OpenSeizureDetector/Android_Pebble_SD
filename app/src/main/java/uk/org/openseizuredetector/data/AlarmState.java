package uk.org.openseizuredetector.data;

/**
 * Centralized alarm state constants.
 */
public final class AlarmState {
    private AlarmState() {}

    public static final int OK = 0;
    public static final int WARNING = 1;
    public static final int ALARM = 2;
    public static final int FALL = 3;
    public static final int FAULT = 4;
    public static final int MANUAL = 5; // manual alarm
    public static final int MUTE = 6;   // mute state
    public static final int NETFAULT = 7; // network fault / other
}

