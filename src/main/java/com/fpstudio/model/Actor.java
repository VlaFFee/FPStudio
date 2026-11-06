package com.fpstudio.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A library NPC placed in a clip. Position/yaw are world coordinates; the move track adds offsets over time.
 * Which animation plays when is decided by NPC_ANIM events; bone tracks are extra poses added on top of the playing animation.
 */
public class Actor {
    public static final int MOVE_CH = 4;   // x y z (blocks), yaw (degrees)
    public static final int BONE_CH = 7;   // px py pz (model units), rx ry rz (degrees, as in Blockbench), scale (1 + v)

    public String id = "npc1";
    public String asset = "";
    public float x, y, z, yaw;
    public float scale = 1f;
    public boolean visible = true;
    public String idle = "";            // idle animation name; empty = auto-detect
    public float blend = 0.15f;         // crossfade seconds between animations
    public Track move = new Track(MOVE_CH);
    public Map<String, Track> bones = new LinkedHashMap<>();
    public java.util.Set<String> hidden = new java.util.LinkedHashSet<>();   // bones not drawn (eye toggle in the NPC tab)

    public Track bone(String name) {
        return bones.computeIfAbsent(name, k -> new Track(BONE_CH));
    }

    public void fix() {
        if (id == null || id.isBlank()) id = "npc";
        if (asset == null) asset = "";
        if (idle == null) idle = "";
        if (scale <= 0.001f) scale = 1f;
        if (blend < 0f) blend = 0f;
        if (move == null) move = new Track(MOVE_CH);
        move.channels = MOVE_CH;
        move.fix();
        if (bones == null) bones = new LinkedHashMap<>();
        if (hidden == null) hidden = new java.util.LinkedHashSet<>();
        bones.values().removeIf(t -> t == null);
        for (Track t : bones.values()) {
            t.channels = BONE_CH;
            t.fix();
        }
    }

    public Actor copy() {
        Actor a = new Actor();
        a.id = id; a.asset = asset; a.x = x; a.y = y; a.z = z; a.yaw = yaw;
        a.scale = scale; a.visible = visible; a.idle = idle; a.blend = blend;
        a.move = copyTrack(move);
        a.hidden.addAll(hidden);
        for (Map.Entry<String, Track> e : bones.entrySet()) a.bones.put(e.getKey(), copyTrack(e.getValue()));
        return a;
    }

    private static Track copyTrack(Track t) {
        Track c = new Track(t.channels);
        for (Keyframe k : t.keys) c.keys.add(k.copy());
        return c;
    }
}
