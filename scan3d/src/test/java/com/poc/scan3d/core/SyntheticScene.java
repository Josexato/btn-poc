package com.poc.scan3d.core;

/**
 * Renderizador por trazado de rayos de una escena sintética: tapete + disco
 * codificado + objeto hecho de cajas, visto por una cámara pinhole conocida.
 * Sirve para validar todo el pipeline sin teléfono.
 */
final class SyntheticScene {

    final int w, h;
    final double f;
    final double[] R, t, C;
    final double discCx, discCy;
    /** Cajas del objeto: {cx, cy, cz, hx, hy, hz, r, g, b} en el marco objeto. */
    final double[][] boxes = {
            {0, 0, 40, 30, 20, 40, 200, 60, 50},
            {38, 0, 12, 8, 10, 12, 40, 90, 200},
    };

    SyntheticScene(int w, int h, double f, double dist, double elevDeg, double azimDeg,
                   double discCx, double discCy) {
        this.w = w; this.h = h; this.f = f;
        this.discCx = discCx; this.discCy = discCy;
        double e = Math.toRadians(elevDeg), az = Math.toRadians(azimDeg);
        C = new double[]{dist * Math.cos(e) * Math.sin(az), -dist * Math.cos(e) * Math.cos(az), dist * Math.sin(e)};
        double[] target = {0, 0, 25};
        double[] zc = norm(sub(target, C));
        double[] xc = norm(cross(zc, new double[]{0, 0, 1}));
        double[] yc = cross(zc, xc);
        R = new double[]{xc[0], xc[1], xc[2], yc[0], yc[1], yc[2], zc[0], zc[1], zc[2]};
        double[] rc = Geom.mulVec(R, C);
        t = new double[]{-rc[0], -rc[1], -rc[2]};
    }

    CamModel trueCam() {
        return new CamModel(w, h, f, w / 2.0, h / 2.0, R, t, "verdad");
    }

    /**
     * @param discAngle ángulo absoluto del disco (rad)
     * @param objAngle  giro del objeto respecto a su marco (rad)
     * @param withObject si se dibuja el objeto
     * @param truthMask si no es null, se llena con la silueta real del objeto
     */
    int[] render(double discAngle, double objAngle, boolean withObject, byte[] truthMask) {
        int[] out = new int[w * h];
        double[] rt = Geom.transpose(R);
        double co = Math.cos(-objAngle), so = Math.sin(-objAngle);
        double cd = Math.cos(-discAngle), sd = Math.sin(-discAngle);
        double[] oc = {C[0] - discCx, C[1] - discCy, C[2]};
        double[] oo = {co * oc[0] - so * oc[1], so * oc[0] + co * oc[1], oc[2]};
        int ss = 2;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                double r = 0, g = 0, b = 0;
                int hits = 0;
                for (int sy = 0; sy < ss; sy++)
                    for (int sx = 0; sx < ss; sx++) {
                        double u = x + (sx + 0.5) / ss - 0.5, v = y + (sy + 0.5) / ss - 0.5;
                        double[] dc = {(u - w / 2.0) / f, (v - h / 2.0) / f, 1};
                        double[] d = Geom.mulVec(rt, dc);
                        double[] col = null;
                        if (withObject) {
                            double[] dobj = {co * d[0] - so * d[1], so * d[0] + co * d[1], d[2]};
                            col = hitObject(oo, dobj);
                        }
                        if (col != null) {
                            hits++;
                        } else {
                            double tt = -C[2] / d[2];
                            double inten;
                            if (d[2] >= 0 || tt <= 0) {
                                inten = 0.55;
                            } else {
                                double px = C[0] + tt * d[0], py = C[1] + tt * d[1];
                                double qx = px - discCx, qy = py - discCy;
                                if (qx * qx + qy * qy <= RefLayout.DISC_R * RefLayout.DISC_R) {
                                    inten = RefLayout.sampleDisc(cd * qx - sd * qy, sd * qx + cd * qy);
                                } else {
                                    inten = RefLayout.sampleMat(px, py);
                                }
                            }
                            double gray = 20 + 215 * inten;
                            col = new double[]{gray, gray, gray * 0.97};
                        }
                        r += col[0]; g += col[1]; b += col[2];
                    }
                int n = ss * ss;
                int ri = clamp(r / n + noise(x, y, 1)), gi = clamp(g / n + noise(x, y, 2)), bi = clamp(b / n + noise(x, y, 3));
                out[y * w + x] = 0xFF000000 | (ri << 16) | (gi << 8) | bi;
                if (truthMask != null) truthMask[y * w + x] = (byte) (hits * 2 >= n ? 1 : 0);
            }
        return out;
    }

    private double[] hitObject(double[] o, double[] d) {
        double best = Double.MAX_VALUE;
        double[] col = null;
        for (double[] bx : boxes) {
            double tmin = -Double.MAX_VALUE, tmax = Double.MAX_VALUE;
            int axis = -1;
            double sign = 0;
            boolean miss = false;
            for (int a = 0; a < 3; a++) {
                double lo = bx[a] - bx[3 + a], hi = bx[a] + bx[3 + a];
                if (Math.abs(d[a]) < 1e-12) {
                    if (o[a] < lo || o[a] > hi) { miss = true; break; }
                    continue;
                }
                double t1 = (lo - o[a]) / d[a], t2 = (hi - o[a]) / d[a];
                double s = -1;
                if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; s = 1; }
                if (t1 > tmin) { tmin = t1; axis = a; sign = s; }
                tmax = Math.min(tmax, t2);
            }
            if (miss || tmin > tmax || tmax < 0 || tmin < 0) continue;
            if (tmin < best) {
                best = tmin;
                double[] n = {0, 0, 0};
                n[axis] = sign;
                double shade = 0.55 + 0.45 * Math.abs(n[0] * 0.3 + n[1] * -0.5 + n[2] * 0.8);
                col = new double[]{bx[6] * shade, bx[7] * shade, bx[8] * shade};
            }
        }
        return col;
    }

    private static double noise(int x, int y, int c) {
        int hsh = x * 73856093 ^ y * 19349663 ^ c * 83492791;
        hsh ^= hsh >>> 13;
        hsh *= 0x5bd1e995;
        hsh ^= hsh >>> 15;
        return ((hsh & 1023) / 1023.0 - 0.5) * 6;
    }

    private static int clamp(double v) { return (int) Math.max(0, Math.min(255, Math.round(v))); }

    static double[] sub(double[] a, double[] b) { return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }

    static double[] norm(double[] a) {
        double l = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        return new double[]{a[0] / l, a[1] / l, a[2] / l};
    }

    static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
}
