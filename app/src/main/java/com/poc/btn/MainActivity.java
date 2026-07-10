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
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class MainActivity extends Activity implements SensorEventListener {

    // Umbral del pico-a-pico de la magnitud dentro de una ventana corta.
    // La magnitud total |a| es invariante a la rotación (girar no la altera);
    // un golpe produce un bajón + rebote que se ve como una oscilación
    // pico-a-pico. Calibrado con el celular apoyado en el pecho (golpes muy
    // suaves): piso de ruido ~0.39, golpes desde ~0.5. 0.8 detecta ~50% de los
    // golpes suaves sin falsos positivos; bajarlo sube aciertos y falsos.
    private static final float KNOCK_P2P_THRESHOLD = 0.8f;

    // Ventana (ms) sobre la que se mide el pico-a-pico de la magnitud.
    private static final long P2P_WINDOW_MS = 110L;

    // Tiempo mínimo entre golpes detectados para evitar rebotes (ms).
    private static final long KNOCK_COOLDOWN_MS = 300L;

    // Filtro pasa-bajos lento para la línea base de la magnitud (~9.81), solo
    // para loguear 'dev' y seguir calibrando.
    private static final float MAG_BASELINE_ALPHA = 0.9f;

    private boolean isRed = true;
    private Button colorButton;
    private Button recordButton;

    private SensorManager sensorManager;
    private Sensor accelerometer;

    // Buffer circular de magnitudes recientes para el pico-a-pico en ventana.
    private static final int MAG_BUF = 256;
    private final float[] magBuf = new float[MAG_BUF];
    private final long[] magBufTime = new long[MAG_BUF];
    private int magBufHead = 0;
    private int magBufSize = 0;

    // Estado de los filtros del acelerómetro.
    private float magBaseline = 0f;
    private float prevRawMag = 0f;
    private boolean filtersInitialized = false;

    private long lastKnockTime = 0L;

    // --- Grabación para calibración ---
    private boolean recording = false;
    private long recordStartRealtime = 0L;
    private StringBuilder logBuffer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);

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
            logRow(String.format(Locale.US, "TOUCH,%s,%.1f,%.1f,,,,",
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

        if (!filtersInitialized) {
            magBaseline = rawMag;
            prevRawMag = rawMag;
            filtersInitialized = true;
        }

        // Guarda la magnitud en el buffer circular (para el pico-a-pico).
        magBuf[magBufHead] = rawMag;
        magBufTime[magBufHead] = now;
        magBufHead = (magBufHead + 1) % MAG_BUF;
        if (magBufSize < MAG_BUF) magBufSize++;

        // Pico-a-pico de la magnitud en la ventana P2P_WINDOW_MS (feature de
        // detección: la magnitud es invariante a la rotación y un golpe produce
        // un bajón + rebote que agranda el pico-a-pico).
        float mn = rawMag, mx = rawMag;
        for (int i = 0; i < magBufSize; i++) {
            int idx = (magBufHead - 1 - i + MAG_BUF) % MAG_BUF;
            if (now - magBufTime[idx] > P2P_WINDOW_MS) break;
            if (magBuf[idx] < mn) mn = magBuf[idx];
            if (magBuf[idx] > mx) mx = magBuf[idx];
        }
        final float p2p = mx - mn;

        // Features auxiliares solo para el log (seguir calibrando).
        magBaseline = MAG_BASELINE_ALPHA * magBaseline + (1 - MAG_BASELINE_ALPHA) * rawMag;
        final float dev = rawMag - magBaseline;
        final float jerk = rawMag - prevRawMag;
        prevRawMag = rawMag;

        // Loguea cada muestra del acelerómetro (lo que "siente").
        if (recording) {
            logRow(String.format(Locale.US, "ACC,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f",
                    x, y, z, rawMag, dev, jerk, p2p));
        }

        if (p2p > KNOCK_P2P_THRESHOLD && now - lastKnockTime > KNOCK_COOLDOWN_MS) {
            lastKnockTime = now;
            if (recording) {
                logRow(String.format(Locale.US, "KNOCK,,,,,%.4f,%.4f,%.4f", dev, jerk, p2p));
            }
            toggleColor();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // No se necesita.
    }

    private void toggleColor() {
        isRed = !isRed;
        colorButton.setBackgroundColor(isRed ? Color.RED : Color.BLUE);
        if (recording) {
            logRow("COLOR," + (isRed ? "RED" : "BLUE") + ",,,,,,");
        }
    }

    // --- Grabación ---

    private void startRecording() {
        logBuffer = new StringBuilder();
        // Formato: t_ms,type,v1..v7  (v* segun el tipo de fila)
        logBuffer.append("# ACC   v1=x v2=y v3=z v4=rawMag v5=dev v6=jerk v7=p2p\n");
        logBuffer.append("# TOUCH v1=action v2=x_px v3=y_px\n");
        logBuffer.append("# KNOCK v5=dev v6=jerk v7=p2p  (detector por p2p, umbral=").append(KNOCK_P2P_THRESHOLD).append(")\n");
        logBuffer.append("# COLOR v1=RED|BLUE\n");
        logBuffer.append("t_ms,type,v1,v2,v3,v4,v5,v6,v7\n");
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
