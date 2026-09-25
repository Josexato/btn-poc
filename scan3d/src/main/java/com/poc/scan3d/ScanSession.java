package com.poc.scan3d;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.poc.scan3d.core.BitMask;
import com.poc.scan3d.core.CamModel;
import com.poc.scan3d.core.Carver;
import com.poc.scan3d.core.Colorizer;
import com.poc.scan3d.core.Geom;
import com.poc.scan3d.core.HuMoments;
import com.poc.scan3d.core.MatDetector;
import com.poc.scan3d.core.Mesh;
import com.poc.scan3d.core.RefLayout;
import com.poc.scan3d.core.RingAngle;
import com.poc.scan3d.core.Segmenter;
import com.poc.scan3d.core.SurfaceNets;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Estado de un escaneo: pasadas (una por altura de cámara), calibración por
 * cámara, fotos aceptadas con su silueta y su ángulo, y la reconstrucción.
 * No toca la UI; se llama desde un hilo de trabajo.
 */
final class ScanSession {

    enum Mode { DISCO, LIBRE }

    /** Calibración de una cámara en una pasada. */
    static final class Cal {
        int cam;
        int w, h;
        CamModel model;
        double[] H;
        double discCx, discCy;
        double bgAngle;      // ángulo absoluto del disco en la foto de fondo
        Segmenter seg;       // se libera al cerrar la pasada
        double[][] markerPx; // para dibujar
    }

    static final class Pass {
        int index;
        Cal[] cals;
        int triggers = 0;
        boolean[] coverage = new boolean[36];
        double startAngle = Double.NaN;
        double unwrapped = 0, lastAngle = Double.NaN;

        int covered() {
            int n = 0;
            for (boolean b : coverage) if (b) n++;
            return n;
        }
    }

    static final class Shot {
        int pass, cam, trigger;
        double angle = Double.NaN; // absoluto (DISCO) o calculado al final (LIBRE)
        BitMask mask;
        HuMoments.Shape shape;
        File jpg;
    }

    /** Resultado de procesar un disparo, para la UI. */
    static final class ShotResult {
        boolean accepted;
        String message;
        byte[] mask0;       // silueta de la cámara 0 (para dibujar)
        int w0, h0;
        double angleDeg = Double.NaN;
        int covered;
    }

    final File dir;
    final Mode mode;
    int threshold = 35;
    int resolution = 160;
    boolean ccw = true;      // sentido de giro (sólo LIBRE: Hu no distingue el sentido)
    final List<Pass> passes = new ArrayList<>();
    final List<Shot> shots = new ArrayList<>();
    private final StringBuilder log = new StringBuilder();
    private final Map<Integer, List<Double>> areaHistory = new HashMap<>();

    ScanSession(File root, Mode mode) {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        this.dir = new File(root, "scan_" + stamp);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        this.mode = mode;
        log("Sesión " + dir.getName() + " modo=" + mode);
    }

    synchronized void log(String s) {
        log.append(s).append('\n');
    }

    synchronized String logText() { return log.toString(); }

    Pass currentPass() { return passes.isEmpty() ? null : passes.get(passes.size() - 1); }

