package com.example.a5466group11app.model;

import android.bluetooth.BluetoothDevice;

public class BleDeviceItem {

    private final String name;
    private final String address;
    private final BluetoothDevice device;

    public BleDeviceItem(String name, String address, BluetoothDevice device) {
        this.name = name == null ? "Unknown Device" : name;
        this.address = address;
        this.device = device;
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return address;
    }

    public BluetoothDevice getDevice() {
        return device;
    }

    public String getDisplayText() {
        return name + "\n" + address;
    }
}