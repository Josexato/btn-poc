package com.poc.scan3d.core;

/**
 * Silueta del objeto por diferencia con una foto de fondo (misma cámara,
 * mismo encuadre, sin objeto).
 *
 * El disco gira junto con el objeto, así que su dibujo cambia respecto al
 * fondo. Para esos píxeles el fondo "esperado" se obtiene girando la foto de
 * fondo el mismo ángulo que se midió en el anillo (vía homografía del plano):
 * fondo compensado en rotación.
 */
public final class Segmenter {

    public final int w, h;
    private final int[] bg;          // ARGB fondo
    private final double[] H;        // mundo -> imagen
    private final double discCx, discCy, discR;
    private final float[] wx, wy;    // coordenadas mundo de cada píxel (sobre el plano)
    private final boolean[] onDisc;

    /** Umbral de diferencia de color (suma ponderada de |dY|, |dCb|, |dCr|). */
    public int threshold = 35;

    public Segmenter(int[] bgArgb, int w, int h, double[] H, double discCx, double discCy, double discR) {
        this.w = w; this.h = h; this.bg = bgArgb; this.H = H;
        this.discCx = discCx; this.discCy = discCy; this.discR = discR;
        wx = new float[w * h];
        wy = new float[w * h];
        onDisc = new boolean[w * h];
        double[] hi = Geom.inv(H);
        // Signo de w para puntos del plano realmente visibles (delante de la cámara).
        double[] c0 = Geom.apply(H, discCx, discCy);
        double sign0 = Math.signum(hi[6] * c0[0] + hi[7] * c0[1] + hi[8]);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                double[] p = Geom.apply(hi, x, y);
                int i = y * w + x;
                wx[i] = (float) p[0];
                wy[i] = (float) p[1];
                double dx = p[0] - discCx, dy = p[1] - discCy;
                // Por encima del horizonte la homografía "da la vuelta": no es plano visible.
                double ww = hi[6] * x + hi[7] * y + hi[8];
                onDisc[i] = Math.signum(ww) == sign0 && dx * dx + dy * dy <= (discR + 2) * (discR + 2);
            }
    }

    /**
     * @param frame  ARGB de la foto actual
     * @param dTheta giro del disco desde la foto de fondo (rad). 0 si el disco no se usa.
     * @param useDisc si false, todo el fondo se considera estático (modo LIBRE).
     * @return máscara 0/1 ya limpia (componente mayor, sin agujeros, dilatada 1 px)
     */
    public byte[] segment(int[] frame, double dTheta, boolean useDisc) {
        byte[] m = new byte[w * h];
        double[] tol = {0, Math.toRadians(0.6), -Math.toRadians(0.6)};
        double[][] rots = new double[tol.length][];
        for (int k = 0; k < tol.length; k++) rots[k] = Geom.rotZ(-(dTheta + tol[k]));
        int thr = threshold;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int c = frame[i];
                int d;
                if (useDisc && onDisc[i]) {
                    // Mínima diferencia contra el fondo girado (con tolerancia angular).
                    d = Integer.MAX_VALUE;
                    double px = wx[i] - discCx, py = wy[i] - discCy;
                    for (double[] r : rots) {
                        double qx = discCx + r[0] * px + r[1] * py;
                        double qy = discCy + r[3] * px + r[4] * py;
                        double[] uv = Geom.apply(H, qx, qy);
                        int b = sampleArgb(bg, w, h, uv[0], uv[1]);
                        if (b == 0) continue;
                        d = Math.min(d, colorDiff(c, b));
                    }
                    if (d == Integer.MAX_VALUE) d = colorDiff(c, bg[i]);
                } else {
                    d = colorDiff(c, bg[i]);
                }
                if (d > thr) m[i] = 1;
            }
        return clean(m, w, h);
    }

    /** Diferencia de color: luminancia pesa menos que el croma (menos sensible a sombras suaves). */
    public static int colorDiff(int a, int b) {
        int ra = (a >> 16) & 255, ga = (a >> 8) & 255, ba = a & 255;
        int rb = (b >> 16) & 255, gb = (b >> 8) & 255, bb = b & 255;
        int ya = (77 * ra + 150 * ga + 29 * ba) >> 8, yb = (77 * rb + 150 * gb + 29 * bb) >> 8;
        int cba = ba - ya, cbb = bb - yb, cra = ra - ya, crb = rb - yb;
        return (6 * Math.abs(ya - yb)) / 10 + Math.abs(cba - cbb) + Math.abs(cra - crb);
    }

    /** Bilineal sobre ARGB; devuelve 0 si cae fuera. */
    static int sampleArgb(int[] img, int w, int h, double x, double y) {
        if (x < 0 || y < 0 || x >= w - 1 || y >= h - 1) return 0;
        int x0 = (int) x, y0 = (int) y;
        double fx = x - x0, fy = y - y0;
        int i = y0 * w + x0;
        int a = img[i], b = img[i + 1], c = img[i + w], d = img[i + w + 1];
        int r = lerp4((a >> 16) & 255, (b >> 16) & 255, (c >> 16) & 255, (d >> 16) & 255, fx, fy);
        int g = lerp4((a >> 8) & 255, (b >> 8) & 255, (c >> 8) & 255, (d >> 8) & 255, fx, fy);
        int bl = lerp4(a & 255, b & 255, c & 255, d & 255, fx, fy);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static int lerp4(int a, int b, int c, int d, double fx, double fy) {
        return (int) Math.round((a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy);
    }

    /** Apertura 3x3, componente 8-conexa mayor, relleno de agujeros y dilatación 1 px. */
    public static byte[] clean(byte[] m, int w, int h) {
        m = dilate(erode(m, w, h), w, h);
        m = largestComponent(m, w, h);
        m = fillHoles(m, w, h);
        return dilate(m, w, h);
    }

    static byte[] erode(byte[] m, int w, int h) {
        byte[] o = new byte[w * h];
        for (int y = 1; y < h - 1; y++)
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                if (m[i] != 0 && m[i - 1] != 0 && m[i + 1] != 0 && m[i - w] != 0 && m[i + w] != 0
                        && m[i - w - 1] != 0 && m[i - w + 1] != 0 && m[i + w - 1] != 0 && m[i + w + 1] != 0)
                    o[i] = 1;
            }
        return o;
    }

    static byte[] dilate(byte[] m, int w, int h) {
        byte[] o = new byte[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                if (m[y * w + x] == 0) continue;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx >= 0 && xx < w) o[yy * w + xx] = 1;
                    }
                }
            }
        return o;
    }

    static byte[] largestComponent(byte[] m, int w, int h) {
        int[] lab = new int[w * h];
        int[] stack = new int[w * h];
        int bestId = 0, bestArea = 0, id = 0;
        for (int p = 0; p < w * h; p++) {
            if (m[p] == 0 || lab[p] != 0) continue;
            id++;
            int area = 0, sp = 0;
            stack[sp++] = p;
            lab[p] = id;
            while (sp > 0) {
                int q = stack[--sp];
                area++;
                int x = q % w, y = q / w;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= w) continue;
                        int r = yy * w + xx;
                        if (m[r] != 0 && lab[r] == 0) { lab[r] = id; stack[sp++] = r; }
                    }
                }
            }
            if (area > bestArea) { bestArea = area; bestId = id; }
        }
        byte[] o = new byte[w * h];
        if (bestId == 0) return o;
        for (int p = 0; p < w * h; p++) if (lab[p] == bestId) o[p] = 1;
        return o;
    }

    static byte[] fillHoles(byte[] m, int w, int h) {
        byte[] outside = new byte[w * h];
        int[] stack = new int[w * h];
        int sp = 0;
        for (int x = 0; x < w; x++) {
            sp = seed(m, outside, stack, sp, x);
            sp = seed(m, outside, stack, sp, (h - 1) * w + x);
        }
        for (int y = 0; y < h; y++) {
            sp = seed(m, outside, stack, sp, y * w);
            sp = seed(m, outside, stack, sp, y * w + w - 1);
        }
        while (sp > 0) {
            int q = stack[--sp];
            int x = q % w, y = q / w;
            if (x > 0) sp = seed(m, outside, stack, sp, q - 1);
            if (x < w - 1) sp = seed(m, outside, stack, sp, q + 1);
            if (y > 0) sp = seed(m, outside, stack, sp, q - w);
            if (y < h - 1) sp = seed(m, outside, stack, sp, q + w);
        }
        byte[] o = new byte[w * h];
        for (int p = 0; p < w * h; p++) o[p] = (byte) (outside[p] == 0 ? 1 : 0);
        return o;
    }

    private static int seed(byte[] m, byte[] outside, int[] stack, int sp, int p) {
        if (m[p] == 0 && outside[p] == 0) { outside[p] = 1; stack[sp++] = p; }
        return sp;
    }

    /** ¿La silueta toca el borde de la imagen? (típico de una mano entrando). */
    public static boolean touchesBorder(byte[] m, int w, int h) {
        for (int x = 0; x < w; x++) if (m[x] != 0 || m[(h - 1) * w + x] != 0) return true;
        for (int y = 0; y < h; y++) if (m[y * w] != 0 || m[y * w + w - 1] != 0) return true;
        return false;
    }
}
