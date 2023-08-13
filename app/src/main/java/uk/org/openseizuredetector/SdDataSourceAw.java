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
import android.app.Activity;
import android.app.Application;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.strictmode.IntentReceiverLeakedViolation;
import android.preference.PreferenceManager;
import android.text.SpannableString;
import android.text.format.Time;
import android.text.util.Linkify;
import android.util.Log;

import org.checkerframework.checker.units.qual.C;

import java.util.Objects;
import java.util.concurrent.CancellationException;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;


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

    public SdDataSourceAw(Context context, Handler handler,
                          SdDataReceiver sdDataReceiver) {
        super(context, handler, sdDataReceiver);
        mName = "AndroidWear";
        // Set default settings from XML files (mContext is set by super().
        PreferenceManager.setDefaultValues(mContext,
                R.xml.network_passive_datasource_prefs, true);

        mContext = context;
        intentBroadCastReceiver = new IntentBroadCastReceiver();


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
        };
        public boolean isRegistered = false;

        /**
         * register receiver
         * @param context - Context
         * @param filter - Intent Filter
         * @return see Context.registerReceiver(BroadcastReceiver,IntentFilter)
         */
        public Intent register(Context context, IntentFilter filter) {
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P){
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
                            ? context.registerReceiver(this, filter)
                            : null;
                } catch (Exception receiverLeakedViolation) {
                    Log.e(TAG,"onReceive() " ,receiverLeakedViolation);
                    return register(context,filter);
                } finally {
                    isRegistered = true;
                }
            }else {
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
                            ? context.registerReceiver(this, filter)
                            : null;
                } finally {
                    isRegistered = true;
                }
            }
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
            Log.d(TAG,this.getClass().getCanonicalName() + "Received command to unregister");
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
            Log.i(TAG,this.getClass().getCanonicalName() + " onReceive: received broadcast.");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                goAsync();
            }
            if (!Objects.equals(intent,null))
                if (Constants.ACTION.BROADCAST_TO_SDSERVER.equals(intent.getAction()))
                    intentReceivedAction(intent);
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
            if (Objects.isNull(mUtil)) mUtil = new OsdUtil(mContext, mHandler);
            if (Objects.equals(intentBroadCastReceiver, null))
                intentBroadCastReceiver = new IntentBroadCastReceiver();
            if (intentBroadCastReceiver.isRegistered)
                intentBroadCastReceiver.unregister(mContext);
            IntentFilter broadCastToSdServer = new IntentFilter(Constants.ACTION.BROADCAST_TO_SDSERVER);
            intentBroadCastReceiver.register(mContext, broadCastToSdServer);
            Log.i(TAG, "onCreate(): reached");
            if ( !Objects.equals(receivedIntentByBroadCast, null)) {
                try {

                    Log.i(TAG, "got intent and count of extras:" + receivedIntentByBroadCast.getExtras().size());
                } catch (Exception e) {
                    Log.e(TAG, "onCreate: ", e);
                }

                if (!Objects.equals(receivedIntentByBroadCast, null))
                    if (receivedIntentByBroadCast.hasExtra(Constants.GLOBAL_CONSTANTS.returnPath)) {
                        if (Constants.GLOBAL_CONSTANTS.mAppPackageNameWearReceiver.equals(receivedIntentByBroadCast.getStringExtra(Constants.GLOBAL_CONSTANTS.returnPath))) {
                            Log.i(TAG, "inOnStartReceived");
                            if (receivedIntentByBroadCast.hasExtra(Constants.GLOBAL_CONSTANTS.intentAction)) {
                                receivedAction = receivedIntentByBroadCast.getStringExtra(Constants.GLOBAL_CONSTANTS.intentAction);
                                Log.d(TAG,"inOnStartReceived(): received action: " +receivedAction);
                            }

                            if (Constants.ACTION.REGISTER_START_INTENT.equals(receivedAction)){
                                Log.d(TAG,"onStartReceived: received REGISTER_START_INTENT in Intent.");
                            }


                            if (Constants.ACTION.REGISTERED_START_INTENT.equals(receivedAction))
                            {
                                sdBroadCastReceived = true;
                                if (registeredAllBroadCastIntents()){
                                    aWIntent = aWIntentBase;
                                    aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.intentAction, Constants.ACTION.REGISTERED_START_INTENT_AW);
                                    mContext.sendBroadcast(aWIntent);
                                }
                            }

                            if (Constants.ACTION.REGISTER_WEARRECEIVER_INTENT.equals(receivedAction)){
                                if (receivedIntentByBroadCast.hasExtra(Constants.GLOBAL_CONSTANTS.wearReceiverServiceIntent)) {
                                    aWIntentBase = receivedIntentByBroadCast.getParcelableExtra(Constants.GLOBAL_CONSTANTS.wearReceiverServiceIntent);
                                    aWIntentBase.putExtra(Constants.GLOBAL_CONSTANTS.returnPath, Constants.GLOBAL_CONSTANTS.mAppPackageName);
                                    aWIntentBase.setAction(Constants.ACTION.BROADCAST_TO_WEARRECEIVER);
                                    aWIntentBase.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                                    aWIntentBase.setComponent(null);
                                    sdAwBroadCastReceived = true;
                                    if (registeredAllBroadCastIntents()){
                                        aWIntent = aWIntentBase;
                                        aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.intentReceiver,receivingIntent);
                                        aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.intentAction, Constants.ACTION.REGISTERED_WEARRECEIVER_INTENT);
                                        mContext.sendBroadcast(aWIntent);
                                    }
                                }else{
                                    Log.e(TAG,"onStartReceived(): registering WearReceiverIntent without intent attached", new Throwable());
                                }
                            }

                            //Sending return from function if aWIntent is null. With luck and a retry
                            //the process can continue.
                            if (Objects.isNull(aWIntentBase)) {
                                Intent lAwIntent = aWIntent;
                                lAwIntent.putExtra(Constants.GLOBAL_CONSTANTS.intentAction, Constants.ACTION.REGISTER_WEARRECEIVER_INTENT);
                                mContext.sendBroadcast(lAwIntent);
                                return;
                            }

                            if (Constants.ACTION.REGISTERED_WEARRECEIVER_INTENT.equals(receivedAction)) {

                                sdBroadCastReceived = true;
                                if (registeredAllBroadCastIntents()){
                                    aWIntent = aWIntentBase;
                                    aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.intentAction, Constants.ACTION.REGISTER_WEAR_LISTENER);
                                    mContext.sendBroadcast(aWIntent);
                                }
                                return;
                            }
                            if (Constants.ACTION.REGISTERED_WEAR_LISTENER.equals(receivedAction)) {
                                useSdServerBinding().mSdData.serverOK = true;
                                mSdData = getSdData();

                                sdBroadCastReceived = true;
                                if (registeredAllBroadCastIntents()){
                                    aWIntent = aWIntentBase;
                                    aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.intentAction, Constants.ACTION.CONNECT_WEARABLE_INTENT);
                                    mContext.sendBroadcast(aWIntent);
                                }
                                return;
                            }



                            if (Constants.ACTION.CONNECTION_WEARABLE_CONNECTED.equals(receivedAction)){
                                useSdServerBinding().mSdData.watchConnected = true;
                                mobileBatteryPctUpdate();
                                return;
                            }

                            if (Constants.ACTION.CONNECTION_WEARABLE_RECONNECTED.equals(receivedAction)){
                                useSdServerBinding().mSdData.watchConnected = true;
                                mobileBatteryPctUpdate();
                                return;
                            }

                            if (Constants.ACTION.CONNECTION_WEARABLE_DISCONNECTED.equals(receivedAction)){
                                useSdServerBinding().mSdData.watchConnected = false;
                                return;
                            }

                            if (Constants.ACTION.PUSH_SETTINGS_ACTION.equals(receivedAction)) {
                                try{
                                    updatePrefs();
                                    mHandler.postDelayed(() -> {
                                        aWIntent = aWIntentBase;
                                        aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.intentAction, Constants.ACTION.PUSH_SETTINGS_ACTION);
                                        aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.mSdDataPath, getSdData().toSettingsJSON());
                                        mContext.sendBroadcast(aWIntent);
                                    }, 100);
                                }catch (Exception e){
                                    Log.e(TAG,"onStartReceived(): PUSH_SETTINGS_ACTION:" ,e);
                                }
                                return;
                            }

                            if (Constants.ACTION.SDDATA_TRANSFER_TO_SD_SERVER.equals(receivedAction)) {
                                try {
                                    if (receivedIntentByBroadCast.hasExtra(Constants.GLOBAL_CONSTANTS.mSdDataPath)) {
                                        String a = updateFromJSON(receivedIntentByBroadCast.getStringExtra(Constants.GLOBAL_CONSTANTS.mSdDataPath));

                                        Log.v(TAG, "result from updateFromJSON(): " + a);
                                        if (Objects.equals(a, "sendSettings")) {
                                            super.updatePrefs();
                                            sendWatchSdSettings();
                                            getWatchSdSettings();
                                        }
                                        if (Objects.equals(a, "ERROR")){
                                            Log.e(TAG,"Error in updateFromJSON: ");
                                        }
                                        if (!getSdData().haveSettings)
                                            useSdServerBinding().mSdData.haveSettings = true;
                                        if (!getSdData().watchConnected)
                                            useSdServerBinding().mSdData.watchConnected = true;
                                        if (!getSdData().watchAppRunning)
                                            useSdServerBinding().mSdData.watchAppRunning = true;
                                        if (!getSdData().haveData && ! a.equals("ERROR"))
                                            useSdServerBinding().mSdData.haveData = true;
                                    }

                                } catch (Exception e) {
                                    Log.e(TAG,"onStartReceived()",e);
                                }
                            }

                            if (Constants.ACTION.STOP_WEAR_SD_ACTION.equals(receivedAction)) {
                                //if (useSdServerBinding().)Log.i(TAG," fixme: add here liveData from startup and main activity");
                                useSdServerBinding().mSdData.haveSettings = false;
                                useSdServerBinding().mSdData.haveData = false;
                                useSdServerBinding().mSdData.watchConnected = false;
                                useSdServerBinding().mSdData.mDataType = receivedAction;
                                if (useSdServerBinding().uiLiveData.hasActiveObservers())
                                    useSdServerBinding().uiLiveData.signalChangedData();
                            }




                        }
                    } else
                        mUtil.showToast("inOnStartWithIntent");
            }
        }catch (Exception e){
            Log.e(TAG,"onStartReceived(): ",e);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.CUPCAKE)
    private boolean isWearReceiverInstalled(){

        mHandler = new Handler(mContext.getMainLooper());
        mUtil = new OsdUtil(mContext, mHandler);
        PackageManager manager = mContext.getPackageManager();
        aWIntent = manager.getLaunchIntentForPackage(Constants.GLOBAL_CONSTANTS.mAppPackageNameWearReceiver);
        Log.i(TAG,"aWIntent: " + aWIntent);
        if (aWIntent == null) {
            mUtil.showToast("Error - OpenSeizureDetector Android Wear App is not installed - please install it and run it");
            installAwApp();
            return false;
        } else {

            aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.returnPath, Constants.GLOBAL_CONSTANTS.mAppPackageName);
            aWIntent.removeCategory(Intent.CATEGORY_LAUNCHER);
            aWIntent.setAction(Constants.ACTION.BROADCAST_TO_WEARRECEIVER_MANIFEST);
            //AddComponent only working for implicit BroadCast For explicit (when app is already running and broadcast registered.
            aWIntent.setComponent(new ComponentName(Constants.GLOBAL_CONSTANTS.mAppPackageNameWearReceiver, Constants.GLOBAL_CONSTANTS.mAppPackageNameWearReceiver + ".WearReceiverBroadCastStart"));
            aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.intentReceiver, receivingIntent);
            aWIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            aWIntentBaseManifest = aWIntent;

            return true;
        }
    }

    /**
     * Start the datasource updating - initialises from sharedpreferences first to
     * make sure any changes to preferences are taken into account.
     */
    @Override
    public void start() {
        Log.i(TAG, "start()");
        mUtil.writeToSysLogFile("SdDataSourceAw.start()");
        super.start();
        mNrawdata = 0;


        //START_WEAR_APP_ACTION.toLower()
        // Now start the AndroidWear companion app
        checkAndUnRegisterReceiver();

        IntentFilter broadCastToSdServer = new IntentFilter(Constants.ACTION.BROADCAST_TO_SDSERVER);
        intentBroadCastReceiver.register(mContext, broadCastToSdServer);
        //waitReceiverRegistered();
        if (isWearReceiverInstalled()) {
            try {

                intentReceivedAction(aWIntent);
                onStartReceived();

                //aWIntent.setClassName(aWIntent.getPackage(),".WearReceiver");
                //aWIntent = new Intent();
                //aWIntent.setPackage(Constants.GLOBAL_CONSTANTS.mAppPackageNameWearReceiver);
                //FIXME: tell me how to incorporate <data ######## /> with:
                // .setData()
                // and: launch DebugActivity from debugger.
                // (this is one way of 2 way communication.)
                // Also tell me how to use activity without broadcast. In this context is no getActivity() or getIntent()
                SdData sdData = getSdData();
                //aWIntent.setData(Constants.GLOBAL_CONSTANTS.mStartUri);
                //aWIntent = new Intent();
                aWIntent = aWIntentBaseManifest;
                aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.dataType, Constants.GLOBAL_CONSTANTS.mStartUri);
                aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.intentAction, Constants.ACTION.STARTFOREGROUND_ACTION);

                //aWIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.startId, useSdServerBinding().mStartId);
                aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.startIdWearReceiver, startIdWearReceiver);
                startIdWearReceiver++;
                aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.startIdWearSd, startIdWearSd);
                startIdWearSd++;
                PendingIntent pIntent = PendingIntent.getBroadcast(mContext, useSdServerBinding().mStartId, aWIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT);
                pIntent.send();

                aWIntent = aWIntentBaseManifest;
                aWIntent.removeExtra(Constants.GLOBAL_CONSTANTS.intentAction);
                aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.intentAction, Constants.ACTION.REGISTER_START_INTENT);

                pIntent = PendingIntent.getBroadcast(mContext, useSdServerBinding().mStartId, aWIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT);
                pIntent.send();


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

    private void checkAndUnRegisterReceiver(){
        if (Objects.nonNull(intentBroadCastReceiver)){

            if (intentBroadCastReceiver.isRegistered)
                intentBroadCastReceiver.unregister(mContext);
        }

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
            PendingIntent pIntent = PendingIntent.getBroadcast(mContext, useSdServerBinding().mStartId, aWIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT);
            pIntent.send();


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

        super.stop();
        // FIXME - send an intent to tell the Android Wear companion app to shutdown.
    }

    private void installAwApp() {
        // FIXME - I don't think this works!
        // from https://stackoverflow.com/questions/11753000/how-to-open-the-google-play-store-directly-from-my-android-application
        // First tries to open Play Store, then uses URL if play store is not installed.
        try {
            aWIntent = aWIntentBase;
            aWIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + Constants.GLOBAL_CONSTANTS.mAppPackageNameWearReceiver));
            aWIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(aWIntent);
        } catch (android.content.ActivityNotFoundException anfe) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + Constants.GLOBAL_CONSTANTS.mAppPackageName));
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(i);
        }
    }

    public void startWearReceiverApp(){
        try {
            aWIntent = aWIntentBase;
            SdData sdData = getSdData();
            aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.intentAction,Constants.ACTION.START_MOBILE_RECEIVER_ACTION);
            aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.mSdDataPath, sdData.toSettingsJSON());
            mContext.sendBroadcast(aWIntent);
        } catch (android.content.ActivityNotFoundException anfe) {
            aWIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + Constants.GLOBAL_CONSTANTS.mAppPackageName));
            aWIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(aWIntent);
        }

    }

    public void intentReceivedAction(Intent intent){
        mHandler.post(()->{
            receivedIntentByBroadCast = intent;
            onStartReceived();
        });
    }

    public void startWearSDApp(){
        try{
            aWIntent = aWIntentBase;
            SdData sdData = getSdData();
            aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.intentAction, Uri.parse(Constants.ACTION.START_WEAR_APP_ACTION));
            sdData.mDataType = Constants.GLOBAL_CONSTANTS.mSettingsString;
            aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.mSdDataPath, sdData.toSettingsJSON());
            mContext.sendBroadcast(aWIntent);
        }catch ( Exception e ){
            Log.e(TAG,"startWearSDApp: Error occoured",e);
        }

    }

    public void startMobileSD(){
        try{
            aWIntent = aWIntentBase;
            SdData sdData = getSdData();
            aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.intentAction,Constants.ACTION.PUSH_SETTINGS_ACTION);
            aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.mSdDataPath, sdData.toSettingsJSON());
            mContext.sendBroadcast(aWIntent);
        }catch ( Exception e ){
            Log.e(TAG,"startWearSDApp: Error occoured",e);
        }


    }

    public void mobileBatteryPctUpdate(){
        try{
            if (Objects.isNull(aWIntentBase))
                return;
            aWIntent = aWIntentBase;
            aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.intentAction,Constants.ACTION.BATTERYUPDATE_ACTION);
            aWIntent.putExtra(Constants.GLOBAL_CONSTANTS.mPowerLevel, useSdServerBinding().batteryPct);
            mContext.sendBroadcast(aWIntent);
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
            Log.e(TAG, "startWearSDApp: Error occoured", e);
        }
        //sendMessage(Constants.GLOBAL_CONSTANTS.MESSAGE_ITEM_OSD_DATA_REQUESTED, "Would you please give me your settings?");
    }

}







