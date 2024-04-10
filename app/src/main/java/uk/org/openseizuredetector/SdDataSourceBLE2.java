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

import static com.welie.blessed.BluetoothBytesParser.FORMAT_SINT16;
import static com.welie.blessed.BluetoothBytesParser.FORMAT_UINT16;
import static com.welie.blessed.BluetoothBytesParser.FORMAT_UINT8;
import static com.welie.blessed.BluetoothBytesParser.asHexString;
import static java.lang.Math.abs;

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
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Time;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import com.welie.blessed.BluetoothBytesParser;
import com.welie.blessed.BluetoothCentralManager;
import com.welie.blessed.BluetoothCentralManagerCallback;
import com.welie.blessed.BluetoothPeripheral;
import com.welie.blessed.BluetoothPeripheralCallback;
import com.welie.blessed.ConnectionPriority;
import com.welie.blessed.GattStatus;
import com.welie.blessed.PhyOptions;
import com.welie.blessed.PhyType;
import com.welie.blessed.WriteType;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
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
    private BluetoothCentralManager mBluetoothCentralManager;


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
        mBluetoothCentralManager.scanForPeripheralsWithAddresses(new String[]{mBleDeviceAddr});
    }


    private final BluetoothCentralManagerCallback mBluetoothCentralManagerCallback = new BluetoothCentralManagerCallback() {
        @Override
        public void onDiscoveredPeripheral(BluetoothPeripheral peripheral, ScanResult scanResult) {
            mBluetoothCentralManager.stopScan();
            mBluetoothCentralManager.connectPeripheral(peripheral, peripheralCallback);
        }
    };

    // Callback for peripherals
    private final BluetoothPeripheralCallback peripheralCallback = new BluetoothPeripheralCallback() {
        @Override // BluetoothPeripheralCallback
        public void onServicesDiscovered(@NotNull BluetoothPeripheral peripheral) {
            // Request a higher MTU, iOS always asks for 185
            peripheral.requestMtu(185);
            // Request a new connection priority
            peripheral.requestConnectionPriority(ConnectionPriority.HIGH);
            peripheral.setPreferredPhy(PhyType.LE_2M, PhyType.LE_2M, PhyOptions.S2);
            peripheral.readPhy();


            // Try to turn on notifications for other characteristics
            peripheral.readCharacteristic(UUID.fromString(SERV_BATT), UUID.fromString(CHAR_BATT_DATA));
            peripheral.setNotify(UUID.fromString(SERV_BATT), UUID.fromString(CHAR_BATT_DATA), true);
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
                Log.i(TAG, String.format("SUCCESS: Writing <%s> to <%s>", asHexString(value), characteristic.getUuid()));
            } else {
                Log.i(TAG, String.format("ERROR: Failed writing <%s> to <%s> (%s)", asHexString(value), characteristic.getUuid(), status));
            }
        }

        @Override
        public void onCharacteristicUpdate(@NotNull BluetoothPeripheral peripheral, @NotNull byte[] value, @NotNull BluetoothGattCharacteristic characteristic, @NotNull GattStatus status) {
            if (status != GattStatus.SUCCESS) return;

            UUID characteristicUUID = characteristic.getUuid();
            BluetoothBytesParser parser = new BluetoothBytesParser(value);

            if (characteristicUUID.equals(UUID.fromString(CHAR_HEART_RATE_MEASUREMENT))) {
                Log.d(TAG, String.format("%s", "HR Measurement"));
            } else if (characteristicUUID.equals(UUID.fromString(CHAR_BATT_DATA))) {
                int batteryLevel = parser.getIntValue(FORMAT_UINT8);
                Log.i(TAG, String.format("Received battery level %d%%", batteryLevel));
            }
        }

        @Override
        public void onMtuChanged(@NotNull BluetoothPeripheral peripheral, int mtu, @NotNull GattStatus status) {
            Log.i(TAG, String.format("new MTU set: %d", mtu));
        }


    };


    private void bleDisconnect() {
    }

    /**
     * Stop the datasource from updating
     */
    public void stop() {
        Log.i(TAG, "stop()");
        mUtil.writeToSysLogFile("SDDataSourceBLE.stop()");

        bleDisconnect();
        CurrentTimeService.stopServer();
        super.stop();
    }




}
