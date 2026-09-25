package com.poc.scan3d;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.poc.scan3d.core.Geom;
import com.poc.scan3d.core.Mesh;
import com.poc.scan3d.core.RefLayout;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Flujo:
 *  1) Imprimir el escenario (tapete + disco).
 *  2) Teléfono fijo (trípode) mirando el tapete. "Foto de fondo" SIN objeto:
 *     fija exposición/enfoque, detecta el tapete y calibra cada cámara.
 *  3) Poner el objeto en el centro del disco y "Iniciar": una foto por
 *     segundo (cuando la escena está quieta) mientras se gira el disco.
 *  4) Opcional: otra pasada con el teléfono a otra altura (mejora la parte de arriba).
 *  5) "Generar 3D": casco visual -> malla -> OBJ/PLY/STL.
 */
public class MainActivity extends Activity implements CameraRig.Listener {

    private static final int REQ_CAM = 1;

    private PreviewView preview;
    private TextView status, logView;
    private Button bMode, bCams, bCalib, bCapture, bBuild, bView, bShare, bThr, bRes, bGate, bDir;

    private CameraRig rig;
    private List<CameraRig.CamInfo> allCams = new ArrayList<>();
    private final List<CameraRig.CamInfo> selected = new ArrayList<>();
    private ScanSession session;
    private ScanSession.Mode mode = ScanSession.Mode.DISCO;
    private int threshold = 35, resolution = 160;
    private boolean gate = true, ccw = true;

    private HandlerThread workThread;
    private Handler work;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // Captura
    private boolean capturing = false, busy = false, calibrating = false;
    private final List<CameraRig.Frame> pending = new ArrayList<>();
    private int[] lastThumb;
    private int stillCount = 0;
    private long lastShotMs = 0;
    private static Mesh lastMesh;

    static Mesh lastMesh() { return lastMesh; }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        workThread = new HandlerThread("proceso");
        workThread.start();
        work = new Handler(workThread.getLooper());
        buildUi();
        rig = new CameraRig(this, this);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        preview = new PreviewView(this);
        root.addView(preview, new LinearLayout.LayoutParams(0, -1, 3f));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(12, 12, 12, 12);
        ScrollView sv = new ScrollView(this);
        sv.addView(panel);
        root.addView(sv, new LinearLayout.LayoutParams(0, -1, 1.4f));

