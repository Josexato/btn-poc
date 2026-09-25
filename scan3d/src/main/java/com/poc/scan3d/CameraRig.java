package com.poc.scan3d;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Size;
import android.util.SizeF;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Cámaras traseras con Camera2. En equipos con cámara lógica multi-lente (los
 * Galaxy Ultra la tienen: gran angular + ultra gran angular + teleobjetivos)
 * se pueden abrir varias cámaras FÍSICAS a la vez dentro de la misma sesión;
 * cada una da su propio flujo YUV y se calibra por separado con el tapete.
 *
 * Si el equipo no acepta la combinación, se cae a la cámara lógica sola.
 */
final class CameraRig {

    interface Listener {
        /** Foto completa pedida con {@link #requestFrames()}. Hilo de la cámara. */
        void onFrame(Frame f);

        /** Vista previa a media resolución de la primera cámara. Hilo de la cámara. */
        void onPreview(int[] argb, int w, int h);

        void onInfo(String msg);

        void onError(String msg);
    }

    static final class Frame {
        final int cam;
        final int w, h;
        final int[] argb;
        final long timestampMs;

        Frame(int cam, int w, int h, int[] argb) {
            this.cam = cam; this.w = w; this.h = h; this.argb = argb;
            this.timestampMs = SystemClock.elapsedRealtime();
        }
    }

    static final class CamInfo {
        String logicalId;
        String physicalId;   // null = cámara lógica
        String label;
        Size size;           // tamaño del flujo de análisis
        double fMetaPx;      // focal en píxeles del flujo según metadatos (NaN si no hay)
        int sensorOrientation;

        @Override
        public String toString() { return label; }
    }

    private static final int TARGET_W = 1280, TARGET_H = 960;

    private final Context ctx;
    private final Listener listener;
    private final CameraManager manager;
    private HandlerThread thread;
    private Handler handler;
    private CameraDevice device;
    private CameraCaptureSession session;
    private final List<ImageReader> readers = new ArrayList<>();
    private List<CamInfo> active = new ArrayList<>();
    private volatile boolean[] wantFrame = new boolean[0];
    private long lastPreviewMs = 0;
    private boolean locked = false;
    private boolean hqDistortion = false;

    CameraRig(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
        this.manager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
    }

