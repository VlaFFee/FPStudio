package com.fpstudio.npc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Bedrock / GeckoLib .animation.json: keyframes with pre/post values, linear / catmullrom / step / easing curves, Molang values. */
public final class GeoAnimations {
    public static final int LOOP_NONE = 0, LOOP = 1, LOOP_HOLD = 2;

    public static final class Channel {
        float[] time = new float[0];
        Molang.Expr[][] pre = new Molang.Expr[0][], post = new Molang.Expr[0][];
        int[] ease = new int[0];
        float[] easeArg = new float[0];
        boolean molang;
        int n() { return time.length; }
    }

    public static final class BoneAnim {
        Channel pos, rot, scl;
    }

    public static final class Anim {
        public final String name;
        public float length;
        public int loop;
        final Map<String, BoneAnim> bones = new LinkedHashMap<>();

        Anim(String name) { this.name = name; }

        /** Bind to a model so sampling is array indexing only. */
        public Bound bind(GeoModel m) {
            Bound b = new Bound(this, m.bones);
            for (Map.Entry<String, BoneAnim> e : bones.entrySet()) {
                int i = m.bone(e.getKey());
                if (i < 0) continue;
                b.pos[i] = e.getValue().pos;
                b.rot[i] = e.getValue().rot;
                b.scl[i] = e.getValue().scl;
            }
            return b;
        }
    }

    public static final class Bound {
        public final Anim anim;
        final Channel[] pos, rot, scl;

        Bound(Anim a, int bones) {
            anim = a;
            pos = new Channel[bones];
            rot = new Channel[bones];
            scl = new Channel[bones];
        }

        /** Map a time since the animation started to a time inside it, honouring the loop mode. */
        public float localTime(float since) {
            if (since < 0f) since = 0f;
            float len = anim.length;
            if (len <= 1e-4f) return 0f;
            if (anim.loop == LOOP) return since % len;
            return Math.min(since, len);
        }

        /** Fill {@code out} with this animation at animation-local time {@code t}. */
        public void sample(float t, Pose out) {
            int n = pos.length;
            for (int i = 0; i < n; i++) {
                Channel c = pos[i];
                if (c != null) eval(c, t, out.pos, i * 3); else { out.pos[i * 3] = 0; out.pos[i * 3 + 1] = 0; out.pos[i * 3 + 2] = 0; }
                c = rot[i];
                if (c != null) {
                    eval(c, t, out.rot, i * 3);
                    out.rot[i * 3] = (float) Math.toRadians(-out.rot[i * 3]);
                    out.rot[i * 3 + 1] = (float) Math.toRadians(-out.rot[i * 3 + 1]);
                    out.rot[i * 3 + 2] = (float) Math.toRadians(out.rot[i * 3 + 2]);
                } else { out.rot[i * 3] = 0; out.rot[i * 3 + 1] = 0; out.rot[i * 3 + 2] = 0; }
                c = scl[i];
                if (c != null) eval(c, t, out.scl, i * 3); else { out.scl[i * 3] = 1; out.scl[i * 3 + 1] = 1; out.scl[i * 3 + 2] = 1; }
            }
        }
    }

    public final Map<String, Anim> anims = new LinkedHashMap<>();

    public void merge(GeoAnimations o) {
        for (Map.Entry<String, Anim> e : o.anims.entrySet()) anims.putIfAbsent(e.getKey(), e.getValue());
    }

    // ------------------------------------------------------------------ evaluation

    private static void eval(Channel c, float t, float[] out, int o) {
        int n = c.n();
        for (int ax = 0; ax < 3; ax++) out[o + ax] = evalAxis(c, t, ax, n);
    }

    private static float evalAxis(Channel c, float t, int ax, int n) {
        if (n == 0) return 0f;
        if (n == 1 || t <= c.time[0]) return c.pre[0][ax].eval(t);
        if (t >= c.time[n - 1]) return c.post[n - 1][ax].eval(t);
        int i = 1;
        while (i < n - 1 && c.time[i] < t) i++;
        // segment (i-1) -> i
        float t0 = c.time[i - 1], t1 = c.time[i];
        float from = c.post[i - 1][ax].eval(t), to = c.pre[i][ax].eval(t);
        float d = t1 > t0 ? (t - t0) / (t1 - t0) : 1f;
        int type = c.ease[i];
        if (type == E_CATMULL) {
            float p0 = i < 2 ? from : c.pre[i - 2][ax].eval(t);
            float p3 = i + 1 >= n ? to : c.pre[i + 1][ax].eval(t);
            return 0.5f * (2f * from + (to - p0) * d + (2f * p0 - 5f * from + 4f * to - p3) * d * d + (3f * from - p0 - 3f * to + p3) * d * d * d);
        }
        if (type == E_STEP) return d >= 1f ? to : from;
        float k = type == E_LINEAR ? d : curve(type, d, c.easeArg[i]);
        return from + (to - from) * k;
    }

