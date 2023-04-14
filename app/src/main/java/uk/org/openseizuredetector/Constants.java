package uk.org.openseizuredetector;

import android.net.Uri;

public class Constants {
    public interface GLOBAL_CONSTANTS {
        public final int ALARMS_OFF = 6;
        public final int ALARMS_ON = 0;
        public static final int PERMISSION_REQUEST_BODY_SENSORS = 16;
        static final Uri CAPABILITY_WEAR_APP = Uri.parse("wear://");
        public final String wearableAppCheckPayload = "AppOpenWearable";
        public final String wearableAppCheckPayloadReturnACK = "AppOpenWearableACK";
        public final String TAG_MESSAGE_RECEIVED = "SdDataSourceAw";
        public final String MESSAGE_ITEM_RECEIVED_PATH = "/message-item-received";
        public final String MESSAGE_ITEM_OSD_TEST = "/testMsg";
        public final String MESSAGE_ITEM_OSD_DATA = "/data";
        public final String MESSAGE_ITEM_OSD_TEST_RECEIVED = "/testMsg-received";
        public final String MESSAGE_ITEM_OSD_DATA_REQUESTED = "/data-requested";
        public final String MESSAGE_ITEM_OSD_DATA_RECEIVED = "/data-received";
        public final String MESSAGE_OSD_FUNCTION_RESTART = "/function-restart";
        public final String MESSAGE_ITEM_PATH = "/message-item";
        public final String APP_OPEN_WEARABLE_PAYLOAD_PATH = "/APP_OPEN_WEARABLE_PAYLOAD";
        public final String TAG_GET_NODES = "getnodes1";
        public final String mAppPackageName = "uk.org.openseizuredetector";
        public final String mAppPackageNameWearReceiver = "uk.org.openseizuredetector.aw";
        public final String mAppPackageNameWearSD = "uk.org.openseizuredetector.aw";
        public final String mServiceWearSdName = "uk.org.openseizuredetector.aw.AWSdService";
        public final Uri mStartUri = Uri.parse("Start");
        public final Uri mStopUri = Uri.parse("Stop");
        public final Uri mPASSUri = Uri.parse("PASS");
        public final Uri mRequestUri = Uri.parse("Request");
        public final Uri PreStart = Uri.parse("PreStart");
        public final String mPowerLevel = "powerLevel";
        public final String mSettingsString = "settingsJson";
        public final String mSdServerIntent = "sdServerIntent";
        public final String intentReceiver = "intentReceiver";
        public final String returnPath = " returnPath";
        public final String intentAction = " intentAction";
        public final String wearReceiverServiceIntent = "wearReceiverServiceIntent";
        public final String mSdDataPath ="mSdDataPath";
        public final String dataType = "dataType";
        public final String dataTypeSettings = "settings";
        public final String dataTypeRaw = "raw";
        public final String startId = "startId";
        public final double maxHeartRefreshRate = 300d;//measured in , 60bpm equals 1Hz
        //equals 1/s seconds = 40ms  , 300bpm is unlikely but will translate to 300/60 5Hz
        // 1/5 = 0,2 seconds. 200ms
        public final double getMaxHeartRefreshRate = (1d/(maxHeartRefreshRate/60d))*1000;

    }

    public interface ACTION {
        public static String STARTFOREGROUND_ACTION = "uk.org.openseizuredetector.startforeground";
        public static String STOPFOREGROUND_ACTION = "uk.org.openseizuredetector.stopforeground";
        public static String BATTERYUPDATE_ACTION = "uk.org.openseizuredetector.onBatteryUpdate";
        public static String BIND_ACTION = "uk.org.openseizuredetector.bindAction";
        public static String CONNECTIONUPDATE_ACTION = "uk.org.openseizuredetector.onConnectionUpdate";
        public static String BROADCAST_TO_WEARRECEIVER = "uk.org.openseizuredetector.aw.broadcastToWearReceiver";
        public static String BROADCAST_TO_SDSERVER = "uk.org.openseizuredetector.broadcastTosdServer";
        public static String PUSH_SETTINGS_ACTION = "uk.org.openseizuredetector.aw.wear.pushSettings";
        public static String PULL_SETTINGS_ACTION = "uk.org.openseizuredetector.aw.wear.pullSettings";
        public static String START_WEAR_APP_ACTION = "uk.org.openseizuredetector.aw.wear.startWear";
        public static String STOP_WEAR_APP_ACTION = "uk.org.openseizuredetector.aw.wear.stopWear";
        public static String START_WEAR_SD_ACTION = "uk.org.openseizuredetector.aw.wear.startWearSD";
        public static String STOP_WEAR_SD_ACTION = "uk.org.openseizuredetector.aw.wear.stopWearSD";
        public static String START_MOBILE_RECEIVER_ACTION = "uk.org.openseizuredetector.aw.mobile.startWearReceiver";
        public static String START_MOBILE_SD_ACTION = "uk.org.openseizuredetector.aw.mobile.startSeizureDetectorServer";
        public static String REGISTER_START_INTENT_AW = "uk.org.openseizuredetector.aw.mobile.registerStartIntents";
        public static String REGISTER_START_INTENT = "uk.org.openseizuredetector.registerStartIntents";
        public static String REGISTERED_START_INTENT_AW = "uk.org.openseizuredetector.aw.mobile.registeredStartIntents";
        public static String REGISTERED_START_INTENT = "uk.org.openseizuredetector.registeredStartIntents";
        public static String REGISTER_WEARRECEIVER_INTENT = "uk.org.openseizuredetector.aw.mobile.registerWearRecieverIntent";
        public static String REGISTERED_WEARRECEIVER_INTENT = "uk.org.openseizuredetector.aw.mobile.registeredWearRecieverIntent";
        public static String REGISTER_WEAR_LISTENER = "uk.org.openseizuredetector.aw.mobile.registerWearListener";
        public static String REGISTERED_WEAR_LISTENER = "uk.org.openseizuredetector.aw.mobile.registeredWearListener";
        public static String CONNECT_WEARABLE_INTENT = "uk.org.openseizuredetector.aw.mobile.connectWearableIntent";
        public static String CONNECTED_WEARABLE_INTENT = "uk.org.openseizuredetector.aw.mobile.connectedWearableIntent";
        public static String DISCONNECT_WEARABLE_INTENT = "uk.org.openseizuredetector.aw.mobile.disConnectIntent";
        public static String DISCONNECTED_WEARABLE_INTENT = "uk.org.openseizuredetector.aw.mobile.disConnectedIntent";
        public static String CONNECTION_WEARABLE_CONNECTED = "uk.org.openseizuredetector.aw.wear.wearableConnected";
        public static String CONNECTION_WEARABLE_RECONNECTED = "uk.org.openseizuredetector.aw.wear.wearableReConnected";
        public static String CONNECTION_WEARABLE_DISCONNECTED = "uk.org.openseizuredetector.aw.wear.wearableDisConnected";
        public static String SDDATA_TRANSFER_TO_SD_SERVER = "uk.org.openseizuredetector.sdDataTransfer";
    }

    public interface NOTIFICATION_ID {
        public static int FOREGROUND_SERVICE = 101;
    }

    public interface TAGS {
        public static String AWSDService = "AWSdService";
    }

    public interface SD_SERVICE_CONSTANTS{
        public static short defaultSampleRate = 25;
        public static short defaultSampleTime = 10;
        public static short defaultSampleCount = defaultSampleRate * defaultSampleTime;
    }
}