    /** Cámara lógica trasera + cada cámara física que expone (si las hay). */
    List<CamInfo> enumerate() throws CameraAccessException {
        String best = null;
        int bestPhys = -1;
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
            int phys = c.getPhysicalCameraIds().size();
            if (phys > bestPhys) { bestPhys = phys; best = id; }
        }
        List<CamInfo> out = new ArrayList<>();
        if (best == null) return out;
        CameraCharacteristics lc = manager.getCameraCharacteristics(best);
        out.add(info(best, null, lc, "Lógica " + best + " (automática)"));
        Set<String> phys = lc.getPhysicalCameraIds();
        for (String pid : phys) {
            CameraCharacteristics pc = manager.getCameraCharacteristics(pid);
            float[] fl = pc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            String f = fl != null && fl.length > 0 ? String.format(Locale.US, "%.1f mm", fl[0]) : "?";
            CamInfo ci = info(best, pid, pc, "Física " + pid + " (" + f + ")");
            if (ci.size != null) out.add(ci);
        }
        return out;
    }

    private CamInfo info(String logical, String physical, CameraCharacteristics c, String label) {
        CamInfo ci = new CamInfo();
        ci.logicalId = logical;
        ci.physicalId = physical;
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        ci.size = map == null ? null : pickSize(map.getOutputSizes(ImageFormat.YUV_420_888));
        Integer so = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
        ci.sensorOrientation = so == null ? 90 : so;
        ci.fMetaPx = Double.NaN;
        float[] fl = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        SizeF phys = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        Size pixArr = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        Rect act = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (ci.size != null && fl != null && fl.length > 0 && phys != null && pixArr != null && act != null) {
            double fActive = fl[0] / phys.getWidth() * pixArr.getWidth();
            double aw = act.width(), ah = act.height();
            double ow = ci.size.getWidth(), oh = ci.size.getHeight();
            // El flujo es la zona activa recortada al aspecto de salida y escalada.
            double scale = (ow / oh >= aw / ah) ? ow / aw : oh / ah;
            ci.fMetaPx = fActive * scale;
        }
        ci.label = label + (ci.size != null ? " " + ci.size.getWidth() + "x" + ci.size.getHeight() : "");
        return ci;
    }

    /** 4:3 más cercano a 1280x960 (suficiente detalle y rápido de procesar). */
    private static Size pickSize(Size[] sizes) {
        if (sizes == null) return null;
        Size best = null;
        double bestScore = Double.MAX_VALUE;
        for (Size s : sizes) {
            double aspect = s.getWidth() / (double) s.getHeight();
            double score = Math.abs(aspect - 4.0 / 3) * 10000
                    + Math.abs(s.getWidth() * s.getHeight() - TARGET_W * TARGET_H) / 1000.0;
            if (score < bestScore) { bestScore = score; best = s; }
        }
        return best;
    }

    List<CamInfo> activeCameras() { return active; }

    /** Abre las cámaras elegidas (todas de la misma lógica). */
    void open(List<CamInfo> selection) throws CameraAccessException, SecurityException {
        close();
        thread = new HandlerThread("camara");
        thread.start();
        handler = new Handler(thread.getLooper());
        active = new ArrayList<>(selection);
        wantFrame = new boolean[active.size()];
        final String logicalId = active.get(0).logicalId;
        int[] dm = manager.getCameraCharacteristics(logicalId)
                .get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES);
        hqDistortion = false;
        if (dm != null) for (int m : dm) if (m == CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY) hqDistortion = true;
        manager.openCamera(logicalId, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice cameraDevice) {
                device = cameraDevice;
                try {
                    startSession(true);
                } catch (Exception e) {
                    listener.onError("No se pudo iniciar la sesión: " + e);
                }
            }

            @Override
            public void onDisconnected(CameraDevice cameraDevice) {
                cameraDevice.close();
                device = null;
            }

            @Override
            public void onError(CameraDevice cameraDevice, int error) {
                cameraDevice.close();
                device = null;
                listener.onError("Error de cámara " + error);
            }
        }, handler);
    }

    private void startSession(boolean allowFallback) throws CameraAccessException {
        for (ImageReader r : readers) r.close();
        readers.clear();
        List<OutputConfiguration> outs = new ArrayList<>();
        for (int i = 0; i < active.size(); i++) {
            CamInfo ci = active.get(i);
            ImageReader r = ImageReader.newInstance(ci.size.getWidth(), ci.size.getHeight(), ImageFormat.YUV_420_888, 3);
            final int camIdx = i;
            r.setOnImageAvailableListener(reader -> onImage(reader, camIdx), handler);
            readers.add(r);
            OutputConfiguration oc = new OutputConfiguration(r.getSurface());
            if (ci.physicalId != null) oc.setPhysicalCameraId(ci.physicalId);
            outs.add(oc);
        }
        Executor ex = handler::post;
        SessionConfiguration cfg = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, ex,
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession s) {
                        session = s;
                        try {
                            repeat();
                            listener.onInfo("Cámaras activas: " + active);
                        } catch (Exception e) {
                            listener.onError("Error al iniciar la vista previa: " + e);
                        }
                    }

                    @Override
                    public void onConfigureFailed(CameraCaptureSession s) {
                        fallback("la sesión fue rechazada");
                    }
                });
        boolean supported = true;
        try {
            supported = device.isSessionConfigurationSupported(cfg);
        } catch (UnsupportedOperationException | IllegalArgumentException ignored) {
            // El fabricante no implementa la consulta: se intenta igual.
        }
        boolean logicalOnly = active.size() == 1 && active.get(0).physicalId == null;
        if (!supported && allowFallback && !logicalOnly) {
            fallback("el equipo no admite esta combinación de cámaras");
            return;
        }
        device.createCaptureSession(cfg);
    }

    private void fallback(String why) {
        if (active.size() == 1 && active.get(0).physicalId == null) {
            listener.onError("No se pudo configurar la cámara (" + why + ").");
            return;
        }
        listener.onInfo("Multicámara no disponible (" + why + "). Uso sólo la cámara lógica.");
        try {
            List<CamInfo> all = enumerate();
            active = new ArrayList<>();
            active.add(all.get(0));
            wantFrame = new boolean[1];
            startSession(false);
        } catch (Exception e) {
            listener.onError("Fallo al volver a la cámara lógica: " + e);
        }
    }

    private void repeat() throws CameraAccessException {
        if (session == null) return;
        CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        for (ImageReader r : readers) b.addTarget(r.getSurface());
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        // Corrección de distorsión: el modelo pinhole asume imagen rectificada.
        if (hqDistortion)
            b.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY);
        if (locked) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            b.set(CaptureRequest.CONTROL_AE_LOCK, true);
            b.set(CaptureRequest.CONTROL_AWB_LOCK, true);
        } else {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        }
        session.setRepeatingRequest(b.build(), null, handler);
    }

    /**
     * Fija enfoque, exposición y balance de blancos. Es clave: la silueta se
     * saca comparando con la foto de fondo, que debe tener la misma exposición.
     */
    void lock3A() {
        handler.post(() -> {
            try {
                if (session == null) return;
                CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                for (ImageReader r : readers) b.addTarget(r.getSurface());
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                session.capture(b.build(), null, handler);
                locked = true;
                repeat();
            } catch (Exception e) {
                listener.onError("No se pudo fijar la exposición: " + e);
            }
        });
    }

    void unlock3A() {
        handler.post(() -> {
            try {
                locked = false;
                repeat();
            } catch (Exception e) {
                listener.onError("No se pudo liberar la exposición: " + e);
            }
        });
    }

    /** Pide la próxima imagen completa de cada cámara activa. */
    void requestFrames() {
        boolean[] w = new boolean[active.size()];
        java.util.Arrays.fill(w, true);
        wantFrame = w;
    }

    private void onImage(ImageReader reader, int cam) {
        Image img = reader.acquireLatestImage();
        if (img == null) return;
        try {
            boolean[] want = wantFrame;
            if (cam < want.length && want[cam]) {
                want[cam] = false;
                listener.onFrame(new Frame(cam, img.getWidth(), img.getHeight(), YuvUtil.toArgb(img, 1)));
            } else if (cam == 0) {
                long now = SystemClock.elapsedRealtime();
                if (now - lastPreviewMs >= 120) {
                    lastPreviewMs = now;
                    listener.onPreview(YuvUtil.toArgb(img, 2), img.getWidth() / 2, img.getHeight() / 2);
                }
            }
        } finally {
            img.close();
        }
    }

    void close() {
        try {
            if (session != null) session.close();
        } catch (Exception ignored) {
        }
        session = null;
        if (device != null) device.close();
        device = null;
        for (ImageReader r : readers) r.close();
        readers.clear();
        if (thread != null) thread.quitSafely();
        thread = null;
        locked = false;
    }
}
