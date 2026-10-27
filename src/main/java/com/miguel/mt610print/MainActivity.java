package com.miguel.mt610print;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Setup screen: pick the printer, find the right mode with test prints, print files. */
public class MainActivity extends Activity {
    private static final int REQ_BT = 1;
    private static final int REQ_FILE = 2;

    private Settings settings;
    private final List<String[]> devices = new ArrayList<>();   // {name, mac}
    private Spinner deviceSpinner;
    private TextView status;
    private TextView log;
    private EditText widthField;
    private TextView darknessLabel;
    private TextView previewInfo;
    private ImageView preview;
    private Button printLoaded;
    private ScrollView scrollView;
    private File currentFile;
    private boolean currentPdf;
    private final java.util.concurrent.ExecutorService previewWorker =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicInteger previewGen =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean busy;
    private Uri pendingShare;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        settings = Settings.load(this);
        setContentView(buildUi());
        ensurePermission();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDevices();
    }

    // ------------------------------------------------------------------ UI

    private View buildUi() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("MT610 Print");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        status = new TextView(this);
        status.setPadding(0, dp(4), 0, dp(12));
        root.addView(status);

        // --- printer
        root.addView(header("1. Printer"));
        root.addView(hint("Pair the printer in Android Bluetooth settings first if it isn't listed."));
        deviceSpinner = new Spinner(this);
        root.addView(deviceSpinner);
        deviceSpinner.setOnItemSelectedListener(new SimpleSelect(pos -> {
            if (pos < devices.size()) {
                settings.name = devices.get(pos)[0];
                settings.mac = devices.get(pos)[1];
                settings.save(this);
                updateStatus();
            }
        }));
        Button btSettings = button("Open Bluetooth settings", v ->
                startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)));
        root.addView(btSettings);

        // --- mode
        root.addView(header("2. Find the mode that works"));
        root.addView(hint("Pick a mode and tap Print test. If the strip reads \"MODE n WORKS\", you're done. "
                + "If it prints junk or nothing, turn the printer off and on, pick the next mode and try again."));
        Spinner modeSpinner = spinner(Protocols.NAMES, settings.protocol, pos -> {
            settings.protocol = pos;
            settings.save(this);
        });
        root.addView(modeSpinner);
        root.addView(button("Print test", v -> printTest()));

        // --- page
        root.addView(header("3. Paper & quality"));
        root.addView(label("Printer resolution"));
        root.addView(spinner(new String[]{"203 dpi (MT610)", "300 dpi (MT610 Pro)"},
                settings.dpi == Settings.DPI_300 ? 1 : 0, pos -> {
                    int dpi = pos == 1 ? Settings.DPI_300 : Settings.DPI_203;
                    if (dpi != settings.dpi) {
                        settings.dpi = dpi;
                        settings.widthDots = Settings.defaultWidth(dpi);
                        widthField.setText(String.valueOf(settings.widthDots));
                        settings.save(this);
                    }
                }));
        root.addView(label("Print width in dots (8.5\" Letter = 1728 at 203 dpi)"));
        widthField = new EditText(this);
        widthField.setInputType(InputType.TYPE_CLASS_NUMBER);
        widthField.setText(String.valueOf(settings.widthDots));
        root.addView(widthField);

        root.addView(label("After each page"));
        root.addView(spinner(Protocols.PAGE_END, settings.pageEnd, pos -> {
            settings.pageEnd = pos;
            settings.save(this);
        }));

        root.addView(header("Print quality"));
        root.addView(label("Processing"));
        root.addView(spinner(ThermalProcessor.MODES, settings.mode, pos -> {
            if (settings.mode != pos) {
                settings.mode = pos;
                settings.save(this);
                refreshPreview();
            }
        }));
        root.addView(hint("Auto cleans up shadows and paper color, makes faded text solid, "
                + "turns gray shading into a light dot pattern and dithers photos."));

        CheckBox bold = new CheckBox(this);
        bold.setText("Extra bold (thicker lines and text)");
        bold.setChecked(settings.bold);
        bold.setOnCheckedChangeListener((b, on) -> {
            settings.bold = on;
            settings.save(this);
            refreshPreview();
        });
        root.addView(bold);

        root.addView(label("Print speed"));
        root.addView(spinner(Protocols.SPEEDS, settings.speed, pos -> {
            settings.speed = pos;
            settings.save(this);
        }));
        root.addView(hint("Slower printing gives each line more heat, so small or busy text comes out "
                + "darker and more even. A full page takes about 35 s on Slow and 70 s on Slowest."));
        root.addView(label("Printer heat (experimental)"));
        root.addView(spinner(Protocols.HEAT, settings.heat, pos -> {
            settings.heat = pos;
            settings.save(this);
        }));
        root.addView(hint("Asks the printer itself to burn darker. HPRT doesn't publish the MT610's "
                + "command, so this tries two common ones. Tap Print test after changing it - if the "
                + "strip prints stray characters or nothing changes, set it back to Printer default."));

        darknessLabel = label("");
        root.addView(darknessLabel);
        SeekBar darkness = new SeekBar(this);
        darkness.setMax(200);
        darkness.setProgress(settings.darkness - 30);
        updateDarknessLabel();
        darkness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                settings.darkness = p + 30;
                updateDarknessLabel();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {}

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                settings.save(MainActivity.this);
                refreshPreview();
            }
        });
        root.addView(darkness);
        root.addView(button("Reset darkness to normal", v -> {
            settings.darkness = ThermalProcessor.NEUTRAL;
            darkness.setProgress(settings.darkness - 30);
            settings.save(this);
            refreshPreview();
        }));

        root.addView(label("ESC/POS rows per block (lower if pages print cut off)"));
        root.addView(spinner(new String[]{"32", "64", "128", "255"},
                indexOf(new int[]{32, 64, 128, 255}, settings.bandLines, 2), pos -> {
                    settings.bandLines = new int[]{32, 64, 128, 255}[pos];
                    settings.save(this);
                }));

        // --- use it
        root.addView(header("4. Print from any app"));
        root.addView(hint("Turn on \"MT610 Print\" once in Android's print settings. After that, use "
                + "Print in Chrome, Gmail, PDF viewers, etc. and pick the MT610. "
                + "You can also Share a PDF or picture to this app."));
        root.addView(button("Open Android print settings", v ->
                startActivity(new Intent(android.provider.Settings.ACTION_PRINT_SETTINGS))));
        root.addView(button("Open a PDF or picture...", v -> pickFile()));

        // --- preview of the loaded file
        previewInfo = hint("");
        previewInfo.setVisibility(View.GONE);
        root.addView(previewInfo);
        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setBackgroundColor(0xFFFFFFFF);
        preview.setVisibility(View.GONE);
        root.addView(preview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        printLoaded = button("Print it", v -> printCurrent());
        printLoaded.setVisibility(View.GONE);
        root.addView(printLoaded);

        log = new TextView(this);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextSize(12);
        log.setPadding(0, dp(16), 0, 0);
        root.addView(log);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        scrollView = scroll;
        updateStatus();
        return scroll;
    }

    private void updateDarknessLabel() {
        int d = settings.darkness - ThermalProcessor.NEUTRAL;
        darknessLabel.setText("Darkness: " + (d > 0 ? "+" + d : d == 0 ? "normal" : String.valueOf(d))
                + "  (slide right for darker)");
    }

    private void updateStatus() {
        if (status == null) return;
        String printer = settings.mac == null ? "none picked" : settings.name + " (" + settings.mac + ")";
        status.setText("Printer: " + printer);
    }

    // --------------------------------------------------------- Bluetooth

    private boolean hasPermission() {
        return Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void ensurePermission() {
        if (!hasPermission() && Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_BT) {
            if (hasPermission()) {
                refreshDevices();
                if (pendingShare != null) {
                    Uri u = pendingShare;
                    pendingShare = null;
                    printUri(u);
                }
            } else {
                appendLog("Bluetooth (Nearby devices) permission is needed to talk to the printer.");
            }
        }
    }

    private void refreshDevices() {
        if (deviceSpinner == null) return;
        devices.clear();
        if (hasPermission()) {
            BluetoothManager bm = getSystemService(BluetoothManager.class);
            BluetoothAdapter a = bm == null ? null : bm.getAdapter();
            if (a != null) {
                try {
                    for (BluetoothDevice d : a.getBondedDevices()) {
                        String n = d.getName() == null ? d.getAddress() : d.getName();
                        String[] entry = {n, d.getAddress()};
                        // Put HPRT/MT610 printers at the top.
                        if (n.toUpperCase().startsWith("MT6") || n.toUpperCase().contains("HPRT")) {
                            devices.add(0, entry);
                        } else {
                            devices.add(entry);
                        }
                    }
                } catch (SecurityException e) {
                    appendLog("No Bluetooth permission.");
                }
            }
        }
        List<String> labels = new ArrayList<>();
        int selected = 0;
        for (int i = 0; i < devices.size(); i++) {
            labels.add(devices.get(i)[0] + "  (" + devices.get(i)[1] + ")");
            if (devices.get(i)[1].equals(settings.mac)) selected = i;
        }
        if (labels.isEmpty()) labels.add("No paired Bluetooth devices");
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels);
        deviceSpinner.setAdapter(ad);
        deviceSpinner.setSelection(selected);
        if (settings.mac == null && !devices.isEmpty()) {
            settings.name = devices.get(0)[0];
            settings.mac = devices.get(0)[1];
            settings.save(this);
        }
        updateStatus();
    }

    // ------------------------------------------------------------ printing

    private void applyWidth() {
        try {
            int w = Integer.parseInt(widthField.getText().toString().trim());
            settings.widthDots = w;
        } catch (NumberFormatException ignored) {
        }
        settings.save(this);
        settings = Settings.load(this);
        widthField.setText(String.valueOf(settings.widthDots));
    }

    private void printTest() {
        applyWidth();
        run("Test, mode " + (settings.protocol + 1),
                new Raster.SingleBitmap(Raster.testStrip(settings.protocol)));
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/pdf", "image/*"});
        startActivityForResult(i, REQ_FILE);
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == REQ_FILE && result == RESULT_OK && data != null && data.getData() != null) {
            printUri(data.getData());
        }
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        Uri uri = null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        } else if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            uri = intent.getData();
        }
        if (uri == null) return;
        setIntent(new Intent(this, MainActivity.class));   // don't reprint on rotate
        if (!hasPermission()) {
            pendingShare = uri;
            return;
        }
        printUri(uri);
    }

    /** Copies the file in, shows a preview of how it will print, and waits for "Print it". */
    private void printUri(Uri uri) {
        applyWidth();
        String type = getContentResolver().getType(uri);
        boolean pdf = type != null ? type.equals("application/pdf")
                : uri.toString().toLowerCase().endsWith(".pdf");
        File tmp = new File(getCacheDir(), "doc-" + System.currentTimeMillis() + (pdf ? ".pdf" : ".img"));
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new IOException("Can't open file");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (IOException e) {
            appendLog("Couldn't read file: " + e.getMessage());
            return;
        }
        if (currentFile != null && !busy) currentFile.delete();
        currentFile = tmp;
        currentPdf = pdf;
        refreshPreview();
    }

    private Raster.PageSource openCurrent() throws IOException {
        return currentPdf ? new Raster.PdfSource(currentFile, null) : new Raster.ImageSource(currentFile);
    }

    private void printCurrent() {
        if (currentFile == null) return;
        applyWidth();
        try {
            run(currentPdf ? "PDF" : "Picture", openCurrent());
        } catch (IOException e) {
            appendLog("Couldn't open file: " + e.getMessage());
        }
    }

    /** Renders page 1 exactly as it will print and shows it on screen. */
    private void refreshPreview() {
        if (currentFile == null || preview == null) return;
        final int gen = previewGen.incrementAndGet();
        final Settings s = Settings.load(this);
        previewInfo.setVisibility(View.VISIBLE);
        previewInfo.setText("Making preview...");
        printLoaded.setVisibility(View.VISIBLE);
        previewWorker.execute(() -> {
            if (gen != previewGen.get()) return;
            String info;
            android.graphics.Bitmap shown = null;
            try (Raster.PageSource src = openCurrent()) {
                android.graphics.Bitmap page = src.render(0, s.widthDots);
                Raster.Packed p = ThermalProcessor.process(page, s.mode, s.darkness, s.bold);
                page.recycle();
                shown = toBitmap(p, s.widthDots);
                info = "Preview - exactly how page 1" + (src.count() > 1 ? " of " + src.count() : "")
                        + " will print. Change the settings above to adjust it.";
            } catch (IOException | RuntimeException e) {
                info = "Couldn't preview: " + e.getMessage();
            }
            final android.graphics.Bitmap img = shown;
            final String text = info;
            runOnUiThread(() -> {
                if (gen != previewGen.get()) return;
                previewInfo.setText(text);
                if (img != null) {
                    preview.setImageBitmap(img);
                    preview.setVisibility(View.VISIBLE);
                    scrollView.post(() -> scrollView.smoothScrollTo(0, previewInfo.getTop()));
                }
            });
        });
    }

    private static android.graphics.Bitmap toBitmap(Raster.Packed p, int width) {
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(
                width, p.height, android.graphics.Bitmap.Config.ARGB_8888);
        int[] row = new int[width];
        for (int y = 0; y < p.height; y++) {
            int base = y * p.widthBytes;
            for (int x = 0; x < width; x++) {
                boolean black = (p.bits[base + (x >> 3)] & (0x80 >> (x & 7))) != 0;
                row[x] = black ? 0xFF000000 : 0xFFFFFFFF;
            }
            b.setPixels(row, 0, width, 0, y, width, 1);
        }
        return b;
    }

    private void run(String what, Raster.PageSource src) {
        if (busy) {
            Toast.makeText(this, "Still printing, please wait", Toast.LENGTH_SHORT).show();
            try {
                src.close();
            } catch (IOException ignored) {
            }
            return;
        }
        if (!hasPermission()) {
            ensurePermission();
            return;
        }
        busy = true;
        final Settings s = Settings.load(this);
        appendLog("-- " + what + " --");
        new Thread(() -> {
            try {
                PrintEngine.print(this, s, src, 1, this::appendLog, new AtomicBoolean(false));
            } catch (IOException | RuntimeException e) {
                appendLog("Failed: " + e.getMessage());
            } finally {
                try {
                    src.close();
                } catch (IOException ignored) {
                }
                busy = false;
            }
        }, "print").start();
    }

    private void appendLog(String msg) {
        runOnUiThread(() -> {
            log.append(msg + "\n");
            status.setText(msg);
        });
    }

    // ------------------------------------------------------------ helpers

    private TextView header(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(18), 0, dp(4));
        return t;
    }

    private TextView hint(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setAlpha(0.75f);
        t.setPadding(0, 0, 0, dp(6));
        return t;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setPadding(0, dp(8), 0, 0);
        return t;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private Spinner spinner(String[] items, int selected, IntCallback onPick) {
        Spinner s = new Spinner(this);
        s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items));
        s.setSelection(selected);
        s.setOnItemSelectedListener(new SimpleSelect(onPick));
        return s;
    }

    private static int indexOf(int[] arr, int v, int fallback) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return fallback;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    interface IntCallback {
        void call(int value);
    }

    private static final class SimpleSelect implements AdapterView.OnItemSelectedListener {
        private final IntCallback cb;

        SimpleSelect(IntCallback cb) {
            this.cb = cb;
        }

        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            cb.call(position);
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {}
    }
}
