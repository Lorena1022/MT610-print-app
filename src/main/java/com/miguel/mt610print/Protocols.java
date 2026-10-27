package com.miguel.mt610print;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Printer command languages. HPRT doesn't publish which one the MT610 uses,
 * so the app can switch between the four common thermal-printer languages.
 * All of them take a 1-bit image: {@code bits} holds rows of
 * {@code widthBytes} bytes, MSB = leftmost pixel, 1 = black.
 */
public final class Protocols {
    public static final int ESCPOS_BANDS = 0;
    public static final int ESCPOS_BLOCK = 1;
    public static final int TSPL = 2;
    public static final int CPCL = 3;

    public static final String[] NAMES = {
            "1 - ESC/POS raster (bands)",
            "2 - ESC/POS raster (one block)",
            "3 - TSPL bitmap",
            "4 - CPCL graphics",
    };

    public static final String[] PAGE_END = {
            "Nothing (image is already full page)",
            "Feed 1 inch after each page",
            "Form feed (jump to next page mark)",
    };

    public static final String[] SPEEDS = {
            "Normal (fastest)",
            "Slow - darker, more even",
            "Slowest - best quality",
    };
    /** Max print rate in dot rows per second for each speed (0 = no limit). */
    private static final int[] ROWS_PER_SEC = {0, 64, 32};

    public static final String[] HEAT = {
            "Printer default",
            "Extra heat - ESC/POS style A",
            "Extra heat - ESC/POS style B",
    };

    private Protocols() {}

    /**
     * Bytes per second to send at the chosen speed, or 0 for no limit.
     * The printer can only print rows it has received, so limiting the data
     * rate (in small blocks) makes the paper move slower and gives each dot
     * row more heat time.
     */
    public static int bytesPerSecond(Settings s) {
        int rows = ROWS_PER_SEC[s.speed];
        return rows == 0 ? 0 : rows * (s.widthDots / 8) * (s.dpi >= 300 ? 3 : 2) / 2;
    }

    /** Rows per ESC/POS raster block: small blocks when slowed down. */
    static int bandFor(Settings s) {
        return s.speed > 0 ? 8 : s.bandLines;
    }

    /** Bytes sent once when the Bluetooth connection opens. */
    public static byte[] jobStart(Settings s) {
        if (s.protocol == ESCPOS_BANDS || s.protocol == ESCPOS_BLOCK) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            o.write(0x1B);
            o.write(0x40);                               // ESC @  initialize
            if (s.heat == 1) {
                // GS ( K fn=49: print density, +6 = darkest
                o.write(new byte[]{0x1D, 0x28, 0x4B, 0x02, 0x00, 0x31, 0x06}, 0, 7);
                // GS ( K fn=50: print speed, 1 = slowest (only when slowed down)
                if (s.speed > 0) {
                    o.write(new byte[]{0x1D, 0x28, 0x4B, 0x02, 0x00, 0x32, (byte) (s.speed == 2 ? 1 : 4)}, 0, 7);
                }
            } else if (s.heat == 2) {
                // ESC 7 n1 n2 n3: fewer dots heated at once, longer heat time, longer interval
                o.write(new byte[]{0x1B, 0x37, 0x05, (byte) 200, (byte) (s.speed > 0 ? 40 : 10)}, 0, 5);
            }
            return o.toByteArray();
        }
        return new byte[0];
    }

    /** Encodes one page. */
    public static byte[] page(Settings s, byte[] bits, int widthBytes, int height) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(bits.length + 256);
        switch (s.protocol) {
            case TSPL:
                tspl(o, s, bits, widthBytes, height);
                break;
            case CPCL:
                cpcl(o, s, bits, widthBytes, height);
                break;
            case ESCPOS_BLOCK:
                escPos(o, s, bits, widthBytes, height, height);
                break;
            case ESCPOS_BANDS:
            default:
                escPos(o, s, bits, widthBytes, height, bandFor(s));
                break;
        }
        return o.toByteArray();
    }

    // ---------------------------------------------------------------- ESC/POS

    /** GS v 0 : print raster bit image, split into blocks of {@code band} rows. */
    private static void escPos(ByteArrayOutputStream o, Settings s, byte[] bits,
                               int wb, int h, int band) {
        for (int y = 0; y < h; y += band) {
            int rows = Math.min(band, h - y);
            o.write(0x1D);
            o.write(0x76);
            o.write(0x30);
            o.write(0x00);                    // normal size
            o.write(wb & 0xFF);
            o.write((wb >> 8) & 0xFF);
            o.write(rows & 0xFF);
            o.write((rows >> 8) & 0xFF);
            o.write(bits, y * wb, rows * wb);
        }
        if (s.pageEnd == 1) {
            int dots = s.dpi;                 // 1 inch
            while (dots > 0) {
                int n = Math.min(255, dots);
                o.write(0x1B);
                o.write(0x4A);                // ESC J n : feed n dots
                o.write(n);
                dots -= n;
            }
        } else if (s.pageEnd == 2) {
            o.write(0x0C);                    // FF
        }
    }

    // ------------------------------------------------------------------- TSPL

    private static void tspl(ByteArrayOutputStream o, Settings s, byte[] bits, int wb, int h) {
        double mmW = wb * 8 * 25.4 / s.dpi;
        double mmH = h * 25.4 / s.dpi;
        ascii(o, String.format(Locale.US,
                "SIZE %.1f mm,%.1f mm\r\nGAP %s\r\nDIRECTION 0\r\nCLS\r\nBITMAP 0,0,%d,%d,0,",
                mmW, mmH, s.pageEnd == 2 ? "3 mm,0 mm" : "0 mm,0 mm", wb, h));
        // TSPL bitmaps use 0 = black, so invert.
        byte[] inv = new byte[wb * h];
        for (int i = 0; i < inv.length; i++) inv[i] = (byte) ~bits[i];
        o.write(inv, 0, inv.length);
        ascii(o, "\r\nPRINT 1,1\r\n");
        if (s.pageEnd == 1) ascii(o, String.format(Locale.US, "FEED %d\r\n", s.dpi));
    }

    // ------------------------------------------------------------------- CPCL

    private static void cpcl(ByteArrayOutputStream o, Settings s, byte[] bits, int wb, int h) {
        int res = s.dpi >= 300 ? 300 : 200;
        ascii(o, String.format(Locale.US,
                "! 0 %d %d %d 1\r\nPAGE-WIDTH %d\r\nCG %d %d 0 0 ", res, res, h, wb * 8, wb, h));
        o.write(bits, 0, wb * h);             // CG = binary graphics, 1 = black
        ascii(o, "\r\n");
        if (s.pageEnd == 2) ascii(o, "FORM\r\n");
        ascii(o, "PRINT\r\n");
        if (s.pageEnd == 1) {
            o.write(0x1B);
            o.write(0x4A);
            o.write(Math.min(255, s.dpi));
        }
    }

    private static void ascii(ByteArrayOutputStream o, String text) {
        byte[] b = text.getBytes(StandardCharsets.US_ASCII);
        o.write(b, 0, b.length);
    }
}
