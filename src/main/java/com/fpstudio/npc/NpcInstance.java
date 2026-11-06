package com.fpstudio.npc;

import com.fpstudio.model.Actor;
import com.fpstudio.model.Clip;
import com.fpstudio.model.EventKey;
import com.fpstudio.model.EventType;
import com.fpstudio.model.Track;

import java.util.Map;

/**
 * Runtime state of one actor: its animation pose and the bone matrices derived from it. {@link #update} is pure maths
 * (no Minecraft classes), runs once per frame and allocates nothing.
 */
public final class NpcInstance {
    public final Actor actor;
    public final NpcAsset asset;
    private final GeoModel m;
    private final Pose cur, a, b;
    /** bone matrices (model space, column-major 4x4, 16 floats per bone) */
    public final float[] mats;
    /** per cube matrix scratch for rotated cubes, written during {@link #cubeMatrix} */
    private final float[] tmp = new float[16], tmp2 = new float[16];
    private final float[] ov = new float[Actor.BONE_CH];
    // resolved bone track indices
    private int[] boneIdx = new int[0];
    private Track[] boneTracks = new Track[0];
    private int boneCount = -1;
    // world transform of this frame
    public float wx, wy, wz, wyaw, wscale = 1f;
    public boolean visible;

    public NpcInstance(Actor actor, NpcAsset asset) {
        this.actor = actor;
        this.asset = asset;
        this.m = asset.model;
        cur = new Pose(m.bones);
        a = new Pose(m.bones);
        b = new Pose(m.bones);
        mats = new float[m.bones * 16];
    }

    private static final float[] MOVE = new float[Actor.MOVE_CH];

    public void update(Clip clip, float time) {
        // ---- world transform
        float[] mv = MOVE;
        actor.move.sample(time, mv);
        wx = actor.x + mv[0];
        wy = actor.y + mv[1];
        wz = actor.z + mv[2];
        wyaw = actor.yaw + mv[3];
        wscale = actor.scale;
        visible = actor.visible && m.bones > 0;
        if (!visible) return;

        // ---- animation selection
        EventKey now = null, before = null;
        EventKey ended = null;
        for (EventKey e : clip.events) {
            if (e.type != EventType.NPC_ANIM || e.time > time + 1e-5f || !actor.id.equals(idOf(e.arg))) continue;
            boolean active = e.dur <= 0f || time < e.time + e.dur;
            if (active) { before = now; now = e; }
            else if (ended == null || e.time + e.dur > ended.time + ended.dur) ended = e;
        }
        float bl = Math.max(0.001f, actor.blend);
        if (now != null) {
            sampleEvent(now, time, cur);
            float s = (time - now.time) / bl;
            if (s < 1f) {
                if (before != null && before.time + before.dur > now.time) sampleEvent(before, now.time, a);
                else sampleIdle(now.time, a);
                cur.blend(a, cur, smooth(s));
            }
        } else {
            sampleIdle(time, cur);
            if (ended != null) {
                float s = (time - (ended.time + ended.dur)) / bl;
                if (s < 1f) {
                    sampleEvent(ended, ended.time + ended.dur, a);
                    cur.blend(a, cur, smooth(s));
                }
            }
        }

        // ---- manual bone tracks (added on top)
        if (boneCount != actor.bones.size()) resolveBones();
        for (int i = 0; i < boneIdx.length; i++) {
            Track t = boneTracks[i];
            if (t.keys.isEmpty()) continue;
            t.sample(time, ov);
            int o = boneIdx[i] * 3;
            cur.pos[o] += ov[0];
            cur.pos[o + 1] += ov[1];
            cur.pos[o + 2] += ov[2];
            cur.rot[o] -= (float) Math.toRadians(ov[3]);
            cur.rot[o + 1] -= (float) Math.toRadians(ov[4]);
            cur.rot[o + 2] += (float) Math.toRadians(ov[5]);
            float sc = 1f + ov[6];
            cur.scl[o] *= sc;
            cur.scl[o + 1] *= sc;
            cur.scl[o + 2] *= sc;
        }
        buildMatrices();
    }

    private int headBone = -2;

    /** The animated pose of the last update (position / rotation radians / scale per bone). */
    public Pose pose() { return cur; }

