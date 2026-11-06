package com.fpstudio.client;

import com.fpstudio.FPStudio;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** HUD hiding, vignette and letterbox bars. */
@Mod.EventBusSubscriber(modid = FPStudio.MODID, value = Dist.CLIENT)
public final class OverlayFx {
    private OverlayFx() {}

    @SubscribeEvent
    public static void onOverlayPre(RenderGuiOverlayEvent.Pre e) {
        var id = e.getOverlay().id();
        if (id.equals(VanillaGuiOverlay.CHAT_PANEL.id())) return;
        if (Playback.playing && Playback.fullscreen) {
            if (Playback.clip.hideHud) e.setCanceled(true);
        } else if (Playback.editing) {
            if (!id.equals(VanillaGuiOverlay.CROSSHAIR.id()) && !id.equals(VanillaGuiOverlay.HOTBAR.id()))
                e.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onOverlayPost(RenderGuiOverlayEvent.Post e) {
        if (!e.getOverlay().id().equals(VanillaGuiOverlay.CHAT_PANEL.id())) return;
        GuiGraphics g = e.getGuiGraphics();
        int w = e.getWindow().getGuiScaledWidth();
        int h = e.getWindow().getGuiScaledHeight();

        Minecraft mc = Minecraft.getInstance();
        if (Playback.stage && !Playback.playing && mc.screen == null) {
            String s = I18n.get("fpstudio.stage.hint");
            int sw = mc.font.width(s), x = (w - sw) / 2, y = h - 56;
            g.fill(x - 6, y - 3, x + sw + 6, y + 11, 0x99000000);
            g.drawString(mc.font, s, x, y, 0xFFFFFFFF, false);
        }

        MoveRecorder.draw(g, w, h);

        float in = StudioFx.easeOut(StudioFx.intro);
        if (in > 0.01f) vignette(g, w, h, in);

        if (StudioFx.bars > 0.001f) {
            float target = w / Math.max(1.2f, Playback.clip.barRatio);
            int bar = (int) (Math.max(0f, (h - target) / 2f) * StudioFx.easeOut(StudioFx.bars));
            if (bar > 0) {
                g.fill(0, 0, w, bar, 0xFF000000);
                g.fill(0, h - bar, w, h, 0xFF000000);
            }
        }
    }

    private static void vignette(GuiGraphics g, int w, int h, float strength) {
        int a = (int) (150 * strength);
        int top = Math.max(1, h / 4);
        g.fillGradient(0, 0, w, top, a << 24, 0);
        g.fillGradient(0, h - top, w, h, 0, a << 24);
        int side = Math.max(1, w / 6);
        int n = 18;
        for (int i = 0; i < n; i++) {
            float f = 1f - i / (float) n;
            int col = ((int) (a * 0.8f * f * f) & 0xFF) << 24;
            int x0 = i * side / n, x1 = (i + 1) * side / n;
            g.fill(x0, 0, x1, h, col);
            g.fill(w - x1, 0, w - x0, h, col);
        }
    }

    /** ESC while a fullscreen clip plays stops it instead of opening the pause menu. */
    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening e) {
        if (Playback.playing && Playback.fullscreen && e.getNewScreen() instanceof PauseScreen) {
            e.setCanceled(true);
            Playback.stop(true);
        }
    }
}
