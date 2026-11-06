package com.fpstudio.model;

import java.util.List;

/**
 * One-click animation building blocks. Each template is inserted at a start time: it first anchors the tracks it touches
 * at their current pose (so it blends from whatever came before), then adds its keys and, optionally, a key that returns
 * the hands to the resting pose. Chain several templates one after another to build a whole scene in seconds.
 * Pose values: x, y, z, pitch(+up), yaw(+left), roll. Z is forward.
 */
public final class Templates {
    public static final int R = 1, L = 2, CAM = 4;

    public interface Builder { void build(Clip c, float t); }

    public static final int BASIC = 0, EPIC = 1, MOVE = 2;

    public record Template(String id, float duration, int mask, int category, Builder builder) {
        public Template(String id, float duration, int mask, Builder builder) { this(id, duration, mask, BASIC, builder); }
    }

    private Templates() {}

    private static void k(Track tr, float time, Easing e, float... v) {
        Keyframe k = new Keyframe(tr.channels);
        k.time = time;
        k.easing = e;
        System.arraycopy(v, 0, k.v, 0, Math.min(v.length, k.v.length));
        tr.put(k);
    }

    /** Same pose on the left arm, mirrored. */
    private static void kl(Clip c, float time, Easing e, float x, float y, float z, float p, float yaw, float roll) {
        k(c.left, time, e, -x, y, z, p, -yaw, -roll);
    }

    private static void ev(Clip c, float time, EventType type, String arg, float dur) {
        c.events.add(new EventKey(time, type, arg, dur));
    }

    private static void ev(Clip c, float time, EventType type, String arg) { ev(c, time, type, arg, 0f); }

