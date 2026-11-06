package com.fpstudio.client.chat;

import com.fpstudio.FPStudio;
import com.fpstudio.chat.ChatCfg;
import com.fpstudio.client.gui.ChatEditorScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import com.mojang.brigadier.Command;
import net.minecraft.commands.Commands;

/** Hooks that replace the vanilla chat panel with the custom one. */
@Mod.EventBusSubscriber(modid = FPStudio.MODID, value = Dist.CLIENT)
public final class ChatEvents {
    private static final int GEAR = 14;
    private static float openAmt;
    private static long lastNano;

    private ChatEvents() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onReceived(ClientChatReceivedEvent e) {
        if (e.isCanceled() || !ChatCfg.get().enabled) return;
        if (isOverlay(e)) return;   // action bar text is not chat
        ChatStore.add(e.getMessage());
    }

    /** ClientChatReceivedEvent.System#isOverlay, via reflection so a different Forge build cannot break compilation. */
    private static boolean isOverlay(ClientChatReceivedEvent e) {
        try {
            var m = e.getClass().getMethod("isOverlay");
            return Boolean.TRUE.equals(m.invoke(e));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return false;
        }
    }

    /** First title screen: create the font/sound folders and enable the generated pack. */
    @SubscribeEvent
    public static void onTitle(ScreenEvent.Init.Post e) {
        if (e.getScreen() instanceof net.minecraft.client.gui.screens.TitleScreen) UserAssets.autoOnce();
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) { ChatStore.clear(); }

    /** Replace the vanilla chat panel. */
    @SubscribeEvent
    public static void onOverlay(RenderGuiOverlayEvent.Pre e) {
        if (e.getOverlay() != VanillaGuiOverlay.CHAT_PANEL.type()) return;
        ChatCfg cfg = ChatCfg.get();
        if (!cfg.enabled) return;
        e.setCanceled(true);
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof ChatEditorScreen) return;   // the editor draws its own preview
        boolean open = mc.screen instanceof ChatScreen;
        long nn = System.nanoTime();
        float dt = lastNano == 0 ? 0.016f : Math.min(0.1f, (nn - lastNano) / 1e9f);
        lastNano = nn;
        float step = dt / 0.16f;
        openAmt = open ? Math.min(1f, openAmt + step) : Math.max(0f, openAmt - step);
        if (openAmt <= 0f) ChatStore.scroll = 0;
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        ChatRenderer.draw(e.getGuiGraphics(), ChatStore.all(), cfg, sw, sh, openAmt, ChatStore.scroll, cfg.glide, true);
        if (open) {
            // mouse position for the hover tooltip
            double mx = mc.mouseHandler.xpos() * sw / mc.getWindow().getScreenWidth();
            double my = mc.mouseHandler.ypos() * sh / mc.getWindow().getScreenHeight();
            Style st = ChatRenderer.styleAt(mx, my);
            if (st != null && st.getHoverEvent() != null) e.getGuiGraphics().renderComponentHoverEffect(mc.font, st, (int) mx, (int) my);
        }
    }

    @SubscribeEvent
    public static void onScroll(ScreenEvent.MouseScrolled.Pre e) {
        if (!(e.getScreen() instanceof ChatScreen) || !ChatCfg.get().enabled) return;
        int step = net.minecraft.client.gui.screens.Screen.hasShiftDown() ? 5 : 1;
        int d = e.getScrollDelta() > 0 ? step : -step;
        ChatStore.scroll = Math.max(0, Math.min(Math.max(0, ChatStore.all().size() - 1), ChatStore.scroll + d));
        e.setCanceled(true);
    }

    @SubscribeEvent
    public static void onClick(ScreenEvent.MouseButtonPressed.Pre e) {
        if (!(e.getScreen() instanceof ChatScreen cs) || !ChatCfg.get().enabled) return;
        if (e.getButton() != 0) return;
        Minecraft mc = Minecraft.getInstance();
        int gx = gearX(mc);
        if (e.getMouseX() >= gx && e.getMouseX() < gx + GEAR && e.getMouseY() >= gearY(mc) && e.getMouseY() < gearY(mc) + GEAR) {
            mc.setScreen(new ChatEditorScreen(cs));
            e.setCanceled(true);
            return;
        }
        Style st = ChatRenderer.styleAt(e.getMouseX(), e.getMouseY());
        if (st != null && cs.handleComponentClicked(st)) e.setCanceled(true);
    }

    private static int gearX(Minecraft mc) { return mc.getWindow().getGuiScaledWidth() - GEAR - 4; }

    private static int gearY(Minecraft mc) { return mc.getWindow().getGuiScaledHeight() - 14 - 2 - GEAR - 2; }

    /** A small gear above the chat input that opens the editor. */
    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post e) {
        if (!(e.getScreen() instanceof ChatScreen) || !ChatCfg.get().enabled) return;
        Minecraft mc = Minecraft.getInstance();
        GuiGraphics g = e.getGuiGraphics();
        int x = gearX(mc), y = gearY(mc);
        boolean hov = e.getMouseX() >= x && e.getMouseX() < x + GEAR && e.getMouseY() >= y && e.getMouseY() < y + GEAR;
        g.fill(x, y, x + GEAR, y + GEAR, hov ? 0xE05A4FB0 : 0xB0101018);
        g.drawCenteredString(mc.font, "⚙", x + GEAR / 2 + 1, y + 3, 0xFFFFFFFF);
        if (hov) g.renderTooltip(mc.font, Component.translatable("fpstudio.gui.chat.open_editor"), e.getMouseX(), e.getMouseY());
    }

    @SubscribeEvent
    public static void onCommands(RegisterClientCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("fpscreeneditor").executes(c -> {
            com.fpstudio.client.screen.ScreenClient.openEditor("default");
            return Command.SINGLE_SUCCESS;
        }).then(Commands.argument("preset", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(c -> {
            com.fpstudio.client.screen.ScreenClient.openEditor(com.mojang.brigadier.arguments.StringArgumentType.getString(c, "preset"));
            return Command.SINGLE_SUCCESS;
        })));
        e.getDispatcher().register(Commands.literal("fpchat").executes(c -> {
            Minecraft mc = Minecraft.getInstance();
            mc.tell(() -> mc.setScreen(new ChatEditorScreen(null)));
            return Command.SINGLE_SUCCESS;
        }));
    }
}
