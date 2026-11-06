package com.fpstudio.client;

import com.fpstudio.Config;
import com.fpstudio.model.EventKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.registries.ForgeRegistries;

/** Real world interaction. Everything goes through the vanilla client game mode, so servers see ordinary player actions. */
public final class Actions {
    private Actions() {}

    public static boolean allowed(LocalPlayer p) {
        return p != null && (!Config.REQUIRE_CREATIVE.get() || p.isCreative());
    }

    public static void fire(EventKey e) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (!allowed(p) || mc.level == null || mc.gameMode == null) return;
        switch (e.type) {
            case SWING_MAIN -> p.swing(InteractionHand.MAIN_HAND);
            case SWING_OFF -> p.swing(InteractionHand.OFF_HAND);
            case USE -> use(mc, p);
            case ATTACK -> attack(mc, p);
            case SOUND -> sound(mc, p, e.arg);
            case HOTBAR -> {
                int n = (int) Playback.parse(e.arg, 1f);
                if (n >= 1 && n <= 9) p.getInventory().selected = n - 1;
            }
            case IMPACT -> { } // purely visual, evaluated from time in Playback
            case WALK, TURN, JUMP, SNEAK, SPRINT -> { } // continuous: handled by Motion every frame / tick
            case HANDS -> { }                           // purely visual, evaluated from time in Playback
            case NPC_ANIM, CAM_LOOK -> { }              // cutscene actors: evaluated from time every frame
        }
    }

    private static void use(Minecraft mc, LocalPlayer p) {
        InteractionHand hand = InteractionHand.MAIN_HAND;
        HitResult hit = mc.hitResult;
        if (hit instanceof EntityHitResult ehr) {
            Entity en = ehr.getEntity();
            InteractionResult r = mc.gameMode.interactAt(p, en, ehr, hand);
            if (!r.consumesAction()) r = mc.gameMode.interact(p, en, hand);
            if (r.consumesAction()) {
                if (r.shouldSwing()) p.swing(hand);
                return;
            }
        } else if (hit instanceof BlockHitResult bhr && bhr.getType() != HitResult.Type.MISS) {
            InteractionResult r = mc.gameMode.useItemOn(p, hand, bhr);
            if (r.consumesAction()) {
                if (r.shouldSwing()) p.swing(hand);
                return;
            }
        }
        ItemStack s = p.getItemInHand(hand);
        if (!s.isEmpty()) {
            InteractionResult r = mc.gameMode.useItem(p, hand);
            if (r.shouldSwing()) p.swing(hand);
        }
    }

    private static void attack(Minecraft mc, LocalPlayer p) {
        HitResult hit = mc.hitResult;
        if (hit instanceof EntityHitResult ehr) {
            mc.gameMode.attack(p, ehr.getEntity());
        } else if (hit instanceof BlockHitResult bhr && bhr.getType() != HitResult.Type.MISS) {
            BlockPos pos = bhr.getBlockPos();
            if (!mc.level.getBlockState(pos).isAir()) {
                if (mc.gameMode.startDestroyBlock(pos, bhr.getDirection())) {
                    mc.particleEngine.crack(pos, bhr.getDirection());
                }
            }
        }
        p.swing(InteractionHand.MAIN_HAND);
    }

    private static void sound(Minecraft mc, LocalPlayer p, String arg) {
        String[] parts = arg.split(",");
        ResourceLocation id = ResourceLocation.tryParse(parts[0].trim());
        if (id == null) return;
        SoundEvent se = ForgeRegistries.SOUND_EVENTS.getValue(id);
        if (se == null) return;
        float vol = parts.length > 1 ? Playback.parse(parts[1], 1f) : 1f;
        float pitch = parts.length > 2 ? Playback.parse(parts[2], 1f) : 1f;
        mc.level.playLocalSound(p.getX(), p.getY(), p.getZ(), se, SoundSource.PLAYERS, vol, pitch, false);
    }
}
