package com.example.bleframework;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.collection.ArraySet;
import androidx.core.app.ActivityCompat;

import org.godotengine.godot.Godot;
import org.godotengine.godot.plugin.GodotPlugin;
import org.godotengine.godot.plugin.SignalInfo;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class GodotBLE extends GodotPlugin
{
    private Activity activity;

    private final int ENABLE_BLUETOOTH_REQUEST_CODE = 1;

    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private boolean isScanning;

    private BluetoothGatt bluetoothGatt;
    private BluetoothGattService bluetoothGattService;
    private BluetoothGattCharacteristic bluetoothGattCharacteristic;

    private UUID ledServiceUUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b");
    private UUID ledCharacteristicUUID = UUID.fromString("19B10010-E8F2-537E-4F6C-D104768A1214");

    private ScanFilter filter = new ScanFilter.Builder()
            .setServiceUuid(new ParcelUuid(ledServiceUUID))
            .build();
    private ScanSettings scanSettings = new ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build();

    // Device scan callback.
    private ScanCallback leScanCallback =
            new ScanCallback()
            {
                @Override
                public void onScanResult(int callbackType, ScanResult result)
                {
                    super.onScanResult(callbackType, result);

                    Log.i("SCANNING", "found device " + result.getDevice().getName() + "with address " + result.getDevice().getAddress());
                    if(result.getDevice().getName() != null)
                        emitSignal("device_found", result.getDevice().getName(), result.getDevice().getAddress());
                    else
                        emitSignal("device_found", "", result.getDevice().getAddress());

                    if(isScanning) stopScan();

                    result.getDevice().connectGatt(activity, false, gattCallback);
                }

                @Override
                public void onScanFailed(int errorCode)
                {
                    Log.e("ScanCallback", "onScanFailed: code ".concat(String.valueOf(errorCode)));
                }
            };


    private BluetoothGattCallback gattCallback =
            new BluetoothGattCallback()
            {
                @Override
                public void onConnectionStateChange(BluetoothGatt gatt , int status, int newState)
                {
                    String deviceAddress = gatt.getDevice().getAddress();

                    if (status == BluetoothGatt.GATT_SUCCESS)
                    {
                        if (newState == BluetoothProfile.STATE_CONNECTED)
                        {
                            Log.w("BluetoothGattCallback", "Successfully connected to ".concat(deviceAddress));
                            bluetoothGatt = gatt;
                            bluetoothGatt.discoverServices();
                        }
                        else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            Log.w("BluetoothGattCallback", "Successfully disconnected from ".concat(deviceAddress));
                            gatt.close();
                        }
                    }
                    else
                    {
                        Log.w("BluetoothGattCallback", "Error ".concat(String.valueOf(status)).concat(" encountered for ").concat(deviceAddress).concat("! Disconnecting..."));
                        gatt.close();
                    }
                }

                @Override
                public void onServicesDiscovered(BluetoothGatt gatt , int status)
                {
                    bluetoothGattService = gatt.getService(ledServiceUUID);
                    if(bluetoothGattService == null)
                    {
                        Log.i("Service discovery", "Service not found");
                        gatt.close();
                        return;
                    }

                    bluetoothGattCharacteristic = bluetoothGattService.getCharacteristic(ledCharacteristicUUID);
                    if(bluetoothGattCharacteristic == null)
                    {
                        Log.i("Character discovery", "Characteristic not found");
                        gatt.close();
                        return;
                    }
                    if(!isWritable(bluetoothGattCharacteristic) && !isWritableNoResponse(bluetoothGattCharacteristic))
                    {
                        Log.i("Character discovery", "Characteristic not writable");
                        gatt.close();
                        return;
                    }
                    Log.i("Characteristic", "Characteristic found successfully");
                }

                @Override
                public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status)
                {
                    if(status == BluetoothGatt.GATT_SUCCESS)
                        Log.i("BluetoothGattCallback", "Wrote to characteristic ".concat(characteristic.getUuid().toString()));
                    else if(status == BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH)
                        Log.e("BluetoothGattCallback", "Write exceeded connection ATT MTU!");
                    else if(status == BluetoothGatt.GATT_WRITE_NOT_PERMITTED)
                        Log.e("BluetoothGattCallback", "Write not permitted for ".concat(characteristic.getUuid().toString()));
                    else
                        Log.e("BluetoothGattCallback", "Characteristic write failed for ".concat(characteristic.getUuid().toString()).concat(", error: ").concat(String.valueOf(status)));
                }
            };


    public GodotBLE(Godot godot)
    {
        super(godot);
    }


    @NonNull
    @Override
    public String getPluginName() {
        return "BLEPlugin";
    }

    @NonNull
    @Override
    public List<String> getPluginMethods()
    {
        return Arrays.asList("set", "startScan", "stopScan", "writeChar");
    }


    @NonNull
    @Override
    public Set<SignalInfo> getPluginSignals()
    {
        Set<SignalInfo> signals = new ArraySet<>();

        signals.add(new SignalInfo("device_found", String.class, String.class));

        return signals;
    }


    public void set()
    {
        activity = getActivity();

        // Initializes Bluetooth adapter.
        bluetoothManager = (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);

        bluetoothAdapter = null;
        if (bluetoothManager != null)
        {
            bluetoothAdapter = bluetoothManager.getAdapter();
            ActivityCompat.requestPermissions(activity,new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1001);
        }

        // Ensures Bluetooth is available on the device and it is enabled. If not,
        // displays a dialog requesting user permission to enable Bluetooth.
        if (bluetoothAdapter != null && !bluetoothAdapter.isEnabled())
        {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            activity.startActivityForResult(enableBtIntent, ENABLE_BLUETOOTH_REQUEST_CODE);
        }
     }


    public void startScan()
    {
        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        bluetoothLeScanner.startScan(Collections.singletonList(filter), scanSettings, leScanCallback);
        isScanning = true;
    }


    public void stopScan()
    {
        bluetoothLeScanner.stopScan(leScanCallback);
        isScanning = false;
    }


    private boolean isWritable(BluetoothGattCharacteristic bluethChar)
    {
        return (bluethChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) !=0;
    }

    private boolean isWritableNoResponse(BluetoothGattCharacteristic bluethChar)
    {
        return (bluethChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
    }


    public void writeChar(int value)
    {
        if (bluetoothAdapter == null || bluetoothGatt == null) {
            Log.w("ERROR", "BluetoothAdapter not initialized");
            return;
        }

        if(isWritable(bluetoothGattCharacteristic))
        {
            bluetoothGattCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            Log.i("Charact property", "PROPERY WRITE");
        }
        else
        {
            bluetoothGattCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            Log.i("Charact property", "PROPERY WRITE NO RESPONSE");
        }

        boolean b = bluetoothGattCharacteristic.setValue(value,BluetoothGattCharacteristic.FORMAT_UINT8,0);
        boolean c = bluetoothGatt.writeCharacteristic(bluetoothGattCharacteristic);

        if(b) Log.i("Charact write", "Correctly set value");
        else Log.i("Charact write", "UNCorrectly set value");

        if(c) Log.i("Charact write", "Correctly written");
        else Log.i("Charact write", "UNCorrectly written");
    }

}
