package com.example.a5466group11app;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.a5466group11app.ble.BleManager;
import com.example.a5466group11app.model.BleDeviceItem;
import com.example.a5466group11app.model.DanceType;
import com.example.a5466group11app.protocol.CommandBuilder;
import com.example.a5466group11app.util.LogHelper;

import java.util.List;

public class MainActivity extends AppCompatActivity implements BleManager.BleManagerListener {

    private static final int REQUEST_PERMISSIONS = 1002;
    private static final long MOVE_REPEAT_INTERVAL_MS = 250;

    private TextView tvConnectionStatus;
    private TextView tvLog;
    private Button btnConnectToggle;
    private ScrollView svLog;

    private BleManager bleManager;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String activeMoveCommand = null;

    private final ActivityResultLauncher<Intent> enableBluetoothLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (bleManager != null && bleManager.isBluetoothEnabled()) {
                    appendLog("Bluetooth enabled");
                    if (hasRequiredPermissions()) {
                        bleManager.startScan();
                    } else {
                        requestRequiredPermissions();
                    }
                } else {
                    appendLog("Bluetooth not enabled");
                }
            });

    private final Runnable moveRepeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (activeMoveCommand != null) {
                boolean success = bleManager.sendCommand(activeMoveCommand);
                if (success) {
                    handler.postDelayed(this, MOVE_REPEAT_INTERVAL_MS);
                }
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

        bleManager = new BleManager(this, this);

        tvConnectionStatus = findViewById(R.id.tvConnectionStatus);
        tvLog = findViewById(R.id.tvLog);
        btnConnectToggle = findViewById(R.id.btnConnectToggle);
        svLog = findViewById(R.id.svLog);

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

        tvLog.setText(LogHelper.resetLog());
        updateConnectionStatus(false);

        btnConnectToggle.setOnClickListener(v -> {
            if (bleManager.isConnected()) {
                stopContinuousMove();
                bleManager.disconnect();
            } else {
                startBleFlow();
            }
        });

        btnVerify.setOnClickListener(v -> sendCommand(CommandBuilder.verify()));
        btnStandToggle.setOnClickListener(v -> sendCommand(CommandBuilder.standToggle()));
        btnStandUp.setOnClickListener(v -> sendCommand(CommandBuilder.standUp()));
        btnLieDown.setOnClickListener(v -> sendCommand(CommandBuilder.lieDown()));

        btnForward.setOnTouchListener((v, event) -> handleMoveTouch(event, CommandBuilder.moveForward()));
        btnBackward.setOnTouchListener((v, event) -> handleMoveTouch(event, CommandBuilder.moveBackward()));
        btnTurnLeft.setOnTouchListener((v, event) -> handleMoveTouch(event, CommandBuilder.turnLeft()));
        btnTurnRight.setOnTouchListener((v, event) -> handleMoveTouch(event, CommandBuilder.turnRight()));

        btnStop.setOnClickListener(v -> {
            stopContinuousMove();
            sendCommand(CommandBuilder.stopMove());
        });

        btnTwistLeft.setOnClickListener(v -> sendCommand(CommandBuilder.twistLeft()));
        btnTwistRight.setOnClickListener(v -> sendCommand(CommandBuilder.twistRight()));

        btnDanceHello.setOnClickListener(v -> sendCommand(CommandBuilder.dance(DanceType.SAY_HELLO)));
        btnDancePushUp.setOnClickListener(v -> sendCommand(CommandBuilder.dance(DanceType.PUSH_UP)));
        btnDanceStretch.setOnClickListener(v -> sendCommand(CommandBuilder.dance(DanceType.STRETCH_SELF)));
        btnDanceTurnAround.setOnClickListener(v -> sendCommand(CommandBuilder.dance(DanceType.TURN_AROUND)));
        btnDanceSitDown.setOnClickListener(v -> sendCommand(CommandBuilder.dance(DanceType.SIT_DOWN)));
        btnDanceDancing.setOnClickListener(v -> sendCommand(CommandBuilder.dance(DanceType.DANCING)));

        btnClearLog.setOnClickListener(v -> tvLog.setText(LogHelper.resetLog()));
    }

    private boolean handleMoveTouch(android.view.MotionEvent event, String command) {
        switch (event.getAction()) {
            case android.view.MotionEvent.ACTION_DOWN:
                startContinuousMove(command);
                return true;
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL:
                stopContinuousMove();
                sendCommand(CommandBuilder.stopMove());
                return true;
            default:
                return false;
        }
    }

    private void startContinuousMove(String command) {
        if (!bleManager.isConnected()) {
            Toast.makeText(this, "BLE not ready", Toast.LENGTH_SHORT).show();
            appendLog("Blocked continuous move: " + LogHelper.sanitizeCommand(command));
            return;
        }

        if (command.equals(activeMoveCommand)) {
            return;
        }

        stopContinuousMove();
        activeMoveCommand = command;

        boolean success = bleManager.sendCommand(activeMoveCommand);
        if (success) {
            handler.postDelayed(moveRepeatRunnable, MOVE_REPEAT_INTERVAL_MS);
        }
    }

    private void stopContinuousMove() {
        activeMoveCommand = null;
        handler.removeCallbacks(moveRepeatRunnable);
    }

    private void startBleFlow() {
        if (!bleManager.isBluetoothSupported()) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_SHORT).show();
            appendLog("Bluetooth not supported");
            return;
        }

        if (!bleManager.isBluetoothEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            enableBluetoothLauncher.launch(enableBtIntent);
            return;
        }

        if (!hasRequiredPermissions()) {
            requestRequiredPermissions();
            return;
        }

        bleManager.startScan();
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

    private void sendCommand(String command) {
        boolean success = bleManager.sendCommand(command);
        if (!success) {
            Toast.makeText(this, "BLE not ready", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateConnectionStatus(boolean connected) {
        if (connected) {
            tvConnectionStatus.setText("Bluetooth: Connected");
            tvConnectionStatus.setTextColor(Color.parseColor("#2E7D32"));
            btnConnectToggle.setText("Disconnect");
        } else {
            tvConnectionStatus.setText("Bluetooth: Disconnected");
            tvConnectionStatus.setTextColor(Color.parseColor("#D32F2F"));
            btnConnectToggle.setText("Connect");
        }
    }

    private void appendLog(String message) {
        runOnUiThread(() -> {
            String currentText = tvLog.getText().toString();
            tvLog.setText(LogHelper.appendLog(currentText, message));
            svLog.post(() -> svLog.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private void showDeviceSelectionDialog(List<BleDeviceItem> devices) {
        if (devices == null || devices.isEmpty()) {
            btnConnectToggle.setText("Connect");
            Toast.makeText(this, "No Freenove dog found", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] items = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            items[i] = devices.get(i).getDisplayText();
        }

        new AlertDialog.Builder(this)
                .setTitle("Select a device")
                .setItems(items, (dialog, which) -> {
                    BleDeviceItem selected = devices.get(which);
                    appendLog("Selected device: " + selected.getName() + " (" + selected.getAddress() + ")");
                    btnConnectToggle.setText("Connecting...");
                    bleManager.connect(selected.getDevice());
                })
                .setOnCancelListener(dialog -> btnConnectToggle.setText("Connect"))
                .show();
    }

    @Override
    public void onLog(String message) {
        appendLog(message);
    }

    @Override
    public void onConnectionStateChanged(boolean connected) {
        runOnUiThread(() -> {
            updateConnectionStatus(connected);
            if (!connected) {
                stopContinuousMove();
            }
        });
    }

    @Override
    public void onScanStarted() {
        runOnUiThread(() -> btnConnectToggle.setText("Scanning..."));
    }

    @Override
    public void onScanFinished(List<BleDeviceItem> devices) {
        runOnUiThread(() -> showDeviceSelectionDialog(devices));
    }

    @Override
    public void onMessageReceived(String message) {
    }

    @Override
    public void onBleError(String message) {
        runOnUiThread(() -> {
            if (!bleManager.isConnected()) {
                btnConnectToggle.setText("Connect");
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopContinuousMove();
        if (bleManager != null) {
            bleManager.release();
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
                bleManager.startScan();
            } else {
                appendLog("Permissions denied");
                Toast.makeText(this, "Bluetooth permissions are required", Toast.LENGTH_SHORT).show();
            }
        }
    }
}