package com.miguel.mt610print;

import android.content.Context;
import android.content.SharedPreferences;

/** All user-adjustable printer settings, stored in SharedPreferences. */
public final class Settings {
    public static final int DPI_203 = 203;
    public static final int DPI_300 = 300;

    public String mac;          // Bluetooth address of the printer
    public String name;         // Bluetooth name, e.g. MT610-25E2
    public int protocol;        // index into Protocols.NAMES
    public int dpi;             // 203 (MT610) or 300 (MT610 Pro)
    public int widthDots;       // printable width in dots (multiple of 8)
    public int bandLines;       // rows per ESC/POS raster block
    public int pageEnd;         // index into Protocols.PAGE_END
    public int mode;            // ThermalProcessor.AUTO / DOCUMENT / PHOTO / SIMPLE
    public boolean bold;        // thicken every black dot by one pixel
    public int darkness;        // 30..230, 128 = normal
    public int chunkDelayMs;    // pause between 512-byte Bluetooth writes
    public int speed;           // index into Protocols.SPEEDS (0 = normal)
    public int heat;            // index into Protocols.HEAT (0 = printer default)

    private Settings() {}

    public static Settings load(Context c) {
        SharedPreferences p = prefs(c);
        Settings s = new Settings();
        s.mac = p.getString("mac", null);
        s.name = p.getString("name", null);
        s.protocol = p.getInt("protocol", 0);
        s.dpi = p.getInt("dpi", DPI_203);
        s.widthDots = p.getInt("widthDots", defaultWidth(s.dpi));
        s.bandLines = p.getInt("bandLines", 128);
        s.pageEnd = p.getInt("pageEnd", 0);
        s.mode = p.getInt("procMode", ThermalProcessor.AUTO);
        s.bold = p.getBoolean("bold", false);
        s.darkness = p.getInt("darkness", ThermalProcessor.NEUTRAL);
        s.chunkDelayMs = p.getInt("chunkDelayMs", 5);
        s.speed = p.getInt("speed", 0);
        s.heat = p.getInt("heat", 0);
        s.sanitize();
        return s;
    }

    public void save(Context c) {
        sanitize();
        prefs(c).edit()
                .putString("mac", mac)
                .putString("name", name)
                .putInt("protocol", protocol)
                .putInt("dpi", dpi)
                .putInt("widthDots", widthDots)
                .putInt("bandLines", bandLines)
                .putInt("pageEnd", pageEnd)
                .putInt("procMode", mode)
                .putBoolean("bold", bold)
                .putInt("darkness", darkness)
                .putInt("chunkDelayMs", chunkDelayMs)
                .putInt("speed", speed)
                .putInt("heat", heat)
                .apply();
    }

    /**
     * Full 216 mm (8.5 in) print head width. At 203 dpi that's the standard
     * 1728-dot head (216 bytes per row); at 300 dpi, 2544 dots.
     */
    public static int defaultWidth(int dpi) {
        return dpi == DPI_300 ? 2544 : 1728;
    }

    private void sanitize() {
        if (dpi != DPI_300) dpi = DPI_203;
        if (widthDots < 64 || widthDots > 4096) widthDots = dpi == DPI_300 ? 2544 : 1728;
        widthDots -= widthDots % 8;
        if (protocol < 0 || protocol >= Protocols.NAMES.length) protocol = 0;
        if (pageEnd < 0 || pageEnd >= Protocols.PAGE_END.length) pageEnd = 0;
        if (bandLines < 8 || bandLines > 4096) bandLines = 128;
        if (darkness < 30 || darkness > 230) darkness = ThermalProcessor.NEUTRAL;
        if (mode < 0 || mode >= ThermalProcessor.MODES.length) mode = ThermalProcessor.AUTO;
        if (chunkDelayMs < 0 || chunkDelayMs > 200) chunkDelayMs = 5;
        if (speed < 0 || speed >= Protocols.SPEEDS.length) speed = 0;
        if (heat < 0 || heat >= Protocols.HEAT.length) heat = 0;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("mt610", Context.MODE_PRIVATE);
    }
}
