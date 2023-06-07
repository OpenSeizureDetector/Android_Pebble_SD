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

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.util.Log;

import static java.lang.Math.sqrt;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;


/**
 * A data source that uses the accelerometer built into the phone to provide seizure detector data for testing purposes.
 * Note that this is unlikely to be useable as a viable seizure detector because the phone must be firmly attached to the part of the body that
 * will shake during a seizure.
 */
public class SdDataSourcePhone extends SdDataSource implements SensorEventListener {
    private String TAG = "SdDataSourcePhone";

    private Intent sdServerIntent ;

    private final static int NSAMP = 250;
    private SensorManager mSensorManager;
    private Sensor mSensor;
    private int mMode = 0;   // 0=check data rate, 1=running
    private SensorEvent mStartEvent = null;
    private long mStartTs = 0;
    public double mSampleFreq = 0;
    private double mSampleTimeUs = -1;
    private int mCurrentMaxSampleCount = -1;
    private double mConversionSampleFactor;
    private SdData mSdDataSettings ;
    private SdServer sdServer;

    private boolean sensorsActive = false;
    private List<Double> rawDataList;
    private List<Double> rawDataList3D;




    /**
     * SdDataSourcePhone Class. This class handles simulation data for
     * the carrier of the phone.
     * @param context : Android context, usually actual class of application or given
     *                  surroundings of parent.
     * @param handler : Handler handles out-of-activity requests.
     * @param sdDataReceiver : Through this object will the child objects of this
     *                         class be available.
     */
    public SdDataSourcePhone(Context context, Handler handler,
                             SdDataReceiver sdDataReceiver) {
        super(context, handler, sdDataReceiver);


        mName = "Phone";
        // Set default settings from XML files (mContext is set by super().
         PreferenceManager.setDefaultValues(mContext,
                R.xml.network_passive_datasource_prefs, true);
        PreferenceManager.setDefaultValues(mContext,
                R.xml.seizure_detector_prefs, true);
        rawDataList = new ArrayList();
        rawDataList3D = new ArrayList();
        updatePrefs();
        Log.d(TAG,"logging value of mSdData: "+super.mSdData.mDefaultSampleCount);
        //mSdDataSettings = sdDataReceiver.mSdData;
        sdServer = (SdServer) sdDataReceiver;

        sdServerIntent = new Intent(context,SdDataSource.class);
        mSdData = pullSdData();

    }


    private  void bindSensorListeners(){
        if (mSampleTimeUs < (double) SensorManager.SENSOR_DELAY_NORMAL ||
                Double.isInfinite(mSampleTimeUs) ||
                Double.isNaN(mSampleTimeUs))
        {
            calculateStaticTimings();
            if (mSampleTimeUs <= 0d)
                mSampleTimeUs = SensorManager.SENSOR_DELAY_NORMAL;
        }
        mSensorManager = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
        Sensor mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        // registering listener with reference to (this).onSensorChanged , mSampleTime in MicroSeconds
        // and bufferingTime , sampleTime * 3 in order to save the battery, calling back to mHandler
        mSensorManager.registerListener(this, mSensor, (int) mSampleTimeUs,(int) mSampleTimeUs * 3, mHandler);
        sensorsActive = true;

    }

    private void unBindSensorListeners(){
        if (sensorsActive)
            mSensorManager.unregisterListener(this);
        sensorsActive = false;
    }

    /**
     * Start the datasource updating - initialises from sharedpreferences first to
     * make sure any changes to preferences are taken into account.
     */
    @Override
    public void start() {
        Log.i(TAG, "start()");
        mUtil.writeToSysLogFile("SdDataSourcePhone.start()");
        mSensorManager = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
        mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        if (!Objects.equals(mSdDataSettings,null))if (mSdDataSettings.mDefaultSampleCount >0d && mSdDataSettings.analysisPeriod > 0d ) {
            calculateStaticTimings();
        }
        if(!useSdServerBinding().uiLiveData.isListeningInContext(this)){
            useSdServerBinding().uiLiveData.addToListening(this);
        }


        super.start();
        Log.i(TAG,"onStart(): returned from SdDataSource.onStart");
        mCurrentMaxSampleCount = getSdData().mDefaultSampleCount;
        bindSensorListeners();
        mIsRunning = true;
    }

    /**
     * Stop the datasource from updating
     */
    @Override
    public void stop() {
        Log.i(TAG, "stop()");
        mUtil.writeToSysLogFile("SdDataSourcePhone.stop()");
        mSensorManager.unregisterListener(this);
        if(useSdServerBinding().uiLiveData.isListeningInContext(this)){
            useSdServerBinding().uiLiveData.removeFromListening(this);
        }

        super.stop();
        Log.i(TAG,"onStop(): returned from SdDataSource.onStop");
        unBindSensorListeners();
        Log.i(TAG,"onStart(): returned from unBindSensorListners");

        mIsRunning = false;
    }





