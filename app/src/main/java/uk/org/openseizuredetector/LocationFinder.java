package uk.org.openseizuredetector;

/**
 */

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

import java.util.Timer;
import java.util.TimerTask;

interface SdLocationReceiver {
    public void onSdLocationReceived(Location ll);
}


public class LocationFinder implements LocationListener
{
    SdLocationReceiver mSdLocationReceiver = null;
    Location mLastLocation = null;
    OsdUtil mUtil;
    Handler mHandler;
    Context mContext;
    Timer mTimeoutTimer = null;
    LocationManager mLocationManager = null;
    LocationListener mLocationListener;
    int mTimeoutPeriod = 60;   // Location search timeout period in seconds.

    String TAG="LocationFinder";

    LocationFinder(Context context) {
        mHandler = new Handler();
        mUtil = new OsdUtil(context, mHandler);
        mContext = context;
        mLocationListener = this;
    }

    public Location getLastLocation() {
        return mLastLocation;
    }

    public void getLocation(SdLocationReceiver sdLocationReceiver) {
        mSdLocationReceiver = sdLocationReceiver;
        // Acquire a reference to the system Location Manager
        mLocationManager = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
        // Register with the Location Manager to receive location updates using both network and GPS
        mLocationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, this);
        mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, this);

        mTimeoutTimer = new Timer();
        mTimeoutTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                Log.v(TAG,"mTimeOutTimer expired - returning last location");
                //mUtil.showToast("mTimeOutTimer expired - returning last location");
                mLocationManager.removeUpdates(mLocationListener);
                mSdLocationReceiver.onSdLocationReceived(mLastLocation);
            }
        }, mTimeoutPeriod * 1000);

    }

    @Override
    public void onLocationChanged(Location location) {
        Log.v(TAG,"onLocationChanged - "+location.toString());

        // if we do not have a last location, this is the best we have!
        if (mLastLocation == null) {
            mLastLocation = location;
        }

        // if this is more accurate than mLastLocation, store it.
        if (location.getAccuracy() < mLastLocation.getAccuracy()) {
            mLastLocation = location;
        }

    }

    @Override
    public void onStatusChanged(String s, int i, Bundle bundle) {

    }

    @Override
    public void onProviderEnabled(String s) {

    }

    @Override
    public void onProviderDisabled(String s) {

    }
}