    public static final List<Template> ALL = List.of(
            new Template("inspect", 3.0f, R | L | CAM, (c, t) -> {
                k(c.right, t + 0.7f, Easing.SPLINE, -0.22f, 0.50f, 0.32f, 14f, 16f, -16f);
                k(c.right, t + 1.6f, Easing.SPLINE, -0.20f, 0.48f, 0.34f, 12f, 24f, 26f);
                k(c.right, t + 2.3f, Easing.SPLINE, -0.22f, 0.50f, 0.32f, 14f, 16f, -10f);
                kl(c, t + 0.8f, Easing.SPLINE, -0.22f, 0.50f, 0.32f, 14f, 16f, -16f);
                kl(c, t + 1.8f, Easing.SPLINE, -0.20f, 0.48f, 0.34f, 12f, 24f, 26f);
                kl(c, t + 2.4f, Easing.SPLINE, -0.22f, 0.50f, 0.32f, 14f, 16f, -10f);
                k(c.camera, t + 1.4f, Easing.SMOOTH, 0, 0, 0, -6f, 0, 0, -3f);
            }),
            new Template("reach", 1.6f, R, (c, t) -> {
                k(c.right, t + 0.55f, Easing.EASE_OUT, -0.28f, 0.30f, 0.75f, 10f, 6f, 0f);
                k(c.right, t + 1.1f, Easing.SMOOTH, -0.28f, 0.30f, 0.75f, 10f, 6f, 0f);
            }),
            new Template("punch", 1.2f, R | L | CAM, (c, t) -> {
                k(c.right, t + 0.22f, Easing.EASE_IN, 0.16f, -0.12f, -0.20f, -12f, -6f, 8f);
                k(c.right, t + 0.38f, Easing.EASE_OUT, -0.35f, 0.32f, 0.65f, 8f, 12f, -6f);
                k(c.right, t + 0.7f, Easing.SMOOTH, -0.35f, 0.32f, 0.65f, 8f, 12f, -6f);
                kl(c, t + 0.3f, Easing.SMOOTH, -0.18f, 0.28f, 0.2f, 6f, 14f, -10f);
                c.events.add(new EventKey(t + 0.34f, EventType.SOUND, "minecraft:entity.player.attack.sweep,0.8,1.1"));
                c.events.add(new EventKey(t + 0.38f, EventType.ATTACK, ""));
                c.events.add(new EventKey(t + 0.38f, EventType.IMPACT, "1.2"));
            }),
            new Template("place", 1.4f, R, (c, t) -> {
                k(c.right, t + 0.35f, Easing.SMOOTH, -0.25f, 0.22f, 0.35f, 10f, 8f, -8f);
                k(c.right, t + 0.55f, Easing.EASE_OUT, -0.25f, 0.12f, 0.50f, 16f, 8f, -8f);
                k(c.right, t + 0.8f, Easing.SMOOTH, -0.25f, 0.12f, 0.50f, 16f, 8f, -8f);
                c.events.add(new EventKey(t + 0.55f, EventType.USE, ""));
                c.events.add(new EventKey(t + 0.55f, EventType.SWING_MAIN, ""));
            }),
            new Template("break", 1.3f, R | CAM, (c, t) -> {
                k(c.right, t + 0.18f, Easing.EASE_IN, 0.1f, 0.05f, -0.1f, -14f, -4f, 6f);
                k(c.right, t + 0.32f, Easing.EASE_OUT, -0.2f, 0.18f, 0.45f, 10f, 6f, -6f);
                k(c.right, t + 0.5f, Easing.EASE_IN, 0.1f, 0.05f, -0.1f, -14f, -4f, 6f);
                k(c.right, t + 0.64f, Easing.EASE_OUT, -0.2f, 0.18f, 0.45f, 10f, 6f, -6f);
                c.events.add(new EventKey(t + 0.32f, EventType.ATTACK, ""));
                c.events.add(new EventKey(t + 0.64f, EventType.ATTACK, ""));
                c.events.add(new EventKey(t + 0.64f, EventType.IMPACT, "0.6"));
            }),
            new Template("wave", 2.2f, R, (c, t) -> {
                k(c.right, t + 0.45f, Easing.SMOOTH, -0.1f, 0.55f, 0.15f, 8f, 0f, 0f);
                for (int i = 0; i < 4; i++) {
                    float s = i % 2 == 0 ? 1f : -1f;
                    k(c.right, t + 0.7f + i * 0.28f, Easing.SMOOTH, -0.1f, 0.55f, 0.15f, 8f, 0f, 24f * s);
                }
                k(c.right, t + 1.9f, Easing.SMOOTH, -0.1f, 0.55f, 0.15f, 8f, 0f, 0f);
            }),
            new Template("push", 1.4f, R | L | CAM, (c, t) -> {
                k(c.right, t + 0.4f, Easing.EASE_OUT, -0.15f, 0.30f, 0.60f, 6f, 8f, 0f);
                kl(c, t + 0.4f, Easing.EASE_OUT, -0.15f, 0.30f, 0.60f, 6f, 8f, 0f);
                k(c.right, t + 0.8f, Easing.SMOOTH, -0.15f, 0.30f, 0.60f, 6f, 8f, 0f);
                kl(c, t + 0.8f, Easing.SMOOTH, -0.15f, 0.30f, 0.60f, 6f, 8f, 0f);
                k(c.camera, t + 0.5f, Easing.SMOOTH, 0, 0, 0.06f, 0, 0, 0, 2f);
                c.events.add(new EventKey(t + 0.45f, EventType.USE, ""));
            }),
            new Template("show_item", 2.0f, R, (c, t) -> {
                k(c.right, t + 0.6f, Easing.EASE_OUT, -0.55f, 0.55f, 0.20f, 22f, 28f, -18f);
                k(c.right, t + 1.5f, Easing.SMOOTH, -0.55f, 0.55f, 0.20f, 22f, 28f, -18f);
            }),
            new Template("swipe", 1.0f, R | CAM, (c, t) -> {
                k(c.right, t + 0.25f, Easing.EASE_IN, 0.45f, 0.20f, 0.30f, 6f, -35f, 14f);
                k(c.right, t + 0.5f, Easing.EASE_OUT, -0.50f, 0.25f, 0.40f, 6f, 35f, -14f);
                k(c.camera, t + 0.5f, Easing.SMOOTH, 0, 0, 0, 0, 2f, 1.5f);
                c.events.add(new EventKey(t + 0.3f, EventType.SWING_MAIN, ""));
            }),
            new Template("cover", 1.8f, R | L | CAM, (c, t) -> {
                k(c.right, t + 0.3f, Easing.EASE_OUT, -0.42f, 0.62f, 0.30f, 20f, 25f, -20f);
                kl(c, t + 0.3f, Easing.EASE_OUT, -0.42f, 0.62f, 0.30f, 20f, 25f, -20f);
                k(c.right, t + 1.3f, Easing.SMOOTH, -0.42f, 0.62f, 0.30f, 20f, 25f, -20f);
                kl(c, t + 1.3f, Easing.SMOOTH, -0.42f, 0.62f, 0.30f, 20f, 25f, -20f);
                c.events.add(new EventKey(t + 0.3f, EventType.IMPACT, "0.8"));
            }),
            new Template("hit_taken", 1.4f, CAM, (c, t) -> {
                k(c.camera, t + 0.08f, Easing.EASE_OUT, 0, 0, -0.05f, -5f, 4f, 6f, 6f);
                k(c.camera, t + 0.7f, Easing.SMOOTH, 0, 0, 0, -1f, 0, 1f, 0f);
                c.events.add(new EventKey(t + 0.05f, EventType.IMPACT, "1.8"));
            }),
            new Template("look_hands", 2.4f, R | L | CAM, (c, t) -> {
                k(c.camera, t + 0.7f, Easing.SMOOTH, 0, 0, 0, -16f, 0, 0, -4f);
                k(c.camera, t + 1.8f, Easing.SMOOTH, 0, 0, 0, -16f, 0, 0, -4f);
                k(c.right, t + 0.7f, Easing.SMOOTH, -0.18f, 0.40f, 0.20f, 18f, 8f, 0f);
                kl(c, t + 0.7f, Easing.SMOOTH, -0.18f, 0.40f, 0.20f, 18f, 8f, 0f);
                k(c.right, t + 1.8f, Easing.SMOOTH, -0.18f, 0.40f, 0.20f, 18f, 8f, 0f);
                kl(c, t + 1.8f, Easing.SMOOTH, -0.18f, 0.40f, 0.20f, 18f, 8f, 0f);
            }),
            new Template("pickup", 2.2f, R | CAM, BASIC, (c, t) -> {
                k(c.right, t + 0.5f, Easing.EASE_OUT, -0.15f, -0.35f, 0.55f, -12f, 0f, 0f);
                k(c.right, t + 0.8f, Easing.SMOOTH, -0.15f, -0.38f, 0.55f, -14f, 0f, 0f);
                k(c.right, t + 1.5f, Easing.EASE_OUT, -0.40f, 0.45f, 0.15f, 30f, 20f, -15f);
                k(c.camera, t + 0.6f, Easing.SMOOTH, 0, 0, 0, -6f, 0, 0, 0f);
                ev(c, t + 0.75f, EventType.USE, "");
            }),
            new Template("drink", 2.6f, R | CAM, BASIC, (c, t) -> {
                k(c.right, t + 0.7f, Easing.EASE_OUT, -0.40f, 0.55f, 0.05f, 40f, 25f, -20f);
                k(c.right, t + 1.9f, Easing.SMOOTH, -0.40f, 0.55f, 0.05f, 40f, 25f, -20f);
                k(c.camera, t + 1.1f, Easing.SMOOTH, 0, 0, 0, 5f, 0, 0, -2f);
            }),

            // ------------------------------------------------------------------ epic
            new Template("dramatic_reveal", 3.2f, R | L | CAM, EPIC, (c, t) -> {
                k(c.right, t + 0.4f, Easing.SMOOTH, 0.05f, -0.55f, -0.10f, -25f, 0f, 0f);
                kl(c, t + 0.4f, Easing.SMOOTH, 0.05f, -0.55f, -0.10f, -25f, 0f, 0f);
                k(c.right, t + 2.0f, Easing.SPLINE, -0.20f, 0.38f, 0.38f, 12f, 10f, -8f);
                kl(c, t + 2.0f, Easing.SPLINE, -0.20f, 0.38f, 0.38f, 12f, 10f, -8f);
                k(c.right, t + 2.7f, Easing.SMOOTH, -0.20f, 0.40f, 0.38f, 12f, 10f, -8f);
                kl(c, t + 2.7f, Easing.SMOOTH, -0.20f, 0.40f, 0.38f, 12f, 10f, -8f);
                k(c.camera, t + 0.4f, Easing.SMOOTH, 0, -0.05f, 0, -3f, 0, 0, 6f);
                k(c.camera, t + 2.0f, Easing.SMOOTH, 0, 0, 0.08f, 2f, 0, 0, -6f);
            }),
            new Template("hero_landing", 1.8f, R | L | CAM, EPIC, (c, t) -> {
                k(c.camera, t + 0.12f, Easing.EASE_OUT, 0, -0.45f, 0.05f, -10f, 0, 0, 8f);
                k(c.camera, t + 0.9f, Easing.SMOOTH, 0, -0.10f, 0, -2f, 0, 0, 0f);
                k(c.right, t + 0.12f, Easing.EASE_OUT, 0.12f, -0.25f, 0f, -15f, -6f, 10f);
                kl(c, t + 0.12f, Easing.EASE_OUT, 0.12f, -0.25f, 0f, -15f, -6f, 10f);
                k(c.right, t + 0.9f, Easing.SMOOTH, 0.05f, -0.05f, 0f, -3f, 0f, 3f);
                kl(c, t + 0.9f, Easing.SMOOTH, 0.05f, -0.05f, 0f, -3f, 0f, 3f);
                ev(c, t + 0.10f, EventType.IMPACT, "2.2");
                ev(c, t + 0.10f, EventType.SOUND, "minecraft:entity.generic.big_fall,1,0.9");
            }),
            new Template("sword_slash", 1.5f, R | CAM, EPIC, (c, t) -> {
                k(c.right, t + 0.35f, Easing.EASE_IN, 0.35f, 0.55f, -0.10f, 40f, -25f, 25f);
                k(c.right, t + 0.55f, Easing.EASE_OUT, -0.55f, -0.25f, 0.70f, -35f, 40f, -30f);
                k(c.right, t + 0.95f, Easing.SMOOTH, -0.55f, -0.25f, 0.70f, -35f, 40f, -30f);
                k(c.camera, t + 0.35f, Easing.EASE_IN, 0, 0, 0, 2f, -3f, -3f, -3f);
                k(c.camera, t + 0.55f, Easing.SMOOTH, 0, 0, 0.05f, -3f, 4f, 5f, 6f);
                ev(c, t + 0.48f, EventType.SOUND, "minecraft:entity.player.attack.sweep,1,0.9");
                ev(c, t + 0.55f, EventType.ATTACK, "");
                ev(c, t + 0.55f, EventType.IMPACT, "1.5");
            }),
            new Template("combo", 2.4f, R | L | CAM, EPIC, (c, t) -> {
                kl(c, t + 0.25f, Easing.EASE_IN, 0.16f, -0.12f, -0.20f, -12f, -6f, 8f);
                kl(c, t + 0.45f, Easing.EASE_OUT, -0.35f, 0.32f, 0.65f, 8f, 12f, -6f);
                kl(c, t + 0.7f, Easing.SMOOTH, -0.10f, 0.10f, 0.20f, 0f, 4f, 0f);
                k(c.right, t + 0.85f, Easing.EASE_IN, 0.16f, -0.12f, -0.20f, -12f, -6f, 8f);
                k(c.right, t + 1.0f, Easing.EASE_OUT, -0.35f, 0.32f, 0.65f, 8f, 12f, -6f);
                k(c.right, t + 1.3f, Easing.SMOOTH, -0.10f, 0.10f, 0.20f, 0f, 4f, 0f);
                k(c.right, t + 1.5f, Easing.EASE_IN, 0f, -0.40f, -0.10f, -20f, 0f, 0f);
                kl(c, t + 1.5f, Easing.EASE_IN, 0f, -0.40f, -0.10f, -20f, 0f, 0f);
                k(c.right, t + 1.65f, Easing.EASE_OUT, -0.15f, 0.60f, 0.55f, 35f, 6f, 0f);
                kl(c, t + 1.65f, Easing.EASE_OUT, -0.15f, 0.60f, 0.55f, 35f, 6f, 0f);
                k(c.right, t + 2.0f, Easing.SMOOTH, -0.15f, 0.60f, 0.55f, 35f, 6f, 0f);
                kl(c, t + 2.0f, Easing.SMOOTH, -0.15f, 0.60f, 0.55f, 35f, 6f, 0f);
                k(c.camera, t + 0.45f, Easing.SMOOTH, 0, 0, 0.05f, 0, 0, -3f, 2f);
                k(c.camera, t + 1.0f, Easing.SMOOTH, 0, 0, 0.05f, 0, 0, 3f, 2f);
                k(c.camera, t + 1.65f, Easing.SMOOTH, 0, 0.04f, 0.10f, 5f, 0, 0, 7f);
                ev(c, t + 0.45f, EventType.ATTACK, "");
                ev(c, t + 1.0f, EventType.ATTACK, "");
                ev(c, t + 1.65f, EventType.ATTACK, "");
                ev(c, t + 0.45f, EventType.IMPACT, "0.9");
                ev(c, t + 1.0f, EventType.IMPACT, "1.1");
                ev(c, t + 1.65f, EventType.IMPACT, "2.0");
                ev(c, t + 0.38f, EventType.SOUND, "minecraft:entity.player.attack.sweep,0.8,1.2");
                ev(c, t + 0.93f, EventType.SOUND, "minecraft:entity.player.attack.sweep,0.8,1.0");
                ev(c, t + 1.58f, EventType.SOUND, "minecraft:entity.player.attack.strong,1,0.9");
            }),
            new Template("charge", 3.0f, R | L | CAM, EPIC, (c, t) -> {
                k(c.right, t + 0.8f, Easing.SMOOTH, -0.10f, -0.15f, -0.10f, -10f, 15f, -10f);
                kl(c, t + 0.8f, Easing.SMOOTH, -0.10f, -0.15f, -0.10f, -10f, 15f, -10f);
                for (int i = 1; i <= 12; i++) {
                    float s = i % 2 == 0 ? 1f : -1f;
                    k(c.right, t + 0.8f + i * 0.1f, Easing.SMOOTH, -0.10f + 0.02f * s, -0.15f - 0.015f * s, -0.10f, -10f, 15f, -10f + 2f * s);
                    kl(c, t + 0.8f + i * 0.1f, Easing.SMOOTH, -0.10f - 0.02f * s, -0.15f + 0.015f * s, -0.10f, -10f, 15f, -10f - 2f * s);
                }
                k(c.camera, t + 2.0f, Easing.EASE_IN, 0, 0, -0.04f, 1f, 0, 0, -10f);
                k(c.right, t + 2.2f, Easing.EASE_OUT, -0.12f, 0.25f, 0.90f, 5f, 0f, 0f);
                kl(c, t + 2.2f, Easing.EASE_OUT, -0.12f, 0.25f, 0.90f, 5f, 0f, 0f);
                k(c.right, t + 2.7f, Easing.SMOOTH, -0.12f, 0.25f, 0.90f, 5f, 0f, 0f);
                kl(c, t + 2.7f, Easing.SMOOTH, -0.12f, 0.25f, 0.90f, 5f, 0f, 0f);
                k(c.camera, t + 2.3f, Easing.SMOOTH, 0, 0, 0.12f, 0, 0, 0, 14f);
                ev(c, t + 2.2f, EventType.IMPACT, "2.5");
                ev(c, t + 2.2f, EventType.SOUND, "minecraft:block.beacon.activate,1,1.3");
            }),
            new Template("shield", 1.6f, R | L | CAM, EPIC, (c, t) -> {
                k(c.right, t + 0.3f, Easing.EASE_OUT, -0.35f, 0.35f, 0.45f, 5f, 35f, 15f);
                kl(c, t + 0.3f, Easing.EASE_OUT, -0.35f, 0.35f, 0.45f, 5f, 35f, 15f);
                k(c.right, t + 1.2f, Easing.SMOOTH, -0.35f, 0.35f, 0.45f, 5f, 35f, 15f);
                kl(c, t + 1.2f, Easing.SMOOTH, -0.35f, 0.35f, 0.45f, 5f, 35f, 15f);
                k(c.camera, t + 0.7f, Easing.SMOOTH, 0, 0, -0.04f, -2f, 0, 0, 0f);
                ev(c, t + 0.7f, EventType.IMPACT, "1.4");
            }),
            new Template("stumble", 1.8f, R | L | CAM, EPIC, (c, t) -> {
                k(c.camera, t + 0.15f, Easing.EASE_OUT, 0.05f, -0.08f, 0.05f, -6f, 4f, 8f, 4f);
                k(c.camera, t + 0.5f, Easing.SMOOTH, -0.05f, -0.03f, 0f, 2f, -3f, -6f, 0f);
                k(c.camera, t + 0.9f, Easing.SMOOTH, 0.02f, 0f, 0f, -1f, 1f, 3f, 0f);
                k(c.right, t + 0.2f, Easing.SMOOTH, 0.15f, -0.10f, -0.10f, 15f, -10f, 15f);
                k(c.right, t + 0.6f, Easing.SMOOTH, -0.10f, 0.15f, 0.20f, -10f, 10f, -15f);
                kl(c, t + 0.2f, Easing.SMOOTH, 0.15f, -0.10f, -0.10f, -10f, -10f, 15f);
                kl(c, t + 0.6f, Easing.SMOOTH, -0.10f, 0.15f, 0.20f, 15f, 10f, -15f);
                ev(c, t + 0.1f, EventType.TURN, "8,3", 0.6f);
                ev(c, t + 0.15f, EventType.IMPACT, "0.9");
            }),
            new Template("whip_turn", 1.2f, R | L | CAM, EPIC, (c, t) -> {
                k(c.camera, t + 0.25f, Easing.EASE_OUT, 0, 0, 0, 0, 0, 10f, 10f);
                k(c.camera, t + 0.6f, Easing.SMOOTH, 0, 0, 0, 0, 0, 0, 0f);
                k(c.right, t + 0.25f, Easing.SMOOTH, -0.20f, -0.10f, -0.10f, -5f, -20f, 10f);
                kl(c, t + 0.25f, Easing.SMOOTH, -0.20f, -0.10f, -0.10f, -5f, -20f, 10f);
                ev(c, t + 0.1f, EventType.TURN, "180,0", 0.45f);
                ev(c, t + 0.1f, EventType.SOUND, "minecraft:entity.player.attack.sweep,1,0.7");
                ev(c, t + 0.5f, EventType.IMPACT, "1.2");
            }),
            new Template("dolly_zoom", 2.4f, CAM, EPIC, (c, t) -> {
                k(c.camera, t + 2.0f, Easing.SMOOTH, 0, 0, 0.9f, 0, 0, 0, 26f);
                k(c.camera, t + 2.2f, Easing.SMOOTH, 0, 0, 0.9f, 0, 0, 0, 26f);
            }),

            // ------------------------------------------------------------------ movement (the player really moves)
            new Template("sprint", 3.0f, R | L | CAM, MOVE, (c, t) -> {
                for (int i = 0; i < 9; i++) {
                    float s = i % 2 == 0 ? 1f : -1f;
                    float tt = t + 0.15f + i * 0.3f;
                    k(c.right, tt, Easing.SPLINE, 0f, -0.05f * s, 0.12f * s, -14f * s, 0f, 0f);
                    kl(c, tt, Easing.SPLINE, 0f, 0.05f * s, -0.12f * s, 14f * s, 0f, 0f);
                    k(c.camera, tt, Easing.SPLINE, 0, 0.03f * s, 0, 0, 0, 1.2f * s, 8f);
                }
                ev(c, t, EventType.WALK, "forward,1", 3.0f);
                ev(c, t, EventType.SPRINT, "", 3.0f);
            }),
            new Template("dash", 1.0f, R | L | CAM, MOVE, (c, t) -> {
                k(c.camera, t + 0.1f, Easing.EASE_OUT, 0, 0, 0.05f, 0, 0, 3f, 14f);
                k(c.camera, t + 0.6f, Easing.SMOOTH, 0, 0, 0, 0, 0, 0, 0f);
                k(c.right, t + 0.15f, Easing.EASE_OUT, 0.10f, -0.10f, -0.30f, 15f, 0f, 0f);
                kl(c, t + 0.15f, Easing.EASE_OUT, 0.10f, -0.10f, -0.30f, 15f, 0f, 0f);
                ev(c, t, EventType.WALK, "forward,1", 0.4f);
                ev(c, t, EventType.SPRINT, "", 0.5f);
                ev(c, t + 0.05f, EventType.IMPACT, "0.7");
                ev(c, t + 0.05f, EventType.SOUND, "minecraft:entity.player.attack.sweep,0.9,0.6");
            }),
            new Template("slow_walk", 3.0f, R | L | CAM, MOVE, (c, t) -> {
                for (int i = 0; i < 6; i++) {
                    float s = i % 2 == 0 ? 1f : -1f;
                    float tt = t + 0.25f + i * 0.5f;
                    k(c.right, tt, Easing.SPLINE, 0f, 0.02f * s, 0f, 4f * s, 0f, 0f);
                    kl(c, tt, Easing.SPLINE, 0f, -0.02f * s, 0f, -4f * s, 0f, 0f);
                    k(c.camera, tt, Easing.SPLINE, 0, 0.015f * s, 0, 0, 0, 0.6f * s, 0f);
                }
                ev(c, t, EventType.WALK, "forward,0.5", 3.0f);
            }),
            new Template("door_walk", 4.0f, R | CAM, MOVE, (c, t) -> {
                k(c.right, t + 0.9f, Easing.SMOOTH, -0.28f, 0.30f, 0.70f, 10f, 6f, 0f);
                k(c.right, t + 1.6f, Easing.SMOOTH, -0.28f, 0.30f, 0.70f, 10f, 6f, 0f);
                k(c.right, t + 2.1f, Easing.SMOOTH, 0f, 0f, 0f, 0f, 0f, 0f);
                ev(c, t, EventType.WALK, "forward,0.6", 1.2f);
                ev(c, t + 1.25f, EventType.USE, "");
                ev(c, t + 2.0f, EventType.WALK, "forward,0.6", 1.6f);
            }),
            new Template("ledge_jump", 2.4f, R | L | CAM, MOVE, (c, t) -> {
                k(c.right, t + 0.4f, Easing.EASE_OUT, -0.10f, 0.50f, 0.60f, 40f, 0f, 0f);
                kl(c, t + 0.8f, Easing.EASE_OUT, -0.10f, 0.50f, 0.60f, 40f, 0f, 0f);
                k(c.right, t + 1.2f, Easing.SMOOTH, 0.05f, 0.05f, 0.20f, 10f, 0f, 0f);
                k(c.camera, t + 0.4f, Easing.SMOOTH, 0, 0.05f, 0, 6f, 0, 0, 3f);
                ev(c, t + 0.1f, EventType.JUMP, "");
                ev(c, t + 0.3f, EventType.WALK, "forward,0.7", 1.2f);
            }),
            new Template("look_around", 4.2f, R | L | CAM, MOVE, (c, t) -> {
                k(c.camera, t + 1.0f, Easing.SMOOTH, 0, 0, 0, 0, 0, -2f, 0f);
                k(c.camera, t + 2.2f, Easing.SMOOTH, 0, 0, 0, 0, 0, 2f, 0f);
                k(c.right, t + 1.0f, Easing.SMOOTH, 0.04f, -0.05f, 0f, -3f, 6f, 0f);
                kl(c, t + 1.0f, Easing.SMOOTH, 0.04f, -0.05f, 0f, -3f, 6f, 0f);
                ev(c, t + 0.2f, EventType.TURN, "-50,3", 1.0f);
                ev(c, t + 1.4f, EventType.TURN, "100,-4", 1.6f);
                ev(c, t + 3.2f, EventType.TURN, "-50,1", 0.7f);
            })
    );

