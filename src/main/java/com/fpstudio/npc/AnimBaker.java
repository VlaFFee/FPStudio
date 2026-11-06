package com.fpstudio.npc;

import com.fpstudio.model.Actor;
import com.fpstudio.model.Clip;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Turns what the studio shows (the animation plus the manual bone tracks on top of it) into a normal Bedrock animation entry,
 * so edited animations are plain animation.json data that NPCs and Blockbench can use.
 */
public final class AnimBaker {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final float FPS = 30f;

    private AnimBaker() {}

    public static JsonObject bake(NpcInstance inst, Clip clip, float t0, float len, int loopMode) {
        Actor actor = inst.actor;
        GeoModel m = inst.asset.model;
        float oldBlend = actor.blend;
        actor.blend = 0.0001f;
        int n = Math.max(1, Math.round(len * FPS));
        int bones = m.bones;
        float[][] pos = new float[bones][(n + 1) * 3], rot = new float[bones][(n + 1) * 3], scl = new float[bones][(n + 1) * 3];
        float[] times = new float[n + 1];
        for (int k = 0; k <= n; k++) {
            float t = len * k / n;
            times[k] = t;
            inst.update(clip, t0 + t);
            Pose ps = inst.pose();
            for (int b = 0; b < bones; b++) {
                for (int a = 0; a < 3; a++) {
                    pos[b][k * 3 + a] = ps.pos[b * 3 + a];
                    float deg = (float) Math.toDegrees(ps.rot[b * 3 + a]);
                    rot[b][k * 3 + a] = a == 2 ? deg : -deg;
                    scl[b][k * 3 + a] = ps.scl[b * 3 + a];
                }
            }
        }
        actor.blend = oldBlend;

        JsonObject anim = new JsonObject();
        if (loopMode == GeoAnimations.LOOP) anim.addProperty("loop", true);
        else if (loopMode == GeoAnimations.LOOP_HOLD) anim.addProperty("loop", "hold_on_last_frame");
        anim.addProperty("animation_length", Math.round(len * 1000f) / 1000f);
        JsonObject bo = new JsonObject();
        for (int b = 0; b < bones; b++) {
            JsonObject one = new JsonObject();
            put(one, "position", times, pos[b], 0f, 0.004f);
            put(one, "rotation", times, rot[b], 0f, 0.05f);
            put(one, "scale", times, scl[b], 1f, 0.001f);
            if (one.size() > 0) bo.add(m.name[b], one);
        }
        anim.add("bones", bo);
        return anim;
    }

    private static void put(JsonObject bone, String key, float[] times, float[] v, float identity, float eps) {
        int n = times.length;
        boolean flat = true;
        for (int i = 0; i < v.length && flat; i++) if (Math.abs(v[i] - identity) > eps) flat = false;
        if (flat) return;
        boolean[] keep = new boolean[n];
        keep[0] = true;
        keep[n - 1] = true;
        simplify(times, v, 0, n - 1, eps, keep);
        JsonObject o = new JsonObject();
        for (int i = 0; i < n; i++) {
            if (!keep[i]) continue;
            JsonArray arr = new JsonArray();
            for (int a = 0; a < 3; a++) arr.add(round(v[i * 3 + a]));
            o.add(fmt(times[i]), arr);
        }
        bone.add(key, o);
    }

    private static void simplify(float[] t, float[] v, int a, int b, float eps, boolean[] keep) {
        if (b <= a + 1) return;
        int worst = -1;
        float wd = eps;
        for (int i = a + 1; i < b; i++) {
            float f = (t[i] - t[a]) / (t[b] - t[a]);
            float d = 0f;
            for (int c = 0; c < 3; c++) {
                float lin = v[a * 3 + c] + (v[b * 3 + c] - v[a * 3 + c]) * f;
                d = Math.max(d, Math.abs(v[i * 3 + c] - lin));
            }
            if (d > wd) { wd = d; worst = i; }
        }
        if (worst < 0) return;
        keep[worst] = true;
        simplify(t, v, a, worst, eps, keep);
        simplify(t, v, worst, b, eps, keep);
    }

    private static float round(float f) { return Math.round(f * 10000f) / 10000f; }

    private static String fmt(float t) {
        String s = String.format(Locale.ROOT, "%.4f", t).replaceAll("0+$", "").replaceAll("\\.$", ".0");
        return s.isEmpty() ? "0.0" : s;
    }

    /** Where edited animations of a model go: its folder (edited.animation.json) or its loose NAME.animation.json. */
    public static Path targetFile(Path root, String asset) {
        Path dir = root.resolve(asset);
        if (Files.isDirectory(dir)) return dir.resolve("edited.animation.json");
        return root.resolve(asset + ".animation.json");
    }

    public static void write(Path file, String animName, JsonObject anim) throws IOException {
        JsonObject root;
        if (Files.exists(file)) {
            root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            Path bak = file.resolveSibling(file.getFileName() + ".bak");
            if (!Files.exists(bak)) Files.copy(file, bak);
        } else {
            root = new JsonObject();
        }
        if (!root.has("format_version")) root.addProperty("format_version", "1.8.0");
        JsonObject anims = root.has("animations") && root.get("animations").isJsonObject() ? root.getAsJsonObject("animations") : new JsonObject();
        anims.add(animName, anim);
        root.add("animations", anims);
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson((JsonElement) root), StandardCharsets.UTF_8);
    }

}
