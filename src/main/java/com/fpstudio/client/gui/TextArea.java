package com.fpstudio.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** A small multi-line text editor for NPC scripts (no selection except Ctrl+A; copy/cut/paste of everything or a line). */
final class TextArea {
    int x, y, w, h;
    boolean focus;
    private final List<StringBuilder> lines = new ArrayList<>();
    private int row, col, scroll;
    private boolean all;
    private Consumer<String> onChange = s -> {};

    TextArea() { lines.add(new StringBuilder()); }

    void onChange(Consumer<String> c) { onChange = c; }

    String text() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) { if (i > 0) b.append('\n'); b.append(lines.get(i)); }
        return b.toString();
    }

    void set(String s) {
        lines.clear();
        for (String l : (s == null ? "" : s).split("\\r?\\n", -1)) lines.add(new StringBuilder(l));
        if (lines.isEmpty()) lines.add(new StringBuilder());
        row = 0; col = 0; scroll = 0; all = false;
    }

    boolean hit(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }

    private int visibleRows(Font f) { return Math.max(1, (h - 6) / (f.lineHeight + 1)); }

    private void changed() { onChange.accept(text()); }

    void render(GuiGraphics g, Font f, int mx, int my) {
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, focus ? 0xFFB8A8FF : 0xFF55507A);
        g.fill(x, y, x + w, y + h, 0xFF0E0D16);
        int lh = f.lineHeight + 1, vis = visibleRows(f);
        if (row < scroll) scroll = row;
        if (row >= scroll + vis) scroll = row - vis + 1;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, lines.size() - vis)));
        g.enableScissor(x, y, x + w, y + h);
        for (int i = 0; i < vis && scroll + i < lines.size(); i++) {
            String ln = lines.get(scroll + i).toString();
            int ty = y + 3 + i * lh;
            int color = ln.trim().startsWith("#") ? 0xFF7C7A96 : colorFor(ln);
            if (all) g.fill(x + 2, ty - 1, x + w - 2, ty + lh - 1, 0x663A6FD8);
            g.drawString(f, ln, x + 4, ty, color, false);
            if (focus && scroll + i == row && (System.currentTimeMillis() / 450) % 2 == 0) {
                int cx = x + 4 + f.width(ln.substring(0, Math.min(col, ln.length())));
                g.fill(cx, ty - 1, cx + 1, ty + f.lineHeight, 0xFFFFFFFF);
            }
        }
        g.disableScissor();
        if (lines.size() > vis) {
            int th = Math.max(8, h * vis / lines.size());
            int ty = y + (h - th) * scroll / Math.max(1, lines.size() - vis);
            g.fill(x + w - 3, ty, x + w - 1, ty + th, 0xFF8A82C8);
        }
    }

    private static int colorFor(String ln) {
        String t = ln.trim().toLowerCase();
        int sp = t.indexOf(' ');
        String c = sp < 0 ? t : t.substring(0, sp);
        return switch (c) {
            case "say", "saywait", "whisper", "title", "actionbar" -> 0xFFFFE9A8;
            case "walk", "walkto", "follow", "tp", "tpplayer", "stop", "sit", "stand", "turn", "face", "look" -> 0xFF9FE0C0;
            case "anim", "animwait", "stopanim" -> 0xFFB6C8FF;
            case "if", "goto", "label", "call", "set", "add", "end" -> 0xFFFFA8D0;
            case "wait", "waitinteract", "waitnear" -> 0xFFC8C8C8;
            default -> 0xFFE6E4F4;
        };
    }

    void click(double mx, double my, Font f) {
        int lh = f.lineHeight + 1;
        int r = scroll + (int) ((my - y - 3) / lh);
        row = Math.max(0, Math.min(lines.size() - 1, r));
        String ln = lines.get(row).toString();
        int px = (int) mx - x - 4, c = 0;
        while (c < ln.length() && f.width(ln.substring(0, c + 1)) <= px + 2) c++;
        col = c;
        all = false;
    }

    void scrollBy(int d) { scroll = Math.max(0, Math.min(Math.max(0, lines.size() - 3), scroll + d)); }

    void insert(String s) {
        if (all) { set(""); }
        for (char c : s.toCharArray()) {
            if (c == '\n') newline();
            else if (c >= 32 && c != 127) { lines.get(row).insert(col, c); col++; }
        }
        changed();
    }

    private void newline() {
        StringBuilder cur = lines.get(row);
        StringBuilder tail = new StringBuilder(cur.substring(col));
        cur.setLength(col);
        lines.add(row + 1, tail);
        row++;
        col = 0;
    }

    boolean charTyped(char c) {
        if (!focus) return false;
        if (c >= 32 && c != 127) { insert(String.valueOf(c)); return true; }
        return false;
    }

    boolean key(int key, int mods) {
        if (!focus) return false;
        boolean ctrl = (mods & GLFW.GLFW_MOD_CONTROL) != 0;
        var kb = Minecraft.getInstance().keyboardHandler;
        if (ctrl && key == GLFW.GLFW_KEY_A) { all = true; return true; }
        if (ctrl && key == GLFW.GLFW_KEY_C) { kb.setClipboard(all ? text() : lines.get(row).toString()); return true; }
        if (ctrl && key == GLFW.GLFW_KEY_X) {
            kb.setClipboard(all ? text() : lines.get(row).toString());
            if (all) set(""); else { lines.remove(row); if (lines.isEmpty()) lines.add(new StringBuilder()); row = Math.min(row, lines.size() - 1); col = 0; }
            changed();
            return true;
        }
        if (ctrl && key == GLFW.GLFW_KEY_V) { insert(kb.getClipboard().replace("\r", "")); return true; }
        switch (key) {
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> { if (all) set(""); newline(); changed(); }
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (all) { set(""); }
                else if (col > 0) { lines.get(row).deleteCharAt(col - 1); col--; }
                else if (row > 0) { int pc = lines.get(row - 1).length(); lines.get(row - 1).append(lines.get(row)); lines.remove(row); row--; col = pc; }
                changed();
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (all) { set(""); }
                else if (col < lines.get(row).length()) lines.get(row).deleteCharAt(col);
                else if (row + 1 < lines.size()) { lines.get(row).append(lines.get(row + 1)); lines.remove(row + 1); }
                changed();
            }
            case GLFW.GLFW_KEY_LEFT -> { all = false; if (col > 0) col--; else if (row > 0) { row--; col = lines.get(row).length(); } }
            case GLFW.GLFW_KEY_RIGHT -> { all = false; if (col < lines.get(row).length()) col++; else if (row + 1 < lines.size()) { row++; col = 0; } }
            case GLFW.GLFW_KEY_UP -> { all = false; if (row > 0) { row--; col = Math.min(col, lines.get(row).length()); } }
            case GLFW.GLFW_KEY_DOWN -> { all = false; if (row + 1 < lines.size()) { row++; col = Math.min(col, lines.get(row).length()); } }
            case GLFW.GLFW_KEY_HOME -> { all = false; col = 0; }
            case GLFW.GLFW_KEY_END -> { all = false; col = lines.get(row).length(); }
            case GLFW.GLFW_KEY_TAB -> insert("  ");
            default -> { return key != GLFW.GLFW_KEY_ESCAPE; }
        }
        return true;
    }
}
