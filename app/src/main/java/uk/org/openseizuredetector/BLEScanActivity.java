package uk.org.openseizuredetector;

/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


import static androidx.core.content.PermissionChecker.PERMISSION_GRANTED;

import android.Manifest;
import android.app.Activity;
import android.app.ListActivity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.MenuItemCompat;

import java.util.ArrayList;

/**
 * Activity for scanning and displaying available Bluetooth LE devices.
 */
public class BLEScanActivity extends ListActivity {
    private LeDeviceListAdapter mLeDeviceListAdapter;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeScanner mBluetoothLeScanner;
    private boolean mScanning;
    private Handler mHandler;

    private boolean mPermissionsRequested = false;
    private final String TAG = "BLEScanActivity";

    private final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            //Manifest.permission.BLUETOOTH_PRIVILEGED,
    };

    private static final int REQUEST_ENABLE_BT = 1;
    // Stops scanning after 10 seconds.
    private static final long SCAN_PERIOD = 10000;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.ble_scan_activity);
        //this.getActionBar().setTitle(R.string.title_devices);
        this.setTitle(R.string.title_devices);
        mHandler = new Handler();

        // Use this check to determine whether BLE is supported on the device.  Then you can
        // selectively disable BLE-related features.
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, R.string.ble_not_supported, Toast.LENGTH_SHORT).show();
            finish();
        } else {
            boolean bleAvailable = true;
        }

        // Initializes a Bluetooth adapter.  For API level 18 and above, get a reference to
        // BluetoothAdapter through BluetoothManager.
        final BluetoothManager bluetoothManager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = bluetoothManager.getAdapter();

        // Checks if Bluetooth is supported on the device.
        if (mBluetoothAdapter == null) {
            Toast.makeText(this, R.string.error_bluetooth_not_supported, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        mBluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.ble_scan_menu, menu);
        if (!mScanning) {
            menu.findItem(R.id.menu_stop).setVisible(false);
            menu.findItem(R.id.menu_scan).setVisible(true);
            MenuItemCompat.setActionView(menu.findItem(R.id.menu_refresh), null);
        } else {
            menu.findItem(R.id.menu_stop).setVisible(true);
            menu.findItem(R.id.menu_scan).setVisible(false);
            menu.findItem(R.id.menu_refresh).setActionView(
                    R.layout.actionbar_indeterminate_progress);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_scan:
                mLeDeviceListAdapter.clear();
                scanLeDevice(true);
                break;
            case R.id.menu_stop:
                scanLeDevice(false);
                break;
        }
        return true;
    }


    public void onScanButtonClick(View v) {
        scanLeDevice(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        SharedPreferences SP = PreferenceManager
                .getDefaultSharedPreferences(this);
        TextView tv = (TextView) findViewById(R.id.current_ble_device_tv);
        try {
            String bleAddr = SP.getString("BLE_Device_Addr", "none");
            String bleName = SP.getString("BLE_Device_Name", "none");
            tv.setText("Current Device=" + bleName + " (" + bleAddr + ")");
        } catch (Exception e) {
            tv.setText("Current Device=" + "none" + " (" + "none" + ")");
        }

        tv = (TextView) findViewById(R.id.ble_present_tv);
        if (mBluetoothAdapter == null) {
            tv.setText("ERROR - Bluetooth Adapter Not Present");
        } else {
            tv.setText("Bluetooth Adapter Present - OK");
        }
        // Ensures Bluetooth is enabled on the device.  If Bluetooth is not currently enabled,
        // fire an intent to display a dialog asking the user to grant permission to enable it.
        tv = (TextView) findViewById(R.id.ble_adapter_tv);
        if (!mBluetoothAdapter.isEnabled()) {
            tv.setText("ERROR - Bluetoot NOT Enabled");
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        } else {
            tv.setText("Bluetooth Adapter Enabled OK");
        }

        requestBTPermissions(this);

        for (int i = 0; i < REQUIRED_PERMISSIONS.length; i++) {
            if (ContextCompat.checkSelfPermission(this, REQUIRED_PERMISSIONS[i]) == PERMISSION_GRANTED) {
                Log.i(TAG, "Permission " + REQUIRED_PERMISSIONS[i] + " OK");
            } else {
                Log.e(TAG, "Permission " + REQUIRED_PERMISSIONS[i] + " NOT GRANTED");
                Toast.makeText(this, "ERROR - Permission " + REQUIRED_PERMISSIONS[i] + " not Granted - this will not work!!!!!", Toast.LENGTH_SHORT).show();
            }
        }

        tv = (TextView) findViewById(R.id.ble_perm1_tv);
        if (ContextCompat.checkSelfPermission(this, REQUIRED_PERMISSIONS[0]) == PERMISSION_GRANTED) {
            tv.setText("Permission " + REQUIRED_PERMISSIONS[0] + " OK");
        } else {
            tv.setText("ERROR: Permission " + REQUIRED_PERMISSIONS[0] + " NOT GRANTED");
        }
        tv = (TextView) findViewById(R.id.ble_perm2_tv);
        if (ContextCompat.checkSelfPermission(this, REQUIRED_PERMISSIONS[1]) == PERMISSION_GRANTED) {
            tv.setText("Permission " + REQUIRED_PERMISSIONS[1] + " OK");
        } else {
            tv.setText("ERROR: Permission " + REQUIRED_PERMISSIONS[1] + " NOT GRANTED");
        }
        tv = (TextView) findViewById(R.id.ble_perm3_tv);
        if (ContextCompat.checkSelfPermission(this, REQUIRED_PERMISSIONS[2]) == PERMISSION_GRANTED) {
            tv.setText("Permission " + REQUIRED_PERMISSIONS[2] + " OK");
        } else {
            tv.setText("ERROR: Permission " + REQUIRED_PERMISSIONS[2] + " NOT GRANTED");
        }
        tv = (TextView) findViewById(R.id.ble_perm4_tv);
        if (ContextCompat.checkSelfPermission(this, REQUIRED_PERMISSIONS[3]) == PERMISSION_GRANTED) {
            tv.setText("Permission " + REQUIRED_PERMISSIONS[3] + " OK");
        } else {
            tv.setText("ERROR: Permission " + REQUIRED_PERMISSIONS[3] + " NOT GRANTED");
        }


        // Initializes list view adapter.
        mLeDeviceListAdapter = new LeDeviceListAdapter();
        setListAdapter(mLeDeviceListAdapter);

        scanLeDevice(true);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // User chose not to enable Bluetooth.
        if (requestCode == REQUEST_ENABLE_BT && resultCode == Activity.RESULT_CANCELED) {
            finish();
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onPause() {
        super.onPause();
        scanLeDevice(false);
        mLeDeviceListAdapter.clear();
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        final BluetoothDevice device = mLeDeviceListAdapter.getDevice(position);
        if (device == null) return;
        Log.v(TAG, "onListItemClick: Device=" + device.getName() + ", Addr=" + device.getAddress());
        if (mScanning) {
            mBluetoothLeScanner.stopScan(mLeScanCallback);
            mScanning = false;
        }
        Log.v(TAG, "Saving Device Details");
        SharedPreferences.Editor SPE = PreferenceManager
                .getDefaultSharedPreferences(this).edit();
        try {
            SPE.putString("BLE_Device_Addr", device.getAddress());
            SPE.putString("BLE_Device_Name", device.getName());
            SPE.apply();
            SPE.commit();

            Log.v(TAG, "Saved Device Name=" + device.getName() + " and Address=" + device.getAddress());
        } catch (Exception ex) {
            Log.e(TAG, "Error Saving Device Name and Address!");
            Toast toast = Toast.makeText(this, "Problem Saving Device Name and Address", Toast.LENGTH_SHORT);
            toast.show();
        }
        SharedPreferences SP = PreferenceManager.getDefaultSharedPreferences((this));
        Log.v(TAG, "Check of saved values - Name=" + SP.getString("BLE_Device_Name", "NOT SET") + ", Addr=" + SP.getString("BLE_Device_Addr", "NOT SET"));

        finish();
    }

    public void requestBTPermissions(Activity activity) {
        if (mPermissionsRequested) {
            Log.i(TAG, "requestPermissions() - request already sent - not doing anything");
        } else {
            Log.i(TAG, "requestPermissions() - requesting permissions");
            for (int i = 0; i < REQUIRED_PERMISSIONS.length; i++) {
                if (ActivityCompat.shouldShowRequestPermissionRationale(activity,
                        REQUIRED_PERMISSIONS[i])) {
                    Log.i(TAG, "shouldShowRationale for permission" + REQUIRED_PERMISSIONS[i]);
                }
            }
            ActivityCompat.requestPermissions(activity,
                    REQUIRED_PERMISSIONS,
                    42);
            mPermissionsRequested = true;
        }
    }


    private void scanLeDevice(final boolean enable) {
        TextView tv;

        if (enable) {
            // Stops scanning after a pre-defined scan period.
            mHandler.postDelayed(() -> {
                mScanning = false;
                mBluetoothLeScanner.stopScan(mLeScanCallback);
                invalidateOptionsMenu();
                TextView tv1 = (TextView) (findViewById(R.id.ble_scan_status_tv));
                tv1.setText("Stopped");
                Button b = (Button) findViewById(R.id.startScanButton);
                b.setEnabled(true);

            }, SCAN_PERIOD);

            mScanning = true;
            mBluetoothLeScanner.startScan(mLeScanCallback);
            tv = (TextView) (findViewById(R.id.ble_scan_status_tv));
            tv.setText("Scanning");
            Button b = (Button) findViewById(R.id.startScanButton);
            b.setEnabled(false);

        } else {
            mScanning = false;
            mBluetoothLeScanner.stopScan(mLeScanCallback);
            tv = (TextView) (findViewById(R.id.ble_scan_status_tv));
            tv.setText("Stopped");
            Button b = (Button) findViewById(R.id.startScanButton);
            b.setEnabled(true);
        }
        invalidateOptionsMenu();
    }

    // Adapter for holding devices found through scanning.
    private class LeDeviceListAdapter extends BaseAdapter {
        private ArrayList<BluetoothDevice> mLeDevices;
        private LayoutInflater mInflator;

        public LeDeviceListAdapter() {
            super();
            mLeDevices = new ArrayList<>();
            mInflator = BLEScanActivity.this.getLayoutInflater();
        }

        public void addDevice(BluetoothDevice device) {
            if (!mLeDevices.contains(device)) {
                Log.v(TAG, "addDevice - " + device.getName());
                mLeDevices.add(device);
            }
        }

        public BluetoothDevice getDevice(int position) {
            return mLeDevices.get(position);
        }

        public void clear() {
            mLeDevices.clear();
        }

        @Override
        public int getCount() {
            return mLeDevices.size();
        }

        @Override
        public Object getItem(int i) {
            return mLeDevices.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View view, ViewGroup viewGroup) {
            ViewHolder viewHolder;
            Log.v(TAG, "scanner getView i=" + i);
            // General ListView optimization code.
            if (view == null) {
                view = mInflator.inflate(R.layout.ble_list_item_device, null);
                viewHolder = new ViewHolder();
                viewHolder.deviceAddress = (TextView) view.findViewById(R.id.device_address);
                viewHolder.deviceName = (TextView) view.findViewById(R.id.device_name);
                view.setTag(viewHolder);
            } else {
                viewHolder = (ViewHolder) view.getTag();
            }

            BluetoothDevice device = mLeDevices.get(i);
            final String deviceName = device.getName();
            if (deviceName != null && deviceName.length() > 0)
                viewHolder.deviceName.setText(deviceName);
            else
                viewHolder.deviceName.setText(R.string.unknown_device);
            viewHolder.deviceAddress.setText(device.getAddress());

            return view;
        }
    }

    // Device scan callback.
    private ScanCallback mLeScanCallback =
            new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    //super.onScanResult(callbackType, result);
                    Log.v(TAG, "ScanCallback - " + result.getDevice().getName());
                    mLeDeviceListAdapter.addDevice(result.getDevice());
                    mLeDeviceListAdapter.notifyDataSetChanged();
                }
            };

    static class ViewHolder {
        TextView deviceName;
        TextView deviceAddress;
    }


}
