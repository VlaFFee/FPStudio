package com.fpstudio.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything that describes one world NPC: model, behaviour, route and scripts. It is stored in the entity's NBT as JSON
 * (so every placed NPC is individually editable) and sent to the editor as JSON.
 */
public final class NpcProfile {
    private static final Gson GSON = new GsonBuilder().create();

    public static final class Point {
        public double x, y, z;
        public float wait = 0f;          // seconds to stand here
        public String script = "";       // optional script name to run on arrival
        public Point() {}
        public Point(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
    }

    // ---- look
    public String asset = "";            // folder / file name in config/fpstudio/npcs
    public String name = "NPC";
    public boolean showName = true;
    public float scale = 1f;
    public float width = 0.6f, height = 1.8f;
    public String idle = "", walk = "", run = "", talk = "", sit = "";   // animation names (empty = auto)
    public float walkSpeedAnim = 1f;     // playback speed multiplier of the walk / run animation

    // ---- behaviour
    public boolean invulnerable = true;
    public boolean pushable = true;
    public boolean lookAtPlayer = true;
    public float lookRange = 6f;
    public float wanderRadius = 0f;      // 0 = stand still
    public float speed = 0.25f;          // movement attribute
    public float runSpeedMul = 1.6f;     // used by `run` in scripts and by routes in run mode
    public boolean faceOnTalk = true;    // turn towards the player who started a script
    public float nearRange = 4f;         // on_near trigger distance
    public int nearCooldown = 600;       // ticks before on_near can fire again for the same player

    // ---- route
    public List<Point> route = new ArrayList<>();
    public String routeMode = "loop";    // none | loop | pingpong | once
    public boolean routeRun = false;

    // ---- scripts / state
    public Map<String, String> scripts = new LinkedHashMap<>();
    public Map<String, String> vars = new LinkedHashMap<>();
    public String chatFormat = "§6[{name}]§r {text}";
    public float chatRange = 48f;
    public String chatIcon = "";         // icon in chat: file name in config/fpstudio/chat_icons, player:Name, item:id ("" = file named like the model)
    public String nameColor = "#B9F26B"; // colour of [Name] in chat

    // ---- equipment (item ids)
    public String mainHand = "", offHand = "", head = "", chest = "", legs = "", feet = "";

    public static final String[] EVENTS = {"on_spawn", "on_interact", "on_near", "on_hurt", "on_death", "on_route_end"};

    public NpcProfile fix() {
        if (asset == null) asset = "";
        if (name == null) name = "NPC";
        if (idle == null) idle = "";
        if (walk == null) walk = "";
        if (run == null) run = "";
        if (talk == null) talk = "";
        if (sit == null) sit = "";
        if (scale < 0.05f) scale = 1f;
        if (width < 0.1f) width = 0.6f;
        if (height < 0.1f) height = 1.8f;
        if (route == null) route = new ArrayList<>();
        if (routeMode == null) routeMode = "loop";
        if (scripts == null) scripts = new LinkedHashMap<>();
        if (vars == null) vars = new LinkedHashMap<>();
        if (chatFormat == null || chatFormat.isBlank()) chatFormat = "§6[{name}]§r {text}";
        if (mainHand == null) mainHand = "";
        if (offHand == null) offHand = "";
        if (head == null) head = "";
        if (chest == null) chest = "";
        if (legs == null) legs = "";
        if (feet == null) feet = "";
        route.removeIf(p -> p == null);
        return this;
    }

    public String toJson() { return GSON.toJson(this); }

    public static NpcProfile fromJson(String json) {
        NpcProfile p;
        try {
            p = GSON.fromJson(json, NpcProfile.class);
        } catch (RuntimeException e) {
            p = null;
        }
        return (p == null ? new NpcProfile() : p).fix();
    }

    public NpcProfile copy() { return fromJson(toJson()); }

    /** Animation set sent to clients: idle|walk|run|talk|sit */
    public String animPack() { return idle + "|" + walk + "|" + run + "|" + talk + "|" + sit; }
}
