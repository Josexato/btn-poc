package com.poc.scan3d.core;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Malla triangular con color por vértice. Unidades: mm, Z hacia arriba. */
public final class Mesh {

    public float[] pos;   // 3 por vértice
    public float[] nrm;   // 3 por vértice
    public float[] col;   // 3 por vértice (0..1)
    public int[] tri;     // 3 por triángulo

    public int vertexCount() { return pos.length / 3; }

    public int triangleCount() { return tri.length / 3; }

    public void computeNormals() {
        nrm = new float[pos.length];
        for (int t = 0; t < tri.length; t += 3) {
            int a = tri[t] * 3, b = tri[t + 1] * 3, c = tri[t + 2] * 3;
            float ux = pos[b] - pos[a], uy = pos[b + 1] - pos[a + 1], uz = pos[b + 2] - pos[a + 2];
            float vx = pos[c] - pos[a], vy = pos[c + 1] - pos[a + 1], vz = pos[c + 2] - pos[a + 2];
            float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            for (int v : new int[]{a, b, c}) { nrm[v] += nx; nrm[v + 1] += ny; nrm[v + 2] += nz; }
        }
        for (int i = 0; i < nrm.length; i += 3) {
            float l = (float) Math.sqrt(nrm[i] * nrm[i] + nrm[i + 1] * nrm[i + 1] + nrm[i + 2] * nrm[i + 2]);
            if (l > 0) { nrm[i] /= l; nrm[i + 1] /= l; nrm[i + 2] /= l; }
        }
    }

    /** Suavizado de Taubin (lambda/mu): quita el escalonado de los vóxeles sin encoger la malla. */
    public void taubinSmooth(int iterations) {
        int nv = vertexCount();
        int[] deg = new int[nv];
        for (int t = 0; t < tri.length; t += 3)
            for (int e = 0; e < 3; e++) deg[tri[t + e]] += 2;
        int[] start = new int[nv + 1];
        for (int i = 0; i < nv; i++) start[i + 1] = start[i] + deg[i];
        int[] adj = new int[start[nv]];
        int[] fill = new int[nv];
        for (int t = 0; t < tri.length; t += 3)
            for (int e = 0; e < 3; e++) {
                int a = tri[t + e], b = tri[t + (e + 1) % 3], c = tri[t + (e + 2) % 3];
                adj[start[a] + fill[a]++] = b;
                adj[start[a] + fill[a]++] = c;
            }
        float[] tmp = new float[pos.length];
        for (int it = 0; it < iterations; it++) {
            laplacian(adj, start, tmp, 0.5f);
            laplacian(adj, start, tmp, -0.53f);
        }
    }

    private void laplacian(int[] adj, int[] start, float[] tmp, float f) {
        int nv = vertexCount();
        for (int v = 0; v < nv; v++) {
            int s = start[v], e = start[v + 1];
            if (e == s) {
                System.arraycopy(pos, v * 3, tmp, v * 3, 3);
                continue;
            }
            float ax = 0, ay = 0, az = 0;
            for (int k = s; k < e; k++) {
                int u = adj[k] * 3;
                ax += pos[u]; ay += pos[u + 1]; az += pos[u + 2];
            }
            int n = e - s;
            int p = v * 3;
            tmp[p] = pos[p] + f * (ax / n - pos[p]);
            tmp[p + 1] = pos[p + 1] + f * (ay / n - pos[p + 1]);
            tmp[p + 2] = pos[p + 2] + f * (az / n - pos[p + 2]);
        }
        System.arraycopy(tmp, 0, pos, 0, pos.length);
    }

    /** {xmin,xmax,ymin,ymax,zmin,zmax}. */
    public float[] bounds() {
        float[] b = {Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < pos.length; i += 3)
            for (int a = 0; a < 3; a++) {
                b[a * 2] = Math.min(b[a * 2], pos[i + a]);
                b[a * 2 + 1] = Math.max(b[a * 2 + 1], pos[i + a]);
            }
        return b;
    }

    public String describe() {
        float[] b = bounds();
        return String.format(Locale.US, "%d vértices, %d triángulos, %.1f x %.1f x %.1f mm",
                vertexCount(), triangleCount(), b[1] - b[0], b[3] - b[2], b[5] - b[4]);
    }

