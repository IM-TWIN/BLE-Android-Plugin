package com.example.bleframework;

import android.Manifest;
import android.app.Activity;
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
import android.net.Uri;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.collection.ArraySet;
import androidx.core.app.ActivityCompat;
import androidx.core.content.FileProvider;

import org.godotengine.godot.Godot;
import org.godotengine.godot.plugin.GodotPlugin;
import org.godotengine.godot.plugin.SignalInfo;
import org.godotengine.godot.plugin.UsedByGodot;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * This class represents the Plugin that will be loaded in Godot in order to exploit
 * BLE functionalities in Android applications. Thanks to this class, an Android device can act
 * as a central and connect to one or multiple peripherals, read/write their characteristics values
 * and enable notifications.
 *
 * @author Massimiliano Schembri
 * @author Francesca Romana Mattei
 *
 * @
 */

public class GodotBLE extends GodotPlugin
{
    // Used to implement a write characteristic queue
    class WriteCharacteristicRequest
    {
        public CharacteristicType type;
        public String deviceAddress;
        public String uuid;
        public String stringValue;
        public int intValue;
        public float floatValue;
    }

    enum CharacteristicType {BYTE, INT, FLOAT, STRING};

    private Activity activity;

    // Codes for bluetooth and location enabling requests
    private final int ENABLE_BLUETOOTH_REQUEST_CODE = 1;
    private final int ENABLE_LOCATION_REQUEST_CODE = 2;

    // UUID of the descriptor used to subscribe to a characteristic notifications
    private final String CCC_DESCRIPTOR_UUID = "00002902-0000-1000-8000-00805F9B34FB";
    //000002902-0000-1000-8000-00805f9b34fb

    // Objects used to control Bluetooth operations on Android
    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;

    // True if a scan is being performed, false otherwise
    private boolean isScanning = false;

    // Mapping <device address, GATT object> in order to be able to communicate with more than one peripheral
    private Map<String, BluetoothGatt> bluetoothGatts = new HashMap<>();

    // Maps to convert an UUID (String) to the corresponding Characterisitc/Service object
    private Map<String, Map<String, BluetoothGattCharacteristic>> characteristicMap = new HashMap<>();
    private Map<String, Map<String, BluetoothGattService>> serviceMap = new HashMap<>();

