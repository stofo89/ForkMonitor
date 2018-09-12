package com.example.vmec.forkmonitor.helper;

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
import android.content.SharedPreferences;
import android.os.Handler;

import com.example.vmec.forkmonitor.Constants;
import com.example.vmec.forkmonitor.event.GattCharacteristicChangeEvent;
import com.example.vmec.forkmonitor.event.GattCharacteristicReadEvent;
import com.example.vmec.forkmonitor.event.GattCharacteristicWriteEvent;
import com.example.vmec.forkmonitor.event.GattConnectedEvent;
import com.example.vmec.forkmonitor.event.GattConnectionDestroyedEvent;
import com.example.vmec.forkmonitor.event.GattDisconnectedEvent;
import com.example.vmec.forkmonitor.event.GattServicesDiscoveredEvent;
import com.example.vmec.forkmonitor.event.GattCharacteristicNotificationConfigEvent;
import com.example.vmec.forkmonitor.exception.BluetoothCharactericticNotFoundException;
import com.example.vmec.forkmonitor.exception.BluetoothNotInitializedException;
import com.example.vmec.forkmonitor.exception.BluetoothServiceNotFoundException;
import com.example.vmec.forkmonitor.preference.StringPreference;
import com.example.vmec.forkmonitor.utils.BluetoothUtils;
import com.example.vmec.forkmonitor.utils.StringUtils;

import org.greenrobot.eventbus.EventBus;

import java.util.UUID;

import timber.log.Timber;

import static android.content.Context.MODE_PRIVATE;

/**
 * Created by Stofanak on 19/08/2018.
 *
 * TODO: Check invalid bluetooth address
 */
public class BluetoothHelper {

    public static final int STATE_DISCONNECTED = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_CONNECTED = 2;

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothGatt mBluetoothGatt;
    private String mBluetoothDeviceAddress;
    private int mConnectionState = STATE_DISCONNECTED;
    private Handler mHandler;
    private StringPreference mBleCommHistoryPreference;

    private Runnable mDisconnectRunnable = new Runnable() {
        @Override public void run() {
            Timber.d("Request timeout FORCE DISCONNECT");
            disconnect();
        }
    };

    private Runnable mDisconnectTimeoutRunnable = new Runnable() {
        @Override public void run() {
            Timber.d("Disconnect timeout DESTROY GATT CLIENT");
            destroyConnection();
        }
    };

