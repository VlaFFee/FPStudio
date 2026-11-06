package com.fpstudio.model;

/** Turns a dense (recorded or edited) camera path into a few smooth keys: Gaussian smoothing, then key reduction. */
public final class CamSmooth {
    public static final float RATE = 30f;

    private CamSmooth() {}

    /** Samples the track at 30 Hz over [a, b] (inclusive). */
    static float[][] resample(Track src, float a, float b) {
        int n = Math.max(2, (int) Math.ceil((b - a) * RATE) + 1);
        float[][] v = new float[Clip.CAM_CH][n];
        for (int i = 0; i < n; i++) {
            float t = Math.min(b, a + i / RATE);
            for (int c = 0; c < Clip.CAM_CH; c++) v[c][i] = src.sampleChannel(c, t);
        }
        return v;
    }

    /** Gaussian blur with odd reflection at both ends (keeps the first and last value where they are). */
    static float[] blur(float[] x, float sigmaSeconds) {
        int n = x.length;
        float s = sigmaSeconds * RATE;
        if (s < 0.05f || n < 3) return x.clone();
        int r = Math.max(1, (int) Math.ceil(3 * s));
        float[] w = new float[r + 1];
        float sum = 0f;
        for (int k = 0; k <= r; k++) { w[k] = (float) Math.exp(-0.5 * (k / s) * (k / s)); sum += k == 0 ? w[k] : 2 * w[k]; }
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            float acc = 0f;
            for (int k = -r; k <= r; k++) acc += w[Math.abs(k)] * at(x, i + k);
            out[i] = acc / sum;
        }
        return out;
    }

    private static float at(float[] x, int i) {
        int n = x.length;
        if (i < 0) return 2 * x[0] - x[Math.min(n - 1, -i)];
        if (i > n - 1) return 2 * x[n - 1] - x[Math.max(0, 2 * (n - 1) - i)];
        return x[i];
    }

    /**
     * @param sigma  smoothing in seconds (0 = none)
     * @param detail key reduction tolerance multiplier (small = more keys, closer to the original)
     */
    public static Track process(Track src, float a, float b, float sigma, float detail) {
        Track out = new Track(Clip.CAM_CH);
        if (b - a < 1e-3f) { for (Keyframe k : src.keys) if (k.time >= a - 1e-4f && k.time <= b + 1e-4f) out.keys.add(k.copy()); return out; }
        float[][] v = resample(src, a, b);
        int n = v[0].length;
        for (int c = 0; c < Clip.CAM_CH; c++) v[c] = blur(v[c], sigma);
        for (int i = 0; i < n; i++) {
            Keyframe k = new Keyframe(Clip.CAM_CH);
            k.time = Math.round(Math.min(b, a + i / RATE) * 1000f) / 1000f;
            for (int c = 0; c < Clip.CAM_CH; c++) k.v[c] = v[c][i];
            k.easing = Easing.SPLINE;
            out.keys.add(k);
        }
        if (detail > 0.001f) Templates.simplify(out, 0.01f * detail, 0.6f * detail);
        return out;
    }

    /** Replace the keys of {@code cam} between a and b by the processed version of the same path. */
    public static void smoothRange(Track cam, float a, float b, float sigma, float detail) {
        Track p = process(cam, a, b, sigma, detail);
        cam.keys.removeIf(k -> k.time >= a - 1e-4f && k.time <= b + 1e-4f);
        for (Keyframe k : p.keys) cam.put(k);
    }

    /** Same, but from a separate dense recording. */
    public static void rebuildFromRaw(Track cam, Track raw, float sigma, float detail) {
        if (raw == null || raw.keys.size() < 2) return;
        raw.sort();
        float a = raw.keys.get(0).time, b = raw.keys.get(raw.keys.size() - 1).time;
        Track p = process(raw, a, b, sigma, detail);
        cam.keys.removeIf(k -> k.time >= a - 1e-4f && k.time <= b + 1e-4f);
        for (Keyframe k : p.keys) cam.put(k);
    }
}
