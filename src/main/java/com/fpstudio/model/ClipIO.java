package com.fpstudio.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** JSON persistence: config/fpstudio/clips/<name>.json */
public final class ClipIO {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path dir = Path.of("fpstudio", "clips");

    private ClipIO() {}

    public static void setDir(Path p) { dir = p; }

    public static Path dir() { return dir; }

    public static String safe(String name) {
        String s = name == null ? "" : name.trim().replaceAll("[^\\p{L}\\p{N}_\\- ]", "_");
        return s.isEmpty() ? "untitled" : s;
    }

    public static String toJson(Clip c) { return GSON.toJson(c); }

    public static Clip fromJson(String json) {
        Clip c = GSON.fromJson(json, Clip.class);
        if (c == null) c = new Clip();
        c.fix();
        return c;
    }

    public static Path save(Clip c) throws IOException {
        Files.createDirectories(dir);
        c.name = safe(c.name);
        Path p = dir.resolve(c.name + ".json");
        Files.writeString(p, toJson(c), StandardCharsets.UTF_8);
        return p;
    }

    public static Clip load(String name) throws IOException {
        Path p = dir.resolve(safe(name) + ".json");
        Clip c = fromJson(Files.readString(p, StandardCharsets.UTF_8));
        c.name = safe(name);
        return c;
    }

    public static boolean exists(String name) { return Files.exists(dir.resolve(safe(name) + ".json")); }

    public static boolean delete(String name) {
        try {
            return Files.deleteIfExists(dir.resolve(safe(name) + ".json"));
        } catch (IOException e) {
            return false;
        }
    }

    public static List<String> list() {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                    .forEach(p -> {
                        String n = p.getFileName().toString();
                        out.add(n.substring(0, n.length() - 5));
                    });
        } catch (IOException ignored) {
        }
        return out;
    }
}
