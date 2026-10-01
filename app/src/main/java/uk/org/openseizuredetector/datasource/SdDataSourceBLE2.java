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
package uk.org.openseizuredetector.datasource;

import uk.org.openseizuredetector.datasource.SdDataSource;
import uk.org.openseizuredetector.datasource.SdDataSourceBLE;
import uk.org.openseizuredetector.datasource.SdDataSourceBLE2;
import uk.org.openseizuredetector.data.SdData;
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
import uk.org.openseizuredetector.data.logging.Log;

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
import com.welie.blessed.ScanFailure;
import com.welie.blessed.WriteType;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import co.beeline.android.bluetooth.currenttimeservice.CurrentTimeService;


import uk.org.openseizuredetector.activity.bluetooth.BLEScanActivity;
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
    private volatile boolean mShutdown = false;
    private static final int DISCONNECT_TIMEOUT_MS = 5000; // 5 seconds timeout for disconnect
    private Handler mTimeoutHandler;
    private volatile boolean mDisconnected = false;
    private volatile boolean mServicesDiscovered = false;

    // Connection state machine
    private enum ConnectionState {
        IDLE, SCANNING, CONNECTING, CONNECTED, DISCONNECTING, CLEANUP
    }
    private volatile ConnectionState mConnectionState = ConnectionState.IDLE;

    // Reconnection management
    private Handler mReconnectionHandler;
    private int mReconnectionAttempt = 0;
    private static final int[] BACKOFF_DELAYS_MS = {1000, 2000, 4000, 8000, 16000}; // exponential backoff
    // After exhausting the array, continue retrying with the last delay indefinitely
    private volatile boolean mIsShuttingDown = false;

    // ---------------------------------------------------------------------
    // Diagnostics (see doc/BLE2_Reconnection_Analysis.md, section 7 "Phase 0").
    //
    // These counters are PROCESS LIFETIME and deliberately static: the defect
    // they are there to expose (D1) is a leak of Bluetooth stack registrations
    // against a per-application cap, so the numbers that matter accumulate
    // across SdDataSourceBLE2 instances as well as across stop()/start()
    // cycles. AtomicInteger/AtomicLong because stop() - and therefore
    // forceCleanup() - can also run on SdServer.onDestroy()'s worker thread.
    //
    // All diagnostic log lines are prefixed BLE2DIAG: so a single
    // "adb logcat | grep BLE2DIAG" captures the whole picture.
    // ---------------------------------------------------------------------
    private static final String DIAG_TAG = "BLE2DIAG";
    private static final long DIAG_HEARTBEAT_PERIOD_MS = 60_000L;

    /** Number of BluetoothCentralManager instances created in this process. Grows without bound => D1/D3. */
    private static final AtomicInteger sManagerCreateCount = new AtomicInteger(0);
    /** Number of forceCleanup() calls in this process. */
    private static final AtomicInteger sForceCleanupCount = new AtomicInteger(0);
    /** Number of autoConnectPeripheral() calls issued in this process. */
    private static final AtomicInteger sAutoConnectCount = new AtomicInteger(0);
    /** Number of scans started in this process. */
    private static final AtomicInteger sScanStartCount = new AtomicInteger(0);
    /** Number of onScanFailed() callbacks received in this process. */
    private static final AtomicInteger sScanFailedCount = new AtomicInteger(0);
    /** Number of onDiscoveredPeripheral() callbacks received in this process. */
    private static final AtomicInteger sDiscoveredCount = new AtomicInteger(0);
    /** Number of onConnectedPeripheral() callbacks received in this process. */
    private static final AtomicInteger sConnectedCount = new AtomicInteger(0);
    /** Number of onDisconnectedPeripheral() callbacks received in this process. */
    private static final AtomicInteger sDisconnectedCount = new AtomicInteger(0);
    /** Number of onConnectionFailed() callbacks received in this process. */
    private static final AtomicInteger sConnectionFailedCount = new AtomicInteger(0);
    /** Number of Bluetooth adapter state changes received in this process. */
    private static final AtomicInteger sAdapterStateCount = new AtomicInteger(0);
    /** Number of datasource start()/stop() pairs in this process. */
    private static final AtomicInteger sStartCount = new AtomicInteger(0);
    private static final AtomicInteger sStopCount = new AtomicInteger(0);
    /**
     * Number of characteristic notifications discarded because the datasource was shut down or
     * not running. A burst of these shows data arriving that BLE2 threw away (D6/D8).
     */
    private static final AtomicInteger sDroppedUpdateCount = new AtomicInteger(0);
    /** Number of completed 5-second acceleration bursts handed to doAnalysis(). */
    private static final AtomicInteger sAccBurstCount = new AtomicInteger(0);

    /** Wall-clock time of the last accepted acceleration burst - lets FAULT timing be correlated. */
    private static final AtomicLong sLastAccDataMillis = new AtomicLong(0);

    /**
     * Generation id of the current BluetoothCentralManager. Incremented on every
     * create, so log lines can be correlated to a specific manager instance and a
     * callback arriving from a superseded ("zombie") manager shows up as an
     * unexpected gap in the sequence (D3).
     */
    private static final AtomicInteger sManagerGeneration = new AtomicInteger(0);
    private volatile int mManagerGeneration = -1;

    /**
     * True while we believe our own address-filtered scan is running. Set when we
     * call scanForPeripheralsWithAddresses(), cleared when we call stopScan().
     * An onDiscoveredPeripheral() arriving while this is false is a strong
     * indicator of a callback from a superseded manager (D3).
     */
    private volatile boolean mScanActive = false;

    /** True between autoConnectPeripheral()/connectPeripheral() and the next connect outcome. */
    private volatile boolean mConnectAttemptPending = false;
    private volatile long mConnectAttemptStartMillis = 0;

    private Handler mDiagHandler;
    private final Runnable mDiagHeartbeat = new Runnable() {
        @Override
        public void run() {
            try {
                long now = System.currentTimeMillis();
                long sinceAcc = sLastAccDataMillis.get() > 0
                        ? (now - sLastAccDataMillis.get()) : -1;
                boolean healthy = (mConnectionState == ConnectionState.CONNECTED)
                        && (sinceAcc >= 0) && (sinceAcc < 15_000L);
                if (healthy) {
                    // Connected and data is flowing: emit a compact line so the syslog is not
                    // flooded with full dumps while nothing interesting is happening.
                    Log.i(TAG, DIAG_TAG + ": heartbeat OK - state=CONNECTED gen=" + mManagerGeneration
                            + " msSinceLastAccBurst=" + sinceAcc
                            + " mgrCreated=" + sManagerCreateCount.get()
                            + " forceCleanup=" + sForceCleanupCount.get()
                            + " scanFailed=" + sScanFailedCount.get()
                            + " accBursts=" + sAccBurstCount.get());
                } else {
                    // Not connected, or data has stalled: this is the interesting case, so dump
                    // everything. The counters here are the evidence for D1/D2/D4.
                    logState("heartbeat-NOT-HEALTHY");
                }
                if (!mIsShuttingDown && !mShutdown && mDiagHandler != null) {
                    mDiagHandler.postDelayed(this, DIAG_HEARTBEAT_PERIOD_MS);
                }
            } catch (Exception e) {
                Log.e(TAG, "diagHeartbeat() - " + e.getMessage());
            }
        }
    };

    public SdDataSourceBLE2(Context context, Handler handler,
                            SdDataReceiver sdDataReceiver) {
        super(context, handler, sdDataReceiver);
        mName = "BLE2";
        mTimeoutHandler = new Handler(Looper.getMainLooper());
        mReconnectionHandler = new Handler(Looper.getMainLooper());
        mDiagHandler = new Handler(Looper.getMainLooper());
        mConnectionState = ConnectionState.IDLE;
        Log.i(TAG, DIAG_TAG + ": SdDataSourceBLE2 instance created, hashCode="
                + System.identityHashCode(this));
    }


    /**
     * Start the datasource updating - initialises from sharedpreferences first to
     * make sure any changes to preferences are taken into account.
     */
    public void start() {
        super.start();
        Log.i(TAG, "start() - mBleDeviceAddr="+mBleDeviceAddr);
        mUtil.writeMemoryLog("SdDataSourceBLE2.start");
        Log.i(TAG, DIAG_TAG + ": start() #" + sStartCount.incrementAndGet()
                + " - process-lifetime counters: " + countersToString());
        mUtil.writeMemoryLog("BLE2 start #" + sStartCount.get()
                + " mgrCreated=" + sManagerCreateCount.get()
                + " forceCleanup=" + sForceCleanupCount.get());

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
        //mSdData.watchSdName = mBleDeviceName;
        mSdData.watchSerNo = mBleDeviceAddr;

        boolean success = CurrentTimeService.startServer(mContext);

        bleConnect();

    }

    private void bleConnect() {
        Log.i(TAG, "bleConnect() - Starting connection sequence");

        // Reset shutdown flag - we're starting fresh
        mIsShuttingDown = false;
        mShutdown = false;
        mServicesDiscovered = false;
        mConnectAttemptPending = false;

        // Only create new manager if we don't already have one
        if (mBluetoothCentralManager == null) {
            mManagerGeneration = sManagerGeneration.incrementAndGet();
            int created = sManagerCreateCount.incrementAndGet();
            Log.i(TAG,"bleConnect() - Creating new BluetoothCentralManager");
            Log.w(TAG, DIAG_TAG + ": BluetoothCentralManager CREATED - generation="
                    + mManagerGeneration + ", process-lifetime total=" + created
                    + ". A steadily rising total across an outage is the D1 leak signature.");
            mUtil.writeMemoryLog("BLE2 manager created gen=" + mManagerGeneration
                    + " total=" + created);
            // Create BluetoothCentral and receive callbacks on the main thread
            mBluetoothCentralManager = new BluetoothCentralManager(mContext,
                    mBluetoothCentralManagerCallback,
                    new Handler(Looper.getMainLooper())
            );
            // Surface blessed's own diagnostics. These are emitted via android.util.Log
            // under the tags "BluetoothCentralManager" and "BluetoothPeripheral", so they
            // appear in logcat only - they do NOT reach the app's syslog file. The strings
            // to look for are listed in doc/BLE2_Reconnection_Analysis.md section 7.
            try {
                mBluetoothCentralManager.enableLogging();
            } catch (Exception e) {
                Log.w(TAG, "bleConnect() - could not enable blessed logging: " + e.getMessage());
            }
        } else {
            Log.i(TAG,"bleConnect() - BluetoothCentralManager already exists, reusing it");
            Log.i(TAG, DIAG_TAG + ": manager REUSED - generation=" + mManagerGeneration);
        }

        // Look for the specified device
        Log.i(TAG,"bleConnect() - scanning for device: "+mBleDeviceAddr);
        setConnectionState(ConnectionState.SCANNING, "bleConnect");
        // Start the heartbeat BEFORE the scan attempt so that a repeatedly failing scan still
        // produces a periodic state dump - that is exactly the case we most need to see.
        startDiagHeartbeat();

        try {
            mBluetoothCentralManager.scanForPeripheralsWithAddresses(new String[]{mBleDeviceAddr});
            mScanActive = true;
            int scans = sScanStartCount.incrementAndGet();
            Log.i(TAG, DIAG_TAG + ": scan started - generation=" + mManagerGeneration
                    + ", process-lifetime scans=" + scans
                    + ", scanFailedSoFar=" + sScanFailedCount.get());
        } catch (Exception e) {
            mScanActive = false;
            Log.e(TAG, "bleConnect() - Error starting scan: " + e.getMessage());
            Log.e(TAG, DIAG_TAG + ": SCAN START FAILED - " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            mUtil.writeExceptionLog("SdDataSourceBLE2", "bleConnect - startScan", e);
            scheduleReconnection();
        }
    }


    private final BluetoothCentralManagerCallback mBluetoothCentralManagerCallback = new BluetoothCentralManagerCallback() {
        @Override
        public void onDiscoveredPeripheral(BluetoothPeripheral peripheral, ScanResult scanResult) {
            Log.i(TAG,"BluetoothCentralManagerCallback.onDiscoveredPeripheral()");

            int discovered = sDiscoveredCount.incrementAndGet();
            try {
                Log.i(TAG, DIAG_TAG + ": onDiscoveredPeripheral #" + discovered
                        + " addr=" + peripheral.getAddress()
                        + " rssi=" + scanResult.getRssi()
                        + " generation=" + mManagerGeneration
                        + " managerHashCode=" + System.identityHashCode(mBluetoothCentralManager)
                        + " mScanActive=" + mScanActive
                        + " state=" + mConnectionState);
                // A discovery while we believe our own scan is stopped means the event came
                // from a manager we have already superseded - the D3 signature.
                if (!mScanActive) {
                    Log.w(TAG, DIAG_TAG + ": SUSPECT ZOMBIE CALLBACK - discovery delivered while"
                            + " mScanActive=false. This discovery did not come from the scan this"
                            + " instance believes it owns (D3).");
                }
            } catch (Exception e) {
                Log.w(TAG, DIAG_TAG + ": onDiscoveredPeripheral - logging failed: " + e.getMessage());
            }

            // CRITICAL: Null-safety check - manager can be destroyed during extended disconnections
            if (mBluetoothCentralManager == null) {
                Log.e(TAG, "onDiscoveredPeripheral() - BluetoothCentralManager is null, ignoring scan result");
                Log.e(TAG, DIAG_TAG + ": onDiscoveredPeripheral DROPPED - manager is null");
                return;
            }

            // Check if we're shutting down - ignore new discoveries during shutdown
            if (mIsShuttingDown || mShutdown) {
                Log.w(TAG, "onDiscoveredPeripheral() - System is shutting down, ignoring discovery");
                Log.w(TAG, DIAG_TAG + ": onDiscoveredPeripheral DROPPED - shutting down"
                        + " (mIsShuttingDown=" + mIsShuttingDown + ", mShutdown=" + mShutdown + ")");
                return;
            }

            try {
                // Update state machine
                setConnectionState(ConnectionState.CONNECTING, "onDiscoveredPeripheral");

                mBluetoothCentralManager.stopScan();
                mScanActive = false;
                int autoConnects = sAutoConnectCount.incrementAndGet();
                Log.i(TAG, DIAG_TAG + ": issuing autoConnectPeripheral #" + autoConnects
                        + " - NOTE blessed starts no timer for autoConnect, so if this never"
                        + " resolves there will be no further callback (D2).");
                mConnectAttemptPending = true;
                mConnectAttemptStartMillis = System.currentTimeMillis();
                mBluetoothCentralManager.autoConnectPeripheral(peripheral, peripheralCallback);

                // Reset reconnection attempt counter on successful discovery
                mReconnectionAttempt = 0;
                Log.i(TAG, "onDiscoveredPeripheral() - Successfully initiated connection");
            } catch (NullPointerException e) {
                Log.e(TAG, "onDiscoveredPeripheral() - NPE while processing discovery: " + e.getMessage());
                mUtil.writeExceptionLog("SdDataSourceBLE2", "onDiscoveredPeripheral NPE", e);
                scheduleReconnection();
            } catch (Exception e) {
                Log.e(TAG, "onDiscoveredPeripheral() - Error during discovery: " + e.getMessage());
                mUtil.writeExceptionLog("SdDataSourceBLE2", "onDiscoveredPeripheral", e);
                scheduleReconnection();
            }
        }

        /**
         * Diagnostics only (Phase 0). blessed reports that a connection attempt has begun.
         * Overriding this is what lets us distinguish "blessed is trying" from "blessed has
         * silently given up", which is otherwise invisible.
         */
        @Override
        public void onConnectingPeripheral(BluetoothPeripheral peripheral) {
            Log.i(TAG, DIAG_TAG + ": onConnectingPeripheral addr=" + safeAddress(peripheral)
                    + " generation=" + mManagerGeneration
                    + " state=" + mConnectionState);
            super.onConnectingPeripheral(peripheral);
        }

        /**
         * Diagnostics only (Phase 0). blessed reports that a disconnect we asked for is in
         * progress. Pairs with onConnectingPeripheral to bracket the shutdown sequence.
         */
        @Override
        public void onDisconnectingPeripheral(BluetoothPeripheral peripheral) {
            Log.i(TAG, DIAG_TAG + ": onDisconnectingPeripheral addr=" + safeAddress(peripheral)
                    + " generation=" + mManagerGeneration
                    + " mShutdown=" + mShutdown
                    + " state=" + mConnectionState);
            super.onDisconnectingPeripheral(peripheral);
        }

        /**
         * Diagnostics only (Phase 0) - deliberately takes NO recovery action yet.
         *
         * blessed calls stopScan() itself before delivering this, so scanning is definitively
         * over and blessed will not restart it. Until now BLE2 did not override this method at
         * all, which is why a failed scan left the datasource parked in SCANNING forever with
         * no retry (D4). APPLICATION_REGISTRATION_FAILED / OUT_OF_HARDWARE_RESOURCES /
         * SCANNING_TOO_FREQUENTLY are the codes that a registration leak (D1) produces.
         *
         * Phase 2 will add scheduleReconnection() here; for now we only record it so the
         * failure is visible in the field.
         */
        @Override
        public void onScanFailed(ScanFailure failure) {
            int failed = sScanFailedCount.incrementAndGet();
            mScanActive = false;
            Log.e(TAG, "onScanFailed() - " + failure);
            Log.e(TAG, DIAG_TAG + ": onScanFailed #" + failed + " failure=" + failure
                    + " generation=" + mManagerGeneration
                    + " scanStartsSoFar=" + sScanStartCount.get()
                    + " state=" + mConnectionState
                    + " - blessed has stopped scanning and will NOT restart it (D4)."
                    + " No recovery action is taken in Phase 0.");
            mUtil.writeMemoryLog("BLE2 onScanFailed " + failure
                    + " count=" + failed + " scans=" + sScanStartCount.get());
            super.onScanFailed(failure);
        }

        /**
         * Diagnostics only (Phase 0) - deliberately takes NO recovery action yet.
         *
         * blessed stops all scans and clears its reconnect bookkeeping on STATE_TURNING_OFF and
         * restarts nothing on STATE_ON, so any Bluetooth toggle currently leaves BLE2 unable to
         * recover (D5). Recording the transitions makes that visible; Phase 2 will re-scan on
         * STATE_ON.
         */
        @Override
        public void onBluetoothAdapterStateChanged(int state) {
            int changes = sAdapterStateCount.incrementAndGet();
            Log.i(TAG, DIAG_TAG + ": onBluetoothAdapterStateChanged #" + changes
                    + " state=" + adapterStateToString(state)
                    + " generation=" + mManagerGeneration
                    + " state=" + mConnectionState);
            if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                mScanActive = false;
                Log.w(TAG, DIAG_TAG + ": Bluetooth going off - blessed will cancel all connections,"
                        + " clear its reconnect lists and stop all scans. Nothing restarts them"
                        + " automatically (D5).");
                mUtil.writeMemoryLog("BLE2 adapter state " + adapterStateToString(state));
            }
            if (state == BluetoothAdapter.STATE_ON) {
                Log.w(TAG, DIAG_TAG + ": Bluetooth back ON - no automatic re-scan is issued in"
                        + " Phase 0; recovery depends on the SdServer fault-restart cycle.");
                mUtil.writeMemoryLog("BLE2 adapter state ON, mScanActive=" + mScanActive);
            }
            super.onBluetoothAdapterStateChanged(state);
        }
        @Override
        public void onConnectedPeripheral(BluetoothPeripheral peripheral) {
            Log.i(TAG,"BluetoothCentralManagerCallback.onConnectedPeripheral()");
            int connected = sConnectedCount.incrementAndGet();
            long attemptMs = mConnectAttemptPending
                    ? (System.currentTimeMillis() - mConnectAttemptStartMillis) : -1;
            mConnectAttemptPending = false;
            Log.i(TAG, DIAG_TAG + ": onConnectedPeripheral #" + connected
                    + " addr=" + safeAddress(peripheral)
                    + " generation=" + mManagerGeneration
                    + " connectAttemptDurationMs=" + attemptMs
                    + " discoveriesSoFar=" + sDiscoveredCount.get());
            mUtil.writeMemoryLog("BLE2 connected #" + connected
                    + " attemptMs=" + attemptMs + " gen=" + mManagerGeneration);
            setConnectionState(ConnectionState.CONNECTED, "onConnectedPeripheral");
            mReconnectionAttempt = 0; // Reset reconnection counter on successful connection
            mUtil.showToast("Watch Connected");
            super.onConnectedPeripheral(peripheral);
        }
        @Override
        public void onConnectionFailed(BluetoothPeripheral peripheral, HciStatus status) {
            Log.i(TAG,"BluetoothCentralManagerCallback.onConnectionFailed() - status=" + status);
            int failed = sConnectionFailedCount.incrementAndGet();
            mConnectAttemptPending = false;
            Log.w(TAG, DIAG_TAG + ": onConnectionFailed #" + failed + " status=" + status
                    + " addr=" + safeAddress(peripheral)
                    + " generation=" + mManagerGeneration
                    + " - blessed has already retried once internally (MAX_CONNECTION_RETRIES=1).");
            mUtil.writeMemoryLog("BLE2 connectionFailed " + status + " count=" + failed);
            setConnectionState(ConnectionState.IDLE, "onConnectionFailed");
            mUtil.showToast("Failed to Connect to Watch - Retrying");

            // Defensive null-check
            if (mBluetoothCentralManager != null && !mIsShuttingDown && !mShutdown) {
                try {
                    int autoConnects = sAutoConnectCount.incrementAndGet();
                    Log.i(TAG, DIAG_TAG + ": onConnectionFailed issuing autoConnectPeripheral #"
                            + autoConnects);
                    mConnectAttemptPending = true;
                    mConnectAttemptStartMillis = System.currentTimeMillis();
                    mBluetoothCentralManager.autoConnectPeripheral(peripheral, peripheralCallback);
                } catch (Exception e) {
                    Log.w(TAG, "onConnectionFailed() - Error attempting reconnection: " + e.getMessage());
                    scheduleReconnection();
                }
            } else {
                Log.w(TAG, "onConnectionFailed() - Manager null or shutting down, scheduling reconnection");
                scheduleReconnection();
            }
            super.onConnectionFailed(peripheral, status);
        }
        @Override
        public void onDisconnectedPeripheral(BluetoothPeripheral peripheral, HciStatus status) {
            Log.i(TAG,"BluetoothCentralManagerCallback.onDisconnectedPeripheral() - status=" + status);

            int disconnected = sDisconnectedCount.incrementAndGet();
            mServicesDiscovered = false;
            mConnectAttemptPending = false;
            long sinceAccData = sLastAccDataMillis.get() > 0
                    ? (System.currentTimeMillis() - sLastAccDataMillis.get()) : -1;
            Log.i(TAG, DIAG_TAG + ": onDisconnectedPeripheral #" + disconnected
                    + " status=" + status
                    + " addr=" + safeAddress(peripheral)
                    + " generation=" + mManagerGeneration
                    + " mShutdown=" + mShutdown
                    + " mIsShuttingDown=" + mIsShuttingDown
                    + " msSinceLastAccData=" + sinceAccData
                    + " state=" + mConnectionState);
            if (status == HciStatus.CONNECTION_TIMEOUT) {
                Log.w(TAG, DIAG_TAG + ": disconnect reason is CONNECTION_TIMEOUT (supervision"
                        + " timeout) - consistent with the watch going out of range.");
            }
            mUtil.writeMemoryLog("BLE2 disconnected #" + disconnected + " status=" + status
                    + " shutdown=" + mShutdown);

            if (mShutdown) {
                Log.i(TAG,"onDisconnectedPeripheral() - mShutdown is set, completing disconnect");
                setConnectionState(ConnectionState.CLEANUP, "onDisconnectedPeripheral(shutdown)");
                mDisconnected = true;
                // Cancel timeout handler
                if (mTimeoutHandler != null) {
                    mTimeoutHandler.removeCallbacksAndMessages(null);
                }
                // Complete cleanup
                forceCleanup();
            } else {
                Log.i(TAG,"onDisconnectedPeripheral() - unexpected disconnect");
                setConnectionState(ConnectionState.IDLE, "onDisconnectedPeripheral(unexpected)");
                mUtil.showToast("WATCH CONNECTION LOST");
                int nextAttempt = mReconnectionAttempt + 1;
                int nextDelay = (mReconnectionAttempt < BACKOFF_DELAYS_MS.length)
                        ? BACKOFF_DELAYS_MS[mReconnectionAttempt]
                        : BACKOFF_DELAYS_MS[BACKOFF_DELAYS_MS.length - 1];
                Log.i(TAG, "onDisconnectedPeripheral() - attempting to re-connect with backoff"
                        + " (attempt=" + nextAttempt + ", nextDelay=" + nextDelay + "ms)");

                // We do not call bleDisconnect() here because that initiates a shutdown sequence
                // including a timeout that will force close the manager, which we do not want
                // when trying to reconnect.
                // bleDisconnect();

                mShutdown = false;
                mDisconnected = false;

                // Check if manager is still available before reconnecting
                if (mBluetoothCentralManager != null && !mIsShuttingDown) {
                    try {
                        int autoConnects = sAutoConnectCount.incrementAndGet();
                        Log.i(TAG, DIAG_TAG + ": onDisconnectedPeripheral issuing"
                                + " autoConnectPeripheral #" + autoConnects
                                + " - blessed arms NO timer for autoConnect and short-circuits"
                                + " repeat calls with 'already issued autoconnect', so if this"
                                + " never resolves nothing else will fire (D2/D7).");
                        mConnectAttemptPending = true;
                        mConnectAttemptStartMillis = System.currentTimeMillis();
                        mBluetoothCentralManager.autoConnectPeripheral(peripheral, peripheralCallback);
                    } catch (Exception e) {
                        Log.w(TAG, "onDisconnectedPeripheral() - Error attempting immediate reconnection: " + e.getMessage());
                        scheduleReconnection();
                    }
                } else {
                    Log.w(TAG, "onDisconnectedPeripheral() - BluetoothCentralManager is null or shutting down");
                    scheduleReconnection();
                }
            }
            super.onDisconnectedPeripheral(peripheral, status);
        }


    };

    private @NotNull BluetoothPeripheral mBlePeripheral;
    // Callback for peripherals
    private final BluetoothPeripheralCallback peripheralCallback = new BluetoothPeripheralCallback() {

        @Override // BluetoothPeripheralCallback
        public void onServicesDiscovered(@NotNull BluetoothPeripheral peripheral) {
            if (mIsShuttingDown || mShutdown || !isRunning()) {
                return;
            }
            // Check if we have already discovered services for this connection to prevent double subscription
            // which causes double rate data updates.
            if (mServicesDiscovered) {
                Log.w(TAG, "onServicesDiscovered() - Services already discovered for this connection - ignoring");
                return;
            }
            mServicesDiscovered = true;

            Log.i(TAG,"onServicesDiscovered()");
            // NOTE (D1): this is the ONLY place mBlePeripheral is assigned. Throughout the
            // whole CONNECTING phase - and after any forceCleanup() - BLE2 therefore holds no
            // reference to the peripheral that blessed is actively connecting, so a pending
            // BluetoothGatt cannot be cancelled and is orphaned by close().
            Log.i(TAG, DIAG_TAG + ": onServicesDiscovered - assigning mBlePeripheral (was "
                    + (mBlePeripheral == null ? "null" : "hashCode=" + System.identityHashCode(mBlePeripheral))
                    + ") to hashCode=" + System.identityHashCode(peripheral)
                    + " generation=" + mManagerGeneration);
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
                        Log.i(TAG, "Subscribing to OSD Acceleration Data Change Notifications");
                        peripheral.setNotify(service.getUuid(), gattCharacteristic.getUuid(), true);
                        mOsdChar = gattCharacteristic;
                    } else if (charUuidStr.equals(CHAR_OSD_STATUS)) {
                        Log.i(TAG, "Found OSD Status Characteristic");
                        mStatusChar = gattCharacteristic;
                    } else if (charUuidStr.equals(CHAR_OSD_BATT_DATA)) {
                        Log.i(TAG, "Subscribing to OSD battery change Notifications");
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
            if (mIsShuttingDown || mShutdown || !isRunning()) {
                return;
            }
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
            if (mIsShuttingDown || mShutdown || !isRunning()) {
                return;
            }
            if (status == GattStatus.SUCCESS) {
                Log.d(TAG, String.format("SUCCESS: Writing <%s> to <%s>", asHexString(value), characteristic.getUuid()));
            } else {
                Log.w(TAG, String.format("ERROR: Failed writing <%s> to <%s> (%s)", asHexString(value), characteristic.getUuid(), status));
            }
        }

        @Override
        public void onCharacteristicUpdate(@NotNull BluetoothPeripheral peripheral, @NotNull byte[] value, @NotNull BluetoothGattCharacteristic characteristic, @NotNull GattStatus status) {
            if (mIsShuttingDown || mShutdown || !isRunning()) {
                int dropped = sDroppedUpdateCount.incrementAndGet();
                Log.w(TAG, DIAG_TAG + ": onCharacteristicUpdate DROPPED #" + dropped
                        + " char=" + characteristic.getUuid()
                        + " mIsShuttingDown=" + mIsShuttingDown
                        + " mShutdown=" + mShutdown
                        + " isRunning=" + isRunning());
                return;
            }
             if (status != GattStatus.SUCCESS) return;

            UUID characteristicUUID = characteristic.getUuid();
            BluetoothBytesParser parser = new BluetoothBytesParser(value);
            String charUuidStr = characteristicUUID.toString();

            Log.v(TAG,"onCharacteristicUpdate() - Characteristic "+charUuidStr+" updated");

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
                byte[] rawDataBytes = value;
                short[] newAccVals = parseDataToAccVals(rawDataBytes);
                Log.v(TAG, "onCharacteristicUpdate(): CHAR_OSD_ACC_DATA: numSamples = " + rawDataBytes.length + " nRawData=" + nRawData);
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
                                Log.e(TAG, "onCharacteristicUpdate(): INVALID ACCELERATION FORMAT" + mAccFmt);
                                //mUtil.showToast("INVALID ACCELERATION FORMAT " + mAccFmt);
                        }

                    } else {
                        Log.d(TAG, "onCharacteristicUpdate(): RawData Buffer Full - processing data");
                        mSdData.watchAppRunning = true;
                        for (i = 0; i < rawData.length; i++) {
                            mSdData.rawData[i] = rawData[i];
                            mSdData.rawData3D[i * 3] = rawData3d[i * 3];
                            mSdData.rawData3D[i * 3 + 1] = rawData3d[i * 3 + 1];
                            mSdData.rawData3D[i * 3 + 2] = rawData3d[i * 3 + 2];
                            //Log.v(TAG,"onDataReceived() i="+i+", "+rawData[i]);
                        }
                        mSdData.mNsamp = rawData.length;
                        mSdData.mSampleFreq = 25;  // BLE device always sends data at 25 Hz
                        mWatchAppRunningCheck = true;
                        mDataStatusTimeMillis = System.currentTimeMillis();
                        sLastAccDataMillis.set(mDataStatusTimeMillis);
                        int bursts = sAccBurstCount.incrementAndGet();
                        Log.i(TAG, DIAG_TAG + ": acc burst #" + bursts + " accepted"
                                + " - state=" + mConnectionState
                                + " generation=" + mManagerGeneration);
                        // Process the data to do seizure detection
                        doAnalysis();
                        // Update RSSI (check peripheral is still connected)
                        if (mBlePeripheral != null) {
                            mBlePeripheral.readRemoteRssi();
                        }
                        // Re-start collecting raw data.
                        nRawData = 0;
                        // Notify the device of the resulting alarm state
                        if (mStatusChar != null) {
                            Log.d(TAG, "onDataReceived() - Sending analysis result");
                            byte[] statusVal = new byte[1];
                            statusVal[0] = (byte) mSdData.alarmState;
                            peripheral.writeCharacteristic(mStatusChar, statusVal, WriteType.WITH_RESPONSE);
                        } else {
                            Log.i(TAG, "onCharacteristicUpdate() - mStatusChar is null - not sending result");
                        }
                    }
                }
            } else if (charUuidStr.equals(CHAR_BATT_DATA)
                    || charUuidStr.equals(CHAR_OSD_BATT_DATA)) {
                byte batteryPc = value[0];
                mSdData.batteryPc = batteryPc;
                Log.v(TAG, "onCharacteristicUpdate(): CHAR_BATT_DATA: " + String.format("%d", batteryPc));
                mSdData.haveSettings = true;
            } else if (charUuidStr.equals(CHAR_OSD_WATCH_ID) || charUuidStr.equals(CHAR_DEV_FW_NAME)) {
                byte[] rawDataBytes = value;
                String watchId = new String(rawDataBytes, StandardCharsets.UTF_8);
                Log.i(TAG, "Received Watch ID: " + watchId);
                mSdData.watchSdName = watchId;
            } else if (charUuidStr.equals(CHAR_OSD_ACC_FMT)) {
                mAccFmt = value[0];
                Log.i(TAG, "onCharacteristicUpdate(): Received Acceleration format code: " + mAccFmt);
            } else if (charUuidStr.equals(CHAR_DEV_MANUF)) {
                byte[] rawDataBytes = value;
                String watchManuf = new String(rawDataBytes, StandardCharsets.UTF_8);
                Log.i(TAG, "onCharacteristicUpdate(): Received Manufacturer: " + watchManuf);
                mSdData.watchManuf = watchManuf;
            } else if (charUuidStr.equals(CHAR_DEV_MODEL_NO)) {
                byte[] rawDataBytes = value;
                String watchModelNo = new String(rawDataBytes, StandardCharsets.UTF_8);
                Log.i(TAG, "onCharacteristicUpdate(): Received Watch Model No.: " + watchModelNo);
                mSdData.watchPartNo = watchModelNo;
            } else if (charUuidStr.equals(CHAR_DEV_SER_NO)) {
                byte[] rawDataBytes = value;
                String watchSerNo = new String(rawDataBytes, StandardCharsets.UTF_8);
                Log.i(TAG, "onCharacteristicUpdate(): Received Watch Serial No.: " + watchSerNo);
                //mSdData.watchSerNo = watchSerNo;
                // We do not use this serial number because it is zero for PineTime - we set the MAC address at start-up instead.
            } else if (charUuidStr.equals(CHAR_DEV_HW_VER)) {
                byte[] rawDataBytes = value;
                String watchHwVer = new String(rawDataBytes, StandardCharsets.UTF_8);
                Log.i(TAG, "onCharacteristicUpdate(): Received Hardware Version: " + watchHwVer);
                mSdData.watchFwVersion = watchHwVer;
            } else if (charUuidStr.equals(CHAR_DEV_FW_NAME)) {
                byte[] rawDataBytes = value;
                String watchFwName = new String(rawDataBytes, StandardCharsets.UTF_8);
                Log.i(TAG, "onCharacteristicUpdate(): Received Firmware Name: " + watchFwName);
                mSdData.watchSdName = watchFwName;
            } else if (charUuidStr.equals(CHAR_OSD_WATCH_FW)  || charUuidStr.equals(CHAR_DEV_FW_VER)) {
                byte[] rawDataBytes = value;
                String watchFwVer = new String(rawDataBytes, StandardCharsets.UTF_8);
                Log.i(TAG, "onCharacteristicUpdate(): Received Watch Firmware Version: " + watchFwVer);
                mSdData.watchSdVersion = watchFwVer;
            } else {
                byte[] rawDataBytes = value;
                String strVal = new String(rawDataBytes, StandardCharsets.UTF_8);
                Log.d(TAG, "onCharacteristicUpdate(): Unrecognised Characteristic Updated " +
                        charUuidStr+" : "+strVal);
            }
        }

        @Override
        public void onMtuChanged(@NotNull BluetoothPeripheral peripheral, int mtu, @NotNull GattStatus status) {
            if (mIsShuttingDown || mShutdown || !isRunning()) {
                return;
            }
             Log.i(TAG, String.format("onMtuChanged(): new MTU set: %d", mtu));
        }

        @Override
        public void onReadRemoteRssi(@NotNull BluetoothPeripheral peripheral, int rssi, @NotNull GattStatus status) {
            if (mIsShuttingDown || mShutdown || !isRunning()) {
                return;
            }
             Log.d(TAG, String.format("onReadRemogeRssi(): Rssi = %d", rssi));
             mSdData.watchSignalStrength = rssi;
         }

    };



    private void bleDisconnect() {
        Log.i(TAG, "bleDisconnect() - Starting disconnect sequence");
        Log.i(TAG, DIAG_TAG + ": bleDisconnect() entered on thread="
                + Thread.currentThread().getName()
                + " mBlePeripheral=" + (mBlePeripheral == null
                        ? "null (a pending BluetoothGatt cannot be cancelled from here - D1)"
                        : "hashCode=" + System.identityHashCode(mBlePeripheral))
                + " generation=" + mManagerGeneration);
        logState("bleDisconnect-enter");
        setConnectionState(ConnectionState.DISCONNECTING, "bleDisconnect");
        mShutdown = true;
        mDisconnected = false;
        mConnectAttemptPending = false;
        stopDiagHeartbeat();

        // Cancel any pending reconnection attempts
        if (mReconnectionHandler != null) {
            mReconnectionHandler.removeCallbacksAndMessages(null);
        }

        // Stop any active scans to reduce callbacks during shutdown
        if (mBluetoothCentralManager != null) {
            try {
                mBluetoothCentralManager.stopScan();
                mScanActive = false;
                Log.i(TAG, DIAG_TAG + ": bleDisconnect stopped our scan. NOTE blessed's separate"
                        + " autoconnect-by-scan scanner and its 180s scan-restart timer are NOT"
                        + " stopped by stopScan() or by close() (D3).");
            } catch (Exception e) {
                Log.w(TAG, "bleDisconnect() - Error stopping scan: " + e.getMessage());
            }
        }

        // Set timeout to force cleanup if disconnect hangs
        mTimeoutHandler.postDelayed(() -> {
            if (!mDisconnected) {
                Log.w(TAG, "bleDisconnect() - Timeout reached, forcing cleanup");
                forceCleanup();
            }
        }, DISCONNECT_TIMEOUT_MS);

        try {
            Log.i(TAG, "bleDisconnect() - Unregistering notifications");
            if (mBlePeripheral != null) {
                if (mOsdChar != null) {
                    Log.i(TAG, "bleDisconnect() - unregistering mOsdChar");
                    try {
                        mBlePeripheral.setNotify(mOsdChar, false);
                    } catch (Exception e) {
                        Log.w(TAG, "bleDisconnect() - Error unregistering mOsdChar: " + e.getMessage());
                    }
                } else {
                    Log.w(TAG, "bleDisconnect() - mOsdChar is null - not removing notification");
                }
                if (mHrChar != null) {
                    Log.i(TAG, "bleDisconnect() - unregistering mHrChar");
                    try {
                        mBlePeripheral.setNotify(mHrChar, false);
                    } catch (Exception e) {
                        Log.w(TAG, "bleDisconnect() - Error unregistering mHrChar: " + e.getMessage());
                    }
                } else {
                    Log.w(TAG, "bleDisconnect() - mHrChar is null - not removing notification");
                }
                if (mBattChar != null) {
                    Log.i(TAG, "bleDisconnect() - unregistering mBattChar");
                    try {
                        mBlePeripheral.setNotify(mBattChar, false);
                    } catch (Exception e) {
                        Log.w(TAG, "bleDisconnect() - Error unregistering mBattChar: " + e.getMessage());
                    }
                } else {
                    Log.w(TAG, "bleDisconnect() - mBattChar is null - not removing notification");
                }

                Log.i(TAG, "bleDisconnect() - Cancelling connection");
                try {
                    // NOTE: this is the PERIPHERAL-level cancelConnection(). Only the MANAGER-level
                    // BluetoothCentralManager.cancelConnection(p) also clears blessed's
                    // reconnectPeripheralAddresses / reconnectCallbacks bookkeeping.
                    // blessed's cancelConnection() returns silently with NO callback when
                    // bluetoothGatt is null or the state is already DISCONNECTED, so this call
                    // cannot be relied on to set mDisconnected (D6).
                    mBlePeripheral.cancelConnection();
                } catch (Exception e) {
                    Log.e(TAG, "bleDisconnect() - Error cancelling connection: " + e.getMessage());
                    forceCleanup();
                }
            } else {
                Log.w(TAG, "bleDisconnect() - mBlePeripheral is null - forcing cleanup");
                Log.e(TAG, DIAG_TAG + ": D1 PATH TAKEN - mBlePeripheral is null, so any pending"
                        + " BluetoothGatt held in blessed's unconnectedPeripherals map will be"
                        + " orphaned by close() and never disconnect()/close()d. Its two bonding"
                        + " BroadcastReceivers leak with it. This is the registration leak.");
                mUtil.writeMemoryLog("BLE2 D1 path: mBlePeripheral null at bleDisconnect");
                forceCleanup();
            }
        } catch (Exception e) {
            Log.e(TAG, "bleDisconnect() - Error during disconnect: " + e.getMessage());
            mUtil.showToast("Error disconnecting from watch");
            forceCleanup();
        }
    }

    /**
     * Force cleanup of all BLE resources, used when disconnect fails or times out
     */
    private void forceCleanup() {
        Log.i(TAG, "forceCleanup() - Forcing cleanup of BLE resources");
        int cleanups = sForceCleanupCount.incrementAndGet();
        Log.w(TAG, DIAG_TAG + ": forceCleanup #" + cleanups + " on thread="
                + Thread.currentThread().getName()
                + " generation=" + mManagerGeneration
                + " mBlePeripheral=" + (mBlePeripheral == null ? "null"
                        : "hashCode=" + System.identityHashCode(mBlePeripheral))
                + " mScanActive=" + mScanActive);
        setConnectionState(ConnectionState.CLEANUP, "forceCleanup");

        mServicesDiscovered = false;
        mScanActive = false;
        mConnectAttemptPending = false;
        stopDiagHeartbeat();
        
        try {
            // Cancel any pending timeout
            if (mTimeoutHandler != null) {
                mTimeoutHandler.removeCallbacksAndMessages(null);
            }

            // Cancel any pending reconnection attempts
            if (mReconnectionHandler != null) {
                mReconnectionHandler.removeCallbacksAndMessages(null);
            }

            // Clear all characteristics
            mOsdChar = null;
            mStatusChar = null;
            mHrChar = null;
            mBattChar = null;

            // Clear peripheral reference
            if (mBlePeripheral != null) {
                try {
                    mBlePeripheral.cancelConnection();
                } catch (Exception e) {
                    Log.w(TAG, "forceCleanup() - Error cancelling peripheral connection: " + e.getMessage());
                }
                mBlePeripheral = null;
            } else {
                Log.w(TAG, DIAG_TAG + ": forceCleanup with mBlePeripheral == null - any peripheral"
                        + " blessed is still connecting cannot be cancelled from here (D1).");
            }

            // Close the central manager
            if (mBluetoothCentralManager != null) {
                try {
                    // Record what blessed is about to drop on the floor. close() only clears its
                    // internal maps and unregisters the adapter-state receiver: it does NOT stop
                    // scans, does NOT cancel blessed's 180s scan-restart / autoconnect timers, and
                    // does NOT disconnect or close any peripheral. Anything still in
                    // unconnectedPeripherals - i.e. a pending connectGatt(autoConnect=true) - is
                    // orphaned here and stays registered against the app's GATT-client budget.
                    int stillConnected = -1;
                    try {
                        stillConnected = mBluetoothCentralManager.getConnectedPeripherals().size();
                    } catch (Exception e) {
                        Log.w(TAG, DIAG_TAG + ": could not read connected peripherals: " + e.getMessage());
                    }
                    Log.w(TAG, DIAG_TAG + ": closing manager generation=" + mManagerGeneration
                            + " connectedPeripherals=" + stillConnected
                            + " (blessed's UNconnected/pending peripherals are not enumerable via"
                            + " the public API and will be orphaned).");
                    mBluetoothCentralManager.close();
                } catch (Exception e) {
                    Log.w(TAG, "forceCleanup() - Error closing central manager: " + e.getMessage());
                    mUtil.writeExceptionLog("SdDataSourceBLE2", "forceCleanup - close", e);
                }
                mBluetoothCentralManager = null;
                mManagerGeneration = -1;
            }

            mDisconnected = true;
            Log.i(TAG, "forceCleanup() - Cleanup complete");
            Log.i(TAG, DIAG_TAG + ": forceCleanup #" + cleanups + " complete - counters: "
                    + countersToString());
            mUtil.writeMemoryLog("BLE2 forceCleanup #" + cleanups
                    + " mgrCreated=" + sManagerCreateCount.get());
        } catch (Exception e) {
            Log.e(TAG, "forceCleanup() - Error during force cleanup: " + e.getMessage());
            mUtil.writeExceptionLog("SdDataSourceBLE2", "forceCleanup", e);
            mDisconnected = true; // Mark as disconnected anyway to allow service to stop
        }
    }

    /**
     * Stop the datasource from updating
     */
    public void stop() {
        Log.i(TAG, "stop() - Beginning shutdown sequence");
        mUtil.writeMemoryLog("SdDataSourceBLE2.stop");
        int stops = sStopCount.incrementAndGet();
        String stopThread = Thread.currentThread().getName();
        Log.i(TAG, DIAG_TAG + ": stop() #" + stops + " on thread=" + stopThread);
        if ("main".equals(stopThread)) {
            Log.w(TAG, DIAG_TAG + ": stop() is running on the MAIN thread. The busy-wait below"
                    + " blocks it, and both the blessed callbacks that would set mDisconnected"
                    + " and the mTimeoutHandler forceCleanup post run on this same looper - so"
                    + " the wait is guaranteed to expire by timeout (D6).");
        }
        logState("stop-enter");
        super.stop();

        try {
            mShutdown = true;
            mIsShuttingDown = true; // Prevent reconnection attempts during shutdown
            setConnectionState(ConnectionState.DISCONNECTING, "stop");

            // Stop the CurrentTimeService
            try {
                CurrentTimeService.stopServer();
                Log.i(TAG, "stop() - CurrentTimeService stopped");
            } catch (Exception e) {
                Log.e(TAG, "stop() - Error stopping CurrentTimeService: " + e.getMessage());
                mUtil.writeExceptionLog("SdDataSourceBLE2", "stop - CurrentTimeService", e);
            }

            // Initiate BLE disconnect with timeout
            bleDisconnect();

            // Wait briefly for disconnect to complete, but not longer than timeout
            int waitTime = 0;
            int maxWait = DISCONNECT_TIMEOUT_MS + 500; // Wait slightly longer than timeout
            int checkInterval = 100;
            while (!mDisconnected && waitTime < maxWait) {
                try {
                    Thread.sleep(checkInterval);
                    waitTime += checkInterval;
                } catch (InterruptedException e) {
                    Log.w(TAG, "stop() - Sleep interrupted");
                    break;
                }
            }

            if (!mDisconnected) {
                Log.w(TAG, "stop() - Disconnect did not complete in time, forcing cleanup");
                Log.w(TAG, DIAG_TAG + ": stop() #" + stops + " BUSY-WAIT EXPIRED after " + waitTime
                        + "ms on thread=" + stopThread + " - main thread was blocked for that"
                        + " whole period (D6). Forcing cleanup.");
                mUtil.writeMemoryLog("BLE2 stop busy-wait expired after " + waitTime + "ms");
                forceCleanup();
            } else {
                Log.i(TAG, DIAG_TAG + ": stop() #" + stops + " disconnect completed normally after "
                        + waitTime + "ms");
            }

            Log.i(TAG, "stop() - Shutdown sequence complete");
            Log.i(TAG, DIAG_TAG + ": stop() #" + stops + " complete - counters: "
                    + countersToString());

        } catch (Exception e) {
            Log.e(TAG, "stop() - Error stopping data source: " + e.getMessage());
            // Ensure cleanup happens even if there's an error
            forceCleanup();
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

    /**
     * Update the connection state and log state transitions.
     *
     * @param newState the state to move to
     * @param reason   where the transition was requested from, so a state trace read back from
     *                 the log shows cause as well as effect
     */
    private synchronized void setConnectionState(ConnectionState newState, String reason) {
        if (mConnectionState != newState) {
            Log.i(TAG, "setConnectionState() - Transition: " + mConnectionState + " -> " + newState);
            Log.i(TAG, DIAG_TAG + ": STATE " + mConnectionState + " -> " + newState
                    + " (because: " + reason + ") generation=" + mManagerGeneration
                    + " thread=" + Thread.currentThread().getName());
            mConnectionState = newState;
            Log.i(TAG, "BLE2 State: " + newState.name());
        } else {
            Log.i(TAG, DIAG_TAG + ": STATE unchanged at " + newState
                    + " (requested by: " + reason + ")");
        }
    }

    // ---------------------------------------------------------------------
    // Diagnostic helpers (Phase 0). Every one of these is wrapped so that a
    // failure while collecting diagnostics can never break BLE operation.
    // ---------------------------------------------------------------------

    /** Process-lifetime counters on one line - the primary evidence for the D1 leak. */
    private String countersToString() {
        return "mgrCreated=" + sManagerCreateCount.get()
                + " forceCleanup=" + sForceCleanupCount.get()
                + " scans=" + sScanStartCount.get()
                + " scanFailed=" + sScanFailedCount.get()
                + " discovered=" + sDiscoveredCount.get()
                + " autoConnect=" + sAutoConnectCount.get()
                + " connected=" + sConnectedCount.get()
                + " connFailed=" + sConnectionFailedCount.get()
                + " disconnected=" + sDisconnectedCount.get()
                + " adapterChanges=" + sAdapterStateCount.get()
                + " start=" + sStartCount.get()
                + " stop=" + sStopCount.get()
                + " accBursts=" + sAccBurstCount.get()
                + " droppedUpdates=" + sDroppedUpdateCount.get();
    }

    /** Address of a peripheral, tolerating nulls - diagnostics must not throw. */
    private String safeAddress(BluetoothPeripheral peripheral) {
        if (peripheral == null) return "null";
        try {
            return peripheral.getAddress();
        } catch (Exception e) {
            return "unavailable(" + e.getClass().getSimpleName() + ")";
        }
    }

    /** Human-readable BluetoothAdapter state, for the onBluetoothAdapterStateChanged trace. */
    private String adapterStateToString(int state) {
        switch (state) {
            case BluetoothAdapter.STATE_OFF:          return "STATE_OFF";
            case BluetoothAdapter.STATE_TURNING_ON:   return "STATE_TURNING_ON";
            case BluetoothAdapter.STATE_ON:           return "STATE_ON";
            case BluetoothAdapter.STATE_TURNING_OFF:  return "STATE_TURNING_OFF";
            default:                                  return "UNKNOWN(" + state + ")";
        }
    }

    /**
     * blessed's own view of whether a scan is live. Comparing this with mScanActive is how a
     * superseded ("zombie") manager is detected: if blessed says it is scanning but we believe
     * we stopped, the scan belongs to a manager we have already closed (D3).
     */
    private boolean isScanningSafely() {
        try {
            return mBluetoothCentralManager != null && mBluetoothCentralManager.isScanning();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Dump a full snapshot of the connection state machine, the flags that gate every retry
     * path, and the process-lifetime counters. Called on every callback, every state transition
     * of interest, and once per minute by the diagnostic heartbeat so that counter growth over a
     * long outage is visible without needing repeated dumpsys captures.
     */
    private void logState(String context) {
        try {
            long now = System.currentTimeMillis();
            long sinceAcc = sLastAccDataMillis.get() > 0 ? (now - sLastAccDataMillis.get()) : -1;
            long attemptAge = mConnectAttemptPending ? (now - mConnectAttemptStartMillis) : -1;
            boolean blessedScanning = isScanningSafely();

            StringBuilder sb = new StringBuilder();
            sb.append("STATE-DUMP[").append(context).append("] ")
              .append("state=").append(mConnectionState)
              .append(" gen=").append(mManagerGeneration)
              .append(" mgr=").append(mBluetoothCentralManager == null
                      ? "null" : "hashCode=" + System.identityHashCode(mBluetoothCentralManager))
              .append(" peripheral=").append(mBlePeripheral == null
                      ? "null" : "hashCode=" + System.identityHashCode(mBlePeripheral))
              .append(" scanActive(ours)=").append(mScanActive)
              .append(" scanning(blessed)=").append(blessedScanning)
              .append(" connectPending=").append(mConnectAttemptPending)
              .append(" connectPendingAgeMs=").append(attemptAge)
              .append(" servicesDiscovered=").append(mServicesDiscovered)
              .append(" mShutdown=").append(mShutdown)
              .append(" mIsShuttingDown=").append(mIsShuttingDown)
              .append(" mDisconnected=").append(mDisconnected)
              .append(" isRunning=").append(isRunning())
              .append(" reconnectAttempt=").append(mReconnectionAttempt)
              .append(" msSinceLastAccBurst=").append(sinceAcc)
              .append(" thread=").append(Thread.currentThread().getName())
              .append(" | ").append(countersToString());

            Log.i(TAG, DIAG_TAG + ": " + sb);

            // blessed scanning while we believe our scan is stopped is the D3 signature:
            // a manager we closed is still scanning and can deliver callbacks into us.
            if (blessedScanning && !mScanActive) {
                Log.w(TAG, DIAG_TAG + ": ANOMALY - blessed reports an active scan but this"
                        + " instance believes its scan is stopped. A superseded manager is"
                        + " probably still scanning and routing callbacks here (D3).");
            }
            // A pending connection attempt that has been outstanding far longer than blessed's
            // 35s direct-connect timeout means we are waiting on an autoConnect that will never
            // report anything (D2).
            if (mConnectAttemptPending && attemptAge > 60_000L) {
                Log.w(TAG, DIAG_TAG + ": ANOMALY - connection attempt pending for "
                        + (attemptAge / 1000) + "s with no callback. blessed arms no timer for"
                        + " autoConnect, so this will never resolve on its own (D2).");
            }
        } catch (Exception e) {
            Log.w(TAG, DIAG_TAG + ": logState failed for context=" + context
                    + " - " + e.getMessage());
        }
    }

    /** Start (or restart) the once-per-minute diagnostic heartbeat. */
    private void startDiagHeartbeat() {
        try {
            if (mDiagHandler == null) return;
            mDiagHandler.removeCallbacks(mDiagHeartbeat);
            mDiagHandler.postDelayed(mDiagHeartbeat, DIAG_HEARTBEAT_PERIOD_MS);
            Log.i(TAG, DIAG_TAG + ": diagnostic heartbeat started (period "
                    + (DIAG_HEARTBEAT_PERIOD_MS / 1000) + "s)");
        } catch (Exception e) {
            Log.w(TAG, DIAG_TAG + ": could not start heartbeat - " + e.getMessage());
        }
    }

    /** Stop the diagnostic heartbeat. */
    private void stopDiagHeartbeat() {
        try {
            if (mDiagHandler != null) {
                mDiagHandler.removeCallbacks(mDiagHeartbeat);
            }
        } catch (Exception e) {
            Log.w(TAG, DIAG_TAG + ": could not stop heartbeat - " + e.getMessage());
        }
    }

    /**
     * Schedule a reconnection attempt with exponential backoff
     * Retries indefinitely - continues with the last backoff delay once the array is exhausted
     */
    private void scheduleReconnection() {
        // Don't schedule if we're shutting down
        if (mIsShuttingDown || mShutdown) {
            Log.w(TAG, "scheduleReconnection() - Shutdown in progress, not scheduling reconnection");
            Log.w(TAG, DIAG_TAG + ": scheduleReconnection SUPPRESSED by shutdown flags"
                    + " (mIsShuttingDown=" + mIsShuttingDown + ", mShutdown=" + mShutdown + ")."
                    + " If these are left set, no retry will ever be scheduled (D8).");
            return;
        }

        // Calculate backoff delay - use last element if we've exhausted the array
        int delayMs;
        if (mReconnectionAttempt < BACKOFF_DELAYS_MS.length) {
            delayMs = BACKOFF_DELAYS_MS[mReconnectionAttempt];
        } else {
            // Continue with the last delay indefinitely
            delayMs = BACKOFF_DELAYS_MS[BACKOFF_DELAYS_MS.length - 1];
        }

        mReconnectionAttempt++;

        Log.i(TAG, "scheduleReconnection() - Scheduling reconnection attempt #" + mReconnectionAttempt
                + " in " + delayMs + "ms (total attempts so far: " + mReconnectionAttempt + ")");
        mUtil.showToast("Reconnecting to watch in " + (delayMs / 1000) + " seconds...");

        // Schedule the reconnection
        mReconnectionHandler.postDelayed(() -> {
            if (!mIsShuttingDown && !mShutdown) {
                Log.i(TAG, "scheduleReconnection() - Executing reconnection attempt #" + mReconnectionAttempt
                        + " (backoffDelay was " + delayMs + "ms)");
                Log.i(TAG, DIAG_TAG + ": backoff retry FIRING (attempt=" + mReconnectionAttempt
                        + "). NOTE this path is only reachable from catch blocks and the"
                        + " null-manager branch - it does NOT run in the normal disconnect path,"
                        + " so the backoff loop is effectively dead code (D7).");
                setConnectionState(ConnectionState.SCANNING, "scheduleReconnection");
                bleConnect();
            } else {
                Log.w(TAG, "scheduleReconnection() - Shutdown in progress, cancelling reconnection");
                Log.w(TAG, DIAG_TAG + ": backoff retry CANCELLED by shutdown flags (D8)");
            }
        }, delayMs);
    }

    /**
     * Reset the reconnection counter when connection is successful
     */
    private void resetReconnectionCounter() {
        mReconnectionAttempt = 0;
        Log.i(TAG, "resetReconnectionCounter() - Reconnection counter reset");
    }

}