    /** Insert {@code tp} at time {@code t0}. Returns the time at which it ends. */
    public static float apply(Clip c, Template tp, float t0, boolean returnToRest) {
        float end = t0 + tp.duration();
        float[] tmp = new float[Clip.CAM_CH];
        for (int i = 0; i < 3; i++) {
            if ((tp.mask() & (1 << i)) == 0) continue;
            Track tr = c.track(i);
            // anchor at the current pose, then clear whatever the template is about to replace
            tr.sample(t0, tmp);
            Keyframe a = new Keyframe(tr.channels);
            System.arraycopy(tmp, 0, a.v, 0, tr.channels);
            a.time = t0;
            a.easing = Easing.SMOOTH;
            tr.keys.removeIf(k -> k.time > t0 + 0.001f && k.time <= end + 0.001f);
            tr.put(a);
        }
        tp.builder().build(c, t0);
        if (returnToRest) {
            for (int i = 0; i < 3; i++) {
                if ((tp.mask() & (1 << i)) == 0) continue;
                Track tr = c.track(i);
                Keyframe z = new Keyframe(tr.channels);
                z.time = end;
                z.easing = Easing.SMOOTH;
                tr.put(z);
            }
        }
        for (int i = 0; i < 3; i++) c.track(i).sort();
        c.sortEvents();
        if (c.duration < end + 0.3f) c.duration = end + 0.3f;
        return end;
    }

