package com.poc.btn;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class MainActivity extends Activity implements SensorEventListener {

    // Un golpe se reconoce por TRES condiciones a la vez (calibrado con datos
    // reales: celular en la pierna/pecho y golpes en pantalla/espalda, frente a
    // giros y manipulación que antes daban falsos positivos):
    //
    //  1) jerk alto: salto brusco de la magnitud entre muestras.
    //  2) impulso concentrado: jerk/p2p alto. Un golpe mete todo el salto en un
    //     instante (jerk ≈ p2p, ratio ~1); girar reparte un swing grande en
    //     muchas muestras (p2p enorme, jerk pequeño, ratio bajo).
    //  3) en reposo justo antes: el nivel de movimiento reciente es bajo. Un
    //     golpe ocurre sobre un teléfono quieto; girar es movimiento sostenido.
    private static final float KNOCK_JERK_THRESHOLD = 0.7f;
    private static final float KNOCK_RATIO_THRESHOLD = 0.6f;   // jerk / p2p
    private static final float KNOCK_MOTION_THRESHOLD = 0.22f;  // reposo previo

    // Ventana (ms) sobre la que se mide el pico-a-pico de la magnitud.
    private static final long P2P_WINDOW_MS = 110L;

    // Factor del EWMA que estima el nivel de movimiento reciente (memoria ~300ms).
    private static final float MOTION_DECAY = 0.985f;

    // Tope de la aportación de cada muestra al EWMA de movimiento. Un golpe
    // fuerte (jerk enorme) es un impulso instantáneo, NO movimiento de fondo;
    // sin este tope, una ráfaga de golpes en una mesa dura infla el medidor y
    // bloquea los golpes siguientes. Girar (jerk moderado y sostenido) sí acumula.
    private static final float MOTION_CAP = 0.5f;

    // Tiempo mínimo entre golpes detectados para evitar rebotes (ms).
    private static final long KNOCK_COOLDOWN_MS = 300L;

    private boolean isRed = true;
    private Button colorButton;
    private Button recordButton;

    private SensorManager sensorManager;
    private Sensor accelerometer;

    // Buffer circular de magnitudes recientes para el pico-a-pico en ventana.
    private static final int MAG_BUF = 512;
    private final float[] magBuf = new float[MAG_BUF];
    private final long[] magBufTime = new long[MAG_BUF];
    private int magBufHead = 0;
    private int magBufSize = 0;

    // Estado de los filtros del acelerómetro.
    private float prevRawMag = 0f;
    private float motionEwma = 0f;
    private boolean filtersInitialized = false;

    private long lastKnockTime = 0L;
    private int knockCount = 0;

    // Orientación actual según el eje Z del acelerómetro.
    private TextView statusView;
    private String orientation = "?";

    // Umbral (m/s^2) para decidir boca arriba / boca abajo por el eje Z.
    private static final float FACE_Z_THRESHOLD = 7.0f;

    // --- Grabación para calibración ---
    private boolean recording = false;
    private long recordStartRealtime = 0L;
    private StringBuilder logBuffer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Evita que la pantalla se apague por timeout mientras la app está
        // en primer plano: al apagarse, la Activity se pausa y se dejaba de
        // recibir el acelerómetro (era la causa del "deja de grabar boca abajo").
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);

        statusView = new TextView(this);
        statusView.setText("Orientación: ?   Golpes: 0");
        statusView.setPadding(0, 0, 0, 32);
        statusView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        colorButton = new Button(this);
        colorButton.setText("Tócame o dame un golpecito");
        colorButton.setBackgroundColor(Color.RED);
        colorButton.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        colorButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleColor();
            }
        });

        recordButton = new Button(this);
        recordButton.setText("● Grabar acelerómetro");
        LinearLayout.LayoutParams recParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        recParams.topMargin = 48;
        recordButton.setLayoutParams(recParams);
        recordButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (recording) {
                    stopRecordingAndShare();
                } else {
                    startRecording();
                }
            }
        });

        root.addView(statusView);
        root.addView(colorButton);
        root.addView(recordButton);
        setContentView(root);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sensorManager != null && accelerometer != null) {
            sensorManager.registerListener(this, accelerometer,
                    SensorManager.SENSOR_DELAY_FASTEST);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        // Reiniciamos los filtros para que al volver no arrastren estado viejo.
        filtersInitialized = false;
        magBufSize = 0;
        magBufHead = 0;
        motionEwma = 0f;
    }

    // Registra CADA toque en la pantalla (lo que "detecta la pantalla").
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (recording) {
            String action;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: action = "DOWN"; break;
                case MotionEvent.ACTION_UP:   action = "UP";   break;
                case MotionEvent.ACTION_MOVE: action = "MOVE"; break;
                default: action = "OTHER"; break;
            }
            logRow(String.format(Locale.US, "TOUCH,%s,%.1f,%.1f,,,,,",
                    action, ev.getX(), ev.getY()));
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) {
            return;
        }

        final float x = event.values[0];
        final float y = event.values[1];
        final float z = event.values[2];

        final float rawMag = (float) Math.sqrt(x * x + y * y + z * z);
        final long now = SystemClock.elapsedRealtime();

        // Orientación por el eje Z: boca arriba (UP), boca abajo (DOWN) o de
        // canto/inclinado (EDGE). Solo refresca la pantalla si cambia.
        final String orient = (z > FACE_Z_THRESHOLD) ? "UP"
                : (z < -FACE_Z_THRESHOLD ? "DOWN" : "EDGE");
        if (!orient.equals(orientation)) {
            orientation = orient;
            updateStatus();
        }

        if (!filtersInitialized) {
            prevRawMag = rawMag;
            motionEwma = 0f;
            filtersInitialized = true;
        }

        // Guarda la magnitud en el buffer circular (para el pico-a-pico).
        magBuf[magBufHead] = rawMag;
        magBufTime[magBufHead] = now;
        magBufHead = (magBufHead + 1) % MAG_BUF;
        if (magBufSize < MAG_BUF) magBufSize++;

        // Pico-a-pico de la magnitud en la ventana P2P_WINDOW_MS.
        float mn = rawMag, mx = rawMag;
        for (int i = 0; i < magBufSize; i++) {
            int idx = (magBufHead - 1 - i + MAG_BUF) % MAG_BUF;
            if (now - magBufTime[idx] > P2P_WINDOW_MS) break;
            if (magBuf[idx] < mn) mn = magBuf[idx];
            if (magBuf[idx] > mx) mx = magBuf[idx];
        }
        final float p2p = mx - mn;

        final float jerk = Math.abs(rawMag - prevRawMag);
        prevRawMag = rawMag;
        final float ratio = (p2p > 0f) ? jerk / p2p : 0f;

        // Nivel de movimiento reciente ANTES de incorporar esta muestra
        // (para saber si el teléfono estaba en reposo justo antes del impulso).
        final float motionBefore = motionEwma;
        final float motionSample = Math.min(jerk, MOTION_CAP);
        motionEwma = MOTION_DECAY * motionEwma + (1 - MOTION_DECAY) * motionSample;

        // Loguea cada muestra del acelerómetro (lo que "siente").
        if (recording) {
            logRow(String.format(Locale.US, "ACC,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%s",
                    x, y, z, rawMag, jerk, p2p, motionBefore, orient));
        }

        if (jerk > KNOCK_JERK_THRESHOLD
                && ratio > KNOCK_RATIO_THRESHOLD
                && motionBefore < KNOCK_MOTION_THRESHOLD
                && now - lastKnockTime > KNOCK_COOLDOWN_MS) {
            lastKnockTime = now;
            knockCount++;
            if (recording) {
                logRow(String.format(Locale.US, "KNOCK,,,,,%.4f,%.4f,%.4f,%s",
                        jerk, p2p, motionBefore, orient));
            }
            toggleColor();
            updateStatus();
        }
    }

    private void updateStatus() {
        statusView.setText("Orientación: " + orientation + "   Golpes: " + knockCount);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // No se necesita.
    }

    private void toggleColor() {
        isRed = !isRed;
        colorButton.setBackgroundColor(isRed ? Color.RED : Color.BLUE);
        if (recording) {
            logRow("COLOR," + (isRed ? "RED" : "BLUE") + ",,,,,,,");
        }
    }

    // --- Grabación ---

    private void startRecording() {
        logBuffer = new StringBuilder();
        // Formato: t_ms,type,v1..v7  (v* segun el tipo de fila)
        logBuffer.append("# ACC   v1=x v2=y v3=z v4=rawMag v5=jerk v6=p2p v7=motion v8=orient\n");
        logBuffer.append("# TOUCH v1=action v2=x_px v3=y_px\n");
        logBuffer.append("# KNOCK v5=jerk v6=p2p v7=motion v8=orient  (jerk>").append(KNOCK_JERK_THRESHOLD)
                .append(" & jerk/p2p>").append(KNOCK_RATIO_THRESHOLD)
                .append(" & motion<").append(KNOCK_MOTION_THRESHOLD).append(")\n");
        logBuffer.append("# COLOR v1=RED|BLUE   | orient: UP=boca arriba DOWN=boca abajo EDGE=canto\n");
        logBuffer.append("t_ms,type,v1,v2,v3,v4,v5,v6,v7,v8\n");
        recordStartRealtime = SystemClock.elapsedRealtime();
        recording = true;
        recordButton.setText("■ Detener y compartir");
        Toast.makeText(this, "Grabando… mueve y golpea el teléfono", Toast.LENGTH_SHORT).show();
    }

    private void stopRecordingAndShare() {
        recording = false;
        recordButton.setText("● Grabar acelerómetro");

        StringBuilder buffer = logBuffer;
        logBuffer = null;
        if (buffer == null || buffer.length() == 0) {
            Toast.makeText(this, "No se grabó nada", Toast.LENGTH_SHORT).show();
            return;
        }

        File logFile = writeLogFile(buffer.toString());
        if (logFile == null) {
            Toast.makeText(this, "Error al guardar el log", Toast.LENGTH_LONG).show();
            return;
        }

        shareLogFile(logFile);
    }

    private File writeLogFile(String content) {
        try {
            File logsDir = new File(getCacheDir(), "logs");
            if (!logsDir.exists() && !logsDir.mkdirs()) {
                return null;
            }
            File logFile = new File(logsDir,
                    "accel-log-" + System.currentTimeMillis() + ".csv");
            FileOutputStream fos = new FileOutputStream(logFile);
            OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
            try {
                writer.write(content);
                writer.flush();
            } finally {
                writer.close();
            }
            return logFile;
        } catch (IOException e) {
            return null;
        }
    }

    private void shareLogFile(File logFile) {
        Uri uri = FileProvider.getUriForFile(
                this, getPackageName() + ".fileprovider", logFile);

        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/csv");
        share.putExtra(Intent.EXTRA_STREAM, uri);
        share.putExtra(Intent.EXTRA_SUBJECT, "Log acelerómetro btn-poc");
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        startActivity(Intent.createChooser(share, "Compartir log"));
    }

    // Añade una fila al buffer con la marca de tiempo relativa al inicio.
    private void logRow(String row) {
        if (logBuffer == null) {
            return;
        }
        long t = SystemClock.elapsedRealtime() - recordStartRealtime;
        logBuffer.append(t).append(',').append(row).append('\n');
    }
}
