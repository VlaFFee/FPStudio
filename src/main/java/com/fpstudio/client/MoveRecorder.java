package com.fpstudio.client;

import com.fpstudio.FPStudio;
import com.fpstudio.client.gui.StudioScreen;
import com.fpstudio.model.CamSmooth;
import com.fpstudio.model.Clip;
import com.fpstudio.model.ClipIO;
import com.fpstudio.model.EventKey;
import com.fpstudio.model.EventType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import com.fpstudio.model.Easing;
import com.fpstudio.model.Keyframe;
import com.fpstudio.model.Templates;
import com.fpstudio.model.Track;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Records what the player really does in the world (walking, strafing, jumping, sneaking, sprinting and where they look)
 * while the clip plays, and turns it into timeline events (WALK / JUMP / SNEAK / SPRINT / TURN) that replay it exactly.
 * The hands and camera tracks keep playing during the take, so the movement is timed against them.
 */
@Mod.EventBusSubscriber(modid = FPStudio.MODID, value = Dist.CLIENT)
public final class MoveRecorder {
    public enum State { IDLE, COUNTDOWN, RECORDING }

    public static State state = State.IDLE;
    /** Clip JSON from before the take; the studio puts it on its undo stack so the take can be undone. */
    public static String pendingUndo;
    /** Set after a camera take so the studio opens on the Clip tab, where the smoothing is. */
    public static boolean justRecorded;

    private static final int COUNT_TICKS = 60;
    private static int ticks;
    private static float t0, origDuration;
    private static boolean origLoop;
    private static final List<float[]> samples = new ArrayList<>();   // time, flag bits, yaw, pitch

    /** true: the walk becomes keys of the camera track (editable like any camera); false: events that move the real player. */
    public static boolean cameraMode = true;
    private static boolean recCamera;
    private static final List<float[]> frames = new ArrayList<>();    // time, eye x y z, yaw, pitch (per rendered frame)

    private MoveRecorder() {}

    public static boolean busy() { return state != State.IDLE; }

    /** Start a take at the given clip time: 3 second countdown, then the clip plays and the player is recorded. */
    public static void begin(float from) {
        Playback.pause();
        Playback.stage = true;
        pendingUndo = ClipIO.toJson(Playback.clip);
        t0 = from;
        samples.clear();
        frames.clear();
        recCamera = cameraMode;
        ticks = 0;
        state = State.COUNTDOWN;
        Playback.time = from;
    }

