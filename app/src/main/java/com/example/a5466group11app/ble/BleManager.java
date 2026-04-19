package com.example.a5466group11app.ble;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.ActivityCompat;

import com.example.a5466group11app.model.BleDeviceItem;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

public class BleManager {

    public interface BleManagerListener {
        void onLog(String message);
        void onConnectionStateChanged(boolean connected);
        void onScanStarted();
        void onScanFinished(List<BleDeviceItem> devices);
        void onMessageReceived(String message);
        void onBleError(String message);
    }

    private static final UUID SERVICE_UUID =
            UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private static final UUID CHARACTERISTIC_UUID =
            UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final int MAX_RECONNECT_ATTEMPTS = 3;

    private final Context context;
    private final BleManagerListener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private BluetoothGattCharacteristic notifyCharacteristic;
    private BluetoothDevice lastConnectedDevice;

    private boolean isScanning = false;
    private boolean isConnected = false;
    private boolean userInitiatedDisconnect = false;
    private int reconnectAttempt = 0;

    private final LinkedHashMap<String, BleDeviceItem> scannedDevices = new LinkedHashMap<>();

    public BleManager(Context context, BleManagerListener listener) {
        this.context = context;
        this.listener = listener;

        BluetoothManager bluetoothManager = context.getSystemService(BluetoothManager.class);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        }
    }

    private final Runnable stopScanRunnable = new Runnable() {
        @Override
        public void run() {
            if (isScanning) {
                stopScanInternal(true);
            }
        }
    };

    private final Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            if (!userInitiatedDisconnect && lastConnectedDevice != null && reconnectAttempt <= MAX_RECONNECT_ATTEMPTS) {
                log("Reconnect attempt " + reconnectAttempt + " of " + MAX_RECONNECT_ATTEMPTS);
                connectInternal(lastConnectedDevice);
            }
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                    error("Missing BLUETOOTH_CONNECT permission");
                    return;
                }
            }

            String name = device.getName();
            String address = device.getAddress();

            if (name == null || name.trim().isEmpty()) {
                name = "Unknown Device";
            }

            if (!scannedDevices.containsKey(address)) {
                scannedDevices.put(address, new BleDeviceItem(name, address, device));
                log("Discovered: " + name + " (" + address + ")");
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            error("Scan failed: " + errorCode);
            stopScanInternal(true);
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                isConnected = true;
                reconnectAttempt = 0;
                log("GATT connected");

                if (listener != null) {
                    listener.onConnectionStateChanged(true);
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                                != PackageManager.PERMISSION_GRANTED) {
                    error("Missing BLUETOOTH_CONNECT permission");
                    return;
                }

                gatt.discoverServices();
                return;
            }

            if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                isConnected = false;
                log("GATT disconnected");

                if (listener != null) {
                    listener.onConnectionStateChanged(false);
                }

                clearGattObjects();

                if (bluetoothGatt != null) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                                    == PackageManager.PERMISSION_GRANTED) {
                        bluetoothGatt.close();
                    }
                    bluetoothGatt = null;
                }

                if (!userInitiatedDisconnect && lastConnectedDevice != null) {
                    if (reconnectAttempt < MAX_RECONNECT_ATTEMPTS) {
                        reconnectAttempt++;
                        long delayMs = reconnectAttempt * 1500L;
                        log("Scheduling reconnect in " + delayMs + " ms");
                        handler.postDelayed(reconnectRunnable, delayMs);
                    } else {
                        log("Reconnect limit reached");
                    }
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                error("Service discovery failed: " + status);
                return;
            }

            BluetoothGattService service = gatt.getService(SERVICE_UUID);
            if (service == null) {
                error("Service FFE0 not found");
                return;
            }

            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                if (CHARACTERISTIC_UUID.equals(characteristic.getUuid())) {
                    int properties = characteristic.getProperties();

                    if ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                            (properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                        writeCharacteristic = characteristic;
                    }

                    if ((properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                        notifyCharacteristic = characteristic;
                    }
                }
            }

            if (writeCharacteristic == null) {
                error("Write characteristic not found");
            } else {
                log("Write characteristic ready");
            }

            if (notifyCharacteristic == null) {
                error("Notify characteristic not found");
            } else {
                log("Notify characteristic ready");
                enableNotifications(gatt, notifyCharacteristic);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] data = characteristic.getValue();
            if (data == null) {
                return;
            }

            String message = new String(data, StandardCharsets.UTF_8);
            log("Receive: " + message.replace("\n", "\\n"));

            if (listener != null) {
                listener.onMessageReceived(message);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("Write success");
            } else {
                error("Write failed: " + status);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("Notifications enabled");
            } else {
                error("Enable notifications failed: " + status);
            }
        }
    };

    public boolean isBluetoothSupported() {
        return bluetoothAdapter != null;
    }

    public boolean isBluetoothEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    public boolean isConnected() {
        return isConnected;
    }

    public void startScan() {
        if (bluetoothAdapter == null) {
            error("Bluetooth not supported");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                        != PackageManager.PERMISSION_GRANTED) {
            error("Missing BLUETOOTH_SCAN permission");
            return;
        }

        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bluetoothLeScanner == null) {
            error("BLE scanner unavailable");
            return;
        }

        scannedDevices.clear();
        userInitiatedDisconnect = false;

        log("Scanning for BLE devices");
        isScanning = true;

        if (listener != null) {
            listener.onScanStarted();
        }

        bluetoothLeScanner.startScan(scanCallback);
        handler.postDelayed(stopScanRunnable, 10000);
    }

    public void stopScan() {
        stopScanInternal(true);
    }

    public void connect(BluetoothDevice device) {
        userInitiatedDisconnect = false;
        reconnectAttempt = 0;
        lastConnectedDevice = device;
        handler.removeCallbacks(reconnectRunnable);
        connectInternal(device);
    }

    public void disconnect() {
        userInitiatedDisconnect = true;
        reconnectAttempt = 0;
        handler.removeCallbacks(reconnectRunnable);
        stopScanInternal(false);

        if (bluetoothGatt != null) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                            == PackageManager.PERMISSION_GRANTED) {
                bluetoothGatt.disconnect();
            } else {
                error("Missing BLUETOOTH_CONNECT permission");
            }
        }
    }

    public boolean sendCommand(String command) {
        if (!isConnected || bluetoothGatt == null || writeCharacteristic == null) {
            error("BLE not ready");
            return false;
        }

        log("Send: " + command.replace("\n", "\\n"));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            error("Missing BLUETOOTH_CONNECT permission");
            return false;
        }

        writeCharacteristic.setValue(command.getBytes(StandardCharsets.UTF_8));

        if ((writeCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            writeCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        } else {
            writeCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        }

        boolean started = bluetoothGatt.writeCharacteristic(writeCharacteristic);
        if (!started) {
            error("Write could not start");
        }

        return started;
    }

    public void release() {
        userInitiatedDisconnect = true;
        reconnectAttempt = 0;
        handler.removeCallbacks(stopScanRunnable);
        handler.removeCallbacks(reconnectRunnable);
        stopScanInternal(false);

        if (bluetoothGatt != null) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                            == PackageManager.PERMISSION_GRANTED) {
                bluetoothGatt.close();
            }
            bluetoothGatt = null;
        }

        clearGattObjects();
        isConnected = false;
    }

    private void stopScanInternal(boolean notifyFinished) {
        if (bluetoothLeScanner != null) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                            == PackageManager.PERMISSION_GRANTED) {
                try {
                    bluetoothLeScanner.stopScan(scanCallback);
                } catch (Exception ignored) {
                }
            }
        }

        isScanning = false;
        handler.removeCallbacks(stopScanRunnable);

        if (notifyFinished && listener != null) {
            List<BleDeviceItem> devices = new ArrayList<>(scannedDevices.values());
            listener.onScanFinished(devices);
        }
    }

    private void connectInternal(BluetoothDevice device) {
        log("Connecting to " + device.getAddress());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            error("Missing BLUETOOTH_CONNECT permission");
            return;
        }

        if (bluetoothGatt != null) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                            == PackageManager.PERMISSION_GRANTED) {
                bluetoothGatt.close();
            }
            bluetoothGatt = null;
        }

        clearGattObjects();
        bluetoothGatt = device.connectGatt(context, false, gattCallback);
    }

    private void enableNotifications(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            error("Missing BLUETOOTH_CONNECT permission");
            return;
        }

        boolean notificationSet = gatt.setCharacteristicNotification(characteristic, true);
        log("setCharacteristicNotification: " + notificationSet);

        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD_UUID);
        if (descriptor != null) {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            gatt.writeDescriptor(descriptor);
        } else {
            error("CCCD descriptor not found");
        }
    }

    private void clearGattObjects() {
        writeCharacteristic = null;
        notifyCharacteristic = null;
    }

    private void log(String message) {
        if (listener != null) {
            listener.onLog(message);
        }
    }

    private void error(String message) {
        if (listener != null) {
            listener.onLog(message);
            listener.onBleError(message);
        }
    }
}