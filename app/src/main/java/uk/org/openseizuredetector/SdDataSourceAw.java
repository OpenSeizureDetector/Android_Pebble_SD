/*
  Android_Pebble_sd - Android alarm client for openseizuredetector..

  See http://openseizuredetector.org for more information.

  Copyright Graham Jones, 2015, 2016

  This file is part of pebble_sd.

  Android_Pebble_sd is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  Android_Pebble_sd is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with Android_pebble_sd.  If not, see <http://www.gnu.org/licenses/>.

*/
package uk.org.openseizuredetector;



import uk.org.openseizuredetector.R;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.strictmode.IntentReceiverLeakedViolation;
import androidx.preference.PreferenceManager;
import android.text.SpannableString;
import android.text.format.Time;
import android.text.util.Linkify;
import android.util.Log;

import org.checkerframework.checker.units.qual.C;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.work.impl.utils.ForceStopRunnable;

import com.firebase.ui.auth.viewmodel.RequestCodes;
import com.github.mikephil.charting.data.Entry;


/**
 * SdDataSource AW
 * A data source that uses an Android Wear Device.   This data source is simple, with the
 * communication with the Android Wear device taking place via a separate companion app.
 * This data source and the companion app communicate via INTENTS.
 *
 * Bram Regtien, 2023
 *
 */
/**
 * A Passive data source that expects a device to send it data periodically by sending a POST request.
 * The POST network request is handled in the SDWebServer class, which calls the 'updateFrom JSON()'
 * function to send the data to this datasource.
 * SdWebServer expects POST requests to /data and /settings URLs to send data or watch settings.
 */

/**
 * order of boolean tracing
 * mConnection.mBound
 * mConnection.mWatchConnected
 * mConnection.hasSdSettings()
 * mWatchAppRunningCheck
 * mConnection.hasSdData
 */
public class SdDataSourceAw extends SdDataSource {
    private String TAG = "SdDataSourceAw";
    private final String mAppPackageName = "uk.org.openseizuredetector.aw.mobile";
    //private final String mAppPackageName = "uk.org.openseizuredetector";
    private int mNrawdata = 0;
    private static int MAX_RAW_DATA = 125;
    private int nRawData = 0;
    private double[] rawData = new double[MAX_RAW_DATA];
    private Intent receivingIntent = null;
    private Intent aWIntent = null;
    private Intent aWIntentBase = null;
    private Intent aWIntentBaseManifest = null;
    private Intent activityIntent = null;
    private Intent intentReceiver = null;
    private Intent receivedIntentByBroadCast = null;
    private String receivedAction = null;
    private boolean sdBroadCastReceived;
    private boolean sdAwBroadCastReceived;
    public int startIdWearReceiver = 0;
    public int startIdWearSd = 0;
    private Intent intentRegisterState;
    IntentFilter broadCastToSdServer = new IntentFilter(Constants.ACTION.BROADCAST_TO_SDSERVER);
    private BroadcastReceiver.PendingResult runningReceiver = null;
    int connectionState = -1;
    int currentBroadcastRequestId = 100;
    private String receiverPermission = "uk.org.openseizuredetector.permission.broadcast";
    Context givenContext = null;

    public SdDataSourceAw(Context context, Handler handler,
                          SdDataReceiver sdDataReceiver) {
        super(context, handler, sdDataReceiver);
        givenContext = context;
        mSdDataReceiver = sdDataReceiver;
        mName = "AndroidWear";
        broadCastToSdServer.addAction(Constants.ACTION.BROADCAST_TO_SDSERVER);
        // Set default settings from XML files (mContext is set by super().
        PreferenceManager.setDefaultValues(useSdServerBinding(),
                R.xml.network_passive_datasource_prefs, true);

        if (Objects.isNull(intentBroadCastReceiver)) intentBroadCastReceiver = new IntentBroadCastReceiver();
        intentRegisterState = intentBroadCastReceiver.register(useSdServerBinding(), broadCastToSdServer);
        Log.i(TAG,"start(): state of registering broadcast: " + (Objects.isNull(intentRegisterState)?" not " : "") + "registered.");
        Log.i(TAG,"start(): state of broadcast receiver: " + (Objects.isNull(intentBroadCastReceiver)?" not " : "") + "registered.");



    }


