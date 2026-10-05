package com.niimbot.preplabels;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class NiimbotB1Printer {

    public static final UUID SERVICE_UUID = UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2");
    public static final UUID CHAR_UUID = UUID.fromString("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f");
    public static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public interface PrinterListener {
        void onConnectionStateChange(boolean connected, String deviceName);
        void onPrintProgress(String status);
        void onError(String message);
    }

    private final Context context;
    private final Handler mainHandler;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic printCharacteristic;
    private PrinterListener listener;
    private boolean isConnected = false;
    private String connectedDeviceName = "";

    // Track printer progress responses
    private final AtomicInteger lastReportedPage = new AtomicInteger(0);
    private final AtomicBoolean printComplete = new AtomicBoolean(false);

    public static final int HEAD_WIDTH = 384;
    public static final int LABEL_WIDTH = 320;
    public static final int LABEL_HEIGHT = 240;
    public static final int OFFSET_X = (HEAD_WIDTH - LABEL_WIDTH) / 2;

    public NiimbotB1Printer(Context context, PrinterListener listener) {
        this.context = context;
        this.listener = listener;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    public boolean isConnected() { return isConnected; }
    public String getConnectedDeviceName() { return connectedDeviceName; }

    public void startScanAndConnect() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            notifyError("Bluetooth is not enabled");
            return;
        }

        BluetoothLeScanner scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            notifyError("BLE Scanner unavailable");
            return;
        }

        ScanCallback scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice device = result.getDevice();
                String name = device.getName();
                if (name != null && (name.startsWith("B1") || name.startsWith("NIIMBOT") || name.startsWith("B"))) {
                    try { scanner.stopScan(this); } catch (Exception ignored) {}
                    connectToDevice(device);
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                notifyError("Scan failed: code " + errorCode);
            }
        };

        scanner.startScan(scanCallback);
        mainHandler.postDelayed(() -> {
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
            if (!isConnected) {
                notifyConnection(false, "");
            }
        }, 8000);
    }

    private void connectToDevice(BluetoothDevice device) {
        bluetoothGatt = device.connectGatt(context, false, gattCallback);
    }

    public void disconnect() {
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
            } catch (Exception ignored) {}
            bluetoothGatt = null;
        }
        isConnected = false;
        connectedDeviceName = "";
        notifyConnection(false, "");
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                    gatt.requestMtu(512);
                } else {
                    gatt.discoverServices();
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false;
                connectedDeviceName = "";
                notifyConnection(false, "");
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            // Once MTU negotiation completes, discover services
            gatt.discoverServices();
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service = gatt.getService(SERVICE_UUID);
                if (service != null) {
                    printCharacteristic = service.getCharacteristic(CHAR_UUID);
                    if (printCharacteristic == null) {
                        for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                            printCharacteristic = c;
                            break;
                        }
                    }
                }

                if (printCharacteristic != null) {
                    // Subscribe to BLE notifications to receive printer responses
                    gatt.setCharacteristicNotification(printCharacteristic, true);
                    BluetoothGattDescriptor descriptor = printCharacteristic.getDescriptor(CCCD_UUID);
                    if (descriptor != null) {
                        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        gatt.writeDescriptor(descriptor);
                    } else {
                        finishConnectionSetup(gatt);
                    }
                } else {
                    notifyError("Niimbot print characteristic not found");
                }
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            finishConnectionSetup(gatt);
        }

        private void finishConnectionSetup(BluetoothGatt gatt) {
            isConnected = true;
            connectedDeviceName = gatt.getDevice().getName();
            if (connectedDeviceName == null || connectedDeviceName.isEmpty()) {
                connectedDeviceName = "Niimbot B1";
            }
            sendPacket(0xC1, new byte[]{0x01});
            notifyConnection(true, connectedDeviceName);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            if (value == null || value.length < 5) return;
            // Parse response packet: 55 55 [CMD] [LEN] [DATA...] [CS] AA AA
            if (value[0] == (byte) 0x55 && value[1] == (byte) 0x55) {
                int cmd = value[2] & 0xFF;
                int len = value[3] & 0xFF;
                if (cmd == 0xB3 && len >= 2 && value.length >= 6) { // In_PrintStatus
                    int pageIndex = ((value[4] & 0xFF) << 8) | (value[5] & 0xFF);
                    lastReportedPage.set(pageIndex);
                    if (pageIndex >= 1) {
                        printComplete.set(true);
                    }
                } else if (cmd == 0xF4) { // In_PrintEnd
                    printComplete.set(true);
                } else if (cmd == 0xDB) { // In_PrintError
                    int errCode = (len > 0 && value.length > 4) ? (value[4] & 0xFF) : -1;
                    notifyError("Printer error: code " + errCode);
                }
            }
        }
    };

    private byte[] makePacket(int cmd, byte[] data) {
        if (data == null) data = new byte[0];
        int cs = cmd ^ data.length;
        for (byte b : data) cs ^= (b & 0xFF);
        byte[] pkt;
        if (cmd == 0xC1) {
            pkt = new byte[data.length + 8];
            pkt[0] = 0x03;
            pkt[1] = 0x55;
            pkt[2] = 0x55;
            pkt[3] = (byte) cmd;
            pkt[4] = (byte) data.length;
            System.arraycopy(data, 0, pkt, 5, data.length);
            pkt[pkt.length - 3] = (byte) (cs & 0xFF);
            pkt[pkt.length - 2] = (byte) 0xAA;
            pkt[pkt.length - 1] = (byte) 0xAA;
        } else {
            pkt = new byte[data.length + 7];
            pkt[0] = 0x55;
            pkt[1] = 0x55;
            pkt[2] = (byte) cmd;
            pkt[3] = (byte) data.length;
            System.arraycopy(data, 0, pkt, 4, data.length);
            pkt[pkt.length - 3] = (byte) (cs & 0xFF);
            pkt[pkt.length - 2] = (byte) 0xAA;
            pkt[pkt.length - 1] = (byte) 0xAA;
        }
        return pkt;
    }

    private synchronized void sendPacket(int cmd, byte[] data) {
        if (bluetoothGatt == null || printCharacteristic == null) return;
        byte[] pkt = makePacket(cmd, data);
        printCharacteristic.setValue(pkt);
        printCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        boolean sent = bluetoothGatt.writeCharacteristic(printCharacteristic);
        int retries = 0;
        while (!sent && retries < 10) {
            try { Thread.sleep(5); } catch (Exception ignored) {}
            sent = bluetoothGatt.writeCharacteristic(printCharacteristic);
            retries++;
        }
    }

    public Bitmap generateLabelBitmap(String name, int days, String durationLabel) {
        Bitmap bitmap = Bitmap.createBitmap(HEAD_WIDTH, LABEL_HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.BLACK);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.CENTER);

        float textSize = 32f;
        textPaint.setTextSize(textSize);
        while (textPaint.measureText(name) > (LABEL_WIDTH - 24) && textSize > 18) {
            textSize -= 2f;
            textPaint.setTextSize(textSize);
        }
        canvas.drawText(name, HEAD_WIDTH / 2f, 48, textPaint);

        Paint linePaint = new Paint();
        linePaint.setColor(Color.BLACK);
        linePaint.setStrokeWidth(2.5f);
        canvas.drawLine(OFFSET_X + 10, 70, OFFSET_X + LABEL_WIDTH - 10, 70, linePaint);

        SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yyyy", Locale.US);
        Date now = new Date();
        Calendar cal = Calendar.getInstance();
        cal.setTime(now);
        cal.add(Calendar.DAY_OF_YEAR, days);
        Date outDate = cal.getTime();

        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTextSize(24f);
        canvas.drawText("Prep Date: " + sdf.format(now), OFFSET_X + 16, 125, textPaint);

        textPaint.setTextSize(26f);
        canvas.drawText("Out Date: " + sdf.format(outDate), OFFSET_X + 16, 180, textPaint);

        return bitmap;
    }

    public void printLabel(Bitmap bitmap) {
        if (!isConnected || bluetoothGatt == null || printCharacteristic == null) {
            notifyError("Printer not connected");
            return;
        }

        new Thread(() -> {
            try {
                notifyProgress("PRINTING");
                lastReportedPage.set(0);
                printComplete.set(false);

                int width = bitmap.getWidth();   // 384 (printhead width)
                int height = bitmap.getHeight(); // 240 (feed length)

                // 1. SetDensity: 3 (standard)
                sendPacket(0x21, new byte[]{0x03});
                Thread.sleep(20);

                // 2. SetLabelType: 1 (gapped label)
                sendPacket(0x23, new byte[]{0x01});
                Thread.sleep(20);

                // 3. PrintStart: 7 bytes [totalPages(u16), 0, 0, 0, 0, pageColor]
                sendPacket(0x01, new byte[]{0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00});
                Thread.sleep(30);

                // 4. PageStart: [0x01]
                sendPacket(0x03, new byte[]{0x01});
                Thread.sleep(20);

                // 5. SetPageSize: 6 bytes [rows(u16), cols(u16), copies(u16)]
                byte hHi = (byte) ((height >> 8) & 0xFF);
                byte hLo = (byte) (height & 0xFF);
                byte wHi = (byte) ((width >> 8) & 0xFF);
                byte wLo = (byte) (width & 0xFF);
                sendPacket(0x13, new byte[]{hHi, hLo, wHi, wLo, 0x00, 0x01});
                Thread.sleep(30);

                // 6. Send bitmap rows (0x85)
                int bytesPerRow = width / 8; // 48 bytes
                int[] pixels = new int[width * height];
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

                for (int y = 0; y < height; y++) {
                    byte[] rowBytes = new byte[bytesPerRow];
                    int offset = y * width;

                    for (int x = 0; x < width; x++) {
                        int pixel = pixels[offset + x];
                        int r = (pixel >> 16) & 0xFF;
                        int g = (pixel >> 8) & 0xFF;
                        int b = pixel & 0xFF;
                        int lum = (int) (0.299 * r + 0.587 * g + 0.114 * b);

                        if (lum < 128) {
                            rowBytes[x / 8] |= (byte) (1 << (7 - (x % 8)));
                        }
                    }

                    // Count black pixels in 3 chunks (Split Mode: 16 bytes each for 384 px)
                    int c1 = 0, c2 = 0, c3 = 0;
                    for (int x = 0; x < 128; x++) {
                        if ((rowBytes[x / 8] & (1 << (7 - (x % 8)))) != 0) c1++;
                    }
                    for (int x = 128; x < 256; x++) {
                        if ((rowBytes[x / 8] & (1 << (7 - (x % 8)))) != 0) c2++;
                    }
                    for (int x = 256; x < 384; x++) {
                        if ((rowBytes[x / 8] & (1 << (7 - (x % 8)))) != 0) c3++;
                    }

                    byte yHi = (byte) ((y >> 8) & 0xFF);
                    byte yLo = (byte) (y & 0xFF);

                    byte[] payload = new byte[6 + bytesPerRow];
                    payload[0] = yHi;
                    payload[1] = yLo;
                    payload[2] = (byte) Math.min(255, c1);
                    payload[3] = (byte) Math.min(255, c2);
                    payload[4] = (byte) Math.min(255, c3);
                    payload[5] = 0x01; // repeat count
                    System.arraycopy(rowBytes, 0, payload, 6, bytesPerRow);

                    sendPacket(0x85, payload);
                    // Inter-row pacing
                    Thread.sleep(4);
                }

                // 7. PageEnd: page data complete in printer buffer
                Thread.sleep(30);
                sendPacket(0xE3, new byte[]{0x01});

                // 8. Poll PrintStatus (0xA3) until printer finishes physical printing
                int pollAttempts = 0;
                while (!printComplete.get() && pollAttempts < 25) {
                    Thread.sleep(200);
                    sendPacket(0xA3, new byte[]{0x01});
                    pollAttempts++;
                    if (lastReportedPage.get() >= 1) {
                        break;
                    }
                }

                // Fallback wait for physical motor to finish feeding sticker
                if (pollAttempts < 5) {
                    Thread.sleep(800);
                }

                // 9. PrintEnd: finalize print job
                sendPacket(0xF3, new byte[]{0x01});
                Thread.sleep(200);

                notifyProgress("DONE");

            } catch (Exception e) {
                notifyProgress("DONE");
                notifyError("Printing failed: " + e.getMessage());
            }
        }).start();
    }

    private void notifyConnection(boolean connected, String name) {
        mainHandler.post(() -> {
            if (listener != null) listener.onConnectionStateChange(connected, name);
        });
    }

    private void notifyProgress(String status) {
        mainHandler.post(() -> {
            if (listener != null) listener.onPrintProgress(status);
        });
    }

    private void notifyError(String err) {
        mainHandler.post(() -> {
            if (listener != null) listener.onError(err);
        });
    }
}