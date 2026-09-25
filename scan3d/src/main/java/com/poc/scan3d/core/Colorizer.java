package com.poc.scan3d.core;

import java.util.List;

/**
 * Color por vértice: cada vértice toma el color de las fotos donde su normal
 * mira más de frente a la cámara (mezcla ponderada por cos^4 del ángulo).
 * No se calcula oclusión: en zonas cóncavas puede "sangrar" color.
 */
public final class Colorizer {

    private Colorizer() {}

    public interface ImageSource {
        /** ARGB de la vista i (mismo tamaño que su máscara) o null si no hay. */
        int[] load(int viewIndex);
    }

    public static void colorize(Mesh m, List<Carver.View> views, double ccx, double ccy, ImageSource src) {
        if (m.nrm == null) m.computeNormals();
        int nv = m.vertexCount();
        float[] acc = new float[nv * 3];
        float[] wsum = new float[nv];
        for (int vi = 0; vi < views.size(); vi++) {
            Carver.View v = views.get(vi);
            int[] img = src.load(vi);
            if (img == null) continue;
            int w = v.cam.w, h = v.cam.h;
            // Centro de la cámara en el marco del objeto: Rz(-theta) (C - c).
            double[] cw = v.cam.center();
            double[] rz = Geom.rotZ(-v.theta);
            double[] co = Geom.mulVec(rz, new double[]{cw[0] - ccx, cw[1] - ccy, cw[2]});
            double[] P = v.P;
            for (int i = 0; i < nv; i++) {
                double px = m.pos[i * 3], py = m.pos[i * 3 + 1], pz = m.pos[i * 3 + 2];
                double dx = co[0] - px, dy = co[1] - py, dz = co[2] - pz;
                double dl = Math.sqrt(dx * dx + dy * dy + dz * dz);
                double cos = (m.nrm[i * 3] * dx + m.nrm[i * 3 + 1] * dy + m.nrm[i * 3 + 2] * dz) / dl;
                if (cos < 0.15) continue;
                double ww = P[8] * px + P[9] * py + P[10] * pz + P[11];
                if (ww <= 0) continue;
                double u = (P[0] * px + P[1] * py + P[2] * pz + P[3]) / ww;
                double vv = (P[4] * px + P[5] * py + P[6] * pz + P[7]) / ww;
                int c = Segmenter.sampleArgb(img, w, h, u, vv);
                if (c == 0) continue;
                float wt = (float) (cos * cos * cos * cos);
                acc[i * 3] += wt * ((c >> 16) & 255);
                acc[i * 3 + 1] += wt * ((c >> 8) & 255);
                acc[i * 3 + 2] += wt * (c & 255);
                wsum[i] += wt;
            }
        }
        m.col = new float[nv * 3];
        for (int i = 0; i < nv; i++) {
            if (wsum[i] <= 0) {
                m.col[i * 3] = m.col[i * 3 + 1] = m.col[i * 3 + 2] = 0.6f;
                continue;
            }
            for (int c = 0; c < 3; c++) m.col[i * 3 + c] = acc[i * 3 + c] / wsum[i] / 255f;
        }
    }
}
