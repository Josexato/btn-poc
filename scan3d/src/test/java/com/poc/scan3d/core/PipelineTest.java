package com.poc.scan3d.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.Test;

/**
 * Prueba de extremo a extremo con imágenes sintéticas: detectar tapete ->
 * pose -> centro y ángulo del disco -> siluetas -> Hu -> tallado -> malla.
 */
public class PipelineTest {

    @Test
    public void mSequenceIsMaximal() {
        int ones = 0;
        for (boolean b : RefLayout.CODE) if (b) ones++;
        assertEquals(32, ones);
        // Autocorrelación circular ±1: 63 en 0 y -1 en el resto.
        for (int s = 1; s < 63; s++) {
            int acc = 0;
            for (int k = 0; k < 63; k++)
                acc += (RefLayout.CODE[k] ? 1 : -1) * (RefLayout.CODE[(k + s) % 63] ? 1 : -1);
            assertEquals(-1, acc);
        }
    }

    @Test
    public void homographyRoundTrip() {
        double[][] src = {{0, 0}, {10, 0}, {10, 10}, {0, 10}, {5, 3}};
        double[] Ht = {1.2, 0.1, 30, -0.2, 0.9, 50, 0.001, 0.002, 1};
        double[][] dst = new double[src.length][];
        for (int i = 0; i < src.length; i++) dst[i] = Geom.apply(Ht, src[i][0], src[i][1]);
        double[] H = Geom.homography(src, dst);
        for (int i = 0; i < 9; i++) assertEquals(Ht[i], H[i], 1e-6);
    }

    @Test
    public void huInvariantUnderImageRotation() {
        int w = 200, h = 200;
        byte[] a = new byte[w * h], b = new byte[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                double dx = x - 100, dy = y - 100;
                // Forma en "L" y la misma girada 90°.
                if ((Math.abs(dx) < 40 && dy > -10 && dy < 10) || (dx > 20 && dx < 40 && dy > -50 && dy < 10)) a[y * w + x] = 1;
                double rx = dy, ry = -dx;
                if ((Math.abs(rx) < 40 && ry > -10 && ry < 10) || (rx > 20 && rx < 40 && ry > -50 && ry < 10)) b[y * w + x] = 1;
            }
        HuMoments.Shape sa = HuMoments.compute(a, w, h), sb = HuMoments.compute(b, w, h);
        for (int i = 0; i < 4; i++) assertEquals(sa.logHu[i], sb.logHu[i], 0.02);
        assertTrue(HuMoments.distance(sa, sb) < 0.02);
    }

    /** Resultado de una pasada (una altura de cámara). */
    static final class Pass {
        List<Carver.View> views = new ArrayList<>();
        List<HuMoments.Shape> shapes = new ArrayList<>();
        Map<Integer, int[]> images = new HashMap<>();
        double cx, cy;
    }

