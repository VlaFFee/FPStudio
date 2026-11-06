package com.fpstudio.client.gui;

import com.fpstudio.Config;
import com.fpstudio.client.Actions;
import com.fpstudio.client.Playback;
import com.fpstudio.client.StudioFx;
import com.fpstudio.client.npc.NpcLibrary;
import com.fpstudio.client.npc.NpcRuntime;
import com.fpstudio.model.Actor;
import com.fpstudio.model.Clip;
import com.fpstudio.model.ClipIO;
import com.fpstudio.model.Easing;
import com.fpstudio.model.EventKey;
import com.fpstudio.model.EventType;
import com.fpstudio.model.Keyframe;
import com.fpstudio.model.Presets;
import com.fpstudio.model.Templates;
import com.fpstudio.model.Track;
import com.fpstudio.npc.GeoModel;
import com.fpstudio.npc.NpcAsset;
import com.fpstudio.npc.NpcInstance;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The animation studio. The world stays visible and live behind it (the game is not paused), so the hands and camera
 * you edit are the real first-person view. Panels glide in with staggered easing; timeline zoom/pan is smoothed.
 */
public class StudioScreen extends Screen {
    private static final int TOP_H = 26, PANEL_W = 176;
    private static final int C_BG = 0xE8121118, C_LINE = 0xFF2C2A3A, C_TEXT = 0xFFECEAF5, C_DIM = 0xFF8F8BA6,
            C_ACC = 0xFF9A6BFF, C_ACC2 = 0xFF4FE0D0, C_WARN = 0xFFFF6B7A, C_FIELD = 0xFF1D1B29, C_FIELD_H = 0xFF2A2740;
    private static final int[] TRACK_COL = {0xFFFF8A5C, 0xFF5CB8FF, 0xFFB57BFF, 0xFF5CE08A};
    private static final String[] TRACK_KEY = {"track.right", "track.left", "track.camera", "track.events"};
    private static final String[] ARM_CH = {"x", "y", "z", "pitch", "yaw", "roll"};
    private static final String[] CAM_CH = {"x", "y", "z", "pitch", "yaw", "roll", "fov"};

    private record Btn(int x, int y, int w, int h, boolean panel, Runnable l, Runnable r) {}

    private record Sld(String id, int x, int y, int w, int h, boolean panel, float val, float min, float max,
                       float step, Consumer<Float> set) {}

    private enum Drag { NONE, SLIDER, RESIZE }

    private static final class TextField {
        String text = "";
        boolean focus;
        final int max;
        int x, y, w, h;

        TextField(int max) { this.max = max; }

        void type(char c) { if (text.length() < max && c >= 32 && c != 127) text += c; }

        void back() { if (!text.isEmpty()) text = text.substring(0, text.length() - 1); }

