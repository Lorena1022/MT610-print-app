package com.miguel.mt610print;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;

/** Turns pages (PDF, pictures, test strip) into 1-bit printer images. */
public final class Raster {
    private Raster() {}

    /** Something that can hand out printable pages one at a time. */
    public interface PageSource extends Closeable {
        int count();

        /** Renders page {@code i} scaled to {@code widthDots}, on white. */
        Bitmap render(int i, int widthDots) throws IOException;
    }

    /** Result of packing: rows of widthBytes bytes, 1 = black. */
    public static final class Packed {
        public final byte[] bits;
        public final int widthBytes;
        public final int height;

        Packed(byte[] bits, int widthBytes, int height) {
            this.bits = bits;
            this.widthBytes = widthBytes;
            this.height = height;
        }
    }

    /**
     * Short, narrow test strip. Kept small on purpose: if a mode is wrong the
     * printer may print the data as junk text, so less data = less wasted paper.
     */
    public static Bitmap testStrip(int protocol) {
        int w = 576, h = 150;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.WHITE);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4);
        c.drawRect(2, 2, w - 2, h - 2, p);
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(56);
        c.drawText("MODE " + (protocol + 1) + " WORKS", 20, 70, p);
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(26);
        c.drawText(Protocols.NAMES[protocol], 20, 110, p);
        c.drawText("MT610 Print test", 20, 138, p);
        return b;
    }

    // ------------------------------------------------------------------ PDF

    public static final class PdfSource implements PageSource {
        private final ParcelFileDescriptor fd;
        private final PdfRenderer renderer;
        private final int[] pages;

        /** @param pages page indexes to print, or null for all */
        public PdfSource(File file, int[] pages) throws IOException {
            fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(fd);
            if (pages == null) {
                pages = new int[renderer.getPageCount()];
                for (int i = 0; i < pages.length; i++) pages[i] = i;
            }
            this.pages = pages;
        }

        public int totalPages() {
            return renderer.getPageCount();
        }

        @Override
        public int count() {
            return pages.length;
        }

        @Override
        public Bitmap render(int i, int widthDots) {
            try (PdfRenderer.Page page = renderer.openPage(pages[i])) {
                float scale = widthDots / (float) page.getWidth();
                int h = Math.max(1, Math.round(page.getHeight() * scale));
                Bitmap b = Bitmap.createBitmap(widthDots, h, Bitmap.Config.ARGB_8888);
                b.eraseColor(Color.WHITE);
                Matrix m = new Matrix();
                m.setScale(scale, scale);
                page.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                return b;
            }
        }

        @Override
        public void close() throws IOException {
            renderer.close();
            fd.close();
        }
    }

    // --------------------------------------------------------------- images

    public static final class ImageSource implements PageSource {
        private final File file;

        public ImageSource(File file) {
            this.file = file;
        }

        @Override
        public int count() {
            return 1;
        }

        @Override
        public Bitmap render(int i, int widthDots) throws IOException {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), o);
            if (o.outWidth <= 0) throw new IOException("Can't read that picture");
            int sample = 1;
            while (o.outWidth / (sample * 2) >= widthDots) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap src = BitmapFactory.decodeFile(file.getPath(), o);
            if (src == null) throw new IOException("Can't read that picture");
            int h = Math.max(1, Math.round(src.getHeight() * (widthDots / (float) src.getWidth())));
            Bitmap b = Bitmap.createBitmap(widthDots, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.drawColor(Color.WHITE);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            c.drawBitmap(src, null, new android.graphics.Rect(0, 0, widthDots, h), p);
            src.recycle();
            return b;
        }

        @Override
        public void close() {}
    }

    /** One fixed bitmap (used for the test strip). */
    public static final class SingleBitmap implements PageSource {
        private final Bitmap bitmap;

        public SingleBitmap(Bitmap bitmap) {
            this.bitmap = bitmap;
        }

        @Override
        public int count() {
            return 1;
        }

        @Override
        public Bitmap render(int i, int widthDots) {
            return bitmap.copy(Bitmap.Config.ARGB_8888, false);
        }

        @Override
        public void close() {}
    }
}
