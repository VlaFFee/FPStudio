package com.fpstudio.model;

import java.util.ArrayList;
import java.util.List;

public class Clip {
    public static final int ARM_CH = 6;   // x y z pitch yaw roll
    public static final int CAM_CH = 7;   // x y z pitch yaw roll fov

    public String name = "untitled";
    public float duration = 4f;
    public boolean loop = false;
    public float blendIn = 0.35f;
    public float blendOut = 0.35f;
    public float sway = 0.8f;      // procedural idle hand sway
    public float shake = 0.0f;     // procedural hand-held camera shake
    public boolean hideHud = true;
    public boolean letterbox = true;
    public boolean lockMovement = false;   // legacy: only blocks walking
    public boolean lockInput = false;      // blocks walking, mouse look, mouse buttons, wheel and all key bindings   // block only walking
    public boolean freezeInput = true;     // block everything: walking, mouse look, keys, clicks, inventory, chat
    public boolean showRight = true;
    public boolean showLeft = false;   // new clips start with the right hand only; switch in the Clip tab
    public float barRatio = 2.39f;     // letterbox aspect ratio (bars appear when the screen is taller than this)

    /** Where the recorded camera path is anchored in the world (eye position and view of the start of the take). */
    public static class Anchor {
        public boolean on;
        public float x, y, z, yaw, pitch;
    }

    public Anchor camAnchor = new Anchor();
    public float camFly = 1.2f;       // seconds: smooth fly from the player's view to the start of the path
    public float camFlyOut = 0.8f;    // seconds: fly back to the player's view at the end
    public float camSmooth = 0.35f;   // seconds of Gaussian smoothing applied to recorded paths
    public float camDetail = 1f;      // key reduction tolerance multiplier
    public Track camRaw;              // dense recording, kept so smoothing can be redone any time

    public Track right = new Track(ARM_CH);
    public Track left = new Track(ARM_CH);
    public Track camera = new Track(CAM_CH);
    public List<EventKey> events = new ArrayList<>();
    public List<Actor> actors = new ArrayList<>();

    public Track track(int i) { return i == 0 ? right : i == 1 ? left : camera; }

    public Actor actor(String id) {
        for (Actor a : actors) if (a.id.equals(id)) return a;
        return null;
    }

    public void sortEvents() { events.sort((a, b) -> Float.compare(a.time, b.time)); }

    public void fix() {
        if (name == null || name.isBlank()) name = "untitled";
        if (right == null) right = new Track(ARM_CH);
        if (left == null) left = new Track(ARM_CH);
        if (camera == null) camera = new Track(CAM_CH);
        right.channels = ARM_CH;
        left.channels = ARM_CH;
        camera.channels = CAM_CH;
        right.fix();
        left.fix();
        camera.fix();
        if (events == null) events = new ArrayList<>();
        if (camAnchor == null) camAnchor = new Anchor();
        if (camRaw != null) { camRaw.channels = CAM_CH; camRaw.fix(); }
        if (actors == null) actors = new ArrayList<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Actor a : actors) {
            a.fix();
            String base = a.id;
            int n = 2;
            while (!ids.add(a.id)) a.id = base + (n++);
        }
        for (EventKey e : events) {
            if (e.type == null) e.type = EventType.SWING_MAIN;
            if (e.arg == null) e.arg = "";
        }
        sortEvents();
        if (duration < 0.1f) duration = 0.1f;
    }

    /** Time of the last key / event of any track (0 if empty). */
    public float lastTime() {
        float m = 0f;
        for (int i = 0; i < 3; i++) for (Keyframe k : track(i).keys) m = Math.max(m, k.time);
        for (EventKey e : events) m = Math.max(m, e.time);
        for (Actor a : actors) {
            for (Keyframe k : a.move.keys) m = Math.max(m, k.time);
            for (Track t : a.bones.values()) for (Keyframe k : t.keys) m = Math.max(m, k.time);
        }
        return m;
    }

    public boolean isEmpty() {
        return right.keys.isEmpty() && left.keys.isEmpty() && camera.keys.isEmpty() && events.isEmpty() && actors.isEmpty();
    }
}
