package com.fpstudio.client;

import com.fpstudio.model.Easing;
import com.fpstudio.model.EventKey;
import com.fpstudio.model.EventType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

import java.util.Locale;

/** Real player movement driven by timeline events: walking, turning, jumping, sneaking and sprinting. */
public final class Motion {
    private static boolean sprintHeld;

    private Motion() {}

    private static float progress(EventKey e, float t) {
        if (e.dur <= 0.001f) return t >= e.time ? 1f : 0f;
        float u = Mth.clamp((t - e.time) / e.dur, 0f, 1f);
        if (e.type == EventType.TURN && e.arg != null && e.arg.endsWith(",lin")) return u;   // recorded turns: constant speed
        return Easing.SMOOTH.apply(u);
    }

    /** Rotate the player by the part of each TURN event that happens between two playback times. */
    public static void turn(float from, float to) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        for (EventKey e : Playback.clip.events) {
            if (e.type != EventType.TURN) continue;
            float dp = progress(e, to) - progress(e, from);
            if (dp == 0f) continue;
            String[] s = e.arg.split(",");
            float yaw = s.length > 0 ? Playback.parse(s[0], 0f) : 0f;
            float pitch = s.length > 1 ? Playback.parse(s[1], 0f) : 0f;
            float dy = yaw * dp;
            p.setYRot(p.getYRot() + dy);
            p.yRotO += dy;
            float nx = Mth.clamp(p.getXRot() + pitch * dp, -90f, 90f);
            float real = nx - p.getXRot();
            p.setXRot(nx);
            p.xRotO += real;
        }
    }

    /** Called from the movement input hook every client tick. */
    public static void apply(Input in) {
        Minecraft mc = Minecraft.getInstance();
        boolean wantSprint = false;
        if (Playback.playing && Playback.runEvents) {
            float t = Playback.time;
            float fwd = 0f, side = 0f;
            boolean sneak = false, jump = false;
            for (EventKey e : Playback.clip.events) {
                boolean act = t >= e.time && t < e.time + Math.max(0.001f, e.dur);
                switch (e.type) {
                    case WALK -> {
                        if (!act) break;
                        String[] s = e.arg.split(",");
                        String dir = s.length > 0 ? s[0].trim().toLowerCase(Locale.ROOT) : "forward";
                        float sp = s.length > 1 ? Mth.clamp(Playback.parse(s[1], 1f), 0f, 1f) : 1f;
                        switch (dir) {
                            case "back" -> fwd -= sp;
                            case "left" -> side += sp;
                            case "right" -> side -= sp;
                            default -> fwd += sp;
                        }
                    }
                    case SNEAK -> sneak |= act;
                    case SPRINT -> wantSprint |= act;
                    case JUMP -> jump |= t >= e.time && t < e.time + 0.12f;
                    default -> { }
                }
            }
            if (fwd != 0f || side != 0f) {
                in.forwardImpulse = Mth.clamp(in.forwardImpulse + fwd, -1f, 1f);
                in.leftImpulse = Mth.clamp(in.leftImpulse + side, -1f, 1f);
                in.up = in.forwardImpulse > 0f;
                in.down = in.forwardImpulse < 0f;
                in.left = in.leftImpulse > 0f;
                in.right = in.leftImpulse < 0f;
            }
            if (sneak) {
                in.shiftKeyDown = true;
                in.forwardImpulse *= 0.3f;
                in.leftImpulse *= 0.3f;
            }
            if (jump) in.jumping = true;
        }
        if (wantSprint) mc.options.keySprint.setDown(true); // re-assert every tick: a locked input clears key states
        else if (sprintHeld) mc.options.keySprint.setDown(false);
        sprintHeld = wantSprint;
    }

    public static void reset() {
        if (sprintHeld) {
            Minecraft.getInstance().options.keySprint.setDown(false);
            sprintHeld = false;
        }
    }
}
