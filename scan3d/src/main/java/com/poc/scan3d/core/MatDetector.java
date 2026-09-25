package com.poc.scan3d.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Detecta los 4 marcadores circulares del tapete y calcula la homografía
 * mundo(X,Y) -> imagen(u,v).
 *
 * Pasos: umbral adaptativo (imagen integral) -> componentes conexas ->
 * filtro de forma (elipse rellena o anillo) -> se prueban combinaciones de 4
 * candidatos y se verifica cada homografía muestreando la imagen donde deben
 * estar los discos negros y el papel blanco.
 */
public final class MatDetector {

    private MatDetector() {}

    public static final class Result {
        public double[][] imagePts; // 4 x 2, orden M0..M3
        public double[] H;          // mundo -> imagen
        public double score;        // 0..1 (fracción de verificaciones correctas)
        public int candidates;

        @Override
        public String toString() {
            return String.format(Locale.US, "score=%.2f cand=%d M0=(%.1f,%.1f)", score, candidates,
                    imagePts[0][0], imagePts[0][1]);
        }
    }

    static final class Blob {
        int area;
        double cx, cy;
        int minX, minY, maxX, maxY;
        double sxx, syy, sxy; // momentos centrales de 2º orden (normalizados por área)
        boolean hole;

        /** area / área de la elipse con los mismos momentos: ~1 disco lleno, ~0.72 anillo. */
        double ellipseRatio() {
            double det = sxx * syy - sxy * sxy;
            return det > 0 ? area / (4 * Math.PI * Math.sqrt(det)) : 0;
        }
    }

    /** Convierte ARGB a luminancia 0..255. */
    public static int[] luminance(int[] argb) {
        int[] y = new int[argb.length];
        for (int i = 0; i < argb.length; i++) {
            int c = argb[i];
            y[i] = (77 * ((c >> 16) & 255) + 150 * ((c >> 8) & 255) + 29 * (c & 255)) >> 8;
        }
        return y;
    }

    public static Result detect(int[] lum, int w, int h) {
        byte[] dark = adaptiveDark(lum, w, h, Math.max(8, w / 8), 20);
        int[] labels = new int[w * h];
        List<Blob> blobs = components(dark, labels, w, h);
        List<Blob> cand = new ArrayList<>();
        int minArea = Math.max(20, (w * h) / 40000);
        for (int i = 0; i < blobs.size(); i++) {
            Blob b = blobs.get(i);
            if (b.area < minArea) continue;
            int bw = b.maxX - b.minX + 1, bh = b.maxY - b.minY + 1;
            if (b.minX == 0 || b.minY == 0 || b.maxX == w - 1 || b.maxY == h - 1) continue;
            double aspect = Math.max(bw, bh) / (double) Math.min(bw, bh);
            if (aspect > 4) continue;
            double fill = b.area / (double) (bw * bh);
            if (fill < 0.45 || fill > 0.92) continue;
            double er = b.ellipseRatio();
            if (er < 0.6 || er > 1.15) continue;
            int ix = (int) Math.round(b.cx), iy = (int) Math.round(b.cy);
            boolean centerIn = labels[iy * w + ix] == i + 1;
            if (!centerIn) {
                if (!enclosedHole(labels, w, i + 1, ix, iy, b)) continue;
                b.hole = true;
            }
            cand.add(b);
        }
        // El marcador con agujero es único: se prueba cada candidato "con
        // agujero" junto con tríos de candidatos macizos (los más grandes).
        List<Blob> holes = new ArrayList<>(), solids = new ArrayList<>();
        for (Blob b : cand) (b.hole ? holes : solids).add(b);
        holes.sort((a, b) -> b.area - a.area);
        solids.sort((a, b) -> b.area - a.area);
        while (holes.size() > 4) holes.remove(holes.size() - 1);
        while (solids.size() > 30) solids.remove(solids.size() - 1);

        Result best = null;
        int n = solids.size();
        for (Blob hb : holes)
            for (int a = 0; a < n; a++)
                for (int b = a + 1; b < n; b++)
                    for (int c = b + 1; c < n; c++) {
                        Blob[] q = {hb, solids.get(a), solids.get(b), solids.get(c)};
                        Result r = tryQuad(q, lum, w, h);
                        if (r != null && (best == null || r.score > best.score)) best = r;
                    }
        if (best != null) best.candidates = cand.size();
        return best != null && best.score >= 0.92 ? best : null;
    }

