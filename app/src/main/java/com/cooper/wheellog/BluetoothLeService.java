package com.cooper.wheellog;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import android.widget.Toast;

import com.cooper.wheellog.utils.*;

import java.text.SimpleDateFormat;
import java.util.*;

import timber.log.Timber;

/**
 * Service for managing connection and data communication with a GATT server hosted on a
 * given Bluetooth LE device.
 */
public class BluetoothLeService extends Service {

    private BluetoothAdapter mBluetoothAdapter;
    private String mBluetoothDeviceAddress;
    private BluetoothGatt mBluetoothGatt;
    private int mConnectionState = STATE_DISCONNECTED;
    private Date mDisconnectTime;
    private Timer reconnectTimer;

    public static final int STATE_DISCONNECTED = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_CONNECTED = 2;

    private boolean disconnectRequested = false;
    private boolean autoConnect = false;

    private Timer beepTimer;
    private int timerTicks;
    PowerManager mgr;
    PowerManager.WakeLock wl;
    FileUtil fileUtilRawData;
    final SimpleDateFormat sdf = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US);
    final SimpleDateFormat sdf2 = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private final String wakeLogTag = "WhellLog:WakeLockTag";
    private final IBinder mBinder = new LocalBinder();

    public void startReconnectTimer() {
        if (reconnectTimer != null) {
            stopReconnectTimer();
        }
        reconnectTimer = new Timer();
        WheelData wd = WheelData.getInstance();
        int magicPeriod = 15_000;
        reconnectTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (mConnectionState == STATE_CONNECTED && wd.getLastLifeData() > 0 && ((System.currentTimeMillis() -  wd.getLastLifeData()) / 1000 > magicPeriod)) {
                    toggleReconnectToWheel();
                }
            }
        }, magicPeriod, magicPeriod);
    }

    public void stopReconnectTimer() {
        if (reconnectTimer != null) {
            reconnectTimer.cancel();
        }
        reconnectTimer = null;
    }

    public int getConnectionState() {
        return mConnectionState;
    }

    // Implements callback methods for GATT events that the app cares about.  For example,
    // connection change and services discovered.
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);
            Boolean connectionSound = WheelLog.AppConfig.getConnectionSound();
            int noConnectionSound = WheelLog.AppConfig.getNoConnectionSound() * 1000;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Timber.i("Connected to GATT server.");
                if (connectionSound) {
                    if (noConnectionSound > 0) {
                        stopBeepTimer();
                    }
                    if (wl != null) {
                        wl.release();
                        wl = null;
                    }
                    wl = mgr.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, wakeLogTag);
                    wl.acquire(5 * 60 * 1000L /*5 minutes*/);
                    SomeUtil.playSound(getApplicationContext(), R.raw.sound_connect);
                }
                mDisconnectTime = null;
                // Attempts to discover services after successful connection.
                if (mBluetoothGatt != null) {
                    Timber.i("Attempting to start service discovery:%b",
                            mBluetoothGatt.discoverServices());
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Timber.i("Disconnected from GATT server.");
                if (mConnectionState == STATE_CONNECTED) {
                    mDisconnectTime = Calendar.getInstance().getTime();
                    if (connectionSound) {
                        SomeUtil.playSound(getApplicationContext(), R.raw.sound_disconnect);
                        if (wl != null) {
                            wl.release();
                            wl = null;
                        }
                        if (noConnectionSound > 0) {
                            startBeepTimer();
                        }
                    }
                }
                if (!disconnectRequested &&
                        mBluetoothGatt != null && mBluetoothGatt.getDevice() != null) {
                    Timber.i("Trying to reconnect");
                    switch (WheelData.getInstance().getWheelType()) {
                        case INMOTION:
                            InMotionAdapter.stopTimer();
                        case INMOTION_V2:
                            InmotionAdapterV2.stopTimer();
                        case NINEBOT_Z:
                            NinebotZAdapter.getInstance().resetConnection();
                        case NINEBOT:
                            NinebotAdapter.getInstance().resetConnection();
                    }
                    if (!autoConnect) {
                        autoConnect = true;
                        mBluetoothGatt.close();
                        mBluetoothGatt = mBluetoothGatt.getDevice().connectGatt(BluetoothLeService.this, autoConnect, mGattCallback);
                    }
                    broadcastConnectionUpdate(STATE_CONNECTING, true);
                } else {
                    Timber.i("Disconnected");
                    mConnectionState = STATE_DISCONNECTED;
                    broadcastConnectionUpdate(STATE_DISCONNECTED);
                }
            } else
                Toast.makeText(BluetoothLeService.this, "Unknown Connection State\rState = " + newState, Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);
            Timber.i("onServicesDiscovered called");
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Timber.i("onServicesDiscovered called, status == BluetoothGatt.GATT_SUCCESS");
                boolean recognisedWheel = WheelData.getInstance().detectWheel(mBluetoothDeviceAddress);
                WheelData.getInstance().setConnected(recognisedWheel);
                if (recognisedWheel) {
                    sendBroadcast(new Intent(Constants.ACTION_WHEEL_TYPE_RECOGNIZED));
                    mConnectionState = STATE_CONNECTED;
                    broadcastConnectionUpdate(mConnectionState);
                } else {
                    disconnect();
                }
                return;
            }
            Timber.i("onServicesDiscovered called, status == BluetoothGatt.GATT_FAILURE");
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicRead(gatt, characteristic, status);
            Timber.i("onCharacteristicRead called %s", characteristic.getUuid().toString());
            readData(characteristic, status);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);
            Timber.i("onCharacteristicChanged called %s", characteristic.getUuid().toString());
            readData(characteristic, BluetoothGatt.GATT_SUCCESS);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorWrite(gatt, descriptor, status);
            Timber.i("onDescriptorWrite %d", status);
        }
    };

    private void readData(BluetoothGattCharacteristic characteristic, int status) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            return;
        }

        // RAW data
        if (WheelLog.AppConfig.getEnableRawData()) {
            if (fileUtilRawData == null) {
                fileUtilRawData = new FileUtil(getApplicationContext());
            }
            if (fileUtilRawData.isNull()) {
                String fileNameForRawData = "RAW_" + sdf.format(new Date()) + ".csv";
                fileUtilRawData.prepareFile(fileNameForRawData, WheelData.getInstance().getMac());
            }
            fileUtilRawData.writeLine(String.format(Locale.US, "%s,%s",
                    sdf2.format(System.currentTimeMillis()),
                    StringUtil.toHexStringRaw(characteristic.getValue())));
        } else if (fileUtilRawData != null && !fileUtilRawData.isNull()) {
            fileUtilRawData.close();
        }

        WheelData wd = WheelData.getInstance();
        byte[] value = characteristic.getValue();
        switch (wd.getWheelType()) {
            case KINGSONG:
                if (characteristic.getUuid().toString().equals(Constants.KINGSONG_READ_CHARACTER_UUID)) {
                    wd.decodeResponse(value, getApplicationContext());
                    if (WheelData.getInstance().getName().isEmpty()) {
                        KingsongAdapter.getInstance().requestNameData();
                    } else if (WheelData.getInstance().getSerial().isEmpty()) {
                        KingsongAdapter.getInstance().requestSerialData();
                    }
                }
                break;
            case GOTWAY:
            case GOTWAY_VIRTUAL:
            case VETERAN:
                WheelData.getInstance().decodeResponse(value, getApplicationContext());
                break;
            case INMOTION:
                if (characteristic.getUuid().toString().equals(Constants.INMOTION_READ_CHARACTER_UUID)) {
                    wd.decodeResponse(value, getApplicationContext());
                }
                break;
            case INMOTION_V2:
                if (characteristic.getUuid().toString().equals(Constants.INMOTION_V2_READ_CHARACTER_UUID)) {
                    wd.decodeResponse(value, getApplicationContext());
                }
                break;
            case NINEBOT_Z:
                Timber.i("Ninebot Z reading");
                if (characteristic.getUuid().toString().equals(Constants.NINEBOT_Z_READ_CHARACTER_UUID)) {
                    wd.decodeResponse(value, getApplicationContext());
                }
                break;
            case NINEBOT:
                Timber.i("Ninebot reading");
                if (characteristic.getUuid().toString().equals(Constants.NINEBOT_READ_CHARACTER_UUID) ||
                        characteristic.getUuid().toString().equals(Constants.NINEBOT_Z_READ_CHARACTER_UUID)) { // in case of S2 or Mini
                    Timber.i("Ninebot read cont");
                    wd.decodeResponse(value, getApplicationContext());
                }
                break;
        }
    }

    private void broadcastConnectionUpdate(int connectionState) {
        broadcastConnectionUpdate(connectionState, false);
    }

    private void broadcastConnectionUpdate(int connectionState, boolean auto_connect) {
        switch (connectionState) {
            case STATE_CONNECTED:
                mConnectionState = STATE_CONNECTED;
                break;
            case STATE_DISCONNECTED:
                mConnectionState = STATE_DISCONNECTED;
                break;
            case STATE_CONNECTING:
                mConnectionState = STATE_CONNECTING;
                break;
        }

        final Intent intent = new Intent(Constants.ACTION_BLUETOOTH_CONNECTION_STATE);
        intent.putExtra(Constants.INTENT_EXTRA_CONNECTION_STATE, connectionState);
        if (auto_connect) {
            intent.putExtra(Constants.INTENT_EXTRA_BLE_AUTO_CONNECT, true);
        }
        sendBroadcast(intent);
    }

    public class LocalBinder extends Binder {
        BluetoothLeService getService() {
            return BluetoothLeService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        mgr = (PowerManager) this.getSystemService(Context.POWER_SERVICE);
        startForeground(Constants.MAIN_NOTIFICATION_ID, WheelLog.Notifications.getNotification());
        if (WheelLog.AppConfig.getUseReconnect()) {
            startReconnectTimer();
        }
        return mBinder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (fileUtilRawData != null) {
            fileUtilRawData.close();
        }
        stopBeepTimer();
        if (mBluetoothGatt != null && mConnectionState != STATE_DISCONNECTED) {
            mBluetoothGatt.disconnect();
        }
        stopReconnectTimer();
        close();
    }

    /**
     * Initializes a reference to the local Bluetooth adapter.
     *
     * @return Return true if the initialization is successful.
     */
    public boolean initialize() {
        mBluetoothAdapter = getAdapter(getApplicationContext());
        if (mBluetoothAdapter == null) {
            Timber.i("Unable to obtain a BluetoothAdapter.");
            return false;
        }
        return true;
    }

    public void setDeviceAddress(String address) {
        if (address != null && !address.isEmpty())
            mBluetoothDeviceAddress = address;
    }

    /**
     * Connects to the GATT server hosted on the Bluetooth LE device.
     *
     * @return Return true if the connection is initiated successfully. The connection result
     * is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
    public boolean connect() {
        disconnectRequested = false;
        autoConnect = false;
        mDisconnectTime = null;

        if (mBluetoothAdapter == null || mBluetoothDeviceAddress == null || mBluetoothDeviceAddress.isEmpty()) {
            Timber.i("BluetoothAdapter not initialized or unspecified address.");
            return false;
        }

        if (mBluetoothGatt != null && mBluetoothGatt.getDevice().getAddress().equals(mBluetoothDeviceAddress)) {
            Timber.i("Trying to use an existing mBluetoothGatt for connection.");
            if (mBluetoothGatt.connect()) {
                WheelData.getInstance().setBtName(mBluetoothGatt.getDevice().getName());
                mConnectionState = STATE_CONNECTING;
                broadcastConnectionUpdate(mConnectionState);
                return true;
            } else {
                return false;
            }
        }

        final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(mBluetoothDeviceAddress);
        if (device == null) {
            Timber.i("Device not found.  Unable to connect.");
            return false;
        }
        mBluetoothGatt = device.connectGatt(this, autoConnect, mGattCallback);
        Timber.i("Trying to create a new connection.");
        mConnectionState = STATE_CONNECTING;
        broadcastConnectionUpdate(mConnectionState);
        return true;
    }

    /**
     * Disconnects an existing connection or cancel a pending connection. The disconnection result
     * is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
    public void disconnect() {
        disconnectRequested = true;
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Timber.i("BluetoothAdapter not initialized");
            return;
        }
        if (mConnectionState != STATE_CONNECTED)
            mConnectionState = STATE_DISCONNECTED;
        mBluetoothGatt.disconnect();
        broadcastConnectionUpdate(STATE_DISCONNECTED);
    }

    /**
     * After using a given BLE device, the app must call this method to ensure resources are
     * released properly.
     */
    public void close() {
        if (mBluetoothGatt == null) {
            return;
        }
        mBluetoothGatt.close();
        mBluetoothGatt = null;
    }

    public void toggleConnectToWheel() {
        if (mConnectionState == STATE_DISCONNECTED)
            connect();
        else {
            disconnect();
            close();
        }
    }

    private void toggleReconnectToWheel() {
        if (mConnectionState == STATE_CONNECTED) {
            Timber.wtf("Trying to reconnect");
            // After disconnect, the method onConnectionStateChange will automatically reconnect
            // because disconnectRequested is false
            disconnectRequested = false;
            mConnectionState = STATE_DISCONNECTED;
            mBluetoothGatt.disconnect();
            broadcastConnectionUpdate(mConnectionState);
        }
    }

    public void setCharacteristicNotification(BluetoothGattCharacteristic characteristic, boolean enabled) {
        Timber.i("Set characteristic start");
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Timber.i("BluetoothAdapter not initialized");
            return;
        }
        boolean success = mBluetoothGatt.setCharacteristicNotification(characteristic, enabled);
        Timber.i("Set characteristic %b", success);
    }

    public synchronized boolean writeBluetoothGattCharacteristic(byte[] cmd) {
        if (this.mBluetoothGatt == null || cmd == null) {
            return false;
        }
        StringBuilder stringBuilder = new StringBuilder(cmd.length);
        for (byte aData : cmd) {
            stringBuilder.append(String.format(Locale.US, "%02X", aData));
        }
        Timber.i("Transmitted: %s", stringBuilder.toString());
        try {
            switch (WheelData.getInstance().getWheelType()) {
                case KINGSONG:
                    BluetoothGattService ks_service = this.mBluetoothGatt.getService(UUID.fromString(Constants.KINGSONG_SERVICE_UUID));
                    if (ks_service == null) {
                        Timber.i("writeBluetoothGattCharacteristic service == null");
                        return false;
                    }
                    BluetoothGattCharacteristic ks_characteristic = ks_service.getCharacteristic(UUID.fromString(Constants.KINGSONG_READ_CHARACTER_UUID));
                    if (ks_characteristic == null) {
                        Timber.i("writeBluetoothGattCharacteristic characteristic == null");
                        return false;
                    }
                    ks_characteristic.setValue(cmd);
                    Timber.i("writeBluetoothGattCharacteristic writeType = %d", ks_characteristic.getWriteType());
                    ks_characteristic.setWriteType(1);
                    return this.mBluetoothGatt.writeCharacteristic(ks_characteristic);
                case GOTWAY:
                case GOTWAY_VIRTUAL:
                case VETERAN:
                    BluetoothGattService gw_service = this.mBluetoothGatt.getService(UUID.fromString(Constants.GOTWAY_SERVICE_UUID));
                    if (gw_service == null) {
                        Timber.i("writeBluetoothGattCharacteristic service == null");
                        return false;
                    }
                    BluetoothGattCharacteristic characteristic = gw_service.getCharacteristic(UUID.fromString(Constants.GOTWAY_READ_CHARACTER_UUID));
                    if (characteristic == null) {
                        Timber.i("writeBluetoothGattCharacteristic characteristic == null");
                        return false;
                    }
                    characteristic.setValue(cmd);
                    Timber.i("writeBluetoothGattCharacteristic writeType = %d", characteristic.getWriteType());
                    return this.mBluetoothGatt.writeCharacteristic(characteristic);
                case NINEBOT:
                    if (WheelData.getInstance().getProtoVer().compareTo("") == 0) {
                        BluetoothGattService nb_service = this.mBluetoothGatt.getService(UUID.fromString(Constants.NINEBOT_SERVICE_UUID));
                        if (nb_service == null) {
                            Timber.i("writeBluetoothGattCharacteristic service == null");
                            return false;
                        }
                        BluetoothGattCharacteristic nb_characteristic = nb_service.getCharacteristic(UUID.fromString(Constants.NINEBOT_WRITE_CHARACTER_UUID));
                        if (nb_characteristic == null) {
                            Timber.i("writeBluetoothGattCharacteristic characteristic == null");
                            return false;
                        }
                        nb_characteristic.setValue(cmd);
                        Timber.i("writeBluetoothGattCharacteristic writeType = %d", nb_characteristic.getWriteType());
                        return this.mBluetoothGatt.writeCharacteristic(nb_characteristic);
                    } // if S2 or Mini, then pass to Ninebot_Z case
                    Timber.i("Passing to NZ");
                case NINEBOT_Z:
                    BluetoothGattService nz_service = this.mBluetoothGatt.getService(UUID.fromString(Constants.NINEBOT_Z_SERVICE_UUID));
                    if (nz_service == null) {
                        Timber.i("writeBluetoothGattCharacteristic service == null");
                        return false;
                    }
                    BluetoothGattCharacteristic nz_characteristic = nz_service.getCharacteristic(UUID.fromString(Constants.NINEBOT_Z_WRITE_CHARACTER_UUID));
                    if (nz_characteristic == null) {
                        Timber.i("writeBluetoothGattCharacteristic characteristic == null");
                        return false;
                    }
                    nz_characteristic.setValue(cmd);
                    Timber.i("writeBluetoothGattCharacteristic writeType = %d", nz_characteristic.getWriteType());
                    return this.mBluetoothGatt.writeCharacteristic(nz_characteristic);
                case INMOTION:
                    BluetoothGattService im_service = this.mBluetoothGatt.getService(UUID.fromString(Constants.INMOTION_WRITE_SERVICE_UUID));
                    if (im_service == null) {
                        Timber.i("writeBluetoothGattCharacteristic service == null");
                        return false;
                    }
                    BluetoothGattCharacteristic im_characteristic = im_service.getCharacteristic(UUID.fromString(Constants.INMOTION_WRITE_CHARACTER_UUID));
                    if (im_characteristic == null) {
                        Timber.i("writeBluetoothGattCharacteristic characteristic == null");
                        return false;
                    }
                    byte[] buf = new byte[20];
                    int i2 = cmd.length / 20;
                    int i3 = cmd.length - (i2 * 20);
                    for (int i4 = 0; i4 < i2; i4++) {
                        System.arraycopy(cmd, i4 * 20, buf, 0, 20);
                        im_characteristic.setValue(buf);
                        if (!this.mBluetoothGatt.writeCharacteristic(im_characteristic))
                            return false;
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    if (i3 > 0) {
                        System.arraycopy(cmd, i2 * 20, buf, 0, i3);
                        im_characteristic.setValue(buf);
                        if (!this.mBluetoothGatt.writeCharacteristic(im_characteristic))
                            return false;
                    }
                    Timber.i("writeBluetoothGattCharacteristic writeType = %d", im_characteristic.getWriteType());
                    return true;
                case INMOTION_V2:
                    BluetoothGattService inv2_service = this.mBluetoothGatt.getService(UUID.fromString(Constants.INMOTION_V2_SERVICE_UUID));
                    if (inv2_service == null) {
                        Timber.i("writeBluetoothGattCharacteristic service == null");
                        return false;
                    }
                    BluetoothGattCharacteristic inv2_characteristic = inv2_service.getCharacteristic(UUID.fromString(Constants.INMOTION_V2_WRITE_CHARACTER_UUID));
                    if (inv2_characteristic == null) {
                        Timber.i("writeBluetoothGattCharacteristic characteristic == null");
                        return false;
                    }
                    inv2_characteristic.setValue(cmd);
                    Timber.i("writeBluetoothGattCharacteristic writeType = %d", inv2_characteristic.getWriteType());
                    return this.mBluetoothGatt.writeCharacteristic(inv2_characteristic);
            }
        } catch (NullPointerException e) {
            // sometimes mBluetoothGatt is null... If the user starts to connect and disconnect quickly
            Timber.i("writeBluetoothGattCharacteristic throws NullPointerException: %s", e.getMessage());
        }
        return false;
    }

    public void writeBluetoothGattDescriptor(BluetoothGattDescriptor descriptor) {
        boolean success = mBluetoothGatt.writeDescriptor(descriptor);
        Timber.i("Write descriptor %b", success);
    }

    public Date getDisconnectTime() {
        return mDisconnectTime;
    }

    /**
     * Retrieves a list of supported GATT services on the connected device. This should be
     * invoked only after {@code BluetoothGatt#discoverServices()} completes successfully.
     *
     * @return A {@code List} of supported services.
     */
    public List<BluetoothGattService> getSupportedGattServices() {
        if (mBluetoothGatt == null) return null;

        return mBluetoothGatt.getServices();
    }

    public BluetoothGattService getGattService(UUID service_id) {
        return mBluetoothGatt.getService(service_id);
    }

    public String getBluetoothDeviceAddress() {
        return mBluetoothDeviceAddress;
    }

    private void startBeepTimer() {
        wl = mgr.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, wakeLogTag);
        wl.acquire(5 * 60 * 1000L /*5 minutes*/);
        timerTicks = 0;
        final int noConnectionSound = WheelLog.AppConfig.getNoConnectionSound() * 1000;
        TimerTask beepTimerTask = new TimerTask() {
            @Override
            public void run() {
                timerTicks++;
                if (timerTicks * noConnectionSound > 300000) {
                    stopBeepTimer();
                }
                SomeUtil.playSound(getApplicationContext(), R.raw.sound_no_connection);

            }
        };
        beepTimer = new Timer();
        beepTimer.scheduleAtFixedRate(beepTimerTask, noConnectionSound, noConnectionSound);
    }

    private void stopBeepTimer() {
        if (wl != null) {
            wl.release();
            wl = null;
        }
        if (beepTimer != null) {
            beepTimer.cancel();
            beepTimer = null;
        }
    }

    public static BluetoothAdapter getAdapter(Context context) {
        BluetoothManager bluetoothManager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null) {
            Timber.i("Unable to initialize BluetoothManager.");
            return null;
        }
        BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
        if (bluetoothAdapter == null) {
            Timber.i("Unable to obtain a BluetoothAdapter.");
        }
        return bluetoothAdapter;
    }
}
