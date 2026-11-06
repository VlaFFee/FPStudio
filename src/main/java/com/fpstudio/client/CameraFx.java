package com.fpstudio.client;

import com.fpstudio.FPStudio;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Camera track. Angles/FOV use Forge viewport events. Position offset has no public hook, so for the duration of one
 * frame's render we shift the local player's (interpolated) position and restore it afterwards. Purely visual.
 */
@Mod.EventBusSubscriber(modid = FPStudio.MODID, value = Dist.CLIENT)
public final class CameraFx {
    private static boolean shifted;
    private static boolean anchoredNow;
    private static float camYaw, camPitch, camRoll, camFov;
    private static double sx, sy, sz, sxo, syo, szo;

    private CameraFx() {}

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent e) {
        Minecraft mc = Minecraft.getInstance();
        if (e.phase == TickEvent.Phase.START) {
            Playback.frame();
            LocalPlayer p = mc.player;
            anchoredNow = false;
            if (p == null || !Playback.active()) return;

            float[] c = Playback.cur.c;
            Vec3 off;
            if (Playback.anchored()) {
                // The camera path lives in the world, at the spot where it was recorded. Fly there from the player's own view.
                var an = Playback.clip.camAnchor;
                Vec3 fwd0 = Vec3.directionFromRotation(an.pitch, an.yaw);
                Vec3 up0 = Vec3.directionFromRotation(an.pitch - 90f, an.yaw);
                Vec3 right0 = fwd0.cross(up0);
                Vec3 want = new Vec3(an.x, an.y, an.z).add(right0.scale(c[0])).add(up0.scale(c[1])).add(fwd0.scale(c[2]));
                float w = Playback.anchorW();
                Vec3 real = p.getEyePosition(e.renderTickTime);
                float ry = p.getViewYRot(e.renderTickTime), rp = p.getViewXRot(e.renderTickTime);
                off = want.subtract(real).scale(w);
                camYaw = ry + net.minecraft.util.Mth.wrapDegrees((an.yaw - c[4]) - ry) * w;
                camPitch = rp + ((an.pitch - c[3]) - rp) * w;
                camRoll = c[5] * w;
                camFov = c[6] * w;
                anchoredNow = true;
            } else {
                if (!Playback.cur.cameraMoved()) return;
                Vec3 fwd = Vec3.directionFromRotation(p.getViewXRot(e.renderTickTime), p.getViewYRot(e.renderTickTime));
                Vec3 up = Vec3.directionFromRotation(p.getViewXRot(e.renderTickTime) - 90f, p.getViewYRot(e.renderTickTime));
                Vec3 right = fwd.cross(up);
                off = right.scale(c[0]).add(up.scale(c[1])).add(fwd.scale(c[2]));
            }

            sx = p.getX(); sy = p.getY(); sz = p.getZ();
            sxo = p.xo; syo = p.yo; szo = p.zo;
            shifted = true;
            p.setPosRaw(sx + off.x, sy + off.y, sz + off.z);
            p.xo = sxo + off.x;
            p.yo = syo + off.y;
            p.zo = szo + off.z;
        } else if (shifted) {
            LocalPlayer p = mc.player;
            if (p != null) {
                p.setPosRaw(sx, sy, sz);
                p.xo = sxo; p.yo = syo; p.zo = szo;
            }
            shifted = false;
        }
    }

    @SubscribeEvent
    public static void onAngles(ViewportEvent.ComputeCameraAngles e) {
        if (!Playback.active()) return;
        if (anchoredNow) {
            e.setYaw(camYaw);
            e.setPitch(camPitch);
            e.setRoll(e.getRoll() + camRoll);
            return;
        }
        float[] c = Playback.cur.c;
        e.setPitch(e.getPitch() - c[3]);   // +pitch = look up
        e.setYaw(e.getYaw() - c[4]);       // +yaw = look left
        e.setRoll(e.getRoll() + c[5]);
        float w = com.fpstudio.client.npc.NpcRuntime.lookW;
        if (w > 0.001f) {
            // CAM_LOOK: turn the view towards the NPC (yaw 0 = south, +yaw = clockwise seen from above)
            Vec3 cam = e.getCamera().getPosition();
            float[] t = com.fpstudio.client.npc.NpcRuntime.lookPos;
            double dx = t[0] - cam.x, dy = t[1] - cam.y, dz = t[2] - cam.z;
            float ty = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float tp = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            float dyaw = net.minecraft.util.Mth.wrapDegrees(ty - e.getYaw());
            e.setYaw(e.getYaw() + dyaw * w);
            e.setPitch(e.getPitch() + (tp - e.getPitch()) * w);
        }
    }

    @SubscribeEvent
    public static void onFov(ViewportEvent.ComputeFov e) {
        if (!e.usedConfiguredFov()) return; // leave the hand projection alone
        double d = 0;
        if (Playback.active()) d += anchoredNow ? camFov : Playback.cur.c[6];
        d -= 3.5 * StudioFx.easeOut(StudioFx.studio);
        if (d != 0) e.setFOV(Math.max(10.0, e.getFOV() + d));
    }

    @SubscribeEvent
    public static void onMove(MovementInputUpdateEvent e) {
        var in = e.getInput();
        if (Playback.playing && Playback.fullscreen && (Playback.clip.lockMovement || InputLock.active())) {
            in.up = in.down = in.left = in.right = false;
            in.forwardImpulse = 0f;
            in.leftImpulse = 0f;
            in.jumping = false;
            in.shiftKeyDown = false;
        }
        Motion.apply(in); // scripted movement from the timeline (after the lock, so it still works)
    }
}
