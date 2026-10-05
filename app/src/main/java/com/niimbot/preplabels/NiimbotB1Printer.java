package com.niimbot.preplabels;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
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

public class NiimbotB1Printer {

    public static final UUID SERVICE_UUID = UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2");
    public static final UUID CHAR_UUID = UUID.fromString("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f");

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
                    scanner.stopScan(this);
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
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
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
                    gatt.requestMtu(512);
                }
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false;
                connectedDeviceName = "";
                notifyConnection(false, "");
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
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
                    isConnected = true;
                    connectedDeviceName = gatt.getDevice().getName();
                    if (connectedDeviceName == null) connectedDeviceName = "Niimbot B1";

                    sendPacket(0xC1, new byte[]{0x01});
                    notifyConnection(true, connectedDeviceName);
                } else {
                    notifyError("Niimbot print characteristic not found");
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

    private void sendPacket(int cmd, byte[] data) {
        if (bluetoothGatt == null || printCharacteristic == null) return;
        byte[] pkt = makePacket(cmd, data);

        for (int i = 0; i < pkt.length; i += 20) {
            int len = Math.min(20, pkt.length - i);
            byte[] chunk = new byte[len];
            System.arraycopy(pkt, i, chunk, 0, len);
            printCharacteristic.setValue(chunk);
            printCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            bluetoothGatt.writeCharacteristic(printCharacteristic);
            try { Thread.sleep(2); } catch (Exception ignored) {}
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
        canvas.drawText("Out Date:  " + sdf.format(outDate), OFFSET_X + 16, 180, textPaint);

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

                int width = bitmap.getWidth();
                int height = bitmap.getHeight();

                sendPacket(0x21, new byte[]{0x04});
                Thread.sleep(15);

                sendPacket(0x23, new byte[]{0x01});
                Thread.sleep(15);

                sendPacket(0x01, new byte[]{0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00});
                Thread.sleep(25);

                sendPacket(0x03, new byte[]{0x01});
                Thread.sleep(15);

                byte hHi = (byte) ((height >> 8) & 0xFF);
                byte hLo = (byte) (height & 0xFF);
                byte wHi = (byte) ((width >> 8) & 0xFF);
                byte wLo = (byte) (width & 0xFF);
                sendPacket(0x13, new byte[]{hHi, hLo, wHi, wLo, 0x00, 0x01});
                Thread.sleep(25);

                int bytesPerRow = width / 8;
                int[] pixels = new int[width * height];
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

                for (int y = 0; y < height; y++) {
                    byte[] rowBytes = new byte[bytesPerRow];
                    int blackCount = 0;
                    int offset = y * width;

                    for (int x = 0; x < width; x++) {
                        int pixel = pixels[offset + x];
                        int r = (pixel >> 16) & 0xFF;
                        int g = (pixel >> 8) & 0xFF;
                        int b = pixel & 0xFF;
                        int lum = (int) (0.299 * r + 0.587 * g + 0.114 * b);

                        if (lum < 128) {
                            rowBytes[x / 8] |= (byte) (1 << (7 - (x % 8)));
                            blackCount++;
                        }
                    }

                    byte yHi = (byte) ((y >> 8) & 0xFF);
                    byte yLo = (byte) (y & 0xFF);

                    byte c2 = (byte) (blackCount & 0xFF);
                    byte c3 = (byte) ((blackCount >> 8) & 0xFF);
                    byte[] payload = new byte[6 + bytesPerRow];
                    payload[0] = yHi;
                    payload[1] = yLo;
                    payload[2] = 0x00;
                    payload[3] = c2;
                    payload[4] = c3;
                    payload[5] = 0x01;
                    System.arraycopy(rowBytes, 0, payload, 6, bytesPerRow);
                    sendPacket(0x85, payload);

                    Thread.sleep(5);
                }

                Thread.sleep(25);
                sendPacket(0xE3, new byte[]{0x01});

                Thread.sleep(1100);
                sendPacket(0xF3, new byte[]{0x01});

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
