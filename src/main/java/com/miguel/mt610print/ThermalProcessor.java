package com.miguel.mt610print;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * Turns a page into the best possible 1-bit image for thermal paper.
 *
 * Thermal heads can only print black or white dots, and small or gray
 * details tend to fade, so each kind of content gets its own treatment:
 *
 *  - Paper color, yellowing and phone-camera shadows are flattened to white.
 *  - Text and lines use an adaptive (local) threshold, so faded, gray or
 *    thin print comes out solid black instead of disappearing.
 *  - Flat gray shading (form fields, table headers) becomes a light, even
 *    dot pattern that never covers the text on top of it.
 *  - Photos get auto-contrast, sharpening, a tone curve for thermal dot gain
 *    and Atkinson dithering, which keeps them clean instead of muddy.
 *
 * In Auto mode the page is split into 32x32 blocks and each block is
 * classified as document or photo, so mixed pages work too.
 */
public final class ThermalProcessor {
    public static final int AUTO = 0;
    public static final int DOCUMENT = 1;
    public static final int PHOTO = 2;
    public static final int SIMPLE = 3;

    public static final String[] MODES = {
            "Auto - best for most pages",
            "Document / forms - sharpest text",
            "Photo / picture - smooth shading",
            "Simple - plain black & white",
    };

    /** Neutral darkness value; the slider runs 30..230. */
    public static final int NEUTRAL = 128;

    private static final int BLOCK = 32;      // classification block size
    private static final int TEXT_R = 15;     // adaptive threshold window radius (31 px ~ 4 mm)

    private static final int[] BAYER8 = {
            0, 32, 8, 40, 2, 34, 10, 42,
            48, 16, 56, 24, 50, 18, 58, 26,
            12, 44, 4, 36, 14, 46, 6, 38,
            60, 28, 52, 20, 62, 30, 54, 22,
            3, 35, 11, 43, 1, 33, 9, 41,
            51, 19, 59, 27, 49, 17, 57, 25,
            15, 47, 7, 39, 13, 45, 5, 37,
            63, 31, 55, 23, 61, 29, 53, 21,
    };

    private ThermalProcessor() {}