    private static Result tryQuad(Blob[] q, int[] lum, int w, int h) {
        int holes = 0, holeIdx = -1;
        int minA = Integer.MAX_VALUE, maxA = 0;
        for (int i = 0; i < 4; i++) {
            if (q[i].hole) { holes++; holeIdx = i; }
            minA = Math.min(minA, q[i].area);
            maxA = Math.max(maxA, q[i].area);
        }
        if (holes != 1 || minA * 15 < maxA) return null; // la perspectiva encoge mucho los lejanos
        // Orden angular alrededor del centroide; en coordenadas de imagen
        // (y hacia abajo) el recorrido M0->M1->M2->M3 tiene área con signo > 0.
        double mx = 0, my = 0;
        for (Blob b : q) { mx += b.cx; my += b.cy; }
        mx /= 4; my /= 4;
        final double fmx = mx, fmy = my;
        Blob[] s = q.clone();
        java.util.Arrays.sort(s, (a, b) -> Double.compare(
                Math.atan2(a.cy - fmy, a.cx - fmx), Math.atan2(b.cy - fmy, b.cx - fmx)));
        // atan2 creciente con y hacia abajo = sentido horario visual = área positiva.
        int start = 0;
        for (int i = 0; i < 4; i++) if (s[i].hole) start = i;
        double[][] img = new double[4][];
        for (int i = 0; i < 4; i++) {
            Blob b = s[(start + i) % 4];
            img[i] = new double[]{b.cx, b.cy};
        }
        if (!convex(img)) return null;
        double[] H = Geom.homography(RefLayout.MARKERS, img);
        if (H == null) return null;
        double sc = verify(H, lum, w, h);
        Result r = new Result();
        r.imagePts = img;
        r.H = H;
        r.score = sc;
        return r;
    }