    /** OBJ con color por vértice ("v x y z r g b": lo leen MeshLab y Blender). */
    public void writeObj(File f) throws IOException {
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8))) {
            w.write("# scan3d - unidades mm, Z hacia arriba\n");
            for (int i = 0; i < pos.length; i += 3) {
                if (col != null)
                    w.write(String.format(Locale.US, "v %.3f %.3f %.3f %.4f %.4f %.4f\n",
                            pos[i], pos[i + 1], pos[i + 2], col[i], col[i + 1], col[i + 2]));
                else
                    w.write(String.format(Locale.US, "v %.3f %.3f %.3f\n", pos[i], pos[i + 1], pos[i + 2]));
            }
            if (nrm != null)
                for (int i = 0; i < nrm.length; i += 3)
                    w.write(String.format(Locale.US, "vn %.4f %.4f %.4f\n", nrm[i], nrm[i + 1], nrm[i + 2]));
            for (int t = 0; t < tri.length; t += 3) {
                int a = tri[t] + 1, b = tri[t + 1] + 1, c = tri[t + 2] + 1;
                if (nrm != null) w.write("f " + a + "//" + a + " " + b + "//" + b + " " + c + "//" + c + "\n");
                else w.write("f " + a + " " + b + " " + c + "\n");
            }
        }
    }

    /** PLY binario con color (uchar) por vértice. */
    public void writePly(File f) throws IOException {
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(f))) {
            String header = "ply\nformat binary_little_endian 1.0\ncomment scan3d mm Z-up\n"
                    + "element vertex " + vertexCount() + "\nproperty float x\nproperty float y\nproperty float z\n"
                    + "property uchar red\nproperty uchar green\nproperty uchar blue\n"
                    + "element face " + triangleCount() + "\nproperty list uchar int vertex_indices\nend_header\n";
            os.write(header.getBytes(StandardCharsets.US_ASCII));
            ByteBuffer vb = ByteBuffer.allocate(15).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < pos.length; i += 3) {
                vb.clear();
                vb.putFloat(pos[i]).putFloat(pos[i + 1]).putFloat(pos[i + 2]);
                for (int c = 0; c < 3; c++)
                    vb.put((byte) Math.round(255 * (col == null ? 0.7f : Math.max(0, Math.min(1, col[i + c])))));
                os.write(vb.array(), 0, 15);
            }
            ByteBuffer fb = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN);
            for (int t = 0; t < tri.length; t += 3) {
                fb.clear();
                fb.put((byte) 3).putInt(tri[t]).putInt(tri[t + 1]).putInt(tri[t + 2]);
                os.write(fb.array(), 0, 13);
            }
        }
    }

    /** STL binario (para imprimir en 3D). */
    public void writeStl(File f) throws IOException {
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(f))) {
            byte[] header = new byte[80];
            byte[] title = "scan3d mm".getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(title, 0, header, 0, title.length);
            os.write(header);
            ByteBuffer bb = ByteBuffer.allocate(50).order(ByteOrder.LITTLE_ENDIAN);
            bb.putInt(triangleCount());
            os.write(bb.array(), 0, 4);
            for (int t = 0; t < tri.length; t += 3) {
                int a = tri[t] * 3, b = tri[t + 1] * 3, c = tri[t + 2] * 3;
                float ux = pos[b] - pos[a], uy = pos[b + 1] - pos[a + 1], uz = pos[b + 2] - pos[a + 2];
                float vx = pos[c] - pos[a], vy = pos[c + 1] - pos[a + 1], vz = pos[c + 2] - pos[a + 2];
                float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
                float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (l > 0) { nx /= l; ny /= l; nz /= l; }
                bb.clear();
                bb.putFloat(nx).putFloat(ny).putFloat(nz);
                for (int v : new int[]{a, b, c}) bb.putFloat(pos[v]).putFloat(pos[v + 1]).putFloat(pos[v + 2]);
                bb.putShort((short) 0);
                os.write(bb.array(), 0, 50);
            }
        }
    }
}
