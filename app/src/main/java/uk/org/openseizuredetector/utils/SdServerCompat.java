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
        if (sdkInt >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
    }

    public static int getStopForegroundFlags() {
        return Service.STOP_FOREGROUND_REMOVE;
    }

    public static SmsManager getSmsManager(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            int subId = SubscriptionManager.getDefaultSmsSubscriptionId();
            return SmsManager.getSmsManagerForSubscriptionId(subId);
        }
        return SmsManager.getDefault();
    }

    public static boolean isConnected(boolean hasInternet) {
        return hasInternet;
    }

    public static boolean isWifi(boolean hasWifiTransport) {
        return hasWifiTransport;
    }
}
