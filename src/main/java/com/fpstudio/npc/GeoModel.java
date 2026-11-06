package com.fpstudio.npc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A Bedrock / GeckoLib .geo.json model, baked once into flat arrays. The maths (mirrored X, pivots, UV layout, per-face
 * UVs, mirror flag, inflate) follows GeckoLib's loader so models from Blockbench look exactly like they do there.
 * Bones are stored parent-first; positions are in blocks (1/16 of model units), pivots in model units with X already negated.
 */
public final class GeoModel {
    public static final class Cube {
        /** cube pivot (model units, X negated) and rotation (radians, -x -y z) */
        public float px, py, pz, rx, ry, rz;
        public boolean rotated;
        /** quads*4 vertices: x y z u v */
        public float[] verts;
        /** quads*3 face normals */
        public float[] normals;
        public int quads;
    }

    public final String id;
    public final int texW, texH;
    public final int bones;
    public final String[] name;
    public final int[] parent;          // -1 for roots, always < own index
    public final float[] pivot;         // 3 per bone: -x, y, z  (model units)
    public final float[] baseRot;       // 3 per bone, radians: -x, -y, z
    public final Cube[][] cubes;
    public final Map<String, Integer> index = new HashMap<>();

    private GeoModel(String id, int tw, int th, List<RawBone> list) {
        this.id = id;
        this.texW = tw;
        this.texH = th;
        // parent-first order
        List<RawBone> sorted = new ArrayList<>();
        Map<String, RawBone> byName = new HashMap<>();
        for (RawBone b : list) byName.put(b.name, b);
        java.util.Set<String> done = new java.util.HashSet<>();
        for (RawBone b : list) addSorted(b, byName, done, sorted, new java.util.HashSet<>());
        bones = sorted.size();
        name = new String[bones];
        parent = new int[bones];
        pivot = new float[bones * 3];
        baseRot = new float[bones * 3];
        cubes = new Cube[bones][];
        for (int i = 0; i < bones; i++) index.put(sorted.get(i).name, i);
        for (int i = 0; i < bones; i++) {
            RawBone b = sorted.get(i);
            name[i] = b.name;
            Integer p = b.parent == null ? null : index.get(b.parent);
            parent[i] = p == null ? -1 : p;
            pivot[i * 3] = -b.pivot[0];
            pivot[i * 3 + 1] = b.pivot[1];
            pivot[i * 3 + 2] = b.pivot[2];
            baseRot[i * 3] = (float) Math.toRadians(-b.rot[0]);
            baseRot[i * 3 + 1] = (float) Math.toRadians(-b.rot[1]);
            baseRot[i * 3 + 2] = (float) Math.toRadians(b.rot[2]);
            cubes[i] = b.cubes.toArray(new Cube[0]);
        }
    }

    private static void addSorted(RawBone b, Map<String, RawBone> byName, java.util.Set<String> done, List<RawBone> out, java.util.Set<String> stack) {
        if (done.contains(b.name) || !stack.add(b.name)) return;
        if (b.parent != null && byName.containsKey(b.parent)) addSorted(byName.get(b.parent), byName, done, out, stack);
        if (done.add(b.name)) out.add(b);
    }

    private static final class RawBone {
        String name, parent;
        float[] pivot = new float[3], rot = new float[3];
        boolean mirror;
        float inflate;
        List<Cube> cubes = new ArrayList<>();
    }

    // ------------------------------------------------------------------ parsing

