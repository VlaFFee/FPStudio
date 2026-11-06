package com.fpstudio.client;

import com.fpstudio.Config;
import com.fpstudio.client.gui.FreezeScreen;
import com.fpstudio.client.gui.StudioScreen;
import com.fpstudio.model.Clip;
import com.fpstudio.model.EventKey;
import com.fpstudio.model.EventType;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

/** Clip state + per-frame evaluation. All methods run on the render thread. */
public final class Playback {
    public static Clip clip = new Clip();
    public static boolean playing;
    public static boolean editing;          // studio screen is open
    public static boolean fullscreen;       // playing without the studio UI
    public static boolean returnToStudio;   // reopen studio when a fullscreen preview ends
    public static boolean runEvents;        // fire world-interaction events while playing
    public static boolean previewFx = true; // sway/shake visible while scrubbing in the editor
    public static float time;
    public static float sinceStart;
    public static final Sample cur = new Sample();
    private static final Sample disp = new Sample();   // smoothed pose shown while editing
    public static int liveTrack = -1;                  // track being recorded live (0 right, 1 left, 2 camera)
    public static final float[] live = new float[7];
    private static float frameDt;

    private static long lastNs;
    private static final float[] tmpArm = new float[Clip.ARM_CH];
    private static final float[] tmpCam = new float[Clip.CAM_CH];

    private Playback() {}

    /** While a camera take is recorded the camera track is muted, so you see (and record) your own view only. */
    public static boolean camMute;

    /** Seconds of "fly to the start of the camera path" still to go before the clip time starts running. */
    public static float lead;

    /** 0..1: how much of the anchored camera path (vs. the player's own view) is shown right now. */
    public static float anchorW() {
        Clip c = clip;
        if (!playing) return 1f;
        float w = 1f;
        if (lead > 0f && c.camFly > 0.01f) w = smooth(1f - lead / c.camFly);
        if (!c.loop && c.camFlyOut > 0.01f && lead <= 0f) w *= smooth((c.duration - time) / c.camFlyOut);
        return w;
    }

    /** The camera track is anchored in the world (not relative to wherever the player stands). */
    public static boolean anchored() {
        return clip.camAnchor != null && clip.camAnchor.on && !camMute && (playing || editing);
    }

    public static boolean stage;            // studio closed but the live pose preview stays on, so you can walk around

    public static boolean active() { return playing || editing || stage; }

