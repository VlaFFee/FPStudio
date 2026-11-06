package com.fpstudio.model;

import java.io.IOException;

/** Starter clips so the studio is never empty on first launch. Values are offsets from the vanilla resting pose. */
public final class Presets {
    private Presets() {}

    private static Keyframe k(Track t, float time, Easing e, float... v) {
        Keyframe k = new Keyframe(t.channels);
        k.time = time;
        k.easing = e;
        System.arraycopy(v, 0, k.v, 0, Math.min(v.length, k.v.length));
        t.keys.add(k);
        return k;
    }

    public static Clip inspect() {
        Clip c = new Clip();
        c.name = "demo_inspect";
        c.duration = 5f;
        c.sway = 1f;
        c.shake = 0.25f;
        k(c.right, 0f, Easing.SPLINE);
        k(c.right, 1.1f, Easing.SPLINE, -0.22f, 0.55f, 0.30f, 15f, 18f, -18f);
        k(c.right, 2.6f, Easing.SPLINE, -0.20f, 0.50f, 0.32f, 12f, 22f, 28f);
        k(c.right, 4.5f, Easing.SMOOTH);
        k(c.left, 0.2f, Easing.SPLINE);
        k(c.left, 1.3f, Easing.SPLINE, 0.22f, 0.55f, 0.30f, 15f, -18f, 18f);
        k(c.left, 2.8f, Easing.SPLINE, 0.20f, 0.50f, 0.32f, 12f, -22f, -28f);
        k(c.left, 4.5f, Easing.SMOOTH);
        k(c.camera, 0f, Easing.SMOOTH);
        k(c.camera, 2.2f, Easing.SMOOTH, 0, 0, 0, 3f, 0, 1.5f, -4f);
        k(c.camera, 4.5f, Easing.SMOOTH);
        return c;
    }

    public static Clip punch() {
        Clip c = new Clip();
        c.name = "demo_punch";
        c.duration = 1.8f;
        c.sway = 0.6f;
        c.blendIn = 0.15f;
        c.blendOut = 0.3f;
        k(c.right, 0f, Easing.EASE_OUT);
        k(c.right, 0.28f, Easing.EASE_IN, 0.16f, -0.12f, -0.20f, -12f, -6f, 8f);
        k(c.right, 0.46f, Easing.EASE_OUT, -0.35f, 0.32f, 0.65f, 8f, 12f, -6f);
        k(c.right, 1.5f, Easing.SMOOTH);
        k(c.left, 0f, Easing.SMOOTH);
        k(c.left, 0.35f, Easing.SMOOTH, 0.18f, 0.28f, 0.2f, 6f, -14f, 10f);
        k(c.left, 1.5f, Easing.SMOOTH);
        k(c.camera, 0f, Easing.SMOOTH);
        k(c.camera, 0.46f, Easing.EASE_OUT, 0, 0, 0.08f, 0, 0, 0, 3f);
        k(c.camera, 1.2f, Easing.SMOOTH);
        c.events.add(new EventKey(0.40f, EventType.SOUND, "minecraft:entity.player.attack.sweep,0.8,1.1"));
        c.events.add(new EventKey(0.46f, EventType.ATTACK, ""));
        c.events.add(new EventKey(0.46f, EventType.IMPACT, "1.2"));
        return c;
    }

    public static Clip place() {
        Clip c = new Clip();
        c.name = "demo_place";
        c.duration = 2f;
        k(c.right, 0f, Easing.SMOOTH);
        k(c.right, 0.35f, Easing.SMOOTH, -0.25f, 0.22f, 0.35f, 10f, 8f, -8f);
        k(c.right, 0.60f, Easing.EASE_OUT, -0.25f, 0.12f, 0.50f, 16f, 8f, -8f);
        k(c.right, 1.6f, Easing.SMOOTH);
        c.events.add(new EventKey(0.60f, EventType.USE, ""));
        c.events.add(new EventKey(0.60f, EventType.SWING_MAIN, ""));
        return c;
    }

    public static void installIfMissing() {
        try {
            if (ClipIO.list().isEmpty()) {
                ClipIO.save(inspect());
                ClipIO.save(punch());
                ClipIO.save(place());
            }
        } catch (IOException ignored) {
        }
    }
}
