/*
 * Copyright (C) 2013 jonas.oreland@gmail.com
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.runnerup.hr;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import android.annotation.TargetApi;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;

/**
 * Base class for BT 2.0 HR providers. It has a thread for connecting with a
 * bluetooth device and a thread for performing data transmission when
 * connected.
 */
@TargetApi(Build.VERSION_CODES.GINGERBREAD_MR1)
public abstract class Bt20Base implements HRProvider {

    public boolean isEnabled() {
        return isEnabledImpl();
    }

    public static boolean isEnabledImpl() {
        if (BluetoothAdapter.getDefaultAdapter() != null)
            return BluetoothAdapter.getDefaultAdapter().isEnabled();
        return false;
    }

    public boolean startEnableIntent(Activity activity, int requestCode) {
        return startEnableIntentImpl(activity, requestCode);
    }

    public static boolean startEnableIntentImpl(Activity activity, int requestCode) {
        activity.startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                requestCode);
        return true;
    }

    public static boolean checkLibrary(Context ctx) {

        // Don't bother if createInsecureRfcommSocketToServiceRecord isn't
        // available
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.GINGERBREAD_MR1)
            return false;

        return true;
    }

    // UUID
    public static final UUID MY_UUID = UUID
            .fromString("00001101-0000-1000-8000-00805F9B34FB");
    private ConnectThread connectThread;
    private ConnectedThread connectedThread;

    private int hrValue = 0;
    private long hrTimestamp = 0;
    private BluetoothAdapter btAdapter = null;

    // private Context context = null;

    public Bt20Base(Context ctx) {
        // context = ctx;
    }

    private HRClient hrClient;
    private Handler hrClientHandler;

    @Override
    public void open(Handler handler, HRClient hrClient) {
        this.hrClient = hrClient;
        this.hrClientHandler = handler;

        if (btAdapter == null) {
            btAdapter = BluetoothAdapter.getDefaultAdapter();
        }
        if (btAdapter == null) {
            hrClient.onOpenResult(false);
            return;
        }

        hrClient.onOpenResult(true);
    }

    @Override
    public void close() {
        reset();
        btAdapter = null;
    }

    private boolean mIsConnecting;
    private boolean mIsConnected;
    private boolean mIsScanning;

    public void disconnect() {
        reset();
    }

    private void reset() {
        cancelThreads();
        mIsConnecting = false;
        mIsConnected = false;
        mIsScanning = false;
    }

    @Override
    public boolean isScanning() {
        return mIsScanning;
    }

    @Override
    public boolean isConnected() {
        return mIsConnected;
    }

    @Override
    public boolean isConnecting() {
        return mIsConnecting;
    }

    @Override
    public int getHRValue() {
        return hrValue;
    }

    @Override
    public long getHRValueTimestamp() {
        return hrTimestamp;
    }

    /**
     * Cancels all the threads.
     */
    private void cancelThreads() {
        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }
    }

    @Override
    public boolean isBondingDevice() {
        return true;
    }

    @Override
    public void startScan() {
        if (btAdapter == null)
            return;

        mIsScanning = true;

        hrClientHandler.post(new Runnable() {
            @Override
            public void run() {
                Set<BluetoothDevice> list = new HashSet<BluetoothDevice>();
                list.addAll(btAdapter.getBondedDevices());
                publishDevice(list);
            }
        });
    }

    private void publishDevice(final Set<BluetoothDevice> list) {
        if (list.isEmpty()) {
            mIsScanning = false;
            return;
        }

        if (mIsScanning) {
            BluetoothDevice dev = list.iterator().next();
            list.remove(dev);
            hrClient.onScanResult(createDeviceRef(getProviderName(), dev));
            hrClientHandler.post(new Runnable() {

                @Override
                public void run() {
                    publishDevice(list);
                }
            });
        }
    }

    @Override
    public void stopScan() {
        mIsScanning = false;
    }

    @Override
    public void connect(HRDeviceRef ref) {
        cancelThreads();

        if (!isEnabledImpl()) {
            reportConnected(false);
            return;
        }

        mIsConnecting = true;
        connectThread = new ConnectThread(btAdapter.getRemoteDevice(ref.deviceAddress),
                ref.deviceName);
        connectThread.start();
    }

    private synchronized void connected(final BluetoothSocket bluetoothSocket,
            final BluetoothDevice bluetoothDevice,
            final String btDeviceName) {
        cancelThreads();

        if (hrClient != null) {
            hrClientHandler.post(new Runnable() {

                @Override
                public void run() {
                    if (mIsConnecting && hrClient != null) {
                        // Start connected thread...
                        connectedThread = new ConnectedThread(bluetoothDevice, btDeviceName,
                                bluetoothSocket);
                        connectedThread.start();
                    } else {
                        closeSocket(bluetoothSocket);
                    }
                }
            });
        } else {
            closeSocket(bluetoothSocket);
        }
    }

    protected static void closeSocket(BluetoothSocket bluetoothSocket) {
        System.err.println("closeSocket(" + bluetoothSocket + ")");
        if (bluetoothSocket != null) {
            try {
                bluetoothSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }

    protected static void closeStream(InputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void reportConnected(final boolean result) {
        System.err.println("reportConnected(" + result + ") mIsConnecting: " + mIsConnecting
                + ", mIsConnected: " + mIsConnected + ", hrClient: " + hrClient);
        if (hrClient != null) {
            hrClientHandler.post(new Runnable() {

                @Override
                public void run() {
                    boolean reset = !result;
                    if (mIsConnecting && hrClient != null) {
                        mIsConnected = result;
                        mIsConnecting = false;
                        hrClient.onConnectResult(result);
                    } else {
                        reset = true;
                    }

                    if (reset) {
                        Bt20Base.this.reset();
                    }
                }
            });
        }
    }

    static BluetoothSocket tryConnect(final BluetoothDevice device, int i)
            throws IOException {
        BluetoothSocket sock = null;
        System.err.println("tryConnect(method: " + i + ")");

        switch (i) {
            case 0:
                sock = device.createRfcommSocketToServiceRecord(MY_UUID);
                break;
            case 1:
                sock = device.createInsecureRfcommSocketToServiceRecord(MY_UUID);
                break;
            case 2: {
                Method m;
                try {
                    m = device.getClass().getMethod("createInsecureRfcommSocket",
                            new Class[] {
                                int.class
                            });
                    m.setAccessible(true);
                    sock = (BluetoothSocket) m.invoke(device, 1);
                } catch (NoSuchMethodException e) {
                    e.printStackTrace();
                } catch (IllegalArgumentException e) {
                    e.printStackTrace();
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                } catch (InvocationTargetException e) {
                    e.printStackTrace();
                }
            }
        }

        if (sock == null) {
            throw new IOException("Create socket failed!");
        }

        try {
            sock.connect();
            return sock;
        } catch (IOException ex) {
            closeSocket(sock);
            throw ex;
        }
    }

    /**
     * A thread to connect to a bluetooth device.
     */
    private class ConnectThread extends Thread {
        private BluetoothSocket bluetoothSocket;
        private final BluetoothDevice bluetoothDevice;
        private final String deviceName;

        public ConnectThread(BluetoothDevice device, String deviceName) {
            setName("ConnectThread-" + device.getName());
            this.bluetoothDevice = device;
            this.deviceName = deviceName;
        }

        @Override
        public void run() {
            if (btAdapter == null || bluetoothDevice == null || deviceName == null) {
                reportConnected(false);
                return;
            }

            for (int i = 0; i < 3; i++) {
                if (!(isConnecting() || isConnected())) {
                    /* check for disconnect */
                    break;
                }
                try {
                    bluetoothSocket = tryConnect(bluetoothDevice, i);
                    break;
                } catch (Exception e1) {
                    e1.printStackTrace();
                }
            }

            if (bluetoothSocket == null) {
                System.err.println("connect failed!");
                reportConnected(false);
                return;

            }

            if (btAdapter == null) {
                System.err.println("btAdapter == null in connect thread. giving up");
                closeSocket(bluetoothSocket);
                synchronized (Bt20Base.this) {
                    connectThread = null;
                }
                reportConnected(false);
                return;
            }

            // Cancel discovery to prevent slow down
            btAdapter.cancelDiscovery();

            // Reset the ConnectThread since we are done
            synchronized (Bt20Base.this) {
                connectThread = null;
            }

            System.err.println("connected => " + bluetoothSocket);
            // Start the connected thread
            connected(bluetoothSocket, bluetoothDevice, deviceName);
            bluetoothSocket = null;
        }

        /**
         * Cancels this thread.
         */
        public void cancel() {
            closeSocket(bluetoothSocket);
        }
    }

    /**
     * This thread handles data transmission when connected.
     */
    private class ConnectedThread extends Thread {
        private final BluetoothDevice bluetoothDevice;
        private final BluetoothSocket bluetoothSocket;
        private final InputStream inputStream;
        private final String deviceName;

        public ConnectedThread(final BluetoothDevice device, final String deviceName,
                final BluetoothSocket bluetoothSocket) {
            this.bluetoothDevice = device;
            this.bluetoothSocket = bluetoothSocket;
            this.deviceName = deviceName;

            InputStream tmp = null;

            try {
                tmp = bluetoothSocket.getInputStream();
            } catch (IOException e) {
                System.err.println("socket.getInputStream(): " + e);
                closeSocket(bluetoothSocket);
            }
            inputStream = tmp;
        }

        @Override
        public void run() {
            readHR();
        }

        private void readHR() {
            Integer hr[] = new Integer[1];
            final int frameSize = getFrameSize();
            byte[] buffer = new byte[2 * frameSize];
            int bytesInBuffer = 0;

            // Keep listening to the inputStream while connected
            while (true) {
                try {
                    // Read from the inputStream
                    int bytesRead = inputStream.read(buffer, bytesInBuffer, buffer.length
                            - bytesInBuffer);

                    if (bytesRead == -1) {
                        throw new IOException("EOF reached.");
                    }

                    bytesInBuffer += bytesRead;
                    int bytesUsed = parseBuffer(buffer, bytesInBuffer, hr);
                    if (hr[0] != null) {
                        hrValue = hr[0].intValue();
                        hrTimestamp = System.currentTimeMillis();

                        if (hrValue > 0 && mIsConnecting) {
                            System.err.println("hrValue: " + hrValue + " => reportConnected");
                            reportConnected(true);
                        }
                    }

                    if (bytesUsed > 0) {
                        System.arraycopy(buffer, bytesUsed, buffer, 0, bytesInBuffer - bytesUsed);
                        bytesInBuffer -= bytesUsed;
                    } else if (bytesUsed == 0 && bytesInBuffer == buffer.length) {
                        System.err.println("reset");
                        bytesInBuffer = 0;
                    }
                } catch (IOException e) {
                    reportDisconnected(bluetoothDevice, deviceName, e);
                    break;
                }
            }

            closeStream(inputStream);
            closeSocket(bluetoothSocket);
        }

        /**
         * Cancels this thread.
         */
        public void cancel() {
            closeStream(inputStream);
            closeSocket(bluetoothSocket);
        }
    }

    public void reportDisconnected(final BluetoothDevice bluetoothDevice,
            final String btDeviceName, final IOException e) {
        System.err.println("reportDisconnect()");
        e.printStackTrace();
        if (hrClientHandler != null) {
            hrClientHandler.post(new Runnable() {

                @Override
                public void run() {
                    if (hrClient == null) {
                        System.err.println("reportDisconnect() hrClient == null");
                        return;
                    }

                    if (mIsConnected) {
                        /**
                         * reconnect
                         */
                        System.err.println("reportDisconnect() => reconnecting");
                        connect(Bt20Base.createDeviceRef(getProviderName(), bluetoothDevice));
                        return;
                    } else {
                        System.err.println("reportDisconnect() mIsConnected != true");
                    }
                }
            });
        } else {
            System.err.println("reportDisconnect() hrClientHandler == null");
        }
    }

    abstract static class Bt20BaseOld extends Bt20Base {

        public Bt20BaseOld(Context ctx) {
            super(ctx);
        }

        @Override
        public int parseBuffer(byte[] buffer, int bytesInBuffer, Integer[] hr) {
            hr[0] = null;
            if (bytesInBuffer < getFrameSize())
                return 0;

            int hrValue = parseBuffer(buffer);
            if (hrValue > 0) {
                hr[0] = Integer.valueOf(hrValue);
                return bytesInBuffer; // use all of buffer
            } else {
                int index = findNextAlignment(buffer);
                if (index < 0)
                    return bytesInBuffer;
                return index;
            }
        }

        public abstract int getFrameSize();

        public abstract int parseBuffer(byte[] buffer);

        public abstract int findNextAlignment(byte buffer[]);
    }

    public abstract int getFrameSize();

    public abstract int parseBuffer(byte[] buffer, int bytesInBuffer, Integer[] hr);

    public static class ZephyrHRM extends Bt20BaseOld {

        static final byte ZEPHYR_HXM_BYTE_STX = 0;
        static final byte ZEPHYR_HXM_BYTE_HR = 12;
        static final byte ZEPHYR_HXM_BYTE_CRC = 58;
        static final byte ZEPHYR_HXM_BYTE_ETX = 59;

        static final byte ZEPHYR_START_BYTE = 0x02;
        static final byte ZEPHYR_END_BYTE = 0x03;
        public static final String NAME = "Zephyr";

        public ZephyrHRM(Context ctx) {
            super(ctx);
        }

        @Override
        public String getName() {
            return NAME;
        }

        @Override
        public String getProviderName() {
            return NAME;
        }

        @Override
        public int getFrameSize() {
            return ZEPHYR_HXM_BYTE_ETX + 1;
        }

        @Override
        public int parseBuffer(byte[] buffer) {
            // Check STX (Start of Text), ETX (End of Text) and CRC Checksum
            boolean ok = buffer.length > ZEPHYR_HXM_BYTE_ETX
                    && getByte(buffer[ZEPHYR_HXM_BYTE_STX]) == ZEPHYR_START_BYTE
                    && getByte(buffer[ZEPHYR_HXM_BYTE_ETX]) == ZEPHYR_END_BYTE
                    && calcCrc8(buffer, 3, 55) == getByte(buffer[ZEPHYR_HXM_BYTE_CRC]);

            if (!ok) {
                System.err.println("HxM insanity! "
                        + (buffer.length > ZEPHYR_HXM_BYTE_ETX) + " "
                        + getByte(buffer[ZEPHYR_HXM_BYTE_STX]) + "=="
                        + ZEPHYR_START_BYTE + " "
                        + getByte(buffer[ZEPHYR_HXM_BYTE_ETX]) + "=="
                        + ZEPHYR_END_BYTE + " " + "calc="
                        + calcCrc8(buffer, 3, 55) + " " + "given="
                        + getByte(buffer[ZEPHYR_HXM_BYTE_CRC]));
                return -1;
            }

            return getByte(buffer[ZEPHYR_HXM_BYTE_HR]);
        }

        @Override
        public int findNextAlignment(byte[] buffer) {
            for (int i = 0; i < buffer.length - 1; i++) {
                if (getByte(buffer[i]) == ZEPHYR_END_BYTE &&
                        getByte(buffer[i + 1]) == ZEPHYR_START_BYTE) {
                    return i;
                }
            }
            return -1;
        }

        private static int calcCrc8(byte buffer[], int start, int length) {
            int crc = 0x0;

            for (int i = start; i < (start + length); i++) {
                crc ^= getByte(buffer[i]);
                for (int b = 0; b <= 7; b++) {
                    if ((crc & 1) != 0) {
                        crc = ((crc >> 1) ^ 0x8c);
                    } else {
                        crc = (crc >> 1);
                    }
                }
            }
            return crc;
        }

    }

    public static class PolarHRM extends Bt20Base {

        public static final String NAME = "Polar WearLink";

        public PolarHRM(Context ctx) {
            super(ctx);
        }

        @Override
        public String getName() {
            return NAME;
        }

        @Override
        public String getProviderName() {
            return NAME;
        }

        @Override
        public int getFrameSize() {
            return 16;
        }

        private boolean startOfMessage(byte buffer[], int bytesInBuffer, int pos) {
            if (bytesInBuffer < pos + 4)
                return false;

            int b0 = getByte(buffer[pos + 0]);
            int b1 = getByte(buffer[pos + 1]); // len
            int b2 = getByte(buffer[pos + 2]);
            int b3 = getByte(buffer[pos + 3]);
            if (b0 != 0xFE) {
                return false;
            }

            if ((0xFF - b1) != b2) {
                return false;
            }

            if (b3 >= 16) {
                return false;
            }

            if (bytesInBuffer < pos + b1)
                return false;

            return true;
        }

        @Override
        public int parseBuffer(byte[] buffer, int bytesInBuffer, Integer hrVal[]) {
            hrVal[0] = null;
            for (int i = 0; i < bytesInBuffer; i++) {
                if (startOfMessage(buffer, bytesInBuffer, i)) {
                    int bytesUsed = getByte(buffer[i + 1]);
                    hrVal[0] = Integer.valueOf(getByte(buffer[i + 5]));
                    return bytesUsed;
                }
            }
            return 0;
        }
    }

    static public int getByte(byte b) {
        return b & 0xFF;
    }

    public static HRDeviceRef createDeviceRef(String providerName, BluetoothDevice device) {
        return HRDeviceRef.create(providerName, device.getName(), device.getAddress());
    }
}