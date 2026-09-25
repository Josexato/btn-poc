package com.poc.scan3d.core;

import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

/**
 * Casco visual (visual hull) por tallado de vóxeles.
 *
 * Marco "objeto": origen en el centro del disco sobre el plano, ejes alineados
 * con el mundo cuando el disco estaba en la posición de la foto de fondo. En
 * la foto i el objeto está girado theta_i alrededor de Z, así que un punto del
 * objeto P_o está en el mundo en  c + Rz(theta_i) * P_o.
 *
 * Un vóxel se elimina si su proyección cae fuera de la silueta en al menos
 * "tol" vistas (tolerancia a alguna silueta defectuosa).
 */
public final class Carver {

    private Carver() {}

    public static final class View {
        public final CamModel cam;
        public final BitMask mask;
        public final double theta;
        final double[] P; // 3x4 objeto -> píxel

        public View(CamModel cam, BitMask mask, double theta, double ccx, double ccy) {
            this.cam = cam; this.mask = mask; this.theta = theta;
            this.P = objectProjection(cam, theta, ccx, ccy);
        }
    }

    /** P = K[R|t] * [[Rz(theta), c], [0, 1]]. */
    public static double[] objectProjection(CamModel cam, double theta, double ccx, double ccy) {
        double[] pc = cam.projection();
        double[] rz = Geom.rotZ(theta);
        double[] out = new double[12];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++)
                out[r * 4 + c] = pc[r * 4] * rz[c] + pc[r * 4 + 1] * rz[3 + c] + pc[r * 4 + 2] * rz[6 + c];
            out[r * 4 + 3] = pc[r * 4] * ccx + pc[r * 4 + 1] * ccy + pc[r * 4 + 3];
        }
        return out;
    }

    public static final class Grid {
        public int nx, ny, nz;
        public double x0, y0, z0, step;
        public byte[] occ;

        public int idx(int i, int j, int k) { return (k * ny + j) * nx + i; }

        public int count() {
            int n = 0;
            for (byte b : occ) if (b != 0) n++;
            return n;
        }

        /** {xmin,xmax,ymin,ymax,zmin,zmax} de los vóxeles ocupados (mm) o null. */
        public double[] bounds() {
            int[] mn = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE}, mx = {-1, -1, -1};
            for (int k = 0; k < nz; k++)
                for (int j = 0; j < ny; j++)
                    for (int i = 0; i < nx; i++)
                        if (occ[idx(i, j, k)] != 0) {
                            mn[0] = Math.min(mn[0], i); mx[0] = Math.max(mx[0], i);
                            mn[1] = Math.min(mn[1], j); mx[1] = Math.max(mx[1], j);
                            mn[2] = Math.min(mn[2], k); mx[2] = Math.max(mx[2], k);
                        }
            if (mx[0] < 0) return null;
            return new double[]{x0 + mn[0] * step, x0 + mx[0] * step, y0 + mn[1] * step, y0 + mx[1] * step,
                    z0 + mn[2] * step, z0 + mx[2] * step};
        }

        @Override
        public String toString() {
            double[] b = bounds();
            return String.format(Locale.US, "grid %dx%dx%d paso=%.2fmm ocupados=%d%s", nx, ny, nz, step, count(),
                    b == null ? "" : String.format(Locale.US, " tamaño=%.1f x %.1f x %.1f mm",
                            b[1] - b[0] + step, b[3] - b[2] + step, b[5] - b[4] + step));
        }
    }

    public static Grid carve(List<View> views, double[] box, double step, int tol) {
        Grid g = new Grid();
        g.step = step;
        g.x0 = box[0] + step / 2; g.y0 = box[2] + step / 2; g.z0 = box[4] + step / 2;
        g.nx = Math.max(2, (int) Math.ceil((box[1] - box[0]) / step));
        g.ny = Math.max(2, (int) Math.ceil((box[3] - box[2]) / step));
        g.nz = Math.max(2, (int) Math.ceil((box[5] - box[4]) / step));
        final byte[] miss = new byte[g.nx * g.ny * g.nz];
        for (View v : views) {
            final double[] P = v.P;
            final BitMask m = v.mask;
            final int w = m.w, h = m.h;
            IntStream.range(0, g.nz).parallel().forEach(k -> {
                double z = g.z0 + k * g.step;
                for (int j = 0; j < g.ny; j++) {
                    double y = g.y0 + j * g.step;
                    double bu = P[1] * y + P[2] * z + P[3];
                    double bv = P[5] * y + P[6] * z + P[7];
                    double bw = P[9] * y + P[10] * z + P[11];
                    int base = (k * g.ny + j) * g.nx;
                    for (int i = 0; i < g.nx; i++) {
                        int id = base + i;
                        if (miss[id] >= tol) continue;
                        double x = g.x0 + i * g.step;
                        double ww = P[8] * x + bw;
                        boolean out;
                        if (ww <= 0) {
                            out = true;
                        } else {
                            double u = (P[0] * x + bu) / ww, vv = (P[4] * x + bv) / ww;
                            int iu = (int) Math.round(u), iv = (int) Math.round(vv);
                            out = iu < 0 || iv < 0 || iu >= w || iv >= h || !m.get(iu, iv);
                        }
                        if (out) miss[id]++;
                    }
                }
            });
        }
        g.occ = new byte[miss.length];
        for (int i = 0; i < miss.length; i++) g.occ[i] = (byte) (miss[i] < tol ? 1 : 0);
        keepLargestComponent(g);
        return g;
    }

    /**
     * Reconstrucción en dos pasadas: una gruesa sobre todo el cilindro del
     * disco para encontrar la caja del objeto, y otra fina ajustada a esa caja.
     *
     * @param res número de vóxeles en el lado más largo de la pasada fina.
     */
    public static Grid reconstruct(List<View> views, double radius, double maxHeight, int res, int tol,
                                   StringBuilder log) {
        double[] box = {-radius, radius, -radius, radius, 0.5, maxHeight};
        Grid coarse = carve(views, box, (2 * radius) / 64.0, tol);
        if (log != null) log.append("Pasada gruesa: ").append(coarse).append('\n');
        double[] b = coarse.bounds();
        if (b == null) return coarse;
        double m = 2 * coarse.step;
        double[] fine = {b[0] - m, b[1] + m, b[2] - m, b[3] + m, Math.max(0.5, b[4] - m), b[5] + m};
        double side = Math.max(fine[1] - fine[0], Math.max(fine[3] - fine[2], fine[5] - fine[4]));
        Grid g = carve(views, fine, side / res, tol);
        if (log != null) log.append("Pasada fina: ").append(g).append('\n');
        return g;
    }

    /** Elimina "islas" sueltas: deja sólo la componente 6-conexa más grande. */
    static void keepLargestComponent(Grid g) {
        int n = g.occ.length;
        int[] lab = new int[n];
        int[] stack = new int[n];
        int id = 0, bestId = 0, bestSize = 0;
        int sxy = g.nx * g.ny;
        for (int p = 0; p < n; p++) {
            if (g.occ[p] == 0 || lab[p] != 0) continue;
            id++;
            int sp = 0, size = 0;
            stack[sp++] = p;
            lab[p] = id;
            while (sp > 0) {
                int q = stack[--sp];
                size++;
                int i = q % g.nx, j = (q / g.nx) % g.ny, k = q / sxy;
                if (i > 0) sp = push(g, lab, stack, sp, q - 1, id);
                if (i < g.nx - 1) sp = push(g, lab, stack, sp, q + 1, id);
                if (j > 0) sp = push(g, lab, stack, sp, q - g.nx, id);
                if (j < g.ny - 1) sp = push(g, lab, stack, sp, q + g.nx, id);
                if (k > 0) sp = push(g, lab, stack, sp, q - sxy, id);
                if (k < g.nz - 1) sp = push(g, lab, stack, sp, q + sxy, id);
            }
            if (size > bestSize) { bestSize = size; bestId = id; }
        }
        for (int p = 0; p < n; p++) g.occ[p] = (byte) (lab[p] == bestId && bestId != 0 ? 1 : 0);
    }

    private static int push(Grid g, int[] lab, int[] stack, int sp, int q, int id) {
        if (g.occ[q] != 0 && lab[q] == 0) { lab[q] = id; stack[sp++] = q; }
        return sp;
    }
}