        boolean hit(double mx, double my) { return w > 0 && mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    // ---- state
    private float open, dt;
    private boolean closing, launching, loadWasOpen;
    private long lastNs = System.nanoTime();
    private int mx, my;
    private double gx = Double.NaN, gy;
    private Object sel;
    private Object syncedSel;
    private int selTrack, tab;
    private int botH = 150;
    private record Extra(String label, Track track, String[] ch, float[] unit, String bone) {}
    private final List<Extra> extras = new ArrayList<>();
    private String selActorId;
    private String selBone = "";
    private final Timeline tl = new Timeline(new Timeline.Host() {
        public Clip clip() { return Playback.clip; }
        public void pushUndo() { StudioScreen.this.pushUndo(); }
        public void toast(String s) { StudioScreen.this.toast(s); }
        public net.minecraft.client.gui.Font font() { return StudioScreen.this.font; }
        public void scissor(GuiGraphics g, int a, int b, int c, int d) { StudioScreen.this.scissor(g, a, b, c, d); }
        public void changed() { StudioScreen.this.selChanged(); }
        public int vw() { return StudioScreen.this.vw; }
        public int vh() { return StudioScreen.this.vh; }
        public int extraCount() { return extras.size(); }
        public String extraLabel(int i) { return extras.get(i).label(); }
        public Track extraTrack(int i) { return i >= 0 && i < extras.size() ? extras.get(i).track() : null; }
        public String extraChannel(int i, int c) { return extras.get(i).ch()[c]; }
        public float extraUnit(int i, int c) { return extras.get(i).unit()[c]; }
    });
    private float pScroll, pScrollT;
    private int pContentH, pX, cTop, cBot;
    private Drag drag = Drag.NONE;
    private Sld activeSld;
    private float dragVal0;
    private double dragMx0;
    private boolean showGrid = true, showLoad, previewEvents;
    private float loadScroll;
    private List<String> clipNames = List.of();
    private final TextField nameField = new TextField(32), argField = new TextField(64), boneFilter = new TextField(24), numField = new TextField(14);
    private Sld numSld;
    private boolean numFresh;
    private final Deque<String> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private String toast = "";
    private float toastT;
    private long lastClickNs;
    private String lastClickId = "";
    private final Map<String, Float> anims = new HashMap<>();
    private final List<Btn> btns = new ArrayList<>();
    private final List<Sld> slds = new ArrayList<>();
    private String tip;
    private int popX, popY, popW, popH;
    // recording + templates + smoothing
    private boolean rec, tplRest = true, tplAtEnd;
    private float recAcc, contentT, dispTime;
    private final float[] recV = new float[7];
    private float us = 1f;     // effective interface scale (config multiplier on top of Minecraft's GUI Scale)
    private int vw, vh;        // virtual (scaled) screen size all layout code works in

    public StudioScreen() { super(Component.translatable("fpstudio.gui.title")); }

    // ======================================================== lifecycle

    @Override
    protected void init() {
        Playback.editing = true;
        Playback.stage = false;
        if (com.fpstudio.client.MoveRecorder.pendingUndo != null) {
            undo.push(com.fpstudio.client.MoveRecorder.pendingUndo);
            com.fpstudio.client.MoveRecorder.pendingUndo = null;
        }
        if (com.fpstudio.client.MoveRecorder.justRecorded) {
            com.fpstudio.client.MoveRecorder.justRecorded = false;
            tab = 2;
            toast(t("toast.cam_recorded"));
        }
        Presets.installIfMissing();
        nameField.text = clip().name;
        clipNames = ClipIO.list();
    }

    @Override
    public void removed() {
        NpcRuntime.hlActor = "";
        NpcRuntime.hlBone = "";
        Playback.liveTrack = -1;
        Playback.editing = false;
        if (!launching) Playback.stop(false);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public boolean shouldCloseOnEsc() { return false; }

    @Override
    public void onClose() { closing = true; }

    @Override
    public void renderBackground(GuiGraphics g) { /* keep the live world visible */ }

    private Clip clip() { return Playback.clip; }

    private static String t(String k) { return I18n.get("fpstudio.gui." + k); }

    // ======================================================== helpers

    /** Config multiplier on top of Minecraft's GUI Scale, never so large that the layout (min ~620 px wide) breaks. */
    private float effectiveScale() {
        float v = Config.UI_SCALE.get().floatValue();
        return Mth.clamp(Math.min(v, this.width / 620f), 0.35f, 1.6f);
    }

    private void changeUiScale(float delta) {
        float v = Mth.clamp(Math.round((Config.UI_SCALE.get().floatValue() + delta) * 20f) / 20f, 0.4f, 1.6f);
        Config.UI_SCALE.set((double) v);
        Config.SPEC.save();
        toast(t("toast.ui_scale") + " " + Math.round(v * 100) + "%");
    }

    /** Scissor takes real GUI coordinates and ignores the pose stack, so scale the rectangle ourselves. */
    private void scissor(GuiGraphics g, int x1, int y1, int x2, int y2) {
        g.enableScissor(Math.round(x1 * us), Math.round(y1 * us), Math.round(x2 * us), Math.round(y2 * us));
    }

    private boolean inside(int x, int y, int w, int h) { return mx >= x && mx < x + w && my >= y && my < y + h; }

    private boolean inContent() { return mx >= pX && mx < pX + PANEL_W && my >= cTop && my < cBot; }

    private float anim(String id, boolean on) {
        float a = anims.getOrDefault(id, 0f);
        a += ((on ? 1f : 0f) - a) * (1f - (float) Math.exp(-dt * 16f));
        anims.put(id, a);
        return a;
    }

    private static int lerpC(int a, int b, float t) {
        t = Mth.clamp(t, 0f, 1f);
        int out = 0;
        for (int s = 0; s <= 24; s += 8) {
            int ca = (a >> s) & 255, cb = (b >> s) & 255;
            out |= ((int) (ca + (cb - ca) * t) & 255) << s;
        }
        return out;
    }

    private static int alpha(int c, float a) {
        return (c & 0xFFFFFF) | ((int) (Mth.clamp(a, 0f, 1f) * 255f) << 24);
    }

    private static void rr(GuiGraphics g, int x, int y, int w, int h, int c) {
        g.fill(x + 1, y, x + w - 1, y + h, c);
        g.fill(x, y + 1, x + w, y + h - 1, c);
    }

    private void txt(GuiGraphics g, String s, int x, int y, int c) { g.drawString(font, s, x, y, c, false); }

    private void ctr(GuiGraphics g, String s, int cx, int y, int c) { g.drawString(font, s, cx - font.width(s) / 2, y, c, false); }

    private int wrap(GuiGraphics g, String s, int x, int y, int w, int c) {
        for (FormattedCharSequence line : font.split(Component.literal(s), w)) {
            g.drawString(font, line, x, y, c, false);
            y += 10;
        }
        return y;
    }

    private void toast(String s) { toast = s; toastT = 2.4f; }

    private void button(GuiGraphics g, String id, String label, int x, int y, int w, int h, boolean active,
                        boolean panel, String tipText, Runnable l, Runnable r) {
        boolean hov = inside(x, y, w, h) && (!panel || inContent());
        float a = anim("b:" + id, hov);
        float on = anim("a:" + id, active);
        int base = lerpC(lerpC(C_FIELD, C_FIELD_H, a), lerpC(0xFF3A2F66, 0xFF4B3C85, a), on);
        rr(g, x, y, w, h, base);
        if (on > 0.02f) {
            int half = (int) ((w - 4) / 2f * on);
            g.fill(x + w / 2 - half, y + h - 2, x + w / 2 + half, y + h - 1, C_ACC);
        }
        ctr(g, label, x + w / 2, y + (h - 8) / 2, lerpC(C_DIM, C_TEXT, Math.max(a, on)));
        if (hov && tipText != null) tip = tipText;
        btns.add(new Btn(x, y, w, h, panel, l, r));
    }

    private void iconButton(GuiGraphics g, String id, int icon, int x, int y, int w, int h, boolean active, String tipText, Runnable l) {
        boolean hov = inside(x, y, w, h);
        float a = anim("b:" + id, hov);
        rr(g, x, y, w, h, active ? lerpC(0xFF3A2F66, 0xFF4B3C85, a) : lerpC(C_FIELD, C_FIELD_H, a));
        int col = lerpC(C_DIM, C_TEXT, Math.max(a, active ? 1f : 0f));
        int cx = x + w / 2, cy = y + h / 2;
        switch (icon) {
            case 0 -> { for (int i = 0; i < 5; i++) g.fill(cx - 3 + i, cy - 4 + i, cx - 2 + i, cy + 5 - i, col); } // play
            case 1 -> { g.fill(cx - 3, cy - 4, cx - 1, cy + 5, col); g.fill(cx + 1, cy - 4, cx + 3, cy + 5, col); } // pause
            case 2 -> g.fill(cx - 3, cy - 3, cx + 4, cy + 4, col);                                                    // stop
            default -> { g.fill(cx - 4, cy - 4, cx - 2, cy + 5, col); for (int i = 0; i < 4; i++) g.fill(cx - 1 + i, cy - i, cx + i, cy + i + 1, col); } // |<
        }
        if (hov && tipText != null) tip = tipText;
        btns.add(new Btn(x, y, w, h, false, l, null));
    }

    private static String fmt(float v) {
        float a = Math.abs(v);
        return a >= 10000f ? String.format(Locale.ROOT, "%.0f", v) : a >= 100f ? String.format(Locale.ROOT, "%.1f", v) : String.format(Locale.ROOT, "%.2f", v);
    }

    /** Slider with a value box on the right: click the box and type any number (it is not limited to the slider range). */
    private int sliderRow(GuiGraphics g, String id, String label, float val, float min, float max, float step,
                          Consumer<Float> set, int x, int y, int w, int accent) {
        int h = 13, bw = 46, tw = w - bw - 3;
        boolean hov = inside(x, y, tw, h) && inContent();
        boolean act = activeSld != null && activeSld.id().equals(id);
        float a = anim("s:" + id, hov || act);
        rr(g, x, y, tw, h, lerpC(C_FIELD, C_FIELD_H, a));
        float shownV = act ? val : anims.getOrDefault("v:" + id, val);
        if (!act) {
            shownV += (val - shownV) * (1f - (float) Math.exp(-dt * 18f));
            if (Math.abs(val - shownV) < (max - min) * 0.0005f) shownV = val;
        }
        anims.put("v:" + id, shownV);
        float f = Mth.clamp((shownV - min) / (max - min), 0f, 1f);
        int fillCol = alpha(accent, 0.45f + 0.3f * a);
        if (min < 0 && max > 0) {
            float z = -min / (max - min);
            int xa = x + (int) (Math.min(f, z) * tw), xb = x + (int) (Math.max(f, z) * tw);
            g.fill(xa, y + 1, Math.max(xb, xa + 1), y + h - 1, fillCol);
        } else {
            g.fill(x, y + 1, x + Math.max(1, (int) (f * tw)), y + h - 1, fillCol);
        }
        int hx = Mth.clamp(x + (int) (f * tw), x + 1, x + tw - 2);
        g.fill(hx - 1, y, hx + 1, y + h, lerpC(C_DIM, C_TEXT, a));
        txt(g, font.plainSubstrByWidth(label, tw - 6, false), x + 4, y + 3, C_TEXT);

        // value box
        int bx = x + w - bw;
        Sld sd = new Sld(id, x, y, tw, h, true, val, min, max, step, set);
        boolean editing = numField.focus && numSld != null && numSld.id().equals(id);
        boolean bh = inside(bx, y, bw, h) && inContent();
        float ba = anim("vb:" + id, bh || editing);
        rr(g, bx, y, bw, h, lerpC(0xFF14131E, 0xFF2A2740, ba));
        if (editing) {
            g.fill(bx + 2, y + h - 2, bx + bw - 2, y + h - 1, C_ACC);
            String shown = numField.text + ((System.currentTimeMillis() / 450) % 2 == 0 ? "_" : "");
            txt(g, font.plainSubstrByWidth(shown, bw - 6, true), bx + 3, y + 3, C_TEXT);
        } else {
            String v = fmt(val);
            txt(g, font.plainSubstrByWidth(v, bw - 6, false), bx + bw - 3 - Math.min(font.width(v), bw - 6), y + 3, lerpC(C_DIM, C_TEXT, ba));
        }
        if (bh && !editing) tip = t("tip.type_value");
        slds.add(sd);
        btns.add(new Btn(bx, y, bw, h, true, () -> beginNum(sd), null));
        return y + h + 3;
    }

    // ---- typed numbers (any magnitude, not limited to the slider range)

    private void beginNum(Sld sd) {
        commitNum();
        numSld = sd;
        numField.text = trimNum(sd.val());
        numField.focus = true;
        numFresh = true;
        nameField.focus = false;
        argField.focus = false;
        boneFilter.focus = false;
    }

    private static String trimNum(float v) {
        String s = String.format(Locale.ROOT, "%.4f", v);
        if (s.indexOf('.') >= 0) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s.isEmpty() || s.equals("-0") ? "0" : s;
    }

    private void commitNum() {
        if (!numField.focus || numSld == null) { numField.focus = false; return; }
        numField.focus = false;
        Sld sd = numSld;
        numSld = null;
        try {
            String tx = numField.text.trim().replace(',', '.');
            if (tx.isEmpty() || tx.equals("-") || tx.equals(".")) return;
            float v = Float.parseFloat(tx);
            if (Float.isNaN(v) || Float.isInfinite(v)) return;
            if (sd.min() >= 0f && v < sd.min()) v = sd.min();
            pushUndo();
            sd.set().accept(v);
        } catch (NumberFormatException ex) {
            toast(t("toast.bad_number"));
        }
    }

    private void cancelNum() {
        numField.focus = false;
        numSld = null;
    }

    private int toggleRow(GuiGraphics g, String id, String label, boolean on, Runnable toggle, int x, int y, int w) {
        button(g, id, label + ": " + (on ? t("on") : t("off")), x, y, w, 13, on, true, null, toggle, null);
        return y + 16;
    }

    // ======================================================== undo

    private void pushUndo() {
        undo.push(ClipIO.toJson(clip()));
        while (undo.size() > 60) undo.removeLast();
        redo.clear();
    }

    private void swapClip(String json) {
        syncSel();
        int pt = tl.primary != null ? tl.primaryTrack : -1, pi = tl.primaryIndex();
        Playback.clip = ClipIO.fromJson(json);
        tl.clearSelection();
        rebuildExtras();
        tl.reselect(pt, pi);
        syncSel();
        nameField.text = clip().name;
    }

    private void doUndo() {
        if (undo.isEmpty()) return;
        redo.push(ClipIO.toJson(clip()));
        swapClip(undo.pop());
    }

    private void doRedo() {
        if (redo.isEmpty()) return;
        undo.push(ClipIO.toJson(clip()));
        swapClip(redo.pop());
    }

    // ======================================================== selection glue

    private Actor selActor() { return selActorId == null ? null : clip().actor(selActorId); }

    private void rebuildExtras() {
        extras.clear();
        Actor a = selActor();
        if (a == null) { selActorId = null; return; }
        extras.add(new Extra(a.id + " - " + t("npc.move"), a.move,
                new String[]{t("ch.x"), t("ch.y"), t("ch.z"), t("ch.yaw")}, new float[]{100f, 100f, 100f, 1f}, null));
        NpcAsset as = NpcLibrary.get(a.asset);
        if (as == null) return;
        String[] bc = {t("ch.x"), t("ch.y"), t("ch.z"), t("ch.rx"), t("ch.ry"), t("ch.rz"), t("ch.scale")};
        float[] bu = {1f, 1f, 1f, 1f, 1f, 1f, 50f};
        for (String n : as.model.name) {
            Track tr = a.bones.get(n);
            if (tr == null || (tr.keys.isEmpty() && !n.equals(selBone))) continue;
            extras.add(new Extra(a.id + " / " + n, tr, bc, bu, n));
        }
    }

    private void selectActor(String id) {
        selActorId = id;
        selBone = "";
        tl.clearSelection();
        rebuildExtras();
    }

    private void selectBone(String bone) {
        Actor a = selActor();
        if (a == null) return;
        selBone = bone;
        a.bone(bone);
        rebuildExtras();
        for (int i = 0; i < extras.size(); i++) if (bone.equals(extras.get(i).bone())) { tl.focusGroup(4 + i); break; }
        syncSel();
    }

    private void placeAtPlayer(Actor a) {
        var p = Minecraft.getInstance().player;
        if (p == null) return;
        float yaw = p.getYRot();
        double r = Math.toRadians(yaw);
        a.x = (float) (p.getX() - Math.sin(r) * 2.0);
        a.y = (float) p.getY();
        a.z = (float) (p.getZ() + Math.cos(r) * 2.0);
        a.yaw = Mth.wrapDegrees(yaw + 180f);
    }

    private void addActor(NpcAsset asset) {
        pushUndo();
        Actor a = new Actor();
        a.asset = asset.name;
        String base = asset.name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        String id = base;
        int n = 1;
        while (clip().actor(id) != null) id = base + (++n);
        a.id = id;
        placeAtPlayer(a);
        clip().actors.add(a);
        selectActor(a.id);
        toast(t("npc.added") + " " + id);
    }

    /** Hide / show a bone. Left click: the bone and everything attached to it; right click: only this bone. */
    private void toggleHide(Actor a, GeoModel m, int bone, boolean tree) {
        pushUndo();
        boolean hide = !a.hidden.contains(m.name[bone]);
        for (int i = 0; i < m.bones; i++) {
            boolean under = i == bone;
            for (int p = m.parent[i]; tree && !under && p >= 0; p = m.parent[p]) if (p == bone) under = true;
            if (!under) continue;
            if (hide) a.hidden.add(m.name[i]); else a.hidden.remove(m.name[i]);
        }
    }

    /** Add a HANDS event at the playhead: hide the hands if they are in view, bring them back if they are hidden. */
    private void toggleHands() {
        pushUndo();
        boolean hidden = (Playback.handHide(clip(), Playback.time, 0) + Playback.handHide(clip(), Playback.time, 1)) * 0.5f > 0.5f;
        float t0 = Math.round(Playback.time * 20f) / 20f;
        EventKey e = new EventKey(t0, EventType.HANDS, "both|" + (hidden ? "show|auto" : "hide|down"), 0.6f);
        clip().events.add(e);
        clip().sortEvents();
        if (t0 + e.dur > clip().duration) clip().duration = Math.min(600f, t0 + e.dur);
        tl.selectEvent(e);
        toast(t(hidden ? "toast.hands_show" : "toast.hands_hide"));
    }

    private void handsEventPanel(GuiGraphics g, EventKey e, int x, int w, int[] yy) {
        int y = yy[0];
        String a = e.arg == null ? "" : e.arg;
        String[] parts = a.split("\\|");
        String who = parts.length > 0 ? parts[0].trim() : "both";
        if (!who.equals("right") && !who.equals("left")) who = "both";
        final boolean fhide = parts.length < 2 || !parts[1].trim().equalsIgnoreCase("show");
        final String fwho = who;
        final String fdir = Playback.handsDir(e, fhide);
        List<String> whos = List.of("both", "right", "left");
        List<String> dirs = fhide ? List.of("down", "up", "side", "inward", "forward", "custom") : List.of("auto", "down", "up", "side", "inward", "forward", "custom");
        String dirShown = dirs.contains(fdir) ? fdir : (fhide ? "down" : "auto");
        button(g, "hwho", t("hands.who") + ": " + t("hands.who." + who), x, y, w, 14, false, true, t("tip.etype"),
                () -> { pushUndo(); e.arg = cycle(whos, fwho, 1) + "|" + (fhide ? "hide" : "show") + "|" + fdir; },
                () -> { pushUndo(); e.arg = cycle(whos, fwho, -1) + "|" + (fhide ? "hide" : "show") + "|" + fdir; });
        y += 18;
        button(g, "hstate", fhide ? t("hands.hide") : t("hands.show"), x, y, w, 14, fhide, true, t("tip.etype"),
                () -> { pushUndo(); e.arg = fwho + "|" + (fhide ? "show" : "hide") + "|" + (fhide ? "auto" : "down"); },
                () -> { pushUndo(); e.arg = fwho + "|" + (fhide ? "show" : "hide") + "|" + (fhide ? "auto" : "down"); });
        y += 18;
        button(g, "hdir", t(fhide ? "hands.to" : "hands.from") + ": " + t("hands.dir." + dirShown), x, y, w, 14, false, true, t("tip.hands_dir"),
                () -> { pushUndo(); e.arg = fwho + "|" + (fhide ? "hide" : "show") + "|" + cycle(dirs, dirShown, 1); },
                () -> { pushUndo(); e.arg = fwho + "|" + (fhide ? "hide" : "show") + "|" + cycle(dirs, dirShown, -1); });
        y += 18;
        if (dirShown.equals("custom")) {
            y = sliderRow(g, "hx", t("ch.x"), e.hx, -3f, 3f, 0.01f, v -> e.hx = v, x, y, w, C_ACC2);
            y = sliderRow(g, "hy", t("ch.y"), e.hy, -3f, 3f, 0.01f, v -> e.hy = v, x, y, w, C_ACC2);
            y = sliderRow(g, "hz", t("ch.z"), e.hz, -3f, 5f, 0.01f, v -> e.hz = v, x, y, w, C_ACC2);
            y = sliderRow(g, "hp", t("ch.pitch"), e.hp, -90f, 90f, 0.5f, v -> e.hp = v, x, y, w, C_ACC2);
            y = sliderRow(g, "hr", t("ch.roll"), e.hr, -90f, 90f, 0.5f, v -> e.hr = v, x, y, w, C_ACC2);
        }
        y = sliderRow(g, "hdur", t("hands.time"), e.dur, 0.05f, 5f, 0.05f, v -> e.dur = v, x, y, w, C_ACC);
        y = wrap(g, t("hint.hands"), x, y, w, C_DIM);
        yy[0] = y + 4;
    }

    /** Entry point from the NPC editor: a fresh scene with this model playing the animation, ready for bone edits. */
    public void editNpcAnimation(String assetName, String animName) {
        NpcAsset as = NpcLibrary.get(assetName);
        if (as == null) return;
        pushUndo();
        Clip c = new Clip();
        c.name = ClipIO.safe("edit_" + animName.replaceAll("^animation\\.", ""));
        c.loop = true;
        Actor a = new Actor();
        a.asset = as.name;
        a.id = "npc1";
        placeAtPlayer(a);
        c.actors.add(a);
        var def = as.animations.anims.get(animName);
        float len = def == null ? 2f : Math.max(0.5f, def.length);
        c.duration = len;
        EventKey e = new EventKey(0f, EventType.NPC_ANIM, a.id + "|" + animName);
        c.events.add(e);
        Playback.clip = c;
        nameField.text = c.name;
        Playback.time = 0f;
        tab = 3;
        selectActor(a.id);
        toast(t("npc.edit_ready"));
    }

    /** Bake the animation that plays at the playhead plus all manual bone edits into a new animation inside the model's folder. */
    private void exportNpcAnim(Actor a) {
        NpcAsset as = NpcLibrary.get(a.asset);
        if (as == null) return;
        EventKey ev = null;
        for (EventKey e : clip().events) {
            if (e.type != EventType.NPC_ANIM || !a.id.equals(NpcInstance.idOf(e.arg))) continue;
            if (ev == null || e.time <= Playback.time + 1e-3f) ev = e;
        }
        if (ev == null) { toast(t("npc.export_none")); return; }
        String src = NpcInstance.animOf(ev.arg);
        var def = as.animations.anims.get(src);
        float len = def != null ? def.length : (ev.dur > 0f ? ev.dur : clip().duration);
        if (len <= 0.01f) len = Math.max(0.5f, clip().duration);
        try {
            NpcInstance inst = new NpcInstance(a, as);
            com.google.gson.JsonObject baked = com.fpstudio.npc.AnimBaker.bake(inst, clip(), ev.time, len, def != null ? def.loop : 0);
            String base = src.endsWith("_edit") ? src : src + "_edit";
            Path file = com.fpstudio.npc.AnimBaker.targetFile(NpcLibrary.dir(), as.name);
            com.fpstudio.npc.AnimBaker.write(file, base, baked);
            NpcLibrary.reload();
            toast(t("npc.exported") + " " + base);
        } catch (Exception ex) {
            toast(t("npc.export_failed"));
            com.fpstudio.FPStudio.LOGGER.warn("fpstudio: export animation failed", ex);
        }
    }

    private void addCue(Actor a, String anim) {
        pushUndo();
        float t0 = Math.round(Playback.time * 20f) / 20f;
        EventKey e = new EventKey(t0, EventType.NPC_ANIM, a.id + "|" + anim);
        clip().events.add(e);
        clip().sortEvents();
        NpcAsset as = NpcLibrary.get(a.asset);
        var an = as == null ? null : as.animations.anims.get(anim);
        if (an != null && t0 + an.length > clip().duration) clip().duration = Math.min(60f, t0 + an.length);
        tl.selectEvent(e);
        Playback.play(t0, false, false);
        toast(t("npc.cue") + " " + anim);
    }

    private static String cycle(List<String> opts, String cur, int d) {
        if (opts.isEmpty()) return cur;
        int i = opts.indexOf(cur);
        i = i < 0 ? (d > 0 ? 0 : opts.size() - 1) : (i + d + opts.size()) % opts.size();
        return opts.get(i);
    }

    private List<String> actorIds() {
        List<String> l = new ArrayList<>();
        for (Actor a : clip().actors) l.add(a.id);
        return l;
    }

    private void syncSel() {
        rebuildExtras();
        sel = tl.primary;
        selTrack = tl.primary != null ? tl.primaryTrack : tl.activeTrack;
    }

    private void selChanged() {
        syncSel();
        contentT = 0f;
        if ((tab == 1 || tab == 3) && tl.hasSelection()) tab = 0;
    }

    private void clearSel() {
        tl.clearSelection();
        sel = null;
    }

    private void setTime(float t) { Playback.time = Mth.clamp(t, 0f, clip().duration); }

    private void toggleBars() {
        pushUndo();
        clip().letterbox = !clip().letterbox;
        toast(t(clip().letterbox ? "toast.bars_on" : "toast.bars_off"));
    }

    /** Edit one channel of a key from the side panel / mouse grab: keys the channel if it was not keyed yet. */
    private void setCh(Keyframe k, int track, int ch, float v) {
        k.setHas(ch, true);
        k.v[ch] = v;
    }

    private Track trackOf(int g) { return g < 3 ? clip().track(g) : extras.get(g - 4).track(); }

    private void nudgeCh(Keyframe k, int track, int ch, float delta) {
        float cur = k.has(ch) ? k.v[ch] : trackOf(track).sampleChannel(ch, k.time);
        setCh(k, track, ch, cur + delta);
    }

    // ======================================================== editing ops

    private static Keyframe mirrored(Keyframe k) {
        Keyframe m = k.copy();
        m.v[0] = -k.v[0];
        m.v[4] = -k.v[4];
        m.v[5] = -k.v[5];
        return m;
    }

    private void mirrorTrack() {
        if (selTrack > 1) return;
        pushUndo();
        Track from = clip().track(selTrack), to = clip().track(1 - selTrack);
        to.keys.clear();
        for (Keyframe k : from.keys) to.keys.add(mirrored(k));
        toast(t("toast.mirrored"));
    }

    private void resortSel() {
        if (sel instanceof Keyframe) clip().track(selTrack).sort();
        else if (sel instanceof EventKey) clip().sortEvents();
    }

    private void save() {
        try {
            clip().name = nameField.text.isBlank() ? "untitled" : nameField.text;
            var p = ClipIO.save(clip());
            nameField.text = clip().name;
            clipNames = ClipIO.list();
            toast(t("toast.saved") + " " + p.getFileName());
        } catch (Exception ex) {
            toast(t("toast.save_failed"));
        }
    }

    private void loadClip(String name) {
        try {
            pushUndo();
            Playback.clip = ClipIO.load(name);
            clearSel();
            nameField.text = clip().name;
            Playback.time = 0f;
            Playback.pause();
            toast(t("toast.loaded") + " " + name);
        } catch (Exception ex) {
            toast(t("toast.load_failed"));
        }
    }

    // ---------------------------------------------------------------- live recording

    private void toggleRec() {
        if (rec) { stopRec(); return; }
        if (selTrack > 2) { toast(t("toast.rec_track")); return; }
        pushUndo();
        float[] tmp = new float[7];
        Playback.sampleRaw(selTrack, Playback.time, tmp);
        System.arraycopy(tmp, 0, recV, 0, 7);
        rec = true;
        recAcc = 0f;
        Playback.liveTrack = selTrack;
        System.arraycopy(recV, 0, Playback.live, 0, 7);
        Playback.play(Playback.time, false, false);
        toast(t("toast.rec_on"));
    }

    private void stopRec() {
        if (!rec) return;
        rec = false;
        Playback.liveTrack = -1;
        Playback.pause();
        Track tr = clip().track(selTrack);
        Templates.simplify(tr, 0.004f, 0.4f);
        clearSel();
        toast(t("toast.rec_done"));
    }

    /** Called every frame: drops a key every 50 ms of playback with the live mouse-driven pose. */
    private void tickRecording() {
        if (!rec) return;
        if (!Playback.playing) { stopRec(); return; }
        System.arraycopy(recV, 0, Playback.live, 0, 7);
        recAcc += dt;
        if (recAcc < 0.05f) return;
        recAcc = 0f;
        Track tr = clip().track(selTrack);
        float tt = Math.round(Playback.time * 20f) / 20f;
        tr.keys.removeIf(k -> Math.abs(k.time - tt) < 0.026f);
        Keyframe k = new Keyframe(tr.channels);
        k.time = tt;
        k.easing = Easing.SPLINE;
        System.arraycopy(recV, 0, k.v, 0, tr.channels);
        tr.put(k);
    }

    private int panelTemplates(GuiGraphics g, int x, int w, int y) {
        argField.w = 0;
        y = wrap(g, t("hint.templates"), x, y, w, C_DIM) + 2;
        button(g, "tplrest", t("tpl_rest") + ": " + (tplRest ? t("on") : t("off")), x, y, w, 13, tplRest, true, t("tip.tpl_rest"), () -> tplRest = !tplRest, null);
        y += 16;
        button(g, "tplend", t("tpl_where") + ": " + (tplAtEnd ? t("tpl_end") : t("tpl_head")), x, y, w, 13, tplAtEnd, true, null, () -> tplAtEnd = !tplAtEnd, null);
        y += 20;
        int lastCat = -1;
        for (Templates.Template tp : Templates.ALL) {
            if (tp.category() != lastCat) {
                lastCat = tp.category();
                y += 3;
                txt(g, t("tpl.cat" + lastCat), x, y, lastCat == 1 ? C_ACC : lastCat == 2 ? C_ACC2 : C_DIM);
                y += 12;
            }
            String label = t("tpl." + tp.id());
            String dur = String.format(Locale.ROOT, "%.1fs", tp.duration());
            button(g, "tp:" + tp.id(), label, x, y, w - 30, 14, false, true, t("tpl_tip." + tp.id()), () -> applyTemplate(tp), null);
            txt(g, dur, x + w - 26, y + 3, C_DIM);
            y += 17;
        }
        return y;
    }

    private void applyTemplate(Templates.Template tp) {
        pushUndo();
        float t0 = tplAtEnd ? clip().lastTime() : Playback.time;
        t0 = Math.round(t0 * 20f) / 20f;
        Templates.apply(clip(), tp, t0, tplRest);
        clearSel();
        setTime(t0);
        Playback.play(t0, false, previewEvents && Actions.allowed(Minecraft.getInstance().player));
        toast(t("toast.template") + " " + t("tpl." + tp.id()));
    }

    /** Close the studio but keep the live hand/camera preview on, so the player can walk and look around the world. */
    private void goStage() {
        if (rec) stopRec();
        launching = true;
        Playback.stage = true;
        Playback.pause();
        Minecraft.getInstance().setScreen(null);
    }

    /** Close the studio, count down, then record the player's real movement while the clip plays. */
    private void startMoveRecord() {
        if (!Actions.allowed(Minecraft.getInstance().player)) { toast(t("toast.creative")); return; }
        if (rec) stopRec();
        launching = true;
        com.fpstudio.client.MoveRecorder.begin(Playback.time);
        Minecraft.getInstance().setScreen(null);
    }

    private void setShownHands(String m) {
        pushUndo();
        clip().showRight = m.equals("right") || m.equals("both");
        clip().showLeft = m.equals("left") || m.equals("both");
    }

    private void recalcCamera() {
        Clip c = clip();
        if (c.camRaw == null || c.camRaw.keys.size() < 2) { toast(t("toast.no_raw")); return; }
        pushUndo();
        com.fpstudio.model.CamSmooth.rebuildFromRaw(c.camera, c.camRaw, c.camSmooth, c.camDetail);
        tl.clearSelection();
        toast(t("toast.cam_applied") + " " + c.camera.keys.size());
    }

    private void smoothCamKeys() {
        Track cam = clip().camera;
        if (cam.keys.size() < 2) { toast(t("toast.no_keys")); return; }
        pushUndo();
        cam.sort();
        com.fpstudio.model.CamSmooth.smoothRange(cam, cam.keys.get(0).time, cam.keys.get(cam.keys.size() - 1).time, clip().camSmooth, clip().camDetail);
        tl.clearSelection();
        toast(t("toast.cam_applied") + " " + cam.keys.size());
    }

    private void togglePlay() {
        if (rec) { stopRec(); return; }
        if (Playback.playing) Playback.pause();
        else Playback.play(Playback.time, false, previewEvents && Actions.allowed(Minecraft.getInstance().player));
    }

    private void fullscreenPreview() {
        if (!Actions.allowed(Minecraft.getInstance().player)) { toast(t("toast.creative")); return; }
        launching = true;
        Playback.returnToStudio = true;
        Minecraft.getInstance().setScreen(null); // close first: play() may open the input-freeze screen
        Playback.play(0f, true, true);
    }

    // ======================================================== render

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float pt) {
        long now = System.nanoTime();
        dt = Math.min(0.05f, (now - lastNs) / 1e9f) * Playback.uiSpeed();
        lastNs = now;
        us = effectiveScale();
        vw = Math.round(this.width / us);
        vh = Math.round(this.height / us);
        mx = (int) (mouseX / us);
        my = (int) (mouseY / us);

        open = Mth.clamp(open + (closing ? -1f : 1f) * dt * 2.4f, 0f, 1f);
        if (closing && open <= 0f) { Minecraft.getInstance().setScreen(null); return; }
        if (toastT > 0f) toastT -= dt;

        btns.clear();
        slds.clear();
        tip = null;

        syncSel();
        NpcRuntime.hlActor = tab == 3 && selActorId != null ? selActorId : "";
        NpcRuntime.hlBone = tab == 3 ? selBone : "";
        botH = Mth.clamp(botH, 96, Math.max(96, (int) (vh * 0.6f)));

        contentT = Math.min(1f, contentT + dt * 4.5f);
        dispTime = Playback.playing ? Playback.time
                : dispTime + (Playback.time - dispTime) * (1f - (float) Math.exp(-dt * 20f));
        tickRecording();

        if (sel != syncedSel) {
            contentT = 0f;
            syncedSel = sel;
            if (sel instanceof EventKey e) argField.text = e.arg;
        }

        float eT = StudioFx.easeOut(open / 0.55f);
        float eR = StudioFx.easeOutBack((open - 0.2f) / 0.6f);
        float eB = StudioFx.easeOut((open - 0.35f) / 0.65f);

        g.pose().pushPose();
        g.pose().scale(us, us, 1f);
        if (showGrid) drawGrid(g, open);
        drawBottom(g, (int) ((botH + 2) * (1f - eB)));
        drawPanel(g, (int) ((PANEL_W + 16) * (1f - eR)));
        drawTop(g, -(int) ((TOP_H + 2) * (1f - eT)));
        if (showLoad) drawLoadPopup(g);
        tl.renderMenu(g, mx, my, dt);

        if (toastT > 0f) {
            float a = Mth.clamp(toastT * 3f, 0f, 1f);
            int w = font.width(toast) + 20;
            int y = TOP_H + 6 + (int) ((1f - a) * -8f);
            rr(g, (vw - w) / 2, y, w, 16, alpha(0xFF241F3D, a * 0.95f));
            ctr(g, toast, vw / 2, y + 4, alpha(C_TEXT, a));
        }
        if (tip != null && !tip.isEmpty()) {
            int w = font.width(tip) + 8;
            int x = Math.min(mx + 10, vw - w - 2), y = my + 14;
            if (y + 14 > vh) y = my - 18;
            rr(g, x, y, w, 13, 0xF0000000);
            txt(g, tip, x + 4, y + 3, C_TEXT);
        }
        g.pose().popPose();
    }

    private void drawGrid(GuiGraphics g, float a) {
        int c = alpha(0xFFFFFFFF, 0.10f * a);
        g.vLine(vw / 3, 0, vh, c);
        g.vLine(vw * 2 / 3, 0, vh, c);
        g.hLine(0, vw, vh / 3, c);
        g.hLine(0, vw, vh * 2 / 3, c);
    }

    private void drawTop(GuiGraphics g, int oy) {
        g.fillGradient(0, oy, vw, oy + TOP_H, 0xF00F0E16, 0xE8191726);
        g.fill(0, oy + TOP_H - 1, vw, oy + TOP_H, C_LINE);

        if (rec) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(System.nanoTime() / 1.4e8);
            rr(g, 10, oy + 9, 8, 8, alpha(0xFFFF2D3D, 0.5f + 0.5f * pulse));
        } else if (Playback.playing) {
            float pulse = 0.55f + 0.45f * (float) Math.sin(System.nanoTime() / 2.2e8);
            rr(g, 10, oy + 9, 8, 8, alpha(C_WARN, pulse));
        } else {
            rr(g, 10, oy + 9, 8, 8, alpha(C_ACC, 0.9f));
        }
        txt(g, "FP STUDIO", 24, oy + 9, C_TEXT);

        nameField.x = 96;
        nameField.y = oy + 5;
        nameField.w = 130;
        nameField.h = 16;
        boolean hov = inside(nameField.x, nameField.y, nameField.w, nameField.h);
        float a = anim("name", hov || nameField.focus);
        rr(g, nameField.x, nameField.y, nameField.w, nameField.h, lerpC(C_FIELD, C_FIELD_H, a));
        if (nameField.focus) g.fill(nameField.x + 2, nameField.y + 14, nameField.x + nameField.w - 2, nameField.y + 15, C_ACC);
        String shown = nameField.text + (nameField.focus && (System.currentTimeMillis() / 450) % 2 == 0 ? "_" : "");
        txt(g, font.plainSubstrByWidth(shown, nameField.w - 8, true), nameField.x + 4, nameField.y + 4, C_TEXT);

        int x = nameField.x + nameField.w + 8;
        button(g, "new", t("new"), x, oy + 5, 44, 16, false, false, t("tip.new"), () -> {
            pushUndo();
            Playback.clip = new Clip();
            clearSel();
            nameField.text = clip().name;
            Playback.time = 0f;
        }, null);
        x += 48;
        popX = x;
        button(g, "load", t("load"), x, oy + 5, 50, 16, showLoad, false, t("tip.load"), () -> {
            showLoad = !loadWasOpen;
            clipNames = ClipIO.list();
            loadScroll = 0;
        }, null);
        x += 54;
        button(g, "save", t("save"), x, oy + 5, 50, 16, false, false, t("tip.save"), this::save, null);
        x += 58;
        button(g, "undo", t("undo"), x, oy + 5, 42, 16, false, false, "Ctrl+Z", this::doUndo, null);
        x += 46;
        button(g, "redo", t("redo"), x, oy + 5, 42, 16, false, false, "Ctrl+Y", this::doRedo, null);

        if (!Actions.allowed(Minecraft.getInstance().player)) {
            String w = t("warn.creative");
            txt(g, w, vw - 30 - font.width(w), oy + 9, C_WARN);
        }
        int ux = vw - 24 - 4 - 56;
        txt(g, Math.round(Config.UI_SCALE.get().floatValue() * 100) + "%", ux - font.width(Math.round(Config.UI_SCALE.get().floatValue() * 100) + "%") - 4, oy + 9, C_DIM);
        button(g, "uim", "-", ux, oy + 5, 16, 16, false, false, t("tip.ui_scale") + " [Ctrl -]", () -> changeUiScale(-0.05f), null);
        button(g, "uip", "+", ux + 20, oy + 5, 16, 16, false, false, t("tip.ui_scale") + " [Ctrl +]", () -> changeUiScale(0.05f), null);
        button(g, "close", "x", vw - 24, oy + 5, 16, 16, false, false, t("tip.close"), () -> closing = true, null);
    }

