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

    // Umbral de aceleración lineal (m/s^2) para considerar un "golpecito".
    private static final float KNOCK_THRESHOLD = 2.2f;

    // Tiempo mínimo entre golpes detectados para evitar rebotes (ms).
    private static final long KNOCK_COOLDOWN_MS = 350L;

    // Factor del filtro pasa-bajos que estima la gravedad.
    private static final float ALPHA = 0.8f;

    private boolean isRed = true;
    private Button colorButton;
    private Button recordButton;

    private SensorManager sensorManager;
    private Sensor accelerometer;

    // Componente de gravedad estimada, para restarla y quedarnos con la
    // aceleración lineal (el movimiento del golpe).
    private final float[] gravity = new float[3];
    private boolean gravityInitialized = false;

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
        // Reiniciamos el filtro para que al volver no arrastre estado viejo.
        gravityInitialized = false;
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
            logRow(String.format(Locale.US, "TOUCH,%s,%.1f,%.1f,,",
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

        final double rawMag = Math.sqrt(x * x + y * y + z * z);

        if (!gravityInitialized) {
            gravity[0] = x;
            gravity[1] = y;
            gravity[2] = z;
            gravityInitialized = true;
            if (recording) {
                logRow(String.format(Locale.US, "ACC,%.4f,%.4f,%.4f,%.4f,%.4f",
                        x, y, z, rawMag, 0.0));
            }
            return;
        }

        // Filtro pasa-bajos: aisla la gravedad (componente lenta).
        gravity[0] = ALPHA * gravity[0] + (1 - ALPHA) * x;
        gravity[1] = ALPHA * gravity[1] + (1 - ALPHA) * y;
        gravity[2] = ALPHA * gravity[2] + (1 - ALPHA) * z;

        // Aceleración lineal = lectura - gravedad estimada.
        final float lx = x - gravity[0];
        final float ly = y - gravity[1];
        final float lz = z - gravity[2];

        final double linearMag = Math.sqrt(lx * lx + ly * ly + lz * lz);

        // Loguea cada muestra del acelerómetro (lo que "siente").
        if (recording) {
            logRow(String.format(Locale.US, "ACC,%.4f,%.4f,%.4f,%.4f,%.4f",
                    x, y, z, rawMag, linearMag));
        }

        if (linearMag > KNOCK_THRESHOLD) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastKnockTime > KNOCK_COOLDOWN_MS) {
                lastKnockTime = now;
                if (recording) {
                    logRow(String.format(Locale.US, "KNOCK,,,,%.4f,", linearMag));
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
            logRow("COLOR," + (isRed ? "RED" : "BLUE") + ",,,,");
        }
    }

    // --- Grabación ---

    private void startRecording() {
        logBuffer = new StringBuilder();
        // Formato: t_ms,type,v1,v2,v3,v4,v5  (v* segun el tipo de fila)
        logBuffer.append("# ACC   v1=x v2=y v3=z v4=rawMag v5=linearMag\n");
        logBuffer.append("# TOUCH v1=action v2=x_px v3=y_px\n");
        logBuffer.append("# KNOCK v4=linearMag  (umbral actual=").append(KNOCK_THRESHOLD).append(")\n");
        logBuffer.append("# COLOR v1=RED|BLUE\n");
        logBuffer.append("t_ms,type,v1,v2,v3,v4,v5\n");
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