    public static GeoModel parse(String json, String fallbackId) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject geo = null, desc = null;
        String id = fallbackId;
        if (root.has("minecraft:geometry")) {
            JsonArray arr = root.getAsJsonArray("minecraft:geometry");
            if (arr.size() == 0) throw new IllegalArgumentException("no geometry in file");
            geo = arr.get(0).getAsJsonObject();
            desc = geo.has("description") ? geo.getAsJsonObject("description") : new JsonObject();
        } else {
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                if (e.getKey().startsWith("geometry.") && e.getValue().isJsonObject()) {
                    geo = e.getValue().getAsJsonObject();
                    id = e.getKey();
                    desc = geo;
                    break;
                }
            }
            if (geo == null) throw new IllegalArgumentException("no geometry in file");
        }
        int tw = intOf(desc, "texture_width", intOf(desc, "texturewidth", 16));
        int th = intOf(desc, "texture_height", intOf(desc, "textureheight", 16));
        if (desc.has("identifier")) id = desc.get("identifier").getAsString();

        List<RawBone> raw = new ArrayList<>();
        JsonArray bones = geo.has("bones") ? geo.getAsJsonArray("bones") : new JsonArray();
        for (JsonElement be : bones) {
            JsonObject bo = be.getAsJsonObject();
            RawBone b = new RawBone();
            b.name = bo.get("name").getAsString();
            b.parent = bo.has("parent") ? bo.get("parent").getAsString() : null;
            b.pivot = vec(bo, "pivot", 0f);
            b.rot = vec(bo, "rotation", 0f);
            b.mirror = bo.has("mirror") && bo.get("mirror").getAsBoolean();
            b.inflate = bo.has("inflate") ? bo.get("inflate").getAsFloat() : 0f;
            if (bo.has("cubes")) {
                for (JsonElement ce : bo.getAsJsonArray("cubes")) {
                    Cube c = cube(ce.getAsJsonObject(), b, tw, th);
                    if (c != null) b.cubes.add(c);
                }
            }
            raw.add(b);
        }
        return new GeoModel(id, tw, th, raw);
    }

    private static int intOf(JsonObject o, String k, int d) { return o.has(k) ? o.get(k).getAsInt() : d; }

    private static float[] vec(JsonObject o, String k, float d) {
        float[] v = {d, d, d};
        if (o.has(k) && o.get(k).isJsonArray()) {
            JsonArray a = o.getAsJsonArray(k);
            for (int i = 0; i < 3 && i < a.size(); i++) v[i] = a.get(i).getAsFloat();
        }
        return v;
    }

    // face order and the vertex sets mirror GeckoLib's VertexSet
    private static final int WEST = 0, EAST = 1, NORTH = 2, SOUTH = 3, UP = 4, DOWN = 5;
    private static final String[] FACE_NAME = {"west", "east", "north", "south", "up", "down"};
    private static final float[][] STEP = {{-1, 0, 0}, {1, 0, 0}, {0, 0, -1}, {0, 0, 1}, {0, 1, 0}, {0, -1, 0}};

    private static Cube cube(JsonObject co, RawBone bone, int tw, int th) {
        float[] origin = vec(co, "origin", 0f), size = vec(co, "size", 0f);
        boolean mirror = co.has("mirror") ? co.get("mirror").getAsBoolean() : bone.mirror;
        float inflate = (co.has("inflate") ? co.get("inflate").getAsFloat() : bone.inflate) / 16f;
        float[] rot = vec(co, "rotation", 0f), piv = vec(co, "pivot", 0f);

        Cube c = new Cube();
        c.px = -piv[0];
        c.py = piv[1];
        c.pz = piv[2];
        c.rx = (float) Math.toRadians(-rot[0]);
        c.ry = (float) Math.toRadians(-rot[1]);
        c.rz = (float) Math.toRadians(rot[2]);
        c.rotated = c.rx != 0 || c.ry != 0 || c.rz != 0;

        float ox = -(origin[0] + size[0]) / 16f, oy = origin[1] / 16f, oz = origin[2] / 16f;
        float sx = size[0] / 16f, sy = size[1] / 16f, sz = size[2] / 16f;
        float x0 = ox - inflate, x1 = ox + sx + inflate, y0 = oy - inflate, y1 = oy + sy + inflate, z0 = oz - inflate, z1 = oz + sz + inflate;
        // vertex set (name: [x,y,z])
        float[] bLB = {x0, y0, z0}, bRB = {x0, y0, z1}, tLB = {x0, y1, z0}, tRB = {x0, y1, z1};
        float[] tLF = {x1, y1, z0}, tRF = {x1, y1, z1}, bLF = {x1, y0, z0}, bRF = {x1, y0, z1};
        float[][][] quad = new float[6][][];
        quad[WEST] = new float[][]{tRB, tLB, bLB, bRB};
        quad[EAST] = new float[][]{tLF, tRF, bRF, bLF};
        quad[NORTH] = new float[][]{tLB, tLF, bLF, bLB};
        quad[SOUTH] = new float[][]{tRF, tRB, bRB, bRF};
        quad[UP] = new float[][]{tRB, tRF, tLF, tLB};
        quad[DOWN] = new float[][]{bLB, bLF, bRF, bRB};

        JsonElement uvEl = co.get("uv");
        boolean boxUv = uvEl != null && uvEl.isJsonArray();
        float u0 = 0, v0 = 0;
        if (boxUv) {
            JsonArray a = uvEl.getAsJsonArray();
            u0 = a.get(0).getAsFloat();
            v0 = a.get(1).getAsFloat();
        }
        JsonObject faces = uvEl != null && uvEl.isJsonObject() ? uvEl.getAsJsonObject() : null;
        float fx = (float) Math.floor(size[0]), fy = (float) Math.floor(size[1]), fz = (float) Math.floor(size[2]);

        List<float[]> vs = new ArrayList<>();
        List<float[]> ns = new ArrayList<>();
        for (int d = 0; d < 6; d++) {
            if (zeroFace(size, d)) continue;
            float u, v, uSize, vSize;
            int rotation = 0;
            if (boxUv) {
                switch (d) {
                    case WEST -> { u = u0 + fz + fx; v = v0 + fz; uSize = fz; vSize = fy; }
                    case EAST -> { u = u0; v = v0 + fz; uSize = fz; vSize = fy; }
                    case NORTH -> { u = u0 + fz; v = v0 + fz; uSize = fx; vSize = fy; }
                    case SOUTH -> { u = u0 + fz + fx + fz; v = v0 + fz; uSize = fx; vSize = fy; }
                    case UP -> { u = u0 + fz; v = v0; uSize = fx; vSize = fz; }
                    default -> { u = u0 + fz + fx; v = v0 + fz; uSize = fx; vSize = -fz; }
                }
            } else {
                if (faces == null || !faces.has(FACE_NAME[d])) continue;
                JsonObject f = faces.getAsJsonObject(FACE_NAME[d]);
                JsonArray ua = f.getAsJsonArray("uv");
                u = ua.get(0).getAsFloat();
                v = ua.get(1).getAsFloat();
                if (f.has("uv_size")) {
                    JsonArray sa = f.getAsJsonArray("uv_size");
                    uSize = sa.get(0).getAsFloat();
                    vSize = sa.get(1).getAsFloat();
                } else {
                    uSize = d <= EAST ? fz : fx;
                    vSize = d <= SOUTH ? fy : (d == UP ? fz : -fz);
                }
                if (f.has("uv_rotation")) rotation = ((f.get("uv_rotation").getAsInt() % 360) + 360) % 360 / 90;
            }
            // vertices for this quad incl. mirror
            float[][] qv;
            switch (d) {
                case WEST -> qv = mirror ? quad[EAST] : quad[WEST];
                case EAST -> qv = mirror ? quad[WEST] : quad[EAST];
                case UP -> qv = mirror && !boxUv ? quad[DOWN] : quad[UP];
                case DOWN -> qv = mirror && !boxUv ? quad[UP] : quad[DOWN];
                default -> qv = quad[d];
            }
            float uu = u / tw, vv = v / th, uw = (u + uSize) / tw, vh = (v + vSize) / th;
            float nx = STEP[d][0], ny = STEP[d][1], nz = STEP[d][2];
            if (!mirror) { float tmp = uw; uw = uu; uu = tmp; } else nx = -nx;
            float[] uv = switch (rotation) {
                case 0 -> new float[]{uu, vv, uw, vv, uw, vh, uu, vh};
                case 1 -> new float[]{uw, vv, uw, vh, uu, vh, uu, vv};
                case 2 -> new float[]{uw, vh, uu, vh, uu, vv, uw, vv};
                default -> new float[]{uu, vh, uu, vv, uw, vv, uw, vh};
            };
            // flat cubes: keep the normal facing the viewer
            if (nx < 0 && (size[1] == 0 || size[2] == 0)) nx = -nx;
            if (ny < 0 && (size[0] == 0 || size[2] == 0)) ny = -ny;
            if (nz < 0 && (size[0] == 0 || size[1] == 0)) nz = -nz;
            for (int k = 0; k < 4; k++) vs.add(new float[]{qv[k][0], qv[k][1], qv[k][2], uv[k * 2], uv[k * 2 + 1]});
            ns.add(new float[]{nx, ny, nz});
        }
        if (vs.isEmpty()) return null;
        c.quads = ns.size();
        c.verts = new float[vs.size() * 5];
        for (int i = 0; i < vs.size(); i++) System.arraycopy(vs.get(i), 0, c.verts, i * 5, 5);
        c.normals = new float[ns.size() * 3];
        for (int i = 0; i < ns.size(); i++) System.arraycopy(ns.get(i), 0, c.normals, i * 3, 3);
        return c;
    }

    private static boolean zeroFace(float[] size, int d) {
        int axis = d <= EAST ? 0 : d <= SOUTH ? 2 : 1;
        if (size[0] == 0) return axis != 0;
        if (size[1] == 0) return axis != 1;
        if (size[2] == 0) return axis != 2;
        return false;
    }

    public int bone(String n) { Integer i = index.get(n); return i == null ? -1 : i; }
}
