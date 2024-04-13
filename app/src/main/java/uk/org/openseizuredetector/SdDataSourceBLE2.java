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

import static com.welie.blessed.BluetoothBytesParser.asHexString;
import static java.lang.Math.abs;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Time;
import android.util.Log;

import com.welie.blessed.BluetoothBytesParser;
import com.welie.blessed.BluetoothCentralManager;
import com.welie.blessed.BluetoothCentralManagerCallback;
import com.welie.blessed.BluetoothPeripheral;
import com.welie.blessed.BluetoothPeripheralCallback;
import com.welie.blessed.ConnectionPriority;
import com.welie.blessed.GattStatus;
import com.welie.blessed.HciStatus;
import com.welie.blessed.PhyOptions;
import com.welie.blessed.PhyType;
import com.welie.blessed.WriteType;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import co.beeline.android.bluetooth.currenttimeservice.CurrentTimeService;


/**
 * A data source that registers for BLE GATT notifications from a device and
 * waits to be notified of data being available.
 * SdDataSourceBLE2 uses the BLESSED library for the BLE access rather than native Android
 * BLE methods to try to improve start-up/shutdown reliability.
 */
public class SdDataSourceBLE2 extends SdDataSource {
    private int MAX_RAW_DATA = 125;  // 5 seconds at 25 Hz.
    private String TAG = "SdDataSourceBLE2";
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


    public static String SERV_DEV_INFO = "0000180a-0000-1000-8000-00805f9b34fb";
    public static String CHAR_DEV_MANUF = "00002a29-0000-1000-8000-00805f9b34fb";
    public static String CHAR_DEV_MODEL_NO = "00002a24-0000-1000-8000-00805f9b34fb";
    public static String CHAR_DEV_SER_NO = "00002a25-0000-1000-8000-00805f9b34fb";
    public static String CHAR_DEV_FW_VER = "00002a26-0000-1000-8000-00805f9b34fb";
    public static String CHAR_DEV_HW_VER = "00002a27-0000-1000-8000-00805f9b34fb";
    public static String CHAR_DEV_FW_NAME = "00002a28-0000-1000-8000-00805f9b34fb";
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
    BluetoothGattCharacteristic mHrChar;
    BluetoothGattCharacteristic mBattChar;
    private BluetoothCentralManager mBluetoothCentralManager;
    private boolean mShutdown = false;

    public SdDataSourceBLE2(Context context, Handler handler,
                            SdDataReceiver sdDataReceiver) {
        super(context, handler, sdDataReceiver);
        mName = "BLE2";
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

        boolean success = CurrentTimeService.startServer(mContext);

        bleConnect();

    }

    private void bleConnect() {

        // Create BluetoothCentral and receive callbacks on the main thread
        mBluetoothCentralManager = new BluetoothCentralManager(mContext,
                mBluetoothCentralManagerCallback,
                new Handler(Looper.getMainLooper())
        );
        // Look for the specified device
        Log.i(TAG,"bleConnect() - scanning for device: "+mBleDeviceAddr);
        mShutdown = false;
        mBluetoothCentralManager.scanForPeripheralsWithAddresses(new String[]{mBleDeviceAddr});
    }


    private final BluetoothCentralManagerCallback mBluetoothCentralManagerCallback = new BluetoothCentralManagerCallback() {
        @Override
        public void onDiscoveredPeripheral(BluetoothPeripheral peripheral, ScanResult scanResult) {
            Log.i(TAG,"BluetoothCentralManagerCallback.onDiscoveredPeripheral()");
            mBluetoothCentralManager.stopScan();
            mBluetoothCentralManager.autoConnectPeripheral(peripheral, peripheralCallback);
        }
        @Override
        public void onConnectedPeripheral(BluetoothPeripheral peripheral) {
            Log.i(TAG,"BluetoothCentralManagerCallback.onConnectedPeripheral()");
            super.onConnectedPeripheral(peripheral);
        }
        @Override
        public void onConnectionFailed(BluetoothPeripheral peripheral, HciStatus status) {
            Log.i(TAG,"BluetoothCentralManagerCallback.onConnectionFailed() - attempting to reconnect");
            mBluetoothCentralManager.autoConnectPeripheral(peripheral, peripheralCallback);
            super.onConnectionFailed(peripheral, status);
        }
        @Override
        public void onDisconnectedPeripheral(BluetoothPeripheral peripheral, HciStatus status) {
            if (mShutdown) {
                Log.i(TAG,"BluetoothCentralManagerCallback.onDisonnectedPeripheral - mShutdown is set, so not reconnecting");
            } else {
                Log.i(TAG,"BluetoothCentralManagerCallback.onDisonnectedPeripheral");
                //Log.i(TAG, "BluetoothCentralManagerCallback.onDisonnectedPeripheral - attempting to re-connect...");
                //bleDisconnect();
                //mShutdown=false;
                //mBluetoothCentralManager.autoConnectPeripheral(peripheral, peripheralCallback);
            }
            super.onDisconnectedPeripheral(peripheral, status);
        }


    };