    private static void startRecording() {
        Clip c = Playback.clip;
        origDuration = c.duration;
        origLoop = c.loop;
        c.loop = false;
        Playback.camMute = recCamera;
        Playback.play(t0, false, false);
        t0 = Playback.time;
        state = State.RECORDING;
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END || state == State.IDLE) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) { abort(); return; }
        if (state == State.COUNTDOWN) {
            if (++ticks >= COUNT_TICKS) startRecording();
            return;
        }
        if (!Playback.playing) { finish(); return; }
        Clip c = Playback.clip;
        if (Playback.time > c.duration - 1f) c.duration = Math.min(600f, c.duration + 10f);
        Input in = p.input;
        int f = (in.up ? 1 : 0) | (in.down ? 2 : 0) | (in.left ? 4 : 0) | (in.right ? 8 : 0)
                | (in.jumping ? 16 : 0) | (in.shiftKeyDown ? 32 : 0) | (p.isSprinting() ? 64 : 0);
        samples.add(new float[]{Playback.time, f, p.getYRot(), p.getXRot()});
    }

    @SubscribeEvent
    public static void onRender(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.END || state != State.RECORDING || !recCamera || !Playback.playing) return;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        float t = Playback.time;
        if (!frames.isEmpty() && t - frames.get(frames.size() - 1)[0] < 0.03f) return;
        Vec3 eye = p.getEyePosition(e.renderTickTime);
        frames.add(new float[]{t, (float) eye.x, (float) eye.y, (float) eye.z, p.getViewYRot(e.renderTickTime), p.getViewXRot(e.renderTickTime)});
    }

    /**
     * Camera take -> dense path. Positions are stored in the frame of the anchor view (right / up / forward), angles as the
     * turn since the anchor, which is exactly what the camera track means when it is anchored in the world.
     */
    static Track rawTrack(Clip.Anchor an) {
        Track tr = new Track(Clip.CAM_CH);
        Vec3 fwd = Vec3.directionFromRotation(an.pitch, an.yaw);
        Vec3 up = Vec3.directionFromRotation(an.pitch - 90f, an.yaw);
        Vec3 right = fwd.cross(up);
        float cumYaw = 0f, prevYaw = an.yaw;
        for (float[] f : frames) {
            cumYaw += Mth.wrapDegrees(f[4] - prevYaw);
            prevYaw = f[4];
            Vec3 d = new Vec3(f[1] - an.x, f[2] - an.y, f[3] - an.z);
            Keyframe k = new Keyframe(Clip.CAM_CH);
            k.time = Math.round(f[0] * 100f) / 100f;
            k.v[0] = (float) d.dot(right);
            k.v[1] = (float) d.dot(up);
            k.v[2] = (float) d.dot(fwd);
            k.v[3] = -(f[5] - an.pitch);                          // +pitch = look up
            k.v[4] = -cumYaw;                                      // +yaw = look left
            k.easing = Easing.LINEAR;
            tr.put(k);
        }
        return tr;
    }

    /** Throw the take away and go back to the studio. */
    public static void abort() {
        boolean wasRec = state == State.RECORDING;
        state = State.IDLE;
        Playback.camMute = false;
        if (wasRec) {
            Playback.pause();
            Playback.clip.loop = origLoop;
            Playback.clip.duration = origDuration;
        }
        Playback.stage = false;
        pendingUndo = null;
        samples.clear();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.setScreen(new StudioScreen());
    }

    /** End the take: convert it to events and reopen the studio. */
    public static void finish() {
        if (state == State.COUNTDOWN) { abort(); return; }
        if (state != State.RECORDING) return;
        state = State.IDLE;
        Playback.camMute = false;
        Playback.pause();
        Playback.stage = false;
        Clip c = Playback.clip;
        c.loop = origLoop;
        int n = 0;
        if (recCamera && frames.size() >= 2) {
            float[] f0 = frames.get(0);
            float tEnd = frames.get(frames.size() - 1)[0];
            Clip.Anchor an = c.camAnchor;
            if (!an.on || t0 <= 0.001f) {
                // new anchor: the spot and view where the take started. Old keys belonged to another frame, so they go.
                an.on = true;
                an.x = f0[1]; an.y = f0[2]; an.z = f0[3];
                an.yaw = f0[4]; an.pitch = f0[5];
                c.camera.keys.clear();
            }
            Track raw = rawTrack(an);
            c.camRaw = raw;
            CamSmooth.rebuildFromRaw(c.camera, raw, c.camSmooth, c.camDetail);
            n = c.camera.keys.size();
            c.duration = Math.max(origDuration, Math.min(600f, tEnd + 0.05f));
            justRecorded = true;
        } else if (!recCamera && samples.size() >= 2) {
            float tEnd = samples.get(samples.size() - 1)[0] + 0.05f;
            c.events.removeIf(ev -> ev.time >= t0 - 1e-4f && ev.time < tEnd
                    && (ev.type == EventType.WALK || ev.type == EventType.TURN || ev.type == EventType.JUMP
                    || ev.type == EventType.SNEAK || ev.type == EventType.SPRINT));
            List<EventKey> out = convert(tEnd);
            c.events.addAll(out);
            n = out.size();
            c.duration = Math.max(origDuration, Math.min(600f, tEnd));
        } else {
            c.duration = origDuration;
        }
        c.sortEvents();
        samples.clear();
        frames.clear();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatable("fpstudio.msg.rec_done", n), true);
            Playback.time = Mth.clamp(t0, 0f, c.duration);
            mc.setScreen(new StudioScreen());
        }
    }

    // ------------------------------------------------------------------ conversion (pure maths)

    private static final String[] DIRS = {"forward", "back", "left", "right"};

    static List<EventKey> convert(float tEnd) {
        List<EventKey> out = new ArrayList<>();
        int n = samples.size();
        // held keys -> events
        for (int bit = 0; bit < 7; bit++) {
            if (bit == 4) continue;
            float start = 0f;
            boolean on = false;
            for (int i = 0; i <= n; i++) {
                boolean set = i < n && (((int) samples.get(i)[1]) >> bit & 1) != 0;
                float ti = i < n ? samples.get(i)[0] : tEnd;
                if (set && !on) { on = true; start = ti; }
                else if (!set && on) {
                    on = false;
                    float dur = Math.max(0.05f, ti - start);
                    if (bit < 4) out.add(new EventKey(start, EventType.WALK, DIRS[bit], dur));
                    else out.add(new EventKey(start, bit == 5 ? EventType.SNEAK : EventType.SPRINT, "", dur));
                }
            }
        }
        // jumps = key press edges
        boolean prev = false;
        for (float[] s : samples) {
            boolean j = (((int) s[1]) & 16) != 0;
            if (j && !prev) out.add(new EventKey(s[0], EventType.JUMP, ""));
            prev = j;
        }
        // looking: cumulative yaw / pitch, cut into 0.2 s windows, similar neighbours merged into one linear turn
        float[] ts = new float[n], cy = new float[n], cp = new float[n];
        for (int i = 0; i < n; i++) {
            float[] s = samples.get(i);
            ts[i] = s[0];
            if (i == 0) continue;
            float[] q = samples.get(i - 1);
            cy[i] = cy[i - 1] + Mth.wrapDegrees(s[2] - q[2]);
            cp[i] = cp[i - 1] + (s[3] - q[3]);
        }
        List<float[]> win = new ArrayList<>();   // start, dur, dyaw, dpitch
        final float W = 0.2f;
        for (float w = ts[0]; w < tEnd; w += W) {
            float e = Math.min(w + W, tEnd);
            float dy = at(ts, cy, e) - at(ts, cy, w), dp = at(ts, cp, e) - at(ts, cp, w);
            if (Math.abs(dy) + Math.abs(dp) < 0.35f) continue;
            float[] cur = {w, e - w, dy, dp};
            float[] last = win.isEmpty() ? null : win.get(win.size() - 1);
            if (last != null && Math.abs(last[0] + last[1] - w) < 1e-3f && similar(last, cur)) {
                last[1] += cur[1];
                last[2] += cur[2];
                last[3] += cur[3];
            } else {
                win.add(cur);
            }
        }
        for (float[] w : win) {
            out.add(new EventKey(w[0], EventType.TURN, String.format(Locale.ROOT, "%.2f,%.2f,lin", w[2], w[3]), w[1]));
        }
        return out;
    }

    private static boolean similar(float[] a, float[] b) {
        return rateOk(a[2] / a[1], b[2] / b[1]) && rateOk(a[3] / a[1], b[3] / b[1]);
    }

    private static boolean rateOk(float r1, float r2) {
        return Math.abs(r1 - r2) <= 0.4f * Math.max(Math.abs(r1), Math.abs(r2)) + 15f;
    }

    private static float at(float[] ts, float[] v, float t) {
        int n = ts.length;
        if (t <= ts[0]) return v[0];
        if (t >= ts[n - 1]) return v[n - 1];
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (ts[mid] <= t) lo = mid; else hi = mid;
        }
        float u = (t - ts[lo]) / Math.max(1e-6f, ts[hi] - ts[lo]);
        return v[lo] + (v[hi] - v[lo]) * u;
    }

    // ------------------------------------------------------------------ HUD

    public static void draw(GuiGraphics g, int w, int h) {
        if (state == State.IDLE) return;
        Minecraft mc = Minecraft.getInstance();
        if (state == State.COUNTDOWN) {
            int left = Math.max(1, 3 - ticks / 20);
            String s = String.valueOf(left);
            g.pose().pushPose();
            g.pose().translate(w / 2f, h / 2f - 20f, 0f);
            g.pose().scale(5f, 5f, 1f);
            g.drawString(mc.font, s, -mc.font.width(s) / 2, -4, 0xFFFFFFFF, true);
            g.pose().popPose();
            String hint = I18n.get("fpstudio.msg.rec_count");
            g.drawString(mc.font, hint, (w - mc.font.width(hint)) / 2, h / 2 + 16, 0xFFFFFFFF, true);
            return;
        }
        float pulse = 0.5f + 0.5f * (float) Math.sin(System.nanoTime() / 1.8e8);
        int col = ((int) (120 + 135 * pulse) << 24) | 0xFF2D3D;
        g.fill(8, 8, 16, 16, col);
        String t = String.format(Locale.ROOT, "REC %.1fs", Playback.time - t0);
        g.drawString(mc.font, t, 20, 8, 0xFFFFFFFF, true);
        String hint = I18n.get("fpstudio.msg.rec_hint");
        g.drawString(mc.font, hint, (w - mc.font.width(hint)) / 2, h - 60, 0xFFFFFFFF, true);
    }
}
