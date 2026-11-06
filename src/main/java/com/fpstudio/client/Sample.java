package com.fpstudio.client;

import java.util.Arrays;

/** Final per-frame pose (blend, sway, shake and impulses already applied). Zero = vanilla resting pose. */
public final class Sample {
    public final float[] r = new float[6];
    public final float[] l = new float[6];
    public final float[] c = new float[7];

    public void zero() {
        Arrays.fill(r, 0f);
        Arrays.fill(l, 0f);
        Arrays.fill(c, 0f);
    }

    public boolean cameraMoved() {
        return Math.abs(c[0]) + Math.abs(c[1]) + Math.abs(c[2]) > 1e-5f;
    }
}
