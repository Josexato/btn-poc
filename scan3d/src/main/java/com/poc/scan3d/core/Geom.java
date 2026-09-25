package com.poc.scan3d.core;

/** Álgebra lineal mínima (matrices 3x3 en fila-mayor como double[9]). */
public final class Geom {

    private Geom() {}

    /** Resuelve A x = b por eliminación gaussiana con pivoteo parcial. Devuelve null si es singular. */
    public static double[] solve(double[][] a, double[] b) {
        int n = b.length;
        double[][] m = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], 0, m[i], 0, n);
            m[i][n] = b[i];
        }
        for (int c = 0; c < n; c++) {
            int p = c;
            for (int r = c + 1; r < n; r++) if (Math.abs(m[r][c]) > Math.abs(m[p][c])) p = r;
            if (Math.abs(m[p][c]) < 1e-12) return null;
            double[] tmp = m[c]; m[c] = m[p]; m[p] = tmp;
            for (int r = 0; r < n; r++) {
                if (r == c) continue;
                double f = m[r][c] / m[c][c];
                if (f == 0) continue;
                for (int k = c; k <= n; k++) m[r][k] -= f * m[c][k];
            }
        }
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = m[i][n] / m[i][i];
        return x;
    }

    /**
     * Homografía H (3x3, h33 = 1) que lleva src[i] -> dst[i]. Con 4 puntos es
     * exacta; con más, mínimos cuadrados. Los puntos se normalizan (Hartley)
     * para que el sistema esté bien condicionado.
     */
    public static double[] homography(double[][] src, double[][] dst) {
        double[] ts = normalizer(src), td = normalizer(dst);
        int n = src.length;
        double[][] ata = new double[8][8];
        double[] atb = new double[8];
        for (int i = 0; i < n; i++) {
            double x = (src[i][0] - ts[0]) * ts[2], y = (src[i][1] - ts[1]) * ts[2];
            double u = (dst[i][0] - td[0]) * td[2], v = (dst[i][1] - td[1]) * td[2];
            double[] r1 = {x, y, 1, 0, 0, 0, -u * x, -u * y};
            double[] r2 = {0, 0, 0, x, y, 1, -v * x, -v * y};
            accumulate(ata, atb, r1, u);
            accumulate(ata, atb, r2, v);
        }
        double[] h = solve(ata, atb);
        if (h == null) return null;
        double[] hn = {h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1};
        // Deshacer normalización: H = Td^-1 * Hn * Ts
        double[] tsm = {ts[2], 0, -ts[2] * ts[0], 0, ts[2], -ts[2] * ts[1], 0, 0, 1};
        double[] tdInv = {1 / td[2], 0, td[0], 0, 1 / td[2], td[1], 0, 0, 1};
        double[] H = mul(tdInv, mul(hn, tsm));
        return scale(H, 1.0 / H[8]);
    }

    private static void accumulate(double[][] ata, double[] atb, double[] r, double rhs) {
        for (int i = 0; i < 8; i++) {
            atb[i] += r[i] * rhs;
            for (int j = 0; j < 8; j++) ata[i][j] += r[i] * r[j];
        }
    }

    /** {mediaX, mediaY, escala} para que la distancia media al centro sea sqrt(2). */
    private static double[] normalizer(double[][] p) {
        double mx = 0, my = 0;
        for (double[] q : p) { mx += q[0]; my += q[1]; }
        mx /= p.length; my /= p.length;
        double d = 0;
        for (double[] q : p) d += Math.hypot(q[0] - mx, q[1] - my);
        d /= p.length;
        return new double[]{mx, my, d > 1e-12 ? Math.sqrt(2) / d : 1};
    }

    public static double[] mul(double[] a, double[] b) {
        double[] c = new double[9];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                c[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
        return c;
    }

    public static double[] scale(double[] a, double s) {
        double[] c = new double[a.length];
        for (int i = 0; i < a.length; i++) c[i] = a[i] * s;
        return c;
    }

    public static double det(double[] a) {
        return a[0] * (a[4] * a[8] - a[5] * a[7])
                - a[1] * (a[3] * a[8] - a[5] * a[6])
                + a[2] * (a[3] * a[7] - a[4] * a[6]);
    }

    public static double[] inv(double[] a) {
        double d = det(a);
        if (Math.abs(d) < 1e-300) return null;
        double[] c = {
                a[4] * a[8] - a[5] * a[7], a[2] * a[7] - a[1] * a[8], a[1] * a[5] - a[2] * a[4],
                a[5] * a[6] - a[3] * a[8], a[0] * a[8] - a[2] * a[6], a[2] * a[3] - a[0] * a[5],
                a[3] * a[7] - a[4] * a[6], a[1] * a[6] - a[0] * a[7], a[0] * a[4] - a[1] * a[3]};
        return scale(c, 1.0 / d);
    }

    public static double[] transpose(double[] a) {
        return new double[]{a[0], a[3], a[6], a[1], a[4], a[7], a[2], a[5], a[8]};
    }

    /** Aplica una homografía a (x, y). */
    public static double[] apply(double[] h, double x, double y) {
        double w = h[6] * x + h[7] * y + h[8];
        return new double[]{(h[0] * x + h[1] * y + h[2]) / w, (h[3] * x + h[4] * y + h[5]) / w};
    }

    public static double[] mulVec(double[] a, double[] v) {
        return new double[]{
                a[0] * v[0] + a[1] * v[1] + a[2] * v[2],
                a[3] * v[0] + a[4] * v[1] + a[5] * v[2],
                a[6] * v[0] + a[7] * v[1] + a[8] * v[2]};
    }

    /** Matriz de rotación alrededor de +Z. */
    public static double[] rotZ(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{c, -s, 0, s, c, 0, 0, 0, 1};
    }

    /** Rotación ortonormal más cercana (iteración de descomposición polar). */
    public static double[] orthonormalize(double[] r) {
        double[] x = r.clone();
        for (int it = 0; it < 30; it++) {
            double[] xi = inv(x);
            if (xi == null) break;
            double[] xit = transpose(xi);
            for (int k = 0; k < 9; k++) x[k] = 0.5 * (x[k] + xit[k]);
        }
        return x;
    }

    /** Diferencia angular envuelta a (-pi, pi]. */
    public static double wrap(double a) {
        a = a % (2 * Math.PI);
        if (a > Math.PI) a -= 2 * Math.PI;
        if (a <= -Math.PI) a += 2 * Math.PI;
        return a;
    }
}
