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

    // Umbral del "jerk" de la magnitud (m/s^2 entre muestras) para un golpecito.
    // La magnitud total |a| es invariante a la rotación, así que girar el
    // teléfono no la altera; un golpe la hace saltar de golpe. Calibrado con
    // datos reales: los golpes suaves dan saltos de ~1.7-3.4; manipular <~1.0.
    private static final float KNOCK_JERK_THRESHOLD = 1.2f;

    // Tiempo mínimo entre golpes detectados para evitar rebotes (ms).
    private static final long KNOCK_COOLDOWN_MS = 300L;

    // Filtro pasa-bajos de la gravedad vectorial (solo para loguear el feature
    // antiguo 'linearMag' y poder compararlo; ya no se usa para detectar).
    private static final float ALPHA = 0.8f;

    // Filtro pasa-bajos lento para la línea base de la magnitud (~9.81).
    private static final float MAG_BASELINE_ALPHA = 0.9f;

    private boolean isRed = true;
    private Button colorButton;
    private Button recordButton;

    private SensorManager sensorManager;
    private Sensor accelerometer;

    // Estado de los filtros del acelerómetro.
    private final float[] gravity = new float[3];
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
                    SensorManager.SENSOR_DELAY_GAME);
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

        if (!filtersInitialized) {
            gravity[0] = x;
            gravity[1] = y;
            gravity[2] = z;
            magBaseline = rawMag;
            prevRawMag = rawMag;
            filtersInitialized = true;
            if (recording) {
                logRow(String.format(Locale.US, "ACC,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f",
                        x, y, z, rawMag, 0.0, 0.0, 0.0));
            }
            return;
        }

        // Feature ANTIGUO (solo para el log, comparación): aceleración lineal
        // vectorial = lectura - gravedad estimada. Es el que fallaba al girar.
        gravity[0] = ALPHA * gravity[0] + (1 - ALPHA) * x;
        gravity[1] = ALPHA * gravity[1] + (1 - ALPHA) * y;
        gravity[2] = ALPHA * gravity[2] + (1 - ALPHA) * z;
        final float lx = x - gravity[0];
        final float ly = y - gravity[1];
        final float lz = z - gravity[2];
        final float linearMag = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);

        // Feature NUEVO: la magnitud total es invariante a la rotación.
        //  - dev  = desviación respecto a la línea base (~9.81)
        //  - jerk = salto brusco de la magnitud entre muestras (lo que dispara)
        magBaseline = MAG_BASELINE_ALPHA * magBaseline + (1 - MAG_BASELINE_ALPHA) * rawMag;
        final float dev = rawMag - magBaseline;
        final float jerk = rawMag - prevRawMag;
        prevRawMag = rawMag;

        // Loguea cada muestra del acelerómetro (lo que "siente").
        if (recording) {
            logRow(String.format(Locale.US, "ACC,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f",
                    x, y, z, rawMag, linearMag, dev, jerk));
        }

        if (Math.abs(jerk) > KNOCK_JERK_THRESHOLD) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastKnockTime > KNOCK_COOLDOWN_MS) {
                lastKnockTime = now;
                if (recording) {
                    logRow(String.format(Locale.US, "KNOCK,,,,,%.4f,%.4f", dev, jerk));
                }
                toggleColor();
            }
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
        logBuffer.append("# ACC   v1=x v2=y v3=z v4=rawMag v5=linearMag(viejo) v6=dev v7=jerk\n");
        logBuffer.append("# TOUCH v1=action v2=x_px v3=y_px\n");
        logBuffer.append("# KNOCK v6=dev v7=jerk  (detector por jerk, umbral=").append(KNOCK_JERK_THRESHOLD).append(")\n");
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
