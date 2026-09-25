package com.poc.scan3d.core;

/**
 * Geometría del escenario de referencia impreso (todo en mm).
 *
 * Coordenadas "mundo": origen en el centro del contenido impreso (que es
 * también el centro del disco giratorio), X hacia la derecha del papel,
 * Y hacia ARRIBA del papel y Z saliendo del papel (sistema dextrógiro).
 *
 * Hoja 1 (tapete fijo): 4 marcadores circulares negros en las esquinas; el
 * M0 (arriba-izquierda) tiene un agujero blanco para fijar la orientación.
 * Hoja 2 (disco giratorio): un anillo con un código binario pseudoaleatorio
 * (secuencia-m de 63 bits). Leyendo cualquier arco visible del anillo se
 * obtiene el ángulo ABSOLUTO del disco aunque el objeto tape una parte.
 *
 * El contenido (210 x 279 mm) cabe tanto en A4 como en Carta.
 */
public final class RefLayout {

    private RefLayout() {}

    public static final double CONTENT_W = 210.0;
    public static final double CONTENT_H = 279.0;

    public static final double MARKER_DX = 87.0;
    public static final double MARKER_DY = 121.5;
    public static final double MARKER_R = 10.0;
    public static final double MARKER_HOLE_R = 4.0;

    /** Centros de los marcadores M0..M3 (mundo). Recorridos en sentido horario visto desde arriba. */
    public static final double[][] MARKERS = {
            {-MARKER_DX, MARKER_DY},   // M0 arriba-izquierda (con agujero)
            {MARKER_DX, MARKER_DY},    // M1 arriba-derecha
            {MARKER_DX, -MARKER_DY},   // M2 abajo-derecha
            {-MARKER_DX, -MARKER_DY},  // M3 abajo-izquierda
    };

    public static final double DISC_R = 88.0;
    public static final double RING_IN = 68.0;
    public static final double RING_OUT = 86.0;

    /** Barra de escala de 100 mm para verificar que se imprimió a tamaño real. */
    public static final double SCALE_BAR_Y = -105.0;

    public static final int CODE_BITS = 63;
    public static final boolean[] CODE = mSequence6();

    /**
     * Secuencia-m de 63 bits (LFSR de 6 bits, polinomio x^6 + x^5 + 1).
     * Su autocorrelación circular es 63 en el pico y -1 en cualquier otro
     * desplazamiento: ideal para medir el giro por correlación.
     */
    static boolean[] mSequence6() {
        boolean[] out = new boolean[CODE_BITS];
        int s = 1;
        for (int i = 0; i < CODE_BITS; i++) {
            out[i] = (s & 1) == 1;
            int fb = ((s >> 0) ^ (s >> 1)) & 1;
            s = (s >> 1) | (fb << 5);
        }
        return out;
    }

    /** Bit del código (true = negro) en el ángulo dado (rad, CCW desde +X del disco). */
    public static boolean codeAt(double angle) {
        double a = angle % (2 * Math.PI);
        if (a < 0) a += 2 * Math.PI;
        int idx = (int) (a / (2 * Math.PI) * CODE_BITS);
        if (idx >= CODE_BITS) idx = CODE_BITS - 1;
        return CODE[idx];
    }

    /**
     * Intensidad (0 negro .. 1 blanco) del tapete fijo en (x, y) mundo.
     * Solo se usa para pruebas sintéticas; el PDF se dibuja con Canvas.
     */
    public static double sampleMat(double x, double y) {
        if (Math.abs(x) > CONTENT_W / 2 || Math.abs(y) > CONTENT_H / 2) return 0.45; // mesa
        for (int i = 0; i < 4; i++) {
            double dx = x - MARKERS[i][0], dy = y - MARKERS[i][1];
            double r = Math.sqrt(dx * dx + dy * dy);
            if (r <= MARKER_R) {
                if (i == 0 && r <= MARKER_HOLE_R) return 1.0;
                return 0.05;
            }
        }
        return 1.0;
    }

    /** Intensidad del disco en coordenadas del propio disco (centro = 0,0). */
    public static double sampleDisc(double x, double y) {
        double r = Math.sqrt(x * x + y * y);
        if (r >= RING_IN && r <= RING_OUT) {
            return codeAt(Math.atan2(y, x)) ? 0.05 : 1.0;
        }
        return 1.0;
    }
}