    public Activity getActivityFromContext(Context context) {
        if (context == null) {
            mUtil.showToast("instantly failing get context wrapped context");
            return null;
        } else if (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                mUtil.showToast("instanceFound");
                return (Activity) context;
            } else {
                mUtil.showToast("retrywithWrapper");
                return getActivityFromContext(((ContextWrapper) context).getBaseContext());
            }
        }
        return null;
    }


    /**
     * IntentBroadCastReceiver with coding from:
     * https://stackoverflow.com/questions/2682043/how-to-check-if-receiver-is-registered-in-android
     * */

    public class IntentBroadCastReceiver  extends BroadcastReceiver {
        IntentBroadCastReceiver(){
            Log.i("IntentBroadCastReceiver","BroadcastReceiverClass() in Constructor");
            registeredIntent.setAction(Constants.ACTION.BROADCAST_TO_SDSERVER);
        };
        public boolean isRegistered = false;
        private Intent registeredIntent = new Intent();

        /**
         * register receiver
         * @param context - Context
         * @param filter - Intent Filter
         * @return see Context.registerReceiver(BroadcastReceiver,IntentFilter)
         */

        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        public Intent register(Context context, IntentFilter filter) {
            Log.d(TAG,"intentBroadCastReceiver()register(): entered register.");
            //if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P){
            try {
                // ceph3us note:
                // here I propose to create
                // a isRegistered(Context) method
                // as you can register receiver on different context
                // so you need to match against the same one :)
                // example  by storing a list of weak references
                // see LoadedApk.class - receiver dispatcher
                // its and ArrayMap there for example
                receivingIntent = new Intent(context, getClass());

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    return !isRegistered
                            ? (
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU?context.registerReceiver(this,filter,Context.RECEIVER_EXPORTED):
                                            context.registerReceiver(this,filter)
                                    /*
                                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU?
                                    context.registerReceiver(this, filter):
                                    context.registerReceiver(this, filter, Constants.ACTION.BROADCAST_TO_SDSERVER,mHandler,Context.RECEIVER_EXPORTED)*/
                            )
                            : null;
                }else {
                    return !isRegistered?(
                            context.registerReceiver(this,filter))
                            : null;
                }
            } catch (Exception receiverLeakedViolation) {
                Log.e(TAG,"onReceive() " ,receiverLeakedViolation);
                return register(context,filter);
            } finally {
                isRegistered = true;
            }
           /* }else {
                try {
                    // ceph3us note:
                    // here I propose to create
                    // a isRegistered(Context) method
                    // as you can register receiver on different context
                    // so you need to match against the same one :)
                    // example  by storing a list of weak references
                    // see LoadedApk.class - receiver dispatcher
                    // its and ArrayMap there for example
                    receivingIntent = new Intent(context, getClass());

                    return !isRegistered
                            ? Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU?
                            context.registerReceiver(this, filter):
                            context.registerReceiver(this, filter,Context.RECEIVER_EXPORTED)
                            : null;
                } finally {
                    isRegistered = true;
                }
            }*/
        }


        /**
         * unregister received
         * @param context - context
         * @return true if was registered else false
         */
        public boolean unregister(Context context) {
            // additional work match on context before unregister
            // eg store weak ref in register then compare in unregister
            // if match same instance
            Log.d(TAG,this.getClass().getName() + "Received command to unregister");
            return isRegistered
                    && unregisterInternal(context);
        }

        private boolean unregisterInternal(Context context) {
            context.unregisterReceiver(this);
            isRegistered = false;
            return true;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG,this.getClass().getName() + " onReceive: received broadcast.");

            if (!Objects.equals(intent,null))
                if (Constants.ACTION.BROADCAST_TO_SDSERVER.equals(intent.getAction())) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                        runningReceiver = goAsync();
                    }
                    intentReceivedAction(intent);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                        runningReceiver.finish();
                    }
                }/*else {
                    this.abortBroadcast();
                }*/
        }

    }

    private boolean registeredAllBroadCastIntents(){
        return sdAwBroadCastReceived && sdBroadCastReceived;
    }

    @Override
    public void initSdServerBindPowerBroadcastComplete(){
        mobileBatteryPctUpdate();
    }

    private IntentBroadCastReceiver intentBroadCastReceiver = null;

    private void onStartReceived() {
        try {
            if (Objects.isNull(mHandler))  mHandler =  new Handler();
            if (Objects.isNull(mUtil)) mUtil = new OsdUtil(useSdServerBinding(), mHandler);
            if (Objects.equals(intentBroadCastReceiver, null))
                intentBroadCastReceiver = new IntentBroadCastReceiver();
            boolean unregisteredBroadCastReceiver = false;
            if (intentBroadCastReceiver.isRegistered)
                unregisteredBroadCastReceiver = intentBroadCastReceiver.unregister(useSdServerBinding());
            if (unregisteredBroadCastReceiver || Objects.isNull(intentRegisterState)) intentRegisterState =  intentBroadCastReceiver.register(useSdServerBinding(), broadCastToSdServer);
            Log.i(TAG, "onCreate(): reached with state of broadcastReceiver"+ (Objects.isNull(intentRegisterState)?" not " : "" )+ " registered: ");
            if ( Objects.nonNull(receivedIntentByBroadCast)) {
                try {

                    Log.i(TAG, "got intent and count of extras:" + receivedIntentByBroadCast.getExtras().size());
                } catch (Exception e) {
                    Log.e(TAG, "onCreate: ", e);
                }

                if (receivedIntentByBroadCast.hasExtra(Constants.GLOBAL_CONSTANTS.returnPath)) {
                    if (Constants.GLOBAL_CONSTANTS.mAppPackageNameWearReceiver.equals(receivedIntentByBroadCast.getStringExtra(Constants.GLOBAL_CONSTANTS.returnPath))) {
                        Log.i(TAG, "inOnStartReceived");
                        if (receivedIntentByBroadCast.hasExtra(Constants.GLOBAL_CONSTANTS.intentAction)) {
                            receivedAction = receivedIntentByBroadCast.getStringExtra(Constants.GLOBAL_CONSTANTS.intentAction);
                            Log.d(TAG,"inOnStartReceived(): received action: " +receivedAction);
                        }

                        if (Constants.ACTION.WEARRECEIVER_FOREGROUND_STARTED.equals(receivedAction)){
                            Log.d(TAG,"onStartReceived: received WEARRECEIVER_FOREGROUND_STARTED in Intent.");
                            connectionState = 3;
                            registerSdServerIntentInWearReceiver();
                            return;
                        }
                        if (Constants.ACTION.REGISTER_START_INTENT.equals(receivedAction)){
                            Log.d(TAG,"onStartReceived: received REGISTER_START_INTENT in Intent.");
                            return;
                        }

                        if (Constants.ACTION.BATTERYUPDATE_AW_ACTION.equals(receivedAction)){
                            if (connectionState < 5){
                                connectionState = 5;
                                useSdServerBinding().mSdData.watchConnected = true;
                                useSdServerBinding().mSdData.batteryPc = receivedIntentByBroadCast.getIntExtra(Constants.ACTION.BATTERYUPDATE_AW_ACTION,-1);
                                useSdServerBinding().lineDataSetWatchBattery.addEntry(new Entry(useSdServerBinding().mSdData.batteryPc,useSdServerBinding().lineDataSetWatchBattery.getYVals().size()));
                                useSdServerBinding().hrHistoryStringsWatchBattery.add(Calendar.getInstance(TimeZone.getDefault()).getTime().toString());
                                signalUpdateUI();
                            }
                        }

                        if (Constants.ACTION.REGISTERED_START_INTENT.equals(receivedAction))
                        {
                            if (receivedIntentByBroadCast.hasExtra(Constants.GLOBAL_CONSTANTS.wearReceiverServiceIntent)) {
                                aWIntentBase = receivedIntentByBroadCast.getParcelableExtra(Constants.GLOBAL_CONSTANTS.wearReceiverServiceIntent);
                                aWIntentBase.removeExtra(Constants.GLOBAL_CONSTANTS.intentAction);
                                aWIntentBase.removeExtra(Constants.GLOBAL_CONSTANTS.dataType);
                                aWIntentBase.putExtra(Constants.GLOBAL_CONSTANTS.returnPath, Constants.GLOBAL_CONSTANTS.mAppPackageName);
                                aWIntentBase.setAction(Constants.ACTION.BROADCAST_TO_WEARRECEIVER);
                                aWIntentBase.addFlags(Intent.FLAG_RECEIVER_FOREGROUND|Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                                aWIntentBase.setComponent(null);
                                sdAwBroadCastReceived = true;
                            }
                            sdBroadCastReceived = true;
                            connectionState = 4;
                            if (registeredAllBroadCastIntents()){
                                aWIntent = aWIntentBase;
                                aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.intentAction, Constants.ACTION.REGISTERED_WEARRECEIVER_INTENT);
                                sendBroadcastToWearReceiver(aWIntent);
                                connectionState = 5;
                            }
                            return;
                        }

                        if (Constants.ACTION.REGISTER_WEARRECEIVER_INTENT.equals(receivedAction)){
                            if (receivedIntentByBroadCast.hasExtra(Constants.GLOBAL_CONSTANTS.wearReceiverServiceIntent)) {
                                aWIntentBase = receivedIntentByBroadCast.getParcelableExtra(Constants.GLOBAL_CONSTANTS.wearReceiverServiceIntent);
                                if (Objects.isNull(aWIntentBase)) throw new AssertionError();
                                aWIntentBase.putExtra(Constants.GLOBAL_CONSTANTS.returnPath, Constants.GLOBAL_CONSTANTS.mAppPackageName);
                                aWIntentBase.setAction(Constants.ACTION.BROADCAST_TO_WEARRECEIVER);
                                aWIntentBase.addFlags(Intent.FLAG_RECEIVER_FOREGROUND|Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                                aWIntentBase.setComponent(null);
                                sdAwBroadCastReceived = true;
                                if (registeredAllBroadCastIntents()){
                                    aWIntent = aWIntentBase;
                                    aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.intentReceiver,receivingIntent);
                                    aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.intentAction, Constants.ACTION.REGISTERED_WEARRECEIVER_INTENT);
                                    sendBroadcastToWearReceiver(aWIntent);
                                }
                            }else{
                                Log.e(TAG,"onStartReceived(): registering WearReceiverIntent without intent attached", new Throwable());
                            }
                        }

                        if (Objects.nonNull(aWIntentBase)) {

                            if (Constants.ACTION.REGISTERED_WEARRECEIVER_INTENT.equals(receivedAction)) {

                                sdBroadCastReceived = true;
                                if (registeredAllBroadCastIntents()) {
                                    aWIntent = aWIntentBase;
                                    aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.intentAction, Constants.ACTION.REGISTER_WEAR_LISTENER);
                                    sendBroadcastToWearReceiver(aWIntent);
                                }
                                connectionState = 5;
                                return;
                            }
                            if (Constants.ACTION.REGISTERED_WEAR_LISTENER.equals(receivedAction)) {
                                useSdServerBinding().mSdData.serverOK = true;
                                mSdData = getSdData();

                                sdBroadCastReceived = true;
                                if (registeredAllBroadCastIntents()) {
                                    aWIntent = aWIntentBase;
                                    aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.intentAction, Constants.ACTION.CONNECT_WEARABLE_INTENT);
                                    sendBroadcastToWearReceiver(aWIntent);
                                    connectionState = 6;
                                }
                                return;
                            }


                            if (Constants.ACTION.CONNECTION_WEARABLE_CONNECTED.equals(receivedAction)) {
                                useSdServerBinding().mSdData.watchConnected = true;
                                useSdServerBinding().mSdData.watchAppRunning = true;
                                signalUpdateUI();
                                mobileBatteryPctUpdate();
                                connectionState = 7;
                                return;
                            }

                            if (Constants.ACTION.CONNECTION_WEARABLE_RECONNECTED.equals(receivedAction)) {
                                useSdServerBinding().mSdData.watchConnected = true;
                                useSdServerBinding().mSdData.watchAppRunning = true;
                                signalUpdateUI();
                                mobileBatteryPctUpdate();
                                connectionState = 8;
                                return;
                            }

                            if (Constants.ACTION.CONNECTION_WEARABLE_DISCONNECTED.equals(receivedAction)) {
                                useSdServerBinding().mSdData.watchConnected = false;
                                useSdServerBinding().mSdData.watchAppRunning = false;
                                signalUpdateUI();
                                connectionState = -2;
                                return;
                            }

                            if (Constants.ACTION.PUSH_SETTINGS_ACTION.equals(receivedAction)) {
                                try {
                                    updatePrefs();
                                    calculateStaticTimings();
                                    mHandler.postDelayed(() -> {
                                        aWIntent = aWIntentBase;
                                        aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.intentAction, Constants.ACTION.PUSH_SETTINGS_ACTION);
                                        if (!getSdData().serverOK)
                                            useSdServerBinding().mSdData.serverOK = true;
                                        aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.mSdDataPath, getSdData().toSettingsJSON()); // send SdDataSource Updated mSdData
                                        sendBroadcastToWearReceiver(aWIntent);
                                    }, 100);
                                    connectionState = 10;
                                } catch (Exception e) {
                                    Log.e(TAG, "onStartReceived(): PUSH_SETTINGS_ACTION:", e);
                                    connectionState = -4;
                                }
                                return;
                            }

                            if (Constants.ACTION.SDDATA_TRANSFER_TO_SD_SERVER.equals(receivedAction)) {
                                try {
                                    if (receivedIntentByBroadCast.hasExtra(Constants.GLOBAL_CONSTANTS.mSdDataPath)) {
                                        String a = updateFromJSON(receivedIntentByBroadCast.getStringExtra(Constants.GLOBAL_CONSTANTS.mSdDataPath));

                                        Log.v(TAG, "result from updateFromJSON(): " + a);
                                        if ("sendSettings".equals(a) ||
                                                "watchConnect".equals(a)
                                        ) {
                                            super.updatePrefs();
                                            sendWatchSdSettings();
                                            getWatchSdSettings();
                                        }
                                        if (Objects.equals(a, "ERROR")) {
                                            Log.e(TAG, "Error in updateFromJSON: ");
                                        }
                                        if (!getSdData().haveSettings)
                                            useSdServerBinding().mSdData.haveSettings = true;
                                        if (!getSdData().watchConnected)
                                            useSdServerBinding().mSdData.watchConnected = true;
                                        if (!getSdData().watchAppRunning)
                                            useSdServerBinding().mSdData.watchAppRunning = true;
                                        if (!getSdData().haveData && !a.equals("ERROR"))
                                            useSdServerBinding().mSdData.haveData = true;
                                        signalUpdateUI();
                                        connectionState = 11;
                                    }

                                } catch (Exception e) {
                                    Log.e(TAG, "onStartReceived()", e);
                                }
                            }

                            if (Constants.ACTION.WATCH_BODY_DETECTED.equals(receivedAction)) {
                                useSdServerBinding().mSdData.mWatchOnBody = Boolean.parseBoolean(receivedIntentByBroadCast.getStringExtra(Constants.GLOBAL_CONSTANTS.mSdDataPath));

                                signalUpdateUI();
                            }

                            if (Constants.ACTION.STOP_WEAR_SD_ACTION.equals(receivedAction)) {
                                //if (useSdServerBinding().)Log.i(TAG," fixme: add here liveData from startup and main activity");
                                useSdServerBinding().mSdData.haveSettings = false;
                                useSdServerBinding().mSdData.haveData = false;
                                useSdServerBinding().mSdData.watchConnected = false;
                                useSdServerBinding().mSdData.mDataType = receivedAction;
                                if (useSdServerBinding().uiLiveData.hasActiveObservers())
                                    useSdServerBinding().uiLiveData.signalChangedData();
                                connectionState = -2;
                            }


                        } else {
                            mUtil.showToast("Received intent without prior setting WearReceiver as Receiving end.");
                        }

                    } else if (Constants.GLOBAL_CONSTANTS.mAppPackageName.equals(aWIntent.getStringExtra(Constants.GLOBAL_CONSTANTS.returnPath))){
                        aWIntent.removeExtra(Constants.GLOBAL_CONSTANTS.intentAction);
                        aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.intentAction, Constants.ACTION.REGISTER_START_INTENT);

                        PendingIntent pIntent = PendingIntent.getBroadcast(useSdServerBinding(), useSdServerBinding().mStartId, aWIntent,
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT);
                        mHandler.post(() -> {
                            try {
                                pIntent.send();
                            } catch (PendingIntent.CanceledException e) {
                                throw new RuntimeException(e);
                            }
                        });


                    }
                } else
                    mUtil.showToast("inOnStartWithIntent");
            }
            else{
                Log.e(TAG, "onStartReceived: entered with empty receivedIntentByBroadCast");
            }
        }catch (Exception e){
            Log.e(TAG,"onStartReceived(): ",e);
        }
    }

    private void sendBroadcastToWearReceiver(Intent intentToSend) {
        logDebug_aWIntent(intentToSend);
        /*PendingIntent pendingIntent = PendingIntent.getBroadcast(useSdServerBinding(),currentBroadcastRequestId,intentToSend,
                PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_ONE_SHOT|PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            pendingIntent.send(givenContext,currentBroadcastRequestId,intentToSend,null,mHandler,
                    "uk.org.openseizuredetector.broadcast");
        }catch (PendingIntent.CanceledException canceledException){
            Log.e(TAG, "sendBroadcastToWearReceiver: failed to send broadcast", canceledException);
        }*/
        currentBroadcastRequestId++;
        //givenContext.sendBroadcast(intentToSend);
        useSdServerBinding().sendBroadcast(intentToSend);
    }

    private void logDebug_aWIntent() {logDebug_aWIntent(null);}
    private void logDebug_aWIntent(Intent intentToLog) {
        Intent intentToPrint = Objects.nonNull(intentToLog)?intentToLog:aWIntent;
        if (Objects.isNull(intentToPrint)){
            Log.e(TAG, Arrays.toString(Thread.currentThread().getStackTrace()) + ":\nPrinting empty intent.");
            return;
        }
        Log.d(TAG,"logDebug_aWIntent(): got Intent:" + intentToPrint + " has got action: "
                + (intentToPrint.hasExtra(Constants.GLOBAL_CONSTANTS.intentAction)?
                intentToPrint.getStringExtra(Constants.GLOBAL_CONSTANTS.intentAction):"not defined. ") +
                "\nof return path: " + (intentToPrint.hasExtra(Constants.GLOBAL_CONSTANTS.returnPath)?
                intentToPrint.getStringExtra(Constants.GLOBAL_CONSTANTS.returnPath):" not defined. ")+
                "\n in connectionState: " + connectionState + " and currentBroadcastRequestId: " + currentBroadcastRequestId +
                "\nwith data: " + (Objects.nonNull(intentToPrint.getData())?intentToPrint.getData(): "not defined.") +
                "\nwith extra string data: " + (intentToPrint.hasExtra(Constants.GLOBAL_CONSTANTS.dataType)?
                intentToPrint.getParcelableExtra(Constants.GLOBAL_CONSTANTS.dataType): "not defined.") +
                "\nstartId: " + (Objects.nonNull(useSdServerBinding())?useSdServerBinding().mStartId:"") +
                "\nand stacktrace\n: " + Arrays.toString(Thread.currentThread().getStackTrace()));
    }

    @RequiresApi(api = Build.VERSION_CODES.CUPCAKE)
    private boolean isWearReceiverInstalled(){

        mHandler = new Handler(useSdServerBinding().getMainLooper());
        mUtil = new OsdUtil(useSdServerBinding(), mHandler);
        PackageManager manager = useSdServerBinding().getPackageManager();
        aWIntent = manager.getLaunchIntentForPackage(Constants.GLOBAL_CONSTANTS.mAppPackageNameWearReceiver);
        Log.i(TAG,"aWIntent: " + aWIntent);
        if (aWIntent == null) {
            mUtil.showToast("Error - OpenSeizureDetector Android Wear App is not installed - please install it and run it");
            installAwApp();
            return false;
        } else {
            aWIntent.setData(null);
            aWIntent.setComponent(null);
            aWIntent.setPackage(null);
            aWIntent.removeCategory(Intent.CATEGORY_LAUNCHER);
            aWIntent.removeExtra(Constants.GLOBAL_CONSTANTS.intentAction);
            aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.intentReceiver, receivingIntent);
            aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.returnPath, Constants.GLOBAL_CONSTANTS.mAppPackageName);
            aWIntent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING|Intent.FLAG_FROM_BACKGROUND|
                    Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            aWIntentBase = aWIntent;
            aWIntentBaseManifest = aWIntent;

            //AddComponent only working for implicit BroadCast For explicit (when app is already running and broadcast registered.
            aWIntentBaseManifest.setComponent(new ComponentName(Constants.GLOBAL_CONSTANTS.mAppPackageNameWearReceiver,  Constants.GLOBAL_CONSTANTS.mAppPackageNameWearReceiver+".WearReceiverBroadCastStart"));
            aWIntentBaseManifest.setData(Constants.GLOBAL_CONSTANTS.mStartUri);
            aWIntentBaseManifest.putExtra(Constants.GLOBAL_CONSTANTS.dataType,Constants.GLOBAL_CONSTANTS.mStartUri);
            aWIntentBaseManifest.setAction(Constants.ACTION.BROADCAST_TO_WEARRECEIVER_MANIFEST);


            aWIntentBase.setAction(Constants.ACTION.BROADCAST_TO_WEARRECEIVER);
            aWIntentBase.putExtra(Constants.GLOBAL_CONSTANTS.dataType,Constants.GLOBAL_CONSTANTS.mPASSUri);
            aWIntent = null;

            // pre-start firing service WearReceiver
            connectionState = 1;
            return true;
        }
    }

    /**
     * Start the datasource updating - initialises from shared-preferences first to
     * make sure any changes to preferences are taken into account.
     */
    @Override
    public void start() {
        Log.i(TAG, "start()");
        mUtil.writeToSysLogFile("SdDataSourceAw.start()");
        super.start();
        mNrawdata = 0;

        connectionState = 0;
        Intent intentToSend = null;
        //START_WEAR_APP_ACTION.toLower()
        // Now start the AndroidWear companion app
        checkAndUnRegisterReceiver();

        if (Objects.isNull(intentBroadCastReceiver)) intentBroadCastReceiver = new IntentBroadCastReceiver();
        if (!intentBroadCastReceiver.isRegistered) {
            intentRegisterState = intentBroadCastReceiver.register(useSdServerBinding(), broadCastToSdServer);
            Log.i(TAG,"start(): state of registering broadcast: " + (Objects.isNull(intentRegisterState)?
                    " not " : "") + "registered.");
        }
        //waitReceiverRegistered();
        if (isWearReceiverInstalled()) {
            try {
                startWearReceiverApp();
                /*mHandler.postDelayed(
                        this::startWearReceiverApp,
                        (long)OsdUtil.convertTimeUnit(2,TimeUnit.SECONDS,TimeUnit.MILLISECONDS));
                 if (Objects.isNull(aWIntentBase)&&Objects.isNull(aWIntentBaseManifest)){
                    intentReceivedAction(aWIntent);
                } else {

                    //aWIntent.setClassName(aWIntent.getPackage(),".WearReceiver");
                    //aWIntent = new Intent();
                    //aWIntent.setPackage(Constants.GLOBAL_CONSTANTS.mAppPackageNameWearReceiver);
                    //FIXME: tell me how to incorporate <data ######## /> with:
                    // .setData()
                    // and: launch DebugActivity from debugger.
                    // (this is one way of 2 way communication.)
                    // Also tell me how to use activity without broadcast. In this context is no getActivity() or getIntent()
                    //aWIntent.setData(Constants.GLOBAL_CONSTANTS.mStartUri);
                    //aWIntent = new Intent();
                    mHandler.postDelayed(this::registerSdServerIntentInWearReceiver,(long)OsdUtil.convertTimeUnit(3,TimeUnit.SECONDS,TimeUnit.MILLISECONDS));
                  final PendingIntent pIntent = PendingIntent.getBroadcast(useSdServerBinding(), useSdServerBinding().mStartId, aWIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT);

                    try {
                        pIntent.send();
                    } catch (PendingIntent.CanceledException e) {
                        Log.e(TAG,"start(): Canceled pIntent2: ",e);
                    }*/

                    /*aWIntent = aWIntentBaseManifest;
                    aWIntent.removeExtra(Constants.GLOBAL_CONSTANTS.intentAction);
                    aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.intentAction, Constants.ACTION.REGISTER_START_INTENT);
                    sendBroadcastToWearReceiver(aWIntent);
                   PendingIntent pIntent2 = PendingIntent.getBroadcast(useSdServerBinding(), useSdServerBinding().mStartId+1, aWIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT);
                    mHandler.postDelayed(() -> {
                        useSdServerBinding().sendBroadcast(aWIntent);
                        *//*try {
                            pIntent2.send();
                        } catch (PendingIntent.CanceledException e) {
                           Log.e(TAG,"start(): Canceled pIntent2: ",e);
                        }*//*
                    },(long) OsdUtil.convertTimeUnit(10, TimeUnit.SECONDS,TimeUnit.MILLISECONDS));
                }*/


            } catch (Exception e) {
                Log.e(TAG, "start() encountered an error", e);
                /*mHandler.postDelayed(() -> {
                    aWIntent = new Intent(Constants.ACTION.BROADCAST_TO_WEARRECEIVER);
                    aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.intentAction, Constants.ACTION.STOP_MOBILE_RECEIVER_ACTION);
                    aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.returnPath, Constants.GLOBAL_CONSTANTS.mAppPackageName);
                    mContext.sendBroadcast(aWIntent);
                }, 100);*/
            }
        }

    }

    private void registerSdServerIntentInWearReceiver() {
        Intent intentToSend = aWIntentBase;
        intentToSend.removeExtra(Constants.GLOBAL_CONSTANTS.intentAction);
        intentToSend.putExtra(Constants.GLOBAL_CONSTANTS.intentAction, Constants.ACTION.REGISTER_START_INTENT);
        intentToSend.setData(Constants.GLOBAL_CONSTANTS.mStartUri);

        SdData sdData = getSdData();

        //intentToSend.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intentToSend.putExtra(Constants.GLOBAL_CONSTANTS.startId, useSdServerBinding().mStartId);
        intentToSend.putExtra(Constants.GLOBAL_CONSTANTS.startIdWearReceiver, startIdWearReceiver);
        intentToSend.putExtra(Constants.GLOBAL_CONSTANTS.mSdDataPath,sdData.toSettingsJSON());
        startIdWearReceiver++;
        intentToSend.putExtra(Constants.GLOBAL_CONSTANTS.startIdWearSd, startIdWearSd);
        startIdWearSd++;
        sendBroadcastToWearReceiver(intentToSend);
    }

    private void checkAndUnRegisterReceiver(){
        if (Objects.nonNull(intentBroadCastReceiver)){

            if (intentBroadCastReceiver.isRegistered)
                intentBroadCastReceiver.unregister(useSdServerBinding());
        }
        intentBroadCastReceiver = null;

    }

    void waitReceiverRegistered(){
        if (Objects.nonNull(intentBroadCastReceiver))
            if (intentBroadCastReceiver.isRegistered)
                if (Objects.nonNull(receivingIntent))
                    return;
        mHandler.postDelayed(this::waitReceiverRegistered,100);
    }

    /**
     * Stop the datasource from updating
     */
    public void stop() {
        Log.i(TAG, "stop()");
        mUtil.writeToSysLogFile("SdDataSourceAw.stop()");
        try{
            checkAndUnRegisterReceiver();

            intentBroadCastReceiver = null;
            aWIntent = aWIntentBaseManifest;
            aWIntent.removeExtra(Constants.GLOBAL_CONSTANTS.intentAction);
            //AddComponent only working for implicit BroadCast For explicit (when app is already running and broadcast registered.
            aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.dataType, Constants.GLOBAL_CONSTANTS.mStopUri);
            aWIntent.setData(Constants.GLOBAL_CONSTANTS.mStopUri);
            aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.intentAction, Constants.ACTION.STOP_MOBILE_RECEIVER_ACTION);

            //aWIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.startId, useSdServerBinding().mStartId);
            aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.startIdWearReceiver, startIdWearReceiver);
            startIdWearReceiver--;
            aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.startIdWearSd, startIdWearSd);
            startIdWearSd--;
            PendingIntent pIntent = PendingIntent.getBroadcast(useSdServerBinding(), useSdServerBinding().mStartId, aWIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT);
            pIntent.send();
            connectionState = -3;


            /*if (!Objects.equals(aWIntent, null)) {
                aWIntent.putExtra("data",Constants.GLOBAL_CONSTANTS.mStopUri);
                mContext.sendBroadcast(aWIntent);
            }
            if (!Objects.equals(intentBroadCastReceiver,null)) {
                if (intentBroadCastReceiver.isRegistered)
                    intentBroadCastReceiver
                            .unregister(mContext);
                intentBroadCastReceiver = null;
            }*/
        }catch (Exception e){
            Log.e(TAG,"Stop() Exepted",e);
        }

        //super.stop();
        // FIXME - send an intent to tell the Android Wear companion app to shutdown.
    }

    private void installAwApp() {
        // FIXME - I don't think this works!
        // from https://stackoverflow.com/questions/11753000/how-to-open-the-google-play-store-directly-from-my-android-application
        // First tries to open Play Store, then uses URL if play store is not installed.
        try {
            aWIntent = aWIntentBase;
            aWIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + Constants.GLOBAL_CONSTANTS.mAppPackageNameWearReceiver));
            aWIntent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING|Intent.FLAG_FROM_BACKGROUND|
                    Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            useSdServerBinding().startActivity(aWIntent);
        } catch (android.content.ActivityNotFoundException anfe) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + Constants.GLOBAL_CONSTANTS.mAppPackageName));
            i.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING|Intent.FLAG_FROM_BACKGROUND|
                    Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            useSdServerBinding().startActivity(i);
        }
    }

    public void startWearReceiverApp(){
        try {
            Intent intentToSend = aWIntentBaseManifest;
            if (Objects.isNull(aWIntentBaseManifest)) {
                mHandler.postDelayed(()->{
                        isWearReceiverInstalled();
                        startWearReceiverApp();
                        },(long) OsdUtil.convertTimeUnit(2d,TimeUnit.SECONDS,TimeUnit.MILLISECONDS));
                connectionState = -1;
                return;
            }
            SdData sdData = getSdData();
            intentToSend.setAction(Constants.ACTION.BROADCAST_TO_WEARRECEIVER_MANIFEST);
            intentToSend.putExtra(Constants.GLOBAL_CONSTANTS.intentAction,Constants.ACTION.START_MOBILE_RECEIVER_ACTION);
            intentToSend.setFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES|Intent.FLAG_FROM_BACKGROUND|
                    Intent.FLAG_RECEIVER_FOREGROUND);
            boolean setDataInIntentToSend = false;
            if (Objects.isNull(intentToSend.getData()))
                setDataInIntentToSend = true;
            else {
                if (!intentToSend.hasExtra(Constants.GLOBAL_CONSTANTS.dataType))
                    setDataInIntentToSend = true;
                else if (Objects.isNull(intentToSend.getParcelableExtra(Constants.GLOBAL_CONSTANTS.dataType)))
                    setDataInIntentToSend = true;
                else if (Constants.GLOBAL_CONSTANTS.mPASSUri.equals(intentToSend.getParcelableExtra(Constants.GLOBAL_CONSTANTS.dataType)))
                    setDataInIntentToSend = true;
            }
            if (setDataInIntentToSend){
                intentToSend.setData(Constants.GLOBAL_CONSTANTS.mStartUri);
                intentToSend.putExtra(Constants.GLOBAL_CONSTANTS.dataType, Constants.GLOBAL_CONSTANTS.mStartUri);
            }
            boolean setActionInIntentToSend = false;
            if (Objects.nonNull(intentToSend.getAction())) {
                if (!Constants.ACTION.BROADCAST_TO_WEARRECEIVER_MANIFEST.equals(intentToSend.getAction()))
                    setActionInIntentToSend = true;
            }else {
                setActionInIntentToSend = true;
            }
            if (setActionInIntentToSend){
                intentToSend.setAction(Constants.ACTION.BROADCAST_TO_WEARRECEIVER_MANIFEST);
            }
            //intentToSend.setComponent(null);
            //intentToSend.setComponent(new ComponentName(Constants.GLOBAL_CONSTANTS.mAppPackageNameWearReceiver,".WearReceiverBroadCastStart"));
            //intentToSend.setPackage(Constants.GLOBAL_CONSTANTS.mAppPackageName);
            if (!sdData.serverOK) sdData.serverOK = true;
            //intentToSend.setComponent(new ComponentName(Constants.GLOBAL_CONSTANTS.mAppPackageNameWearReceiver,".WearReceiverBroadCastStart"));
            intentToSend.putExtra(Constants.GLOBAL_CONSTANTS.mSdDataPath, sdData.toSettingsJSON());
            //intentToSend.setPackage(useSdServerBinding().getPackageName());
            //aWIntent.setComponent(null);

            logDebug_aWIntent(intentToSend);
            sendBroadcastToWearReceiver(intentToSend);
            connectionState = 2;

            /*PendingIntent pIntent = PendingIntent.getBroadcast(useSdServerBinding(), useSdServerBinding().mStartId, aWIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT );
            try {
                pIntent.send();
            } catch (PendingIntent.CanceledException e) {
                throw new RuntimeException(e);
            }*/
        } catch (android.content.ActivityNotFoundException anfe) {
            aWIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + Constants.GLOBAL_CONSTANTS.mAppPackageName));
            aWIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            useSdServerBinding().startActivity(aWIntent);
        }

    }

    public void intentReceivedAction(Intent intent){
         receivedIntentByBroadCast = intent;
         onStartReceived();
    }

    public void startWearSDApp(){
        try{
            aWIntent = aWIntentBase;
            SdData sdData = getSdData();
            aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.intentAction, Uri.parse(Constants.ACTION.START_WEAR_APP_ACTION));
            sdData.mDataType = Constants.GLOBAL_CONSTANTS.mSettingsString;
            if (!sdData.serverOK) sdData.serverOK = true;
            aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.mSdDataPath, sdData.toSettingsJSON());
            sendBroadcastToWearReceiver(aWIntent);
        }catch ( Exception e ){
            Log.e(TAG,"startWearSDApp: Error occurred",e);
        }

    }

    public void startMobileSD(){
        try{
            aWIntent = aWIntentBase;
            SdData sdData = getSdData();
            aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.intentAction,Constants.ACTION.PUSH_SETTINGS_ACTION);
            if (!sdData.serverOK) sdData.serverOK = true;
            aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.mSdDataPath, sdData.toSettingsJSON());
            sendBroadcastToWearReceiver(aWIntent);
        }catch ( Exception e ){
            Log.e(TAG,"startWearSDApp: Error occurred",e);
        }


    }

    public void mobileBatteryPctUpdate(){
        try{
            if (Objects.isNull(aWIntentBase)||Objects.isNull(aWIntentBaseManifest)||connectionState<=10)
                return;
            Intent intentToSend = aWIntentBase;
            intentToSend.removeExtra(Constants.GLOBAL_CONSTANTS.intentAction);
            intentToSend.setData(null);
            intentToSend.removeExtra(Constants.GLOBAL_CONSTANTS.dataType);
            intentToSend.putExtra(Constants.GLOBAL_CONSTANTS.intentAction,Constants.ACTION.BATTERYUPDATE_ACTION);
            intentToSend.putExtra(Constants.GLOBAL_CONSTANTS.mPowerLevel, useSdServerBinding().batteryPct);
            intentToSend.setAction(Constants.ACTION.BROADCAST_TO_WEARRECEIVER);
            sendBroadcastToWearReceiver(intentToSend);
        }catch ( Exception e ){
            Log.e(TAG,"startWearSDApp: Error occurred",e);
        }
    }

    /**
     * Send the watch settings that are stored as class member
     * variables to the watch.
     */
    public void sendWatchSdSettings() {
        Log.v(TAG, "sendWatchSdSettings() - preparing settings dictionary.. mSampleFreq=" + mSampleFreq);
        mUtil.writeToSysLogFile("SdDataSourceAw.sendWatchSdSettings()");
        /*SdData mSdDataOut = getSdData();
        mSdDataOut.dataTime.setToNow();
        mSdDataOut.mDataType = "settings";
        mSdDataOut.serverOK = mUtil.isServerRunning();

        if (!mUtil.isServerRunning())
            Log.v(TAG, "sendWatchSdSettings returning without mSdData.HaveSettings");
        else Log.v(TAG, "sendWatchSdSettings returning with mSdData.HaveSettings");
        if ((mSdDataOut.phoneName == null) || (mSdDataOut.phoneName == ""))
            mSdDataOut.phoneName = Build.HOST;
        String text = mSdDataOut.toSettingsJSON();
        sendMessage(MESSAGE_ITEM_OSD_DATA_RECEIVED, text);*/
    }

    /**
     * Request Watch App to send us its latest data.
     * Will be received as a message by the receiveData handler
     */
    public void getWatchData() {
        Log.v(TAG, "getData() - requesting data from watch");
        mUtil.writeToSysLogFile("SdDataSourceAw.getData() - requesting data from Android Wear");


    }

    /**
     * Send our latest settings to the watch, then request Pebble App to send
     * us its latest settings so we can check it has been set up correctly..
     * Will be received as a message by the receiveData handler
     */
    public void getWatchSdSettings() {
        Log.v(TAG, "getWatchSdSettings() - sending required settings to pebble");
        mUtil.writeToSysLogFile("SdDataSourceAw.getWatchSdSettings()");
        try {
            ((SdServer) mSdDataReceiver).mSdData.haveSettings = true;
        } catch (Exception e) {
            Log.e(TAG, "startWearSDApp: Error occurred", e);
        }
        //sendMessage(Constants.GLOBAL_CONSTANTS.MESSAGE_ITEM_OSD_DATA_REQUESTED, "Would you please give me your settings?");
    }

}







