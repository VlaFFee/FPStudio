package com.fpstudio.client;

import com.fpstudio.FPStudio;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Replaces vanilla first-person hands while a clip is playing or the studio is open. */
@Mod.EventBusSubscriber(modid = FPStudio.MODID, value = Dist.CLIENT)
public final class HandRenderer {
    private HandRenderer() {}

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent e) {
        if (!Playback.active()) return;
        e.setCanceled(true);                                   // hide the vanilla hand for both events
        if (e.getHand() != InteractionHand.MAIN_HAND) return;  // ...and draw both of ours once

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return;

        boolean rightIsMain = p.getMainArm() == HumanoidArm.RIGHT;
        ItemStack rightStack = rightIsMain ? p.getMainHandItem() : p.getOffhandItem();
        ItemStack leftStack = rightIsMain ? p.getOffhandItem() : p.getMainHandItem();

        PoseStack ps = e.getPoseStack();
        MultiBufferSource buf = e.getMultiBufferSource();
        int light = e.getPackedLight();

        if (Playback.shownHideR < 0.995f && (Playback.clip.showRight || !rightStack.isEmpty()))
            side(ps, buf, light, p, true, Playback.cur.r, rightStack, Playback.clip.showRight);
        if (Playback.shownHideL < 0.995f && (Playback.clip.showLeft || !leftStack.isEmpty()))
            side(ps, buf, light, p, false, Playback.cur.l, leftStack, Playback.clip.showLeft);
    }

    private static void side(PoseStack ps, MultiBufferSource buf, int light, LocalPlayer p, boolean right,
                             float[] v, ItemStack stack, boolean showArm) {
        float f = right ? 1f : -1f;
        ps.pushPose();
        if (!stack.isEmpty()) {
            // Same anchor vanilla uses for held items, then our offsets (camera space, +Z = away from the player).
            ps.translate(f * 0.56f, -0.52f, -0.72f);
            user(ps, v);
            Minecraft.getInstance().getItemRenderer().renderStatic(p, stack,
                    right ? ItemDisplayContext.FIRST_PERSON_RIGHT_HAND : ItemDisplayContext.FIRST_PERSON_LEFT_HAND,
                    !right, ps, buf, p.level(), light, OverlayTexture.NO_OVERLAY, p.getId() + (right ? 1 : 2));
        } else if (showArm) {
            // Vanilla ItemInHandRenderer#renderPlayerArm with equip/swing = 0, plus our offsets at the anchor.
            ps.translate(f * 0.64000005f, -0.6f, -0.71999997f);
            user(ps, v);
            ps.mulPose(Axis.YP.rotationDegrees(f * 45f));
            ps.translate(f * -1f, 3.6f, 3.5f);
            ps.mulPose(Axis.ZP.rotationDegrees(f * 120f));
            ps.mulPose(Axis.XP.rotationDegrees(200f));
            ps.mulPose(Axis.YP.rotationDegrees(f * -135f));
            ps.translate(f * 5.6f, 0f, 0f);
            PlayerRenderer pr = (PlayerRenderer) Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(p);
            AbstractClientPlayer ap = p;
            if (right) pr.renderRightHand(ps, buf, light, ap);
            else pr.renderLeftHand(ps, buf, light, ap);
        }
        ps.popPose();
    }

    /** v = x, y, z, pitch(+up), yaw(+left), roll. Z is "forward" for the user, so it is negated into render space. */
    private static void user(PoseStack ps, float[] v) {
        ps.translate(v[0], v[1], -v[2]);
        ps.mulPose(Axis.YP.rotationDegrees(v[4]));
        ps.mulPose(Axis.XP.rotationDegrees(v[3]));
        ps.mulPose(Axis.ZP.rotationDegrees(v[5]));
    }
}
