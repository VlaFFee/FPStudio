package com.fpstudio.chat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Settings of the custom chat (config/fpstudio/chat.json). Pure data, edited in the chat editor. */
public final class ChatCfg {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path dir = Path.of("fpstudio");
    private static ChatCfg cur;

    /** A rule applied to every incoming message whose plain text matches the regex. */
    public static final class Rule {
        public String pattern = "";      // regex, searched anywhere in the plain text
        public String icon = "";         // "", player:Name, file:name, item:minecraft:apple, none
        public String color = "";        // #RRGGBB for the leading [Name] part ("" = keep)
        public String fx = "";           // text effects for the whole message, e.g. "shake=1;glitch" ("" = none)
        public boolean hide = false;     // drop the message
        public boolean enabled = true;
    }

    public static final java.util.List<String> ANCHORS = java.util.List.of("top_left", "top_center", "top_right",
            "middle_left", "middle_center", "middle_right", "bottom_left", "bottom_center", "bottom_right");

    /** A "voice": the blip sounds of the typewriter effect. A message uses the first voice whose pattern matches. */
    public static final class Voice {
        public boolean enabled = true;
        public String pattern = "";         // regex over the message text ("" = default voice)
        public String sound = "minecraft:block.note_block.bit";
        public float volume = 0.5f;
        public float pitch = 1f;
        public float pitchVar = 0.08f;      // random pitch spread
        public int everyN = 1;              // a sound every N characters
        public String mute = " .,!?\u2026-\u2014;:\"'()[]";   // characters that stay silent
        public String map = "";             // per-character sounds: "aeiou=minecraft:block.note_block.pling@1.2; xyz=ns:blip.low@0.8"
    }

    // ---- general
    public boolean enabled = true;
    public transient int ver = 1;                  // bumped on every change so cached layouts are rebuilt (not saved)

    // ---- placement / box
    public String anchor = "bottom_center"; // top|middle|bottom _ left|center|right
    public int x = 0;                    // margin from the anchor (px, see ChatRenderer.place)
    public int y = 60;
    public int width = 380;              // box width in chat pixels (before scale)
    public float scale = 1f;             // whole chat (box, icons, text)
    public float textScale = 1f;         // text only, on top of scale
    public int idleMessages = 8;         // messages shown while chat is closed
    public int openMessages = 18;        // messages shown while chat is open
    public int padX = 4, padY = 3, gap = 2;
    public int lineSpacing = 1;
    public String style = "box";         // box | bar | none
    public int bgAlpha = 55;             // percent
    public String bgColor = "#000000";
    public boolean rounded = true;

    // ---- text
    public String font = "minecraft:default";
    public String textColor = "#FFFFFF";
    public String nameColor = "#B9F26B"; // name when a message has no colour of its own
    public boolean shadow = true;
    public boolean timestamps = false;
    public boolean restylePlayers = true; // <Name> text  ->  [Name]: text
    public boolean bracketNames = true;

    // ---- fading
    public float showSeconds = 10f;
    public float fadeSeconds = 1.2f;

    // ---- typewriter (Undertale / Deltarune style text)
    public boolean typewriter = false;
    public float typeCps = 30f;             // characters per second
    public float typeDelay = 0.1f;          // seconds before typing starts
    public float typePausePunct = 0.22f;    // extra pause after . ! ? ...
    public float typePauseComma = 0.1f;     // extra pause after , ; :
    public boolean typeNameInstant = true;  // the [Name]: part appears at once
    public Voice voice = new Voice();
    public List<Voice> voices = new ArrayList<>();

    // ---- text effects and gifs
    public boolean fxEnabled = true;        // draw effects
    public boolean fxTags = true;           // understand <shake>..</shake> in messages
    public float fxSpeed = 1f;
    public float fxStrength = 1f;
    public boolean gifsOn = true;           // understand <gif=name> in messages
    public int gifSize = 12;                // default inline gif size in px

    // ---- layout extras (mostly for text on the screen)
    public boolean vcenter = false;         // the anchor line is the vertical centre of the message instead of its bottom/top
    public int align = 0;                   // text alignment inside the box: 0 left, 1 centre, 2 right
    public String baseFx = "";              // effects for the whole message, e.g. "glitch=1;shake" ("" = none)

    // ---- user fonts (TTF/OTF from config/fpstudio/chat_fonts)
    public float ttfSize = 10f;
    public float ttfShiftY = 0f;
    public float ttfOversample = 4f;

    // ---- animations
    public String animIn = "slide_up";   // none | fade | slide_up | slide_side | pop
    public String animOut = "fade";      // none | fade | slide | shrink
    public float inSeconds = 0.28f;
    public int slidePx = 14;
    public boolean glide = true;         // older messages glide when a new one pushes them

    // ---- icons
    public boolean icons = true;
    public int iconSize = 20;
    public int iconGap = 4;
    public String iconDefault = "";      // used when nothing else matches ("" = no icon)
    public boolean playerHeads = true;   // automatic head for player messages

    public List<Rule> rules = new ArrayList<>();

