package com.poc.btn;

import android.app.Activity;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;

public class MainActivity extends Activity implements SensorEventListener {

    // Umbral de aceleración lineal (m/s^2) para considerar un "golpecito".
    // Bajo, porque queremos detectar golpes suaves. Ajustable.
    private static final float KNOCK_THRESHOLD = 2.2f;

    // Tiempo mínimo entre golpes detectados para evitar rebotes (ms).
    private static final long KNOCK_COOLDOWN_MS = 350L;

    // Factor del filtro pasa-bajos que estima la gravedad.
    private static final float ALPHA = 0.8f;

    private boolean isRed = true;
    private Button button;

    private SensorManager sensorManager;
    private Sensor accelerometer;

    // Componente de gravedad estimada, para restarla y quedarnos con la
    // aceleración lineal (el movimiento del golpe).
    private final float[] gravity = new float[3];
    private boolean gravityInitialized = false;

    private long lastKnockTime = 0L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);

        button = new Button(this);
        button.setText("Tócame o dame un golpecito");
        button.setBackgroundColor(Color.RED);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.CENTER;
        button.setLayoutParams(params);

        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleColor();
            }
        });

        root.addView(button);
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

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) {
            return;
        }

        final float x = event.values[0];
        final float y = event.values[1];
        final float z = event.values[2];

        if (!gravityInitialized) {
            gravity[0] = x;
            gravity[1] = y;
            gravity[2] = z;
            gravityInitialized = true;
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

        final double magnitude = Math.sqrt(lx * lx + ly * ly + lz * lz);

        if (magnitude > KNOCK_THRESHOLD) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastKnockTime > KNOCK_COOLDOWN_MS) {
                lastKnockTime = now;
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
        button.setBackgroundColor(isRed ? Color.RED : Color.BLUE);
    }
}