    private void drawLoadPopup(GuiGraphics g) {
        popW = 170;
        int rows = Math.min(10, Math.max(1, clipNames.size()));
        popH = rows * 15 + 20;
        popY = TOP_H + 2;
        rr(g, popX, popY, popW, popH, 0xF2141222);
        txt(g, t("load.hint"), popX + 6, popY + 5, C_DIM);
        scissor(g, popX, popY + 16, popX + popW, popY + popH);
        int y = popY + 18 - (int) loadScroll;
        if (clipNames.isEmpty()) txt(g, t("load.empty"), popX + 8, y + 2, C_DIM);
        for (String n : clipNames) {
            boolean h = inside(popX, y, popW, 15) && my >= popY + 16 && my < popY + popH;
            float a = anim("l:" + n, h);
            if (a > 0.01f) g.fill(popX + 2, y, popX + popW - 2, y + 15, alpha(C_ACC, 0.25f * a));
            txt(g, n, popX + 8, y + 3, lerpC(C_DIM, C_TEXT, a));
            y += 15;
        }
        g.disableScissor();
    }

    // ---------------------------------------------------------------- bottom (transport + timeline)

    private void drawBottom(GuiGraphics g, int oy) {
        int by = vh - botH + oy;
        g.fillGradient(0, by, vw, vh + oy, 0xF0171524, 0xF00F0E16);
        g.fill(0, by, vw, by + 1, C_LINE);
        // resize handle
        boolean hh = my >= by - 2 && my <= by + 3 && !showLoad;
        float ha = anim("resize", hh || drag == Drag.RESIZE);
        if (ha > 0.02f) g.fill(vw / 2 - 24, by - 1, vw / 2 + 24, by + 2, alpha(C_ACC, ha));
        if (hh) tip = t("tip.resize");

        int x = 8, y = by + 6;
        iconButton(g, "home", 3, x, y, 22, 16, false, "Home", () -> setTime(0f));
        x += 26;
        iconButton(g, "play", Playback.playing ? 1 : 0, x, y, 26, 16, Playback.playing, t("tip.play") + " [Space]", this::togglePlay);
        x += 30;
        iconButton(g, "stop", 2, x, y, 22, 16, false, t("tip.stop"), () -> { Playback.pause(); setTime(0f); });
        x += 26;
        button(g, "rec", rec ? t("rec_stop") : t("rec"), x, y, 46, 16, rec, false, t("tip.rec") + " [E]", this::toggleRec, null);
        x += 50;
        button(g, "loop", t("loop"), x, y, 36, 16, clip().loop, false, "L", () -> { pushUndo(); clip().loop = !clip().loop; }, null);
        x += 40;
        button(g, "addkey", "+ " + t("key"), x, y, 50, 16, false, false, t("tip.addkey") + " [K]", tl::addKeyAtPlayhead, null);
        x += 54;
        button(g, "view", tl.graph() ? t("view_dope") : t("view_graph"), x, y, 56, 16, tl.graph(), false, t("tip.view") + " [Q]", tl::toggleGraph, null);
        x += 60;
        button(g, "bars", t("bars") + " " + (clip().letterbox ? t("on") : t("off")), x, y, 62, 16, clip().letterbox, false, t("tip.bars") + " [B]", this::toggleBars, null);
        x += 66;
        button(g, "hands", t("hands"), x, y, 50, 16, Playback.shownHideR > 0.5f && Playback.shownHideL > 0.5f, false, t("tip.hands") + " [H]", this::toggleHands, null);
        x += 54;
        button(g, "full", t("full"), x, y, 64, 16, false, false, t("tip.full") + " [Enter]", this::fullscreenPreview, null);
        x += 68;
        button(g, "stage", t("stage"), x, y, 56, 16, false, false, t("tip.stage") + " [Tab]", this::goStage, null);
        x += 60;
        button(g, "recmove", t("rec_move"), x, y, 56, 16, false, false, t("tip.rec_move") + " [R]", this::startMoveRecord, null);
        x += 60;
        if (tl.hasSelection()) {
            String n = t("selected") + " " + tl.selectionCount();
            txt(g, n, x + 2, y + 4, C_ACC2);
        }

        int rulerTop = by + 26;
        tl.layout(8, rulerTop, vw - 8, vh - 5);
        String time = String.format(Locale.ROOT, "%.2f / %.1f", Playback.time, clip().duration);
        txt(g, time, 12, rulerTop + 3, C_ACC2);
        tl.render(g, mx, my, dt, dispTime);
    }