    /**
     * Nueva pasada a partir de las fotos de fondo (una por cámara, SIN objeto).
     * Devuelve un resumen; lanza IllegalStateException si la cámara 0 no ve el tapete.
     */
    String calibrate(List<CameraRig.Frame> frames, List<CameraRig.CamInfo> cams) {
        Pass p = new Pass();
        p.index = passes.size() + 1;
        p.cals = new Cal[cams.size()];
        StringBuilder sb = new StringBuilder("Pasada " + p.index + ":\n");
        for (CameraRig.Frame f : frames) {
            int[] lum = MatDetector.luminance(f.argb);
            MatDetector.Result mr = MatDetector.detect(lum, f.w, f.h);
            String name = cams.get(f.cam).label;
            if (mr == null) {
                sb.append("· ").append(name).append(": no veo los 4 marcadores\n");
                log("cal cam" + f.cam + " FALLO tapete");
                continue;
            }
            Cal c = new Cal();
            c.cam = f.cam;
            c.w = f.w;
            c.h = f.h;
            c.H = mr.H;
            c.markerPx = mr.imagePts;
            c.model = CamModel.fromHomography(mr.H, f.w, f.h, cams.get(f.cam).fMetaPx);
            if (mode == Mode.DISCO) {
                double[] dc = RingAngle.refineCenter(lum, f.w, f.h, mr.H);
                c.discCx = dc[0];
                c.discCy = dc[1];
                c.bgAngle = dc[2];
                if (dc[3] < 0.3) {
                    sb.append("· ").append(name).append(": tapete OK pero el anillo del disco no se lee bien (conf ")
                            .append(String.format(Locale.US, "%.2f", dc[3])).append(")\n");
                    log("cal cam" + f.cam + " anillo débil conf=" + dc[3]);
                    continue;
                }
            }
            Segmenter seg = new Segmenter(f.argb, f.w, f.h, mr.H, c.discCx, c.discCy, RefLayout.DISC_R);
            seg.threshold = threshold;
            c.seg = seg;
            p.cals[f.cam] = c;
            String info = String.format(Locale.US, "· %s: %s; tapete %s; centro disco (%.1f, %.1f) mm; f meta=%.0f px",
                    name, c.model.describe(), mr, c.discCx, c.discCy, cams.get(f.cam).fMetaPx);
            sb.append(info).append('\n');
            log("cal cam" + f.cam + " " + info);
            saveJpeg(f.argb, f.w, f.h, new File(dir, String.format(Locale.US, "p%d_c%d_fondo.jpg", p.index, f.cam)));
        }
        if (p.cals.length == 0 || p.cals[0] == null) {
            throw new IllegalStateException(sb.append("La cámara principal no quedó calibrada. Encuadra todo el tapete, "
                    + "con buena luz y sin objeto, y vuelve a intentarlo.").toString());
        }
        Pass prev = currentPass();
        if (prev != null) for (Cal c : prev.cals) if (c != null) c.seg = null; // liberar memoria
        passes.add(p);
        return sb.toString();
    }

    /** Procesa las fotos de un disparo (una por cámara). */
    ShotResult process(List<CameraRig.Frame> frames) {
        Pass p = currentPass();
        ShotResult r = new ShotResult();
        int trig = p.triggers++;
        double angle = Double.NaN;
        if (mode == Mode.DISCO) {
            RingAngle.Measurement best = null;
            StringBuilder angles = new StringBuilder();
            for (CameraRig.Frame f : frames) {
                Cal c = p.cals[f.cam];
                if (c == null) continue;
                RingAngle.Measurement me = RingAngle.measure(MatDetector.luminance(f.argb), f.w, f.h, c.H,
                        c.discCx, c.discCy, 720);
                angles.append(" c").append(f.cam).append('=').append(me);
                if (me.ok() && (best == null || me.conf > best.conf)) best = me;
            }
            if (best == null) {
                r.message = "Anillo no legible (¿mano o sombra encima?)";
                log("t" + trig + " rechazo anillo" + angles);
                return r;
            }
            angle = best.angle;
            log("t" + trig + " ángulo" + angles);
        }
        List<Shot> accepted = new ArrayList<>();
        String reason = null;
        for (CameraRig.Frame f : frames) {
            Cal c = p.cals[f.cam];
            if (c == null || c.seg == null) continue;
            c.seg.threshold = threshold;
            double d = mode == Mode.DISCO ? Geom.wrap(angle - c.bgAngle) : 0;
            byte[] mask = c.seg.segment(f.argb, d, mode == Mode.DISCO);
            if (f.cam == 0) { r.mask0 = mask; r.w0 = f.w; r.h0 = f.h; }
            HuMoments.Shape sh = HuMoments.compute(mask, f.w, f.h);
            String why = check(f.cam, mask, sh, f.w, f.h);
            if (why != null) {
                if (f.cam == 0) reason = why;
                log(String.format(Locale.US, "t%d c%d rechazada: %s (%s)", trig, f.cam, why, sh));
                continue;
            }
            Shot s = new Shot();
            s.pass = p.index;
            s.cam = f.cam;
            s.trigger = trig;
            s.angle = angle;
            s.mask = BitMask.fromBytes(mask, f.w, f.h);
            s.shape = sh;
            s.jpg = new File(dir, String.format(Locale.US, "p%d_c%d_%04d.jpg", p.index, f.cam, trig));
            saveJpeg(f.argb, f.w, f.h, s.jpg);
            accepted.add(s);
            log(String.format(Locale.US, "t%d c%d OK %s", trig, f.cam, sh));
        }
        synchronized (this) {
            shots.addAll(accepted);
        }
        r.accepted = !accepted.isEmpty();
        if (r.accepted && mode == Mode.DISCO) {
            if (Double.isNaN(p.startAngle)) { p.startAngle = angle; p.lastAngle = angle; }
            p.unwrapped += Geom.wrap(angle - p.lastAngle);
            p.lastAngle = angle;
            double rel = Geom.wrap(angle - p.startAngle);
            if (rel < 0) rel += 2 * Math.PI;
            p.coverage[Math.min(35, (int) (rel / (2 * Math.PI) * 36))] = true;
            r.angleDeg = Math.toDegrees(Geom.wrap(angle - p.startAngle));
        }
        r.covered = p.covered();
        r.message = r.accepted
                ? (mode == Mode.DISCO ? String.format(Locale.US, "Foto %d OK · giro %.1f° · vuelta %d/36",
                countShots(p.index, 0), Math.toDegrees(p.unwrapped), r.covered)
                : String.format(Locale.US, "Foto %d OK", countShots(p.index, 0)))
                : "Foto descartada: " + (reason == null ? "sin silueta" : reason);
        return r;
    }