    /** Remove keys that lie (almost) on the straight line between their neighbours. Used after live recording. */
    public static void simplify(Track tr, float posTol, float rotTol) {
        if (tr.keys.size() < 3) return;
        tr.sort();
        List<Keyframe> src = new java.util.ArrayList<>(tr.keys);
        List<Keyframe> out = new java.util.ArrayList<>();
        out.add(src.get(0));
        Keyframe last = src.get(0);
        for (int i = 1; i < src.size() - 1; i++) {
            Keyframe k = src.get(i), nx = src.get(i + 1);
            if (!k.hasAll() || !nx.hasAll() || !last.hasAll()) { out.add(k); last = k; continue; }
            float f = (k.time - last.time) / Math.max(1e-4f, nx.time - last.time);
            boolean close = true;
            for (int ch = 0; ch < tr.channels; ch++) {
                boolean pos = ch < 3;
                float tol = pos ? posTol : (tr.channels == 7 && ch == 6 ? rotTol : rotTol);
                float pred = last.v[ch] + (nx.v[ch] - last.v[ch]) * f;
                if (Math.abs(k.v[ch] - pred) > tol) { close = false; break; }
            }
            if (!close) { out.add(k); last = k; }
        }
        out.add(src.get(src.size() - 1));
        tr.keys.clear();
        tr.keys.addAll(out);
        for (Keyframe k : tr.keys) k.easing = Easing.SPLINE;
    }
}