    // ------------------------------------------------------------------ easing

    private static final int E_LINEAR = 0, E_STEP = 1, E_CATMULL = 2, E_CURVE = 16;
    private static final String[] FAMILY = {"sine", "quad", "cubic", "quart", "quint", "expo", "circ", "back", "elastic", "bounce"};

    private static int easeId(String s) {
        if (s == null) return E_LINEAR;
        s = s.toLowerCase(Locale.ROOT);
        if (s.equals("linear")) return E_LINEAR;
        if (s.equals("step")) return E_STEP;
        if (s.equals("catmullrom")) return E_CATMULL;
        if (!s.startsWith("ease")) return E_LINEAR;
        s = s.substring(4);
        int mode = 0; // 0 in, 1 out, 2 inout
        if (s.startsWith("inout")) { mode = 2; s = s.substring(5); }
        else if (s.startsWith("in")) { s = s.substring(2); }
        else if (s.startsWith("out")) { mode = 1; s = s.substring(3); }
        for (int f = 0; f < FAMILY.length; f++) if (FAMILY[f].equals(s)) return E_CURVE + mode * 16 + f;
        return E_LINEAR;
    }

    private static float curve(int type, float t, float arg) {
        int id = type - E_CURVE, mode = id / 16, f = id % 16;
        t = Math.max(0f, Math.min(1f, t));
        return switch (mode) {
            case 0 -> base(f, t, arg);
            case 1 -> 1f - base(f, 1f - t, arg);
            default -> t < 0.5f ? base(f, t * 2f, arg) / 2f : 1f - base(f, (1f - t) * 2f, arg) / 2f;
        };
    }

    private static float base(int f, float t, float arg) {
        switch (f) {
            case 0: return 1f - (float) Math.cos(t * Math.PI / 2.0);
            case 1: return t * t;
            case 2: return t * t * t;
            case 3: return t * t * t * t;
            case 4: return t * t * t * t * t;
            case 5: return t == 0f ? 0f : (float) Math.pow(2.0, 10.0 * t - 10.0);
            case 6: return 1f - (float) Math.sqrt(1.0 - t * t);
            case 7: { float c1 = arg == 0f ? 1.70158f : arg * 1.70158f, c3 = c1 + 1f; return c3 * t * t * t - c1 * t * t; }
            case 8: {
                if (t == 0f || t == 1f) return t;
                float c4 = (float) (2.0 * Math.PI / 3.0);
                return (float) (-Math.pow(2.0, 10.0 * t - 10.0) * Math.sin((t * 10.0 - 10.75) * c4));
            }
            default: { // bounce (ease-in = mirrored ease-out bounce)
                float u = 1f - t;
                return 1f - bounceOut(u);
            }
        }
    }

    private static float bounceOut(float x) {
        float n1 = 7.5625f, d1 = 2.75f;
        if (x < 1f / d1) return n1 * x * x;
        if (x < 2f / d1) { x -= 1.5f / d1; return n1 * x * x + 0.75f; }
        if (x < 2.5f / d1) { x -= 2.25f / d1; return n1 * x * x + 0.9375f; }
        x -= 2.625f / d1;
        return n1 * x * x + 0.984375f;
    }

    // ------------------------------------------------------------------ parsing