    /**
     * Simula una pasada: foto de fondo, detección, y n fotos girando ~10°.
     * El objeto va solidario al disco (marco objeto = marco del disco).
     */
    private static Pass runPass(SyntheticScene sc, double disc0, int n, boolean keepImages) {
        int w = sc.w, h = sc.h;
        int[] bg = sc.render(disc0, disc0, false, null);
        int[] lum = MatDetector.luminance(bg);
        MatDetector.Result mr = MatDetector.detect(lum, w, h);
        assertNotNull("no se detectó el tapete", mr);
        CamModel truth = sc.trueCam();
        for (int i = 0; i < 4; i++) {
            double[] p = truth.project(RefLayout.MARKERS[i][0], RefLayout.MARKERS[i][1], 0);
            double err = Math.hypot(p[0] - mr.imagePts[i][0], p[1] - mr.imagePts[i][1]);
            assertTrue("marcador " + i + " err=" + err, err < 1.5);
        }
        CamModel cam = CamModel.fromHomography(mr.H, w, h, Double.NaN);
        System.out.println("Cámara estimada: " + cam.describe());
        System.out.println("Cámara real:     " + truth.describe());
        assertEquals(sc.f, cam.f, sc.f * 0.05);
        double[] c1 = cam.center(), c2 = truth.center();
        assertTrue(Math.hypot(Math.hypot(c1[0] - c2[0], c1[1] - c2[1]), c1[2] - c2[2]) < 25);

        double[] dc = RingAngle.refineCenter(lum, w, h, mr.H);
        System.out.printf(Locale.US, "Centro disco: (%.2f, %.2f) ángulo=%.2f°%n", dc[0], dc[1], Math.toDegrees(dc[2]));
        assertEquals(sc.discCx, dc[0], 1.5);
        assertEquals(sc.discCy, dc[1], 1.5);
        assertEquals(0, Math.toDegrees(Geom.wrap(dc[2] - disc0)), 0.7);

        Pass ps = new Pass();
        ps.cx = dc[0];
        ps.cy = dc[1];
        Segmenter seg = new Segmenter(bg, w, h, mr.H, dc[0], dc[1], RefLayout.DISC_R);
        double maxAngErr = 0, minIou = 1;
        for (int i = 0; i < n; i++) {
            double ang = disc0 + Math.toRadians(10 * i + 0.37 * (i % 3));
            byte[] truthMask = new byte[w * h];
            int[] img = sc.render(ang, ang, true, truthMask);
            int[] l = MatDetector.luminance(img);
            RingAngle.Measurement me = RingAngle.measure(l, w, h, mr.H, dc[0], dc[1], 720);
            assertTrue("anillo no legible en foto " + i + ": " + me, me.ok());
            double err = Math.abs(Math.toDegrees(Geom.wrap(me.angle - ang)));
            maxAngErr = Math.max(maxAngErr, err);
            byte[] mask = seg.segment(img, Geom.wrap(me.angle - dc[2]), true);
            assertTrue("silueta toca el borde", !Segmenter.touchesBorder(mask, w, h));
            minIou = Math.min(minIou, iou(mask, truthMask));
            ps.shapes.add(HuMoments.compute(mask, w, h));
            ps.views.add(new Carver.View(cam, BitMask.fromBytes(mask, w, h), me.angle, dc[0], dc[1]));
            if (keepImages && i < 36 && i % 4 == 0) ps.images.put(ps.views.size() - 1, img);
        }
        System.out.printf(Locale.US, "Error angular máx=%.2f°  IoU mín=%.3f%n", maxAngErr, minIou);
        assertTrue(maxAngErr < 0.8);
        assertTrue(minIou > 0.9);
        return ps;
    }

