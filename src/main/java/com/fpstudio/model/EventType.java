package com.fpstudio.model;

/** Timeline events that act on the real world (all use vanilla client interaction, so they work on any server). */
public enum EventType {
    SWING_MAIN,   // swing the main hand (visible to other players)
    SWING_OFF,    // swing the off hand
    USE,          // right click at the crosshair target (place block / open / use item / interact entity)
    ATTACK,       // left click at the crosshair target (break block / hit entity)
    SOUND,        // arg: "namespace:sound[,volume[,pitch]]"
    HOTBAR,       // arg: slot 1-9
    IMPACT,       // camera + hands kick, arg: intensity (default 1)
    WALK,         // player walks for dur seconds. arg: "forward|back|left|right[,speed 0-1]"
    TURN,         // player smoothly turns over dur seconds. arg: "yaw,pitch" degrees (+yaw right, +pitch down)
    JUMP,         // one jump
    SNEAK,        // sneak for dur seconds
    SPRINT,       // sprint for dur seconds (combine with WALK)
    NPC_ANIM,     // play an NPC animation. arg: "actorId|animation", dur = length, speed
    CAM_LOOK,     // camera turns to look at an NPC for dur seconds. arg: "actorId|bone" (bone optional)
    HANDS;        // hands slide out of / into the screen over dur seconds. arg: "both|right|left" + "|hide|show"

    public boolean usesDur() { return this == WALK || this == TURN || this == SNEAK || this == SPRINT || isNpc() || this == HANDS; }

    /** NPC events only affect rendering, they never touch the real world. */
    public boolean isNpc() { return this == NPC_ANIM || this == CAM_LOOK; }

    public boolean usesArg() { return this == SOUND || this == HOTBAR || this == IMPACT || this == WALK || this == TURN; }

    public EventType step(int d) {
        EventType[] v = values();
        return v[(ordinal() + d + v.length) % v.length];
    }
}
