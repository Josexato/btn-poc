package com.poc.scan3d.core;

import java.util.List;
import java.util.Locale;

/**
 * Momentos invariantes de Hu de una silueta binaria.
 *
 * Los 7 invariantes no cambian con traslación, escala ni rotación EN EL PLANO
 * de la imagen; por eso no dan el ángulo directamente, pero sí sirven para:
 *  - comparar siluetas: la silueta vuelve a parecerse a la inicial cuando el
 *    objeto completa 360° (detección de la vuelta completa en modo LIBRE);
 *  - descartar fotos anómalas (una mano en la escena cambia mucho la forma);
 *  - dar la orientación del eje principal de la silueta (momentos de 2º orden).
 */
public final class HuMoments {

    private HuMoments() {}

    public static final class Shape {
        public double[] hu = new double[7];
        public double[] logHu = new double[7];
        public double area, cx, cy;
        /** Orientación del eje principal (rad) en la imagen. */
        public double theta;

        @Override
        public String toString() {
            return String.format(Locale.US, "area=%.0f eje=%.1f° logHu=[%.2f %.2f %.2f %.2f]",
                    area, Math.toDegrees(theta), logHu[0], logHu[1], logHu[2], logHu[3]);
        }
    }

    public static Shape compute(byte[] mask, int w, int h) {
        double m00 = 0, m10 = 0, m01 = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (mask[y * w + x] != 0) { m00++; m10 += x; m01 += y; }
        Shape s = new Shape();
        s.area = m00;
        if (m00 < 1) return s;
        double cx = m10 / m00, cy = m01 / m00;
        s.cx = cx; s.cy = cy;
        double mu20 = 0, mu02 = 0, mu11 = 0, mu30 = 0, mu03 = 0, mu21 = 0, mu12 = 0;
        for (int y = 0; y < h; y++) {
            double dy = y - cy;
            for (int x = 0; x < w; x++) {
                if (mask[y * w + x] == 0) continue;
                double dx = x - cx;
                double dx2 = dx * dx, dy2 = dy * dy;
                mu20 += dx2; mu02 += dy2; mu11 += dx * dy;
                mu30 += dx2 * dx; mu03 += dy2 * dy; mu21 += dx2 * dy; mu12 += dx * dy2;
            }
        }
        s.theta = 0.5 * Math.atan2(2 * mu11, mu20 - mu02);
        double n2 = Math.pow(m00, 2), n3 = Math.pow(m00, 2.5);
        double e20 = mu20 / n2, e02 = mu02 / n2, e11 = mu11 / n2;
        double e30 = mu30 / n3, e03 = mu03 / n3, e21 = mu21 / n3, e12 = mu12 / n3;
        double[] h7 = s.hu;
        h7[0] = e20 + e02;
        h7[1] = sq(e20 - e02) + 4 * sq(e11);
        h7[2] = sq(e30 - 3 * e12) + sq(3 * e21 - e03);
        h7[3] = sq(e30 + e12) + sq(e21 + e03);
        h7[4] = (e30 - 3 * e12) * (e30 + e12) * (sq(e30 + e12) - 3 * sq(e21 + e03))
                + (3 * e21 - e03) * (e21 + e03) * (3 * sq(e30 + e12) - sq(e21 + e03));
        h7[5] = (e20 - e02) * (sq(e30 + e12) - sq(e21 + e03)) + 4 * e11 * (e30 + e12) * (e21 + e03);
        h7[6] = (3 * e21 - e03) * (e30 + e12) * (sq(e30 + e12) - 3 * sq(e21 + e03))
                - (e30 - 3 * e12) * (e21 + e03) * (3 * sq(e30 + e12) - sq(e21 + e03));
        for (int i = 0; i < 7; i++) {
            double v = h7[i];
            s.logHu[i] = v == 0 ? 0 : -Math.signum(v) * Math.log10(Math.abs(v));
        }
        return s;
    }

    private static double sq(double v) { return v * v; }

    /**
     * Distancia entre formas (tipo cv::matchShapes I3, pero sólo con los 4
     * primeros invariantes, que son los estables en siluetas reales), más un
     * término por cambio relativo de área.
     */
    public static double distance(Shape a, Shape b) {
        double d = 0;
        for (int i = 0; i < 4; i++) {
            double x = a.logHu[i], y = b.logHu[i];
            if (x == 0 || y == 0) continue;
            d += Math.abs(x - y) / Math.max(Math.abs(x), 1e-9);
        }
        double ar = Math.abs(Math.log(Math.max(a.area, 1) / Math.max(b.area, 1)));
        return d + ar;
    }

    /**
     * Estima cuántas fotos dura una vuelta completa (modo LIBRE). Compara la
     * secuencia inicial (ventana de "win" fotos) con la misma ventana
     * desplazada; la vuelta completa es donde la diferencia es mínima, buscando
     * a partir de la mitad de la secuencia (se pide girar un poco más de 360°).
     *
     * @return {período en fotos, distancia en el mínimo}, o null si no hay datos.
     */
    public static double[] period(List<Shape> seq, int win) {
        int n = seq.size();
        if (n < 8) return null;
        win = Math.max(1, Math.min(win, n / 4));
        int from = Math.max(4, n / 2);
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int p = from; p + win <= n; p++) {
            double d = 0;
            for (int k = 0; k < win; k++) d += distance(seq.get(k), seq.get(p + k));
            d /= win;
            if (d < bestD) { bestD = d; best = p; }
        }
        if (best < 0) return null;
        return new double[]{best, bestD};
    }
}
