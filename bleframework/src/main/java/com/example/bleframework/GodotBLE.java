package com.example.bleframework;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
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
import android.location.LocationManager;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.collection.ArraySet;
import androidx.core.app.ActivityCompat;

import org.godotengine.godot.Godot;
import org.godotengine.godot.plugin.GodotPlugin;
import org.godotengine.godot.plugin.SignalInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class GodotBLE extends GodotPlugin
{
    private Activity activity;

    //codes for bluetooth and location enabling requests
    private final int ENABLE_BLUETOOTH_REQUEST_CODE = 1;
    private final int ENABLE_LOCATION_REQUEST_CODE = 2;

    //UUID of the descriptor used to subscribe to a characteristic notifications
    private final String CCC_DESCRIPTOR_UUID = "00002902-0000-1000-8000-00805F9B34FB";

    //Objects used to control Bluetooth operations on Android
    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;

    //True if a scan is being perfomed, false otherwise
    private boolean isScanning = false;

    //Mapping <device address, GATT object> in order to be able to communicate with more than one peripheral
    private Map<String, BluetoothGatt> bluetoothGatts = new HashMap<>();;

    // Maps to convert an UUID (String) to the corresponding Characterisitc/Service object
    private Map<String, Map<String, BluetoothGattCharacteristic>> characteristicMap = new HashMap<>();
    private Map<String, Map<String, BluetoothGattService>> serviceMap = new HashMap<>();

    //Objects use to set the scanning options
    private ScanFilter.Builder scanFilter = new ScanFilter.Builder();
    private ScanSettings scanSettings = new ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)  // SCAN_MODE_LOW_LATENCY  is recommended for short time scanning at the beginning of the App
            .build();

    //List of devices found during the last scanning
    private List<BluetoothDevice> scanResults;

    // Device scan callback
    private ScanCallback leScanCallback =
            new ScanCallback()
            {
                @Override
                public void onScanResult(int callbackType, ScanResult result)
                {
                    Log.i("SCANNING", "found device " + result.getDevice().getName() + "with address " + result.getDevice().getAddress());

                    BluetoothDevice device = result.getDevice();
                    if(scanResults.indexOf(device) == -1)
                    {
                        // Send a signal to Godot with name and address of the device found
                        if(result.getDevice().getName() != null)
                            emitSignal("device_found", device.getName(), device.getAddress());
                        else
                            emitSignal("device_found", "", device.getAddress());

                        scanResults.add(result.getDevice()); //add the result to the list
                    }

                }

                @Override
                public void onScanFailed(int errorCode)
                {
                    String scanFailedMessage = "Scan Failed: code ".concat(String.valueOf(errorCode));
                    Log.e("ScanCallback", "onScanFailed: code ".concat(String.valueOf(errorCode)));
                    emitSignal("scan_failed", scanFailedMessage);
                }
            };


    // Callbacks for any operation or change in the connection
    private BluetoothGattCallback gattCallback =
            new BluetoothGattCallback()
            {
                @Override
                public void onConnectionStateChange(BluetoothGatt gatt , int status, int newState)
                {
                    String deviceAddress = gatt.getDevice().getAddress();
                    String deviceName = gatt.getDevice().getName();

                    //if there was not an error, check if it was a connection or disconnection
                    if (status == BluetoothGatt.GATT_SUCCESS)
                    {
                        if (newState == BluetoothProfile.STATE_CONNECTED)
                        {
                            Log.w("BluetoothGattCallback", "Successfully connected to ".concat(deviceAddress));
                            bluetoothGatts.put(deviceAddress, gatt); //save the instance of the BluetoothGatt for this connection
                            //gatt.requestMtu(40);
                            gatt.discoverServices(); //discover services of the device we are connected to
                            emitSignal("device_connected", deviceAddress, deviceName); //send a signal to Godot to say that the connection was successfull
                        }
                        else if (newState == BluetoothProfile.STATE_DISCONNECTED)
                        {
                            Log.w("BluetoothGattCallback", "Successfully disconnected from ".concat(deviceAddress));
                            gatt.close();
                            bluetoothGatts.remove(deviceAddress);
                            emitSignal("device_disconnected", deviceAddress, deviceName);//send a signal to Godot to say that the device has been disconnected
                        }
                    }
                    else
                    {
                        Log.w("BluetoothGattCallback", "Error ".concat(String.valueOf(status)).concat(" encountered for ").concat(deviceAddress).concat("! Disconnecting..."));
                        String connectionErrorMessage = "Connection Error ".concat(String.valueOf(status)).concat(" encountered for ").concat(deviceAddress).concat("! Disconnecting...");
                        emitSignal("connection_error", connectionErrorMessage);
                        gatt.close();
                    }
                }

                @Override
                public void onServicesDiscovered(BluetoothGatt gatt , int status)
                {
                    String deviceAddress = gatt.getDevice().getAddress();
                    List<BluetoothGattService> services = gatt.getServices();
                    if(services.isEmpty()) //if no services found, close the connection
                    {
                        Log.i("Service discovery", "Services not found");
                        bluetoothGatts.remove(deviceAddress);
                        gatt.close();
                        return;
                    }

                    //save all the services and characteristics in the correspondent maps
                    Map<String, BluetoothGattService> deviceServices = new HashMap<>();
                    Map<String, BluetoothGattCharacteristic> deviceCharacteristics = new HashMap<>();
                    for(BluetoothGattService s : services)
                    {
                        deviceServices.put(s.getUuid().toString().toLowerCase(), s);

                        List<BluetoothGattCharacteristic> serviceCharacteristics = s.getCharacteristics();
                        for(BluetoothGattCharacteristic c : serviceCharacteristics)
                            deviceCharacteristics.put(c.getUuid().toString().toLowerCase(), c);
                    }
                    serviceMap.put(deviceAddress, deviceServices);
                    characteristicMap.put(deviceAddress, deviceCharacteristics);

                    emitSignal("service_discovery_success", deviceAddress);
                }

                @Override //Called every time a write with response is performed
                public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status)
                {
                    if(status == BluetoothGatt.GATT_SUCCESS) {
                        Log.i("BluetoothGattCallback", "Wrote to characteristic ".concat(characteristic.getUuid().toString()));
                        emitSignal("characteristic_written", gatt.getDevice().getAddress(), characteristic.getUuid().toString());
                    }
                    else if(status == BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH) {
                        Log.e("BluetoothGattCallback", "Write exceeded connection ATT MTU!");
                        emitSignal("characteristic_write_error","Write exceeded connection ATT MTU!");
                    }
                    else if(status == BluetoothGatt.GATT_WRITE_NOT_PERMITTED) {
                        Log.e("BluetoothGattCallback", "Write not permitted for ".concat(characteristic.getUuid().toString()));
                        emitSignal("characteristic_write_error","Write not permitted for ".concat(characteristic.getUuid().toString()));
                    }
                    else {
                        Log.e("BluetoothGattCallback", "Characteristic write failed for ".concat(characteristic.getUuid().toString()).concat(", error: ").concat(String.valueOf(status)));
                        emitSignal("characteristic_write_error","Characteristic write failed for ".concat(characteristic.getUuid().toString()).concat(", error: ").concat(String.valueOf(status)));
                    }
                }

                @Override //Called every time a read is performed
                public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status)
                {
                    if(status == BluetoothGatt.GATT_SUCCESS)
                    {
                        Log.i("BluetoothGattCallback", "Read characteristic ".concat(characteristic.getUuid().toString()));

                        //send the UUID and the new value to godot
                        emitSignal("characteristic_read", gatt.getDevice().getAddress(), characteristic.getUuid().toString(), characteristic.getValue());
                    }
                    else if(status == BluetoothGatt.GATT_READ_NOT_PERMITTED) {
                        Log.e("BluetoothGattCallback", "Read not permitted for ".concat(characteristic.getUuid().toString()));
                        emitSignal("characteristic_read_error","Read not permitted for ".concat(characteristic.getUuid().toString()));
                    }
                    else {
                        Log.e("BluetoothGattCallback", "Characteristic read failed for ".concat(characteristic.getUuid().toString()).concat(", error: ").concat(String.valueOf(status)));
                        emitSignal("characteristic_read_error","Characteristic read failed for ".concat(characteristic.getUuid().toString()).concat(", error: ").concat(String.valueOf(status)));
                    }
                }

                @Override //Called every time a characteristic we are subscribed to changed its value
                public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic)
                {
                    Log.i("Character changed", "the characteristic: ".concat(characteristic.getUuid().toString()).concat("changed"));

                    //send the UUID and the new value to godot
                    emitSignal("characteristic_changed", gatt.getDevice().getAddress(), characteristic.getUuid().toString(), characteristic.getValue());
                }
            };


    public GodotBLE(Godot godot) { super(godot); }


    @NonNull
    @Override
    public String getPluginName() { return "BLEPlugin"; }

    @NonNull
    @Override
    public List<String> getPluginMethods() //List of methods callable from Godot
    {
        return Arrays.asList(
                "initialize",
                "addScanFilterDeviceName",
                "addScanFilterDeviceAddress",
                "addScanFilterService",
                "resetScanFilters",
                "startScan",
                "stopScan",
                "connectToDeviceByAddress",
                "connectToDeviceByName",
                "disconnect",
                "isConnected",
                "hasService",
                "hasCharacteristic",
                "setCharacteristicNotifications",
                "writeIntCharacteristic",
                "writeByteCharacteristic",
                "writeStringCharacteristic",
                "writeFloatCharacteristic",
                "readCharacteristic",
                "isWritable",
                "isWritableNoResponse",
                "isReadable",
                "isNotifiable",
                "isIndicatable");
    }


    @NonNull
    @Override
    public Set<SignalInfo> getPluginSignals() //List of signals receivable from Godot
    {
        Set<SignalInfo> signals = new ArraySet<>();

        signals.add(new SignalInfo("scan_failed", String.class));
        signals.add(new SignalInfo("device_found", String.class, String.class));
        signals.add(new SignalInfo("device_connected", String.class, String.class));
        signals.add(new SignalInfo("device_disconnected", String.class, String.class));
        signals.add(new SignalInfo("connection_error", String.class));
        signals.add(new SignalInfo("characteristic_read", String.class, String.class, byte[].class));
        signals.add(new SignalInfo("characteristic_read_error", String.class));
        signals.add(new SignalInfo("characteristic_written", String.class, String.class));
        signals.add(new SignalInfo("characteristic_written_error", String.class));
        signals.add(new SignalInfo("characteristic_changed", String.class, String.class, byte[].class));
        signals.add(new SignalInfo("service_discovery_success", String.class));
        signals.add(new SignalInfo( "ble_initialized"));
        signals.add(new SignalInfo( "ble_initialization_error", String.class));

        return signals;
    }


    @Override
    public void onMainActivityResult(int requestCode, int resultCode, Intent data) {
        super.onMainActivityResult(requestCode, resultCode, data);
        String activityResult = " ---------->".concat(Integer.toString(requestCode));
        Log.i("ActivityResult",activityResult);
        if (requestCode == ENABLE_BLUETOOTH_REQUEST_CODE && resultCode != Activity.RESULT_OK)
        {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            activity.startActivityForResult(enableBtIntent, ENABLE_BLUETOOTH_REQUEST_CODE);
        }
    }

    @Override
    public void onMainRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onMainRequestPermissionsResult(requestCode, permissions, grantResults);
        Log.i("RequestPermissionResult","-------------->".concat(Integer.toString(grantResults[0])));

        /*
        if(requestCode== 1001)
        {
            if(grantResults[0]!=Activity.RESULT_OK)
                ActivityCompat.requestPermissions(activity,new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1001);
        }

         */


    }

    /*
     * This method initializes bluetooth, location and permissions
     */
    public void initialize()
    {
        activity = getActivity();

        // Initializes Bluetooth adapter.
        bluetoothManager = (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null)
        {
            bluetoothAdapter = bluetoothManager.getAdapter();
            ActivityCompat.requestPermissions(activity,new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1001);
        }
        else
        {
            Log.e("Bluetooth Manager", "Bluetooth Manager impossible to retrieve");
            emitSignal("ble_initialization_error","Bluetooth Manager impossible to retrieve");
            return;
        }

        // Ensures Bluetooth is available on the device and it is enabled. If not,
        // displays a dialog requesting user permission to enable Bluetooth.
        if (bluetoothAdapter != null && !bluetoothAdapter.isEnabled())
        {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            activity.startActivityForResult(enableBtIntent, ENABLE_BLUETOOTH_REQUEST_CODE);
        }

        // Ensures Location is is enabled. If not, opens Location Settings
        final LocationManager manager = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
        if(!manager.isProviderEnabled(LocationManager.GPS_PROVIDER))
        {
            Intent enableLocation = new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            activity.startActivityForResult(enableLocation, ENABLE_LOCATION_REQUEST_CODE);
            /*AlertDialog.Builder builder = new AlertDialog.Builder();
            builder.setMessage("Your GPS seems to be disabled, do you want to enable it?")
                    .setCancelable(false)
                    .setPositiveButton("Yes", (dialog, id) -> activity.startActivityForResult(new
                            Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS), ENABLE_LOCATION_REQUEST_CODE))
                    .setNegativeButton("No", (dialog, id) -> dialog.cancel());
            AlertDialog alert = builder.create();
            alert.show();*/
        }
        emitSignal("ble_initialized");
    }


    /**
     * It adds the device name as filter for the scanning
     * @param deviceName
     */
    public void addScanFilterDeviceName(String deviceName) { scanFilter = scanFilter.setDeviceName(deviceName); }


    /**
     * It adds the device address as filter for the scanning
     * @param deviceAddress
     */
    public void addScanFilterDeviceAddress(String deviceAddress) { scanFilter = scanFilter.setDeviceAddress(deviceAddress); }


    /**
     * It adds a service UUID as filter for the scanning
     * @param serviceUUID
     */
    public void addScanFilterService(String serviceUUID) { scanFilter = scanFilter.setServiceUuid(ParcelUuid.fromString(serviceUUID)); }


    /**
     * It removes all the filters for the scanning
     */
    public void resetScanFilters() { scanFilter = new ScanFilter.Builder(); }


    /**
     * It starts the scanning
     */
    public void startScan()
    {
        if (bluetoothAdapter == null)
        {
            Log.e("ERROR", "BluetoothAdapter not initialized");
            return;
        }
        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        scanResults = new ArrayList<>();
        bluetoothLeScanner.startScan(Collections.singletonList(scanFilter.build()), scanSettings, leScanCallback);
        isScanning = true;
    }


    /**
     * Stop the scanning
     */
    public void stopScan()
    {
        if(bluetoothLeScanner != null) bluetoothLeScanner.stopScan(leScanCallback);
        isScanning = false;
    }


    /**
     * Connect to a device previously found giving its address
     * @param deviceAddress
     */
    public void connectToDeviceByAddress(String deviceAddress)
    {
        //Search for the device with that address
        for(BluetoothDevice r : scanResults)
            if(r.getAddress().equals(deviceAddress))
            {   // connect
                r.connectGatt(activity, false, gattCallback);
                return;
            }
    }


    /**
     * Connect to a device previously found giving its name
     * @param deviceName
     */
    public void connectToDeviceByName(String deviceName)
    {
        for(BluetoothDevice r: scanResults)
        {   //look for the desired device
            String name = r.getName();
            if(name == null) continue;
            else if(name.equals(deviceName))
            {   //connect
                r.connectGatt(activity, false, gattCallback);
                return;
            }
        }
    }


    /**
     * Disconnect from the device
     */
    public void disconnect(String deviceAddress)
    {
        characteristicMap.remove(deviceAddress);
        serviceMap.remove(deviceAddress);
        bluetoothGatts.get(deviceAddress).disconnect();
    }


    /**
     * @return True if we are currecntly connected to a device, false otherwise
     */
    public boolean isConnected(String deviceAddress)
    {
        return bluetoothGatts.containsKey(deviceAddress);
    }


    /**
     * @param uuid Service to be search in the device we are connected to
     * @return True if the device provides the service with this uuid, False otherwise
     */
    public boolean hasService(String deviceAddress, String uuid)
    {
        Map<String, BluetoothGattService> deviceServices = serviceMap.get(deviceAddress);
        if(deviceServices == null) return false;
        return deviceServices.containsKey(uuid.toLowerCase());
    }


    /**
     * @param uuid Characteristic to be search in the device we are connected to
     * @return True if the device provides the characteristic with this uuid, False otherwise
     */
    public boolean hasCharacteristic(String deviceAddress, String uuid)
    {
        Map<String, BluetoothGattCharacteristic> deviceCharacteristics = characteristicMap.get(deviceAddress);
        if(deviceCharacteristics == null) return false;
        return deviceCharacteristics.containsKey(uuid.toLowerCase());
    }


    /**
     * Enables/Disables the notifications for the given UUID characteristic
     * @param uuid UUID of the characteristic of which enable/disable notifications
     * @param enable if true, notifications are enabled. if false, they are disabled
     * @return True if the operation succeeds, False otherwise
     */
    public boolean setCharacteristicNotifications(String deviceAddress, String uuid, boolean enable)
    {
        Map<String, BluetoothGattCharacteristic> deviceCharacteristics = characteristicMap.get(deviceAddress);
        if(deviceCharacteristics == null) return false;
        return enableNotifications(deviceAddress, deviceCharacteristics.get(uuid.toLowerCase()), enable);
    }


    /**
     * Enables/Disables notifications for the given characteristic setting the value of the
     * CCC descriptor.
     * @param characteristic Characteristic of which we want to enable/disable notifications
     * @param enable if true, notifications are enabled. if false, they are disabled
     * @returnTrue if the operation succeeds, False otherwise
     */
    private boolean enableNotifications(String deviceAddress, BluetoothGattCharacteristic characteristic, boolean enable)
    {
        if(characteristic ==  null) return false;

        BluetoothGatt bluetoothGatt = bluetoothGatts.get(deviceAddress);

        //If possible and enable=True, enable notifications. Otherwise indications
        UUID cccdUuid = UUID.fromString(CCC_DESCRIPTOR_UUID);

        byte[] payload = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;;
        if(isNotifiable(deviceAddress, characteristic.getUuid().toString()))
            if(enable) payload = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
            else if(isIndicatable(deviceAddress, characteristic.getUuid().toString()))
                if(enable) payload = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
                else
                {
                    Log.e("ConnectionManager", characteristic.getUuid().toString().concat(" doesn't support notifications/indications"));
                    return false;
                }

        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(cccdUuid);
        if (bluetoothGatt.setCharacteristicNotification(characteristic, enable) == false)
        {
            Log.e("ConnectionManager", "setCharacteristicNotification failed for ".concat(characteristic.getUuid().toString()));
            return false;
        }

        boolean set = descriptor.setValue(payload);
        boolean written = bluetoothGatt.writeDescriptor(descriptor);

        return set && written;
    }


    /**
     * Change the value of characteristic with the given UUID to "value"
     * @param uuid UUID of the characterisitc we want to change the value of
     * @param value new value
     * @return True on success, false otherwise
     */
    public boolean writeIntCharacteristic(String deviceAddress, String uuid, int value)
    {
        //Check if the given characterisitc exists and can be written
        Map<String, BluetoothGattCharacteristic> deviceCharacteristics = characteristicMap.get(deviceAddress);
        if(deviceCharacteristics == null) return false;
        BluetoothGattCharacteristic characteristic = deviceCharacteristics.get(uuid.toLowerCase());
        if(!checkWritability(deviceAddress, characteristic)) return false;

        //Set the new value and send it
        boolean set = characteristic.setValue(value,BluetoothGattCharacteristic.FORMAT_UINT32,0);
        boolean written = bluetoothGatts.get(deviceAddress).writeCharacteristic(characteristic);

        return set && written;
    }


    /**
     * Change the value of characteristic with the given UUID to "value"
     * @param uuid UUID of the characterisitc we want to change the value of
     * @param value new value. It is an int because Godot does not support bytes. It is treated as an 8 bit int.
     * @return True on success, false otherwise
     */
    public boolean writeByteCharacteristic(String deviceAddress, String uuid, int value)
    {
        Map<String, BluetoothGattCharacteristic> deviceCharacteristics = characteristicMap.get(deviceAddress);
        if(deviceCharacteristics == null) return false;
        BluetoothGattCharacteristic characteristic = deviceCharacteristics.get(uuid.toLowerCase());
        if(!checkWritability(deviceAddress, characteristic)) return false;

        //Format is UINT8 so that the given int is treated as a byte
        boolean set = characteristic.setValue(value,BluetoothGattCharacteristic.FORMAT_UINT8,0);
        boolean written = bluetoothGatts.get(deviceAddress).writeCharacteristic(characteristic);

        return set && written;
    }


    /**
     * Change the value of characteristic with the given UUID to "value"
     * @param uuid UUID of the characterisitc we want to change the value of
     * @param value new value.
     * @return True on success, false otherwise
     */
    public boolean writeStringCharacteristic(String deviceAddress, String uuid, String value)
    {
        Map<String, BluetoothGattCharacteristic> deviceCharacteristics = characteristicMap.get(deviceAddress);
        if(deviceCharacteristics == null) return false;
        BluetoothGattCharacteristic characteristic = deviceCharacteristics.get(uuid.toLowerCase());
        if(!checkWritability(deviceAddress, characteristic)) return false;

        boolean set = characteristic.setValue(value);
        boolean written = bluetoothGatts.get(deviceAddress).writeCharacteristic(characteristic);

        return set && written;
    }


    /**
     * Change the value of characteristic with the given UUID to "value"
     * @param uuid UUID of the characterisitc we want to change the value of
     * @param value new value.
     * @return True on success, false otherwise
     */
    public boolean writeFloatCharacteristic(String deviceAddress, String uuid, float value)
    {
        Map<String, BluetoothGattCharacteristic> deviceCharacteristics = characteristicMap.get(deviceAddress);
        if(deviceCharacteristics == null) return false;
        BluetoothGattCharacteristic characteristic = deviceCharacteristics.get(uuid.toLowerCase());
        if(!checkWritability(deviceAddress, characteristic)) return false;

        // The float cannot be directly sent, so it is converted to a UINT32 (without truncating it)
        //and the conversion will be done by the receiver
        Integer intBits = Float.floatToIntBits(value);
        boolean set = characteristic.setValue(intBits, BluetoothGattCharacteristic.FORMAT_UINT32, 0);
        boolean written = bluetoothGatts.get(deviceAddress).writeCharacteristic(characteristic);

        return set && written;
    }


    /**
     * It checks the writability of the given characteristic and set the proper write type
     */
    private boolean checkWritability(String deviceAddress, BluetoothGattCharacteristic characteristic)
    {
        if(characteristic == null) return false;

        if (bluetoothAdapter == null)
        {
            Log.e("ERROR", "BluetoothAdapter not initialized");
            return false;
        }

        if(isWritable(deviceAddress, characteristic.getUuid().toString()))
        {
            characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            Log.i("Charact property", "PROPERY WRITE");
        }
        else if(isWritableNoResponse(deviceAddress, characteristic.getUuid().toString()))
        {
            characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            Log.i("Charact property", "PROPERY WRITE NO RESPONSE");
        }
        else
        {
            Log.e("ERROR", "Characteristic is not writable");
            return false;
        }

        return true;
    }


    /**
     * Reads from the characteristic with the given UUID.
     * @param uuid
     * @return True on success, False otherwise.
     */
    public boolean readCharacteristic(String deviceAddress, String uuid)
    {
        //Check if the characterisitc exists
        Map<String, BluetoothGattCharacteristic> deviceCharacteristics = characteristicMap.get(deviceAddress);
        if(deviceCharacteristics == null) return false;
        BluetoothGattCharacteristic characteristic = deviceCharacteristics.get(uuid.toLowerCase());
        if(characteristic == null) return false;

        if (bluetoothAdapter == null)
        {
            Log.w("ERROR", "BluetoothAdapter not initialized");
            return false;
        }
        if(!isReadable(deviceAddress, characteristic.getUuid().toString()))
        {
            Log.w("ERROR", "Characteristic is not readable");
            return false;
        }

        return bluetoothGatts.get(deviceAddress).readCharacteristic(characteristic);
        //The actual value is sent as a signal from the callback onCharacteristicRead
    }


    /**
     * Checks if the given characteristic is writable with response
     * @param uuid UUID of the characteristic
     * @return True if the characteristic is writable, false otherwise
     */
    public boolean isWritable(String deviceAddress, String uuid)
    {
        Map<String, BluetoothGattCharacteristic> deviceCharacteristics = characteristicMap.get(deviceAddress);
        if(deviceCharacteristics == null) return false;
        BluetoothGattCharacteristic characteristic = deviceCharacteristics.get(uuid.toLowerCase());
        if(characteristic ==  null) return false;

        return (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) !=0;
    }

    /**
     * Checks if the given characteristic is writable with no response
     * @param uuid UUID of the characteristic
     * @return True if the characteristic is writable, false otherwise
     */
    public boolean isWritableNoResponse(String deviceAddress, String uuid)
    {
        Map<String, BluetoothGattCharacteristic> deviceCharacteristics = characteristicMap.get(deviceAddress);
        if(deviceCharacteristics == null) return false;
        BluetoothGattCharacteristic characteristic = deviceCharacteristics.get(uuid.toLowerCase());
        if(characteristic ==  null) return false;

        return (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
    }


    /**
     * Checks if the given characteristic is readable
     * @param uuid UUID of the characteristic
     * @return True if the characteristic is readable, false otherwise
     */
    public boolean isReadable(String deviceAddress, String uuid)
    {
        Map<String, BluetoothGattCharacteristic> deviceCharacteristics = characteristicMap.get(deviceAddress);
        if(deviceCharacteristics == null) return false;
        BluetoothGattCharacteristic characteristic = deviceCharacteristics.get(uuid.toLowerCase());
        if(characteristic ==  null) return false;

        return (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0;
    }


    /**
     * Check if the given characteristic is enabled to send notifications
     * @param uuid UUID of the characteristic
     * @return True if it can send notifications, false otherwise
     */
    public boolean isNotifiable(String deviceAddress, String uuid)
    {
        Map<String, BluetoothGattCharacteristic> deviceCharacteristics = characteristicMap.get(deviceAddress);
        if(deviceCharacteristics == null) return false;
        BluetoothGattCharacteristic characteristic = deviceCharacteristics.get(uuid.toLowerCase());
        if(characteristic ==  null) return false;

        return (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
    }


    /**
     * Check if the given characteristic is enabled to send indications
     * @param uuid UUID of the characteristic
     * @return True if it can send indications, false otherwise
     */
    public boolean isIndicatable(String deviceAddress, String uuid)
    {
        Map<String, BluetoothGattCharacteristic> deviceCharacteristics = characteristicMap.get(deviceAddress);
        if(deviceCharacteristics == null) return false;
        BluetoothGattCharacteristic characteristic = deviceCharacteristics.get(uuid.toLowerCase());
        if (characteristic == null) return false;

        return (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
    }
}