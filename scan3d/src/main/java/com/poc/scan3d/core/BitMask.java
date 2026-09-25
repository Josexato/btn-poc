package com.poc.scan3d.core;

/** Máscara binaria compacta (1 bit por píxel) para guardar muchas siluetas en memoria. */
public final class BitMask {

    public final int w, h;
    private final long[] bits;

    public BitMask(int w, int h) {
        this.w = w; this.h = h;
        this.bits = new long[(w * h + 63) >>> 6];
    }

    public static BitMask fromBytes(byte[] m, int w, int h) {
        BitMask b = new BitMask(w, h);
        for (int i = 0; i < m.length; i++) if (m[i] != 0) b.bits[i >>> 6] |= 1L << (i & 63);
        return b;
    }

    public boolean get(int x, int y) {
        int i = y * w + x;
        return (bits[i >>> 6] & (1L << (i & 63))) != 0;
    }

    public byte[] toBytes() {
        byte[] m = new byte[w * h];
        for (int i = 0; i < m.length; i++) if ((bits[i >>> 6] & (1L << (i & 63))) != 0) m[i] = 1;
        return m;
    }
}