    /** Validaciones de la silueta; devuelve el motivo de rechazo o null. */
    private String check(int cam, byte[] mask, HuMoments.Shape sh, int w, int h) {
        if (sh.area < 0.002 * w * h) return "silueta demasiado pequeña (¿falta el objeto?)";
        if (Segmenter.touchesBorder(mask, w, h)) return "la silueta toca el borde (¿una mano en la escena?)";
        List<Double> hist = areaHistory.computeIfAbsent(cam, k -> new ArrayList<>());
        if (hist.size() >= 5) {
            List<Double> sorted = new ArrayList<>(hist);
            java.util.Collections.sort(sorted);
            double med = sorted.get(sorted.size() / 2);
            if (sh.area > 2.2 * med || sh.area < 0.45 * med) return "área anómala respecto a las anteriores";
        }
        hist.add(sh.area);
        return null;
    }

    int countShots(int pass, int cam) {
        int n = 0;
        synchronized (this) {
            for (Shot s : shots) if (s.pass == pass && s.cam == cam) n++;
        }
        return n;
    }

    interface Progress {
        void onProgress(String msg);
    }

    /** Genera la malla y la exporta a OBJ/PLY/STL. Devuelve la malla. */
    Mesh reconstruct(Progress progress) throws IOException {
        List<Shot> use;
        synchronized (this) {
            use = new ArrayList<>(shots);
        }
        if (mode == Mode.LIBRE) use = assignFreeAngles(use, progress);
        List<Carver.View> views = new ArrayList<>();
        List<Shot> viewShots = new ArrayList<>();
        for (Shot s : use) {
            if (Double.isNaN(s.angle)) continue;
            Cal c = passes.get(s.pass - 1).cals[s.cam];
            views.add(new Carver.View(c.model, s.mask, s.angle, c.discCx, c.discCy));
            viewShots.add(s);
        }
        if (views.size() < 8) throw new IOException("Hacen falta al menos 8 fotos válidas (hay " + views.size() + ").");
        int tol = 1 + views.size() / 30;
        progress.onProgress("Tallando vóxeles con " + views.size() + " vistas…");
        StringBuilder carveLog = new StringBuilder();
        Carver.Grid g = Carver.reconstruct(views, 110, 3 * RefLayout.DISC_R, resolution, tol, carveLog);
        log(carveLog.toString().trim());
        if (g.count() == 0) throw new IOException("El tallado eliminó todo: revisa la calibración o el umbral.");
        progress.onProgress("Extrayendo superficie…");
        Mesh mesh = SurfaceNets.extract(g);
        mesh.taubinSmooth(4);
        mesh.computeNormals();
        progress.onProgress("Coloreando (" + mesh.vertexCount() + " vértices)…");
        // Para el color basta un subconjunto repartido de fotos.
        int stride = Math.max(1, views.size() / 36);
        List<Carver.View> cv = new ArrayList<>();
        List<Shot> cs = new ArrayList<>();
        for (int i = 0; i < views.size(); i += stride) { cv.add(views.get(i)); cs.add(viewShots.get(i)); }
        // El centro de rotación es por pasada, pero la malla está en el marco del disco:
        // Colorizer usa el centro de la primera pasada; se corrige por vista abajo.
        colorizePerPass(mesh, cv, cs);
        progress.onProgress("Guardando archivos…");
        mesh.writeObj(new File(dir, "modelo.obj"));
        mesh.writePly(new File(dir, "modelo.ply"));
        mesh.writeStl(new File(dir, "modelo.stl"));
        log("Malla: " + mesh.describe());
        saveLog();
        return mesh;
    }

