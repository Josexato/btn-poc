package com.poc.scan3d;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;

import com.poc.scan3d.core.RefLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Genera el PDF del escenario de referencia (2 hojas) a tamaño real.
 * Hoja 1: tapete fijo con 4 marcadores. Hoja 2: disco giratorio codificado.
 * Toda la geometría sale de {@link RefLayout}, la misma que usa el análisis.
 */
final class PrintSheets {

    private PrintSheets() {}

    private static final float PT_PER_MM = 72f / 25.4f;

    static void write(File out, boolean a4) throws IOException {
        int pw = a4 ? 595 : 612, ph = a4 ? 842 : 792;
        PdfDocument doc = new PdfDocument();
        try {
            PdfDocument.Page p1 = doc.startPage(new PdfDocument.PageInfo.Builder(pw, ph, 1).create());
            drawMat(p1.getCanvas(), pw, ph, a4);
            doc.finishPage(p1);
            PdfDocument.Page p2 = doc.startPage(new PdfDocument.PageInfo.Builder(pw, ph, 2).create());
            drawDisc(p2.getCanvas(), pw, ph);
            doc.finishPage(p2);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                doc.writeTo(fos);
            }
        } finally {
            doc.close();
        }
    }

    /** Coordenadas mundo (mm, Y hacia arriba) -> puntos PDF (Y hacia abajo), centradas en la hoja. */
    private static float px(float pw, double x) { return (float) (pw / 2f + x * PT_PER_MM); }

    private static float py(float ph, double y) { return (float) (ph / 2f - y * PT_PER_MM); }

    private static float mm(double v) { return (float) (v * PT_PER_MM); }

    private static void drawMat(Canvas c, int pw, int ph, boolean a4) {
        c.drawColor(Color.WHITE);
        Paint black = new Paint(Paint.ANTI_ALIAS_FLAG);
        black.setColor(Color.BLACK);
        Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
        white.setColor(Color.WHITE);
        Paint thin = new Paint(Paint.ANTI_ALIAS_FLAG);
        thin.setColor(Color.rgb(170, 170, 170));
        thin.setStyle(Paint.Style.STROKE);
        thin.setStrokeWidth(mm(0.3));
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.rgb(120, 120, 120));
        text.setTextSize(mm(3));
        text.setTextAlign(Paint.Align.CENTER);

        for (int i = 0; i < 4; i++) {
            double[] m = RefLayout.MARKERS[i];
            c.drawCircle(px(pw, m[0]), py(ph, m[1]), mm(RefLayout.MARKER_R), black);
            if (i == 0) c.drawCircle(px(pw, m[0]), py(ph, m[1]), mm(RefLayout.MARKER_HOLE_R), white);
        }
        // Guía para colocar el disco y cruz central (modo LIBRE: el objeto va aquí).
        c.drawCircle(px(pw, 0), py(ph, 0), mm(RefLayout.DISC_R), thin);
        c.drawLine(px(pw, -6), py(ph, 0), px(pw, 6), py(ph, 0), thin);
        c.drawLine(px(pw, 0), py(ph, -6), px(pw, 0), py(ph, 6), thin);

        // Barra de escala de 100 mm: mídela con una regla para comprobar la impresión.
        double by = RefLayout.SCALE_BAR_Y;
        Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        bar.setColor(Color.rgb(90, 90, 90));
        c.drawRect(px(pw, -50), py(ph, by + 0.5), px(pw, 50), py(ph, by - 0.5), bar);
        c.drawRect(px(pw, -50), py(ph, by + 2), px(pw, -49.5), py(ph, by - 2), bar);
        c.drawRect(px(pw, 49.5), py(ph, by + 2), px(pw, 50), py(ph, by - 2), bar);
        c.drawText("100 mm · imprimir al 100 % (tamaño real)",
                px(pw, 0), py(ph, by - 6), text);
        c.drawText("Scan3D · tapete fijo (" + (a4 ? "A4" : "Carta") + ")", px(pw, 0), py(ph, 100), text);
    }

    private static void drawDisc(Canvas c, int pw, int ph) {
        c.drawColor(Color.WHITE);
        float cx = px(pw, 0), cy = py(ph, 0);
        Paint black = new Paint(Paint.ANTI_ALIAS_FLAG);
        black.setColor(Color.BLACK);
        black.setStyle(Paint.Style.FILL);
        Paint cut = new Paint(Paint.ANTI_ALIAS_FLAG);
        cut.setColor(Color.rgb(150, 150, 150));
        cut.setStyle(Paint.Style.STROKE);
        cut.setStrokeWidth(mm(0.3));
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.rgb(120, 120, 120));
        text.setTextSize(mm(3));
        text.setTextAlign(Paint.Align.CENTER);

        c.drawCircle(cx, cy, mm(RefLayout.DISC_R), cut);
        RectF outer = new RectF(cx - mm(RefLayout.RING_OUT), cy - mm(RefLayout.RING_OUT),
                cx + mm(RefLayout.RING_OUT), cy + mm(RefLayout.RING_OUT));
        RectF inner = new RectF(cx - mm(RefLayout.RING_IN), cy - mm(RefLayout.RING_IN),
                cx + mm(RefLayout.RING_IN), cy + mm(RefLayout.RING_IN));
        // Cada racha de bits "1" se dibuja como un único sector (sin juntas).
        int n = RefLayout.CODE_BITS;
        boolean[] code = RefLayout.CODE;
        int start = 0;
        while (start < n && code[start]) start++; // empezar tras un 0 para no partir rachas
        for (int k = 0; k < n; ) {
            int i = (start + k) % n;
            if (!code[i]) { k++; continue; }
            int len = 0;
            while (k + len < n && code[(start + k + len) % n]) len++;
            double a0 = 360.0 * i / n, a1 = 360.0 * (i + len) / n;
            Path p = new Path();
            // El ángulo mundo (CCW, Y arriba) es -ángulo en el lienzo (Y abajo).
            p.arcTo(outer, (float) -a1, (float) (a1 - a0), true);
            p.arcTo(inner, (float) -a0, (float) -(a1 - a0), false);
            p.close();
            c.drawPath(p, black);
            k += len;
        }
        // Cruz central pequeña (ayuda a centrar el objeto y a clavar una chincheta).
        c.drawLine(cx - mm(4), cy, cx + mm(4), cy, cut);
        c.drawLine(cx, cy - mm(4), cx, cy + mm(4), cut);
        c.drawText("Scan3D · Disco giratorio · recortar por el círculo gris y pegar sobre cartón",
                cx, py(ph, -RefLayout.DISC_R - 10), text);
        c.drawText("Colocar sobre el círculo del tapete; el objeto va en el centro",
                cx, py(ph, -RefLayout.DISC_R - 15), text);
    }
}
