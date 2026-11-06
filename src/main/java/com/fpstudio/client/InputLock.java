package com.fpstudio.client;

import com.fpstudio.Config;
import com.fpstudio.FPStudio;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * Locks the player's controls while an animation plays: no walking, no mouse look, no mouse buttons, no wheel, and no key
 * bindings (inventory, chat, hotbar, ...). Two ways out are always kept: Esc and the Play key (P by default).
 * Scripted movement from the timeline (Walk/Turn/Jump...) is unaffected.
 */
@Mod.EventBusSubscriber(modid = FPStudio.MODID, value = Dist.CLIENT)
public final class InputLock {
    private static boolean applied;

    private InputLock() {}

    /** True while a fullscreen animation is running and its lock (or the global lock) is on. */
    public static boolean active() {
        return Playback.playing && Playback.fullscreen && (Playback.clip.lockInput || Config.FORCE_LOCK_INPUT.get());
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.START) return;
        Minecraft mc = Minecraft.getInstance();
        boolean want = active() && mc.player != null && mc.screen == null;
        if (want) {
            KeyMapping.releaseAll();           // forget every held key and queued click before the game reads them
            if (!applied) {
                applied = true;
                // An ungrabbed mouse does not turn the player. Hide the cursor so it does not show over the scene.
                mc.mouseHandler.releaseMouse();
                GLFW.glfwSetInputMode(mc.getWindow().getWindow(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_HIDDEN);
                mc.player.displayClientMessage(Component.translatable("fpstudio.msg.lock_hint"), true);
            }
        } else if (applied) {
            applied = false;
            GLFW.glfwSetInputMode(mc.getWindow().getWindow(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
            // If the studio is reopening, it needs the free cursor; otherwise give the mouse back to the game.
            if (mc.screen == null && mc.player != null && mc.isWindowActive()) mc.mouseHandler.grabMouse();
        }
    }

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre e) {
        if (active()) e.setCanceled(true);
    }

    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent e) {
        if (active()) e.setCanceled(true);
    }

    /** The Play key still stops the animation even though key bindings are locked. */
    @SubscribeEvent
    public static void onKey(InputEvent.Key e) {
        if (!active() || e.getAction() != GLFW.GLFW_PRESS) return;
        InputConstants.Key bound = Keys.PLAY.getKey();
        if (bound.getType() == InputConstants.Type.KEYSYM && bound.getValue() == e.getKey()) {
            Keys.suppressPlay = true;      // the same key press must not restart the clip on this tick
            Playback.stop(true);
        }
    }
}