    /**
     * Live mode for world NPCs: a looping/once animation with an optional crossfade from the previous one, plus a head turn
     * (radians, already in model-space sign). Pure maths like {@link #update}.
     */
    public void updateLive(String anim, float t, boolean loop, String prev, float prevT, boolean prevLoop, float blendK,
                           float headYaw, float headPitch) {
        visible = m.bones > 0;
        if (!visible) return;
        sampleLive(anim, t, loop, cur);
        if (prev != null && blendK < 1f) {
            sampleLive(prev, prevT, prevLoop, a);
            cur.blend(a, cur, smooth(blendK));
        }
        if (headBone == -2) {
            headBone = m.bone("head");
            if (headBone < 0) headBone = m.bone("Head");
            if (headBone < 0) for (int i = 0; i < m.bones; i++) if (m.name[i].equalsIgnoreCase("head")) { headBone = i; break; }
        }
        if (headBone >= 0) {
            cur.rot[headBone * 3] += headPitch;
            cur.rot[headBone * 3 + 1] += headYaw;
        }
        buildMatrices();
    }

    private void sampleLive(String name, float t, boolean loop, Pose out) {
        GeoAnimations.Bound an = asset.anim(name);
        if (an == null) { out.identity(); return; }
        float len = an.anim.length;
        float lt = loop ? (len > 1e-4f ? t % len : 0f) : Math.min(Math.max(t, 0f), len);
        an.sample(lt, out);
    }

    /** Call after the actor's bone track map changed (keys added to a new bone). */
    public void invalidate() { boneCount = -1; }

    private void resolveBones() {
        int n = actor.bones.size();
        int[] idx = new int[n];
        Track[] tr = new Track[n];
        int k = 0;
        for (Map.Entry<String, Track> e : actor.bones.entrySet()) {
            int bi = m.bone(e.getKey());
            if (bi < 0) continue;
            idx[k] = bi;
            tr[k++] = e.getValue();
        }
        boneIdx = java.util.Arrays.copyOf(idx, k);
        boneTracks = java.util.Arrays.copyOf(tr, k);
        boneCount = n;
    }

    private static float smooth(float s) {
        s = Math.max(0f, Math.min(1f, s));
        return s * s * (3f - 2f * s);
    }

    public static String idOf(String arg) {
        if (arg == null) return "";
        int i = arg.indexOf('|');
        return i < 0 ? arg : arg.substring(0, i);
    }

    public static String animOf(String arg) {
        if (arg == null) return "";
        int i = arg.indexOf('|');
        return i < 0 ? "" : arg.substring(i + 1);
    }

    private void sampleEvent(EventKey e, float time, Pose out) {
        GeoAnimations.Bound an = asset.anim(animOf(e.arg));
        if (an == null) { out.identity(); return; }
        float local = an.localTime((time - e.time) * (e.speed == 0f ? 1f : e.speed));
        an.sample(local, out);
    }

    private void sampleIdle(float time, Pose out) {
        String name = actor.idle.isEmpty() ? asset.autoIdle() : actor.idle;
        GeoAnimations.Bound an = asset.anim(name);
        if (an == null) { out.identity(); return; }
        // the idle clip always loops
        float len = an.anim.length;
        an.sample(len > 1e-4f ? time % len : 0f, out);
    }

    // ------------------------------------------------------------------ matrices

    private void buildMatrices() {
        float[] mt = mats;
        for (int i = 0; i < m.bones; i++) {
            int o = i * 16;
            int p = m.parent[i];
            if (p < 0) identity(mt, o); else System.arraycopy(mt, p * 16, mt, o, 16);
            float px = m.pivot[i * 3] / 16f, py = m.pivot[i * 3 + 1] / 16f, pz = m.pivot[i * 3 + 2] / 16f;
            translate(mt, o, px, py, pz);
            translate(mt, o, -cur.pos[i * 3] / 16f, cur.pos[i * 3 + 1] / 16f, cur.pos[i * 3 + 2] / 16f);
            rotateZYX(mt, o, m.baseRot[i * 3 + 2] + cur.rot[i * 3 + 2], m.baseRot[i * 3 + 1] + cur.rot[i * 3 + 1], m.baseRot[i * 3] + cur.rot[i * 3]);
            float sx = cur.scl[i * 3], sy = cur.scl[i * 3 + 1], sz = cur.scl[i * 3 + 2];
            if (sx != 1f || sy != 1f || sz != 1f) scale(mt, o, sx, sy, sz);
            translate(mt, o, -px, -py, -pz);
        }
    }

