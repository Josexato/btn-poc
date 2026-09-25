package com.poc.scan3d.core;

import java.util.Locale;

/**
 * Mide el ángulo absoluto del disco giratorio leyendo su anillo codificado.
 *
 * Se muestrea el anillo en M ángulos (vía homografía), se binariza y se hace
 * correlación circular con el código conocido. El pico da el giro; como el
 * código es una secuencia-m, un arco ocluido por el objeto sólo baja el pico,
 * no lo desplaza.
 */
public final class RingAngle {

    private RingAngle() {}

    public static final class Measurement {
        public double angle;   // rad, CCW visto desde arriba, [0, 2pi)
        public double conf;    // pico normalizado (1 = anillo completo y perfecto)
        public double margin;  // pico - segundo pico, normalizado
        public int valid;      // muestras dentro de la imagen

        public boolean ok() { return conf >= 0.3 && margin >= 0.12; }

        @Override
        public String toString() {
            return String.format(Locale.US, "%.2f° conf=%.2f margen=%.2f", Math.toDegrees(angle), conf, margin);
        }
    }

    private static final double[] RADII = {71, 75, 79, 83};

    public static Measurement measure(int[] lum, int w, int h, double[] H, double ccx, double ccy, int m) {
        double[] s = new double[m];
        boolean[] in = new boolean[m];
        int valid = 0;
        double[] all = new double[m];
        for (int k = 0; k < m; k++) {
            double a = 2 * Math.PI * (k + 0.5) / m;
            double ca = Math.cos(a), sa = Math.sin(a);
            double acc = 0;
            int n = 0;
            for (double r : RADII) {
                double[] p = Geom.apply(H, ccx + r * ca, ccy + r * sa);
                double v = MatDetector.sample(lum, w, h, p[0], p[1]);
                if (v >= 0) { acc += v; n++; }
            }
            if (n == RADII.length) {
                s[k] = acc / n;
                in[k] = true;
                all[valid++] = s[k];
            }
        }
        Measurement out = new Measurement();
        out.valid = valid;
        if (valid < m / 4) return out;
        double[] sorted = java.util.Arrays.copyOf(all, valid);
        java.util.Arrays.sort(sorted);
        double lo = sorted[valid / 10], hi = sorted[valid * 9 / 10];
        double thr = (lo + hi) / 2;
        if (hi - lo < 20) return out;
        int[] obs = new int[m];
        for (int k = 0; k < m; k++) obs[k] = !in[k] ? 0 : (s[k] < thr ? 1 : -1);
        int[] ref = new int[m];
        for (int k = 0; k < m; k++) ref[k] = RefLayout.codeAt(2 * Math.PI * (k + 0.5) / m) ? 1 : -1;

        double[] c = new double[m];
        int best = 0;
        for (int sh = 0; sh < m; sh++) {
            int acc = 0;
            for (int k = 0; k < m; k++) {
                int o = obs[k];
                if (o == 0) continue;
                int j = k - sh;
                if (j < 0) j += m;
                acc += o * ref[j];
            }
            c[sh] = acc;
            if (acc > c[best]) best = sh;
        }
        // Segundo pico: fuera de +-1.5 bits del máximo.
        int excl = (int) Math.ceil(1.5 * m / RefLayout.CODE_BITS);
        double second = -1e9;
        for (int sh = 0; sh < m; sh++) {
            int d = Math.abs(sh - best);
            d = Math.min(d, m - d);
            if (d > excl && c[sh] > second) second = c[sh];
        }
        // Refinamiento sub-muestra: el pico de correlación es triangular, así
        // que se ajusta un triángulo (más exacto que una parábola aquí).
        double cm = c[(best - 1 + m) % m], c0 = c[best], cp = c[(best + 1) % m];
        double slope = c0 - Math.min(cm, cp);
        double off = slope > 0 ? (cp - cm) / (2 * slope) : 0;
        double ang = 2 * Math.PI * (best + off) / m;
        ang %= 2 * Math.PI;
        if (ang < 0) ang += 2 * Math.PI;
        out.angle = ang;
        out.conf = c0 / m;
        out.margin = (c0 - second) / m;
        return out;
    }

    /**
     * Busca el centro real del disco (el usuario lo coloca a mano, con algunos
     * mm de error) maximizando la nitidez de la correlación. Devuelve
     * {cx, cy, angulo, conf}.
     */
    public static double[] refineCenter(int[] lum, int w, int h, double[] H) {
        double bx = 0, by = 0, bestScore = -1;
        Measurement bestM = null;
        double[][] passes = {{10, 2, 360}, {2, 0.5, 720}};
        for (double[] ps : passes) {
            double range = ps[0], step = ps[1];
            int m = (int) ps[2];
            double cx0 = bx, cy0 = by;
            bestScore = -1;
            for (double dx = -range; dx <= range + 1e-9; dx += step)
                for (double dy = -range; dy <= range + 1e-9; dy += step) {
                    Measurement me = measure(lum, w, h, H, cx0 + dx, cy0 + dy, m);
                    double sc = me.conf + me.margin;
                    if (sc > bestScore) {
                        bestScore = sc;
                        bx = cx0 + dx;
                        by = cy0 + dy;
                        bestM = me;
                    }
                }
        }
        return new double[]{bx, by, bestM == null ? 0 : bestM.angle, bestM == null ? 0 : bestM.conf};
    }
}
