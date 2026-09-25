package com.poc.scan3d;

import android.media.Image;

import java.nio.ByteBuffer;

/** Conversión YUV_420_888 -> ARGB respetando rowStride/pixelStride de cada plano. */
final class YuvUtil {

    private YuvUtil() {}

    /**
     * @param step 1 = resolución completa, 2 = mitad (vista previa rápida)
     * @return ARGB de tamaño (w/step) x (h/step)
     */
    static int[] toArgb(Image img, int step) {
        int w = img.getWidth(), h = img.getHeight();
        Image.Plane[] p = img.getPlanes();
        byte[] y = copy(p[0].getBuffer());
        byte[] u = copy(p[1].getBuffer());
        byte[] v = copy(p[2].getBuffer());
        int yRow = p[0].getRowStride(), yPix = p[0].getPixelStride();
        int uRow = p[1].getRowStride(), uPix = p[1].getPixelStride();
        int vRow = p[2].getRowStride(), vPix = p[2].getPixelStride();
        int ow = w / step, oh = h / step;
        int[] out = new int[ow * oh];
        for (int oy = 0; oy < oh; oy++) {
            int sy = oy * step;
            int yBase = sy * yRow, uBase = (sy >> 1) * uRow, vBase = (sy >> 1) * vRow;
            for (int ox = 0; ox < ow; ox++) {
                int sx = ox * step;
                int yy = (y[yBase + sx * yPix] & 255) - 16;
                int uu = (u[uBase + (sx >> 1) * uPix] & 255) - 128;
                int vv = (v[vBase + (sx >> 1) * vPix] & 255) - 128;
                if (yy < 0) yy = 0;
                // BT.601 en enteros (x1024).
                int y1 = 1192 * yy;
                int r = (y1 + 1634 * vv) >> 10;
                int g = (y1 - 833 * vv - 400 * uu) >> 10;
                int b = (y1 + 2066 * uu) >> 10;
                r = r < 0 ? 0 : Math.min(r, 255);
                g = g < 0 ? 0 : Math.min(g, 255);
                b = b < 0 ? 0 : Math.min(b, 255);
                out[oy * ow + ox] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return out;
    }

    private static byte[] copy(ByteBuffer buf) {
        buf.rewind();
        byte[] a = new byte[buf.remaining()];
        buf.get(a);
        return a;
    }
}