    @Test
    public void fullPipelineOnSyntheticScene() throws Exception {
        int w = 960, h = 720;
        // Pasada 1: cámara alta (38°). Pasada 2: cámara baja (20°) desde otro azimut.
        SyntheticScene hi = new SyntheticScene(w, h, 820, 470, 38, 12, 3, -2);
        SyntheticScene lo = new SyntheticScene(w, h, 900, 430, 20, -30, 3, -2);
        Pass p1 = runPass(hi, Math.toRadians(25), 38, true);

        // Hu: la vuelta completa debe detectarse en ~36 fotos.
        double[] per = HuMoments.period(p1.shapes, 3);
        System.out.printf(Locale.US, "Periodo Hu: %.0f fotos (dist=%.4f)%n", per[0], per[1]);
        assertEquals(36, per[0], 1.0);

        // Una sola pasada alta: queda un "techo" sobre la cara plana superior
        // (límite del casco visual): altura real 80, esperado ~80 + 20*tan(38°).
        StringBuilder log = new StringBuilder();
        List<Carver.View> turn = p1.views.subList(0, 36);
        Carver.Grid g1 = Carver.reconstruct(turn, RefLayout.DISC_R, 3 * RefLayout.DISC_R, 96, 2, log);
        double top1 = g1.bounds()[5] + g1.step / 2;
        System.out.printf(Locale.US, "Altura con 1 pasada: %.1f mm%n", top1);
        assertTrue(top1 > 84 && top1 < 100);

        // Dos pasadas combinadas: el techo desaparece.
        Pass p2 = runPass(lo, Math.toRadians(-70), 36, false);
        List<Carver.View> all = new ArrayList<>(turn);
        all.addAll(p2.views);
        Carver.Grid g = Carver.reconstruct(all, RefLayout.DISC_R, 3 * RefLayout.DISC_R, 128, 2, log);
        System.out.print(log);
        // Objeto real: x -30..46, y -20..20, z 0..80.
        double[] b = g.bounds();
        double s = g.step;
        assertEquals(-30, b[0] - s / 2, 3);
        assertEquals(46, b[1] + s / 2, 3);
        assertEquals(-20, b[2] - s / 2, 3);
        assertEquals(20, b[3] + s / 2, 3);
        assertEquals(80, b[5] + s / 2, 5);

        Mesh mesh = SurfaceNets.extract(g);
        mesh.taubinSmooth(4);
        mesh.computeNormals();
        assertTrue(isClosed(mesh));
        double vol = signedVolume(mesh);
        double real = 60 * 40 * 80 + 16 * 20 * 24;
        System.out.printf(Locale.US, "Malla: %s volumen=%.0f (real %.0f)%n", mesh.describe(), vol, real);
        assertTrue("volumen con signo debe ser positivo (normales hacia fuera)", vol > 0);
        assertEquals(real, vol, real * 0.2);

        Colorizer.colorize(mesh, turn, p1.cx, p1.cy, i -> p1.images.get(i));
        File out = new File("build/test-output");
        out.mkdirs();
        mesh.writeObj(new File(out, "sintetico.obj"));
        mesh.writePly(new File(out, "sintetico.ply"));
        mesh.writeStl(new File(out, "sintetico.stl"));
        // La caja grande es roja: la media de color en su cara superior debe ser rojiza.
        double rs = 0, gs = 0;
        int cnt = 0;
        for (int i = 0; i < mesh.vertexCount(); i++)
            if (mesh.pos[i * 3 + 2] > 75 && Math.abs(mesh.pos[i * 3]) < 20 && Math.abs(mesh.pos[i * 3 + 1]) < 10) {
                rs += mesh.col[i * 3]; gs += mesh.col[i * 3 + 1]; cnt++;
            }
        assertTrue(cnt > 0 && rs > 2 * gs);
    }

    private static double iou(byte[] a, byte[] b) {
        int inter = 0, uni = 0;
        for (int i = 0; i < a.length; i++) {
            boolean x = a[i] != 0, y = b[i] != 0;
            if (x && y) inter++;
            if (x || y) uni++;
        }
        return uni == 0 ? 1 : inter / (double) uni;
    }

    private static boolean isClosed(Mesh m) {
        Map<Long, Integer> edges = new HashMap<>();
        for (int t = 0; t < m.tri.length; t += 3)
            for (int e = 0; e < 3; e++) {
                long a = m.tri[t + e], b = m.tri[t + (e + 1) % 3];
                long key = Math.min(a, b) * 10_000_000L + Math.max(a, b);
                edges.merge(key, 1, Integer::sum);
            }
        int bad = 0;
        for (int c : edges.values()) if (c != 2) bad++;
        System.out.println("Aristas no-manifold/borde: " + bad + " de " + edges.size());
        return bad < edges.size() / 500 + 1;
    }

    private static double signedVolume(Mesh m) {
        double v = 0;
        for (int t = 0; t < m.tri.length; t += 3) {
            int a = m.tri[t] * 3, b = m.tri[t + 1] * 3, c = m.tri[t + 2] * 3;
            float[] p = m.pos;
            v += p[a] * (p[b + 1] * p[c + 2] - p[b + 2] * p[c + 1])
                    - p[a + 1] * (p[b] * p[c + 2] - p[b + 2] * p[c])
                    + p[a + 2] * (p[b] * p[c + 1] - p[b + 1] * p[c]);
        }
        return v / 6;
    }
}