    private static boolean convex(double[][] p) {
        int sign = 0;
        for (int i = 0; i < 4; i++) {
            double[] a = p[i], b = p[(i + 1) % 4], c = p[(i + 2) % 4];
            double cr = (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0]);
            int sg = cr > 0 ? 1 : -1;
            if (sign == 0) sign = sg;
            else if (sg != sign) return false;
        }
        return sign > 0;
    }

    /** Fracción de muestras que coinciden con lo esperado (negro dentro del disco, blanco fuera). */
    static double verify(double[] H, int[] lum, int w, int h) {
        int ok = 0, total = 0;
        double[] vals = new double[4 * 26];
        boolean[] expDark = new boolean[4 * 26];
        int k = 0;
        for (int m = 0; m < 4; m++) {
            double mx = RefLayout.MARKERS[m][0], my = RefLayout.MARKERS[m][1];
            for (int j = 0; j < 12; j++) {
                double a = j * Math.PI / 6;
                double[] pIn = Geom.apply(H, mx + 7 * Math.cos(a), my + 7 * Math.sin(a));
                double[] pOut = Geom.apply(H, mx + 15 * Math.cos(a), my + 15 * Math.sin(a));
                vals[k] = sample(lum, w, h, pIn[0], pIn[1]); expDark[k++] = true;
                vals[k] = sample(lum, w, h, pOut[0], pOut[1]); expDark[k++] = false;
            }
            double[] c = Geom.apply(H, mx, my);
            vals[k] = sample(lum, w, h, c[0], c[1]); expDark[k++] = m != 0;
            double[] c2 = Geom.apply(H, mx + 1.5, my);
            vals[k] = sample(lum, w, h, c2[0], c2[1]); expDark[k++] = m != 0;
        }
        // Umbral: punto medio entre las medias esperadas de negro y blanco.
        double sd = 0, sw = 0; int nd = 0, nw = 0;
        for (int i = 0; i < k; i++) {
            if (vals[i] < 0) continue;
            if (expDark[i]) { sd += vals[i]; nd++; } else { sw += vals[i]; nw++; }
        }
        if (nd == 0 || nw == 0) return 0;
        double thr = (sd / nd + sw / nw) / 2;
        if (sw / nw - sd / nd < 25) return 0;
        for (int i = 0; i < k; i++) {
            total++;
            if (vals[i] < 0) continue;
            if ((vals[i] < thr) == expDark[i]) ok++;
        }
        return ok / (double) total;
    }

    /** Muestreo bilineal; -1 fuera de la imagen. */
    public static double sample(int[] lum, int w, int h, double x, double y) {
        if (x < 0 || y < 0 || x >= w - 1 || y >= h - 1) return -1;
        int x0 = (int) x, y0 = (int) y;
        double fx = x - x0, fy = y - y0;
        int i = y0 * w + x0;
        return (lum[i] * (1 - fx) + lum[i + 1] * fx) * (1 - fy)
                + (lum[i + w] * (1 - fx) + lum[i + w + 1] * fx) * fy;
    }

    /** Píxel oscuro si es "margin" más oscuro que la media local (ventana 2r+1). */
    static byte[] adaptiveDark(int[] lum, int w, int h, int r, int margin) {
        long[] integ = new long[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) {
            long row = 0;
            for (int x = 0; x < w; x++) {
                row += lum[y * w + x];
                integ[(y + 1) * (w + 1) + x + 1] = integ[y * (w + 1) + x + 1] + row;
            }
        }
        byte[] out = new byte[w * h];
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - r), y1 = Math.min(h, y + r + 1);
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - r), x1 = Math.min(w, x + r + 1);
                long s = integ[y1 * (w + 1) + x1] - integ[y0 * (w + 1) + x1]
                        - integ[y1 * (w + 1) + x0] + integ[y0 * (w + 1) + x0];
                int mean = (int) (s / ((long) (x1 - x0) * (y1 - y0)));
                out[y * w + x] = (byte) (lum[y * w + x] < mean - margin ? 1 : 0);
            }
        }
        return out;
    }

    /** Componentes 4-conexas de píxeles == 1. labels usa índices desde 1. */
    static List<Blob> components(byte[] img, int[] labels, int w, int h) {
        List<Blob> out = new ArrayList<>();
        int[] stack = new int[w * h];
        for (int p = 0; p < w * h; p++) {
            if (img[p] == 0 || labels[p] != 0) continue;
            int id = out.size() + 1;
            Blob b = new Blob();
            b.minX = b.minY = Integer.MAX_VALUE;
            long sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;
            int sp = 0;
            stack[sp++] = p;
            labels[p] = id;
            while (sp > 0) {
                int q = stack[--sp];
                int x = q % w, y = q / w;
                b.area++; sx += x; sy += y;
                sxx += (long) x * x; syy += (long) y * y; sxy += (long) x * y;
                if (x < b.minX) b.minX = x;
                if (x > b.maxX) b.maxX = x;
                if (y < b.minY) b.minY = y;
                if (y > b.maxY) b.maxY = y;
                if (x > 0 && img[q - 1] != 0 && labels[q - 1] == 0) { labels[q - 1] = id; stack[sp++] = q - 1; }
                if (x < w - 1 && img[q + 1] != 0 && labels[q + 1] == 0) { labels[q + 1] = id; stack[sp++] = q + 1; }
                if (y > 0 && img[q - w] != 0 && labels[q - w] == 0) { labels[q - w] = id; stack[sp++] = q - w; }
                if (y < h - 1 && img[q + w] != 0 && labels[q + w] == 0) { labels[q + w] = id; stack[sp++] = q + w; }
            }
            b.cx = sx / (double) b.area;
            b.cy = sy / (double) b.area;
            b.sxx = sxx / (double) b.area - b.cx * b.cx;
            b.syy = syy / (double) b.area - b.cy * b.cy;
            b.sxy = sxy / (double) b.area - b.cx * b.cy;
            out.add(b);
        }
        return out;
    }

    /** ¿El hueco que contiene (x,y) está totalmente rodeado por el blob? */
    private static boolean enclosedHole(int[] labels, int w, int id, int x, int y, Blob b) {
        int bw = b.maxX - b.minX + 1, bh = b.maxY - b.minY + 1;
        boolean[] seen = new boolean[bw * bh];
        int[] stack = new int[bw * bh];
        int sp = 0, count = 0;
        stack[sp++] = (y - b.minY) * bw + (x - b.minX);
        seen[stack[0]] = true;
        while (sp > 0) {
            int q = stack[--sp];
            int lx = q % bw, ly = q / bw;
            if (lx == 0 || ly == 0 || lx == bw - 1 || ly == bh - 1) return false;
            count++;
            int[] nb = {q - 1, q + 1, q - bw, q + bw};
            for (int nq : nb) {
                if (seen[nq]) continue;
                int gx = nq % bw + b.minX, gy = nq / bw + b.minY;
                if (labels[gy * w + gx] == id) continue;
                seen[nq] = true;
                stack[sp++] = nq;
            }
        }
        // El agujero debe ser una fracción razonable del marcador (r=4 de 10 -> ~16%).
        return count > b.area * 0.04 && count < b.area * 0.6;
    }
}
