package com.poc.scan3d;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Diagnóstico: vuelca en JSON todo lo que el HAL expone de cada cámara
 * (lógicas y físicas, incluso las que no aparecen en getCameraIdList) y
 * pregunta qué combinaciones de flujos físicos simultáneos acepta.
 *
 * No supone IDs fijos: en los Galaxy los IDs físicos no son 0,1,2,3.
 * Cada cámara se identifica por focal + tamaño de sensor + resolución.
 */
final class CameraProbe {

    private CameraProbe() {}

    /** Bloqueante: llamar desde un hilo de trabajo. */
    static JSONObject run(Context ctx) throws Exception {
        CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        JSONObject root = new JSONObject();
        root.put("device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + " (" + android.os.Build.DEVICE + ")");
        root.put("android", android.os.Build.VERSION.RELEASE + " / API " + android.os.Build.VERSION.SDK_INT);
        root.put("build", android.os.Build.DISPLAY);

        String[] ids = cm.getCameraIdList();
        root.put("cameraIdList", new JSONArray(ids));
        Set<String> all = new LinkedHashSet<>();
        for (String id : ids) {
            all.add(id);
            all.addAll(cm.getCameraCharacteristics(id).getPhysicalCameraIds());
        }
        JSONArray cams = new JSONArray();
        for (String id : all) {
            JSONObject o = describe(cm, id);
            o.put("inCameraIdList", contains(ids, id));
            cams.put(o);
        }
        root.put("cameras", cams);

        // Combinaciones de flujos físicos simultáneos en cada cámara lógica.
        JSONArray combos = new JSONArray();
        boolean camPerm = ctx.checkSelfPermission(android.Manifest.permission.CAMERA)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        for (String id : camPerm ? ids : new String[0]) {
            CameraCharacteristics c = cm.getCameraCharacteristics(id);
            List<String> phys = new ArrayList<>(c.getPhysicalCameraIds());
            if (phys.size() < 2) continue;
            combos.put(testCombos(cm, id, phys));
        }
        root.put("concurrentPhysicalStreams", combos);
        try {
            JSONArray conc = new JSONArray();
            for (Set<String> s : cm.getConcurrentCameraIds()) conc.put(new JSONArray(new ArrayList<>(s)));
            root.put("concurrentCameraIds", conc);
        } catch (Exception e) {
            root.put("concurrentCameraIds", "error: " + e);
        }
        return root;
    }

    private static boolean contains(String[] a, String s) {
        for (String x : a) if (x.equals(s)) return true;
        return false;
    }

    private static JSONObject describe(CameraManager cm, String id) throws Exception {
        CameraCharacteristics c = cm.getCameraCharacteristics(id);
        JSONObject o = new JSONObject();
        o.put("id", id);
        Integer facing = c.get(CameraCharacteristics.LENS_FACING);
        o.put("facing", facing == null ? "?" : facing == CameraCharacteristics.LENS_FACING_BACK ? "back"
                : facing == CameraCharacteristics.LENS_FACING_FRONT ? "front" : "external");
        o.put("physicalIds", new JSONArray(new ArrayList<>(c.getPhysicalCameraIds())));
        o.put("focalLengthsMm", floats(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)));
        o.put("apertures", floats(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)));
        SizeF ps = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        if (ps != null) o.put("sensorSizeMm", ps.getWidth() + "x" + ps.getHeight());
        Size pa = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        if (pa != null) o.put("pixelArray", pa.toString());
        Rect act = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (act != null) o.put("activeArray", act.width() + "x" + act.height());
        float[] fl = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        if (fl != null && fl.length > 0 && ps != null) {
            // Focal equivalente 35 mm (diagonal 43.27 mm): sirve para decir 0.6x / 1x / 3x / 5x.
            double diag = Math.hypot(ps.getWidth(), ps.getHeight());
            o.put("equiv35mm", Math.round(fl[0] * 43.27 / diag));
            o.put("hfovDeg", Math.round(Math.toDegrees(2 * Math.atan(ps.getWidth() / (2 * fl[0])))));
        }
        o.put("hardwareLevel", level(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)));
        o.put("capabilities", caps(c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)));
        o.put("ois", ints(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)));
        o.put("afModes", ints(c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)));
        Float minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        if (minFocus != null) o.put("minFocusDiopters", minFocus);
        Range<Integer> iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (iso != null) o.put("isoRange", iso.toString());
        Range<Long> exp = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if (exp != null) o.put("exposureNsRange", exp.toString());
        float[] cal = c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION);
        if (cal != null) o.put("intrinsics_fx_fy_cx_cy_s", floats(cal));
        float[] dist = c.get(CameraCharacteristics.LENS_DISTORTION);
        if (dist != null) o.put("distortion", floats(dist));
        float[] pose = c.get(CameraCharacteristics.LENS_POSE_TRANSLATION);
        if (pose != null) o.put("poseTranslationM", floats(pose));
        Integer poseRef = c.get(CameraCharacteristics.LENS_POSE_REFERENCE);
        if (poseRef != null) o.put("poseReference", poseRef);
        JSONArray fps = new JSONArray();
        Range<Integer>[] fr = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (fr != null) for (Range<Integer> r : fr) fps.put(r.toString());
        o.put("fpsRanges", fps);
        JSONArray physKeys = new JSONArray();
        for (CaptureRequest.Key<?> k : c.getAvailablePhysicalCameraRequestKeys() == null
                ? new ArrayList<CaptureRequest.Key<?>>() : c.getAvailablePhysicalCameraRequestKeys())
            physKeys.put(k.getName());
        o.put("physicalRequestKeys", physKeys);
        JSONArray vendor = new JSONArray();
        for (CameraCharacteristics.Key<?> k : c.getKeys())
            if (!k.getName().startsWith("android.")) vendor.put(k.getName());
        o.put("vendorCharacteristicKeys", vendor);

        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        JSONObject fmts = new JSONObject();
        if (map != null) {
            int[][] f = {{ImageFormat.YUV_420_888}, {ImageFormat.JPEG}, {ImageFormat.RAW_SENSOR},
                    {ImageFormat.RAW10}, {ImageFormat.RAW12}, {ImageFormat.PRIVATE}, {ImageFormat.DEPTH16},
                    {ImageFormat.DEPTH_POINT_CLOUD}};
            String[] names = {"YUV_420_888", "JPEG", "RAW16", "RAW10", "RAW12", "PRIVATE", "DEPTH16", "DEPTH_POINT_CLOUD"};
            for (int i = 0; i < f.length; i++) {
                Size[] sz = null;
                try {
                    sz = map.getOutputSizes(f[i][0]);
                } catch (Exception ignored) {
                }
                if (sz == null || sz.length == 0) continue;
                Size max = sz[0];
                for (Size s : sz) if ((long) s.getWidth() * s.getHeight() > (long) max.getWidth() * max.getHeight()) max = s;
                fmts.put(names[i], "max " + max + " (" + sz.length + " tamaños)");
            }
        }
        o.put("formats", fmts);
        return o;
    }

    /** Pregunta al HAL por pares, tríos y todas las físicas a la vez (YUV 1280x960 aprox.). */
    @SuppressLint("MissingPermission") // la actividad sólo llama al diagnóstico con el permiso concedido
    private static JSONObject testCombos(CameraManager cm, String logical, List<String> phys) throws Exception {
        JSONObject out = new JSONObject();
        out.put("logicalId", logical);
        HandlerThread t = new HandlerThread("probe");
        t.start();
        Handler h = new Handler(t.getLooper());
        CountDownLatch opened = new CountDownLatch(1);
        final CameraDevice[] dev = new CameraDevice[1];
        cm.openCamera(logical, new CameraDevice.StateCallback() {
            @Override public void onOpened(CameraDevice d) { dev[0] = d; opened.countDown(); }
            @Override public void onDisconnected(CameraDevice d) { d.close(); opened.countDown(); }
            @Override public void onError(CameraDevice d, int e) { d.close(); opened.countDown(); }
        }, h);
        opened.await(5, TimeUnit.SECONDS);
        JSONArray results = new JSONArray();
        if (dev[0] == null) {
            out.put("error", "no se pudo abrir la cámara lógica");
        } else {
            List<List<String>> sets = new ArrayList<>();
            int n = phys.size();
            for (int mask = 1; mask < (1 << n); mask++) {
                if (Integer.bitCount(mask) < 2) continue;
                List<String> s = new ArrayList<>();
                for (int i = 0; i < n; i++) if ((mask & (1 << i)) != 0) s.add(phys.get(i));
                sets.add(s);
            }
            for (List<String> s : sets) {
                JSONObject r = new JSONObject();
                r.put("physical", new JSONArray(s));
                List<ImageReader> readers = new ArrayList<>();
                try {
                    List<OutputConfiguration> outs = new ArrayList<>();
                    for (String p : s) {
                        Size sz = yuvSize(cm.getCameraCharacteristics(p));
                        if (sz == null) sz = new Size(1280, 960);
                        ImageReader ir = ImageReader.newInstance(sz.getWidth(), sz.getHeight(), ImageFormat.YUV_420_888, 2);
                        readers.add(ir);
                        OutputConfiguration oc = new OutputConfiguration(ir.getSurface());
                        oc.setPhysicalCameraId(p);
                        outs.add(oc);
                    }
                    SessionConfiguration cfg = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs,
                            h::post, new android.hardware.camera2.CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(android.hardware.camera2.CameraCaptureSession x) { x.close(); }
                        @Override public void onConfigureFailed(android.hardware.camera2.CameraCaptureSession x) { }
                    });
                    r.put("supported", dev[0].isSessionConfigurationSupported(cfg));
                } catch (UnsupportedOperationException e) {
                    r.put("supported", "consulta no implementada por el fabricante");
                } catch (Exception e) {
                    r.put("supported", "error: " + e.getMessage());
                } finally {
                    for (ImageReader ir : readers) ir.close();
                }
                results.put(r);
            }
            dev[0].close();
        }
        t.quitSafely();
        out.put("combinations", results);
        return out;
    }

    private static Size yuvSize(CameraCharacteristics c) {
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return null;
        Size best = null;
        for (Size s : map.getOutputSizes(ImageFormat.YUV_420_888)) {
            if (Math.abs(s.getWidth() * 3 - s.getHeight() * 4) > 8) continue;
            if (best == null || Math.abs(s.getWidth() - 1280) < Math.abs(best.getWidth() - 1280)) best = s;
        }
        return best;
    }

    private static JSONArray floats(float[] a) throws JSONException {
        JSONArray j = new JSONArray();
        if (a != null) for (float f : a) j.put((double) f);
        return j;
    }

    private static JSONArray ints(int[] a) {
        JSONArray j = new JSONArray();
        if (a != null) for (int v : a) j.put(v);
        return j;
    }

    private static String level(Integer l) {
        if (l == null) return "?";
        switch (l) {
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY: return "LEGACY";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED: return "LIMITED";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL: return "FULL";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3: return "LEVEL_3";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL: return "EXTERNAL";
            default: return String.valueOf(l);
        }
    }

    private static JSONArray caps(int[] c) {
        JSONArray j = new JSONArray();
        if (c == null) return j;
        String[] names = new String[32];
        names[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE] = "BACKWARD_COMPATIBLE";
        names[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR] = "MANUAL_SENSOR";
        names[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING] = "MANUAL_POST_PROCESSING";
        names[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW] = "RAW";
        names[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING] = "PRIVATE_REPROCESSING";
        names[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS] = "READ_SENSOR_SETTINGS";
        names[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE] = "BURST_CAPTURE";
        names[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING] = "YUV_REPROCESSING";
        names[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT] = "DEPTH_OUTPUT";
        names[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO] = "HIGH_SPEED_VIDEO";
        names[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING] = "MOTION_TRACKING";
        names[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA] = "LOGICAL_MULTI_CAMERA";
        names[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME] = "MONOCHROME";
        names[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA] = "SECURE_IMAGE_DATA";
        names[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA] = "SYSTEM_CAMERA";
        names[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING] = "OFFLINE_PROCESSING";
        names[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR] = "ULTRA_HIGH_RESOLUTION";
        names[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING] = "REMOSAIC_REPROCESSING";
        names[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT] = "10BIT";
        names[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE] = "STREAM_USE_CASE";
        for (int v : c) j.put(v < names.length && names[v] != null ? names[v] : String.valueOf(v));
        return j;
    }
}