    // Implements callback methods for GATT events that the app cares about.  For example,
    // connection change and services discovered.
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            clearRequestTimeoutAction();
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                mConnectionState = STATE_CONNECTED;
                Timber.i("Connected to GATT server.");
                mBleCommHistoryPreference.append("Connected to GATT server");
                EventBus.getDefault().post(new GattConnectedEvent());
                discoverServices();

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                clearDisconnectTimeoutAction();
                mConnectionState = STATE_DISCONNECTED;
                Timber.i("Disconnected from GATT server.");
                mBleCommHistoryPreference.append("Disconnected from GATT server");
                EventBus.getDefault().post(new GattDisconnectedEvent());
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            clearRequestTimeoutAction();
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Timber.i("Discover services callback.");
                mBleCommHistoryPreference.append("Discover services callback - success");
                EventBus.getDefault().post(new GattServicesDiscoveredEvent());
            } else {
                Timber.w("onServicesDiscovered status received: %s", status);
                mBleCommHistoryPreference.append("Discover services callback - failed");
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int status) {
            clearRequestTimeoutAction();
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Timber.d("Gatt characteristic read callback");
                final String charMessage = BluetoothUtils.getCharacteristicStringValue(characteristic);
                mBleCommHistoryPreference.append(String.format("Characteristic read callback - success: %s", charMessage));
                EventBus.getDefault().post(new GattCharacteristicReadEvent(characteristic, charMessage));
            } else {
                Timber.d("Gatt characteristic read failed");
                mBleCommHistoryPreference.append("Characteristic read callback - failed");
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            clearRequestTimeoutAction();
            Timber.d("Characteristic changed callback");
            final String charMessage = BluetoothUtils.getCharacteristicStringValue(characteristic);
            mBleCommHistoryPreference.append(String.format("Characteristic changed callback: %s", charMessage));
            EventBus.getDefault().post(new GattCharacteristicChangeEvent(characteristic, charMessage));
        }

        @Override public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);
            clearRequestTimeoutAction();
            final String msg = String.format("Characteristic write callback - status: %d", status);
            Timber.d(msg);
            mBleCommHistoryPreference.append(msg);
            EventBus.getDefault().post(new GattCharacteristicWriteEvent(characteristic, status));
        }

        @Override public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorWrite(gatt, descriptor, status);
            clearRequestTimeoutAction();
            Timber.d("Descriptor write callback - status: %d", status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Characteristic notification configuration callback
                if (descriptor.getUuid().toString().equals(Constants.BLUETOOTH_CLIENT_CHARACTERISTIC_CONFIG_UUID)) {
                    Timber.d("Characteristic notification config callback - success");
                    EventBus.getDefault().post(new GattCharacteristicNotificationConfigEvent(status));
                }
            }
        }
    };

    /**
     * Contructor
     */
    public BluetoothHelper() {
        this.mHandler = new Handler();
    }

    public boolean initialize(final Context context) {
        final SharedPreferences sp = context.getSharedPreferences(Constants.PREFERENCES_FILE_NAME, MODE_PRIVATE);
        mBleCommHistoryPreference = new StringPreference(sp, Constants.PREFERENCE_BLE_COMMUNICATION_HISTORY, StringUtils.EMPTY_STRING);
        // For API level 18 and above, get a reference to BluetoothAdapter through
        // BluetoothManager.
        Timber.i("Initialize.");
        if (mBluetoothManager == null) {
            mBluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            if (mBluetoothManager == null) {
                Timber.e("Unable to initialize BluetoothManager.");
                mBleCommHistoryPreference.append("Unable to initialize BluetoothManager");
                return false;
            }
        }

        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            Timber.e("Unable to obtain a BluetoothAdapter.");
            mBleCommHistoryPreference.append("Unable to obtain a BluetoothAdapter");
            return false;
        }

        return true;
    }

    /**
     * Connects to the GATT server hosted on the Bluetooth LE device.
     *
     * @param address The device address of the destination device.
     *
     * @return Return true if the connection is initiated successfully. The connection result
     *         is reported asynchronously through the
     *         {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     *         callback.
     */
    public boolean connect(final Context context, final String address) {
        if (mBluetoothAdapter == null || address == null) {
            Timber.w("BluetoothAdapter not initialized or unspecified address.");
            mBleCommHistoryPreference.append("BluetoothAdapter not initialized");
            return false;
        }

        // Previously connected device.  Try to reconnect.
        if (mBluetoothDeviceAddress != null && address.equals(mBluetoothDeviceAddress)
                && mBluetoothGatt != null) {
            Timber.d("Trying to use an existing mBluetoothGatt for connection.");
            mBleCommHistoryPreference.append("Trying to use an existing connection");
            postRequestTimeoutAction();
            if (mBluetoothGatt.connect()) {
                mConnectionState = STATE_CONNECTING;
                return true;
            } else {
                return false;
            }
        }

        final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        if (device == null) {
            Timber.w("Device not found.  Unable to connect.");
            mBleCommHistoryPreference.append("Device not found.  Unable to connect.");
            return false;
        }
        // We want to directly connect to the device, so we are setting the autoConnect
        // parameter to false.
        Timber.d("Trying to create a new connection.");
        mBleCommHistoryPreference.append("Trying to create a new connection.");
        mBluetoothDeviceAddress = address;
        mConnectionState = STATE_CONNECTING;
        postRequestTimeoutAction();
        mBluetoothGatt = device.connectGatt(context, false, mGattCallback);
        return true;
    }

    /**
     * Call bluetooth LE service discovery request
     */
    private void discoverServices() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Timber.w("discoverServices - BluetoothAdapter not initialized");
            mBleCommHistoryPreference.append("discoverServices - BluetoothAdapter not initialized");
            return;
        }
        Timber.d("discover services request");
        mBleCommHistoryPreference.append("discoverServices - request");
        postRequestTimeoutAction();
        mBluetoothGatt.discoverServices();
    }

    /**
     * Disconnects an existing connection or cancel a pending connection. The disconnection result
     * is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
    public void disconnect() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Timber.w("disconnect - BluetoothAdapter not initialized");
            mBleCommHistoryPreference.append("disconnect - BluetoothAdapter not initialized");
            return;
        }
        Timber.d("Disconnect request.");
        mBleCommHistoryPreference.append("Disconnect request");
        postDisconnectTimeoutAction();
        mBluetoothGatt.disconnect();
    }

    public void destroyConnection() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Timber.w("destroyConnection - BluetoothAdapter not initialized");
            mBleCommHistoryPreference.append("destroyConnection - BluetoothAdapter not initialized");
            return;
        }
        Timber.d("Destroy connection.");
        mBleCommHistoryPreference.append("Destroy connection.");
//        mBluetoothGatt.disconnect();
        mBluetoothGatt.close();
        mBluetoothDeviceAddress = null;
        mBluetoothGatt = null;
        mConnectionState = STATE_DISCONNECTED;
        EventBus.getDefault().post(new GattConnectionDestroyedEvent());
    }

    /**
     * Request a read on a given characteristic specified by service UUID and characterictic UUID.
     * The read result is reported asynchronously
     * through the {@code BluetoothGattCallback#onCharacteristicRead(android.bluetooth.BluetoothGatt, android.bluetooth.BluetoothGattCharacteristic, int)}
     * callback.
     *
     * @param serviceUUID The service to read characteristic from.
     * @param characteristicUUID The characteristic to read from.
     */
    public void readCharacteristic(final String serviceUUID, final String characteristicUUID)
            throws BluetoothNotInitializedException, BluetoothServiceNotFoundException, BluetoothCharactericticNotFoundException {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Timber.w("readCharacteristic - BluetoothAdapter not initialized");
            mBleCommHistoryPreference.append("readCharacteristic - BluetoothAdapter not initialized");
            throw new BluetoothNotInitializedException("BluetoothAdapter not initialized");
        }

        Timber.d("Read characteristic request %s", characteristicUUID);
        mBleCommHistoryPreference.append("readCharacteristic - request");
        clearRequestTimeoutAction();

        BluetoothGattService mCustomService = mBluetoothGatt.getService(UUID.fromString(serviceUUID));
        if(mCustomService == null){
            final String msg = String.format("Custom BLE Service: %s not found", serviceUUID);
            mBleCommHistoryPreference.append(msg);
            Timber.w(msg);
            disconnect();
            throw new BluetoothServiceNotFoundException(msg);
        }

        postRequestTimeoutAction();

        /*get the read characteristic from the service*/
        BluetoothGattCharacteristic mReadCharacteristic = mCustomService.getCharacteristic(UUID.fromString(characteristicUUID));
        if(!mBluetoothGatt.readCharacteristic(mReadCharacteristic)) {
            final String msg = String.format("Failed to read characteristic: %s ", characteristicUUID);
            Timber.w("Failed to read characteristic");
            mBleCommHistoryPreference.append(msg);
            disconnect();
            throw new BluetoothServiceNotFoundException(msg);
        }
    }

    /**
     * Enables or disables notification on a give characteristic.
     *
     * @param characteristic Characteristic to act on.
     * @param enabled If true, enable notification.  False otherwise.
     */
    public void setCharacteristicNotification(BluetoothGattCharacteristic characteristic,
                                              boolean enabled) {
        Timber.d("Register characteristic for notifications");
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Timber.w("BluetoothAdapter not initialized");
            return;
        }
        postRequestTimeoutAction();
        mBluetoothGatt.setCharacteristicNotification(characteristic, enabled);

        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
                UUID.fromString(Constants.BLUETOOTH_CLIENT_CHARACTERISTIC_CONFIG_UUID));
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        mBluetoothGatt.writeDescriptor(descriptor);
    }

    /**
     * Writes value to specified characteristic
     *
     * @param characteristic Characteristic to act on.
     * @param value value to write to characteristic
     */
    public void writeToCharacteristic(BluetoothGattCharacteristic characteristic, final String value) {
        Timber.d("Write value to characteristic: %s", value);
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Timber.w("writeToCharacteristic - BluetoothAdapter not initialized");
            mBleCommHistoryPreference.append("writeToCharacteristic - BluetoothAdapter not initialized");
            return;
        }
        postRequestTimeoutAction();
        characteristic.setValue(value);
        final boolean writeResult = mBluetoothGatt.writeCharacteristic(characteristic);

        Timber.d("Characteristic write result %s", String.valueOf(writeResult));
        mBleCommHistoryPreference.append(String.format("Characteristic write result %s", String.valueOf(writeResult)));
    }

    public void requestConnectionDisconnectAfterTimeout() {
        postRequestTimeoutAction();
    }

    public int getConnectionState() {
        return mConnectionState;
    }

    private void postRequestTimeoutAction() {
        mHandler.postDelayed(mDisconnectRunnable, Constants.BLUETOOTH_MAX_REQUEST_TIMEOUT_MS);
    }

    private void clearRequestTimeoutAction() {
        mHandler.removeCallbacks(mDisconnectRunnable);
    }

    private void postDisconnectTimeoutAction() {
        mHandler.postDelayed(mDisconnectTimeoutRunnable, Constants.BLUETOOTH_MAX_REQUEST_TIMEOUT_MS);
    }

    private void clearDisconnectTimeoutAction() {
        mHandler.removeCallbacks(mDisconnectTimeoutRunnable);
    }
}
