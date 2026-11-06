package com.fpstudio.npc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * One NPC from the library: geometry + all animations + the texture file. Two layouts are accepted inside config/fpstudio/npcs:
 * a folder per NPC (name = folder) or loose files NAME.geo.json / NAME.png / NAME.animation.json (name = NAME).
 */
public final class NpcAsset {
    public final String name;
    public final GeoModel model;
    public final GeoAnimations animations;
    public final Path texture;               // may be null
    public final List<String> animNames = new ArrayList<>();
    private final Map<String, GeoAnimations.Bound> bound = new HashMap<>();
    public String error = "";

    private NpcAsset(String name, GeoModel model, GeoAnimations animations, Path texture) {
        this.name = name;
        this.model = model;
        this.animations = animations;
        this.texture = texture;
        animNames.addAll(animations.anims.keySet());
    }

    public GeoAnimations.Bound anim(String n) {
        if (n == null || n.isEmpty()) return null;
        GeoAnimations.Bound b = bound.get(n);
        if (b == null) {
            GeoAnimations.Anim a = animations.anims.get(n);
            if (a == null) return null;
            b = a.bind(model);
            bound.put(n, b);
        }
        return b;
    }

    /** Best guess for the standing animation: exact "idle", then anything containing it. */
    public String autoIdle() {
        for (String n : animNames) if (n.equalsIgnoreCase("idle")) return n;
        for (String n : animNames) if (n.toLowerCase(Locale.ROOT).contains("idle")) return n;
        return "";
    }

    public static boolean isGeo(Path p) { return p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".geo.json"); }

    public static boolean isAnim(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".animation.json") || n.endsWith(".animations.json");
    }

    public static boolean isPng(Path p) { return p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"); }

    private static String stem(Path p) {
        String n = p.getFileName().toString();
        int i = n.toLowerCase(Locale.ROOT).indexOf(".geo.json");
        if (i < 0) i = n.toLowerCase(Locale.ROOT).indexOf(".animation");
        if (i < 0) i = n.lastIndexOf('.');
        return i < 0 ? n : n.substring(0, i);
    }

    /** Scan the library folder. Broken NPCs are returned with {@link #error} set instead of failing the whole scan. */
    public static List<NpcAsset> scan(Path root) {
        List<NpcAsset> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        try (Stream<Path> s = Files.list(root)) {
            List<Path> entries = s.sorted().toList();
            for (Path p : entries) {
                if (Files.isDirectory(p)) {
                    try (Stream<Path> f = Files.list(p)) {
                        List<Path> files = f.sorted().toList();
                        out.add(build(p.getFileName().toString(), files, null));
                    }
                } else if (isGeo(p)) {
                    String st = stem(p);
                    List<Path> files = new ArrayList<>();
                    for (Path q : entries) {
                        if (Files.isDirectory(q)) continue;
                        if (q.equals(p) || ((isPng(q) || isAnim(q)) && stem(q).equalsIgnoreCase(st))) files.add(q);
                    }
                    out.add(build(st, files, null));
                }
            }
        } catch (IOException e) {
            // unreadable folder: nothing to list
        }
        out.removeIf(a -> a == null);
        return out;
    }

    private static NpcAsset build(String name, List<Path> files, String unused) {
        Path geo = null, png = null;
        List<Path> anims = new ArrayList<>();
        for (Path f : files) {
            if (isGeo(f)) { if (geo == null || stem(f).equalsIgnoreCase(name)) geo = f; }
            else if (isAnim(f)) anims.add(f);
            else if (isPng(f)) { if (png == null || stem(f).equalsIgnoreCase(name)) png = f; }
        }
        if (geo == null) return null;
        String err = "";
        GeoModel model;
        try {
            model = GeoModel.parse(Files.readString(geo, StandardCharsets.UTF_8), name);
        } catch (Exception e) {
            return broken(name, "geo: " + e.getMessage());
        }
        GeoAnimations all = new GeoAnimations();
        for (Path a : anims) {
            try {
                all.merge(GeoAnimations.parse(Files.readString(a, StandardCharsets.UTF_8)));
            } catch (Exception e) {
                err = "animation (" + a.getFileName() + "): " + e.getMessage();
            }
        }
        NpcAsset asset = new NpcAsset(name, model, all, png);
        asset.error = err;
        return asset;
    }

    private static NpcAsset broken(String name, String err) {
        GeoModel empty = GeoModel.parse("{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{\"description\":{},\"bones\":[]}]}", name);
        NpcAsset a = new NpcAsset(name, empty, new GeoAnimations(), null);
        a.error = err;
        return a;
    }
}