    // ---------------------------------------------------------------- right panel

    private void drawPanel(GuiGraphics g, int ox) {
        pX = vw - PANEL_W - 8 + ox;
        int top = TOP_H + 8, bot = vh - botH - 8;
        if (bot - top < 80) bot = top + 80;
        rr(g, pX, top, PANEL_W, bot - top, C_BG);

        int tw = (PANEL_W - 12) / 4;
        String[] tabKeys = {"tab.key", "tab.templates", "tab.clip", "tab.npc"};
        for (int i = 0; i < 4; i++) {
            final int ti = i;
            button(g, "tab" + i, t(tabKeys[i]), pX + 6 + tw * i, top + 6, tw, 14, tab == i, false, null,
                    () -> { if (tab != ti) { tab = ti; pScrollT = 0; contentT = 0f; } }, null);
        }

        boneFilter.w = 0;
        cTop = top + 26;
        cBot = bot - 4;
        scissor(g, pX, cTop, pX + PANEL_W, cBot);
        int start = cTop + 4 - (int) pScroll + (int) ((1f - StudioFx.easeOut(contentT)) * 10f);
        int cx = pX + 8, cw = PANEL_W - 16;
        int end = tab == 0 ? panelKey(g, cx, cw, start) : tab == 1 ? panelTemplates(g, cx, cw, start) : tab == 2 ? panelClip(g, cx, cw, start) : panelNpc(g, cx, cw, start);
        pContentH = end - start + 4;
        g.disableScissor();

        float maxScroll = Math.max(0, pContentH - (cBot - cTop));
        pScrollT = Mth.clamp(pScrollT, 0f, maxScroll);
        pScroll += (pScrollT - pScroll) * (1f - (float) Math.exp(-dt * 14f));
        if (maxScroll > 0) {
            int trackH = cBot - cTop;
            int bh = Math.max(14, (int) (trackH * trackH / (float) pContentH));
            int by = cTop + (int) ((trackH - bh) * (pScroll / maxScroll));
            g.fill(pX + PANEL_W - 3, by, pX + PANEL_W - 1, by + bh, alpha(C_ACC, 0.7f));
        }
    }

