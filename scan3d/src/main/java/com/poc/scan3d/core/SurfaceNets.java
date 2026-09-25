package com.poc.scan3d.core;

/**
 * Extracción de superficie "Naive Surface Nets": un vértice por celda que
 * cruza la isosuperficie (media de los cruces en sus aristas) y un quad por
 * cada arista de la rejilla que cambia de signo. Más simple que Marching
 * Cubes (sin tablas) y da mallas cerradas y suaves tras un leve desenfoque.
 */
public final class SurfaceNets {

    private SurfaceNets() {}

    private static final int[][] CORNERS = {
            {0, 0, 0}, {1, 0, 0}, {0, 1, 0}, {1, 1, 0}, {0, 0, 1}, {1, 0, 1}, {0, 1, 1}, {1, 1, 1}};
    private static final int[][] EDGES = {
            {0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};

    public static Mesh extract(Carver.Grid g) {
        // Rejilla con 1 vóxel de margen vacío para que la superficie quede cerrada.
        int nx = g.nx + 2, ny = g.ny + 2, nz = g.nz + 2;
        float[] f = new float[nx * ny * nz];
        for (int k = 0; k < g.nz; k++)
            for (int j = 0; j < g.ny; j++)
                for (int i = 0; i < g.nx; i++)
                    if (g.occ[g.idx(i, j, k)] != 0) f[((k + 1) * ny + (j + 1)) * nx + (i + 1)] = 1f;
        blur(f, nx, ny, nz);
        final float iso = 0.5f;

        int[] cellVert = new int[(nx - 1) * (ny - 1) * (nz - 1)];
        java.util.Arrays.fill(cellVert, -1);
        FloatList pos = new FloatList();
        int nverts = 0;
        float[] cv = new float[8];
        for (int k = 0; k < nz - 1; k++)
            for (int j = 0; j < ny - 1; j++)
                for (int i = 0; i < nx - 1; i++) {
                    int mask = 0;
                    for (int c = 0; c < 8; c++) {
                        cv[c] = f[((k + CORNERS[c][2]) * ny + (j + CORNERS[c][1])) * nx + (i + CORNERS[c][0])];
                        if (cv[c] > iso) mask |= 1 << c;
                    }
                    if (mask == 0 || mask == 255) continue;
                    float sx = 0, sy = 0, sz = 0;
                    int cnt = 0;
                    for (int[] e : EDGES) {
                        float a = cv[e[0]], b = cv[e[1]];
                        if ((a > iso) == (b > iso)) continue;
                        float t = (iso - a) / (b - a);
                        int[] p0 = CORNERS[e[0]], p1 = CORNERS[e[1]];
                        sx += p0[0] + t * (p1[0] - p0[0]);
                        sy += p0[1] + t * (p1[1] - p0[1]);
                        sz += p0[2] + t * (p1[2] - p0[2]);
                        cnt++;
                    }
                    // Índice con margen -> coordenadas del objeto (mm).
                    pos.add((float) (g.x0 + (i - 1 + sx / cnt) * g.step));
                    pos.add((float) (g.y0 + (j - 1 + sy / cnt) * g.step));
                    pos.add((float) (g.z0 + (k - 1 + sz / cnt) * g.step));
                    cellVert[(k * (ny - 1) + j) * (nx - 1) + i] = nverts++;
                }

        IntList tris = new IntList();
        int[] dims = {nx, ny, nz};
        int[] p = new int[3];
        for (int k = 0; k < nz; k++)
            for (int j = 0; j < ny; j++)
                for (int i = 0; i < nx; i++) {
                    p[0] = i; p[1] = j; p[2] = k;
                    float v0 = f[(k * ny + j) * nx + i];
                    for (int a = 0; a < 3; a++) {
                        int b = (a + 1) % 3, c = (a + 2) % 3;
                        if (p[a] + 1 >= dims[a] || p[b] < 1 || p[c] < 1) continue;
                        if (p[b] > dims[b] - 2 || p[c] > dims[c] - 2) continue;
                        int[] q = p.clone();
                        q[a]++;
                        float v1 = f[(q[2] * ny + q[1]) * nx + q[0]];
                        boolean in0 = v0 > iso;
                        if (in0 == (v1 > iso)) continue;
                        int c0 = cell(p, 0, 0, b, c, nx, ny);
                        int c1 = cell(p, 1, 0, b, c, nx, ny);
                        int c2 = cell(p, 1, 1, b, c, nx, ny);
                        int c3 = cell(p, 0, 1, b, c, nx, ny);
                        int q0 = cellVert[c0], q1 = cellVert[c1], q2 = cellVert[c2], q3 = cellVert[c3];
                        if (q0 < 0 || q1 < 0 || q2 < 0 || q3 < 0) continue;
                        // Dentro en p -> normal hacia +a -> orden (p, p-eb, p-eb-ec, p-ec).
                        if (in0) {
                            tris.add(q0); tris.add(q1); tris.add(q2);
                            tris.add(q0); tris.add(q2); tris.add(q3);
                        } else {
                            tris.add(q0); tris.add(q2); tris.add(q1);
                            tris.add(q0); tris.add(q3); tris.add(q2);
                        }
                    }
                }
        Mesh m = new Mesh();
        m.pos = pos.toArray();
        m.tri = tris.toArray();
        return m;
    }

    /** Índice de la celda con esquina mínima p - db*e_b - dc*e_c. */
    private static int cell(int[] p, int db, int dc, int b, int c, int nx, int ny) {
        int[] q = p.clone();
        q[b] -= db;
        q[c] -= dc;
        return (q[2] * (ny - 1) + q[1]) * (nx - 1) + q[0];
    }

    /** Desenfoque separable [1 2 1]/4 en los 3 ejes. */
    static void blur(float[] f, int nx, int ny, int nz) {
        float[] t = new float[f.length];
        int[] strides = {1, nx, nx * ny};
        int[] dims = {nx, ny, nz};
        for (int a = 0; a < 3; a++) {
            int s = strides[a];
            for (int k = 0; k < nz; k++)
                for (int j = 0; j < ny; j++)
                    for (int i = 0; i < nx; i++) {
                        int id = (k * ny + j) * nx + i;
                        int coord = a == 0 ? i : (a == 1 ? j : k);
                        float l = coord > 0 ? f[id - s] : 0, r = coord < dims[a] - 1 ? f[id + s] : 0;
                        t[id] = 0.25f * l + 0.5f * f[id] + 0.25f * r;
                    }
            System.arraycopy(t, 0, f, 0, f.length);
        }
    }

    static final class FloatList {
        float[] a = new float[1 << 12];
        int n;

        void add(float v) {
            if (n == a.length) a = java.util.Arrays.copyOf(a, n * 2);
            a[n++] = v;
        }

        float[] toArray() { return java.util.Arrays.copyOf(a, n); }
    }

    static final class IntList {
        int[] a = new int[1 << 12];
        int n;

        void add(int v) {
            if (n == a.length) a = java.util.Arrays.copyOf(a, n * 2);
            a[n++] = v;
        }

        int[] toArray() { return java.util.Arrays.copyOf(a, n); }
    }
}