    public static void play(float from, boolean full, boolean withEvents) {
        playing = true;
        fullscreen = full;
        runEvents = withEvents;
        time = Mth.clamp(from, 0f, clip.duration);
        if (time >= clip.duration - 1e-3f) time = 0f;
        sinceStart = 0f;
        lead = clip.camAnchor != null && clip.camAnchor.on && clip.camFly > 0.01f && time <= 0.001f && !MoveRecorder.busy() ? clip.camFly : 0f;
        if (full && Config.FREEZE_INPUT.get() && clip.freezeInput) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && !(mc.screen instanceof FreezeScreen)) mc.setScreen(new FreezeScreen());
        }
    }

    private static void releaseFreeze() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof FreezeScreen) mc.setScreen(null);
    }

    public static void pause() { lead = 0f; playing = false; fullscreen = false; Motion.reset(); releaseFreeze(); }

    /** Stop playback. If this was a fullscreen preview started from the studio, reopen it. */
    public static void stop(boolean reopenStudio) {
        boolean wasFull = fullscreen;
        lead = 0f;
        Motion.reset();
        releaseFreeze();
        playing = false;
        fullscreen = false;
        if (reopenStudio && returnToStudio && wasFull) {
            returnToStudio = false;
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> mc.setScreen(new StudioScreen()));
        }
        returnToStudio = false;
    }

    /** Off-screen offset for a direction name. Right-hand values; the left hand mirrors x and roll. */
    public static void dirOffset(String dir, EventKey e, float[] out) {
        java.util.Arrays.fill(out, 0f);
        switch (dir) {
            case "up" -> { out[1] = 1.5f; out[3] = 30f; }
            case "side" -> { out[0] = 1.0f; out[1] = -0.25f; out[4] = 30f; }      // outward: right hand to the right
            case "inward" -> { out[0] = -1.0f; out[1] = -0.25f; out[4] = -30f; }  // towards the middle / opposite side
            case "forward" -> { out[2] = 3.0f; out[1] = 0.2f; }
            case "custom" -> { out[0] = e.hx; out[1] = e.hy; out[2] = e.hz; out[3] = e.hp; out[4] = e.hr; }
            default -> { out[1] = -1.5f; out[3] = -30f; out[4] = 20f; }           // down
        }
    }

    /** Direction word of a HANDS event ("down" if unset; "auto" only means something for show events). */
    public static String handsDir(EventKey e, boolean hide) {
        String a = e.arg == null ? "" : e.arg;
        String[] p = a.split("\\|");
        String d = p.length > 2 ? p[2].trim() : "";
        return d.isEmpty() ? (hide ? "down" : "auto") : d;
    }

    /**
     * Where a hand is at time t because of HANDS events: out[0..4] = x y z pitch roll offsets (added to the pose) and
     * out[5] = 0..1 how far it is gone (1 = fully out of view). Evaluated from time, so scrubbing is exact.
     */
    public static void handState(Clip c, float t, int hand, float[] out) {
        float[] fromV = new float[5], toV = new float[5], p = new float[5];
        float fromH = 0f, toH = 0f, t0 = 0f, d = 0.01f;
        float side = hand == 0 ? 1f : -1f;
        for (EventKey e : c.events) {
            if (e.type != EventType.HANDS) continue;
            if (e.time > t) break;
            String a = e.arg == null ? "" : e.arg;
            String[] parts = a.split("\\|");
            String who = parts.length > 0 ? parts[0].trim() : "";
            boolean hide = parts.length < 2 || !parts[1].trim().equalsIgnoreCase("show");
            boolean mine = who.isEmpty() || who.equals("both") || who.equals(hand == 0 ? "right" : "left");
            if (!mine) continue;
            float s = smooth((e.time - t0) / d);
            float curH = fromH + (toH - fromH) * s;
            for (int i = 0; i < 5; i++) fromV[i] = fromV[i] + (toV[i] - fromV[i]) * s;
            fromH = curH;
            String dir = handsDir(e, hide);
            if (hide) {
                dirOffset(dir, e, p);
                p[0] *= side;
                p[4] *= side;
                System.arraycopy(p, 0, toV, 0, 5);
                toH = 1f;
            } else {
                if (curH >= 0.99f && !dir.equals("auto")) {
                    dirOffset(dir, e, p);
                    p[0] *= side;
                    p[4] *= side;
                    System.arraycopy(p, 0, fromV, 0, 5);
                }
                java.util.Arrays.fill(toV, 0f);
                toH = 0f;
            }
            t0 = e.time;
            d = Math.max(0.01f, e.dur);
        }
        float s = smooth((t - t0) / d);
        for (int i = 0; i < 5; i++) out[i] = fromV[i] + (toV[i] - fromV[i]) * s;
        out[5] = fromH + (toH - fromH) * s;
    }

    /** How hidden a hand is at time t: 0 = in view, 1 = slid fully out of the screen. */
    public static float handHide(Clip c, float t, int hand) {
        float[] o = new float[6];
        handState(c, t, hand, o);
        return o[5];
    }

    /** What the renderer shows (glides while editing, like the poses). */
    public static float shownHideR, shownHideL;

    /** Called once per rendered frame (start of render tick). */
    public static void frame() {
        long now = System.nanoTime();
        float dt = lastNs == 0 ? 0f : Mth.clamp((now - lastNs) / 1e9f, 0f, 0.1f);
        lastNs = now;

        frameDt = dt;
        StudioFx.update(dt);

        if (playing) advance(dt);
        if (active()) compute(); else { cur.zero(); disp.zero(); }
        smoothForEditing();
        float kh = playing ? 1f : 1f - (float) Math.exp(-dt * 16f);
        shownHideR += (hideTR - shownHideR) * kh;
        shownHideL += (hideTL - shownHideL) * kh;
        if (active()) com.fpstudio.client.npc.NpcRuntime.update(clip, time); else com.fpstudio.client.npc.NpcRuntime.clear();
    }

    /** While editing (not playing) pose changes glide instead of snapping; playback stays frame-exact. */
    private static void smoothForEditing() {
        if (!active()) return;
        if (!playing && frameDt > 0f) {
            float k = 1f - (float) Math.exp(-frameDt * 16f);
            blend(cur.r, disp.r, k);
            blend(cur.l, disp.l, k);
            blend(cur.c, disp.c, k);
        }
        System.arraycopy(cur.r, 0, disp.r, 0, 6);
        System.arraycopy(cur.l, 0, disp.l, 0, 6);
        System.arraycopy(cur.c, 0, disp.c, 0, 7);
    }

    private static void blend(float[] target, float[] shown, float k) {
        for (int i = 0; i < target.length; i++) target[i] = shown[i] + (target[i] - shown[i]) * k;
    }

    private static void advance(float dt) {
        if (lead > 0f) { lead = Math.max(0f, lead - dt); return; }
        float prev = time;
        time += dt;
        sinceStart += dt;
        if (time >= clip.duration) {
            fire(prev, clip.duration);
            if (clip.loop) {
                time = time % Math.max(0.1f, clip.duration);
                fire(-1f, time);
            } else {
                time = clip.duration;
                stop(true);
            }
        } else {
            fire(prev, time);
        }
    }

    private static void fire(float from, float to) {
        if (!runEvents) return;
        Motion.turn(from, to);
        for (EventKey e : clip.events) {
            if (e.time > from && e.time <= to) Actions.fire(e);
        }
    }

    private static float smooth(float t) {
        t = Mth.clamp(t, 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private static void compute() {
        Clip c = clip;
        float t = time;

        float w = 1f;
        if (playing) {
            if (c.blendIn > 0.001f) w = smooth(sinceStart / c.blendIn);
            if (!c.loop && c.blendOut > 0.001f) w *= smooth((c.duration - t) / c.blendOut);
        }
        boolean fx = playing || previewFx;

        c.right.sample(t, tmpArm);
        for (int i = 0; i < 6; i++) cur.r[i] = tmpArm[i] * w;
        c.left.sample(t, tmpArm);
        for (int i = 0; i < 6; i++) cur.l[i] = tmpArm[i] * w;
        c.camera.sample(t, tmpCam);
        for (int i = 0; i < 7; i++) cur.c[i] = camMute ? 0f : tmpCam[i] * (clip.camAnchor != null && clip.camAnchor.on ? 1f : w);

        if (liveTrack >= 0 && playing) {
            float[] dst = liveTrack == 0 ? cur.r : liveTrack == 1 ? cur.l : cur.c;
            System.arraycopy(live, 0, dst, 0, dst.length);
        }

        // HANDS events: slide the hands down and out of the frame
        handState(c, t, 0, hsR);
        handState(c, t, 1, hsL);
        hideTR = hsR[5];
        hideTL = hsL[5];
        slideOut(cur.r, hsR);
        slideOut(cur.l, hsL);

        if (!fx) return;

        // Procedural life: breathing hands and hand-held camera (sums of incommensurate sines = smooth, deterministic).
        float s = c.sway * w;
        if (s > 0f) {
            sway(cur.r, t, 0f, s);
            sway(cur.l, t, 2.1f, s);
        }
        float sh = c.shake * w;
        if (sh > 0f) {
            cur.c[0] += n(t, 3.1f, 0.3f) * 0.012f * sh;
            cur.c[1] += n(t, 2.7f, 1.4f) * 0.016f * sh;
            cur.c[3] += n(t, 4.3f, 2.2f) * 0.35f * sh;
            cur.c[4] += n(t, 3.7f, 0.9f) * 0.35f * sh;
            cur.c[5] += n(t, 2.3f, 3.1f) * 0.45f * sh;
        }

        // Impact events: damped oscillation, evaluated from time so scrubbing is exact.
        for (EventKey e : c.events) {
            if (e.type != EventType.IMPACT) continue;
            float dt = t - e.time;
            if (dt < 0f || dt > 2f) continue;
            float k = parse(e.arg, 1f) * (float) Math.exp(-5.5f * dt);
            float o = (float) Math.sin(dt * 38f);
            float o2 = (float) Math.sin(dt * 23f + 1f);
            cur.c[3] -= k * 3.2f * o;
            cur.c[5] += k * 1.4f * o2;
            cur.c[4] += k * 0.8f * o2;
            cur.c[6] += k * 3.0f * (float) Math.exp(-14f * dt);
            cur.r[2] += k * 0.05f * o;
            cur.l[2] += k * 0.05f * o;
            cur.r[1] -= k * 0.03f * o2;
            cur.l[1] -= k * 0.03f * o2;
        }
    }

    private static float hideTR, hideTL;

    private static final float[] hsR = new float[6], hsL = new float[6];

    private static void slideOut(float[] a, float[] h) {
        a[0] += h[0];
        a[1] += h[1];
        a[2] += h[2];
        a[3] += h[3];
        a[5] += h[4];
    }

    private static void sway(float[] a, float t, float ph, float s) {
        a[0] += (float) Math.sin(t * 1.3 + ph) * 0.006f * s;
        a[1] += (float) Math.sin(t * 1.9 + ph + 0.7) * 0.009f * s;
        a[2] += (float) Math.sin(t * 1.1 + ph + 1.9) * 0.006f * s;
        a[3] += (float) Math.sin(t * 1.5 + ph + 0.3) * 1.1f * s;
        a[5] += (float) Math.sin(t * 0.9 + ph + 2.4) * 1.0f * s;
    }

    private static float n(float t, float f, float ph) {
        return (float) (Math.sin(t * f + ph) + 0.5 * Math.sin(t * f * 2.17 + ph * 1.7) + 0.25 * Math.sin(t * f * 4.31 + ph * 0.6)) / 1.75f;
    }

    public static float parse(String s, float def) {
        try {
            return Float.parseFloat(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    /** Raw track sample for the editor (what a new keyframe should capture). */
    public static void sampleRaw(int track, float t, float[] out) {
        clip.track(track).sample(t, out);
    }

    public static float uiSpeed() { return Config.UI_SPEED.get().floatValue(); }
}