    private int panelKey(GuiGraphics g, int x, int w, int y) {
        if (sel instanceof Keyframe k && (selTrack < 3 || (selTrack >= 4 && selTrack - 4 < extras.size()))) {
            argField.w = 0;
            boolean cam = selTrack == 2;
            Extra ex = selTrack >= 4 ? extras.get(selTrack - 4) : null;
            int col = ex == null ? TRACK_COL[selTrack] : 0xFFE0B04F;
            txt(g, font.plainSubstrByWidth(ex == null ? t(TRACK_KEY[selTrack]) : ex.label(), w, false), x, y, col);
            y += 13;
            y = sliderRow(g, "time", t("time"), k.time, 0f, clip().duration, 0.05f, v -> { if (v > clip().duration) clip().duration = Math.min(600f, v); k.time = v; resortSel(); setTime(v); }, x, y, w, C_ACC2);
            String[] names = ex != null ? ex.ch() : cam ? CAM_CH : ARM_CH;
            for (int c = 0; c < names.length; c++) {
                final int ch = c;
                boolean pos = c < 3, fov = c == 6 && ex == null;
                float range = fov ? 60f : pos ? (cam ? 3f : ex == null ? 1.5f : ex.bone() == null ? 8f : 32f) : (ex != null && ex.bone() != null && c == 6) ? 1f : 180f;
                float step = fov ? 0.5f : pos ? (ex == null ? 0.005f : ex.bone() == null ? 0.01f : 0.1f) : (ex != null && ex.bone() != null && c == 6) ? 0.01f : 0.5f;
                boolean keyed = k.has(c);
                float shown = keyed ? k.v[c] : trackOf(selTrack).sampleChannel(c, k.time);
                final int ft = selTrack;
                y = sliderRow(g, "c" + c, ex != null ? names[c] : t("ch." + names[c]), shown, -range, range, step, v -> setCh(k, ft, ch, v), x, y, w, keyed ? col : alpha(col, 0.35f));
            }
            y += 2;
            button(g, "ease", t("ease") + ": " + t("ease." + k.easing.name().toLowerCase(Locale.ROOT)) + (tl.selectionCount() > 1 ? " (" + tl.selectionCount() + ")" : ""), x, y, w, 14, false, true,
                    t("tip.ease"), () -> tl.setEasing(k.easing.step(1)), () -> tl.setEasing(k.easing.step(-1)));
            y += 18;
            int bw = (w - 4) / 2;
            button(g, "dup", t("duplicate"), x, y, bw, 14, false, true, "Ctrl+D", tl::duplicateSelection, null);
            button(g, "del2", t("delete"), x + bw + 4, y, bw, 14, false, true, "Del", tl::deleteSelection, null);
            y += 18;
            if (selTrack < 2) {
                button(g, "mirk", t("mirror_key"), x, y, w, 14, false, true, null, () -> {
                    pushUndo();
                    clip().track(1 - selTrack).put(mirrored(k));
                    toast(t("toast.mirrored"));
                }, null);
                y += 18;
            }
            y += 4;
            return wrap(g, t("hint.grab"), x, y, w, C_DIM);
        }
        if (sel instanceof EventKey e) {
            txt(g, t(TRACK_KEY[3]), x, y, TRACK_COL[3]);
            y += 13;
            y = sliderRow(g, "etime", t("time"), e.time, 0f, clip().duration, 0.05f, v -> { if (v > clip().duration) clip().duration = Math.min(600f, v); e.time = v; resortSel(); setTime(v); }, x, y, w, C_ACC2);
            button(g, "etype", t("event." + e.type.name().toLowerCase(Locale.ROOT)), x, y, w, 14, false, true, t("tip.etype"),
                    () -> { pushUndo(); e.type = e.type.step(1); }, () -> { pushUndo(); e.type = e.type.step(-1); });
            y += 18;
            if (e.type == EventType.HANDS) {
                int[] yy = {y};
                handsEventPanel(g, e, x, w, yy);
                y = yy[0];
                int hbw = (w - 4) / 2;
                button(g, "dup", t("duplicate"), x, y, hbw, 14, false, true, "Ctrl+D", tl::duplicateSelection, null);
                button(g, "del2", t("delete"), x + hbw + 4, y, hbw, 14, false, true, "Del", tl::deleteSelection, null);
                argField.w = 0;
                return y + 18;
            }
            if (e.type.isNpc()) {
                y = npcEventPanel(g, e, x, w, y);
                int nbw = (w - 4) / 2;
                button(g, "dup", t("duplicate"), x, y, nbw, 14, false, true, "Ctrl+D", tl::duplicateSelection, null);
                button(g, "del2", t("delete"), x + nbw + 4, y, nbw, 14, false, true, "Del", tl::deleteSelection, null);
                argField.w = 0;
                return y + 18;
            }
            if (e.type.usesDur()) {
                y = sliderRow(g, "edur", t("duration"), e.dur, 0.1f, 20f, 0.05f, v -> e.dur = v, x, y, w, C_ACC);
            }
            boolean usesArg = e.type.usesArg();
            if (usesArg) {
                txt(g, t("arg"), x, y, C_DIM);
                y += 11;
                argField.x = x;
                argField.y = y;
                argField.w = w;
                argField.h = 16;
                boolean hov = inside(x, y, w, 16) && inContent();
                float a = anim("arg", hov || argField.focus);
                rr(g, x, y, w, 16, lerpC(C_FIELD, C_FIELD_H, a));
                if (argField.focus) g.fill(x + 2, y + 14, x + w - 2, y + 15, C_ACC);
                String shown = argField.text + (argField.focus && (System.currentTimeMillis() / 450) % 2 == 0 ? "_" : "");
                txt(g, font.plainSubstrByWidth(shown, w - 8, true), x + 4, y + 4, C_TEXT);
                y += 20;
            } else {
                argField.w = 0;
            }
            y = wrap(g, t("hint." + e.type.name().toLowerCase(Locale.ROOT)), x, y, w, C_DIM);
            y += 4;
            int bw = (w - 4) / 2;
            button(g, "dup", t("duplicate"), x, y, bw, 14, false, true, "Ctrl+D", tl::duplicateSelection, null);
            button(g, "del2", t("delete"), x + bw + 4, y, bw, 14, false, true, "Del", tl::deleteSelection, null);
            return y + 18;
        }
        argField.w = 0;
        y = wrap(g, t("hint.none"), x, y, w, C_DIM);
        y += 6;
        for (String line : t("hint.keys").split("\\|")) y = wrap(g, line, x, y, w, C_TEXT);
        return y;
    }

