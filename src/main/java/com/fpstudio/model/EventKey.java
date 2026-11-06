package com.fpstudio.model;

public class EventKey {
    public float time;
    public EventType type = EventType.SWING_MAIN;
    public String arg = "";
    public float dur;        // seconds; used by WALK / TURN / SNEAK / SPRINT / NPC events
    public float speed = 1f; // NPC_ANIM playback speed
    // HANDS with direction "custom": where the (right) hand goes / comes from. x y z in blocks, pitch / roll in degrees. The left hand is mirrored.
    public float hx, hy = -1.5f, hz, hp, hr;

    public EventKey() {}

    public EventKey(float time, EventType type, String arg) {
        this.time = time;
        this.type = type;
        this.arg = arg == null ? "" : arg;
    }

    public EventKey(float time, EventType type, String arg, float dur) {
        this(time, type, arg);
        this.dur = dur;
    }

    public EventKey copy() {
        EventKey e = new EventKey(time, type, arg, dur);
        e.speed = speed;
        e.hx = hx; e.hy = hy; e.hz = hz; e.hp = hp; e.hr = hr;
        return e;
    }
}
