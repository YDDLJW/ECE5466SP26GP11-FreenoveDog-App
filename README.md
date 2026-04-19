# ECE5466SP26GP11 - Freenove Dog Android App

This is a simple Android app for controlling a Freenove ESP32 robot dog via Bluetooth (BLE).

## Features

- Connect to Freenove Dog using BLE
- Device scanning and selection
- Basic commands:
    - Verify controller
    - Stand / Lie down
- Movement control:
    - Forward / Backward
    - Turn left / right
    - Long-press to keep moving
- Twist control
- Dance actions (multiple predefined motions)
- Real-time command log
- Auto scroll log view
- Auto reconnect (on unexpected disconnection)

## How It Works

1. The app scans for BLE devices with name starting with:
   Freenove-Dog-

2. Select a device from the list

3. App connects using BLE GATT

4. Commands are sent as formatted strings like:
   F#...#\n
   A#...#\n
   O#...#\n

## Controls

- Direction buttons:
    - Press and hold → continuous movement
    - Release → stop
- Stop button:
    - Immediately stops movement
- Dance buttons:
    - Trigger predefined animations

## Requirements

- Android API 24+
- Bluetooth enabled
- Permissions:
    - BLUETOOTH_SCAN
    - BLUETOOTH_CONNECT
    - (Location for older Android versions)

## Project Structure

- MainActivity.java       // UI + interaction
- BleManager.java        // BLE logic
- CommandBuilder.java    // command format
- DanceType.java         // dance IDs
- BleDeviceItem.java     // device model
- LogHelper.java         // log formatting

## Notes

- This app is designed to work with Freenove ESP32 Dog firmware
- No modification to the firmware is required
- Commands follow the original protocol defined in the firmware