    private int npcEventPanel(GuiGraphics g, EventKey e, int x, int w, int y) {
        List<String> ids = actorIds();
        if (ids.isEmpty()) return wrap(g, t("npc.no_actor"), x, y, w, C_WARN) + 4;
        String id = NpcInstance.idOf(e.arg);
        String sub = NpcInstance.animOf(e.arg);
        if (clip().actor(id) == null) { id = ids.get(0); sub = ""; e.arg = id + "|" + sub; }
        final String fid = id, fsub = sub;
        button(g, "nactor", t("npc.actor") + ": " + id, x, y, w, 14, false, true, t("tip.etype"),
                () -> { pushUndo(); e.arg = cycle(ids, fid, 1) + "|"; }, () -> { pushUndo(); e.arg = cycle(ids, fid, -1) + "|"; });
        y += 18;
        Actor a = clip().actor(id);
        NpcAsset as = a == null ? null : NpcLibrary.get(a.asset);
        if (e.type == EventType.NPC_ANIM) {
            List<String> names = as == null ? List.of() : as.animNames;
            String shown = sub.isEmpty() ? "-" : sub;
            button(g, "nanim", t("npc.anim") + ": " + font.plainSubstrByWidth(shown, w - 70, false), x, y, w, 14, false, true, t("tip.etype"),
                    () -> { pushUndo(); e.arg = fid + "|" + cycle(names, fsub, 1); }, () -> { pushUndo(); e.arg = fid + "|" + cycle(names, fsub, -1); });
            y += 18;
            y = sliderRow(g, "nspeed", t("speed"), e.speed, 0.1f, 3f, 0.05f, v -> e.speed = v, x, y, w, C_ACC2);
            y = sliderRow(g, "ndur", t("npc.dur0"), e.dur, 0f, 20f, 0.05f, v -> e.dur = v, x, y, w, C_ACC);
        } else {
            List<String> bones = new ArrayList<>();
            bones.add("");
            if (as != null) for (String n : as.model.name) bones.add(n);
            button(g, "nbone", t("npc.look_bone") + ": " + (sub.isEmpty() ? t("npc.eyes") : sub), x, y, w, 14, false, true, t("tip.etype"),
                    () -> { pushUndo(); e.arg = fid + "|" + cycle(bones, fsub, 1); }, () -> { pushUndo(); e.arg = fid + "|" + cycle(bones, fsub, -1); });
            y += 18;
            y = sliderRow(g, "ndur", t("npc.dur0"), e.dur, 0f, 20f, 0.05f, v -> e.dur = v, x, y, w, C_ACC);
        }
        y = wrap(g, t("hint." + e.type.name().toLowerCase(Locale.ROOT)), x, y, w, C_DIM);
        return y + 4;
    }

    private int panelNpc(GuiGraphics g, int x, int w, int y) {
        argField.w = 0;
        y = wrap(g, t("npc.hint"), x, y, w, C_DIM) + 2;
        int bw = (w - 4) / 2;
        button(g, "npcdir", t("npc.folder"), x, y, bw, 14, false, true, NpcLibrary.dir().toString(), NpcLibrary::openFolder, null);
        button(g, "npcreload", t("npc.reload"), x + bw + 4, y, bw, 14, false, true, null,
                () -> { NpcLibrary.reload(); toast(t("npc.reloaded") + " " + NpcLibrary.all().size()); }, null);
        y += 20;
        txt(g, t("npc.library"), x, y, C_ACC);
        y += 12;
        if (NpcLibrary.all().isEmpty()) y = wrap(g, t("npc.empty"), x, y, w, C_DIM);
        for (NpcAsset asset : NpcLibrary.all()) {
            button(g, "nl:" + asset.name, "+ " + asset.name + (asset.error.isEmpty() ? "" : " !"), x, y, w, 14, false, true,
                    asset.error.isEmpty() ? t("npc.add_tip") : asset.error, () -> addActor(asset), null);
            y += 17;
        }
        y += 4;
        txt(g, t("npc.scene"), x, y, C_ACC);
        y += 12;
        if (clip().actors.isEmpty()) y = wrap(g, t("npc.no_actor"), x, y, w, C_DIM);
        for (Actor a : new ArrayList<>(clip().actors)) {
            button(g, "na:" + a.id, a.id + " (" + a.asset + ")", x, y, w - 18, 14, a.id.equals(selActorId), true, null, () -> selectActor(a.id), null);
            button(g, "nx:" + a.id, "x", x + w - 14, y, 14, 14, false, true, t("delete"), () -> {
                pushUndo();
                clip().actors.remove(a);
                clip().events.removeIf(ev -> ev.type.isNpc() && a.id.equals(NpcInstance.idOf(ev.arg)));
                if (a.id.equals(selActorId)) selectActor(null);
            }, null);
            y += 17;
        }
        Actor sa = selActor();
        if (sa == null) return y + 4;
        y += 6;
        button(g, "nhere", t("npc.here"), x, y, bw, 14, false, true, t("npc.here_tip"), () -> { pushUndo(); placeAtPlayer(sa); }, null);
        button(g, "nvis", t("npc.visible") + ": " + (sa.visible ? t("on") : t("off")), x + bw + 4, y, bw, 14, sa.visible, true, null,
                () -> { pushUndo(); sa.visible = !sa.visible; }, null);
        y += 20;
        y = sliderRow(g, "nyaw", t("ch.yaw"), sa.yaw, -180f, 180f, 1f, v -> sa.yaw = v, x, y, w, C_ACC);
        y = sliderRow(g, "nscale", t("npc.scale"), sa.scale, 0.1f, 4f, 0.05f, v -> sa.scale = v, x, y, w, C_ACC);
        y = sliderRow(g, "nblend", t("npc.blend"), sa.blend, 0f, 1f, 0.05f, v -> sa.blend = v, x, y, w, C_ACC2);
        NpcAsset as = NpcLibrary.get(sa.asset);
        if (as == null) return wrap(g, t("npc.missing"), x, y, w, C_WARN);
        List<String> idles = new ArrayList<>();
        idles.add("");
        idles.addAll(as.animNames);
        button(g, "nidle", t("npc.idle") + ": " + (sa.idle.isEmpty() ? t("npc.auto") + " " + as.autoIdle() : sa.idle), x, y, w, 14, false, true, t("npc.idle_tip"),
                () -> { pushUndo(); sa.idle = cycle(idles, sa.idle, 1); }, () -> { pushUndo(); sa.idle = cycle(idles, sa.idle, -1); });
        y += 20;
        button(g, "nexport", t("npc.export"), x, y, w, 14, false, true, t("npc.export_tip"), () -> exportNpcAnim(sa), null);
        y += 20;
        txt(g, t("npc.animations"), x, y, C_ACC);
        y += 12;
        if (as.animNames.isEmpty()) y = wrap(g, t("npc.no_anims"), x, y, w, C_DIM);
        for (String an : as.animNames) {
            var def = as.animations.anims.get(an);
            String len = def == null ? "" : String.format(Locale.ROOT, "%.1fs", def.length);
            button(g, "nan:" + an, "> " + font.plainSubstrByWidth(an, w - 40, false), x, y, w - 30, 14, false, true, t("npc.cue_tip"), () -> addCue(sa, an), null);
            txt(g, len, x + w - 26, y + 3, C_DIM);
            y += 17;
        }
        y += 4;
        txt(g, t("npc.bones"), x, y, C_ACC);
        y += 12;
        y = wrap(g, t("npc.bones_hint"), x, y, w, C_DIM) + 2;
        GeoModel m = as.model;
        // search box
        boneFilter.x = x;
        boneFilter.y = y;
        boneFilter.w = w;
        boneFilter.h = 16;
        boolean fh = inside(x, y, w, 16) && inContent();
        float fa = anim("bfilter", fh || boneFilter.focus);
        rr(g, x, y, w, 16, lerpC(C_FIELD, C_FIELD_H, fa));
        if (boneFilter.focus) g.fill(x + 2, y + 14, x + w - 2, y + 15, C_ACC);
        String fshown = boneFilter.text.isEmpty() && !boneFilter.focus ? t("npc.search") : boneFilter.text + (boneFilter.focus && (System.currentTimeMillis() / 450) % 2 == 0 ? "_" : "");
        txt(g, font.plainSubstrByWidth(fshown, w - 8, true), x + 4, y + 4, boneFilter.text.isEmpty() && !boneFilter.focus ? C_DIM : C_TEXT);
        y += 20;
        if (!sa.hidden.isEmpty()) {
            button(g, "nshowall", t("npc.show_all") + " (" + sa.hidden.size() + ")", x, y, w, 14, false, true, null, () -> { pushUndo(); sa.hidden.clear(); }, null);
            y += 18;
        }
        String q = boneFilter.text.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < m.bones; i++) {
            String name = m.name[i];
            if (!q.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(q)) continue;
            int depth = 0;
            for (int p2 = m.parent[i]; p2 >= 0 && depth < 8; p2 = m.parent[p2]) depth++;
            Track bt = sa.bones.get(name);
            boolean keyed = bt != null && !bt.keys.isEmpty();
            boolean hid = sa.hidden.contains(name);
            int ind = q.isEmpty() ? Math.min(depth, 6) * 6 : 0;
            final int bi = i;
            button(g, "nh:" + name, hid ? "o" : "*", x + ind, y, 13, 13, hid, true, t("npc.eye_tip"), () -> toggleHide(sa, m, bi, true), () -> toggleHide(sa, m, bi, false));
            button(g, "nb:" + name, (keyed ? "+ " : "") + name, x + ind + 15, y, w - ind - 15, 13, name.equals(selBone), true, null, () -> selectBone(name), null);
            y += 15;
        }
        return y + 4;
    }

