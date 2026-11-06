package com.fpstudio.npc;

/** Per-bone animation state: position (model units, file convention), rotation (radians, GeckoLib sign convention) and scale. */
public final class Pose {
    public final float[] pos, rot, scl;
    public final int bones;

    public Pose(int bones) {
        this.bones = bones;
        pos = new float[bones * 3];
        rot = new float[bones * 3];
        scl = new float[bones * 3];
        identity();
    }

    public void identity() {
        java.util.Arrays.fill(pos, 0f);
        java.util.Arrays.fill(rot, 0f);
        java.util.Arrays.fill(scl, 1f);
    }

    public void copyFrom(Pose o) {
        System.arraycopy(o.pos, 0, pos, 0, pos.length);
        System.arraycopy(o.rot, 0, rot, 0, rot.length);
        System.arraycopy(o.scl, 0, scl, 0, scl.length);
    }

    /** this = a + (b - a) * t */
    public void blend(Pose a, Pose b, float t) {
        for (int i = 0; i < pos.length; i++) {
            pos[i] = a.pos[i] + (b.pos[i] - a.pos[i]) * t;
            rot[i] = a.rot[i] + (b.rot[i] - a.rot[i]) * t;
            scl[i] = a.scl[i] + (b.scl[i] - a.scl[i]) * t;
        }
    }
}
