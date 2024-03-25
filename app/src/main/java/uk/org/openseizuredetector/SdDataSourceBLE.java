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

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.text.format.Time;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;


/**
 * A data source that registers for BLE GATT notifications from a device and
 * waits to be notified of data being available.
 */
public class SdDataSourceBLE extends SdDataSource {
    private int MAX_RAW_DATA = 125;  // 5 seconds at 25 Hz.
    private String TAG = "SdDataSourceBLE";
    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private String mBluetoothDeviceAddress;
    private BluetoothGatt mBluetoothGatt;
    private int mConnectionState = STATE_DISCONNECTED;

    private int nRawData = 0;
    private double[] rawData = new double[MAX_RAW_DATA];
    private double[] rawData3d = new double[MAX_RAW_DATA * 3];
    private int mAccFmt = 0;
    private boolean waitForDescriptorWrite = false;

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;

    /*
    public final static String ACTION_GATT_CONNECTED =
            "com.example.bluetooth.le.ACTION_GATT_CONNECTED";
    public final static String ACTION_GATT_DISCONNECTED =
            "com.example.bluetooth.le.ACTION_GATT_DISCONNECTED";
    public final static String ACTION_GATT_SERVICES_DISCOVERED =
            "com.example.bluetooth.le.ACTION_GATT_SERVICES_DISCOVERED";
    public final static String ACTION_DATA_AVAILABLE =
            "com.example.bluetooth.le.ACTION_DATA_AVAILABLE";
    public final static String EXTRA_DATA =
            "com.example.bluetooth.le.EXTRA_DATA";
    */

    public static String SERV_DEV_INFO = "0000180a-0000-1000-8000-00805f9b34fb";
    public static String SERV_HEART_RATE = "0000180d-0000-1000-8000-00805f9b34fb";
    public static String CHAR_HEART_RATE_MEASUREMENT = "00002a37-0000-1000-8000-00805f9b34fb";

    public static String SERV_OSD = "000085e9-0000-1000-8000-00805f9b34fb";
    public static String CHAR_OSD_ACC_DATA = "000085e9-0001-1000-8000-00805f9b34fb";
    public static String CHAR_OSD_BATT_DATA = "000085e9-0002-1000-8000-00805f9b34fb";
    public static String CHAR_OSD_WATCH_ID = "000085e9-0003-1000-8000-00805f9b34fb";
    public static String CHAR_OSD_WATCH_FW = "000085e9-0004-1000-8000-00805f9b34fb";
    public static String CHAR_OSD_ACC_FMT = "000085e9-0005-1000-8000-00805f9b34fb";
    // Valid values are 0: 8 bit vector magnitude scaled so 1g=44
    public final static int ACC_FMT_8BIT = 0;
    public final static int ACC_FMT_16BIT = 1;
    public final static int ACC_FMT_3D = 3;
    public static String CHAR_OSD_STATUS = "000085e9-0006-1000-8000-00805f9b34fb";

    public static String SERV_INFINITIME_MOTION = "00030000-78fc-48fe-8e23-433b3a1942d0";
    public static String CHAR_INFINITIME_ACC_DATA = "00030002-78fc-48fe-8e23-433b3a1942d0";
    public static String CHAR_INFINITIME_OSD_STATUS = "00030078-78fc-48fe-8e23-433b3a1942d0";

    public static String CHAR_BATT_DATA = "00002a19-0000-1000-8000-00805f9b34fb";
    public static String SERV_BATT = "0000180f-0000-1000-8000-00805f9b34fb";

    // public static String CHAR_MANUF_NAME = "00002a29-0000-1000-8000-00805f9b34fb";
    // public static String CLIENT_CHARACTERISTIC_CONFIG = "00002902-0000-1000-8000-00805f9b34fb";
    private BluetoothGatt mGatt;
    private BluetoothGattCharacteristic mOsdChar;
    private BluetoothGattCharacteristic mStatusChar;