    private int panelClip(GuiGraphics g, int x, int w, int y) {
        argField.w = 0;
        Clip c = clip();
        txt(g, t("cam_title"), x, y, C_ACC);
        y += 12;
        y = toggleRow(g, "canchor", t("cam_anchor"), c.camAnchor.on, () -> { pushUndo(); c.camAnchor.on = !c.camAnchor.on; }, x, y, w);
        y = sliderRow(g, "cfly", t("cam_fly"), c.camFly, 0f, 5f, 0.05f, v -> c.camFly = v, x, y, w, C_ACC);
        y = sliderRow(g, "cflyout", t("cam_fly_out"), c.camFlyOut, 0f, 5f, 0.05f, v -> c.camFlyOut = v, x, y, w, C_ACC);
        y = sliderRow(g, "csmooth", t("cam_smooth"), c.camSmooth, 0f, 2f, 0.01f, v -> c.camSmooth = v, x, y, w, C_ACC2);
        y = sliderRow(g, "cdetail", t("cam_detail"), c.camDetail, 0.1f, 5f, 0.05f, v -> c.camDetail = v, x, y, w, C_ACC2);
        button(g, "capply", t("cam_apply"), x, y, w, 14, false, true, t("tip.cam_apply"), this::recalcCamera, null);
        y += 18;
        button(g, "csmoothk", t("cam_smooth_keys"), x, y, w, 14, false, true, t("tip.cam_smooth_keys"), this::smoothCamKeys, null);
        y += 18;
        y = wrap(g, t("hint.cam"), x, y, w, C_DIM) + 6;
        y = sliderRow(g, "dur", t("duration"), c.duration, 0.5f, 60f, 0.1f, v -> c.duration = v, x, y, w, C_ACC);
        y = sliderRow(g, "bin", t("blend_in"), c.blendIn, 0f, 2f, 0.05f, v -> c.blendIn = v, x, y, w, C_ACC);
        y = sliderRow(g, "bout", t("blend_out"), c.blendOut, 0f, 2f, 0.05f, v -> c.blendOut = v, x, y, w, C_ACC);
        y = sliderRow(g, "sway", t("sway"), c.sway, 0f, 3f, 0.05f, v -> c.sway = v, x, y, w, C_ACC2);
        y = sliderRow(g, "shake", t("shake"), c.shake, 0f, 3f, 0.05f, v -> c.shake = v, x, y, w, C_ACC2);
        y += 4;
        y = toggleRow(g, "tloop", t("loop"), c.loop, () -> { pushUndo(); c.loop = !c.loop; }, x, y, w);
        y = toggleRow(g, "thud", t("hide_hud"), c.hideHud, () -> { pushUndo(); c.hideHud = !c.hideHud; }, x, y, w);
        y = toggleRow(g, "tbox", t("letterbox"), c.letterbox, () -> { pushUndo(); c.letterbox = !c.letterbox; }, x, y, w);
        y = sliderRow(g, "bar", t("bar_ratio"), c.barRatio, 1.6f, 2.8f, 0.01f, v -> c.barRatio = v, x, y, w, C_ACC2);
        y = toggleRow(g, "tgrid", t("grid"), showGrid, () -> showGrid = !showGrid, x, y, w);
        y = toggleRow(g, "tfreeze", t("freeze_all"), c.freezeInput, () -> { pushUndo(); c.freezeInput = !c.freezeInput; }, x, y, w);
        y = toggleRow(g, "tlock", t("lock_move"), c.lockMovement, () -> { pushUndo(); c.lockMovement = !c.lockMovement; }, x, y, w);
        y = toggleRow(g, "tlockin", t("lock_input"), c.lockInput, () -> { pushUndo(); c.lockInput = !c.lockInput; }, x, y, w);
        y = toggleRow(g, "tforce", t("lock_force"), Config.FORCE_LOCK_INPUT.get(), () -> {
            Config.FORCE_LOCK_INPUT.set(!Config.FORCE_LOCK_INPUT.get());
            Config.SPEC.save();
        }, x, y, w);
        y = wrap(g, t("hint.lock"), x, y + 1, w, C_DIM) + 3;
        button(g, "hands2", t("hands_toggle"), x, y, w, 14, false, true, t("tip.hands"), this::toggleHands, null);
        y += 18;
        button(g, "mirror2", t("mirror"), x, y, w, 14, false, true, t("tip.mirror") + " [M]", this::mirrorTrack, null);
        y += 18;
        String shownHands = c.showRight && c.showLeft ? "both" : c.showLeft ? "left" : c.showRight ? "right" : "none";
        List<String> handOpts = List.of("right", "left", "both", "none");
        button(g, "handsshown", t("hands_shown") + ": " + t("hands_shown." + shownHands), x, y, w, 14, false, true, t("tip.hands_shown"),
                () -> setShownHands(cycle(handOpts, shownHands, 1)), () -> setShownHands(cycle(handOpts, shownHands, -1)));
        y += 18;
        y += 4;
        txt(g, t("preview"), x, y, C_DIM);
        y += 12;
        txt(g, t("rec_mode_title"), x, y, C_DIM);
        y += 12;
        button(g, "recmode", com.fpstudio.client.MoveRecorder.cameraMode ? t("rec_mode.camera") : t("rec_mode.events"), x, y, w, 14, com.fpstudio.client.MoveRecorder.cameraMode, true, t("tip.rec_mode"),
                () -> com.fpstudio.client.MoveRecorder.cameraMode = !com.fpstudio.client.MoveRecorder.cameraMode, () -> com.fpstudio.client.MoveRecorder.cameraMode = !com.fpstudio.client.MoveRecorder.cameraMode);
        y += 18;
        y = wrap(g, t(com.fpstudio.client.MoveRecorder.cameraMode ? "hint.rec_camera" : "hint.rec_events"), x, y, w, C_DIM) + 4;
        y = toggleRow(g, "tfx", t("preview_fx"), Playback.previewFx, () -> Playback.previewFx = !Playback.previewFx, x, y, w);
        y = toggleRow(g, "tev", t("preview_events"), previewEvents, () -> previewEvents = !previewEvents, x, y, w);
        y += 2;
        y = wrap(g, t("hint.events_warn"), x, y, w, C_DIM) + 4;
        return wrap(g, t("hint.freeze"), x, y, w, C_DIM);
    }

    // ======================================================== input

    private boolean overUi(double x, double y) {
        return y < TOP_H || y > vh - botH - 3 || x > vw - PANEL_W - 8;
    }

    @Override
    public void mouseMoved(double x, double y) {
        x /= us;
        y /= us;
        if (Double.isNaN(gx)) { gx = x; gy = y; }
        double ddx = x - gx, ddy = y - gy;
        gx = x;
        gy = y;
        if (rec && Playback.playing && !overUi(x, y)) {
            boolean cam = selTrack == 2;
            boolean alt = hasShiftDown();
            if (cam != alt) { // arm: move / Shift rotates.  camera: rotate / Shift moves
                float sc = cam ? 0.1f : 0.25f;
                recV[4] -= (float) ddx * sc;
                recV[3] -= (float) ddy * sc;
            } else {
                recV[0] += (float) ddx * 0.004f;
                recV[1] -= (float) ddy * 0.004f;
            }
            return;
        }
        syncSel();
        if (!(sel instanceof Keyframe k) || selTrack == 3 || overUi(x, y) || closing) return;
        long win = Minecraft.getInstance().getWindow().getWindow();
        if (!InputConstants.isKeyDown(win, GLFW.GLFW_KEY_G)) return;
        if (selTrack >= 4) {
            // NPC bone: mouse turns the bone, Shift moves it (model pixels). NPC move track: mouse moves it in x / z.
            if (selTrack - 4 >= extras.size()) return;
            Extra ex = extras.get(selTrack - 4);
            if (ex.bone() == null) {
                nudgeCh(k, selTrack, 0, (float) ddx * 0.01f);
                nudgeCh(k, selTrack, 1, -(float) ddy * 0.01f);
            } else if (hasShiftDown()) {
                nudgeCh(k, selTrack, 0, (float) ddx * 0.1f);
                nudgeCh(k, selTrack, 1, -(float) ddy * 0.1f);
            } else {
                nudgeCh(k, selTrack, 4, (float) ddx * 0.5f);
                nudgeCh(k, selTrack, 3, (float) ddy * 0.5f);
            }
            return;
        }
        boolean rot = InputConstants.isKeyDown(win, GLFW.GLFW_KEY_R) || (selTrack == 2 && !hasShiftDown());
        if (rot) {
            float s = selTrack == 2 ? 0.1f : 0.25f;
            nudgeCh(k, selTrack, 4, -(float) ddx * s);
            nudgeCh(k, selTrack, 3, -(float) ddy * s);
        } else {
            nudgeCh(k, selTrack, 0, (float) ddx * 0.004f);
            nudgeCh(k, selTrack, 1, -(float) ddy * 0.004f);
        }
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        x /= us;
        y /= us;
        if (closing) return true;
        mx = (int) x;
        my = (int) y;
        loadWasOpen = showLoad;
        syncSel();
        if (tl.menuOpen()) { tl.menuClick(x, y); syncSel(); return true; }
        commitNum();
        nameField.focus = false;
        argField.focus = false;
        boneFilter.focus = false;

        if (showLoad) {
            if (x >= popX && x < popX + popW && y >= popY + 16 && y < popY + popH) {
                int idx = (int) ((y - (popY + 18) + loadScroll) / 15);
                if (idx >= 0 && idx < clipNames.size()) {
                    String n = clipNames.get(idx);
                    if (button == 1) {
                        ClipIO.delete(n);
                        clipNames = ClipIO.list();
                        toast(t("toast.deleted") + " " + n);
                    } else {
                        loadClip(n);
                        showLoad = false;
                    }
                }
                return true;
            }
            showLoad = false; // click outside; the Load button itself re-toggles using loadWasOpen
        }

        if (nameField.hit(x, y)) { nameField.focus = true; return true; }
        if (argField.hit(x, y) && inContent()) { argField.focus = true; return true; }
        if (boneFilter.hit(x, y) && inContent()) { boneFilter.focus = true; return true; }

        for (int i = btns.size() - 1; i >= 0; i--) {
            Btn b = btns.get(i);
            if (!inside(b.x, b.y, b.w, b.h) || (b.panel && !inContent())) continue;
            Runnable r = button == 1 ? b.r : button == 0 ? b.l : null;
            if (r != null) r.run();
            return true;
        }
        for (int i = slds.size() - 1; i >= 0; i--) {
            Sld s = slds.get(i);
            if (!inside(s.x, s.y, s.w, s.h) || (s.panel && !inContent()) || button != 0) continue;
            pushUndo();
            long now = System.nanoTime();
            if (lastClickId.equals(s.id) && now - lastClickNs < 350_000_000L && s.min <= 0 && s.max >= 0) {
                s.set.accept(0f);
                lastClickId = "";
                return true;
            }
            lastClickId = s.id;
            lastClickNs = now;
            activeSld = s;
            drag = Drag.SLIDER;
            dragVal0 = s.val;
            dragMx0 = x;
            sliderDrag(s, x);
            return true;
        }

        if (button == 0 && y >= vh - botH - 3 && y <= vh - botH + 3 && x < vw - PANEL_W - 8) {
            drag = Drag.RESIZE;
            return true;
        }
        if (y >= vh - botH && tl.mouseClicked(x, y, button)) { syncSel(); return true; }
        return super.mouseClicked(x, y, button);
    }

