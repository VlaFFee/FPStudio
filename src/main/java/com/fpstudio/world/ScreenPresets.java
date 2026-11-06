package com.fpstudio.world;

import com.fpstudio.FPStudio;
import com.fpstudio.chat.ChatCfg;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Screen text presets live in the world (fpstudio_screen_presets.json in the world folder), so they travel with the map.
 * A preset is the JSON of a {@link ChatCfg}. A few built-in presets exist until the map overrides them.
 */
@Mod.EventBusSubscriber(modid = FPStudio.MODID)
public final class ScreenPresets {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, String> saved = new TreeMap<>();
    private static Path file;

    private ScreenPresets() {}

    public static String clean(String n) {
        return n == null ? "" : n.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    }

    /** Built-in presets: name -> JSON */
    public static Map<String, String> builtin() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("default", base(c -> { }).toJson());
        m.put("subtitle", base(c -> {
            c.anchor = "bottom_center"; c.y = 70; c.width = 460; c.textScale = 1.2f; c.style = "box"; c.bgAlpha = 60; c.rounded = true;
            c.typewriter = true; c.typeCps = 28f; c.animIn = "slide_up"; c.slidePx = 10; c.showSeconds = 4f;
        }).toJson());
        m.put("horror", base(c -> {
            c.textScale = 2.4f; c.textColor = "#C01010"; c.baseFx = "glitch=1.5;shake=0.6"; c.animIn = "pop"; c.showSeconds = 4f; c.fadeSeconds = 1.5f;
        }).toJson());
        m.put("undertale", base(c -> {
            c.anchor = "bottom_center"; c.y = 60; c.width = 440; c.align = 0; c.vcenter = false; c.textScale = 1.3f; c.style = "box"; c.bgColor = "#000000"; c.bgAlpha = 90;
            c.rounded = false; c.typewriter = true; c.typeCps = 22f; c.typeNameInstant = false; c.animIn = "fade"; c.inSeconds = 0.15f; c.showSeconds = 5f; c.padX = 8; c.padY = 6;
        }).toJson());
        return m;
    }

    private static ChatCfg base(java.util.function.Consumer<ChatCfg> f) {
        ChatCfg c = new ChatCfg();
        c.anchor = "middle_center"; c.x = 0; c.y = 0; c.vcenter = true; c.align = 1; c.width = 420; c.scale = 1f; c.textScale = 2f;
        c.style = "none"; c.icons = false; c.iconDefault = ""; c.shadow = true; c.animIn = "fade"; c.animOut = "fade"; c.inSeconds = 0.6f;
        c.showSeconds = 5f; c.fadeSeconds = 1f; c.idleMessages = 1; c.openMessages = 1; c.timestamps = false; c.restylePlayers = false; c.playerHeads = false;
        c.typewriter = false;
        f.accept(c);
        return c.fix();
    }

    public static String get(String name) {
        String n = clean(name);
        String s = saved.get(n);
        return s != null ? s : builtin().get(n);
    }

    /** Names and JSON of every preset (built-ins overridden by saved ones). */
    public static Map<String, String> all() {
        Map<String, String> m = new TreeMap<>(builtin());
        m.putAll(saved);
        return m;
    }

    public static void put(String name, String json) {
        String n = clean(name);
        if (n.isEmpty()) return;
        saved.put(n, ChatCfg.fromJson(json).toJson());
        write();
    }

    public static boolean remove(String name) {
        boolean had = saved.remove(clean(name)) != null;
        if (had) write();
        return had;
    }

    private static void write() {
        if (file == null) return;
        try {
            Files.writeString(file, GSON.toJson(saved), StandardCharsets.UTF_8);
        } catch (IOException e) {
            FPStudio.LOGGER.warn("fpstudio: cannot save screen presets", e);
        }
    }

    @SubscribeEvent
    public static void onStart(ServerStartingEvent e) {
        MinecraftServer s = e.getServer();
        saved.clear();
        file = s.getWorldPath(LevelResource.ROOT).resolve("fpstudio_screen_presets.json");
        try {
            if (Files.exists(file)) {
                Map<String, String> m = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<Map<String, String>>() {}.getType());
                if (m != null) saved.putAll(m);
            }
        } catch (IOException | RuntimeException ex) {
            FPStudio.LOGGER.warn("fpstudio: cannot read screen presets", ex);
        }
    }

    @SubscribeEvent
    public static void onStop(ServerStoppedEvent e) {
        saved.clear();
        file = null;
    }

    @SubscribeEvent
    public static void onJoin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && NpcNet.canEdit(p)) ScreenNet.sendPresets(p);
    }
}