    public SdDataSourceBLE(Context context, Handler handler,
                           SdDataReceiver sdDataReceiver) {
        super(context, handler, sdDataReceiver);
        mName = "BLE";
    }


    /**
     * Start the datasource updating - initialises from sharedpreferences first to
     * make sure any changes to preferences are taken into account.
     */
    public void start() {
        super.start();
        Log.i(TAG, "start() - mBleDeviceAddr="+mBleDeviceAddr);
        mUtil.writeToSysLogFile("SdDataSourceBLE.start() - mBleDeviceAddr=" + mBleDeviceAddr);

        if (mBleDeviceAddr == "" || mBleDeviceAddr == null) {
            final Intent intent = new Intent(this.mContext, BLEScanActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(intent);
        }

        // Note, these values are set in BleScanActivity and written to shared preferences, which
        // ae read in SdDataSource.java
        // FIXME:  Read the shared preferences in this class so SdDataSource does not need to know
        // FIXME:   about BLE details.
        Log.i(TAG, "mBLEDevice is " + mBleDeviceName + ", Addr=" + mBleDeviceAddr);
        mSdData.watchSdName = mBleDeviceName;
        mSdData.watchPartNo = mBleDeviceAddr;

        bleConnect();

    }

    private void bleConnect() {
        mSdData.watchConnected = false;
        mSdData.watchAppRunning = false;
        mBluetoothGatt = null;
        mConnectionState = STATE_DISCONNECTED;
        if (mBluetoothManager == null) {
            mBluetoothManager = (BluetoothManager) mContext.getSystemService(Context.BLUETOOTH_SERVICE);
            if (mBluetoothManager == null) {
                Log.e(TAG, "bleConnect(): Unable to initialize BluetoothManager.");
                return;
            }
        }

        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "bleConnect(): Unable to obtain a BluetoothAdapter.");
            return;
        }

        if (mBluetoothAdapter == null || mBleDeviceAddr == null) {
            Log.w(TAG, "bleConnect(): BluetoothAdapter not initialized or unspecified address.");
            return;
        }