    /** Matrix for a cube: the bone matrix, plus the cube's own pivot rotation. Returns the array and sets offset 0. */
    public float[] cubeMatrix(int bone, GeoModel.Cube c) {
        if (!c.rotated) return null;
        System.arraycopy(mats, bone * 16, tmp, 0, 16);
        translate(tmp, 0, c.px / 16f, c.py / 16f, c.pz / 16f);
        rotateZYX(tmp, 0, c.rz, c.ry, c.rx);
        translate(tmp, 0, -c.px / 16f, -c.py / 16f, -c.pz / 16f);
        return tmp;
    }

    /** Position (model space, blocks) of a bone's pivot after animation. */
    public void bonePos(int bone, float[] out) {
        int o = bone * 16;
        float x = m.pivot[bone * 3] / 16f, y = m.pivot[bone * 3 + 1] / 16f, z = m.pivot[bone * 3 + 2] / 16f;
        out[0] = mats[o] * x + mats[o + 4] * y + mats[o + 8] * z + mats[o + 12];
        out[1] = mats[o + 1] * x + mats[o + 5] * y + mats[o + 9] * z + mats[o + 13];
        out[2] = mats[o + 2] * x + mats[o + 6] * y + mats[o + 10] * z + mats[o + 14];
    }

    /** World position of a bone pivot (actor yaw / scale / position applied). bone < 0 gives roughly eye height. */
    public void boneWorld(int bone, float[] out) {
        if (bone < 0) {
            out[0] = 0; out[1] = 1.6f; out[2] = 0;
        } else {
            bonePos(bone, out);
        }
        double r = Math.toRadians(180f - wyaw);
        float cs = (float) Math.cos(r), sn = (float) Math.sin(r);
        float x = out[0] * wscale, y = out[1] * wscale, z = out[2] * wscale;
        // rotation about +Y by r
        out[0] = wx + x * cs + z * sn;
        out[1] = wy + y;
        out[2] = wz - x * sn + z * cs;
    }

    // ---- tiny column-major matrix helpers: m = m * T / R / S (in place, at offset o)

    private static void identity(float[] m, int o) {
        for (int i = 0; i < 16; i++) m[o + i] = 0f;
        m[o] = m[o + 5] = m[o + 10] = m[o + 15] = 1f;
    }

    static void translate(float[] m, int o, float x, float y, float z) {
        m[o + 12] += m[o] * x + m[o + 4] * y + m[o + 8] * z;
        m[o + 13] += m[o + 1] * x + m[o + 5] * y + m[o + 9] * z;
        m[o + 14] += m[o + 2] * x + m[o + 6] * y + m[o + 10] * z;
        m[o + 15] += m[o + 3] * x + m[o + 7] * y + m[o + 11] * z;
    }

    static void scale(float[] m, int o, float x, float y, float z) {
        for (int i = 0; i < 4; i++) { m[o + i] *= x; m[o + 4 + i] *= y; m[o + 8 + i] *= z; }
    }

    /** m = m * Rz(z) * Ry(y) * Rx(x), like PoseStack.mulPose(Rz) then Ry then Rx. */
    static void rotateZYX(float[] m, int o, float z, float y, float x) {
        if (z != 0f) rot(m, o, 2, z);
        if (y != 0f) rot(m, o, 1, y);
        if (x != 0f) rot(m, o, 0, x);
    }

    private static void rot(float[] m, int o, int axis, float ang) {
        float c = (float) Math.cos(ang), s = (float) Math.sin(ang);
        int a = axis == 0 ? 4 : axis == 1 ? 8 : 0;   // first affected column offset
        int bcol = axis == 0 ? 8 : axis == 1 ? 0 : 4;
        // columns a,b rotate: for rotation about X: (col1,col2); Y: (col2,col0); Z: (col0,col1)
        for (int i = 0; i < 4; i++) {
            float ca = m[o + a + i], cb = m[o + bcol + i];
            m[o + a + i] = ca * c + cb * s;
            m[o + bcol + i] = cb * c - ca * s;
        }
    }
}
