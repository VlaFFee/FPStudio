package com.fpstudio.client;

import com.fpstudio.Config;
import net.minecraft.util.Mth;

/** Smoothed "cinematic ambience" values driven every frame: vignette presence, letterbox bars, studio FOV push-in. */
public final class StudioFx {
    public static float intro;   // 0..1, anything cinematic is active (editing or playing)
    public static float studio;  // 0..1, studio UI is open (FOV push-in)
    public static float bars;    // 0..1, letterbox bars

    private StudioFx() {}

    private static float chase(float cur, float target, float dt, float rate) {
        float n = cur + (target - cur) * (1f - (float) Math.exp(-dt * rate));
        return Math.abs(n - target) < 0.002f ? target : n;
    }

    public static void update(float dt) {
        boolean fx = Config.STUDIO_FX.get();
        intro = chase(intro, fx && Playback.active() ? 1f : 0f, dt, 4.5f);
        studio = chase(studio, fx && Playback.editing && !Playback.fullscreen ? 1f : 0f, dt, 4f);
        bars = chase(bars, Playback.active() && Playback.clip.letterbox ? 1f : 0f, dt, 3.5f);
    }

    public static float easeOut(float t) {
        t = Mth.clamp(t, 0f, 1f);
        float u = 1f - t;
        return 1f - u * u * u;
    }

    /** Ease-out with a small overshoot, for springy panel entrances. */
    public static float easeOutBack(float t) {
        t = Mth.clamp(t, 0f, 1f);
        float c1 = 1.20f, c3 = c1 + 1f, u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }
}