        BluetoothDevice device;
        try {
            device = mBluetoothAdapter.getRemoteDevice(mBleDeviceAddr);
        } catch (Exception e) {
            Log.w(TAG, "bleConnect(): Error connecting to device address " + mBleDeviceAddr + ".");
            device = null;
        }
        if (device == null) {
            Log.w(TAG, "bleConnect(): Device not found.  Unable to connect.");
            return;
        } else {
            // We want to directly connect to the device, so we are setting the autoConnect
            // parameter to false.
            mBluetoothGatt = device.connectGatt(mContext, true, mGattCallback);
            Log.d(TAG, "bleConnect(): Trying to create a new connection.");
            mBluetoothDeviceAddress = mBleDeviceAddr;
            mConnectionState = STATE_CONNECTING;
        }
    }

    private void bleDisconnect() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        // Un-register for BLE Notifications.
        if (mOsdChar != null) {
            setCharacteristicNotification(mOsdChar, false);
        }

        mBluetoothGatt.disconnect();
        if (mBluetoothGatt == null) {
            return;
        }
        mBluetoothGatt.close();
        mBluetoothGatt = null;
        mSdData.watchAppRunning = false;
        mSdData.watchConnected = false;
        mConnectionState = STATE_DISCONNECTED;

    }

    /**
     * Stop the datasource from updating
     */
    public void stop() {
        Log.i(TAG, "stop()");
        mUtil.writeToSysLogFile("SDDataSourceBLE.stop()");

        bleDisconnect();
        super.stop();
    }


    // Implements callback methods for GATT events that the app cares about.  For example,
    // connection change and services discovered.
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                mConnectionState = STATE_CONNECTED;
                mSdData.watchConnected = true;
                Log.i(TAG, "onConnectionStateChange(): Connected to GATT server.");
                // Attempts to discover services after successful connection.
                Log.i(TAG, "onConnectionStateChange(): Attempting to start service discovery:");
                mBluetoothGatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                mConnectionState = STATE_DISCONNECTED;
                mSdData.watchConnected = false;
                Log.i(TAG, "onConnectionStateChange(): Disconnected from GATT server");
                /**Log.i(TAG, "onConnectionStateChange(): Disconnected from GATT server - reconnecting after delay...");
                 bleDisconnect();  // Tidy up connections
                 // Wait 2 seconds to give the server chance to shutdown, then re-start it
                 mHandler.postDelayed(new Runnable() {
                 public void run() {
                 bleConnect();
                 }
                 }, 2000);
                 */
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            boolean foundOsdService = false;
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.v(TAG, "Services discovered");
                List<BluetoothGattService> serviceList = mBluetoothGatt.getServices();
                for (int i = 0; i < serviceList.size(); i++) {
                    String uuidStr = serviceList.get(i).getUuid().toString();
                    Log.v(TAG, "Service " + uuidStr);
                    List<BluetoothGattCharacteristic> gattCharacteristics =
                            serviceList.get(i).getCharacteristics();
                    if (uuidStr.equals(SERV_DEV_INFO)) {
                        Log.v(TAG, "Device Info Service Discovered");
                    } else if (uuidStr.equals(SERV_HEART_RATE)) {
                        Log.v(TAG, "Heart Rate Service Discovered");
                        for (BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics) {
                            String charUuidStr = gattCharacteristic.getUuid().toString();
                            if (charUuidStr.equals(CHAR_HEART_RATE_MEASUREMENT)) {
                                Log.v(TAG, "Subscribing to Heart Rate Measurement Change Notifications");
                                setCharacteristicNotification(gattCharacteristic, true);
                            }
                        }
                    } else if (uuidStr.equals(SERV_OSD)) {
                        Log.v(TAG, "OpenSeizureDetector Service Discovered");
                        foundOsdService = true;
                        for (BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics) {
                            String charUuidStr = gattCharacteristic.getUuid().toString();
                            if (charUuidStr.equals(CHAR_OSD_ACC_DATA)) {
                                Log.i(TAG, "Subscribing to Acceleration Data Change Notifications");
                                mOsdChar = gattCharacteristic;
                                setCharacteristicNotification(gattCharacteristic, true);
                            } else if (charUuidStr.equals(CHAR_OSD_STATUS)) {
                                Log.i(TAG, "Found OSD Status Characteristic");
                                mStatusChar = gattCharacteristic;
                            } else if (charUuidStr.equals(CHAR_OSD_BATT_DATA)) {
                                Log.i(TAG, "Subscribing to battery change Notifications");
                                executeReadCharacteristic(gattCharacteristic);
                                setCharacteristicNotification(gattCharacteristic, true);
                                executeReadCharacteristic(gattCharacteristic);
                            } else if (charUuidStr.equals(CHAR_OSD_WATCH_ID)) {
                                Log.i(TAG, "Reading Watch ID");
                                executeReadCharacteristic(gattCharacteristic);
                            } else if (charUuidStr.equals(CHAR_OSD_WATCH_FW)) {
                                Log.i(TAG, "Reading Watch Firmware Version");
                                executeReadCharacteristic(gattCharacteristic);
                            } else if (charUuidStr.equals(CHAR_OSD_ACC_FMT)) {
                                Log.i(TAG, "Reading Acceleration format code");
                                executeReadCharacteristic(gattCharacteristic);
                            }
                        }
                    } else if (uuidStr.equals(SERV_INFINITIME_MOTION)) {
                        Log.v(TAG, "Infinitime Motion Service Discovered");
                        foundOsdService = true;
                        for (BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics) {
                            String charUuidStr = gattCharacteristic.getUuid().toString();
                            if (charUuidStr.equals(CHAR_INFINITIME_ACC_DATA)) {
                                Log.i(TAG, "Subscribing to Infinitime Acceleration Data Change Notifications");
                                mOsdChar = gattCharacteristic;
                                mAccFmt = ACC_FMT_3D;  // Infinitime presents x, y, z data
                                setCharacteristicNotification(gattCharacteristic, true);
                            } else if (charUuidStr.equals(CHAR_INFINITIME_OSD_STATUS)) {
                                Log.i(TAG, "Found Infinitime OSD Status Characteristic");
                                mStatusChar = gattCharacteristic;
                            }
                        }
                    } else if (uuidStr.equals(SERV_BATT)) {
                        Log.v(TAG, "Battery Data Service Service Discovered");
                        for (BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics) {
                            String charUuidStr = gattCharacteristic.getUuid().toString();
                            Log.i(TAG, "batt char=" + charUuidStr);
                            if (charUuidStr.equals(CHAR_BATT_DATA)) {
                                Log.i(TAG, "Subscribing to Battery Data Change Notifications");
                                setCharacteristicNotification(gattCharacteristic, true);
                                Log.i(TAG, "Reading battery level");
                                executeReadCharacteristic(gattCharacteristic);
                            }
                        }
                    }
                }
                if (foundOsdService) {
                    mGatt = gatt;
                } else {
                    Log.i(TAG, "device is not offering the OSD Gatt Service - re-trying connection");
                    bleDisconnect();
                    // Wait 1 second to give the server chance to shutdown, then re-start it
                    mHandler.postDelayed(new Runnable() {
                        public void run() {
                            bleConnect();
                        }
                    }, 1000);
                }
            } else {
                Log.w(TAG, "onServicesDiscovered received: " + status);
            }
        }


        /**
         * executeReadCharacteristic runs the bluetoothGatt readCharacteristic command to read the value
         * of a given characteristic.
         * Because only one BLE operation can be taking place at a time, it may fail, in which case
         * the read is re-tried after a 100ms delay.
         * @param gattCharacteristic - the characteristic to be read.
         */
        private void executeReadCharacteristic(BluetoothGattCharacteristic gattCharacteristic) {
            if (gattCharacteristic != null) {
                boolean retVal = mBluetoothGatt.readCharacteristic(gattCharacteristic);
                if (retVal) {
                    Log.d(TAG, "executeReadCharacteristic - read initiated successfully");
                } else {
                    Log.d(TAG, "executeReadCharacteristic - read initiation failed - waiting, then re-trying");
                    mHandler.postDelayed(new Runnable() {
                        public void run() {
                            Log.w(TAG, "Executing delayed read of characteristic");
                            executeReadCharacteristic(gattCharacteristic);
                        }
                    }, 100);
                }
            } else {
                Log.i(TAG,"ExecuteReadCharacteristic() - gatCharacteristic is null, so not doing anything");
            }
        }

        /**
         * executeWriteCharacteristic runs the bluetoothGatt writeCharacteristic command to sent the value
         * of a given characteristic.
         * Because only one BLE operation can be taking place at a time, it may fail, in which case
         * the read is re-tried after a 100ms delay.
         * @param gattCharacteristic - the characteristic to be read.
         * @param valBytes[] - array of bytes to send
         * @param nBytes - number of bytes to send.
         */
        private void executeWriteCharacteristic(BluetoothGattCharacteristic gattCharacteristic, byte[] valBytes) {
            if (gattCharacteristic != null) {
                gattCharacteristic.setValue(valBytes);
                boolean retVal = mBluetoothGatt.writeCharacteristic(gattCharacteristic);
                if (retVal) {
                    Log.d(TAG, "executeWriteCharacteristic - write initiated successfully");
                } else {
                    Log.d(TAG, "executeWriteCharacteristic - write initiation failed - waiting, then re-trying");
                    mHandler.postDelayed(new Runnable() {
                        public void run() {
                            Log.w(TAG, "Executing delayed write of characteristic");
                            executeWriteCharacteristic(gattCharacteristic, valBytes);
                        }
                    }, 100);
                }
            } else {
                Log.i(TAG,"ExecuteWriteCharacteristic() - gatCharacteristic is null, so not doing anything");
            }
        }


        private boolean permissionsOK() {
            if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                Log.e(TAG, "permissionsOK() - Bluetooth Permmission Not Granted");
                mUtil.showToast("ERROR - Bluetooth Permission not Granted");
                return (false);
            } else {
                return (true);
            }

        }

        public void onDataReceived(BluetoothGattCharacteristic characteristic) {
            /*
             * onDataReceived - called whenever a BLE characteristic notifies us that its data has changed.
             * If the data is acceleration data, we add it to a buffer - it is analysed once the buffer is full.
             * Heart rate data is written directly to sdData to be used in future analysis.
             */
            Log.v(TAG, "onDataReceived: Characteristic=" + characteristic.getUuid().toString());
            if (characteristic.getUuid().toString().equals(CHAR_HEART_RATE_MEASUREMENT)) {
                int flag = characteristic.getProperties();
                //Log.d(TAG,"onDataReceived() - flag = "+flag);
                int format = -1;
                if ((flag & 0x01) != 0) {
                    format = BluetoothGattCharacteristic.FORMAT_UINT16;
                    //Log.d(TAG, "onDataReceived(): Heart rate format UINT16.");
                } else {
                    format = BluetoothGattCharacteristic.FORMAT_UINT8;
                    //Log.d(TAG, "onDataReceived(): Heart rate format UINT8.");
                }
                final int heartRate = characteristic.getIntValue(format, 1);  // heart rate is second byte
                // We normally use -1 for fault indication, but the BLE standard is for one byte for heart
                // rate services, so we can't send -1, so treat either 0 or 255 as fault.
                if (heartRate == 255 || heartRate == 0) {
                    mSdData.mHR = -1;
                } else {
                    mSdData.mHR = (double) heartRate;
                }
                Log.d(TAG, String.format("onDataReceived(): CHAR_HEART_RATE_MEASUREMENT: %d", heartRate));
            } else if (characteristic.getUuid().toString().equals(CHAR_OSD_ACC_DATA)
                    || characteristic.getUuid().toString().equals(CHAR_INFINITIME_ACC_DATA)) {
                //Log.v(TAG,"Received OSD ACC DATA"+characteristic.getValue());
                byte[] rawDataBytes = characteristic.getValue();
                short[] newAccVals = parseDataToAccVals(rawDataBytes);
                Log.v(TAG, "onDataReceived(): CHAR_OSD_ACC_DATA: numSamples = " + rawDataBytes.length + " nRawData=" + nRawData);
                //Log.v(TAG, "onDataReceived() - rawDataBytes="+ Arrays.toString(rawDataBytes));
                //Log.v(TAG, "onDataReceived() - newAccVals="+Arrays.toString(newAccVals));
                for (int i = 0; i < newAccVals.length; i++) {
                    if (nRawData < MAX_RAW_DATA) {
                        switch (mAccFmt) {
                            case ACC_FMT_8BIT:
                            case ACC_FMT_16BIT:
                                rawData[nRawData] = newAccVals[i];
                                nRawData++;
                                break;
                            case ACC_FMT_3D:
                                // 3d data is x1,y1,z1, x2,y2,z2 ... xn,yn,zn
                                // We only do this every third value, then process x, y and z simultaneously.
                                if (i + 2 < newAccVals.length) {
                                    if (i % 3 == 0) {
                                        short x, y, z;
                                        x = newAccVals[i];
                                        y = newAccVals[i + 1];
                                        z = newAccVals[i + 2];
                                        // Calculate vector magnitude
                                        rawData[nRawData] = Math.sqrt(x * x + y * y + z * z);
                                        // Store 3d values
                                        rawData3d[nRawData * 3] = x;
                                        rawData3d[nRawData * 3 + 1] = y;
                                        rawData3d[nRawData * 3 + 2] = z;
                                        nRawData++;
                                    }
                                }
                                break;
                            default:
                                Log.e(TAG, "INVALID ACCELERATION FORMAT" + mAccFmt);
                                mUtil.showToast("INVALID ACCELERATION FORMAT " + mAccFmt);
                        }

                    } else {
                        Log.i(TAG, "onDataReceived(): RawData Buffer Full - processing data");
                        mSdData.watchAppRunning = true;
                        for (i = 0; i < rawData.length; i++) {
                            mSdData.rawData[i] = rawData[i];
                            mSdData.rawData3D[i * 3] = rawData3d[i * 3];
                            mSdData.rawData3D[i * 3 + 1] = rawData3d[i * 3 + 1];
                            mSdData.rawData3D[i * 3 + 2] = rawData3d[i * 3 + 2];
                            //Log.v(TAG,"onDataReceived() i="+i+", "+rawData[i]);
                        }
                        mSdData.mNsamp = rawData.length;
                        mWatchAppRunningCheck = true;
                        mDataStatusTime = new Time(Time.getCurrentTimezone());
                        // Process the data to do seizure detection
                        doAnalysis();
                        // Re-start collecting raw data.
                        nRawData = 0;
                        // Notify the device of the resulting alarm state
                        if (mStatusChar != null) {
                            Log.i(TAG,"onDataReceived() - Sending analysis result");
                            byte[] statusVal = new byte[1];
                            statusVal[0] = (byte) mSdData.alarmState;
                            executeWriteCharacteristic(mStatusChar, statusVal);
                        } else {
                            Log.i(TAG,"onDataReceived() - mStatusChar is null - not sending result");
                        }
                    }
                }
            } else if (characteristic.getUuid().toString().equals(CHAR_OSD_BATT_DATA)) {
                byte batteryPc = characteristic.getValue()[0];
                mSdData.batteryPc = batteryPc;
                Log.v(TAG, "onDataReceived(): CHAR_OSD_BATT_DATA: " + String.format("%d", batteryPc));
                mSdData.haveSettings = true;
            } else if (characteristic.getUuid().toString().equals(CHAR_BATT_DATA)) {
                byte batteryPc = characteristic.getValue()[0];
                mSdData.batteryPc = batteryPc;
                Log.v(TAG, "onDataReceived(): CHAR_BATT_DATA: " + String.format("%d", batteryPc));
                mSdData.haveSettings = true;
            } else if (characteristic.getUuid().toString().equals(CHAR_OSD_WATCH_ID)) {
                byte[] rawDataBytes = characteristic.getValue();
                String watchId = new String(rawDataBytes, StandardCharsets.UTF_8);
                Log.v(TAG, "Received Watch ID: " + watchId);
                mSdData.watchSdName = watchId;
            } else if (characteristic.getUuid().toString().equals(CHAR_OSD_WATCH_FW)) {
                byte[] rawDataBytes = characteristic.getValue();
                String watchFwVer = new String(rawDataBytes, StandardCharsets.UTF_8);
                Log.v(TAG, "Received Watch Firmware Version: " + watchFwVer);
                mSdData.watchSdVersion = watchFwVer;
            } else if (characteristic.getUuid().toString().equals(CHAR_OSD_ACC_FMT)) {
                mAccFmt = characteristic.getValue()[0];
                Log.v(TAG, "Received Acceleration format code: " + mAccFmt);
            } else {
                Log.v(TAG, "Unrecognised Characteristic Updated " +
                        characteristic.getUuid().toString());
            }
        }

        private short[] parseDataToAccVals(byte[] rawDataBytes) {
            short[] retArr;
            switch (mAccFmt) {
                case ACC_FMT_8BIT:
                    retArr = new short[rawDataBytes.length];
                    for (int i = 0; i < rawDataBytes.length; i++) {
                        retArr[i] = (short) (1000 * rawDataBytes[i] / 64);   // Scale to mg
                    }
                    break;
                case ACC_FMT_16BIT:
                case ACC_FMT_3D:
                    // from https://stackoverflow.com/questions/5625573/byte-array-to-short-array-and-back-again-in-java
                    retArr = new short[rawDataBytes.length / 2];
                    // to turn bytes to shorts as either big endian or little endian.
                    ByteBuffer.wrap(rawDataBytes)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer()
                            .get(retArr);
                    break;
                default:
                    Log.e(TAG, "INVALID ACCELERATION FORMAT" + mAccFmt);
                    mUtil.showToast("INVALID ACCELERATION FORMAT " + mAccFmt);
                    retArr = new short[0];
            }
            return (retArr);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int status) {
            Log.v(TAG, "onCharacteristicRead");
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onDataReceived(characteristic);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            //Log.v(TAG,"onCharacteristicChanged(): Characteristic "+characteristic.getUuid()+" changed");
            onDataReceived(characteristic);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            Log.v(TAG, "onDescriptorWrite(): Characteristic " + descriptor.getUuid() + " changed");
            waitForDescriptorWrite = false;
        }
    };

    /**
     * Enables or disables notification on a give characteristic.
     *
     * @param characteristic Characteristic to act on.
     * @param enabled        If true, enable notification.  False otherwise.
     */
    public void setCharacteristicNotification(final BluetoothGattCharacteristic characteristic, final boolean enabled) {
        Log.w(TAG, "setCharacteristicNotification " + characteristic.getUuid());

        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }

        if (waitForDescriptorWrite) {
            // Apparently if you try to write multiple descriptors too quickly then only
            // one is processed, hence why this waiting logic is necessary
            Log.w(TAG, "waitForDescriptor " + characteristic.getUuid());
            mHandler.postDelayed(new Runnable() {
                public void run() {
                    Log.w(TAG, "delayed");
                    setCharacteristicNotification(characteristic, enabled);
                }
            }, 500);
            return;
        }

        if (enabled) {
            Log.v(TAG, "setCharacteristicNotification - Requesting notifications");
            mBluetoothGatt.setCharacteristicNotification(characteristic, true);

            // Tell the device we want notifications?   The sample from Google said we only need this for Heart Rate, but the
            // BangleJS widget did not work without it so do it for everything.
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
                    UUID.fromString(GattAttributes.CLIENT_CHARACTERISTIC_CONFIG));
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            mBluetoothGatt.writeDescriptor(descriptor);
        } else {
            Log.v(TAG, "setCharacteristicNotification - De-registering notifications");
            mBluetoothGatt.setCharacteristicNotification(characteristic, false);

            // Tell the device we want notifications?   The sample from Google said we only need this for Heart Rate, but the
            // BangleJS widget did not work without it so do it for everything.
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
                    UUID.fromString(GattAttributes.CLIENT_CHARACTERISTIC_CONFIG));
            descriptor.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
            mBluetoothGatt.writeDescriptor(descriptor);
        }

        waitForDescriptorWrite = true;
    }

    /**
     * Retrieves a list of supported GATT services on the connected device. This should be
     * invoked only after {@code BluetoothGatt#discoverServices()} completes successfully.
     *
     * @return A {@code List} of supported services.
     */
    public List<BluetoothGattService> getSupportedGattServices() {
        if (mBluetoothGatt == null) return null;

        return mBluetoothGatt.getServices();
    }

    /**
     * Install the watch app on the watch.
     */
/*    @Override
    public void installWatchApp() {
        Log.v(TAG, "installWatchApp");
        try {
            String url = "http://www.openseizuredetector.org.uk/?page_id=1207";
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setData(Uri.parse(url));
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(i);
        } catch (Exception ex) {
            Log.i(TAG, "exception starting install watch app activity " + ex.toString());
            showToast("Error Displaying Installation Instructions - try http://www.openseizuredetector.org.uk/?page_id=1207 instead");
        }
    }

 */
}