        status = new TextView(this);
        status.setText("Listo");
        panel.addView(status);
        add(panel, "Imprimir escenario (PDF)", v -> printSheets());
        bMode = add(panel, "", v -> { mode = mode == ScanSession.Mode.DISCO ? ScanSession.Mode.LIBRE : ScanSession.Mode.DISCO; newSession(); refresh(); });
        bCams = add(panel, "Cámaras…", v -> chooseCameras());
        bCalib = add(panel, "", v -> calibrate());
        bCapture = add(panel, "", v -> toggleCapture());
        bBuild = add(panel, "Generar 3D", v -> build());
        bView = add(panel, "Ver 3D", v -> startActivity(new Intent(this, MeshViewActivity.class)));
        bShare = add(panel, "Compartir modelo + log", v -> share());
        add(panel, "Nueva sesión", v -> { newSession(); refresh(); });
        bThr = add(panel, "", v -> { threshold = next(threshold, new int[]{20, 28, 35, 45, 60}); applySettings(); });
        bRes = add(panel, "", v -> { resolution = next(resolution, new int[]{96, 128, 160, 200, 256}); applySettings(); });
        bGate = add(panel, "", v -> { gate = !gate; refresh(); });
        bDir = add(panel, "", v -> { ccw = !ccw; applySettings(); });
        add(panel, "Diagnóstico de cámaras (JSON)", v -> probe());
        logView = new TextView(this);
        logView.setTextSize(11);
        panel.addView(logView);
        setContentView(root);
        newSession();
        refresh();
    }

    private Button add(LinearLayout p, String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        p.addView(b);
        return b;
    }

    private static int next(int cur, int[] opts) {
        for (int i = 0; i < opts.length; i++) if (opts[i] == cur) return opts[(i + 1) % opts.length];
        return opts[0];
    }

    private void applySettings() {
        if (session != null) {
            session.threshold = threshold;
            session.resolution = resolution;
            session.ccw = ccw;
        }
        refresh();
    }

    private void refresh() {
        bMode.setText("Modo: " + (mode == ScanSession.Mode.DISCO ? "DISCO (ángulo por anillo)" : "LIBRE (ángulo por Hu)"));
        ScanSession.Pass p = session == null ? null : session.currentPass();
        bCalib.setText(p == null ? "Foto de fondo (sin objeto)" : "Nueva pasada: foto de fondo");
        bCapture.setText(capturing ? "■ Detener captura" : "● Iniciar captura (1 foto/s)");
        bCapture.setEnabled(p != null && !calibrating);
        bBuild.setEnabled(!capturing && session != null && !session.shots.isEmpty());
        bView.setEnabled(lastMesh != null);
        bShare.setEnabled(lastMesh != null);
        bThr.setText("Umbral silueta: " + threshold);
        bRes.setText("Resolución 3D: " + resolution + " vóxeles");
        bGate.setText("Esperar escena quieta: " + (gate ? "Sí" : "No"));
        bDir.setText("Sentido (LIBRE): " + (ccw ? "antihorario" : "horario"));
        bDir.setVisibility(mode == ScanSession.Mode.LIBRE ? View.VISIBLE : View.GONE);
        StringBuilder cams = new StringBuilder();
        for (CameraRig.CamInfo c : rig == null ? new ArrayList<CameraRig.CamInfo>() : rig.activeCameras())
            cams.append(cams.length() > 0 ? ", " : "").append(c.physicalId == null ? "lógica" : c.physicalId);
        bCams.setText("Cámaras: " + (cams.length() == 0 ? "—" : cams));
    }

    private void newSession() {
        if (capturing) toggleCapture();
        File root = new File(getExternalFilesDir(null), "scans");
        session = new ScanSession(root, mode);
        applySettings();
        preview.setCalibration(null, null);
        preview.setMask(null, 0, 0);
        preview.setCoverage(null);
        log("Nueva sesión en " + session.dir.getAbsolutePath());
    }

    private void log(String s) {
        ui.post(() -> {
            String t = s + "\n" + logView.getText();
            logView.setText(t.length() > 4000 ? t.substring(0, 4000) : t);
        });
    }

    private void setStatus(String s) {
        ui.post(() -> {
            status.setText(s);
            preview.setBanner(s);
        });
    }

    // ---------------------------------------------------------------- cámara

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
            return;
        }
        openCamera();
    }

    @Override
    protected void onPause() {
        if (capturing) toggleCapture();
        rig.close();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        workThread.quitSafely();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (code == REQ_CAM && r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) openCamera();
        else setStatus("Sin permiso de cámara");
    }

    private void openCamera() {
        try {
            allCams = rig.enumerate();
            if (allCams.isEmpty()) { setStatus("No hay cámara trasera"); return; }
            if (selected.isEmpty()) selected.add(allCams.get(0));
            rig.open(selected);
            CameraRig.CamInfo c0 = selected.get(0);
            preview.setImageSize(c0.size.getWidth(), c0.size.getHeight());
            preview.setRotation(previewRotation(c0.sensorOrientation));
        } catch (Exception e) {
            setStatus("Error al abrir la cámara: " + e.getMessage());
        }
    }

    private int previewRotation(int sensorOrientation) {
        int r = getDisplay() == null ? Surface.ROTATION_90 : getDisplay().getRotation();
        int deg = r == Surface.ROTATION_0 ? 0 : r == Surface.ROTATION_90 ? 90 : r == Surface.ROTATION_180 ? 180 : 270;
        return (sensorOrientation - deg + 360) % 360;
    }

    private void chooseCameras() {
        if (session.currentPass() != null) {
            Toast.makeText(this, "Cambiar cámaras reinicia la sesión", Toast.LENGTH_SHORT).show();
        }
        String[] names = new String[allCams.size()];
        boolean[] checked = new boolean[allCams.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = allCams.get(i).label;
            for (CameraRig.CamInfo s : selected) if (s == allCams.get(i)) checked[i] = true;
        }
        new AlertDialog.Builder(this)
                .setTitle("Lógica sola, o una o varias físicas a la vez")
                .setMultiChoiceItems(names, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Aplicar", (d, w) -> {
                    List<CameraRig.CamInfo> sel = new ArrayList<>();
                    for (int i = 1; i < names.length; i++) if (checked[i]) sel.add(allCams.get(i));
                    if (sel.isEmpty()) sel.add(allCams.get(0)); // la lógica no se mezcla con físicas
                    selected.clear();
                    selected.addAll(sel);
                    rig.close();
                    newSession();
                    openCamera();
                    refresh();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    @Override
    public void onPreview(int[] argb, int w, int h) {
        Bitmap b = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888);
        int[] thumb = thumb(argb, w, h);
        ui.post(() -> {
            preview.setFrame(b);
            onThumb(thumb);
        });
    }

    /** Miniatura 64x48 de luminancia para medir si la escena está quieta. */
    private static int[] thumb(int[] argb, int w, int h) {
        int tw = 64, th = 48;
        int[] t = new int[tw * th];
        for (int y = 0; y < th; y++)
            for (int x = 0; x < tw; x++) {
                int c = argb[(y * h / th) * w + x * w / tw];
                t[y * tw + x] = (((c >> 16) & 255) + ((c >> 8) & 255) * 2 + (c & 255)) >> 2;
            }
        return t;
    }

    private void onThumb(int[] t) {
        if (lastThumb != null) {
            long d = 0;
            for (int i = 0; i < t.length; i++) d += Math.abs(t[i] - lastThumb[i]);
            double mean = d / (double) t.length;
            stillCount = mean < 2.5 ? stillCount + 1 : 0;
        }
        lastThumb = t;
        maybeShoot();
    }

    /** Dispara como máximo 1 vez por segundo y, si se pide, sólo con la escena quieta. */
    private void maybeShoot() {
        if (!capturing || busy) return;
        long now = System.currentTimeMillis();
        if (now - lastShotMs < 1000) return;
        if (gate && stillCount < 2) return;
        lastShotMs = now;
        busy = true;
        synchronized (pending) { pending.clear(); }
        rig.requestFrames();
    }

    @Override
    public void onFrame(CameraRig.Frame f) {
        List<CameraRig.Frame> ready = null;
        synchronized (pending) {
            pending.add(f);
            if (pending.size() >= rig.activeCameras().size()) {
                ready = new ArrayList<>(pending);
                pending.clear();
            }
        }
        if (ready == null) return;
        final List<CameraRig.Frame> frames = ready;
        work.post(() -> {
            if (calibrating) doCalibrate(frames);
            else doProcess(frames);
        });
    }

    @Override
    public void onInfo(String msg) { log(msg); ui.post(this::refresh); }

    @Override
    public void onError(String msg) { log("ERROR: " + msg); setStatus(msg); }

    // ------------------------------------------------------------ calibración

    private void calibrate() {
        if (capturing) toggleCapture();
        calibrating = true;
        refresh();
        setStatus("Fijando exposición y enfoque… (la escena debe estar SIN objeto)");
        rig.lock3A();
        ui.postDelayed(() -> {
            synchronized (pending) { pending.clear(); }
            rig.requestFrames();
        }, 1500);
    }

    private void doCalibrate(List<CameraRig.Frame> frames) {
        try {
            frames.sort((a, b) -> a.cam - b.cam);
            String r = session.calibrate(frames, rig.activeCameras());
            log(r);
            ScanSession.Pass p = session.currentPass();
            ScanSession.Cal c0 = p.cals[0];
            float[] ring = new float[2 * 72];
            for (int i = 0; i < 72; i++) {
                double a = i * 2 * Math.PI / 72;
                double[] q = Geom.apply(c0.H, c0.discCx + RefLayout.RING_OUT * Math.cos(a),
                        c0.discCy + RefLayout.RING_OUT * Math.sin(a));
                ring[2 * i] = (float) q[0];
                ring[2 * i + 1] = (float) q[1];
            }
            ui.post(() -> {
                preview.setCalibration(c0.markerPx, mode == ScanSession.Mode.DISCO ? ring : null);
                preview.setCoverage(mode == ScanSession.Mode.DISCO ? p.coverage : null);
            });
            setStatus("Pasada " + p.index + " calibrada. Pon el objeto en el centro y pulsa Iniciar.");
        } catch (IllegalStateException e) {
            log(e.getMessage());
            setStatus("No se detectó el tapete. Revisa el encuadre y la luz.");
            rig.unlock3A();
        } finally {
            calibrating = false;
            ui.post(this::refresh);
        }
    }

    // --------------------------------------------------------------- captura

    private void toggleCapture() {
        capturing = !capturing;
        busy = false;
        stillCount = 0;
        if (capturing) setStatus(mode == ScanSession.Mode.DISCO
                ? "Capturando: gira el disco poco a poco (suelta para que dispare)…"
                : "Capturando: gira el objeto de forma continua algo más de una vuelta…");
        else {
            setStatus("Captura detenida. " + session.shots.size() + " siluetas guardadas.");
            session.saveLog();
        }
        refresh();
    }

    private void doProcess(List<CameraRig.Frame> frames) {
        try {
            frames.sort((a, b) -> a.cam - b.cam);
            ScanSession.ShotResult r = session.process(frames);
            setStatus(r.message);
            ui.post(() -> {
                if (r.mask0 != null) preview.setMask(r.mask0, r.w0, r.h0);
                preview.invalidate();
                if (mode == ScanSession.Mode.DISCO && r.covered >= 36 && capturing) {
                    toggleCapture();
                    setStatus("¡Vuelta completa! Puedes hacer otra pasada a otra altura o Generar 3D.");
                }
            });
        } catch (Exception e) {
            log("Error procesando: " + e);
        } finally {
            busy = false;
        }
    }

    // ----------------------------------------------------------- reconstrucción

    private void build() {
        bBuild.setEnabled(false);
        setStatus("Generando 3D…");
        work.post(() -> {
            try {
                long t0 = System.currentTimeMillis();
                Mesh m = session.reconstruct(this::setStatus);
                lastMesh = m;
                String msg = String.format(Locale.US, "Listo en %.1f s: %s", (System.currentTimeMillis() - t0) / 1000.0,
                        m.describe());
                log(msg + "\n" + session.dir.getAbsolutePath());
                setStatus(msg);
                ui.post(() -> {
                    refresh();
                    startActivity(new Intent(this, MeshViewActivity.class));
                });
            } catch (Throwable e) {
                log("Error al generar: " + e.getMessage());
                setStatus("Error: " + e.getMessage());
                ui.post(this::refresh);
            }
        });
    }

    // --------------------------------------------------------------- archivos

    private void printSheets() {
        new AlertDialog.Builder(this)
                .setTitle("Tamaño de papel")
                .setItems(new String[]{"A4", "Carta"}, (d, which) -> {
                    try {
                        File dir = new File(getExternalFilesDir(null), "print");
                        //noinspection ResultOfMethodCallIgnored
                        dir.mkdirs();
                        File f = new File(dir, which == 0 ? "escenario_A4.pdf" : "escenario_Carta.pdf");
                        PrintSheets.write(f, which == 0);
                        Uri u = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setDataAndType(u, "application/pdf");
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(i, "Abrir PDF para imprimir"));
                    } catch (Exception e) {
                        setStatus("No se pudo crear el PDF: " + e.getMessage());
                    }
                })
                .show();
    }

    private void share() {
        ArrayList<Uri> uris = new ArrayList<>();
        for (File f : session.outputs())
            if (f.exists()) uris.add(FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f));
        if (uris.isEmpty()) return;
        Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE);
        i.setType("*/*");
        i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "Compartir modelo 3D"));
    }

    private void probe() {
        if (capturing) toggleCapture();
        setStatus("Diagnosticando cámaras… (se cierra la vista previa unos segundos)");
        rig.close();
        work.post(() -> {
            try {
                JSONObject j = CameraProbe.run(this);
                File dir = new File(getExternalFilesDir(null), "diagnostico");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File f = new File(dir, "camaras_" + System.currentTimeMillis() + ".json");
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    fos.write(j.toString(2).getBytes(StandardCharsets.UTF_8));
                }
                log("Diagnóstico guardado: " + f.getAbsolutePath());
                setStatus("Diagnóstico listo: " + f.getName());
                ui.post(() -> {
                    openCamera();
                    Uri u = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("application/json");
                    i.putExtra(Intent.EXTRA_STREAM, u);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(i, "Compartir diagnóstico"));
                });
            } catch (Exception e) {
                log("Error en diagnóstico: " + e);
                setStatus("Error en diagnóstico: " + e.getMessage());
                ui.post(this::openCamera);
            }
        });
    }
}