    private void colorizePerPass(Mesh mesh, List<Carver.View> views, List<Shot> shotsForViews) {
        // Agrupar por pasada (cada una tiene su centro de disco) y mezclar pesos.
        Map<Integer, List<Integer>> byPass = new HashMap<>();
        for (int i = 0; i < views.size(); i++)
            byPass.computeIfAbsent(shotsForViews.get(i).pass, k -> new ArrayList<>()).add(i);
        float[] acc = null;
        int passesUsed = 0;
        for (Map.Entry<Integer, List<Integer>> e : byPass.entrySet()) {
            List<Carver.View> vs = new ArrayList<>();
            List<Shot> ss = new ArrayList<>();
            for (int i : e.getValue()) { vs.add(views.get(i)); ss.add(shotsForViews.get(i)); }
            Cal c0 = passes.get(e.getKey() - 1).cals[ss.get(0).cam];
            Colorizer.colorize(mesh, vs, c0.discCx, c0.discCy, i -> loadArgb(ss.get(i).jpg, vs.get(i).cam.w, vs.get(i).cam.h));
            if (acc == null) acc = new float[mesh.col.length];
            for (int k = 0; k < acc.length; k++) acc[k] += mesh.col[k];
            passesUsed++;
        }
        if (acc != null && passesUsed > 1) for (int k = 0; k < acc.length; k++) mesh.col[k] = acc[k] / passesUsed;
    }

    /**
     * Modo LIBRE (sin disco): el ángulo se deduce con los momentos de Hu.
     * La silueta vuelve a parecerse a la inicial al completar la vuelta; con
     * ese período (en segundos/disparos) se asume giro uniforme.
     */
    private List<Shot> assignFreeAngles(List<Shot> all, Progress progress) {
        List<Shot> out = new ArrayList<>();
        Pass p1 = passes.get(0);
        if (passes.size() > 1) log("Modo LIBRE: sólo se usa la pasada 1 (sin disco no hay marco común).");
        Map<Integer, HuMoments.Shape> byTrig = new HashMap<>();
        int maxT = 0;
        for (Shot s : all) if (s.pass == 1 && s.cam == 0) { byTrig.put(s.trigger, s.shape); maxT = Math.max(maxT, s.trigger); }
        int win = 3;
        double bestD = Double.MAX_VALUE;
        int period = -1;
        for (int per = Math.max(4, (maxT + 1) / 2); per <= maxT; per++) {
            double d = 0;
            int n = 0;
            for (int k = 0; k < win * 3 && n < win; k++) {
                HuMoments.Shape a = byTrig.get(k), b = byTrig.get(k + per);
                if (a == null || b == null) continue;
                d += HuMoments.distance(a, b);
                n++;
            }
            if (n == 0) continue;
            d /= n;
            if (d < bestD) { bestD = d; period = per; }
        }
        if (period < 0) {
            log("Hu: no se pudo estimar la vuelta completa.");
            return out;
        }
        String msg = String.format(Locale.US, "Hu: vuelta completa ≈ %d disparos (distancia %.3f) → %.1f° por disparo",
                period, bestD, 360.0 / period);
        log(msg);
        progress.onProgress(msg);
        double sign = ccw ? 1 : -1;
        for (Shot s : all) {
            if (s.pass != p1.index || s.trigger >= period) continue;
            s.angle = sign * 2 * Math.PI * s.trigger / period;
            out.add(s);
        }
        return out;
    }

    static int[] loadArgb(File jpg, int w, int h) {
        Bitmap b = BitmapFactory.decodeFile(jpg.getAbsolutePath());
        if (b == null) return null;
        if (b.getWidth() != w || b.getHeight() != h) {
            Bitmap s = Bitmap.createScaledBitmap(b, w, h, true);
            b.recycle();
            b = s;
        }
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        b.recycle();
        return px;
    }

    static void saveJpeg(int[] argb, int w, int h, File f) {
        Bitmap b = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.JPEG, 92, fos);
        } catch (IOException ignored) {
            // Sin la foto sólo se pierde el color de esa vista.
        } finally {
            b.recycle();
        }
    }

    void saveLog() {
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(new File(dir, "log.txt")),
                StandardCharsets.UTF_8)) {
            w.write(logText());
        } catch (IOException ignored) {
        }
    }

    File[] outputs() {
        return new File[]{new File(dir, "modelo.obj"), new File(dir, "modelo.ply"), new File(dir, "modelo.stl"),
                new File(dir, "log.txt")};
    }
}
