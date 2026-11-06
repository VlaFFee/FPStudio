package com.fpstudio.client.npc;

import com.fpstudio.FPStudio;
import com.fpstudio.npc.NpcAsset;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The NPC library (config/fpstudio/npcs): scans Blockbench exports and uploads their skins as textures. */
public final class NpcLibrary {
    private static final Map<String, NpcAsset> assets = new LinkedHashMap<>();
    private static final Map<String, ResourceLocation> textures = new HashMap<>();
    private static final List<ResourceLocation> uploaded = new ArrayList<>();
    private static boolean loaded;

    private NpcLibrary() {}

    public static void setDir(Path p) { com.fpstudio.npc.NpcFiles.setDir(p); }

    public static Path dir() { return com.fpstudio.npc.NpcFiles.dir(); }

    private static void ensure() {
        if (!loaded) reload();
    }

    public static Collection<NpcAsset> all() {
        ensure();
        return assets.values();
    }

    public static NpcAsset get(String name) {
        ensure();
        return name == null ? null : assets.get(name);
    }

    /** Re-read the folder. Must run on the render thread (it uploads textures). */
    public static void reload() {
        loaded = true;
        Minecraft mc = Minecraft.getInstance();
        for (ResourceLocation rl : uploaded) mc.getTextureManager().release(rl);
        uploaded.clear();
        textures.clear();
        assets.clear();
        try {
            Files.createDirectories(dir());
            if (isEmpty(dir())) installExample();
        } catch (IOException e) {
            FPStudio.LOGGER.warn("fpstudio: cannot prepare npc folder {}", dir(), e);
        }
        for (NpcAsset a : NpcAsset.scan(dir())) {
            assets.put(a.name, a);
            upload(a);
            if (!a.error.isEmpty()) FPStudio.LOGGER.warn("fpstudio: npc '{}': {}", a.name, a.error);
        }
        FPStudio.LOGGER.info("fpstudio: {} npc model(s) loaded from {}", assets.size(), dir());
    }

    private static boolean isEmpty(Path p) throws IOException {
        try (var s = Files.list(p)) { return s.findAny().isEmpty(); }
    }

    private static void installExample() {
        Path target = dir().resolve("robot");
        try {
            Files.createDirectories(target);
            for (String f : new String[]{"robot.geo.json", "robot.animation.json", "robot.png"}) {
                try (InputStream in = NpcLibrary.class.getResourceAsStream("/assets/" + FPStudio.MODID + "/example_npc/" + f)) {
                    if (in != null) Files.copy(in, target.resolve(f), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            FPStudio.LOGGER.warn("fpstudio: cannot install example npc", e);
        }
    }

    private static void upload(NpcAsset a) {
        Minecraft mc = Minecraft.getInstance();
        String clean = a.name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        ResourceLocation rl = new ResourceLocation(FPStudio.MODID, "npc/" + clean + "_" + Integer.toHexString(a.name.hashCode()));
        NativeImage img = null;
        if (a.texture != null) {
            try (InputStream in = Files.newInputStream(a.texture)) {
                img = NativeImage.read(in);
            } catch (Exception e) {
                FPStudio.LOGGER.warn("fpstudio: cannot read skin {}", a.texture, e);
            }
        }
        if (img == null) {
            img = new NativeImage(16, 16, false);
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) img.setPixelRGBA(x, y, ((x / 4 + y / 4) & 1) == 0 ? 0xFFFF00FF : 0xFF202020);
        }
        mc.getTextureManager().register(rl, new DynamicTexture(img));
        uploaded.add(rl);
        textures.put(a.name, rl);
    }

    public static ResourceLocation texture(NpcAsset a) { return textures.get(a.name); }

    /** Open the folder in the system file manager. */
    public static void openFolder() {
        try {
            Files.createDirectories(dir());
            net.minecraft.Util.getPlatform().openFile(dir().toFile());
        } catch (IOException ignored) {
            // nothing to open
        }
    }
}
