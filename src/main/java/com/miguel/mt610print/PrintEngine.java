package com.miguel.mt610print;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.graphics.Bitmap;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Opens a Bluetooth serial link to the printer and streams encoded pages to it. */
public final class PrintEngine {
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int CHUNK = 512;

    public interface Progress {
        void onStatus(String message);
    }

    private PrintEngine() {}

    public static void print(Context ctx, Settings s, Raster.PageSource src, int copies,
                             Progress progress, AtomicBoolean cancel) throws IOException {
        if (s.mac == null) throw new IOException("No printer picked yet. Open the MT610 Print app and choose it.");
        copies = Math.max(1, copies);
        progress.onStatus("Connecting to " + (s.name != null ? s.name : s.mac) + "...");
        BluetoothSocket socket = connect(ctx, s.mac);
        try {
            OutputStream out = socket.getOutputStream();
            write(out, Protocols.jobStart(s), s, cancel);
            int total = src.count() * copies;
            int done = 0;
            for (int c = 0; c < copies; c++) {
                for (int i = 0; i < src.count(); i++) {
                    if (cancel != null && cancel.get()) throw new IOException("Cancelled");
                    done++;
                    progress.onStatus("Sending page " + done + " of " + total + "...");
                    Bitmap bmp = src.render(i, s.widthDots);
                    Raster.Packed packed = ThermalProcessor.process(bmp, s.mode, s.darkness, s.bold);
                    bmp.recycle();
                    write(out, Protocols.page(s, packed.bits, packed.widthBytes, packed.height), s, cancel);
                }
            }
            out.flush();
            progress.onStatus("Finishing...");
            // Give the printer time to drain its buffer before the link drops,
            // otherwise the last part of the page can be lost.
            sleep(2500);
            progress.onStatus("Done");
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void write(OutputStream out, byte[] data, Settings s, AtomicBoolean cancel)
            throws IOException {
        for (int off = 0; off < data.length; off += CHUNK) {
            if (cancel != null && cancel.get()) throw new IOException("Cancelled");
            out.write(data, off, Math.min(CHUNK, data.length - off));
            if (s.chunkDelayMs > 0) sleep(s.chunkDelayMs);
        }
        out.flush();
    }

    private static BluetoothSocket connect(Context ctx, String mac) throws IOException {
        BluetoothManager bm = ctx.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null) throw new IOException("This phone has no Bluetooth");
        try {
            if (!adapter.isEnabled()) throw new IOException("Bluetooth is turned off");
            try {
                adapter.cancelDiscovery();
            } catch (SecurityException ignored) {
                // BLUETOOTH_SCAN isn't requested; not needed when not scanning.
            }
            BluetoothDevice device = adapter.getRemoteDevice(mac);
            IOException last = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                BluetoothSocket sock = null;
                try {
                    if (attempt == 0) {
                        sock = device.createRfcommSocketToServiceRecord(SPP);
                    } else if (attempt == 1) {
                        sock = device.createInsecureRfcommSocketToServiceRecord(SPP);
                    } else {
                        Method m = device.getClass().getMethod("createRfcommSocket", int.class);
                        sock = (BluetoothSocket) m.invoke(device, 1);
                    }
                    sock.connect();
                    return sock;
                } catch (IOException e) {
                    last = e;
                    closeQuietly(sock);
                    sleep(300);
                } catch (ReflectiveOperationException e) {
                    last = new IOException("Fallback connect failed: " + e);
                }
            }
            throw new IOException("Couldn't connect to the printer. Is it on and not connected to another phone? ("
                    + (last == null ? "unknown" : last.getMessage()) + ")");
        } catch (SecurityException e) {
            throw new IOException("Bluetooth permission missing. Open the MT610 Print app and allow Nearby devices.");
        }
    }

    private static void closeQuietly(BluetoothSocket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
