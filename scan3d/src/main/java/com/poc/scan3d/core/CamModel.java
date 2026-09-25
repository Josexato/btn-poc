package com.poc.scan3d.core;

import java.util.Locale;

/**
 * Cámara pinhole (sin distorsión) con pose respecto al mundo del tapete:
 * x_cam = R * X_mundo + t ;  u = f * x/z + cx ;  v = f * y/z + cy.
 */
public final class CamModel {

    public final int w, h;
    public final double f, cx, cy;
    public final double[] R; // 3x3 fila-mayor
    public final double[] t;
    /** Qué focal se terminó usando: "autocalib" (de la homografía) o "metadatos". */
    public final String fSource;

    public CamModel(int w, int h, double f, double cx, double cy, double[] R, double[] t, String fSource) {
        this.w = w; this.h = h; this.f = f; this.cx = cx; this.cy = cy;
        this.R = R; this.t = t; this.fSource = fSource;
    }

    /**
     * Focal (px) deducida sólo de la homografía del plano, suponiendo píxeles
     * cuadrados y punto principal en el centro de la imagen. Bien condicionada
     * cuando la cámara mira el tapete en ángulo (20-70°). Devuelve NaN si no
     * se puede estimar.
     */
    public static double focalFromHomography(double[] H, double cx, double cy) {
        double[] tInv = {1, 0, -cx, 0, 1, -cy, 0, 0, 1};
        double[] h = Geom.mul(tInv, H);
        double n = 0;
        for (double v : h) n += v * v;
        h = Geom.scale(h, 1 / Math.sqrt(n));
        double h11 = h[0], h12 = h[1], h21 = h[3], h22 = h[4], h31 = h[6], h32 = h[7];
        // Restricciones (con a = 1/f^2):  a*A_k + B_k = 0
        double a1 = h11 * h12 + h21 * h22, b1 = h31 * h32;
        double a2 = h11 * h11 + h21 * h21 - h12 * h12 - h22 * h22, b2 = h31 * h31 - h32 * h32;
        double den = a1 * a1 + a2 * a2;
        if (den < 1e-30) return Double.NaN;
        double a = -(a1 * b1 + a2 * b2) / den;
        if (!(a > 0)) return Double.NaN;
        return 1 / Math.sqrt(a);
    }

    /**
     * Pose a partir de la homografía mundo(X,Y,0) -> imagen.
     *
     * @param fMeta focal (px) según metadatos de la cámara; NaN si no hay.
     */
    public static CamModel fromHomography(double[] H, int w, int h, double fMeta) {
        double cx = w / 2.0, cy = h / 2.0;
        double fAuto = focalFromHomography(H, cx, cy);
        double f;
        String src;
        boolean autoOk = !Double.isNaN(fAuto) && fAuto > 0.3 * w && fAuto < 4 * w;
        if (autoOk && (Double.isNaN(fMeta) || (fAuto > 0.6 * fMeta && fAuto < 1.6 * fMeta))) {
            f = fAuto; src = "autocalib";
        } else if (!Double.isNaN(fMeta)) {
            f = fMeta; src = "metadatos";
        } else {
            f = 0.8 * w; src = "por defecto";
        }
        double[] kInv = {1 / f, 0, -cx / f, 0, 1 / f, -cy / f, 0, 0, 1};
        double[] m = Geom.mul(kInv, H);
        double[] c1 = {m[0], m[3], m[6]}, c2 = {m[1], m[4], m[7]}, c3 = {m[2], m[5], m[8]};
        double lam = 2.0 / (norm(c1) + norm(c2));
        if (c3[2] * lam < 0) lam = -lam; // el tapete debe quedar delante de la cámara
        double[] r1 = mulS(c1, lam), r2 = mulS(c2, lam), t = mulS(c3, lam);
        double[] r3 = cross(r1, r2);
        double[] R = {r1[0], r2[0], r3[0], r1[1], r2[1], r3[1], r1[2], r2[2], r3[2]};
        R = Geom.orthonormalize(R);
        return new CamModel(w, h, f, cx, cy, R, t, src);
    }

    /** Proyección 3x4 P = K [R | t]. */
    public double[] projection() {
        double[] K = {f, 0, cx, 0, f, cy, 0, 0, 1};
        double[] kr = Geom.mul(K, R);
        double[] kt = Geom.mulVec(K, t);
        return new double[]{kr[0], kr[1], kr[2], kt[0], kr[3], kr[4], kr[5], kt[1], kr[6], kr[7], kr[8], kt[2]};
    }

    /** Centro óptico en coordenadas mundo: C = -R^T t. */
    public double[] center() {
        double[] rt = Geom.transpose(R);
        double[] c = Geom.mulVec(rt, t);
        return new double[]{-c[0], -c[1], -c[2]};
    }

    public double[] project(double x, double y, double z) {
        double px = R[0] * x + R[1] * y + R[2] * z + t[0];
        double py = R[3] * x + R[4] * y + R[5] * z + t[1];
        double pz = R[6] * x + R[7] * y + R[8] * z + t[2];
        return new double[]{f * px / pz + cx, f * py / pz + cy, pz};
    }

    /** Ángulo de elevación de la cámara sobre el tapete (grados). */
    public double elevationDeg() {
        double[] c = center();
        return Math.toDegrees(Math.atan2(c[2], Math.hypot(c[0], c[1])));
    }

    public String describe() {
        double[] c = center();
        return String.format(Locale.US, "f=%.1fpx (%s) C=(%.0f, %.0f, %.0f)mm dist=%.0fmm elev=%.1f°",
                f, fSource, c[0], c[1], c[2], norm(c), elevationDeg());
    }

    static double norm(double[] v) { return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); }

    static double[] mulS(double[] v, double s) { return new double[]{v[0] * s, v[1] * s, v[2] * s}; }

    static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
}