    private void sliderDrag(Sld s, double x) {
        float range = s.max - s.min;
        float v;
        if (hasShiftDown()) v = dragVal0 + (float) ((x - dragMx0) / s.w) * range * 0.1f;
        else v = s.min + (float) ((x - s.x) / s.w) * range;
        v = Mth.clamp(v, s.min, s.max);
        if (!hasShiftDown() && s.step > 0f) v = Mth.clamp(Math.round(v / s.step) * s.step, s.min, s.max);
        s.set.accept(v);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        x /= us;
        y /= us;
        dx /= us;
        dy /= us;
        switch (drag) {
            case SLIDER -> { if (activeSld != null) sliderDrag(activeSld, x); }
            case RESIZE -> botH = Mth.clamp((int) (vh - y + 2), 96, Math.max(96, (int) (vh * 0.6f)));
            default -> {
                if (tl.mouseDragged(x, y, button, dx, dy)) { syncSel(); return true; }
                return super.mouseDragged(x, y, button, dx, dy);
            }
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        drag = Drag.NONE;
        activeSld = null;
        boolean used = tl.mouseReleased(x / us, y / us, button);
        syncSel();
        return used || super.mouseReleased(x, y, button);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        x /= us;
        y /= us;
        int d = delta > 0 ? 1 : -1;
        mx = (int) x;
        my = (int) y;
        if (showLoad && x >= popX && x < popX + popW && y >= popY && y < popY + popH) {
            float max = Math.max(0, clipNames.size() * 15 - 150);
            loadScroll = Mth.clamp(loadScroll - d * 15, 0, max);
            return true;
        }
        if (rec && Playback.playing && !inContent()) {
            recV[2] += d * (selTrack == 2 ? 0.03f : 0.02f);
            return true;
        }
        long win = Minecraft.getInstance().getWindow().getWindow();
        if (sel instanceof Keyframe k && selTrack < 3 && InputConstants.isKeyDown(win, GLFW.GLFW_KEY_G)) {
            nudgeCh(k, selTrack, 2, d * (selTrack == 2 ? 0.03f : 0.02f));
            return true;
        }
        if (inContent()) {
            for (Sld s : slds) {
                if (s.panel && inside(s.x, s.y, s.w, s.h)) {
                    float step = s.step > 0 ? s.step : (s.max - s.min) / 200f;
                    float nv = s.val + d * step * (hasShiftDown() ? 1f : 4f);
                    s.set.accept(s.val >= s.min && s.val <= s.max ? Mth.clamp(nv, s.min, s.max) : nv);
                    return true;
                }
            }
            pScrollT -= d * 20f;
            return true;
        }
        if (y > vh - botH && tl.mouseScrolled(x, y, delta)) return true;
        return super.mouseScrolled(x, y, delta);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (closing) return true;
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (numField.focus) { cancelNum(); return true; }
            if (nameField.focus || argField.focus || boneFilter.focus) { nameField.focus = false; argField.focus = false; boneFilter.focus = false; return true; }
            if (showLoad) { showLoad = false; return true; }
            if (tl.menuOpen() || tl.hasSelection()) { tl.keyPressed(GLFW.GLFW_KEY_ESCAPE); syncSel(); return true; }
            closing = true;
            return true;
        }
        if (numField.focus) {
            if (key == GLFW.GLFW_KEY_BACKSPACE) { if (numFresh) numField.text = ""; numFresh = false; numField.back(); }
            else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_TAB) commitNum();
            else if (hasControlDown() && key == GLFW.GLFW_KEY_A) numFresh = true;
            else if (hasControlDown() && (key == GLFW.GLFW_KEY_C || key == GLFW.GLFW_KEY_X)) {
                Minecraft.getInstance().keyboardHandler.setClipboard(numField.text);
                if (key == GLFW.GLFW_KEY_X) { numField.text = ""; numFresh = false; }
            }
            else if (hasControlDown() && key == GLFW.GLFW_KEY_V) {
                numField.text = "";
                for (char ch : Minecraft.getInstance().keyboardHandler.getClipboard().toCharArray()) if ("0123456789.,-".indexOf(ch) >= 0) numField.type(ch);
                numFresh = false;
            }
            return true;
        }
        if (nameField.focus || argField.focus || boneFilter.focus) {
            TextField f = nameField.focus ? nameField : argField.focus ? argField : boneFilter;
            if (key == GLFW.GLFW_KEY_BACKSPACE) { f.back(); applyFields(); }
            else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) f.focus = false;
            else if (hasControlDown() && key == GLFW.GLFW_KEY_A) f.text = "";
            else if (hasControlDown() && (key == GLFW.GLFW_KEY_C || key == GLFW.GLFW_KEY_X)) {
                Minecraft.getInstance().keyboardHandler.setClipboard(f.text);
                if (key == GLFW.GLFW_KEY_X) { f.text = ""; applyFields(); }
            }
            else if (hasControlDown() && key == GLFW.GLFW_KEY_V) {
                for (char ch : Minecraft.getInstance().keyboardHandler.getClipboard().toCharArray()) f.type(ch);
                applyFields();
            }
            return true;
        }
        boolean ctrl = hasControlDown();
        if (ctrl && (key == GLFW.GLFW_KEY_C || key == GLFW.GLFW_KEY_V) && inContent()) {
            for (Sld sd : slds) {
                if (!sd.panel() || my < sd.y() || my >= sd.y() + sd.h() || mx < sd.x() || mx >= sd.x() + sd.w() + 49) continue;
                if (key == GLFW.GLFW_KEY_C) {
                    Minecraft.getInstance().keyboardHandler.setClipboard(trimNum(sd.val()));
                    toast(t("toast.value_copied"));
                } else {
                    try {
                        float v = Float.parseFloat(Minecraft.getInstance().keyboardHandler.getClipboard().trim().replace(',', '.'));
                        if (!Float.isNaN(v) && !Float.isInfinite(v)) {
                            if (sd.min() >= 0f && v < sd.min()) v = sd.min();
                            pushUndo();
                            sd.set().accept(v);
                        }
                    } catch (NumberFormatException ex) { toast(t("toast.bad_number")); }
                }
                return true;
            }
        }
        syncSel();
        if (tl.keyPressed(key)) { syncSel(); return true; }
        if (ctrl && (key == GLFW.GLFW_KEY_EQUAL || key == GLFW.GLFW_KEY_KP_ADD)) { changeUiScale(0.05f); return true; }
        if (ctrl && (key == GLFW.GLFW_KEY_MINUS || key == GLFW.GLFW_KEY_KP_SUBTRACT)) { changeUiScale(-0.05f); return true; }
        switch (key) {
            case GLFW.GLFW_KEY_SPACE -> togglePlay();
            case GLFW.GLFW_KEY_Z -> { if (ctrl) { if (hasShiftDown()) doRedo(); else doUndo(); } }
            case GLFW.GLFW_KEY_Y -> { if (ctrl) doRedo(); }
            case GLFW.GLFW_KEY_S -> { if (ctrl) save(); }
            case GLFW.GLFW_KEY_B -> toggleBars();
            case GLFW.GLFW_KEY_H -> toggleHands();
            case GLFW.GLFW_KEY_R -> startMoveRecord();
            case GLFW.GLFW_KEY_Q -> tl.toggleGraph();
            case GLFW.GLFW_KEY_L -> { pushUndo(); clip().loop = !clip().loop; }
            case GLFW.GLFW_KEY_M -> mirrorTrack();
            case GLFW.GLFW_KEY_E -> toggleRec();
            case GLFW.GLFW_KEY_TAB -> goStage();
            case GLFW.GLFW_KEY_N -> showGrid = !showGrid;
            case GLFW.GLFW_KEY_HOME -> setTime(0f);
            case GLFW.GLFW_KEY_END -> setTime(clip().duration);
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> fullscreenPreview();
            case GLFW.GLFW_KEY_LEFT -> setTime(Playback.time - (hasShiftDown() ? 1f : 0.05f));
            case GLFW.GLFW_KEY_RIGHT -> setTime(Playback.time + (hasShiftDown() ? 1f : 0.05f));
            case GLFW.GLFW_KEY_G -> {
                if (sel == null && selTrack != 3) tl.addKeyAtPlayhead();
                else if (sel instanceof Keyframe k) { pushUndo(); setTime(k.time); }
            }
            default -> { return super.keyPressed(key, scan, mods); }
        }
        return true;
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (nameField.focus) { nameField.type(c); applyFields(); return true; }
        if (argField.focus) { argField.type(c); applyFields(); return true; }
        if (boneFilter.focus) { boneFilter.type(c); return true; }
        if (numField.focus) {
            if ("0123456789.,-".indexOf(c) >= 0) {
                if (numFresh) numField.text = "";
                numFresh = false;
                numField.type(c);
            }
            return true;
        }
        return super.charTyped(c, mods);
    }

    private void applyFields() {
        clip().name = nameField.text;
        if (sel instanceof EventKey e) e.arg = argField.text;
    }
}
