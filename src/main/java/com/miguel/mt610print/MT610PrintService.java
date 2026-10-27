package com.miguel.mt610print;

import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintJobId;
import android.print.PrintJobInfo;
import android.print.PrinterCapabilitiesInfo;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.printservice.PrintJob;
import android.printservice.PrintService;
import android.printservice.PrinterDiscoverySession;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Android system print service: shows the MT610 in every app's Print menu. */
public class MT610PrintService extends PrintService {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<PrintJobId, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    @Override
    protected PrinterDiscoverySession onCreatePrinterDiscoverySession() {
        return new Session();
    }

    private final class Session extends PrinterDiscoverySession {
        @Override
        public void onStartPrinterDiscovery(List<PrinterId> priorityList) {
            publish();
        }

        @Override
        public void onStopPrinterDiscovery() {}

        @Override
        public void onValidatePrinters(List<PrinterId> printerIds) {
            publish();
        }

        @Override
        public void onStartPrinterStateTracking(PrinterId printerId) {
            publish();
        }

        @Override
        public void onStopPrinterStateTracking(PrinterId printerId) {}

        @Override
        public void onDestroy() {}

        private void publish() {
            Settings s = Settings.load(MT610PrintService.this);
            List<PrinterInfo> list = new ArrayList<>();
            if (s.mac != null) {
                PrinterId id = generatePrinterId(s.mac);
                String res = s.dpi + "dpi";
                PrinterCapabilitiesInfo caps = new PrinterCapabilitiesInfo.Builder(id)
                        .addMediaSize(PrintAttributes.MediaSize.NA_LETTER, true)
                        .addMediaSize(PrintAttributes.MediaSize.ISO_A4, false)
                        .addMediaSize(PrintAttributes.MediaSize.ISO_A5, false)
                        .addResolution(new PrintAttributes.Resolution(res, s.dpi + " dpi", s.dpi, s.dpi), true)
                        .setColorModes(PrintAttributes.COLOR_MODE_MONOCHROME,
                                PrintAttributes.COLOR_MODE_MONOCHROME)
                        .setMinMargins(new PrintAttributes.Margins(0, 0, 0, 0))
                        .build();
                list.add(new PrinterInfo.Builder(id,
                        s.name != null ? s.name : "HPRT MT610",
                        PrinterInfo.STATUS_IDLE)
                        .setDescription("HPRT MT610 thermal (Bluetooth)")
                        .setCapabilities(caps)
                        .build());
            }
            // Drop printers from an older pick, then add the current one.
            List<PrinterId> stale = new ArrayList<>();
            for (PrinterInfo p : getPrinters()) {
                if (list.isEmpty() || !p.getId().equals(list.get(0).getId())) stale.add(p.getId());
            }
            if (!stale.isEmpty()) removePrinters(stale);
            if (!list.isEmpty()) addPrinters(list);
        }
    }

    @Override
    protected void onPrintJobQueued(PrintJob job) {
        if (job.isQueued()) job.start();
        final PrintJobInfo info = job.getInfo();
        final ParcelFileDescriptor data = job.getDocument().getData();
        if (data == null) {
            job.fail("No document data");
            return;
        }
        final AtomicBoolean cancel = new AtomicBoolean(false);
        cancelFlags.put(job.getId(), cancel);
        final Settings s = Settings.load(this);
        final int copies = Math.max(1, info.getCopies());
        final PageRange[] ranges = info.getPages();

        worker.execute(() -> {
            String error = null;
            File tmp = new File(getCacheDir(), "job.pdf");
            try {
                copy(data, tmp);
                int[] pages = null;
                try (Raster.PdfSource probe = new Raster.PdfSource(tmp, null)) {
                    pages = selectPages(ranges, probe.totalPages());
                }
                try (Raster.PdfSource src = new Raster.PdfSource(tmp, pages)) {
                    PrintEngine.print(this, s, src, copies, msg -> main.post(() -> {
                        if (job.isStarted()) job.setStatus(msg);
                    }), cancel);
                }
            } catch (IOException | RuntimeException e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            } finally {
                tmp.delete();
            }
            final String err = error;
            main.post(() -> {
                cancelFlags.remove(job.getId());
                if (cancel.get() || job.isCancelled()) return;
                if (err == null) job.complete();
                else job.fail(err);
            });
        });
    }

    @Override
    protected void onRequestCancelPrintJob(PrintJob job) {
        AtomicBoolean f = cancelFlags.get(job.getId());
        if (f != null) f.set(true);
        job.cancel();
    }

    @Override
    public void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }

    /**
     * Returns the page indexes to print, or null for all. If the document
     * already holds exactly the selected pages, prints them all.
     */
    static int[] selectPages(PageRange[] ranges, int total) {
        if (ranges == null || ranges.length == 0) return null;
        List<Integer> want = new ArrayList<>();
        for (PageRange r : ranges) {
            if (PageRange.ALL_PAGES.equals(r)) return null;
            for (int p = r.getStart(); p <= r.getEnd(); p++) {
                if (p >= 0 && p < total) want.add(p);
            }
        }
        int requested = 0;
        for (PageRange r : ranges) requested += r.getEnd() - r.getStart() + 1;
        if (want.isEmpty() || requested == total) return null;
        int[] out = new int[want.size()];
        for (int i = 0; i < out.length; i++) out[i] = want.get(i);
        return out;
    }

    private static void copy(ParcelFileDescriptor pfd, File dest) throws IOException {
        try (InputStream in = new FileInputStream(pfd.getFileDescriptor());
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            try {
                pfd.close();
            } catch (IOException ignored) {
            }
        }
    }
}
