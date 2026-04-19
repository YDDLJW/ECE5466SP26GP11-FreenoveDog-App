package com.example.a5466group11app;

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
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSIONS = 1002;

    private static final String DEVICE_NAME_PREFIX = "Freenove-Dog-";

    private static final UUID SERVICE_UUID =
            UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private static final UUID CHARACTERISTIC_UUID =
            UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final int DANCE_SAY_HELLO = 0;
    private static final int DANCE_PUSH_UP = 1;
    private static final int DANCE_STRETCH_SELF = 2;
    private static final int DANCE_TURN_AROUND = 3;
    private static final int DANCE_SIT_DOWN = 4;
    private static final int DANCE_DANCING = 5;

    private static final long MOVE_REPEAT_INTERVAL_MS = 250;

    private TextView tvConnectionStatus;
    private TextView tvLog;
    private Button btnConnectToggle;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private BluetoothGattCharacteristic notifyCharacteristic;

    private boolean isScanning = false;
    private boolean isBluetoothConnected = false;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<Intent> enableBluetoothLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
                    appendLog("Bluetooth enabled");
                    if (hasRequiredPermissions()) {
                        startScan();
                    } else {
                        requestRequiredPermissions();
                    }
                } else {
                    appendLog("Bluetooth not enabled");
                }
            });

    private final Runnable stopScanRunnable = new Runnable() {
        @Override
        public void run() {
            if (isScanning) {
                stopScan();
                appendLog("Scan timeout");
                runOnUiThread(() -> {
                    if (!isBluetoothConnected) {
                        Toast.makeText(MainActivity.this, "No Freenove dog found", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }
    };

    private final Runnable moveRepeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (activeMoveCommand != null) {
                sendCommand(activeMoveCommand);
                handler.postDelayed(this, MOVE_REPEAT_INTERVAL_MS);
            }
        }
    };

    private String activeMoveCommand = null;

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = null;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                    return;
                }
            }

            name = device.getName();

            if (name != null && name.startsWith(DEVICE_NAME_PREFIX)) {
                appendLog("Found device: " + name);
                stopScan();
                connectToDevice(device);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            appendLog("Scan failed: " + errorCode);
            runOnUiThread(() ->
                    Toast.makeText(MainActivity.this, "BLE scan failed: " + errorCode, Toast.LENGTH_SHORT).show()
            );
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                appendLog("GATT connected");
                runOnUiThread(() -> {
                    isBluetoothConnected = true;
                    updateConnectionStatus();
                    btnConnectToggle.setText("Disconnect");
                });

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT)
                                != PackageManager.PERMISSION_GRANTED) {
                    return;
                }

                gatt.discoverServices();

            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                appendLog("GATT disconnected");
                runOnUiThread(() -> {
                    isBluetoothConnected = false;
                    updateConnectionStatus();
                    btnConnectToggle.setText("Connect");
                });

                stopContinuousMove();
                clearGattObjects();

                if (bluetoothGatt != null) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                            ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT)
                                    == PackageManager.PERMISSION_GRANTED) {
                        bluetoothGatt.close();
                    }
                    bluetoothGatt = null;
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                appendLog("Service discovery failed: " + status);
                return;
            }

            BluetoothGattService service = gatt.getService(SERVICE_UUID);
            if (service == null) {
                appendLog("Service FFE0 not found");
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
                appendLog("Write characteristic not found");
            } else {
                appendLog("Write characteristic ready");
            }

            if (notifyCharacteristic == null) {
                appendLog("Notify characteristic not found");
            } else {
                appendLog("Notify characteristic ready");
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
            appendLog("Receive: " + message.replace("\n", "\\n"));
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                appendLog("Write success");
            } else {
                appendLog("Write failed: " + status);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                appendLog("Notifications enabled");
            } else {
                appendLog("Enable notifications failed: " + status);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            v.setPadding(
                    insets.getInsets(WindowInsetsCompat.Type.systemBars()).left,
                    insets.getInsets(WindowInsetsCompat.Type.systemBars()).top,
                    insets.getInsets(WindowInsetsCompat.Type.systemBars()).right,
                    insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            );
            return insets;
        });

        BluetoothManager bluetoothManager = getSystemService(BluetoothManager.class);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        }

        tvConnectionStatus = findViewById(R.id.tvConnectionStatus);
        tvLog = findViewById(R.id.tvLog);
        btnConnectToggle = findViewById(R.id.btnConnectToggle);

        Button btnVerify = findViewById(R.id.btnVerify);
        Button btnStandToggle = findViewById(R.id.btnStandToggle);
        Button btnStandUp = findViewById(R.id.btnStandUp);
        Button btnLieDown = findViewById(R.id.btnLieDown);

        Button btnForward = findViewById(R.id.btnForward);
        Button btnBackward = findViewById(R.id.btnBackward);
        Button btnTurnLeft = findViewById(R.id.btnTurnLeft);
        Button btnTurnRight = findViewById(R.id.btnTurnRight);
        Button btnStop = findViewById(R.id.btnStop);

        Button btnTwistLeft = findViewById(R.id.btnTwistLeft);
        Button btnTwistRight = findViewById(R.id.btnTwistRight);

        Button btnDanceHello = findViewById(R.id.btnDanceHello);
        Button btnDancePushUp = findViewById(R.id.btnDancePushUp);
        Button btnDanceStretch = findViewById(R.id.btnDanceStretch);
        Button btnDanceTurnAround = findViewById(R.id.btnDanceTurnAround);
        Button btnDanceSitDown = findViewById(R.id.btnDanceSitDown);
        Button btnDanceDancing = findViewById(R.id.btnDanceDancing);

        Button btnClearLog = findViewById(R.id.btnClearLog);

        updateConnectionStatus();

        btnConnectToggle.setOnClickListener(v -> {
            if (isBluetoothConnected) {
                disconnectGatt();
            } else {
                startBleFlow();
            }
        });

        btnVerify.setOnClickListener(v -> sendCommand("W#4#FREENOVE#\n"));
        btnStandToggle.setOnClickListener(v -> sendCommand("A#0#\n"));
        btnStandUp.setOnClickListener(v -> sendCommand("A#1#\n"));
        btnLieDown.setOnClickListener(v -> sendCommand("A#2#\n"));

        setContinuousMoveTouchListener(btnForward, buildMoveCommand(20, 0, 0, 5));
        setContinuousMoveTouchListener(btnBackward, buildMoveCommand(-20, 0, 0, 5));
        setContinuousMoveTouchListener(btnTurnLeft, buildMoveCommand(0, 0, -20, 5));
        setContinuousMoveTouchListener(btnTurnRight, buildMoveCommand(0, 0, 20, 5));

        btnStop.setOnClickListener(v -> {
            stopContinuousMove();
            sendCommand(buildMoveCommand(0, 0, 0, 0));
        });

        btnTwistLeft.setOnClickListener(v -> sendCommand(buildTwistCommand(-15, 0, 0)));
        btnTwistRight.setOnClickListener(v -> sendCommand(buildTwistCommand(15, 0, 0)));

        btnDanceHello.setOnClickListener(v -> sendCommand(buildDanceCommand(DANCE_SAY_HELLO)));
        btnDancePushUp.setOnClickListener(v -> sendCommand(buildDanceCommand(DANCE_PUSH_UP)));
        btnDanceStretch.setOnClickListener(v -> sendCommand(buildDanceCommand(DANCE_STRETCH_SELF)));
        btnDanceTurnAround.setOnClickListener(v -> sendCommand(buildDanceCommand(DANCE_TURN_AROUND)));
        btnDanceSitDown.setOnClickListener(v -> sendCommand(buildDanceCommand(DANCE_SIT_DOWN)));
        btnDanceDancing.setOnClickListener(v -> sendCommand(buildDanceCommand(DANCE_DANCING)));

        btnClearLog.setOnClickListener(v -> tvLog.setText("Ready...\n"));
    }

    private void setContinuousMoveTouchListener(View button, String command) {
        button.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startContinuousMove(command);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    stopContinuousMove();
                    sendCommand(buildMoveCommand(0, 0, 0, 0));
                    return true;
                default:
                    return false;
            }
        });
    }

    private void startContinuousMove(String command) {
        if (!isBluetoothConnected || bluetoothGatt == null || writeCharacteristic == null) {
            Toast.makeText(this, "BLE not ready", Toast.LENGTH_SHORT).show();
            appendLog("Blocked continuous move: " + sanitizeCommand(command));
            return;
        }

        if (command.equals(activeMoveCommand)) {
            return;
        }

        stopContinuousMove();
        activeMoveCommand = command;
        sendCommand(activeMoveCommand);
        handler.postDelayed(moveRepeatRunnable, MOVE_REPEAT_INTERVAL_MS);
    }

    private void stopContinuousMove() {
        activeMoveCommand = null;
        handler.removeCallbacks(moveRepeatRunnable);
    }

    private void startBleFlow() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_SHORT).show();
            appendLog("Bluetooth not supported");
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            enableBluetoothLauncher.launch(enableBtIntent);
            return;
        }

        if (!hasRequiredPermissions()) {
            requestRequiredPermissions();
            return;
        }

        startScan();
    }

    private boolean hasRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT
                    },
                    REQUEST_PERMISSIONS
            );
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQUEST_PERMISSIONS
            );
        }
    }

    private void startScan() {
        if (bluetoothAdapter == null) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                        != PackageManager.PERMISSION_GRANTED) {
            appendLog("Missing BLUETOOTH_SCAN permission");
            return;
        }

        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bluetoothLeScanner == null) {
            appendLog("BLE scanner unavailable");
            return;
        }

        appendLog("Scanning for " + DEVICE_NAME_PREFIX + "*");
        isScanning = true;
        btnConnectToggle.setText("Scanning...");
        bluetoothLeScanner.startScan(scanCallback);
        handler.postDelayed(stopScanRunnable, 10000);
    }

    private void stopScan() {
        if (bluetoothLeScanner == null) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                        != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        bluetoothLeScanner.stopScan(scanCallback);
        isScanning = false;
        handler.removeCallbacks(stopScanRunnable);

        if (!isBluetoothConnected) {
            runOnUiThread(() -> btnConnectToggle.setText("Connect"));
        }
    }

    private void connectToDevice(BluetoothDevice device) {
        appendLog("Connecting to " + device.getAddress());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            appendLog("Missing BLUETOOTH_CONNECT permission");
            return;
        }

        bluetoothGatt = device.connectGatt(this, false, gattCallback);
        btnConnectToggle.setText("Connecting...");
    }

    private void enableNotifications(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            appendLog("Missing BLUETOOTH_CONNECT permission");
            return;
        }

        boolean notificationSet = gatt.setCharacteristicNotification(characteristic, true);
        appendLog("setCharacteristicNotification: " + notificationSet);

        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD_UUID);
        if (descriptor != null) {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            gatt.writeDescriptor(descriptor);
        } else {
            appendLog("CCCD descriptor not found");
        }
    }

    private void disconnectGatt() {
        stopScan();
        stopContinuousMove();

        if (bluetoothGatt != null) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                            == PackageManager.PERMISSION_GRANTED) {
                bluetoothGatt.disconnect();
            } else {
                appendLog("Missing BLUETOOTH_CONNECT permission");
            }
        }
    }

    private void clearGattObjects() {
        writeCharacteristic = null;
        notifyCharacteristic = null;
    }

    private void sendCommand(String command) {
        if (!isBluetoothConnected || bluetoothGatt == null || writeCharacteristic == null) {
            Toast.makeText(this, "BLE not ready", Toast.LENGTH_SHORT).show();
            appendLog("Blocked: " + sanitizeCommand(command));
            return;
        }

        appendLog("Send: " + sanitizeCommand(command));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            appendLog("Missing BLUETOOTH_CONNECT permission");
            return;
        }

        writeCharacteristic.setValue(command.getBytes(StandardCharsets.UTF_8));

        if ((writeCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            writeCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        } else {
            writeCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        }

        boolean started = bluetoothGatt.writeCharacteristic(writeCharacteristic);
        if (!started) {
            appendLog("Write could not start");
        }
    }

    private String buildMoveCommand(int p1, int p2, int p3, int p4) {
        return "F#" + p1 + "#" + p2 + "#" + p3 + "#" + p4 + "#\n";
    }

    private String buildTwistCommand(int p1, int p2, int p3) {
        return "E#" + p1 + "#" + p2 + "#" + p3 + "#\n";
    }

    private String buildDanceCommand(int danceId) {
        return "O#" + danceId + "#\n";
    }

    private void updateConnectionStatus() {
        if (isBluetoothConnected) {
            tvConnectionStatus.setText("Bluetooth: Connected");
            tvConnectionStatus.setTextColor(Color.parseColor("#2E7D32"));
        } else {
            tvConnectionStatus.setText("Bluetooth: Disconnected");
            tvConnectionStatus.setTextColor(Color.parseColor("#D32F2F"));
        }
    }

    private void appendLog(String message) {
        runOnUiThread(() -> {
            String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            String oldText = tvLog.getText().toString();
            String newText = oldText + "[" + time + "] " + message + "\n";
            tvLog.setText(newText);
        });
    }

    private String sanitizeCommand(String command) {
        return command.replace("\n", "\\n");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopScan();
        stopContinuousMove();

        if (bluetoothGatt != null) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                            == PackageManager.PERMISSION_GRANTED) {
                bluetoothGatt.close();
            }
            bluetoothGatt = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;

            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                appendLog("Permissions granted");
                startScan();
            } else {
                appendLog("Permissions denied");
                Toast.makeText(this, "Bluetooth permissions are required", Toast.LENGTH_SHORT).show();
            }
        }
    }
}