    public ChatCfg fix() {
        if (anchor == null || !ANCHORS.contains(anchor)) anchor = "bottom_center";
        x = clamp(x, -3000, 3000); y = clamp(y, -3000, 3000);
        width = clamp(width, 100, 1600);
        scale = Math.max(0.3f, Math.min(4f, scale));
        textScale = Math.max(0.4f, Math.min(4f, textScale));
        inSeconds = Math.max(0f, Math.min(3f, inSeconds)); slidePx = clamp(slidePx, 0, 200);
        if (animIn == null || !java.util.List.of("none", "fade", "slide_up", "slide_side", "pop").contains(animIn)) animIn = "slide_up";
        if (animOut == null || !java.util.List.of("none", "fade", "slide", "shrink").contains(animOut)) animOut = "fade";
        idleMessages = clamp(idleMessages, 1, 60); openMessages = clamp(openMessages, 1, 100);
        padX = clamp(padX, 0, 20); padY = clamp(padY, 0, 20); gap = clamp(gap, 0, 20); lineSpacing = clamp(lineSpacing, 0, 10);
        bgAlpha = clamp(bgAlpha, 0, 100);
        iconSize = clamp(iconSize, 8, 64); iconGap = clamp(iconGap, 0, 20);
        showSeconds = Math.max(1f, Math.min(120f, showSeconds)); fadeSeconds = Math.max(0f, Math.min(10f, fadeSeconds));
        if (style == null || !(style.equals("box") || style.equals("bar") || style.equals("none"))) style = "box";
        if (font == null || font.isBlank()) font = "minecraft:default";
        if (textColor == null) textColor = "#FFFFFF";
        if (nameColor == null) nameColor = "#B9F26B";
        if (bgColor == null) bgColor = "#000000";
        if (iconDefault == null) iconDefault = "";
        if (rules == null) rules = new ArrayList<>();
        rules.removeIf(r -> r == null);
        typeCps = Math.max(2f, Math.min(400f, typeCps));
        typeDelay = Math.max(0f, Math.min(5f, typeDelay));
        typePausePunct = Math.max(0f, Math.min(3f, typePausePunct));
        typePauseComma = Math.max(0f, Math.min(3f, typePauseComma));
        align = clamp(align, 0, 2);
        if (baseFx == null) baseFx = "";
        fxSpeed = Math.max(0.1f, Math.min(4f, fxSpeed));
        fxStrength = Math.max(0f, Math.min(4f, fxStrength));
        gifSize = Math.max(4, Math.min(64, gifSize));
        for (Rule r : rules) if (r.fx == null) r.fx = "";
        ttfSize = Math.max(4f, Math.min(48f, ttfSize));
        ttfShiftY = Math.max(-10f, Math.min(10f, ttfShiftY));
        ttfOversample = Math.max(1f, Math.min(8f, ttfOversample));
        if (voice == null) voice = new Voice();
        if (voices == null) voices = new ArrayList<>();
        voices.removeIf(v -> v == null);
        fixVoice(voice);
        for (Voice v : voices) fixVoice(v);
        for (Rule r : rules) {
            if (r.pattern == null) r.pattern = "";
            if (r.icon == null) r.icon = "";
            if (r.color == null) r.color = "";
        }
        return this;
    }

    private static void fixVoice(Voice v) {
        if (v.pattern == null) v.pattern = "";
        if (v.sound == null) v.sound = "";
        if (v.mute == null) v.mute = "";
        if (v.map == null) v.map = "";
        v.volume = Math.max(0f, Math.min(2f, v.volume));
        v.pitch = Math.max(0.2f, Math.min(4f, v.pitch));
        v.pitchVar = Math.max(0f, Math.min(1f, v.pitchVar));
        v.everyN = Math.max(1, Math.min(20, v.everyN));
    }

    private static int clamp(int v, int a, int b) { return Math.max(a, Math.min(b, v)); }

    /** Parse "#RRGGBB" (or RRGGBB); returns fallback on error. */
    public static int rgb(String s, int fallback) {
        if (s == null) return fallback;
        String t = s.trim();
        if (t.startsWith("#")) t = t.substring(1);
        if (t.length() != 6) return fallback;
        try {
            return Integer.parseInt(t, 16);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static String hex(int rgb) { return String.format("#%06X", rgb & 0xFFFFFF); }

    // ------------------------------------------------------------------ persistence

    public static void setDir(Path p) { dir = p; }

    public static Path dir() { return dir; }

    public static Path file() { return dir.resolve("chat.json"); }

    public static Path iconDir() { return dir.resolve("chat_icons"); }

    public static ChatCfg get() {
        if (cur == null) cur = load();
        return cur;
    }

    private static ChatCfg load() {
        try {
            if (Files.exists(file())) {
                ChatCfg c = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), ChatCfg.class);
                if (c != null) return c.fix();
            }
        } catch (Exception ignored) {
            // broken file: start from defaults
        }
        return new ChatCfg().fix();
    }

    public static void save() {
        try {
            Files.createDirectories(dir);
            Files.writeString(file(), GSON.toJson(get()), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // read-only config folder: settings just are not kept
        }
    }

    /** Replace the live settings by a saved snapshot (editor undo). */
    public static void restore(String json) {
        try {
            ChatCfg c = GSON.fromJson(json, ChatCfg.class);
            if (c != null) { cur = c.fix(); cur.ver = 5000 + (int) (System.nanoTime() & 0xFFFFF); }
        } catch (RuntimeException ignored) {
            // keep current
        }
    }

    public static String snapshot() { return GSON.toJson(get()); }

    /** Standalone settings object (screen text presets). Never null. */
    public static ChatCfg fromJson(String json) {
        try {
            ChatCfg c = GSON.fromJson(json, ChatCfg.class);
            if (c != null) return c.fix();
        } catch (RuntimeException ignored) {
            // fall through to defaults
        }
        return new ChatCfg().fix();
    }

    public String toJson() { return new GsonBuilder().create().toJson(this); }

    public ChatCfg copy() { return fromJson(toJson()); }

    public static void reset() {
        cur = new ChatCfg().fix();
        cur.ver = 1000;
        save();
    }

    /** Call after every edit. */
    public void touch() { ver++; fix(); }
}
