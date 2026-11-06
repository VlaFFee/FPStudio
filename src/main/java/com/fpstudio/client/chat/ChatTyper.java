package com.fpstudio.client.chat;

import com.fpstudio.chat.ChatCfg;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.FormattedCharSequence;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Undertale / Deltarune style text: letters appear one by one, every letter can make a blip sound. */
public final class ChatTyper {
    private record Snd(String id, float pitch) {}

    private static final Map<String, Map<Integer, Snd>> maps = new HashMap<>();
    private static final Map<String, Pattern> pats = new HashMap<>();
    private static final java.util.Random RND = new java.util.Random();

    private ChatTyper() {}

    /** Collect the characters of the wrapped lines and compute when each one appears (seconds since the message arrived). */
    public static void schedule(ChatMsg m, ChatCfg cfg) {
        StringBuilder all = new StringBuilder();
        int[] off = new int[m.lines.size() + 1];
        for (int i = 0; i < m.lines.size(); i++) {
            off[i] = all.length();
            m.lines.get(i).accept((idx, st, cp) -> { all.appendCodePoint(cp); return true; });
        }
        off[m.lines.size()] = all.length();
        // code points, not chars: build an int[] of code points to stay consistent with cut()
        int[] cps = all.codePoints().toArray();
        int[] lineOff = new int[m.lines.size() + 1];
        int[] lineLen = new int[m.lines.size()];
        int run = 0;
        for (int i = 0; i < m.lines.size(); i++) {
            int[] n = {0};
            m.lines.get(i).accept((idx, st, cp) -> { n[0]++; return true; });
            lineOff[i] = run;
            lineLen[i] = n[0];
            run += n[0];
        }
        lineOff[m.lines.size()] = run;
        float[] t = new float[cps.length];
        int instant = 0;
        if (cfg.typeNameInstant) {
            String s = all.toString();
            int k = s.indexOf(": ");
            if (k >= 0 && k < 48) instant = s.codePointCount(0, k + 2);
        }
        float time = cfg.typeDelay, step = 1f / Math.max(2f, cfg.typeCps);
        for (int i = 0; i < cps.length; i++) {
            if (i < instant) { t[i] = cfg.typeDelay; continue; }
            t[i] = time;
            time += step;
            int c = cps[i];
            if (c == '.' || c == '!' || c == '?' || c == 0x2026) time += cfg.typePausePunct;
            else if (c == ',' || c == ';' || c == ':' || c == 0x2014) time += cfg.typePauseComma;
        }
        m.cps = cps;
        m.sched = t;
        m.lineOff = lineOff;
        m.lineLen = lineLen;
        m.typeTotal = time;
    }

    /** how many characters of the message are visible at {@code age} seconds */
    public static int revealed(ChatMsg m, float age) {
        float[] t = m.sched;
        if (t == null) return Integer.MAX_VALUE;
        if (age >= m.typeTotal) return t.length;
        int lo = 0, hi = t.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (t[mid] <= age) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    /** Keep only the first {@code limit} characters (code points) of a styled sequence. */
    public static FormattedCharSequence cut(FormattedCharSequence seq, int limit) {
        return sink -> {
            int[] i = {0};
            return seq.accept((idx, st, cp) -> i[0]++ < limit && sink.accept(idx, st, cp));
        };
    }

    // ------------------------------------------------------------------ sounds

    private static ChatCfg.Voice voiceFor(ChatMsg m, ChatCfg cfg) {
        String plain = m.text.getString();
        for (ChatCfg.Voice v : cfg.voices) {
            if (!v.enabled || v.pattern.isEmpty()) continue;
            Pattern p = pats.computeIfAbsent(v.pattern, s -> {
                try { return Pattern.compile(s, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE); } catch (RuntimeException e) { return null; }
            });
            if (p != null && p.matcher(plain).find()) return v;
        }
        return cfg.voice;
    }

    /** "aeiou=minecraft:block.note_block.pling@1.2; xyz=ns:blip.low@0.8" -> char -> sound */
    private static Map<Integer, Snd> parseMap(String dsl) {
        return maps.computeIfAbsent(dsl, d -> {
            Map<Integer, Snd> out = new HashMap<>();
            for (String part : d.split(";")) {
                int eq = part.indexOf('=');
                if (eq <= 0) continue;
                String chars = part.substring(0, eq).trim().isEmpty() ? part.substring(0, eq) : part.substring(0, eq).trim();
                String rest = part.substring(eq + 1).trim();
                float pitch = 1f;
                int at = rest.lastIndexOf('@');
                if (at >= 0) {
                    try { pitch = Float.parseFloat(rest.substring(at + 1).trim()); } catch (NumberFormatException ignored) { pitch = 1f; }
                    rest = rest.substring(0, at).trim();
                }
                if (rest.isEmpty()) continue;
                Snd sn = new Snd(rest, pitch);
                chars.codePoints().forEach(cp -> {
                    out.put(cp, sn);
                    out.put(Character.toLowerCase(cp), sn);
                    out.put(Character.toUpperCase(cp), sn);
                });
            }
            return out;
        });
    }

    /** Play the blips of the characters that became visible since the last call. */
    public static void blips(ChatMsg m, ChatCfg cfg, int from, int to) {
        if (to <= from || m.cps == null) return;
        if (to - from > 12) from = to - 1;        // a long jump (lag / chat was hidden): one blip only
        ChatCfg.Voice v = voiceFor(m, cfg);
        if (!v.enabled) return;
        Map<Integer, Snd> map = v.map.isEmpty() ? Map.of() : parseMap(v.map);
        int played = 0;
        for (int i = from; i < to && i < m.cps.length && played < 3; i++) {
            int cp = m.cps[i];
            if (cp != ' ' && v.mute.indexOf(cp) >= 0 || Character.isWhitespace(cp)) continue;
            if (cp >= TextFx.GIF_BASE && cp <= TextFx.GIF_BASE + 64) continue;      // gif placeholder
            if (m.blipCount++ % v.everyN != 0) continue;
            Snd s = map.get(cp);
            String id = s != null ? s.id : v.sound;
            float pitch = (s != null ? s.pitch : 1f) * v.pitch * (1f + (RND.nextFloat() * 2f - 1f) * v.pitchVar);
            play(id, v.volume, pitch);
            played++;
        }
    }

    public static void play(String id, float volume, float pitch) {
        if (id == null || id.isEmpty() || volume <= 0f) return;
        try {
            SoundEvent ev = SoundEvent.createVariableRangeEvent(new ResourceLocation(id));
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(ev, Math.max(0.2f, Math.min(4f, pitch)), volume));
        } catch (RuntimeException ignored) {
            // bad sound id typed in the editor: stay silent
        }
    }

    /** Sound ids offered in the editor: a few vanilla blips plus the user's own files. */
    public static List<String> suggestions() {
        java.util.ArrayList<String> out = new java.util.ArrayList<>(UserAssets.soundIds());
        out.add("minecraft:block.note_block.bit");
        out.add("minecraft:block.note_block.pling");
        out.add("minecraft:block.note_block.chime");
        out.add("minecraft:block.note_block.hat");
        out.add("minecraft:block.note_block.bell");
        out.add("minecraft:ui.button.click");
        out.add("minecraft:entity.villager.ambient");
        out.add("minecraft:entity.chicken.ambient");
        return out;
    }
}