    private @NotNull BluetoothPeripheral mBlePeripheral;
    // Callback for peripherals
    private final BluetoothPeripheralCallback peripheralCallback = new BluetoothPeripheralCallback() {

        @Override // BluetoothPeripheralCallback
        public void onServicesDiscovered(@NotNull BluetoothPeripheral peripheral) {
            Log.i(TAG,"onServicesDiscovered()");
            mBlePeripheral = peripheral;
            // Request a higher MTU, iOS always asks for 185 - This is likely to have no effect, as Pinetime uses 23 bytes.
            Log.i(TAG,"onServicesDiscovered() - requesting higher MTU");
            peripheral.requestMtu(185);
            // Request a new connection priority
            Log.i(TAG,"onServicesDiscovered() - requesting high priority connection");
            peripheral.requestConnectionPriority(ConnectionPriority.HIGH);
            Log.i(TAG,"onServicesDiscovered() - requesting Long Range Bluetooth 5 connection");
            //peripheral.setPreferredPhy(PhyType.LE_2M, PhyType.LE_2M, PhyOptions.S2);
            // Request long range Bluetooth 5 connection if available.
            peripheral.setPreferredPhy(PhyType.LE_CODED, PhyType.LE_CODED, PhyOptions.S8);
            peripheral.readPhy();

            peripheral.readRemoteRssi();

            boolean foundOsdService = false;
            for (BluetoothGattService service : peripheral.getServices()) {
                String servUuidStr = service.getUuid().toString();
                Log.d(TAG, "found service: " + servUuidStr);
                if (servUuidStr.equals(SERV_OSD)) {
                    Log.v(TAG, "OpenSeizureDetector Service Discovered");
                    foundOsdService = true;
                } else if (servUuidStr.equals(SERV_INFINITIME_MOTION)) {
                    Log.v(TAG, "InfiniTime Motion Service Discovered");
                    foundOsdService = true;
                } else if (servUuidStr.equals(SERV_HEART_RATE)) {
                    Log.v(TAG, "Heart Rate Measurement Service Service Discovered");
                } else if (servUuidStr.equals(SERV_BATT)) {
                    Log.v(TAG, "Battery Data Service Service Discovered");
                } else if (servUuidStr.equals(SERV_DEV_INFO)) {
                    Log.v(TAG, "Device Information Service Service Discovered");
                }


            // Loop through the available characteristics...
                for (BluetoothGattCharacteristic gattCharacteristic : service.getCharacteristics()) {
                    String charUuidStr = gattCharacteristic.getUuid().toString();
                    Log.d(TAG, "  found characteristic: " + charUuidStr);
                    // The generic heart rate measurement characteristic
                    if (charUuidStr.equals(CHAR_HEART_RATE_MEASUREMENT)) {
                        Log.v(TAG, "Subscribing to Heart Rate Measurement Change Notifications");
                        mHrChar = gattCharacteristic;
                        peripheral.setNotify(service.getUuid(), gattCharacteristic.getUuid(), true);
                    } else if (charUuidStr.equals(CHAR_OSD_ACC_DATA)) {
                        Log.i(TAG, "Subscribing to Acceleration Data Change Notifications");
                        peripheral.setNotify(service.getUuid(), gattCharacteristic.getUuid(), true);
                        mOsdChar = gattCharacteristic;
                    } else if (charUuidStr.equals(CHAR_OSD_STATUS)) {
                        Log.i(TAG, "Found OSD Status Characteristic");
                        mStatusChar = gattCharacteristic;
                    } else if (charUuidStr.equals(CHAR_OSD_BATT_DATA)) {
                        Log.i(TAG, "Subscribing to battery change Notifications");
                        peripheral.readCharacteristic(service.getUuid(), gattCharacteristic.getUuid());
                        peripheral.setNotify(service.getUuid(), gattCharacteristic.getUuid(), true);
                        mBattChar = gattCharacteristic;
                    } else if (charUuidStr.equals(CHAR_OSD_WATCH_ID)) {
                        Log.i(TAG, "Reading Watch ID");
                        peripheral.readCharacteristic(service.getUuid(), gattCharacteristic.getUuid());
                    } else if (charUuidStr.equals(CHAR_OSD_WATCH_FW)) {
                        Log.i(TAG, "Reading Watch Firmware Version");
                        peripheral.readCharacteristic(service.getUuid(), gattCharacteristic.getUuid());
                    } else if (charUuidStr.equals(CHAR_OSD_ACC_FMT)) {
                        Log.i(TAG, "Reading Acceleration format code");
                        peripheral.readCharacteristic(service.getUuid(), gattCharacteristic.getUuid());
                        // Now the Infinitime Motion Service Characteristics
                    } else if (charUuidStr.equals(CHAR_INFINITIME_ACC_DATA)) {
                        Log.i(TAG, "Subscribing to Infinitime Acceleration Data Change Notifications");
                        mOsdChar = gattCharacteristic;
                        mAccFmt = ACC_FMT_3D;  // InfiniTime presents x, y, z data
                        peripheral.setNotify(service.getUuid(), gattCharacteristic.getUuid(), true);
                    } else if (charUuidStr.equals(CHAR_INFINITIME_OSD_STATUS)) {
                        Log.i(TAG, "Found InfiniTime OSD Status Characteristic");
                        mStatusChar = gattCharacteristic;
                        // Now the generic battery data characteristic
                    } else if (charUuidStr.equals(CHAR_BATT_DATA)) {
                        mBattChar = gattCharacteristic;
                        Log.i(TAG, "Subscribing to Generic Battery Data Change Notifications");
                        peripheral.setNotify(service.getUuid(), gattCharacteristic.getUuid(), true);
                        Log.i(TAG, "Reading battery level");
                        peripheral.readCharacteristic(service.getUuid(), gattCharacteristic.getUuid());
                        // Now device info characteristics
                    } else if (charUuidStr.equals(CHAR_DEV_MANUF)) {
                        Log.i(TAG, "Reading device manufacturer");
                        peripheral.readCharacteristic(service.getUuid(), gattCharacteristic.getUuid());
                    } else if (charUuidStr.equals(CHAR_DEV_MODEL_NO)) {
                        Log.i(TAG, "Reading device model number");
                        peripheral.readCharacteristic(service.getUuid(), gattCharacteristic.getUuid());
                    } else if (charUuidStr.equals(CHAR_DEV_SER_NO)) {
                        Log.i(TAG, "Reading device serial number");
                        peripheral.readCharacteristic(service.getUuid(), gattCharacteristic.getUuid());
                    } else if (charUuidStr.equals(CHAR_DEV_FW_VER)) {
                        Log.i(TAG, "Reading device firmware version");
                        peripheral.readCharacteristic(service.getUuid(), gattCharacteristic.getUuid());
                    } else if (charUuidStr.equals(CHAR_DEV_HW_VER)) {
                        Log.i(TAG, "Reading device hardware version");
                        peripheral.readCharacteristic(service.getUuid(), gattCharacteristic.getUuid());
                    } else if (charUuidStr.equals(CHAR_DEV_FW_NAME)) {
                        Log.i(TAG, "Reading device firmware name");
                        peripheral.readCharacteristic(service.getUuid(), gattCharacteristic.getUuid());
                    }
                }
            }
            if (foundOsdService) {
                Log.i(TAG,"Success - found OSD Service");
            } else {
                Log.e(TAG,"ERROR - device does not provide the OSD service");
                mUtil.showToast("ERROR: BLE Device does no provide OSD Servie");
            }
        }

        @Override
        public void onNotificationStateUpdate(@NotNull BluetoothPeripheral peripheral, @NotNull BluetoothGattCharacteristic characteristic, @NotNull GattStatus status) {
            if (status == GattStatus.SUCCESS) {
                final boolean isNotifying = peripheral.isNotifying(characteristic);
                Log.i(TAG, String.format("SUCCESS: Notify set to '%s' for %s", isNotifying, characteristic.getUuid()));

            } else {
                Log.e(TAG, String.format("ERROR: Changing notification state failed for %s (%s)",
                        characteristic.getUuid(), status));
            }
        }

        @Override
        public void onCharacteristicWrite(@NotNull BluetoothPeripheral peripheral, @NotNull byte[] value, @NotNull BluetoothGattCharacteristic characteristic, @NotNull GattStatus status) {
            if (status == GattStatus.SUCCESS) {
                Log.d(TAG, String.format("SUCCESS: Writing <%s> to <%s>", asHexString(value), characteristic.getUuid()));
            } else {
                Log.w(TAG, String.format("ERROR: Failed writing <%s> to <%s> (%s)", asHexString(value), characteristic.getUuid(), status));
            }
        }

        @Override
        public void onCharacteristicUpdate(@NotNull BluetoothPeripheral peripheral, @NotNull byte[] value, @NotNull BluetoothGattCharacteristic characteristic, @NotNull GattStatus status) {
            if (status != GattStatus.SUCCESS) return;

            UUID characteristicUUID = characteristic.getUuid();
            BluetoothBytesParser parser = new BluetoothBytesParser(value);
            String charUuidStr = characteristicUUID.toString();

            if (charUuidStr.equals(CHAR_HEART_RATE_MEASUREMENT)) {
                Log.v(TAG, String.format("%s", "HR Measurement"));
                // Parse the flags
                int flags = parser.getUInt8();
                final int unit = flags & 0x01;
                final int sensorContactStatus = (flags & 0x06) >> 1;
                final boolean energyExpenditurePresent = (flags & 0x08) > 0;
                final boolean rrIntervalPresent = (flags & 0x10) > 0;
                // Parse heart rate
                mSdData.mHR = (unit == 0) ? parser.getUInt8() : parser.getUInt16();
                Log.d(TAG,"Received HR="+mSdData.mHR);

            } else if (charUuidStr.equals(CHAR_OSD_ACC_DATA)
                    || charUuidStr.equals(CHAR_INFINITIME_ACC_DATA)) {
                //Log.v(TAG,"Received OSD ACC DATA"+characteristic.getValue());
                byte[] rawDataBytes = characteristic.getValue();
                short[] newAccVals = parseDataToAccVals(rawDataBytes);
                Log.v(TAG, "onDataReceived(): CHAR_OSD_ACC_DATA: numSamples = " + rawDataBytes.length + " nRawData=" + nRawData);
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
                        mBlePeripheral.readRemoteRssi();  // Update RSSI
                        // Re-start collecting raw data.
                        nRawData = 0;
                        // Notify the device of the resulting alarm state
                        if (mStatusChar != null) {
                            Log.i(TAG, "onDataReceived() - Sending analysis result");
                            byte[] statusVal = new byte[1];
                            statusVal[0] = (byte) mSdData.alarmState;
                            peripheral.writeCharacteristic(mStatusChar, statusVal, WriteType.WITH_RESPONSE);
                        } else {
                            Log.i(TAG, "onDataReceived() - mStatusChar is null - not sending result");
                        }
                    }
                }
            } else if (charUuidStr.equals(CHAR_BATT_DATA)
                    || charUuidStr.equals(CHAR_OSD_BATT_DATA)) {
                byte batteryPc = characteristic.getValue()[0];
                mSdData.batteryPc = batteryPc;
                Log.v(TAG, "onDataReceived(): CHAR_BATT_DATA: " + String.format("%d", batteryPc));
                mSdData.haveSettings = true;
            } else if (charUuidStr.equals(CHAR_OSD_WATCH_ID) || charUuidStr.equals(CHAR_DEV_FW_NAME)) {
                byte[] rawDataBytes = characteristic.getValue();
                String watchId = new String(rawDataBytes, StandardCharsets.UTF_8);
                Log.i(TAG, "Received Watch ID: " + watchId);
                mSdData.watchSdName = watchId;
            } else if (charUuidStr.equals(CHAR_OSD_ACC_FMT)) {
                mAccFmt = characteristic.getValue()[0];
                Log.i(TAG, "Received Acceleration format code: " + mAccFmt);
            } else if (charUuidStr.equals(CHAR_DEV_MANUF)) {
                byte[] rawDataBytes = characteristic.getValue();
                String watchManuf = new String(rawDataBytes, StandardCharsets.UTF_8);
                Log.i(TAG, "Received Manufacturer: " + watchManuf);
                mSdData.watchManuf = watchManuf;
            } else if (charUuidStr.equals(CHAR_DEV_MODEL_NO)) {
                byte[] rawDataBytes = characteristic.getValue();
                String watchModelNo = new String(rawDataBytes, StandardCharsets.UTF_8);
                Log.i(TAG, "Received Watch Model No.: " + watchModelNo);
                mSdData.watchPartNo = watchModelNo;
            } else if (charUuidStr.equals(CHAR_DEV_SER_NO)) {
                byte[] rawDataBytes = characteristic.getValue();
                String watchSerNo = new String(rawDataBytes, StandardCharsets.UTF_8);
                Log.i(TAG, "Received Watch Serial No.: " + watchSerNo);
                mSdData.watchSerNo = watchSerNo;
            } else if (charUuidStr.equals(CHAR_DEV_HW_VER)) {
                byte[] rawDataBytes = characteristic.getValue();
                String watchHwVer = new String(rawDataBytes, StandardCharsets.UTF_8);
                Log.i(TAG, "Received Hardware Version: " + watchHwVer);
                mSdData.watchFwVersion = watchHwVer;
            } else if (charUuidStr.equals(CHAR_DEV_FW_NAME)) {
                byte[] rawDataBytes = characteristic.getValue();
                String watchFwName = new String(rawDataBytes, StandardCharsets.UTF_8);
                Log.i(TAG, "Received Firmware Name: " + watchFwName);
                mSdData.watchSdName = watchFwName;
            } else if (charUuidStr.equals(CHAR_OSD_WATCH_FW)  || charUuidStr.equals(CHAR_DEV_FW_VER)) {
                byte[] rawDataBytes = characteristic.getValue();
                String watchFwVer = new String(rawDataBytes, StandardCharsets.UTF_8);
                Log.i(TAG, "Received Watch Firmware Version: " + watchFwVer);
                mSdData.watchSdVersion = watchFwVer;
            } else {
                byte[] rawDataBytes = characteristic.getValue();
                String strVal = new String(rawDataBytes, StandardCharsets.UTF_8);
                Log.d(TAG, "Unrecognised Characteristic Updated " +
                        charUuidStr+" : "+strVal);
            }
        }

        @Override
        public void onMtuChanged(@NotNull BluetoothPeripheral peripheral, int mtu, @NotNull GattStatus status) {
            Log.i(TAG, String.format("new MTU set: %d", mtu));
        }

        @Override
        public void onReadRemoteRssi(@NotNull BluetoothPeripheral peripheral, int rssi, @NotNull GattStatus status) {
            Log.d(TAG, String.format("Rssi = %d", rssi));
            mSdData.watchSignalStrength = rssi;

        }

    };



    private void bleDisconnect() {
        try {
            Log.i(TAG, "bleDisconnect() - Unregistering notifications");
            if (mBlePeripheral != null) {
                if (mOsdChar != null) {
                    Log.i(TAG, "bleDisconnect() - unregistering mOsdChar");
                    mBlePeripheral.setNotify(mOsdChar, false);
                } else {
                    Log.w(TAG, "bleDisconnect() - mOsdChar is null - not removing notification");
                }
                if (mHrChar != null) {
                    Log.i(TAG, "bleDisconnect() - unregistering mHrChar");
                    mBlePeripheral.setNotify(mHrChar, false);
                } else {
                    Log.w(TAG, "bleDisconnect() - mHrChar is null - not removing notification");
                }
                if (mBattChar != null) {
                    Log.i(TAG, "bleDisconnect() - unregistering mBattChar");
                    mBlePeripheral.setNotify(mBattChar, false);
                } else {
                    Log.w(TAG, "bleDisconnect() - mBattChar is null - not removing notification");
                }
            } else {
                Log.w(TAG, "bleDisconnect() - mBlePeripheral is null - not removing notifications");
            }

            mShutdown = true;
            mBlePeripheral.cancelConnection();

            Log.i(TAG, "bleDisconnect() - closing  BluetoothCentralManager");
            mBluetoothCentralManager.close();
        } catch (Exception e) {
            Log.e(TAG,"bleDisconnect() - Error: "+e.getMessage());
            mUtil.showToast("Error disconnecting from watch");
        }
    }

    /**
     * Stop the datasource from updating
     */
    public void stop() {
        Log.i(TAG, "stop()");
        mUtil.writeToSysLogFile("SDDataSourceBLE.stop()");
        super.stop();

        try {
            bleDisconnect();
            CurrentTimeService.stopServer();
        } catch (Exception e) {
            Log.e(TAG,"stop() - Error stopping data source: "+e.getMessage());
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



}
