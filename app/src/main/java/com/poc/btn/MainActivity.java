package com.poc.btn;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
    // La fase brusca de un golpe (jerk>umbral) dura <50ms, así que 150ms evita
    // el doble conteo y permite golpes seguidos (~6/seg). Antes 300ms perdía
    // los golpes rápidos.
    private static final long KNOCK_COOLDOWN_MS = 150L;

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

    // --- Decodificador de símbolos (código de 5 bits: 1=golpe, 0=silencio) ---
    // Cada símbolo lleva su propio beat: se infiere el beat base (hueco más
    // corto de la ráfaga) y cada hueco se cuenta como 1 ó 2 beats. El 1er golpe
    // es el beat 1. Sin metrónomo. AUX1 (10101) no se usa, por eso no hay
    // ambigüedad "3 rápidos vs 3 lentos".
    // Un símbolo se cierra tras SYMBOL_END_MS sin golpes (mayor que un hueco de
    // 2 beats, para no partir el símbolo).
    private static final long SYMBOL_END_MS = 1400L;
    // Un hueco cuenta como 2 beats si es >= LONG_BEAT_RATIO * beat base.
    private static final double LONG_BEAT_RATIO = 1.5;

    // El mensaje son 3 símbolos: pieza -> columna -> fila.
    private static final int MOVE_LEN = 3;

    private TextView pieceView;
    private final long[] burstTimes = new long[16];
    private int burstLen = 0;
    private Handler patternHandler;
    private Runnable finalizeBurst;
    private String lastPiece = "—";
    // Símbolos acumulados del movimiento en curso.
    private final String[] moveParts = new String[MOVE_LEN];
    private int movePos = 0;

    // Voz: dice el nombre de la pieza (útil con el teléfono boca abajo).
    private TextToSpeech tts;
    private boolean ttsReady = false;

    // --- Grabación para calibración ---
    private boolean recording = false;
    private long recordStartRealtime = 0L;
    private StringBuilder logBuffer;

    // --- Micrófono (solo calibración): nivel de sonido junto al acelerómetro ---
    private static final int REQ_AUDIO = 1;
    private boolean audioAsked = false;
    private AudioRecord audioRecord;
    private Thread audioThread;
    private volatile boolean audioRunning = false;
    // Último pico de audio normalizado (0..1) del frame más reciente.
    private volatile float audioPeak = 0f;

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
        statusView.setPadding(0, 0, 0, 16);
        statusView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        pieceView = new TextView(this);
        pieceView.setPadding(0, 0, 0, 32);
        pieceView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        patternHandler = new Handler(Looper.getMainLooper());
        finalizeBurst = new Runnable() {
            @Override
            public void run() {
                classifyBurst();
            }
        };
        updatePieceView();

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
                    tryStartRecording();
                }
            }
        });

        // Chuleta de códigos en la pantalla inicial.
        TextView codesView = new TextView(this);
        codesView.setTypeface(Typeface.MONOSPACE);
        codesView.setTextSize(13f);
        LinearLayout.LayoutParams codesParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        codesParams.topMargin = 48;
        codesView.setLayoutParams(codesParams);
        codesView.setText(buildCodesText());

        root.addView(statusView);
        root.addView(pieceView);
        root.addView(colorButton);
        root.addView(recordButton);
        root.addView(codesView);

        // Envolvemos en un ScrollView para que quepa la chuleta.
        ScrollView scroll = new ScrollView(this);
        scroll.setPadding(24, 24, 24, 24);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }

        // Voz en español para anunciar la pieza detectada.
        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS && tts != null) {
                    tts.setLanguage(new Locale("es", "ES"));
                    ttsReady = true;
                }
            }
        });
    }

    private void speak(String text) {
        if (tts != null && ttsReady) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "piece");
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
        // Cancelamos cualquier símbolo/movimiento en curso.
        if (patternHandler != null) {
            patternHandler.removeCallbacks(finalizeBurst);
        }
        burstLen = 0;
        movePos = 0;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopAudio();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
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
            logRow(String.format(Locale.US, "TOUCH,%s,%.1f,%.1f,,,,,,",
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
            logRow(String.format(Locale.US, "ACC,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%s,%.4f",
                    x, y, z, rawMag, jerk, p2p, motionBefore, orient, audioPeak));
        }

        if (jerk > KNOCK_JERK_THRESHOLD
                && ratio > KNOCK_RATIO_THRESHOLD
                && motionBefore < KNOCK_MOTION_THRESHOLD
                && now - lastKnockTime > KNOCK_COOLDOWN_MS) {
            lastKnockTime = now;
            knockCount++;
            if (recording) {
                logRow(String.format(Locale.US, "KNOCK,,,,,%.4f,%.4f,%.4f,%s,%.4f",
                        jerk, p2p, motionBefore, orient, audioPeak));
            }
            toggleColor();
            registerKnockInBurst(now);
            updateStatus();
        }
    }

    // Añade el golpe al símbolo en curso y reprograma el cierre del símbolo.
    private void registerKnockInBurst(long now) {
        if (burstLen < burstTimes.length) {
            burstTimes[burstLen++] = now;
        }
        patternHandler.removeCallbacks(finalizeBurst);
        patternHandler.postDelayed(finalizeBurst, SYMBOL_END_MS);
    }

    // Se llama cuando el símbolo termina (sin golpes durante SYMBOL_END_MS).
    private void classifyBurst() {
        int n = burstLen;
        burstLen = 0;
        // Un golpe suelto (accidental) se ignora en silencio.
        if (n < 2) {
            updateStatus();
            return;
        }
        String code = decodeCode(burstTimes, n);
        int number = codeToNumber(code);
        handleSymbol(number, code, n);
    }

    // Reconstruye el código de 5 bits a partir de los tiempos de golpe.
    // Devuelve null si no es un símbolo válido (1 golpe, o cae fuera de 5 beats).
    private String decodeCode(long[] t, int n) {
        if (n < 2 || n > 5) return null;
        long base = Long.MAX_VALUE;
        for (int i = 1; i < n; i++) {
            long g = t[i] - t[i - 1];
            if (g < base) base = g;
        }
        if (base <= 0) return null;
        int[] beat = new int[n];
        beat[0] = 1;
        for (int i = 1; i < n; i++) {
            long g = t[i] - t[i - 1];
            int b = (g >= base * LONG_BEAT_RATIO) ? 2 : 1;  // hueco de 1 ó 2 beats
            beat[i] = beat[i - 1] + b;
        }
        if (beat[n - 1] > 5) return null;
        char[] c = {'0', '0', '0', '0', '0'};
        for (int i = 0; i < n; i++) c[beat[i] - 1] = '1';
        return new String(c);
    }

    // Código de 5 bits -> número (0..10), o -1 si no está en la tabla.
    private int codeToNumber(String code) {
        if (code == null) return -1;
        switch (code) {
            case "00000": return 0;
            case "10110": return 1;
            case "10111": return 2;   // 1,2,3 = 22,23,24 en decimal (consecutivos)
            case "11000": return 3;
            case "11010": return 4;
            case "11011": return 5;
            case "11100": return 6;
            case "11101": return 7;
            case "11110": return 8;
            case "10101": return 9;   // AUX1 (no se usa)
            case "11111": return 10;  // AUX2
            default: return -1;
        }
    }

    // Interpreta el número según su posición: 0=pieza, 1=columna, 2=fila.
    private String interpretSymbol(int number, int pos) {
        if (number < 0) return null;
        if (pos == 0) {
            switch (number) {
                case 1: return "Rey";
                case 2: return "Peón";
                case 3: return "Torre";
                case 4: return "Dama";
                case 6: return "Alfil";
                case 8: return "Caballo";
                default: return null;
            }
        } else if (pos == 1) {
            if (number >= 1 && number <= 8) return String.valueOf((char) ('A' + number - 1));
            return null;
        } else {
            if (number >= 1 && number <= 8) return String.valueOf(number);
            return null;
        }
    }

    private void handleSymbol(int number, String code, int n) {
        String codeStr = (code == null ? "-----" : code);
        String name = interpretSymbol(number, movePos);
        if (name == null) {
            lastPiece = "no reconocido (" + n + " golpes, " + codeStr + ")";
            speak("no reconocido");
            if (recording) logRow("SYM,INVALID," + number + "," + codeStr + ",,,,,");
            updatePieceView();
            updateStatus();
            return;
        }
        moveParts[movePos] = name;
        movePos++;
        speak(name);
        if (recording) {
            logRow("SYM," + name + "," + number + "," + codeStr + ",pos" + (movePos - 1) + ",,,,");
        }
        if (movePos >= MOVE_LEN) {
            String move = moveParts[0] + " " + moveParts[1] + " " + moveParts[2];
            lastPiece = move;
            speak(move);
            if (recording) {
                logRow("MOVE," + moveParts[0] + "," + moveParts[1] + "," + moveParts[2] + ",,,,,");
            }
            movePos = 0;
        }
        updatePieceView();
        updateStatus();
    }

    private void updateStatus() {
        statusView.setText("Orientación: " + orientation + "   Golpes: " + burstLen);
    }

    // Texto de referencia con la definición de cada código.
    private String buildCodesText() {
        return "CÓDIGOS  (● golpe  · silencio)\n"
                + "El 1er golpe marca el inicio; un\n"
                + "hueco doble = un beat en silencio.\n"
                + "\n"
                + "Nº   patrón (5 beats)\n"
                + "1    ● · ● ● ·\n"
                + "2    ● · ● ● ●\n"
                + "3    ● ● · · ·\n"
                + "4    ● ● · ● ·\n"
                + "5    ● ● · ● ●\n"
                + "6    ● ● ● · ·\n"
                + "7    ● ● ● · ●\n"
                + "8    ● ● ● ● ·\n"
                + "\n"
                + "MENSAJE:  Pieza · Columna · Fila\n"
                + "(pausa entre cada símbolo)\n"
                + "\n"
                + "Pieza:   Rey=1  Peón=2  Torre=3\n"
                + "         Dama=4  Alfil=6  Caballo=8\n"
                + "Columna: A=1 B=2 C=3 D=4\n"
                + "         E=5 F=6 G=7 H=8\n"
                + "Fila:    1 … 8\n"
                + "\n"
                + "Ej.: Caballo E 3  →  8 · 5 · 3";
    }

    private void updatePieceView() {
        StringBuilder sb = new StringBuilder("Jugada: ");
        for (int i = 0; i < MOVE_LEN; i++) {
            sb.append(i < movePos && moveParts[i] != null ? moveParts[i] : "·");
            if (i < MOVE_LEN - 1) sb.append(" · ");
        }
        sb.append("\nÚltimo: ").append(lastPiece);
        pieceView.setText(sb.toString());
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // No se necesita.
    }

    private void toggleColor() {
        isRed = !isRed;
        colorButton.setBackgroundColor(isRed ? Color.RED : Color.BLUE);
        if (recording) {
            logRow("COLOR," + (isRed ? "RED" : "BLUE") + ",,,,,,,,");
        }
    }

    // --- Grabación ---

    // Pide el micrófono la primera vez; después graba con audio (si se concedió)
    // o sin audio (si se denegó). El acelerómetro se graba siempre.
    private void tryStartRecording() {
        if (hasAudioPermission()) {
            startRecording(true);
        } else if (!audioAsked) {
            audioAsked = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        } else {
            startRecording(false);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO) {
            boolean granted = hasAudioPermission();
            if (!granted) {
                Toast.makeText(this, "Sin micrófono: se grabará solo el acelerómetro",
                        Toast.LENGTH_SHORT).show();
            }
            startRecording(granted);
        }
    }

    private boolean hasAudioPermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startRecording(boolean withAudio) {
        if (withAudio) {
            startAudio();
        }
        logBuffer = new StringBuilder();
        // Formato: t_ms,type,v1..v9  (v* segun el tipo de fila)
        logBuffer.append("# ACC   v1=x v2=y v3=z v4=rawMag v5=jerk v6=p2p v7=motion v8=orient v9=audio\n");
        logBuffer.append("# TOUCH v1=action v2=x_px v3=y_px\n");
        logBuffer.append("# KNOCK v5=jerk v6=p2p v7=motion v8=orient v9=audio  (jerk>").append(KNOCK_JERK_THRESHOLD)
                .append(" & jerk/p2p>").append(KNOCK_RATIO_THRESHOLD)
                .append(" & motion<").append(KNOCK_MOTION_THRESHOLD).append(")\n");
        logBuffer.append("# COLOR v1=RED|BLUE | orient:UP/DOWN/EDGE | audio=pico sonido 0..1")
                .append(withAudio ? "" : " (micrófono NO disponible)").append("\n");
        logBuffer.append("# SYM   v1=símbolo v2=número v3=código5bits v4=posición\n");
        logBuffer.append("# MOVE  v1=pieza v2=columna v3=fila\n");
        logBuffer.append("t_ms,type,v1,v2,v3,v4,v5,v6,v7,v8,v9\n");
        recordStartRealtime = SystemClock.elapsedRealtime();
        recording = true;
        recordButton.setText("■ Detener y compartir");
        Toast.makeText(this, withAudio ? "Grabando con micrófono…" : "Grabando (sin micrófono)…",
                Toast.LENGTH_SHORT).show();
    }

    private void stopRecordingAndShare() {
        recording = false;
        stopAudio();
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

    // --- Micrófono: captura en un hilo aparte el pico de audio por frame ---

    private void startAudio() {
        final int rate = 44100;
        int minBuf = AudioRecord.getMinBufferSize(rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) return;
        final int bufSize = Math.max(minBuf, 4096);
        try {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                audioRecord.release();
                audioRecord = null;
                return;
            }
            audioRecord.startRecording();
        } catch (Exception e) {
            audioRecord = null;
            return;
        }
        audioRunning = true;
        audioThread = new Thread(new Runnable() {
            @Override
            public void run() {
                short[] frame = new short[512];  // ~12ms a 44.1kHz
                while (audioRunning) {
                    int n = audioRecord.read(frame, 0, frame.length);
                    if (n > 0) {
                        int peak = 0;
                        for (int i = 0; i < n; i++) {
                            int a = Math.abs(frame[i]);
                            if (a > peak) peak = a;
                        }
                        audioPeak = peak / 32768f;
                    }
                }
            }
        });
        audioThread.start();
    }

    private void stopAudio() {
        audioRunning = false;
        if (audioThread != null) {
            try {
                audioThread.join(300);
            } catch (InterruptedException ignored) {
            }
            audioThread = null;
        }
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (IllegalStateException ignored) {
            }
            audioRecord.release();
            audioRecord = null;
        }
        audioPeak = 0f;
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
