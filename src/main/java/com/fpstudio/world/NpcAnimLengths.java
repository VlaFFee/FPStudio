package com.fpstudio.world;

import com.fpstudio.npc.GeoAnimations;
import com.fpstudio.npc.NpcFiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/** Animation lengths read on the server (if it has the model files) so `animwait` knows how long to wait. */
public final class NpcAnimLengths {
    private static final Map<String, Map<String, Float>> cache = new HashMap<>();

    private NpcAnimLengths() {}

    public static void clear() { cache.clear(); }

    /** Seconds, or -1 if unknown. */
    public static float get(String asset, String anim) {
        if (asset == null || asset.isEmpty() || anim == null) return -1f;
        Map<String, Float> m = cache.computeIfAbsent(asset, NpcAnimLengths::load);
        Float f = m.get(anim);
        return f == null ? -1f : f;
    }

    private static Map<String, Float> load(String asset) {
        Map<String, Float> out = new HashMap<>();
        Path root = NpcFiles.dir();
        try {
            Path dir = root.resolve(asset);
            if (Files.isDirectory(dir)) {
                try (Stream<Path> s = Files.list(dir)) {
                    for (Path p : s.toList()) read(p, out);
                }
            } else {
                try (Stream<Path> s = Files.list(root)) {
                    for (Path p : s.toList()) {
                        String n = p.getFileName().toString().toLowerCase();
                        if (n.startsWith(asset.toLowerCase() + ".")) read(p, out);
                    }
                }
            }
        } catch (Exception ignored) {
            // no model files on this side
        }
        return out;
    }

    private static void read(Path p, Map<String, Float> out) {
        if (!com.fpstudio.npc.NpcAsset.isAnim(p)) return;
        try {
            GeoAnimations a = GeoAnimations.parse(Files.readString(p, StandardCharsets.UTF_8));
            for (GeoAnimations.Anim an : a.anims.values()) out.putIfAbsent(an.name, an.length);
        } catch (Exception ignored) {
            // broken file
        }
    }
}
