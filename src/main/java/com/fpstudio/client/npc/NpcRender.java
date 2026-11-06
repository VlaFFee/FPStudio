package com.fpstudio.client.npc;

import com.fpstudio.FPStudio;
import com.fpstudio.client.Playback;
import com.fpstudio.npc.GeoModel;
import com.fpstudio.npc.NpcInstance;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Draws the cutscene NPCs. Bones are pre-baked quads, so a frame is just a matrix multiply per vertex. */
@Mod.EventBusSubscriber(modid = FPStudio.MODID, value = Dist.CLIENT)
public final class NpcRender {
    private static final Matrix4f world = new Matrix4f(), bone = new Matrix4f();
    private static final Matrix3f nrm = new Matrix3f(), bone3 = new Matrix3f();
    private static final Vector3f v = new Vector3f(), n = new Vector3f();

    private NpcRender() {}

    @SubscribeEvent
    public static void onStage(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || !Playback.active()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        var all = NpcRuntime.all();
        if (all.isEmpty()) return;

        PoseStack ps = e.getPoseStack();
        Vec3 cam = e.getCamera().getPosition();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        for (NpcInstance inst : all) {
            if (!inst.visible) continue;
            ResourceLocation tex = NpcLibrary.texture(inst.asset);
            if (tex == null) continue;
            RenderType rt = RenderType.entityCutoutNoCull(tex);
            VertexConsumer vc = buf.getBuffer(rt);
            int light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(inst.wx, inst.wy + 1.0, inst.wz));
            ps.pushPose();
            ps.translate(inst.wx - cam.x, inst.wy - cam.y, inst.wz - cam.z);
            ps.mulPose(Axis.YP.rotationDegrees(180f - inst.wyaw));
            if (inst.wscale != 1f) ps.scale(inst.wscale, inst.wscale, inst.wscale);
            draw(ps.last(), inst, vc, light);
            ps.popPose();
            buf.endBatch(rt);
        }
    }

    static void draw(PoseStack.Pose pose, NpcInstance inst, VertexConsumer vc, int light) {
        GeoModel m = inst.asset.model;
        boolean anyHidden = !inst.actor.hidden.isEmpty();
        boolean hlActor = inst.actor.id.equals(NpcRuntime.hlActor) && !NpcRuntime.hlBone.isEmpty();
        float pulse = 0.5f + 0.5f * (float) Math.sin(System.nanoTime() / 2.5e8);
        for (int b = 0; b < m.bones; b++) {
            if (anyHidden && inst.actor.hidden.contains(m.name[b])) continue;
            boolean hl = hlActor && m.name[b].equals(NpcRuntime.hlBone);
            float cr = hl ? 0.55f + 0.2f * pulse : 1f, cg = 1f, cb = hl ? 0.65f + 0.2f * pulse : 1f;
            GeoModel.Cube[] cubes = m.cubes[b];
            for (GeoModel.Cube c : cubes) {
                float[] mat = inst.cubeMatrix(b, c);
                if (mat != null) bone.set(mat); else bone.set(inst.mats, b * 16);
                world.set(pose.pose()).mul(bone);
                bone3.set(bone);
                nrm.set(pose.normal()).mul(bone3);
                float[] vt = c.verts, nm = c.normals;
                for (int q = 0; q < c.quads; q++) {
                    n.set(nm[q * 3], nm[q * 3 + 1], nm[q * 3 + 2]);
                    nrm.transform(n);
                    if (n.lengthSquared() > 1e-8f) n.normalize();
                    for (int k = 0; k < 4; k++) {
                        int o = (q * 4 + k) * 5;
                        world.transformPosition(vt[o], vt[o + 1], vt[o + 2], v);
                        vc.vertex(v.x, v.y, v.z, cr, cg, cb, 1f, vt[o + 3], vt[o + 4], OverlayTexture.NO_OVERLAY, light, n.x, n.y, n.z);
                    }
                }
            }
        }
    }
}