    /** Bitmap entry point used by the app. */
    public static Raster.Packed process(Bitmap bmp, int mode, int darkness, boolean bold) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        byte[] gray = new byte[w * h];
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            bmp.getPixels(row, 0, w, 0, y, w, 1);
            int base = y * w;
            for (int x = 0; x < w; x++) gray[base + x] = (byte) lum(row[x]);
        }
        return process(gray, w, h, mode, darkness, bold);
    }

    /** Luminance 0..255, transparent = white paper. */
    private static int lum(int argb) {
        int a = Color.alpha(argb);
        int l = (Color.red(argb) * 299 + Color.green(argb) * 587 + Color.blue(argb) * 114) / 1000;
        return 255 - a * (255 - l) / 255;
    }

    /** Core pipeline on an 8-bit grayscale image (row-major, 0 = black). */
    public static Raster.Packed process(byte[] gray, int w, int h, int mode, int darkness, boolean bold) {
        int wb = (w + 7) / 8;
        darkness = Math.max(30, Math.min(230, darkness));

        if (mode == SIMPLE) {
            byte[] out = new byte[wb * h];
            int t = darkness;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if ((gray[y * w + x] & 0xFF) < t) out[y * wb + (x >> 3)] |= (byte) (0x80 >> (x & 7));
                }
            }
            if (bold) embolden(out, wb, h);
            return new Raster.Packed(out, wb, h);
        }

        int bw = (w + BLOCK - 1) / BLOCK;
        int bh = (h + BLOCK - 1) / BLOCK;
        boolean[] photo = new boolean[bw * bh];
        byte[] norm = null;
        boolean cleanPaper = true;

        if (mode == PHOTO) {
            java.util.Arrays.fill(photo, true);
        } else {
            boolean[] clean = new boolean[1];
            norm = flattenBackground(gray, w, h, clean);
            cleanPaper = clean[0];
            if (mode == AUTO) classify(norm, w, h, bw, bh, photo);
        }

        boolean anyPhoto = false, anyDoc = false;
        for (boolean p : photo) {
            if (p) anyPhoto = true;
            else anyDoc = true;
        }

        byte[] textBits = new byte[wb * h];   // gets bolded
        byte[] toneBits = new byte[wb * h];   // shading & photos, never bolded

        if (anyDoc) documentPass(norm, w, h, bw, photo, darkness, cleanPaper, textBits, toneBits);
        norm = null;
        if (anyPhoto) photoPass(gray, w, h, bw, photo, darkness, toneBits);

        if (bold) embolden(textBits, wb, h);
        for (int i = 0; i < textBits.length; i++) textBits[i] |= toneBits[i];
        return new Raster.Packed(textBits, wb, h);
    }

    // ------------------------------------------------------------------
    // Background flattening: divide by a smooth estimate of the paper color

    static byte[] flattenBackground(byte[] g, int w, int h, boolean[] cleanOut) {
        final int B = 32;
        int bw = (w + B - 1) / B, bh = (h + B - 1) / B;
        int[] bg = new int[bw * bh];

        // 90th-percentile brightness of each block = local paper color
        int[][] hist = new int[bw][256];
        for (int by = 0; by < bh; by++) {
            for (int[] hh : hist) java.util.Arrays.fill(hh, 0);
            int y1 = Math.min(h, (by + 1) * B);
            for (int y = by * B; y < y1; y++) {
                int base = y * w;
                for (int x = 0; x < w; x++) hist[x / B][g[base + x] & 0xFF]++;
            }
            for (int bx = 0; bx < bw; bx++) {
                int[] hh = hist[bx];
                int total = 0;
                for (int v : hh) total += v;
                int target = total / 10, acc = 0, v = 255;
                for (; v > 0; v--) {
                    acc += hh[v];
                    if (acc >= target) break;
                }
                bg[by * bw + bx] = v;
            }
        }

        // Max filter (spans text and dark areas up to ~36 mm), then smooth.
        bg = gridMax(bg, bw, bh, 4);
        bg = gridBlur(bg, bw, bh, 2);

        int bgMin = 255, bgMax = 0;
        for (int i = 0; i < bg.length; i++) {
            bg[i] = Math.max(110, Math.min(255, bg[i]));
            bgMin = Math.min(bgMin, bg[i]);
            bgMax = Math.max(bgMax, bg[i]);
        }
        if (bgMax - bgMin <= 12) {
            // Even background (PDF, scan, screenshot): just set the paper to white.
            cleanOut[0] = true;
            if (bgMax >= 250) return g;
            float scale = 255f / bgMax;
            byte[] out = new byte[g.length];
            for (int i = 0; i < g.length; i++) out[i] = (byte) Math.min(255, Math.round((g[i] & 0xFF) * scale));
            return out;
        }
        cleanOut[0] = false;

        // Bilinear upsample of the grid and divide.
        byte[] out = new byte[w * h];
        for (int y = 0; y < h; y++) {
            float fy = (y + 0.5f) / B - 0.5f;
            int y0 = clamp((int) Math.floor(fy), 0, bh - 1);
            int y1 = Math.min(bh - 1, y0 + 1);
            float ty = Math.max(0f, Math.min(1f, fy - y0));
            int base = y * w;
            for (int x = 0; x < w; x++) {
                float fx = (x + 0.5f) / B - 0.5f;
                int x0 = clamp((int) Math.floor(fx), 0, bw - 1);
                int x1 = Math.min(bw - 1, x0 + 1);
                float tx = Math.max(0f, Math.min(1f, fx - x0));
                float top = bg[y0 * bw + x0] * (1 - tx) + bg[y0 * bw + x1] * tx;
                float bot = bg[y1 * bw + x0] * (1 - tx) + bg[y1 * bw + x1] * tx;
                float b = top * (1 - ty) + bot * ty;
                int v = Math.round((g[base + x] & 0xFF) * 255f / b);
                out[base + x] = (byte) Math.min(255, v);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Block classification for Auto mode

    static void classify(byte[] g, int w, int h, int bw, int bh, boolean[] photo) {
        int n = bw * bh;
        boolean[] raw = new boolean[n];
        int[][] hist = new int[bw][256];
        for (int by = 0; by < bh; by++) {
            for (int[] hh : hist) java.util.Arrays.fill(hh, 0);
            int yEnd = Math.min(h, (by + 1) * BLOCK);
            for (int y = by * BLOCK; y < yEnd; y++) {
                int base = y * w;
                for (int x = 0; x < w; x++) hist[x / BLOCK][g[base + x] & 0xFF]++;
            }
            for (int bx = 0; bx < bw; bx++) {
                int[] hh = hist[bx];
                long cnt = 0, sum = 0, sq = 0, mid = 0;
                for (int v = 0; v < 256; v++) {
                    cnt += hh[v];
                    sum += (long) hh[v] * v;
                    sq += (long) hh[v] * v * v;
                    if (v > 60 && v < 215) mid += hh[v];
                }
                double m = sum / (double) cnt;
                double sd = Math.sqrt(Math.max(0, sq / (double) cnt - m * m));
                // median, and how many pixels sit right at it
                long acc = 0;
                int med = 0;
                for (; med < 255; med++) {
                    acc += hh[med];
                    if (acc * 2 >= cnt) break;
                }
                long near = 0;
                for (int v = Math.max(0, med - 10); v <= Math.min(255, med + 10); v++) near += hh[v];
                // Photo-like: lots of in-between grays, varied, and NOT one flat
                // background with marks on it (that's text on paper or shading).
                raw[by * bw + bx] = mid > 0.35 * cnt && sd > 14 && near < 0.5 * cnt;
            }
        }
        // Opening (erode then dilate): text lines are only 1-2 blocks tall,
        // so gray text and small shaded labels drop out; real photos stay.
        boolean[] opened = dilate(erode(raw, bw, bh), bw, bh);

        // Each remaining region is a photo. Mostly-rectangular regions are
        // filled to their bounding box so dark and light parts of the photo
        // (which aren't "gray") are included too.
        int[] label = new int[n];
        java.util.Arrays.fill(label, -1);
        int[] stack = new int[n];
        for (int start = 0; start < n; start++) {
            if (!opened[start] || label[start] >= 0) continue;
            int sp = 0, size = 0;
            int x0 = bw, y0 = bh, x1 = -1, y1 = -1;
            stack[sp++] = start;
            label[start] = start;
            while (sp > 0) {
                int c = stack[--sp];
                size++;
                int cx = c % bw, cy = c / bw;
                x0 = Math.min(x0, cx); x1 = Math.max(x1, cx);
                y0 = Math.min(y0, cy); y1 = Math.max(y1, cy);
                int[] nbs = {cy > 0 ? c - bw : -1, cy < bh - 1 ? c + bw : -1,
                        cx > 0 ? c - 1 : -1, cx < bw - 1 ? c + 1 : -1};
                for (int nb : nbs) {
                    if (nb >= 0 && opened[nb] && label[nb] < 0) {
                        label[nb] = start;
                        stack[sp++] = nb;
                    }
                }
            }
            if (size < 6) continue;
            int boxArea = (x1 - x0 + 1) * (y1 - y0 + 1);
            if (size >= 0.6 * boxArea) {
                for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) photo[y * bw + x] = true;
            } else {
                for (int i = 0; i < n; i++) if (label[i] == start) photo[i] = true;
            }
        }
    }

    private static boolean[] erode(boolean[] a, int w, int h) {
        boolean[] o = new boolean[a.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean all = a[y * w + x];
                for (int dy = -1; dy <= 1 && all; dy++) {
                    for (int dx = -1; dx <= 1 && all; dx++) {
                        int yy = clamp(y + dy, 0, h - 1), xx = clamp(x + dx, 0, w - 1);
                        all = a[yy * w + xx];
                    }
                }
                o[y * w + x] = all;
            }
        }
        return o;
    }

    private static boolean[] dilate(boolean[] a, int w, int h) {
        boolean[] o = new boolean[a.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean any = false;
                for (int dy = -1; dy <= 1 && !any; dy++) {
                    for (int dx = -1; dx <= 1 && !any; dx++) {
                        int yy = y + dy, xx = x + dx;
                        if (yy >= 0 && yy < h && xx >= 0 && xx < w) any = a[yy * w + xx];
                    }
                }
                o[y * w + x] = any;
            }
        }
        return o;
    }

    // ------------------------------------------------------------------
    // Documents: adaptive threshold for text, light ordered dots for shading

    static void documentPass(byte[] g, int w, int h, int bw, boolean[] photo, int darkness,
                             boolean cleanPaper, byte[] textBits, byte[] toneBits) {
        int wb = (w + 7) / 8;
        int r = TEXT_R;
        float area = (2 * r + 1) * (2 * r + 1);
        int[] tmp = new int[w * h];
        int[] sum = boxSum(g, w, h, r, false, tmp);
        int[] sq = boxSum(g, w, h, r, true, tmp);
        tmp = null;

        float shift = (darkness - NEUTRAL) / 100f;                 // -0.98 .. +1.02
        float k = Math.max(0.03f, Math.min(0.5f, 0.22f - 0.18f * shift));
        int solidBlack = clamp(95 + Math.round(60 * shift), 40, 170);   // always black below this
        int paperWhite = clamp(232 + Math.round(12 * shift), 215, 250); // always white above this
        // On a clean PDF any light gray is real shading; on a camera photo light
        // gray is usually shadow, so only clearly gray areas count as shading.
        int tintMax = cleanPaper ? paperWhite - 4 : clamp(200 + Math.round(15 * shift), 170, 225);
        float tintStrength = Math.max(0.25f, Math.min(0.9f, 0.5f + 0.3f * shift));

        for (int y = 0; y < h; y++) {
            int base = y * w;
            int pbase = (y / BLOCK) * bw;
            int obase = y * wb;
            for (int x = 0; x < w; x++) {
                if (photo[pbase + x / BLOCK]) continue;
                int i = base + x;
                int v = g[i] & 0xFF;
                int bit = 0x80 >> (x & 7);
                if (v < solidBlack) {
                    textBits[obase + (x >> 3)] |= (byte) bit;
                    continue;
                }
                if (v > paperWhite) continue;
                float m = sum[i] / area;
                float sd = (float) Math.sqrt(Math.max(0f, sq[i] / area - m * m));
                boolean text = false;
                if (sd >= 9f) {
                    // Sauvola threshold: relative to the local background,
                    // so faded or gray text still comes out solid.
                    float t = m * (1f + k * (sd / 128f - 1f));
                    text = v <= t;
                }
                if (text) {
                    textBits[obase + (x >> 3)] |= (byte) bit;
                } else if (v <= tintMax) {
                    // Gray shading (form fields, headers): lighten it and draw it
                    // as an even dot pattern that never hides the text on top.
                    float tone = 255f - (255f - v) * tintStrength;
                    float thr = BAYER8[(y & 7) * 8 + (x & 7)] * 4 + 2;
                    if (tone < thr) toneBits[obase + (x >> 3)] |= (byte) bit;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Photos: levels, sharpen, tone curve, Atkinson dither

    static void photoPass(byte[] g, int w, int h, int bw, boolean[] photo, int darkness, byte[] toneBits) {
        int wb = (w + 7) / 8;

        // Auto-levels from the photo pixels only (1st..99th percentile).
        int[] hist = new int[256];
        int total = 0;
        for (int y = 0; y < h; y++) {
            int pbase = (y / BLOCK) * bw;
            int base = y * w;
            for (int x = 0; x < w; x++) {
                if (!photo[pbase + x / BLOCK]) continue;
                hist[g[base + x] & 0xFF]++;
                total++;
            }
        }
        if (total == 0) return;
        int lo = percentile(hist, total, 0.01), hi = percentile(hist, total, 0.99);
        if (hi - lo < 40) {
            lo = 0;
            hi = 255;
        }

        // Tone curve: gamma < 1 lightens mid-tones to offset thermal dot gain.
        double gamma = 0.8 * Math.pow(2.0, (darkness - NEUTRAL) / 60.0);
        float[] lut = new float[256];
        for (int v = 0; v < 256; v++) {
            double lv = Math.max(0, Math.min(1, (v - lo) / (double) (hi - lo)));
            lut[v] = (float) (255.0 * Math.pow(lv, gamma));
        }

        int r = 2;
        float area = (2 * r + 1) * (2 * r + 1);
        int[] blur = boxSum(g, w, h, r, false, new int[w * h]);
        float amount = 0.8f;

        // Atkinson: spreads 6/8 of the error, which keeps highlights clean
        // and shadows solid - good for thermal paper.
        float[] e0 = new float[w + 4], e1 = new float[w + 4], e2 = new float[w + 4];
        for (int y = 0; y < h; y++) {
            int pbase = (y / BLOCK) * bw;
            int base = y * w;
            int obase = y * wb;
            for (int x = 0; x < w; x++) {
                if (!photo[pbase + x / BLOCK]) continue;
                int i = base + x;
                float orig = g[i] & 0xFF;
                float sharp = orig + amount * (orig - blur[i] / area);
                int sv = clamp(Math.round(sharp), 0, 255);
                float v = lut[sv] + e0[x + 2];
                boolean black = v < 128f;
                float err = (v - (black ? 0f : 255f)) / 8f;
                e0[x + 3] += err;
                e0[x + 4] += err;
                e1[x + 1] += err;
                e1[x + 2] += err;
                e1[x + 3] += err;
                e2[x + 2] += err;
                if (black) toneBits[obase + (x >> 3)] |= (byte) (0x80 >> (x & 7));
            }
            float[] t = e0;
            e0 = e1;
            e1 = e2;
            e2 = t;
            java.util.Arrays.fill(e2, 0f);
        }
    }

    // ------------------------------------------------------------------
    // Helpers

    /** Grows every black dot one pixel right and one pixel down. */
    static void embolden(byte[] bits, int wb, int h) {
        for (int y = 0; y < h; y++) {
            int base = y * wb;
            int carry = 0;
            for (int i = 0; i < wb; i++) {
                int b = bits[base + i] & 0xFF;
                bits[base + i] = (byte) (b | (b >> 1) | carry);
                carry = (b & 1) << 7;
            }
        }
        for (int y = h - 1; y > 0; y--) {
            int base = y * wb;
            int prev = base - wb;
            for (int i = 0; i < wb; i++) bits[base + i] |= bits[prev + i];
        }
    }

    /** Sum (or sum of squares) over a (2r+1)^2 window, edges replicated. */
    static int[] boxSum(byte[] src, int w, int h, int r, boolean square, int[] tmp) {
        for (int y = 0; y < h; y++) {
            int base = y * w;
            int s = 0;
            for (int k = -r; k <= r; k++) s += val(src[base + clamp(k, 0, w - 1)], square);
            for (int x = 0; x < w; x++) {
                tmp[base + x] = s;
                s += val(src[base + Math.min(w - 1, x + r + 1)], square)
                        - val(src[base + Math.max(0, x - r)], square);
            }
        }
        int[] out = new int[w * h];
        int[] acc = new int[w];
        for (int k = -r; k <= r; k++) {
            int rb = clamp(k, 0, h - 1) * w;
            for (int x = 0; x < w; x++) acc[x] += tmp[rb + x];
        }
        for (int y = 0; y < h; y++) {
            System.arraycopy(acc, 0, out, y * w, w);
            int add = Math.min(h - 1, y + r + 1) * w;
            int sub = Math.max(0, y - r) * w;
            for (int x = 0; x < w; x++) acc[x] += tmp[add + x] - tmp[sub + x];
        }
        return out;
    }

    private static int val(byte b, boolean square) {
        int v = b & 0xFF;
        return square ? v * v : v;
    }

    private static int[] gridMax(int[] a, int w, int h, int r) {
        int[] t = new int[a.length], o = new int[a.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int m = 0;
                for (int k = Math.max(0, x - r); k <= Math.min(w - 1, x + r); k++) m = Math.max(m, a[y * w + k]);
                t[y * w + x] = m;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int m = 0;
                for (int k = Math.max(0, y - r); k <= Math.min(h - 1, y + r); k++) m = Math.max(m, t[k * w + x]);
                o[y * w + x] = m;
            }
        }
        return o;
    }

    private static int[] gridBlur(int[] a, int w, int h, int r) {
        int[] o = new int[a.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int s = 0, c = 0;
                for (int dy = -r; dy <= r; dy++) {
                    for (int dx = -r; dx <= r; dx++) {
                        int yy = y + dy, xx = x + dx;
                        if (yy < 0 || yy >= h || xx < 0 || xx >= w) continue;
                        s += a[yy * w + xx];
                        c++;
                    }
                }
                o[y * w + x] = s / c;
            }
        }
        return o;
    }

    private static int percentile(int[] hist, int total, double p) {
        int target = (int) (total * p), acc = 0;
        for (int v = 0; v < 256; v++) {
            acc += hist[v];
            if (acc > target) return v;
        }
        return 255;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
