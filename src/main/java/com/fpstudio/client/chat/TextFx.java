package com.fpstudio.client.chat;

import com.fpstudio.chat.ChatCfg;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text effects and inline gifs for chat messages, inspired by the Horror FX screen effects.
 * <p>
 * Markup in the message text: {@code <shake>text</shake>}, {@code <glitch=2>}, {@code <blood=1,#ff0000>}, {@code </>} closes the last tag,
 * {@code <gif=name>} / {@code <gif=name,16>} puts a gif. Tags can be nested: {@code <wave><glow>both</glow></wave>}.
 * Parsed tags are stored in the style insertion ("fpfx:..." / "fpgif:...") so the text keeps wrapping like normal text.
 */
public final class TextFx {
    public static final String FX = "fpfx:";
    public static final String GIF = "fpgif:";
    public static final ResourceLocation GIF_FONT = new ResourceLocation("fpstudio", "gifspace");
    public static final int GIF_BASE = 0xE000;          // U+E000 + width in px is a placeholder char with that advance
    public static final List<String> NAMES = List.of("shake", "wave", "glitch", "flicker", "blood", "glow", "ghost", "corrupt", "rainbow", "robot", "color");

    private record Fx(String name, float k, int rgb) {}

    private static final Pattern TAG = Pattern.compile("<(/?)([a-zA-Z]*)(?:=([^<>]*))?>");
    private static final Map<String, Fx[]> parsed = new HashMap<>();
    private static final String JUNK = "#$%&@?!/\\|<>01*+=~";

    private TextFx() {}

    // ------------------------------------------------------------------ markup -> styled component

    private static boolean known(String n) { return NAMES.contains(n) || n.equals("static"); }

    /** Replace tags in {@code msg} by styles. Returns msg itself when it has no tags (fast path). */
    public static Component parse(Component msg, ChatCfg cfg) {
        if (!cfg.fxTags && !cfg.gifsOn) return msg;
        String plain = msg.getString();
        if (plain.indexOf('<') < 0) return msg;
        List<Object[]> segs = new ArrayList<>();
        msg.visit((st, s) -> { segs.add(new Object[]{st, s}); return Optional.empty(); }, Style.EMPTY);
        List<String> stack = new ArrayList<>();
        MutableComponent out = Component.empty();
        boolean any = false;
        for (Object[] sg : segs) {
            Style st = (Style) sg[0];
            String s = (String) sg[1];
            Matcher m = TAG.matcher(s);
            int pos = 0;
            while (m.find()) {
                String name = m.group(2).toLowerCase(Locale.ROOT), arg = m.group(3) == null ? "" : m.group(3);
                boolean close = !m.group(1).isEmpty();
                if (name.equals("gif") && !close && cfg.gifsOn) {
                    emit(out, s.substring(pos, m.start()), st, stack);
                    out.append(gifChar(arg, cfg, st));
                    pos = m.end();
                    any = true;
                } else if (cfg.fxTags && (close && name.isEmpty() || known(name))) {
                    emit(out, s.substring(pos, m.start()), st, stack);
                    if (close) {
                        if (name.isEmpty()) { if (!stack.isEmpty()) stack.remove(stack.size() - 1); }
                        else for (int i = stack.size() - 1; i >= 0; i--) if (stack.get(i).startsWith(name + "=")) { stack.remove(i); break; }
                    } else {
                        stack.add(name + "=" + (arg.isEmpty() ? "1" : arg.replace(';', ' ')));
                    }
                    pos = m.end();
                    any = true;
                }
            }
            emit(out, s.substring(pos), st, stack);
        }
        return any ? out : msg;
    }

    private static void emit(MutableComponent out, String text, Style st, List<String> stack) {
        if (text.isEmpty()) return;
        Style ns = st;
        if (!stack.isEmpty()) ns = st.withInsertion(FX + String.join(";", stack));
        out.append(Component.literal(text).withStyle(ns));
    }

    private static Component gifChar(String arg, ChatCfg cfg, Style st) {
        String name = arg;
        int size = cfg.gifSize;
        int c = arg.indexOf(',');
        if (c >= 0) {
            name = arg.substring(0, c);
            try { size = Integer.parseInt(arg.substring(c + 1).trim()); } catch (NumberFormatException ignored) { size = cfg.gifSize; }
        }
        size = Math.max(1, Math.min(64, size));
        return Component.literal(String.valueOf((char) (GIF_BASE + size)))
                .withStyle(Style.EMPTY.withFont(GIF_FONT).withInsertion(GIF + ChatGifs.clean(name)));
    }

    /** Add a base effect spec ("shake=1;glitch") to every text part of a message (used by rules). */
    public static Component wrap(Component msg, String spec) {
        if (spec == null || spec.isBlank()) return msg;
        List<Object[]> segs = new ArrayList<>();
        msg.visit((st, s) -> { segs.add(new Object[]{st, s}); return Optional.empty(); }, Style.EMPTY);
        MutableComponent out = Component.empty();
        String add = spec.trim();
        for (Object[] sg : segs) {
            Style st = (Style) sg[0];
            String s = (String) sg[1];
            if (s.isEmpty()) continue;
            String ins = st.getInsertion();
            if (ins != null && ins.startsWith(GIF)) { out.append(Component.literal(s).withStyle(st)); continue; }
            String merged = ins != null && ins.startsWith(FX) ? add + ";" + ins.substring(FX.length()) : add;
            out.append(Component.literal(s).withStyle(st.withInsertion(FX + merged)));
        }
        return out;
    }

    // ------------------------------------------------------------------ detection

    public static boolean isFx(Style st) {
        String i = st.getInsertion();
        return i != null && (i.startsWith(FX) || i.startsWith(GIF));
    }

    public static boolean hasFx(FormattedCharSequence seq) {
        boolean[] f = {false};
        seq.accept((idx, st, cp) -> { if (isFx(st)) { f[0] = true; return false; } return true; });
        return f[0];
    }

    private static Fx[] fxOf(String spec) {
        return parsed.computeIfAbsent(spec, sp -> {
            List<Fx> l = new ArrayList<>();
            for (String part : sp.split(";")) {
                part = part.trim();
                if (part.isEmpty()) continue;
                String name = part, arg = "";
                int eq = part.indexOf('=');
                if (eq >= 0) { name = part.substring(0, eq).trim(); arg = part.substring(eq + 1).trim(); }
                name = name.toLowerCase(Locale.ROOT);
                if (name.equals("static")) name = "glitch";
                if (!NAMES.contains(name)) continue;
                float k = 1f;
                int rgb = -1;
                for (String a : arg.split(",")) {
                    a = a.trim();
                    if (a.isEmpty()) continue;
                    if (a.startsWith("#")) {
                        try { rgb = Integer.parseInt(a.substring(1), 16) & 0xFFFFFF; } catch (NumberFormatException ignored) { rgb = -1; }
                    } else {
                        try { k = Float.parseFloat(a); } catch (NumberFormatException ignored) { k = 1f; }
                    }
                }
                l.add(new Fx(name, Mth.clamp(k, 0f, 8f), rgb));
            }
            return l.toArray(new Fx[0]);
        });
    }

    // ------------------------------------------------------------------ drawing

    private static float hash(int a, int b) {
        int h = a * 374761393 + b * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (float) 0x1000000;
    }

    private static int hsv(float h) {
        return java.awt.Color.HSBtoRGB(h - (float) Math.floor(h), 0.65f, 1f) & 0xFFFFFF;
    }

    /**
     * Draw one line character by character with its effects. Coordinates are local (0,0 = line start), the caller
     * already applied position and text scale. {@code argb} is the normal text colour.
     */
    public static void drawLine(GuiGraphics g, Font font, FormattedCharSequence seq, int argb, boolean shadow, ChatCfg cfg, int seedBase) {
        List<int[]> cps = new ArrayList<>();       // {cp}
        List<Style> styles = new ArrayList<>();
        seq.accept((idx, st, cp) -> { cps.add(new int[]{cp}); styles.add(st); return true; });
        int n = cps.size();
        float[] xs = new float[n + 1];
        float x = 0f;
        for (int i = 0; i < n; i++) {
            xs[i] = x;
            x += font.getSplitter().stringWidth(FormattedCharSequence.codepoint(cps.get(i)[0], styles.get(i)));
        }
        xs[n] = x;
        float t = (System.nanoTime() / 1_000_000L % 100_000_000L) / 1000f * cfg.fxSpeed;
        float alphaBase = ((argb >>> 24) & 255) / 255f;
        int baseRgb = argb & 0xFFFFFF;
        float strength = cfg.fxStrength;
        for (int i = 0; i < n; i++) {
            int cp = cps.get(i)[0];
            Style st = styles.get(i);
            String ins = st.getInsertion();
            if (ins != null && ins.startsWith(GIF)) {
                float size = cp - GIF_BASE;
                String name = ins.substring(GIF.length());
                float h = size * ChatGifs.aspect(name);
                ChatGifs.draw(g, name, xs[i], (font.lineHeight - 1 - h) / 2f, size, h, alphaBase);
                continue;
            }
            if (cp == ' ') continue;
            Fx[] fx = ins != null && ins.startsWith(FX) ? fxOf(ins.substring(FX.length())) : null;
            float dx = 0f, dy = 0f, alpha = alphaBase;
            int rgb = -1;
            int glyph = cp;
            boolean glitchOn = false, ghost = false, glowOn = false, blood = false;
            float gk = 0f, glowK = 0f, ghostK = 0f, bloodK = 0f;
            int glowRgb = -1, bloodRgb = -1;
            if (fx != null) {
                int step14 = (int) (t * 14f), step30 = (int) (t * 30f);
                for (Fx f : fx) {
                    float k = f.k * strength;
                    switch (f.name) {
                        case "shake" -> {
                            dx += (hash(step30, i + seedBase) * 2f - 1f) * 0.9f * k;
                            dy += (hash(step30 + 77, i + seedBase) * 2f - 1f) * 0.9f * k;
                        }
                        case "wave" -> dy += Mth.sin(t * 6f + i * 0.55f) * 1.6f * k;
                        case "glitch" -> {
                            int st12 = (int) (t * 12f);
                            if (hash(st12, i + seedBase) < 0.22f * Math.min(2f, k) + 0.05f) {
                                dx += (hash(st12 + 5, i) * 2f - 1f) * 2.5f * k;
                                glitchOn = true;
                                gk = Math.max(gk, k);
                            }
                        }
                        case "flicker" -> {
                            int s = (int) (t * 16f);
                            if (hash(s, i * 3 + seedBase) < 0.35f * Math.min(2f, k)) alpha *= 0.12f + 0.5f * hash(s + 9, i);
                        }
                        case "blood" -> {
                            rgb = f.rgb >= 0 ? f.rgb : 0xB80000;
                            blood = true;
                            bloodK = k;
                            bloodRgb = rgb;
                        }
                        case "glow" -> {
                            glowOn = true;
                            glowK = k;
                            glowRgb = f.rgb >= 0 ? f.rgb : 0xFFFFFF;
                        }
                        case "ghost" -> { ghost = true; ghostK = k; }
                        case "corrupt" -> {
                            if (hash(step14, i * 7 + seedBase) < 0.10f * Math.min(3f, k)) glyph = JUNK.charAt((int) (hash(step14 + 3, i) * JUNK.length()));
                        }
                        case "rainbow" -> rgb = hsv(t * 0.5f * Math.max(0.2f, f.k) + i * 0.07f);
                        case "robot" -> {
                            rgb = f.rgb >= 0 ? f.rgb : 0x4DFFD2;
                            float scan = ((t * 8f * Math.max(0.3f, f.k)) % (n + 10)) - 4f;
                            float d = Math.abs(i - scan);
                            if (d < 3f) rgb = 0xFFFFFF;
                            int s = (int) (t * 10f);
                            if (hash(s, i * 5 + seedBase) < 0.04f * k) glyph = JUNK.charAt((int) (hash(s + 1, i) * JUNK.length()));
                        }
                        case "color" -> { if (f.rgb >= 0) rgb = f.rgb; }
                        default -> { }
                    }
                }
            }
            Style cs = st;
            if (rgb >= 0) cs = cs.withColor(TextColor.fromRgb(rgb));
            int a255 = Mth.clamp(Math.round(alpha * 255f), 4, 255);
            int col = (a255 << 24) | baseRgb;
            float px = xs[i] + dx, py = dy;
            if (ghost) {
                float o = Mth.sin(t * 3f + i * 0.4f) * 1.6f * ghostK;
                int ga = (Mth.clamp(Math.round(alpha * 90f), 4, 255) << 24) | baseRgb;
                g.drawString(font, FormattedCharSequence.codepoint(glyph, cs), px + o, py, ga, false);
                g.drawString(font, FormattedCharSequence.codepoint(glyph, cs), px - o, py, ga, false);
            }
            if (glowOn) {
                float pulse = 0.7f + 0.3f * Mth.sin(t * 4f + i * 0.3f);
                int ga = (Mth.clamp(Math.round(alpha * 70f * pulse * Math.min(2f, glowK)), 4, 255) << 24) | glowRgb;
                Style gs = st.withColor(TextColor.fromRgb(glowRgb));
                FormattedCharSequence gc = FormattedCharSequence.codepoint(glyph, gs);
                float r = 0.8f + 0.4f * glowK;
                g.drawString(font, gc, px - r, py, ga, false);
                g.drawString(font, gc, px + r, py, ga, false);
                g.drawString(font, gc, px, py - r, ga, false);
                g.drawString(font, gc, px, py + r, ga, false);
            }
            if (glitchOn) {
                int ca = (Mth.clamp(Math.round(alpha * 170f), 4, 255) << 24);
                float o = 1.2f * gk;
                g.drawString(font, FormattedCharSequence.codepoint(glyph, st.withColor(TextColor.fromRgb(0x00FFFF))), px - o, py, ca | 0x00FFFF, false);
                g.drawString(font, FormattedCharSequence.codepoint(glyph, st.withColor(TextColor.fromRgb(0xFF00FF))), px + o, py, ca | 0xFF00FF, false);
            }
            g.drawString(font, FormattedCharSequence.codepoint(glyph, cs), px, py, col, shadow);
            if (blood && hash(i, seedBase + 11) < 0.45f) {
                float w = xs[i + 1] - xs[i];
                float cyc = (t * (1.2f + 2f * hash(i, 5)) + hash(i, 9) * 10f) % 6f;
                float len = Math.min(cyc, 4f) * (1f + 0.5f * bloodK) * 1.1f;
                float fade = cyc > 4f ? Math.max(0f, 1f - (cyc - 4f) / 2f) : 1f;
                if (len > 0.5f) {
                    int da = (Mth.clamp(Math.round(alpha * 255f * fade * 0.9f), 0, 255) << 24) | bloodRgb;
                    int bx = Math.round(px + w / 2f);
                    g.fill(bx, Math.round(py + font.lineHeight - 1), bx + 1, Math.round(py + font.lineHeight - 1 + len), da);
                }
            }
        }
    }

    /** the Minecraft font, for helpers */
    static Font font() { return Minecraft.getInstance().font; }
}
