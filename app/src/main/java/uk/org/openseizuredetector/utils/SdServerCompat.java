package uk.org.openseizuredetector.utils;

import android.app.Service;
import android.content.Context;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;
import android.view.WindowManager;

public final class SdServerCompat {
    private SdServerCompat() {
        // Utility class
    }

    public static boolean isConnected(NetworkCapabilities capabilities) {
        return isConnected(capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
    }

    public static boolean isWifi(NetworkCapabilities capabilities) {
        return isWifi(capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
    }

    public static boolean shouldStartMainActivity(String topPackageName) {
        return topPackageName == null || !topPackageName.equals("uk.org.openseizuredetector");
    }

    public static int getSystemAlertWindowType(int sdkInt) {
        // minSdkVersion is 26, so we always use TYPE_APPLICATION_OVERLAY
        return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    }

    public static int getStopForegroundFlags() {
        return Service.STOP_FOREGROUND_REMOVE;
    }

    // We are suppressing a warning about using SMSManager - I don't think there is an alternative for older versions
    // of android so need to keep it.
    public static SmsManager getSmsManager(Context context) {
        // On API 23+ (Marshmallow) we can use getSystemService
        return context.getSystemService(SmsManager.class);
    }

    public static boolean isConnected(boolean hasInternet) {
        return hasInternet;
    }

    public static boolean isWifi(boolean hasWifiTransport) {
        return hasWifiTransport;
    }
}
