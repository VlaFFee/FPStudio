package com.fpstudio.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public class Track {
    public int channels = 6;
    public List<Keyframe> keys = new ArrayList<>();

    public Track() {}

    public Track(int channels) { this.channels = channels; }

    public void sort() {
        keys.sort((a, b) -> Float.compare(a.time, b.time));
    }

    /** Insert a key; an existing key at (almost) the same time is replaced. */
    public Keyframe put(Keyframe k) {
        for (int i = 0; i < keys.size(); i++) {
            if (Math.abs(keys.get(i).time - k.time) < 0.001f) {
                keys.set(i, k);
                return k;
            }
        }
        keys.add(k);
        sort();
        return k;
    }

    /** Key a single channel at {@code time}; joins an existing key at that time, otherwise makes a one-channel key. */
    public Keyframe putChannel(float time, int ch, float value, Easing easing) {
        for (Keyframe k : keys) {
            if (Math.abs(k.time - time) < 0.001f) {
                k.v[ch] = value;
                k.setHas(ch, true);
                return k;
            }
        }
        Keyframe k = new Keyframe(channels);
        k.time = time;
        k.easing = easing;
        k.has = new boolean[channels];
        k.has[ch] = true;
        k.v[ch] = value;
        keys.add(k);
        sort();
        return k;
    }

    /** Merge keys that ended up at the same time (after dragging). Returns removed key -> the key that absorbed it. */
    public Map<Keyframe, Keyframe> mergeCoincident() {
        sort();
        Map<Keyframe, Keyframe> replaced = new IdentityHashMap<>();
        int i = 0;
        while (i + 1 < keys.size()) {
            Keyframe a = keys.get(i), b = keys.get(i + 1);
            if (Math.abs(b.time - a.time) < 0.001f) {
                for (int c = 0; c < channels; c++) {
                    if (b.has(c)) {
                        a.v[c] = b.v[c];
                        a.setHas(c, true);
                    }
                }
                if (b.hasAll()) a.easing = b.easing;
                replaced.put(b, a);
                keys.remove(i + 1);
            } else {
                i++;
            }
        }
        return replaced;
    }

    /** Ensure every key has exactly {@link #channels} values (after loading old / hand-edited files). */
    public void fix() {
        for (Keyframe k : keys) {
            if (k.v == null) k.v = new float[channels];
            else if (k.v.length != channels) k.v = Arrays.copyOf(k.v, channels);
            if (k.has != null) {
                if (k.has.length != channels) {
                    boolean[] h = new boolean[channels];
                    Arrays.fill(h, true);
                    System.arraycopy(k.has, 0, h, 0, Math.min(k.has.length, channels));
                    k.has = h;
                }
                boolean all = true;
                for (boolean b : k.has) if (!b) { all = false; break; }
                if (all) k.has = null;
            }
            if (k.easing == null) k.easing = Easing.SMOOTH;
        }
        keys.removeIf(k -> !k.any());
        sort();
    }

    /** Raw (unblended) value of one channel at {@code time}. No keys on the channel = neutral 0. */
    public float sampleChannel(int c, float time) {
        Keyframe p = null, a = null, b = null, nx = null;
        for (Keyframe k : keys) {
            if (!k.has(c)) continue;
            if (k.time <= time) {
                p = a;
                a = k;
            } else if (b == null) {
                b = k;
            } else {
                nx = k;
                break;
            }
        }
        if (a == null) return b == null ? 0f : b.v[c];
        if (b == null) return a.v[c];

        float dt = b.time - a.time;
        float t = dt <= 1e-6f ? 1f : (time - a.time) / dt;
        float va = a.v[c], vb = b.v[c];
        if (a.easing == Easing.SPLINE) {
            float s = t, s2 = s * s, s3 = s2 * s;
            float h00 = 2 * s3 - 3 * s2 + 1, h10 = s3 - 2 * s2 + s, h01 = -2 * s3 + 3 * s2, h11 = s3 - s2;
            float ma = p != null ? (vb - p.v[c]) / Math.max(1e-4f, b.time - p.time) * dt : (vb - va);
            float mb = nx != null ? (nx.v[c] - va) / Math.max(1e-4f, nx.time - a.time) * dt : (vb - va);
            return h00 * va + h10 * ma + h01 * vb + h11 * mb;
        }
        return va + (vb - va) * a.easing.apply(t);
    }

    /** Raw (unblended) value of the whole track. Writes {@link #channels} floats into out. */
    public void sample(float time, float[] out) {
        Arrays.fill(out, 0f);
        for (int c = 0; c < channels && c < out.length; c++) out[c] = sampleChannel(c, time);
    }
}