    @Override
    public void onSensorChanged(SensorEvent event) {
        if (Objects.isNull(mSdDataSettings))
            mSdDataSettings = pullSdData();
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            // we initially start in mMode=0, which calculates the sample frequency returned by the sensor, then enters mMode=1, which is normal operation.
            if (mMode == 0) {
                if (mStartEvent==null) {
                    Log.v(TAG,"onSensorChanged(): mMode=0 - checking Sample Rate - mNSamp = "+mSdData.mNsamp);
                    Log.v(TAG,"onSensorChanged(): saving initial event data");
                    mStartEvent = event;
                    mStartTs = event.timestamp;
                    mSdData.mNsamp = 0;
                } else {
                    mSdData.mNsamp ++;
                }
                if (mSdData.mNsamp >= mSdDataSettings.mDefaultSampleCount) {
                    Log.v(TAG, "onSensorChanged(): Collected Data = final TimeStamp=" + event.timestamp + ", initial TimeStamp=" + mStartTs);
                    mSdData.dT = 1.0e-9 * (event.timestamp - mStartTs);
                    mCurrentMaxSampleCount = mSdData.mNsamp;
                    mSdData.mSampleFreq = (int) (mSdData.mNsamp / mSdData.dT);
                    mSdData.haveSettings = true;
                    Log.v(TAG, "onSensorChanged(): Collected data for " + mSdData.dT + " sec - calculated sample rate as " + mSampleFreq + " Hz");
                    accelerationCombined = sqrt(event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2]);
                    calculateStaticTimings();
                    unBindSensorListeners();
                    bindSensorListeners();
                    mMode = 1;
                    mSdData.mNsamp = 0;
                    mStartTs = event.timestamp;
                }
            } else if (mMode==1) {
                // mMode=1 is normal operation - collect NSAMP accelerometer data samples, then analyse them by calling doAnalysis().

                if (mSdData.mNsamp == mCurrentMaxSampleCount  )
                {


                    // Calculate the sample frequency for this sample, but do not change mSampleFreq, which is used for
                    // analysis - this is because sometimes you get a very long delay (e.g. when disconnecting debugger),
                    // which gives a very low frequency which can make us run off the end of arrays in doAnalysis().
                    // FIXME - we should do some sort of check and disregard samples with long delays in them.
                    mSdData.dT = 1e-9 * (event.timestamp - mStartTs);
                    int sampleFreq = (int) (mSdData.mNsamp / mSdData.dT);
                    Log.v(TAG, "onSensorChanged(): Collected " + NSAMP + " data points in " + mSdData.dT + " sec (=" + sampleFreq + " Hz) - analysing...");

                    // DownSample from the **Hz received frequency to 25Hz and convert to mg.
                    // FIXME - we should really do this properly rather than assume we are really receiving data at 50Hz.
                    int readPosition = 1;

                    for (int i = 0; i < Constants.SD_SERVICE_CONSTANTS.defaultSampleCount ; i++) {
                        readPosition = (int) (i / mConversionSampleFactor);
                        if (readPosition < rawDataList.size() ){
                            mSdData.rawData[i] = gravityScaleFactor * rawDataList.get(readPosition) / SensorManager.GRAVITY_EARTH;
                            mSdData.rawData3D[i] = gravityScaleFactor * rawDataList3D.get(readPosition) / SensorManager.GRAVITY_EARTH;
                            mSdData.rawData3D[i + 1] = gravityScaleFactor * rawDataList3D.get(readPosition + 1) / SensorManager.GRAVITY_EARTH;
                            mSdData.rawData3D[i + 2] = gravityScaleFactor * rawDataList3D.get(readPosition + 2) / SensorManager.GRAVITY_EARTH;
                            //Log.v(TAG,"i="+i+", rawData="+mSdData.rawData[i]+","+mSdData.rawData[i/2]);
                        }
                    }
                    rawDataList.clear();
                    rawDataList3D.clear();
                    mSdData.mNsamp = Constants.SD_SERVICE_CONSTANTS.defaultSampleCount;
                    mSdData.mHR = -1d;
                    mSdData.mHRAlarmActive = false;
                    mSdData.mHRAlarmStanding = false;
                    mSdData.mHRNullAsAlarm = false;
                    doAnalysis();
                    mSdData.mNsamp = 0;
                    mStartTs = event.timestamp;
                    return;
                }else if (!Objects.equals(rawDataList, null) && rawDataList.size() <= mCurrentMaxSampleCount ) {

                    float x = event.values[0];
                    float y = event.values[1];
                    float z = event.values[2];
                    //Log.v(TAG,"Accelerometer Data Received: x="+x+", y="+y+", z="+z);
                    rawDataList.add( sqrt(x * x + y * y + z * z));
                    rawDataList3D.add((double) x);
                    rawDataList3D.add((double) y);
                    rawDataList3D.add((double) z);
                    mSdData.mNsamp++;
                    return;
                }else if (mSdData.mNsamp > mCurrentMaxSampleCount - 1) {
                    Log.v(TAG, "onSensorChanged(): Received data during analysis - ignoring sample");
                    return;
                } else if (rawDataList.size() >= mCurrentMaxSampleCount){
                    Log.v(TAG, "onSensorChanged(): mSdData.mNSamp and mCurrentMaxSampleCount differ in size");
                    rawDataList.remove(0);
                    rawDataList3D.remove(0);
                    rawDataList3D.remove(0);
                    rawDataList3D.remove(0);
                    return;
                }
                else{
                    Log.v(TAG, "onSensorChanged(): Received empty data during analysis - ignoring sample");
                }

            } else {
                Log.v(TAG,"onSensorChanged(): ERROR - Mode "+mMode+" unrecognised");
            }
        }

    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        Log.v(TAG,"onAccuracyChanged()");
    }




}





