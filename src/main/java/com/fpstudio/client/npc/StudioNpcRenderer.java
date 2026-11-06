package com.fpstudio.client.npc;

import com.fpstudio.FPStudio;
import com.fpstudio.model.Actor;
import com.fpstudio.npc.NpcAsset;
import com.fpstudio.npc.NpcInstance;
import com.fpstudio.world.StudioNpc;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Locale;
import java.util.WeakHashMap;

/** Draws world NPCs with the same Blockbench/GeckoLib model engine the studio uses for cutscene actors. */
public final class StudioNpcRenderer extends EntityRenderer<StudioNpc> {
    @Mod.EventBusSubscriber(modid = FPStudio.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Reg {
        @SubscribeEvent
        public static void onRegister(EntityRenderersEvent.RegisterRenderers e) {
            e.registerEntityRenderer(com.fpstudio.world.ModEntities.NPC.get(), StudioNpcRenderer::new);
        }
    }

    private static final class Live {
        NpcAsset asset;
        NpcInstance inst;
        String cur = "", prev;
        boolean curLoop = true, prevLoop = true;
        float t, prevT, blend = 1f, lastTime = Float.NaN;
        int seq = -1;
        float forcedStart;
        boolean forcedActive;
    }

    private static final WeakHashMap<StudioNpc, Live> LIVE = new WeakHashMap<>();

    public StudioNpcRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        shadowRadius = 0.4f;
    }

    @Override
    public ResourceLocation getTextureLocation(StudioNpc e) { return net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS; }

    @Override
    protected boolean shouldShowName(StudioNpc e) {
        if (!e.isCustomNameVisible() || !e.hasCustomName()) return false;
        double d = this.entityRenderDispatcher.distanceToSqr(e);
        return d < 32.0 * 32.0;
    }

    @Override
    public void render(StudioNpc e, float yaw, float pt, PoseStack ps, MultiBufferSource buf, int light) {
        NpcAsset asset = NpcLibrary.get(e.getEntityData().get(StudioNpc.D_ASSET));
        if (asset != null && asset.model.bones > 0) {
            Live l = LIVE.computeIfAbsent(e, k -> new Live());
            if (l.asset != asset || l.inst == null) {
                l.asset = asset;
                l.inst = new NpcInstance(new Actor(), asset);
                l.cur = "";
                l.prev = null;
                l.blend = 1f;
            }
            ResourceLocation tex = NpcLibrary.texture(asset);
            if (tex != null) {
                update(e, l, asset, pt);
                float body = Mth.rotLerp(pt, e.yBodyRotO, e.yBodyRot);
                float head = Mth.rotLerp(pt, e.yHeadRotO, e.yHeadRot);
                float net = Mth.clamp(Mth.wrapDegrees(head - body), -75f, 75f);
                float pitch = Mth.clamp(e.getViewXRot(pt), -45f, 45f);
                l.inst.updateLive(l.cur, l.t, l.curLoop, l.prev, l.prevT, l.prevLoop, l.blend,
                        (float) Math.toRadians(-net), (float) Math.toRadians(-pitch));
                float s = e.getEntityData().get(StudioNpc.D_SCALE);
                ps.pushPose();
                ps.mulPose(Axis.YP.rotationDegrees(180f - body));
                if (s != 1f) ps.scale(s, s, s);
                VertexConsumer vc = buf.getBuffer(RenderType.entityCutoutNoCull(tex));
                NpcRender.draw(ps.last(), l.inst, vc, light);
                ps.popPose();
            }
        }
        super.render(e, yaw, pt, ps, buf, light);
    }

    // ------------------------------------------------------------------ animation state machine

    private static String find(NpcAsset a, String... parts) {
        for (String p : parts) for (String n : a.animNames) if (n.toLowerCase(Locale.ROOT).contains(p)) return n;
        return "";
    }

    private static void update(StudioNpc e, Live l, NpcAsset asset, float pt) {
        float now = (e.tickCount + pt) / 20f;
        float dt = Float.isNaN(l.lastTime) ? 0f : Mth.clamp(now - l.lastTime, 0f, 0.25f);
        l.lastTime = now;

        String[] pack = e.getEntityData().get(StudioNpc.D_ANIMS).split("\\|", -1);
        String idle = pick(pack, 0, asset.autoIdle());
        String walk = pick(pack, 1, find(asset, "walk"));
        String run = pick(pack, 2, find(asset, "run", "sprint"));
        String talk = pick(pack, 3, find(asset, "talk", "speak"));
        String sit = pick(pack, 4, find(asset, "sit"));

        String want;
        boolean loop = true;
        float speed = 1f;
        int seq = e.getEntityData().get(StudioNpc.D_FORCED_SEQ);
        if (seq != l.seq) {
            l.seq = seq;
            l.forcedStart = now;
            l.forcedActive = !e.getEntityData().get(StudioNpc.D_FORCED).isEmpty();
        }
        String forced = e.getEntityData().get(StudioNpc.D_FORCED);
        if (l.forcedActive && !forced.isEmpty() && asset.anim(forced) != null) {
            boolean floop = e.getEntityData().get(StudioNpc.D_FORCED_LOOP);
            float len = asset.anim(forced).anim.length;
            if (floop || now - l.forcedStart < len + 0.05f) {
                want = forced;
                loop = floop;
                if (!l.cur.equals(want)) { l.t = 0f; }
            } else {
                l.forcedActive = false;
                want = null;
            }
        } else {
            want = null;
        }
        if (want == null) {
            double dx = e.getX() - e.xo, dz = e.getZ() - e.zo;
            double sp = Math.sqrt(dx * dx + dz * dz);
            boolean moving = sp > 0.01;
            if (e.getEntityData().get(StudioNpc.D_SITTING) && !sit.isEmpty()) want = sit;
            else if (moving && e.getEntityData().get(StudioNpc.D_RUNNING) && !run.isEmpty()) { want = run; speed = e.getEntityData().get(StudioNpc.D_ANIM_SPEED); }
            else if (moving && !walk.isEmpty()) { want = walk; speed = e.getEntityData().get(StudioNpc.D_ANIM_SPEED); }
            else if (e.getEntityData().get(StudioNpc.D_TALKING) && !talk.isEmpty()) want = talk;
            else want = idle;
        }
        if (!want.equals(l.cur)) {
            l.prev = l.cur.isEmpty() ? null : l.cur;
            l.prevT = l.t;
            l.prevLoop = l.curLoop;
            l.cur = want;
            l.t = l.forcedActive && want.equals(forced) ? Math.max(0f, now - l.forcedStart) : 0f;
            l.blend = l.prev == null ? 1f : 0f;
        }
        l.curLoop = loop;
        l.t += dt * speed;
        l.prevT += dt;
        if (l.blend < 1f) l.blend = Math.min(1f, l.blend + dt / 0.18f);
    }

    private static String pick(String[] pack, int i, String auto) {
        return i < pack.length && !pack[i].isEmpty() ? pack[i] : auto;
    }
}
