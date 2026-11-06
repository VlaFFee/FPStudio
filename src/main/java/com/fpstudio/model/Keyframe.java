package com.fpstudio.model;

import java.util.Arrays;

/**
 * One key. It holds a value for every channel, but {@link #has} says which channels it really keys: a key made in a single
 * channel row only affects that channel, like per-property keys in a dope sheet. {@code has == null} means "all channels".
 */
public class Keyframe {
    public float time;
    public float[] v;
    public boolean[] has;          // null = keys every channel
    public Easing easing = Easing.SMOOTH;

    public Keyframe() { this(6); }

    public Keyframe(int channels) { this.v = new float[channels]; }

    public boolean has(int c) { return has == null || (c < has.length && has[c]); }

    public void setHas(int c, boolean on) {
        if (has == null) {
            if (on) return;
            has = new boolean[v.length];
            Arrays.fill(has, true);
        }
        has[c] = on;
        boolean all = true;
        for (boolean b : has) if (!b) { all = false; break; }
        if (all && has.length == v.length) has = null;
    }

    public boolean any() {
        for (int c = 0; c < v.length; c++) if (has(c)) return true;
        return false;
    }

    public boolean hasAll() {
        for (int c = 0; c < v.length; c++) if (!has(c)) return false;
        return true;
    }

    public int count() {
        int n = 0;
        for (int c = 0; c < v.length; c++) if (has(c)) n++;
        return n;
    }

    public Keyframe copy() {
        Keyframe k = new Keyframe(v.length);
        k.time = time;
        k.easing = easing;
        System.arraycopy(v, 0, k.v, 0, v.length);
        if (has != null) k.has = has.clone();
        return k;
    }
}