    // Objects use to set the scanning options
    private ScanFilter.Builder scanFilter = new ScanFilter.Builder();
    private ScanSettings scanSettings = new ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)  // SCAN_MODE_LOW_LATENCY  is recommended for short time scanning at the beginning of the App
            .build();

    // List of devices found during the last scanning
    private List<BluetoothDevice> scanResults;

    // List of write characteristics requests received from the app
    private LinkedList<WriteCharacteristicRequest> writeCharacteristicsQueue = new LinkedList<WriteCharacteristicRequest>();

    // Device scan callback. This object contains all the callbacks necessary to manage the scan process
    private ScanCallback leScanCallback =
            new ScanCallback()
            {
                // This callback is invoked when the scanning process was successful and has some scan results
                @Override
                public void onScanResult(int callbackType, ScanResult result)
                {
                    Log.i("SCANNING", "found device " + result.getDevice().getName() + "with address " + result.getDevice().getAddress());

                    BluetoothDevice device = result.getDevice();
                    if (scanResults.indexOf(device) == -1)
                    {
                        // Send a signal to Godot with name and address of the device found
                        if (result.getDevice().getName() != null)
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
                public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                    String deviceAddress = gatt.getDevice().getAddress();
                    String deviceName = gatt.getDevice().getName();

                    Log.i("BluetoothGattCallback", "---bleplugin:ConnectionStateChanged status=".concat(String.valueOf(status)).concat(" newStatus=").concat(String.valueOf(newState)));

                    //if there was not an error, check if it was a connection or disconnection
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            Log.w("BluetoothGattCallback", "Successfully connected to ".concat(deviceAddress));
                            bluetoothGatts.put(deviceAddress, gatt); //save the instance of the BluetoothGatt for this connection
                            gatt.discoverServices(); //discover services of the device we are connected to
                            //gatt.requestMtu(512);
                            //Log.i("BluetootGattCallback","---bleplugin:mtu_request_512");
                            emitSignal("device_connected", deviceAddress, deviceName); //send a signal to Godot to say that the connection was successfull
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            Log.w("BluetoothGattCallback", "Successfully disconnected from ".concat(deviceAddress));
                            gatt.close();
                            bluetoothGatts.remove(deviceAddress);
                            emitSignal("device_disconnected", deviceAddress, deviceName);//send a signal to Godot to say that the device has been disconnected
                        }
                    } else {
                        Log.w("BluetoothGattCallback", "Error ".concat(String.valueOf(status)).concat(" encountered for ").concat(deviceAddress).concat("! Disconnecting..."));
                        String connectionErrorMessage = "Connection Error ".concat(String.valueOf(status)).concat(" encountered for ").concat(deviceAddress).concat("! Disconnecting...");
                        emitSignal("connection_error", connectionErrorMessage, deviceAddress);
                        gatt.close();
                    }
                }

                @Override
                public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
                    Log.i("BluetoothGattCallback", "---bleplugin:mut_request, mtu set to:".concat(String.valueOf(mtu)));
                    emitSignal("mtu_changed", mtu);
                }

                @Override
                public void onServicesDiscovered(BluetoothGatt gatt, int status)
                {
                    String deviceAddress = gatt.getDevice().getAddress();
                    List<BluetoothGattService> services = gatt.getServices();
                    if (services.isEmpty()) //if no services found, close the connection
                    {
                        Log.i("Service discovery", "Services not found");
                        bluetoothGatts.remove(deviceAddress);
                        gatt.close();
                        return;
                    }

                    //save all the services and characteristics in the correspondent maps
                    Map<String, BluetoothGattService> deviceServices = new HashMap<>();
                    Map<String, BluetoothGattCharacteristic> deviceCharacteristics = new HashMap<>();
                    for (BluetoothGattService s : services) {
                        deviceServices.put(s.getUuid().toString().toLowerCase(), s);

                        List<BluetoothGattCharacteristic> serviceCharacteristics = s.getCharacteristics();
                        for (BluetoothGattCharacteristic c : serviceCharacteristics)
                            deviceCharacteristics.put(c.getUuid().toString().toLowerCase(), c);
                    }
                    serviceMap.put(deviceAddress, deviceServices);
                    characteristicMap.put(deviceAddress, deviceCharacteristics);

                    emitSignal("service_discovery_success", deviceAddress);
                }

                @Override //Called every time a write with response is performed
                public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status)
                {
                    if (status == BluetoothGatt.GATT_SUCCESS)
                    {
                        Log.i("BluetoothGattCallback", "Wrote to characteristic ".concat(characteristic.getUuid().toString()));
                        emitSignal("characteristic_written", gatt.getDevice().getAddress(), characteristic.getUuid().toString());

                        // If characteristic queue is not empty let's write the next characteristic in the queue
                        if (!writeCharacteristicsQueue.isEmpty())
                        {
                            boolean set;
                            WriteCharacteristicRequest nextRequest = writeCharacteristicsQueue.poll();
                            Map<String, BluetoothGattCharacteristic> deviceCharacteristics = characteristicMap.get(nextRequest.deviceAddress);
                            BluetoothGattCharacteristic nextCharacteristic = deviceCharacteristics.get(nextRequest.uuid.toLowerCase());
                            switch (nextRequest.type) {
                                case INT:
                                    set = characteristic.setValue(nextRequest.intValue, BluetoothGattCharacteristic.FORMAT_UINT32, 0);
                                    break;
                                case FLOAT:
                                    // The float cannot be directly sent, so it is converted to a UINT32 (without truncating it)
                                    //and the conversion will be done by the receiver
                                    Integer intBits = Float.floatToIntBits(nextRequest.floatValue);
                                    set = characteristic.setValue(intBits, BluetoothGattCharacteristic.FORMAT_UINT32, 0);
                                    break;
                                case BYTE:
                                    set = characteristic.setValue(nextRequest.intValue, BluetoothGattCharacteristic.FORMAT_UINT8, 0);
                                    break;
                                case STRING:
                                    set = characteristic.setValue(nextRequest.stringValue);
                                    break;
                            }
                            // write the characterisitc
                            boolean written = bluetoothGatts.get(nextRequest.deviceAddress).writeCharacteristic(characteristic);
                            // TODO: implement a Godot boolean signal to notify set&written
                        }
                    } else if (status == BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH)
                    {
                        Log.e("BluetoothGattCallback", "Write exceeded connection ATT MTU!");
                        emitSignal("characteristic_write_error", "Write exceeded connection ATT MTU!");
                    } else if (status == BluetoothGatt.GATT_WRITE_NOT_PERMITTED)
                    {
                        Log.e("BluetoothGattCallback", "Write not permitted for ".concat(characteristic.getUuid().toString()));
                        emitSignal("characteristic_write_error", "Write not permitted for ".concat(characteristic.getUuid().toString()));
                    } else {
                        Log.e("BluetoothGattCallback", "Characteristic write failed for ".concat(characteristic.getUuid().toString()).concat(", error: ").concat(String.valueOf(status)));
                        emitSignal("characteristic_write_error", "Characteristic write failed for ".concat(characteristic.getUuid().toString()).concat(", error: ").concat(String.valueOf(status)));
                    }
                }

                @Override //Called every time a read is performed
                public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.i("BluetoothGattCallback", "Read characteristic ".concat(characteristic.getUuid().toString()));

                        //send the UUID and the new value to godot
                        emitSignal("characteristic_read", gatt.getDevice().getAddress(), characteristic.getUuid().toString(), characteristic.getValue());
                    } else if (status == BluetoothGatt.GATT_READ_NOT_PERMITTED) {
                        Log.e("BluetoothGattCallback", "Read not permitted for ".concat(characteristic.getUuid().toString()));
                        emitSignal("characteristic_read_error", "Read not permitted for ".concat(characteristic.getUuid().toString()));
                    } else {
                        Log.e("BluetoothGattCallback", "Characteristic read failed for ".concat(characteristic.getUuid().toString()).concat(", error: ").concat(String.valueOf(status)));
                        emitSignal("characteristic_read_error", "Characteristic read failed for ".concat(characteristic.getUuid().toString()).concat(", error: ").concat(String.valueOf(status)));
                    }
                }

                @Override
                //Called every time a characteristic we are subscribed to changed its value
                public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                    Log.i("Character changed", "the characteristic: ".concat(characteristic.getUuid().toString()).concat("changed"));

                    //send the UUID and the new value to godot
                    emitSignal("characteristic_changed", gatt.getDevice().getAddress(), characteristic.getUuid().toString(), characteristic.getValue());
                }
            };

    
    public GodotBLE(Godot godot) {
        super(godot);

    }

    @NonNull
    @Override
    public String getPluginName() {
        return "BLEPlugin";
    }


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
                "requestMtu",
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
        signals.add(new SignalInfo("mtu_changed", Integer.class));
        signals.add(new SignalInfo("connection_error", String.class, String.class));
        signals.add(new SignalInfo("characteristic_read", String.class, String.class, byte[].class));
        signals.add(new SignalInfo("characteristic_read_error", String.class));
        signals.add(new SignalInfo("characteristic_written", String.class, String.class));
        signals.add(new SignalInfo("characteristic_written_error", String.class));
        signals.add(new SignalInfo("characteristic_changed", String.class, String.class, byte[].class));
        signals.add(new SignalInfo("service_discovery_success", String.class));
        signals.add(new SignalInfo("ble_initialized"));
        signals.add(new SignalInfo("ble_initialization_error", String.class));

        return signals;
    }

    // Share functions
    @UsedByGodot
    public void shareText(String title, String subject, String text)
    {
        Log.d("ShareText", "shareText called");
        Intent sharingIntent = new Intent(android.content.Intent.ACTION_SEND);
        sharingIntent.setType("text/plain");
        sharingIntent.putExtra(android.content.Intent.EXTRA_SUBJECT, subject);
        sharingIntent.putExtra(android.content.Intent.EXTRA_TEXT, text);
        activity.startActivity(Intent.createChooser(sharingIntent, title));
    }

    @UsedByGodot
    public void sharePic(String path, String title, String subject, String text) {
        Log.d("SharePic", "sharePic called");

        File f = new File(path);

        Uri uri;
        try {
            uri = FileProvider.getUriForFile(activity, activity.getPackageName(), f);
        } catch (IllegalArgumentException e) {
            Log.e("SharePic", "The selected file can't be shared: " + path);
            return;
        }

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("image/*");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, subject);
        shareIntent.putExtra(Intent.EXTRA_TEXT, text);
        shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(Intent.createChooser(shareIntent, title));
    }

    @UsedByGodot
    public void shareTextFile(String path, String title, String subject, String text) {
        Log.d("ShareTextFile", "sharePic called  filepath=" + path);

        File f = new File(path);

        Uri uri;
        try {
            uri = FileProvider.getUriForFile(activity, activity.getPackageName(), f);
        } catch (IllegalArgumentException e) {
            Log.e("ShareTextFile", "The selected file can't be shared: " + path);
            return;
        }

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/*");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, subject);
        shareIntent.putExtra(Intent.EXTRA_TEXT, text);
        shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(Intent.createChooser(shareIntent, title));
    }


    @Override
    public void onMainActivityResult(int requestCode, int resultCode, Intent data)
    {
        super.onMainActivityResult(requestCode, resultCode, data);
        String activityResult = " ---------->".concat(Integer.toString(requestCode));
        Log.i("ActivityResult", activityResult);

        if (requestCode == ENABLE_BLUETOOTH_REQUEST_CODE && resultCode != Activity.RESULT_OK)
        {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            activity.startActivityForResult(enableBtIntent, ENABLE_BLUETOOTH_REQUEST_CODE);
        }

    }

    @Override
    public void onMainRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onMainRequestPermissionsResult(requestCode, permissions, grantResults);
        Log.i("RequestPermissionResult", "-------------->".concat(Integer.toString(grantResults[0])));

        // keep asking for required permission until granted
        if (requestCode == 1001) {
            if (grantResults[0] != Activity.RESULT_OK)
                ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1001);
        }
    }

    /*
     * This method initializes bluetooth, location and permissions
     */
    public void initialize() {
        activity = getActivity();

        // Initializes Bluetooth adapter.
        bluetoothManager = (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null)
        {
            bluetoothAdapter = bluetoothManager.getAdapter();
            ActivityCompat.requestPermissions(activity, new String[]{   Manifest.permission.ACCESS_FINE_LOCATION,
                                                                        Manifest.permission.BLUETOOTH_SCAN,
                                                                        Manifest.permission.BLUETOOTH_CONNECT},
                                                                        1001);
        } else {
            Log.e("Bluetooth Manager", "Bluetooth Manager impossible to retrieve");
            emitSignal("ble_initialization_error", "Bluetooth Manager impossible to retrieve");
            return;
        }

        // Ensures Bluetooth is available on the device and it is enabled. If not,
        // displays a dialog requesting user permission to enable Bluetooth.
        if (bluetoothAdapter != null && !bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            activity.startActivityForResult(enableBtIntent, ENABLE_BLUETOOTH_REQUEST_CODE);
        }

        // Ensures Location is enabled. If not, opens Location Settings
        final LocationManager manager = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Intent enableLocation = new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            activity.startActivityForResult(enableLocation, ENABLE_LOCATION_REQUEST_CODE);
        }

        emitSignal("ble_initialized");
    }


    /**
     * It adds the device name as filter for the scanning
     * @param deviceName
     */
    public void addScanFilterDeviceName(String deviceName) {
        scanFilter = scanFilter.setDeviceName(deviceName);
    }


    /**
     * It adds the device address as filter for the scanning
     * @param deviceAddress
     */
    public void addScanFilterDeviceAddress(String deviceAddress) {
        scanFilter = scanFilter.setDeviceAddress(deviceAddress);
    }


    /**
     * It adds a service UUID as filter for the scanning
     * @param serviceUUID
     */
    public void addScanFilterService(String serviceUUID) {
        scanFilter = scanFilter.setServiceUuid(ParcelUuid.fromString(serviceUUID));
    }


    /**
     * It removes all the filters for the scanning
     */
    public void resetScanFilters() {
        scanFilter = new ScanFilter.Builder();
    }


    /**
     * It starts the scanning
     */
    public void startScan()
    {
        if (bluetoothAdapter == null) {
            Log.e("ERROR", "BluetoothAdapter not initialized");
            return;
        }
        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        scanResults = new ArrayList<>();
        Log.i("SCANNING","Start Scanning");

        bluetoothLeScanner.startScan(Collections.singletonList(scanFilter.build()), scanSettings, leScanCallback);

        isScanning = true;
    }


    /**
     * Stop the scanning
     */
    public void stopScan()
    {
        if (bluetoothLeScanner != null)
            bluetoothLeScanner.stopScan(leScanCallback);

        isScanning = false;
    }


    /**
     * Connect to a device previously found giving its address
     * @param deviceAddress
     */
    public void connectToDeviceByAddress(String deviceAddress)
    {
        //Search for the device with that address
        for (BluetoothDevice r : scanResults)
            if (r.getAddress().equals(deviceAddress)) {   // connect
                r.connectGatt(activity, false, gattCallback, 2);
                return;
            }
    }


    /**
     * Connect to a device previously found giving its name
     * @param deviceName
     */
    public void connectToDeviceByName(String deviceName)
    {
        for (BluetoothDevice r : scanResults)
        {   //look for the desired device
            String name = r.getName();
            if (name == null) continue;
            else if (name.equals(deviceName)) {   //connect
                r.connectGatt(activity, false, gattCallback, 2);
                return;
            }
        }
    }

    /**
     * Request a specific mtu passed as parameter
     * @param deviceAddress
     */
    public void requestMtu(String deviceAddress) {
        bluetoothGatts.get(deviceAddress).requestMtu(512);
    }

    /**
     * Disconnect from the device
     */
    public void disconnect(String deviceAddress)
    {
        characteristicMap.remove(deviceAddress);
        serviceMap.remove(deviceAddress);
        bluetoothGatts.get(deviceAddress).disconnect();
        bluetoothGatts.get(deviceAddress).close();
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
        if(deviceCharacteristics == null)
            return false;
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
        if(characteristic ==  null)
            return false;

        BluetoothGatt bluetoothGatt = bluetoothGatts.get(deviceAddress);

        //If possible and enable=True, enable notifications. Otherwise indications
        UUID cccdUuid = UUID.fromString(CCC_DESCRIPTOR_UUID);

        byte[] payload = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;;

        if(isNotifiable(deviceAddress, characteristic.getUuid().toString()))
        {
            Log.w("ConnectionManager", " isNotifiable");
            if (enable)
                payload = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
            else if (isIndicatable(deviceAddress, characteristic.getUuid().toString()))
                if (enable) payload = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
                else {
                    Log.e("ConnectionManager", characteristic.getUuid().toString().concat(" doesn't support notifications/indications"));
                    return false;
                }
        }
        else {
            Log.w("ConnetionManager", characteristic.getUuid().toString().concat(" is not notifiable"));
            return false;
        }

        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(cccdUuid);

        if (bluetoothGatt.setCharacteristicNotification(characteristic, enable) == false)
        {
            Log.e("ConnectionManager", "setCharacteristicNotification failed for ".concat(characteristic.getUuid().toString()));
            return false;
        }

        if (descriptor == null)
        {
            Log.w("ConnectionManager", " Descriptor is null for "+characteristic.toString());
            return false;
        }
        else{
            boolean set = descriptor.setValue(payload);
            boolean written = bluetoothGatt.writeDescriptor(descriptor);

            return set && written;
        }
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
        if(deviceCharacteristics == null)
            return false;
        BluetoothGattCharacteristic characteristic = deviceCharacteristics.get(uuid.toLowerCase());
        if(!checkWritability(deviceAddress, characteristic))
            return false;

        boolean set=false;
        boolean written=false;
        if(writeCharacteristicsQueue.isEmpty())
        {
            //Set the new value and send it
            set = characteristic.setValue(value, BluetoothGattCharacteristic.FORMAT_UINT32, 0);
            written = bluetoothGatts.get(deviceAddress).writeCharacteristic(characteristic);
        }
        else{
            WriteCharacteristicRequest newCharacteristicRequest = new WriteCharacteristicRequest();
            newCharacteristicRequest.type = CharacteristicType.INT;
            newCharacteristicRequest.deviceAddress = deviceAddress;
            newCharacteristicRequest.uuid = uuid;
            newCharacteristicRequest.intValue = value;
            writeCharacteristicsQueue.add(newCharacteristicRequest);
            set = false;
            written = false;
        }

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
        if(deviceCharacteristics == null)
            return false;
        BluetoothGattCharacteristic characteristic = deviceCharacteristics.get(uuid.toLowerCase());
        if(!checkWritability(deviceAddress, characteristic))
            return false;

        boolean set= false;
        boolean written = false;
        if(writeCharacteristicsQueue.isEmpty())
        {
            //Format is UINT8 so that the given int is treated as a byte
            set = characteristic.setValue(value, BluetoothGattCharacteristic.FORMAT_UINT8, 0);
            written = bluetoothGatts.get(deviceAddress).writeCharacteristic(characteristic);
        }
        else{
            WriteCharacteristicRequest newCharacteristicRequest = new WriteCharacteristicRequest();
            newCharacteristicRequest.type = CharacteristicType.BYTE;
            newCharacteristicRequest.deviceAddress = deviceAddress;
            newCharacteristicRequest.uuid = uuid;
            newCharacteristicRequest.intValue = value;
            writeCharacteristicsQueue.add(newCharacteristicRequest);
            set = false;
            written = false;
        }

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

        boolean set=false;
        boolean written=false;
        if(writeCharacteristicsQueue.isEmpty())
        {
            set = characteristic.setValue(value);
            written = bluetoothGatts.get(deviceAddress).writeCharacteristic(characteristic);
        }
        else
        {
            WriteCharacteristicRequest newCharacteristicRequest = new WriteCharacteristicRequest();
            newCharacteristicRequest.type = CharacteristicType.STRING;
            newCharacteristicRequest.deviceAddress = deviceAddress;
            newCharacteristicRequest.uuid = uuid;
            newCharacteristicRequest.stringValue = value;
            writeCharacteristicsQueue.add(newCharacteristicRequest);
            set = false;
            written = false;
        }

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

        boolean set=false;
        boolean written=false;
        if(writeCharacteristicsQueue.isEmpty())
        {
            // The float cannot be directly sent, so it is converted to a UINT32 (without truncating it)
            //and the conversion will be done by the receiver
            Integer intBits = Float.floatToIntBits(value);
            set = characteristic.setValue(intBits, BluetoothGattCharacteristic.FORMAT_UINT32, 0);

            written = bluetoothGatts.get(deviceAddress).writeCharacteristic(characteristic);
        }
        else{
            WriteCharacteristicRequest newCharacteristicRequest = new WriteCharacteristicRequest();
            newCharacteristicRequest.type = CharacteristicType.FLOAT;
            newCharacteristicRequest.deviceAddress = deviceAddress;
            newCharacteristicRequest.uuid = uuid;
            newCharacteristicRequest.floatValue = value;
            writeCharacteristicsQueue.add(newCharacteristicRequest);
            set = false;
            written = false;
        }

        return set && written;
    }


    /**
     * It checks the writability of the given characteristic and set the proper write type
     */
    private boolean checkWritability(String deviceAddress, BluetoothGattCharacteristic characteristic)
    {
        if(characteristic == null)
            return false;

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
        if(deviceCharacteristics == null)
            return false;
        BluetoothGattCharacteristic characteristic = deviceCharacteristics.get(uuid.toLowerCase());
        if(characteristic == null)
            return false;

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
        if(deviceCharacteristics == null)
            return false;
        BluetoothGattCharacteristic characteristic = deviceCharacteristics.get(uuid.toLowerCase());
        if(characteristic ==  null)
            return false;

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
        if(deviceCharacteristics == null)
            return false;
        BluetoothGattCharacteristic characteristic = deviceCharacteristics.get(uuid.toLowerCase());
        if(characteristic ==  null)
            return false;

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
        if(deviceCharacteristics == null)
            return false;
        BluetoothGattCharacteristic characteristic = deviceCharacteristics.get(uuid.toLowerCase());
        if(characteristic ==  null)
            return false;

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
        if(deviceCharacteristics == null)
            return false;
        BluetoothGattCharacteristic characteristic = deviceCharacteristics.get(uuid.toLowerCase());
        if(characteristic ==  null)
            return false;

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