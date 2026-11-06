package com.fpstudio.client.screen;

import com.fpstudio.FPStudio;
import com.fpstudio.chat.ChatCfg;
import com.fpstudio.client.chat.ChatMsg;
import com.fpstudio.client.chat.ChatRenderer;
import com.fpstudio.client.chat.ChatStore;
import com.fpstudio.client.gui.ChatEditorScreen;
import com.fpstudio.world.NpcNet;
import com.fpstudio.world.ScreenNet;
import com.fpstudio.world.ScreenPresets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Client side of "text on the screen": active messages, drawing, presets received from the world. */
public final class ScreenClient {
    private static final class Entry {
        final String id;
        final ChatCfg cfg;
        final ChatMsg msg;
        final long until;

        Entry(String id, ChatCfg cfg, ChatMsg msg, long until) { this.id = id; this.cfg = cfg; this.msg = msg; this.until = until; }
    }

    private static final List<Entry> active = new ArrayList<>();
    private static Map<String, String> presets = new TreeMap<>();

    private ScreenClient() {}

    // ------------------------------------------------------------------ presets

    public static void setPresets(Map<String, String> m) {
        presets = new TreeMap<>(m);
        ChatEditorScreen.presetsChanged();
    }

    /** Presets known to this client: the world ones (op/creative players receive them) or the built-ins. */
    public static Map<String, String> presets() {
        if (presets.isEmpty()) return new TreeMap<>(ScreenPresets.builtin());
        return presets;
    }

    public static void requestPresets() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) NpcNet.CH.sendToServer(new ScreenNet.Request());
    }

    public static void savePreset(String name, ChatCfg cfg) {
        Minecraft mc = Minecraft.getInstance();
        Map<String, String> m = new TreeMap<>(presets());
        m.put(name, cfg.toJson());
        presets = new TreeMap<>(m);
        if (mc.getConnection() != null) NpcNet.CH.sendToServer(new ScreenNet.Save(name, cfg.toJson()));
    }

    public static void deletePreset(String name) {
        Minecraft mc = Minecraft.getInstance();
        presets.remove(name);
        if (mc.getConnection() != null) NpcNet.CH.sendToServer(new ScreenNet.Delete(name));
    }

    // ------------------------------------------------------------------ show / clear

    public static void show(String id, String json, float seconds, String text) {
        ChatCfg cfg = ChatCfg.fromJson(json);
        cfg.idleMessages = 1;
        cfg.openMessages = 1;
        if (seconds > 0f) cfg.showSeconds = seconds;
        long now = System.currentTimeMillis();
        ChatMsg m = ChatStore.process(Component.literal(text), cfg, now);
        if (m == null) return;
        long total = (long) ((cfg.showSeconds + cfg.fadeSeconds) * 1000f) + 400L;
        active.removeIf(e -> e.id.equals(id));
        active.add(new Entry(id, cfg, m, now + total));
    }

    public static void clear(String id) {
        if (id == null || id.isEmpty()) active.clear();
        else active.removeIf(e -> e.id.equals(id));
    }

    public static void openEditor(String preset) {
        Minecraft mc = Minecraft.getInstance();
        mc.tell(() -> mc.setScreen(new ChatEditorScreen(null, preset)));
    }

    public static boolean any() { return !active.isEmpty(); }

    // ------------------------------------------------------------------ drawing

    public static void render(GuiGraphics g) {
        if (active.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        active.removeIf(e -> now > e.until);
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        float bx0 = ChatRenderer.boxX0, by0 = ChatRenderer.boxY0, bx1 = ChatRenderer.boxX1, by1 = ChatRenderer.boxY1;
        for (Entry e : new ArrayList<>(active)) {
            ChatRenderer.draw(g, List.of(e.msg), e.cfg, sw, sh, 0f, 0, false, false);
        }
        ChatRenderer.boxX0 = bx0; ChatRenderer.boxY0 = by0; ChatRenderer.boxX1 = bx1; ChatRenderer.boxY1 = by1;
    }

    @Mod.EventBusSubscriber(modid = FPStudio.MODID, value = Dist.CLIENT)
    public static final class Events {
        private Events() {}

        @SubscribeEvent
        public static void onGui(RenderGuiEvent.Post e) {
            render(e.getGuiGraphics());
        }

        @SubscribeEvent
        public static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
            active.clear();
            presets = new TreeMap<>();
        }
    }

}
