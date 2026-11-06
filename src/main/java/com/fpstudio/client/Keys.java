package com.fpstudio.client;

import com.fpstudio.FPStudio;
import com.fpstudio.client.gui.StudioScreen;
import com.fpstudio.model.ClipIO;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;

public final class Keys {
    public static final KeyMapping OPEN = new KeyMapping("key.fpstudio.open", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, "key.categories.fpstudio");
    public static final KeyMapping PLAY = new KeyMapping("key.fpstudio.play", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P, "key.categories.fpstudio");

    public static final KeyMapping NPC = new KeyMapping("key.fpstudio.npc", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, "key.categories.fpstudio");

    public static final KeyMapping CHAT = new KeyMapping("key.fpstudio.chat", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_U, "key.categories.fpstudio");

    public static final KeyMapping SCREEN = new KeyMapping("key.fpstudio.screen", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Y, "key.categories.fpstudio");

    /** Set by InputLock when the Play key just stopped a locked animation, so the same press does not restart it. */
    public static boolean suppressPlay;

    private Keys() {}

    @Mod.EventBusSubscriber(modid = FPStudio.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBus {
        @SubscribeEvent
        public static void register(RegisterKeyMappingsEvent e) {
            e.register(OPEN);
            e.register(PLAY);
            e.register(NPC);
            e.register(CHAT);
            e.register(SCREEN);
        }
    }

    @Mod.EventBusSubscriber(modid = FPStudio.MODID, value = Dist.CLIENT)
    public static final class ForgeBus {
        @SubscribeEvent
        public static void onTick(TickEvent.ClientTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance();
            boolean skipPlay = suppressPlay;
            suppressPlay = false;
            while (OPEN.consumeClick()) {
                if (MoveRecorder.busy()) { MoveRecorder.finish(); continue; }
                if (!InputLock.active()) openStudio(mc);
            }
            while (SCREEN.consumeClick()) {
                com.fpstudio.client.screen.ScreenClient.openEditor("default");
            }
            while (CHAT.consumeClick()) {
                mc.setScreen(new com.fpstudio.client.gui.ChatEditorScreen(null));
            }
            while (NPC.consumeClick()) {
                if (!InputLock.active()) openNpc(mc);
            }
            while (PLAY.consumeClick()) {
                if (MoveRecorder.busy()) { MoveRecorder.finish(); continue; }
                if (skipPlay) continue;
                if (Playback.playing) Playback.stop(false);
                else playCurrent(mc);
            }
        }

        @SubscribeEvent
        public static void onCommands(RegisterClientCommandsEvent e) {
            e.getDispatcher().register(Commands.literal("fpstudio")
                    .executes(c -> { openStudio(Minecraft.getInstance()); return 1; })
                    .then(Commands.literal("open").executes(c -> { openStudio(Minecraft.getInstance()); return 1; }))
                    .then(Commands.literal("stop").executes(c -> { Playback.stop(false); return 1; }))
                    .then(Commands.literal("list").executes(c -> {
                        msg("fpstudio.msg.list", String.join(", ", ClipIO.list()));
                        return 1;
                    }))
                    .then(Commands.literal("play")
                            .then(Commands.argument("name", StringArgumentType.greedyString())
                                    .suggests((c, b) -> SharedSuggestionProvider.suggest(ClipIO.list(), b))
                                    .executes(c -> {
                                        String name = StringArgumentType.getString(c, "name");
                                        try {
                                            Playback.clip = ClipIO.load(name);
                                            playCurrent(Minecraft.getInstance());
                                        } catch (Exception ex) {
                                            msg("fpstudio.msg.load_failed", name);
                                        }
                                        return 1;
                                    }))));
        }
    }

    public static void openStudio(Minecraft mc) {
        if (mc.player == null) return;
        if (!Actions.allowed(mc.player)) {
            mc.player.displayClientMessage(Component.translatable("fpstudio.msg.creative_only"), true);
            return;
        }
        mc.setScreen(new StudioScreen());
    }

    /** N: edit the NPC you look at (or the nearest within 6 blocks); otherwise open the "new NPC" dialog at the crosshair. */
    public static void openNpc(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        if (!com.fpstudio.world.NpcNet.canEdit(mc.player)) {
            mc.player.displayClientMessage(Component.translatable("fpstudio.msg.npc_no_permission"), true);
            return;
        }
        com.fpstudio.world.StudioNpc target = null;
        if (mc.crosshairPickEntity instanceof com.fpstudio.world.StudioNpc n) target = n;
        if (target == null) {
            double best = 36.0;
            for (var en : mc.level.entitiesForRendering()) {
                if (en instanceof com.fpstudio.world.StudioNpc n) {
                    double d = n.distanceToSqr(mc.player);
                    if (d < best) { best = d; target = n; }
                }
            }
        }
        if (target != null && mc.player.isShiftKeyDown() == false && target.distanceToSqr(mc.player) < 36.0) {
            com.fpstudio.world.NpcNet.CH.sendToServer(new com.fpstudio.world.NpcNet.Action(target.getId(), com.fpstudio.world.NpcNet.A_EDIT, ""));
            return;
        }
        net.minecraft.world.phys.Vec3 at = mc.player.position();
        if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bh && bh.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK)
            at = bh.getLocation();
        mc.setScreen(new com.fpstudio.client.gui.NpcEditorScreen(at.x, at.y, at.z, mc.player.getYRot() + 180f));
    }

    public static void playCurrent(Minecraft mc) {
        if (mc.player == null) return;
        if (!Actions.allowed(mc.player)) {
            mc.player.displayClientMessage(Component.translatable("fpstudio.msg.creative_only"), true);
            return;
        }
        Playback.returnToStudio = false;
        Playback.play(0f, true, true);
    }

    private static void msg(String key, Object... args) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.translatable(key, args), false);
    }
}
