package com.fpstudio.client.chat;

import com.fpstudio.FPStudio;
import com.fpstudio.chat.ChatCfg;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Icon sources for chat messages: "player:Name" (head), "file:name" (PNG from config/fpstudio/chat_icons), "item:id", "none".
 * A bare name is treated as a file.
 */
public final class ChatIcons {
    private record Img(ResourceLocation rl, int w, int h) {}

    private static final Map<String, Img> files = new HashMap<>();
    private static final List<ResourceLocation> uploaded = new ArrayList<>();
    private static final Map<String, ItemStack> items = new HashMap<>();

    private ChatIcons() {}

    public static void reload() {
        Minecraft mc = Minecraft.getInstance();
        for (ResourceLocation rl : uploaded) mc.getTextureManager().release(rl);
        uploaded.clear();
        files.clear();
    }

    /** Names of the PNG files in the icon folder (without extension). */
    public static List<String> list() {
        List<String> out = new ArrayList<>();
        try {
            Files.createDirectories(ChatCfg.iconDir());
            try (Stream<Path> s = Files.list(ChatCfg.iconDir())) {
                s.map(p -> p.getFileName().toString()).filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".png")).sorted()
                        .forEach(n -> out.add(n.substring(0, n.length() - 4)));
            }
        } catch (Exception ignored) {
            // no folder
        }
        return out;
    }

    private static Img file(String name) {
        Img i = files.get(name);
        if (i != null || files.containsKey(name)) return i;
        Img made = null;
        try {
            Path p = ChatCfg.iconDir().resolve(name.replaceAll("[\\\\/]", "_") + ".png");
            if (Files.exists(p)) {
                try (InputStream in = Files.newInputStream(p)) {
                    NativeImage img = NativeImage.read(in);
                    ResourceLocation rl = new ResourceLocation(FPStudio.MODID, "chat_icon/" + Integer.toHexString(name.hashCode()) + "_" + uploaded.size());
                    Minecraft.getInstance().getTextureManager().register(rl, new DynamicTexture(img));
                    uploaded.add(rl);
                    made = new Img(rl, img.getWidth(), img.getHeight());
                }
            }
        } catch (Exception e) {
            FPStudio.LOGGER.warn("fpstudio: cannot read chat icon {}", name, e);
        }
        files.put(name, made);
        return made;
    }

    private static ItemStack item(String id) {
        return items.computeIfAbsent(id, k -> {
            try {
                var it = ForgeRegistries.ITEMS.getValue(new ResourceLocation(k));
                return it == null ? ItemStack.EMPTY : new ItemStack(it);
            } catch (RuntimeException e) {
                return ItemStack.EMPTY;
            }
        });
    }

    public static boolean has(String spec) { return spec != null && !spec.isEmpty() && !spec.equals("none"); }

    /** Draw an icon square at x,y (size px). Returns false if nothing could be drawn. */
    public static boolean draw(GuiGraphics g, String spec, int x, int y, int size, float alpha) {
        if (!has(spec)) return false;
        Minecraft mc = Minecraft.getInstance();
        String kind = "file", arg = spec;
        int c = spec.indexOf(':');
        if (c > 0) {
            String k = spec.substring(0, c).toLowerCase(Locale.ROOT);
            if (k.equals("player") || k.equals("file") || k.equals("item") || k.equals("gif")) { kind = k; arg = spec.substring(c + 1); }
        }
        g.setColor(1f, 1f, 1f, alpha);
        try {
            switch (kind) {
                case "player" -> {
                    ResourceLocation skin = DefaultPlayerSkin.getDefaultSkin();
                    if (mc.getConnection() != null) {
                        PlayerInfo info = mc.getConnection().getPlayerInfo(arg);
                        if (info != null) skin = info.getSkinLocation();
                    }
                    if (mc.player != null && mc.player.getGameProfile().getName().equalsIgnoreCase(arg) ) skin = mc.player.getSkinTextureLocation();
                    PlayerFaceRenderer.draw(g, skin, x, y, size);
                }
                case "gif" -> {
                    if (!ChatGifs.draw(g, arg, x, y, size, size, alpha)) return false;
                }
                case "item" -> {
                    ItemStack st = item(arg.contains(":") ? arg : "minecraft:" + arg);
                    if (st.isEmpty()) return false;
                    float s = size / 16f;
                    g.pose().pushPose();
                    g.pose().translate(x, y, 0);
                    g.pose().scale(s, s, 1f);
                    g.renderItem(st, 0, 0);
                    g.pose().popPose();
                }
                default -> {
                    Img im = file(arg);
                    if (im == null) return false;
                    g.blit(im.rl(), x, y, size, size, 0f, 0f, im.w(), im.h(), im.w(), im.h());
                }
            }
        } finally {
            g.setColor(1f, 1f, 1f, 1f);
        }
        return true;
    }

    public static void openFolder() {
        try {
            Files.createDirectories(ChatCfg.iconDir());
            net.minecraft.Util.getPlatform().openFile(ChatCfg.iconDir().toFile());
        } catch (Exception ignored) {
            // nothing to open
        }
    }
}
