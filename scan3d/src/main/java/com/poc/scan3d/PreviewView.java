package com.poc.scan3d;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * Vista previa dibujada a mano (bitmap de la cámara 0) con superposiciones en
 * coordenadas de la imagen de análisis: marcadores, anillo del disco, silueta
 * y cobertura de la vuelta.
 */
final class PreviewView extends View {

    private Bitmap frame;
    private Bitmap maskOverlay;
    private int imgW = 1280, imgH = 960; // tamaño de análisis
    private int rotation = 0;
    private double[][] markers;
    private float[] ring;                // polilínea x,y,x,y... del anillo
    private boolean[] coverage;
    private String banner = "";
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix m = new Matrix();

    PreviewView(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
    }

    void setRotation(int deg) { rotation = deg; }

    void setImageSize(int w, int h) { imgW = w; imgH = h; }

    void setFrame(Bitmap b) {
        Bitmap old = frame;
        frame = b;
        if (old != null && old != b) old.recycle();
        invalidate();
    }

    void setCalibration(double[][] markers, float[] ring) {
        this.markers = markers;
        this.ring = ring;
        invalidate();
    }

    void setMask(byte[] mask, int w, int h) {
        if (mask == null) { maskOverlay = null; invalidate(); return; }
        int s = 4, ow = w / s, oh = h / s;
        int[] px = new int[ow * oh];
        for (int y = 0; y < oh; y++)
            for (int x = 0; x < ow; x++)
                px[y * ow + x] = mask[(y * s) * w + x * s] != 0 ? 0x6600FF66 : 0;
        maskOverlay = Bitmap.createBitmap(px, ow, oh, Bitmap.Config.ARGB_8888);
        invalidate();
    }

    void setCoverage(boolean[] c) { coverage = c; invalidate(); }

    void setBanner(String s) { banner = s; invalidate(); }

    @Override
    protected void onDraw(Canvas c) {
        int vw = getWidth(), vh = getHeight();
        boolean swap = rotation % 180 != 0;
        float rw = swap ? imgH : imgW, rh = swap ? imgW : imgH;
        float sc = Math.min(vw / rw, vh / rh);
        m.reset();
        m.postTranslate(-imgW / 2f, -imgH / 2f);
        m.postRotate(rotation);
        m.postScale(sc, sc);
        m.postTranslate(vw / 2f, vh / 2f);
        c.save();
        c.concat(m);
        if (frame != null)
            c.drawBitmap(frame, null, new RectF(0, 0, imgW, imgH), null);
        if (maskOverlay != null)
            c.drawBitmap(maskOverlay, null, new RectF(0, 0, imgW, imgH), null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3 / sc);
        if (ring != null && ring.length >= 4) {
            paint.setColor(Color.CYAN);
            Path p = new Path();
            p.moveTo(ring[0], ring[1]);
            for (int i = 2; i < ring.length; i += 2) p.lineTo(ring[i], ring[i + 1]);
            p.close();
            c.drawPath(p, paint);
        }
        if (markers != null) {
            for (int i = 0; i < markers.length; i++) {
                paint.setColor(i == 0 ? Color.RED : Color.YELLOW);
                c.drawCircle((float) markers[i][0], (float) markers[i][1], 14 / sc, paint);
            }
        }
        c.restore();

        if (coverage != null) {
            float r = Math.min(vw, vh) * 0.08f, cx = vw - r - 16, cy = r + 16;
            RectF oval = new RectF(cx - r, cy - r, cx + r, cy + r);
            paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < coverage.length; i++) {
                paint.setColor(coverage[i] ? 0xFF33DD55 : 0x66FFFFFF);
                c.drawArc(oval, -90 + i * 10 + 1, 8, true, paint);
            }
        }
        if (!banner.isEmpty()) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xAA000000);
            c.drawRect(0, vh - 64, vw, vh, paint);
            paint.setColor(Color.WHITE);
            paint.setTextSize(34);
            c.drawText(banner, 16, vh - 20, paint);
        }
    }
}