    public static GeoAnimations parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        GeoAnimations out = new GeoAnimations();
        if (!root.has("animations")) return out;
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("animations").entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            JsonObject ao = e.getValue().getAsJsonObject();
            Anim a = new Anim(e.getKey());
            JsonElement loop = ao.get("loop");
            if (loop != null && loop.isJsonPrimitive()) {
                if (loop.getAsJsonPrimitive().isBoolean()) a.loop = loop.getAsBoolean() ? LOOP : LOOP_NONE;
                else if ("hold_on_last_frame".equals(loop.getAsString())) a.loop = LOOP_HOLD;
            }
            float maxT = 0f;
            if (ao.has("bones") && ao.get("bones").isJsonObject()) {
                for (Map.Entry<String, JsonElement> be : ao.getAsJsonObject("bones").entrySet()) {
                    if (!be.getValue().isJsonObject()) continue;
                    JsonObject bo = be.getValue().getAsJsonObject();
                    BoneAnim ba = new BoneAnim();
                    ba.pos = channel(bo.get("position"), 0f);
                    ba.rot = channel(bo.get("rotation"), 0f);
                    ba.scl = channel(bo.get("scale"), 1f);
                    for (Channel c : new Channel[]{ba.pos, ba.rot, ba.scl}) if (c != null && c.n() > 0) maxT = Math.max(maxT, c.time[c.n() - 1]);
                    a.bones.put(be.getKey(), ba);
                }
            }
            a.length = ao.has("animation_length") ? ao.get("animation_length").getAsFloat() : maxT;
            out.anims.put(a.name, a);
        }
        return out;
    }

    private static Molang.Expr[] vec(JsonElement el, float def) {
        Molang.Expr[] v = {Molang.constant(def), Molang.constant(def), Molang.constant(def)};
        if (el == null) return v;
        if (el.isJsonObject() && el.getAsJsonObject().has("vector")) el = el.getAsJsonObject().get("vector");
        if (el.isJsonArray()) {
            JsonArray a = el.getAsJsonArray();
            for (int i = 0; i < 3; i++) v[i] = i < a.size() ? scalar(a.get(i)) : Molang.ZERO;
        } else if (el.isJsonPrimitive()) {
            Molang.Expr s = scalar(el);
            v[0] = s; v[1] = s; v[2] = s;
        }
        return v;
    }

    private static Molang.Expr scalar(JsonElement e) {
        if (e.isJsonNull()) return Molang.ZERO;
        if (e.getAsJsonPrimitive().isNumber()) return Molang.constant(e.getAsFloat());
        return Molang.compile(e.getAsString());
    }

    private static Channel channel(JsonElement el, float def) {
        if (el == null || el.isJsonNull()) return null;
        Channel c = new Channel();
        boolean keyed = el.isJsonObject() && !el.getAsJsonObject().has("vector") && !hasOnlyPrePost(el.getAsJsonObject());
        Map<Float, JsonElement> byTime = new java.util.TreeMap<>();
        if (!keyed) {
            byTime.put(0f, el);
        } else {
            for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                try { byTime.put(Float.parseFloat(e.getKey()), e.getValue()); } catch (NumberFormatException ignore) { /* skip */ }
            }
        }
        int n = byTime.size();
        if (n == 0) return null;
        c.time = new float[n];
        c.pre = new Molang.Expr[n][];
        c.post = new Molang.Expr[n][];
        c.ease = new int[n];
        c.easeArg = new float[n];
        int i = 0;
        for (Map.Entry<Float, JsonElement> e : byTime.entrySet()) {
            c.time[i] = e.getKey();
            JsonElement k = e.getValue();
            Molang.Expr[] pre, post;
            String mode = null;
            float arg = 0f;
            if (k.isJsonObject()) {
                JsonObject ko = k.getAsJsonObject();
                mode = ko.has("lerp_mode") ? ko.get("lerp_mode").getAsString() : ko.has("easing") ? ko.get("easing").getAsString() : null;
                if (ko.has("easingArgs") && ko.get("easingArgs").isJsonArray() && ko.getAsJsonArray("easingArgs").size() > 0) {
                    try { arg = ko.getAsJsonArray("easingArgs").get(0).getAsFloat(); } catch (RuntimeException ignore) { /* skip */ }
                }
                Molang.Expr[] main = ko.has("vector") ? vec(ko.get("vector"), def) : null;
                pre = ko.has("pre") ? vec(ko.get("pre"), def) : main;
                post = ko.has("post") ? vec(ko.get("post"), def) : main;
                if (pre == null) pre = post;
                if (post == null) post = pre;
                if (pre == null) { pre = vec(null, def); post = pre; }
            } else {
                pre = vec(k, def);
                post = pre;
            }
            c.pre[i] = pre;
            c.post[i] = post;
            c.ease[i] = easeId(mode);
            c.easeArg[i] = arg;
            i++;
        }
        return c;
    }

    private static boolean hasOnlyPrePost(JsonObject o) {
        return (o.has("pre") || o.has("post")) && o.keySet().stream().allMatch(k -> k.equals("pre") || k.equals("post") || k.equals("lerp_mode") || k.equals("easing") || k.equals("easingArgs"));
    }
}
