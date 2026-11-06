package com.fpstudio.client.gui;

import com.fpstudio.client.Playback;
import com.fpstudio.model.Clip;
import com.fpstudio.model.Easing;
import com.fpstudio.model.EventKey;
import com.fpstudio.model.EventType;
import com.fpstudio.model.Keyframe;
import com.fpstudio.model.Track;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Dope sheet + graph editor. Rows per track (collapsible into one row per channel), keys that can sit on a single channel,
 * rubber-band selection, multi-key drag, copy / paste, a context menu, and a curve view where keys are dragged in time and value.
 * Everything is laid out in the studio's virtual (scaled) pixels.
 */
public final class Timeline {
    public interface Host {
        Clip clip();
        void pushUndo();
        void toast(String s);
        Font font();
        void scissor(GuiGraphics g, int x1, int y1, int x2, int y2);
        void changed();
        int vw();
        int vh();
        /** extra track groups (NPC move / bones of the selected actor); group id = 4 + index */
        default int extraCount() { return 0; }
        default String extraLabel(int i) { return ""; }
        default Track extraTrack(int i) { return null; }
        default String extraChannel(int i, int c) { return ""; }
        default float extraUnit(int i, int c) { return 1f; }
    }

    public static final int LBL_W = 100, ROW_H = 14, GROUP_H = 16, RULER_H = 14;
    private static final int C_TEXT = 0xFFECEAF5, C_DIM = 0xFF8F8BA6, C_ACC = 0xFF9A6BFF, C_ACC2 = 0xFF4FE0D0, C_WARN = 0xFFFF6B7A;
    private static final int[] COL = {0xFFFF8A5C, 0xFF5CB8FF, 0xFFB57BFF, 0xFF5CE08A};
    private static final int[] CHC = {0xFFFF6B6B, 0xFF7BE07B, 0xFF6BA8FF, 0xFFFFB454, 0xFFE8E26B, 0xFFC38BFF, 0xFF5CE8E0};
    private static final String[] TRACK_KEY = {"track.right", "track.left", "track.camera", "track.events"};
    private static final String[] ARM = {"x", "y", "z", "pitch", "yaw", "roll"};
    private static final String[] CAM = {"x", "y", "z", "pitch", "yaw", "roll", "fov"};

    public record Sel(int track, Keyframe k, int ch) {}

    private record KP(int x, int y, int track, Keyframe k, int ch, EventKey ev) {}

    private record Row(int type, int track, int ch, int y, int h) {}

    private record Item(String label, Runnable run, boolean header, boolean mark) {}

    private record CItem(int track, int ch, float dt, float[] v, boolean[] has, Easing e, EventKey ev) {}

    private static final class Orig {
        Keyframe k;
        EventKey ev;
        int track;
        float time;
        float[] vals;
        List<Integer> chs = new ArrayList<>();
    }

    private enum Mode { NONE, HEAD, DRAG, MARQUEE, PAN }

    private static List<CItem> clipboard = new ArrayList<>();

    private final Host host;

    // ---- public selection state (the studio's side panel edits `primary`)
    public Object primary;           // Keyframe or EventKey
    public int primaryTrack;
    public int activeTrack;          // row group that "+ Key" targets
    public int activeCh = -1;        // -1 = whole track

    private final Set<Sel> sel = new LinkedHashSet<>();
    private final Set<EventKey> selEv = new LinkedHashSet<>();

    private boolean graph;
    private final boolean[] expanded = new boolean[64];
    private final boolean[][] vis = new boolean[64][12];

    // ---- view
    private float pps = 90, ppsT = 90, vStart = -0.4f, vStartT = -0.4f;
    private float rowScroll, rowScrollT;
    private float gCenter, gCenterT, gPx = 2f, gPxT = 2f;
    private boolean pendingFit = true, pendingGraphFit;

    // ---- layout (set by layout())
    private int left, x0, x1, top, bottom, bodyTop, midY;

    // ---- frame data
    private final List<KP> kps = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final List<Integer> majorX = new ArrayList<>();
    private final Map<Integer, Float> anims = new HashMap<>();
    private int mx, my;
    private float dt;
    private int contentH;
    private KP hover;

    // ---- interaction
    private Mode mode = Mode.NONE;
    private int pressX, pressY;
    private boolean moved, dragStarted;
    private float pressTime;
    private final List<Orig> orig = new ArrayList<>();
    private List<Sel> baseSel = new ArrayList<>();
    private List<EventKey> baseEv = new ArrayList<>();
    private long lastClickNs;
    private int lastClickX, lastClickY;

    // ---- menu
    private final List<Item> menu = new ArrayList<>();
    private int menuX, menuY;
    private static final int MENU_W = 140, MENU_ROW = 13;

    public Timeline(Host host) {
        this.host = host;
        for (boolean[] r : vis) java.util.Arrays.fill(r, true);
        expanded[0] = true;
        for (int i = 4; i < expanded.length; i++) expanded[i] = true;
    }

    private static String t(String k) { return I18n.get("fpstudio.gui." + k); }

    private Clip clip() { return host.clip(); }

    private static final int[] XCOL = {0xFFE0B04F, 0xFF4FD0E0, 0xFFE06FA8, 0xFF8FE04F};

    private Track track(int g) { return g < 3 ? clip().track(g) : host.extraTrack(g - 4); }

    private int chCount(int g) { return g < 3 ? (g == 2 ? 7 : 6) : (host.extraTrack(g - 4) == null ? 0 : host.extraTrack(g - 4).channels); }

    private float unit(int g, int ch) { return g < 3 ? (ch < 3 ? 100f : 1f) : host.extraUnit(g - 4, ch); }

    private int col(int g) { return g < 4 ? COL[g] : XCOL[(g - 4) % XCOL.length]; }

    private String groupLabel(int g) { return g < 4 ? t(TRACK_KEY[g]) : host.extraLabel(g - 4); }

    private String chName(int g, int c) { return g < 3 ? t("ch." + (g == 2 ? CAM : ARM)[c]) : host.extraChannel(g - 4, c); }

    private List<Integer> groups() {
        List<Integer> l = new ArrayList<>();
        l.add(0); l.add(1); l.add(2);
        for (int i = 0; i < host.extraCount(); i++) l.add(4 + i);
        return l;
    }

    // ============================================================ public API

    public boolean graph() { return graph; }

    public void toggleGraph() {
        graph = !graph;
        mode = Mode.NONE;
        menu.clear();
        if (graph) {
            if (activeTrack == 3) activeTrack = 0;
            pendingGraphFit = true;
        }
    }

    /** Make a track group the target of "+ Key" and open it. */
    public void focusGroup(int g) {
        activeTrack = g;
        activeCh = -1;
        expanded[g] = true;
        sel.clear();
        selEv.clear();
        primary = null;
        pendingGraphFit = true;
        host.changed();
    }

    public void selectEvent(EventKey e) {
        sel.clear();
        selEv.clear();
        selEv.add(e);
        primary = e;
        primaryTrack = 3;
        activeTrack = 3;
        host.changed();
    }

    /** Index of the primary selection inside its track / the event list, or -1. */
    public int primaryIndex() {
        if (primary instanceof EventKey e) return clip().events.indexOf(e);
        if (primary instanceof Keyframe k) { Track t = track(primaryTrack); return t == null ? -1 : t.keys.indexOf(k); }
        return -1;
    }

    /** Re-select the key/event at the given position (used to keep the selection across undo/redo). */
    public void reselect(int track, int idx) {
        sel.clear();
        selEv.clear();
        primary = null;
        if (idx < 0) return;
        if (track == 3) {
            if (idx >= clip().events.size()) return;
            EventKey e = clip().events.get(idx);
            selEv.add(e);
            primary = e;
            primaryTrack = 3;
        } else {
            Track t = track(track);
            if (t == null || idx >= t.keys.size()) return;
            Keyframe k = t.keys.get(idx);
            sel.add(new Sel(track, k, -1));
            primary = k;
            primaryTrack = track;
        }
        activeTrack = track;
    }

    public boolean hasSelection() { return !sel.isEmpty() || !selEv.isEmpty(); }

    public int selectionCount() { return sel.size() + selEv.size(); }

    public void clearSelection() {
        sel.clear();
        selEv.clear();
        primary = null;
        mode = Mode.NONE;
        orig.clear();
        menu.clear();
        host.changed();
    }

    public Easing primaryEasing() { return primary instanceof Keyframe k ? k.easing : null; }

    public boolean menuOpen() { return !menu.isEmpty(); }

    public void layout(int leftX, int rulerTop, int rightX, int bottomY) {
        left = leftX;
        x0 = leftX + LBL_W;
        x1 = rightX;
        top = rulerTop;
        bottom = bottomY;
        bodyTop = top + RULER_H + 2;
        midY = (bodyTop + bottom) / 2;
    }

    public void fitTime() {
        float d = Math.max(0.5f, clip().duration);
        ppsT = Mth.clamp((x1 - x0 - 60) / d, 20f, 700f);
        vStartT = -24f / ppsT;
    }

    public float snap(float t) { return Screen.hasShiftDown() ? t : Math.round(t * 20f) / 20f; }

    public void setTime(float t) { Playback.time = Mth.clamp(t, 0f, clip().duration); }

    public float timeToX(float t) { return x0 + (t - vStart) * pps; }

    public float xToTime(double x) { return (float) ((x - x0) / pps + vStart); }

    // ============================================================ rendering

    public void render(GuiGraphics g, int mouseX, int mouseY, float frameDt, float dispTime) {
        mx = mouseX;
        my = mouseY;
        dt = frameDt;
        int maxG = 4 + host.extraCount();
        if (activeTrack >= maxG) { activeTrack = 0; activeCh = -1; }
        if (primary != null && primaryTrack >= maxG) primary = null;
        sel.removeIf(q -> q.track >= maxG);
        if (pendingFit && x1 > x0 + 40) { fitTime(); pendingFit = false; }
        if (pendingGraphFit) { fitGraph(); pendingGraphFit = false; }

        float k = 1f - (float) Math.exp(-dt * 14f);
        pps += (ppsT - pps) * k;
        vStart += (vStartT - vStart) * k;
        rowScroll += (rowScrollT - rowScroll) * k;
        gCenter += (gCenterT - gCenter) * k;
        gPx += (gPxT - gPx) * k;
        if (anims.size() > 1500) anims.clear();

        if (Playback.playing && mode != Mode.HEAD) {
            float x = timeToX(Playback.time);
            if (x > x1 - 30 || x < x0) vStartT = Playback.time - (x1 - x0 - 80) / ppsT;
        }

        hover = menu.isEmpty() && mx >= x0 && mx < x1 && my >= bodyTop && my < bottom ? pickKp(mx, my) : null;
        kps.clear();
        rows.clear();
        majorX.clear();

        drawRuler(g);
        if (graph) drawGraph(g); else drawDope(g);

        // clip end + origin
        int endX = (int) timeToX(clip().duration);
        host.scissor(g, x0, top, x1, bottom);
        if (endX < x1) g.fill(Math.max(endX, x0), top + RULER_H, x1, bottom, 0x55000000);
        g.vLine(endX, top, bottom, alpha(C_WARN, 0.8f));
        g.vLine((int) timeToX(0), top, bottom, alpha(C_ACC2, 0.5f));

        // playhead
        int hx = (int) timeToX(dispTime);
        g.fill(hx - 2, top, hx + 3, bottom, alpha(C_ACC2, 0.13f));
        g.vLine(hx, top, bottom, C_ACC2);
        for (int i = 0; i < 4; i++) g.fill(hx - 4 + i, top + i, hx + 5 - i, top + i + 1, C_ACC2);

        if (mode == Mode.MARQUEE && moved) {
            int ax = Math.min(pressX, mx), bx = Math.max(pressX, mx), ay = Math.min(pressY, my), by = Math.max(pressY, my);
            g.fill(ax, ay, bx, by, alpha(C_ACC, 0.15f));
            g.hLine(ax, bx, ay, C_ACC);
            g.hLine(ax, bx, by, C_ACC);
            g.vLine(ax, ay, by, C_ACC);
            g.vLine(bx, ay, by, C_ACC);
        }
        g.disableScissor();
    }

    /** Context menu; drawn last by the studio so it sits above the side panel. */
    public void renderMenu(GuiGraphics g, int mouseX, int mouseY, float frameDt) {
        mx = mouseX;
        my = mouseY;
        dt = frameDt;
        drawMenu(g);
    }

    private void drawRuler(GuiGraphics g) {
        rr(g, x0, top, x1 - x0, RULER_H, 0xFF14131E);
        host.scissor(g, x0, top, x1, top + RULER_H);
        float[] steps = {0.05f, 0.1f, 0.25f, 0.5f, 1f, 2f, 5f, 10f, 30f};
        float step = steps[steps.length - 1];
        for (float s : steps) if (s * pps >= 64f) { step = s; break; }
        float t0 = (float) Math.floor(vStart / step) * step;
        for (float tt = t0; timeToX(tt) <= x1 + 2; tt += step) {
            int px = (int) timeToX(tt);
            majorX.add(px);
            g.vLine(px, top + 7, top + RULER_H, C_DIM);
            String lab = step >= 1f ? String.format(Locale.ROOT, "%.0fs", tt) : String.format(Locale.ROOT, "%.2f", tt);
            txt(g, lab, px + 3, top + 3, C_DIM);
            for (int i = 1; i < 4; i++) g.vLine((int) timeToX(tt + step * i / 4f), top + 11, top + RULER_H, alpha(C_DIM, 0.5f));
        }
        g.disableScissor();
    }

    // ------------------------------------------------------------ dope sheet

    private void buildRows(boolean forGraph) {
        int y = bodyTop - (forGraph ? 0 : (int) rowScroll);
        for (int i : groups()) {
            rows.add(new Row(0, i, -1, y, GROUP_H));
            y += GROUP_H;
            if (forGraph ? i == activeTrack : expanded[i]) {
                for (int c = 0; c < chCount(i); c++) {
                    rows.add(new Row(1, i, c, y, ROW_H));
                    y += ROW_H;
                }
            }
        }
        if (!forGraph) {
            rows.add(new Row(2, 3, -1, y, GROUP_H));
            y += GROUP_H;
        }
        contentH = y - (bodyTop - (forGraph ? 0 : (int) rowScroll));
    }

    private void drawDope(GuiGraphics g) {
        buildRows(false);
        rowScrollT = Mth.clamp(rowScrollT, 0f, Math.max(0, contentH - (bottom - bodyTop)));
        host.scissor(g, left, bodyTop, x1, bottom);
        for (int px : majorX) g.vLine(px, bodyTop, bottom, alpha(0xFFFFFFFF, 0.05f));
        for (Row r : rows) {
            if (r.y + r.h < bodyTop || r.y > bottom) continue;
            drawStripe(g, r);
            if (r.type == 0) drawGroupKeys(g, r);
            else if (r.type == 1) drawChannelKeys(g, r);
            else drawEventKeys(g, r);
        }
        for (Row r : rows) {
            if (r.y + r.h < bodyTop || r.y > bottom) continue;
            drawLabel(g, r);
        }
        g.disableScissor();
        float maxS = Math.max(0, contentH - (bottom - bodyTop));
        if (maxS > 0) {
            int trackH = bottom - bodyTop;
            int bh = Math.max(12, (int) (trackH * (long) trackH / (float) contentH));
            int by = bodyTop + (int) ((trackH - bh) * (rowScroll / maxS));
            g.fill(x1 - 3, by, x1 - 1, by + bh, alpha(C_ACC, 0.7f));
        }
    }

    private void drawStripe(GuiGraphics g, Row r) {
        boolean act = activeTrack == r.track && (r.type == 0 ? activeCh == -1 : r.type == 1 ? activeCh == r.ch : true);
        int c = r.type == 0 || r.type == 2 ? 0x22FFFFFF : 0x0DFFFFFF;
        g.fill(x0, r.y, x1, r.y + r.h - 1, c);
        if (act && r.type != 2) g.fill(x0, r.y, x1, r.y + r.h - 1, alpha(col(r.track), 0.08f));
    }

    private void drawLabel(GuiGraphics g, Row r) {
        boolean act = activeTrack == r.track && (r.type == 0 ? activeCh == -1 : r.type == 1 ? activeCh == r.ch : activeTrack == 3);
        boolean hov = mx >= left && mx < x0 && my >= r.y && my < r.y + r.h && my >= bodyTop && my < bottom;
        float a = anim(1_000_000 + r.type * 1000 + r.track * 20 + r.ch + 1, hov || act);
        rr(g, left, r.y, LBL_W - 2, r.h - 1, lerp(0xFF14131E, 0xFF22203A, a));
        if (r.type == 0) {
            int col = lerp(C_DIM, C_TEXT, a);
            boolean open = graph ? r.track == activeTrack : expanded[r.track];
            if (open) for (int i = 0; i < 4; i++) g.fill(left + 3 + i, r.y + 5 + i, left + 11 - i, r.y + 6 + i, col);
            else for (int i = 0; i < 4; i++) g.fill(left + 4 + i, r.y + 4 + i, left + 5 + i, r.y + 12 - i, col);
            rr(g, left + 14, r.y + 5, 5, 5, col(r.track));
            txt(g, groupLabel(r.track), left + 23, r.y + 4, col);
        } else if (r.type == 1) {
            String name = chName(r.track, r.ch);
            boolean on = !graph || vis[r.track][r.ch];
            if (graph) {
                rr(g, left + 12, r.y + 3, 7, 7, on ? CHC[r.ch] : 0xFF2A2740);
            } else {
                g.fill(left + 12, r.y + 6, left + 15, r.y + 8, CHC[r.ch]);
            }
            txt(g, font().plainSubstrByWidth(name, LBL_W - 28, false), left + 22, r.y + 3, on ? lerp(C_DIM, C_TEXT, Math.max(a, act ? 1f : 0f)) : 0xFF55526A);
        } else {
            rr(g, left + 14, r.y + 5, 5, 5, COL[3]);
            txt(g, t(TRACK_KEY[3]), left + 23, r.y + 4, lerp(C_DIM, C_TEXT, a));
        }
    }

    private void drawGroupKeys(GuiGraphics g, Row r) {
        Track tr = track(r.track);
        int cy = r.y + r.h / 2 - 1;
        for (int i = 0; i + 1 < tr.keys.size(); i++) {
            int xa = (int) timeToX(tr.keys.get(i).time), xb = (int) timeToX(tr.keys.get(i + 1).time);
            if (xb < x0 || xa > x1) continue;
            g.fill(Math.max(xa, x0), cy, Math.min(xb, x1), cy + 1, alpha(col(r.track), 0.4f));
        }
        for (Keyframe k : tr.keys) {
            int cx = (int) timeToX(k.time);
            if (cx < x0 - 8 || cx > x1 + 8) continue;
            KP p = new KP(cx, cy, r.track, k, -1, null);
            kps.add(p);
            drawKey(g, p, col(r.track), 4, !k.hasAll());
        }
    }

    private void drawChannelKeys(GuiGraphics g, Row r) {
        Track tr = track(r.track);
        int cy = r.y + r.h / 2 - 1;
        Keyframe prev = null;
        for (Keyframe k : tr.keys) {
            if (!k.has(r.ch)) continue;
            int cx = (int) timeToX(k.time);
            if (prev != null) {
                int xa = (int) timeToX(prev.time);
                if (cx >= x0 && xa <= x1) g.fill(Math.max(xa, x0), cy, Math.min(cx, x1), cy + 1, alpha(CHC[r.ch], 0.35f));
            }
            prev = k;
            if (cx < x0 - 8 || cx > x1 + 8) continue;
            KP p = new KP(cx, cy, r.track, k, r.ch, null);
            kps.add(p);
            drawKey(g, p, CHC[r.ch], 3, false);
        }
    }

    private void drawEventKeys(GuiGraphics g, Row r) {
        int cy = r.y + r.h / 2 - 1;
        for (EventKey e : clip().events) {
            int cx = (int) timeToX(e.time);
            if (e.type.usesDur() && e.dur > 0f) {
                int xb = (int) timeToX(e.time + e.dur);
                g.fill(cx, cy - 3, Math.max(xb, cx + 2), cy + 4, alpha(COL[3], selEv.contains(e) ? 0.45f : 0.26f));
            }
            if (cx < x0 - 8 || cx > x1 + 8) continue;
            KP p = new KP(cx, cy, 3, null, -1, e);
            kps.add(p);
            drawKey(g, p, COL[3], 4, false);
        }
    }

    private void drawKey(GuiGraphics g, KP p, int col, int base, boolean hollow) {
        boolean s = isSel(p);
        boolean h = hover != null && hover == p;
        int id = (p.ev != null ? System.identityHashCode(p.ev) : System.identityHashCode(p.k)) * 8 + (p.ch + 1);
        float a = anim(id, h || s);
        float birth = anim(id + 7_000_000, true);
        int r = Math.max(2, Math.round((base + a * 1.5f) * (0.35f + 0.65f * easeBack(birth))));
        int fill = s ? 0xFFFFFFFF : lerp(col, 0xFFFFFFFF, a * 0.4f);
        if (p.ev != null) {
            g.fill(p.x - r, p.y - r, p.x + r, p.y + r, fill);
            g.fill(p.x - r + 1, p.y - r + 1, p.x + r - 1, p.y + r - 1, s ? col : alpha(0xFF000000, 0.35f));
            return;
        }
        diamond(g, p.x, p.y, r, fill);
        if (s) diamond(g, p.x, p.y, Math.max(1, r - 2), col);
        else if (hollow) diamond(g, p.x, p.y, Math.max(1, r - 2), 0xFF1A1826);
    }

    private static void diamond(GuiGraphics g, int cx, int cy, int r, int c) {
        for (int dy = -r; dy <= r; dy++) {
            int half = r - Math.abs(dy);
            g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, c);
        }
    }

    // ------------------------------------------------------------ graph editor

    private void drawGraph(GuiGraphics g) {
        buildRows(true);
        host.scissor(g, left, bodyTop, x1, bottom);
        for (Row r : rows) drawLabel(g, r);
        g.disableScissor();

        host.scissor(g, x0, bodyTop, x1, bottom);
        g.fill(x0, bodyTop, x1, bottom, 0x22000000);
        for (int px : majorX) g.vLine(px, bodyTop, bottom, alpha(0xFFFFFFFF, 0.05f));

        float half = (bottom - bodyTop) / 2f / gPx;
        float[] steps = {1, 2, 5, 10, 20, 50, 100, 200, 500};
        float step = steps[steps.length - 1];
        for (float s : steps) if (s * gPx >= 26f) { step = s; break; }
        float v0 = (float) Math.floor((gCenter - half) / step) * step;
        for (float v = v0; v <= gCenter + half + step; v += step) {
            int y = (int) (midY - (v - gCenter) * gPx);
            g.hLine(x0, x1, y, Math.abs(v) < 1e-3f ? alpha(0xFFFFFFFF, 0.28f) : alpha(0xFFFFFFFF, 0.07f));
            txt(g, String.format(Locale.ROOT, "%.0f", v), x0 + 3, y - 9, alpha(C_DIM, 0.8f));
        }

        if (activeTrack == 3) {
            txt(g, t("graph.only_arms"), x0 + 12, midY - 4, C_DIM);
            g.disableScissor();
            return;
        }
        Track tr = track(activeTrack);
        for (int ch = 0; ch < chCount(activeTrack); ch++) {
            if (!vis[activeTrack][ch]) continue;
            int col = CHC[ch];
            int prevY = 0;
            boolean first = true;
            for (int px = x0; px <= x1; px += 2) {
                float v = tr.sampleChannel(ch, xToTime(px)) * unit(activeTrack, ch);
                int y = (int) (midY - (v - gCenter) * gPx);
                if (first) { prevY = y; first = false; }
                int lo = Math.min(prevY, y), hi = Math.max(prevY, y);
                g.fill(px, Mth.clamp(lo, bodyTop - 4, bottom + 4), px + 2, Mth.clamp(hi, bodyTop - 4, bottom + 4) + 1, col);
                prevY = y;
            }
        }
        for (int ch = 0; ch < chCount(activeTrack); ch++) {
            if (!vis[activeTrack][ch]) continue;
            for (Keyframe k : tr.keys) {
                if (!k.has(ch)) continue;
                int cx = (int) timeToX(k.time);
                int cy = (int) (midY - (k.v[ch] * unit(activeTrack, ch) - gCenter) * gPx);
                if (cx < x0 - 8 || cx > x1 + 8) continue;
                KP p = new KP(cx, cy, activeTrack, k, ch, null);
                kps.add(p);
                boolean s = isSel(p), h = hover != null && hover == p;
                float a = anim(System.identityHashCode(k) * 8 + ch + 1, h || s);
                int r = 3 + Math.round(a * 1.5f);
                g.fill(cx - r, cy - r, cx + r + 1, cy + r + 1, s ? 0xFFFFFFFF : CHC[ch]);
                g.fill(cx - r + 1, cy - r + 1, cx + r, cy + r, s ? CHC[ch] : 0xFF14131E);
            }
        }
        if (hover != null && hover.k != null && hover.ch >= 0) {
            String s = String.format(Locale.ROOT, "%s = %.3f   t = %.2f", chName(hover.track, hover.ch), hover.k.v[hover.ch], hover.k.time);
            int w = font().width(s) + 8;
            int tx = Math.min(hover.x + 10, x1 - w - 2), ty = Math.max(bodyTop + 2, hover.y - 16);
            rr(g, tx, ty, w, 12, 0xE0000000);
            txt(g, s, tx + 4, ty + 2, C_TEXT);
        }
        g.disableScissor();
    }

    /** Frame the visible curves. */
    public void fitGraph() {
        if (activeTrack == 3) return;
        float mn = Float.MAX_VALUE, mxv = -Float.MAX_VALUE;
        Track tr = track(activeTrack);
        for (int ch = 0; ch < chCount(activeTrack); ch++) {
            if (!vis[activeTrack][ch]) continue;
            for (Keyframe k : tr.keys) {
                if (!k.has(ch)) continue;
                float v = k.v[ch] * unit(activeTrack, ch);
                mn = Math.min(mn, v);
                mxv = Math.max(mxv, v);
            }
        }
        if (mn > mxv) { gCenterT = 0f; gPxT = 2f; return; }
        float range = Math.max(10f, mxv - mn);
        gCenterT = (mn + mxv) / 2f;
        gPxT = Mth.clamp((bottom - bodyTop - 40) / range, 0.2f, 30f);
    }

    // ------------------------------------------------------------ menu

    private void drawMenu(GuiGraphics g) {
        if (menu.isEmpty()) return;
        int h = menu.size() * MENU_ROW + 4;
        rr(g, menuX, menuY, MENU_W, h, 0xF4141222);
        for (int i = 0; i < menu.size(); i++) {
            Item it = menu.get(i);
            int y = menuY + 2 + i * MENU_ROW;
            boolean hov = !it.header && mx >= menuX && mx < menuX + MENU_W && my >= y && my < y + MENU_ROW;
            float a = anim(2_000_000 + i, hov);
            if (a > 0.01f) g.fill(menuX + 2, y, menuX + MENU_W - 2, y + MENU_ROW, alpha(C_ACC, 0.3f * a));
            int c = it.header ? C_DIM : lerp(0xFFC8C5D8, C_TEXT, a);
            txt(g, (it.mark ? "> " : "  ") + it.label, menuX + 6, y + 3, c);
        }
    }

    private void openMenu(int x, int y) {
        int h = menu.size() * MENU_ROW + 4;
        menuX = Mth.clamp(x, 2, Math.max(2, host.vw() - MENU_W - 2));
        menuY = Mth.clamp(y, 2, Math.max(2, host.vh() - h - 2));
        for (int i = 0; i < menu.size(); i++) anims.put(2_000_000 + i, 0f);
    }

    private void openKeyMenu(int x, int y) {
        menu.clear();
        Easing cur = primaryEasing();
        menu.add(new Item(t("menu.interp"), null, true, false));
        for (Easing e : Easing.values()) {
            menu.add(new Item(t("ease." + e.name().toLowerCase(Locale.ROOT)), () -> setEasing(e), false, e == cur));
        }
        menu.add(new Item(t("menu.duplicate"), this::duplicateSelection, false, false));
        menu.add(new Item(t("menu.copy"), this::copy, false, false));
        menu.add(new Item(t("menu.delete"), this::deleteSelection, false, false));
        openMenu(x, y);
    }

    private void openEmptyMenu(int x, int y) {
        menu.clear();
        final int fx = x, fy = y;
        menu.add(new Item(t("menu.add_key"), () -> addAtMouse(fx, fy), false, false));
        menu.add(new Item(t("menu.paste"), this::paste, false, false));
        menu.add(new Item(t("menu.select_all"), this::selectAll, false, false));
        menu.add(new Item(graph ? t("menu.fit_curves") : t("menu.fit_time"), graph ? this::fitGraph : this::fitTime, false, false));
        openMenu(x, y);
    }

    /** Returns true if the click belonged to the open menu (or closed it). */
    public boolean menuClick(double x, double y) {
        if (menu.isEmpty()) return false;
        int idx = (int) ((y - menuY - 2) / MENU_ROW);
        if (x >= menuX && x < menuX + MENU_W && idx >= 0 && idx < menu.size()) {
            Item it = menu.get(idx);
            if (it.header) return true;
            menu.clear();
            if (it.run != null) it.run.run();
            return true;
        }
        menu.clear();
        return true;
    }

    // ============================================================ selection

    private boolean isSel(KP p) {
        if (p.ev != null) return selEv.contains(p.ev);
        if (sel.contains(new Sel(p.track, p.k, -1))) return true;
        if (p.ch >= 0) return sel.contains(new Sel(p.track, p.k, p.ch));
        // group row: selected when every keyed channel is selected one by one
        int n = 0;
        for (int c = 0; c < chCount(p.track); c++) {
            if (!p.k.has(c)) continue;
            n++;
            if (!sel.contains(new Sel(p.track, p.k, c))) return false;
        }
        return n > 0;
    }

    private void addSel(KP p) {
        if (p.ev != null) selEv.add(p.ev);
        else sel.add(new Sel(p.track, p.k, p.ch));
    }

    private void toggleSel(KP p) {
        if (p.ev != null) {
            if (!selEv.remove(p.ev)) selEv.add(p.ev);
        } else {
            Sel s = new Sel(p.track, p.k, p.ch);
            if (!sel.remove(s)) sel.add(s);
        }
    }

    private void selectOnly(KP p) {
        sel.clear();
        selEv.clear();
        addSel(p);
    }

    private void setPrimary(KP p) {
        primary = p.ev != null ? p.ev : p.k;
        primaryTrack = p.track;
        activeTrack = p.track;
        activeCh = p.ch;
        host.changed();
    }

    public void selectAll() {
        sel.clear();
        selEv.clear();
        for (int i : groups()) for (Keyframe k : track(i).keys) sel.add(new Sel(i, k, -1));
        selEv.addAll(clip().events);
        host.toast(t("toast.selected") + " " + selectionCount());
    }

    private KP pickKp(int ix, int iy) {
        KP best = null;
        int bd = Integer.MAX_VALUE;
        for (KP p : kps) {
            int dx = Math.abs(p.x - ix), dy = Math.abs(p.y - iy);
            if (dx > 6 || dy > 7) continue;
            int d = dx * dx + dy * dy;
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    // ============================================================ editing operations

    public void addKeyAtPlayhead() {
        addKey(activeTrack, activeCh, Playback.time, null);
    }

    private void addKey(int track, int ch, float time, Float value) {
        host.pushUndo();
        time = Mth.clamp(snap(time), 0f, Math.max(clip().duration, time));
        if (time > clip().duration) clip().duration = time;
        sel.clear();
        selEv.clear();
        activeTrack = track;
        activeCh = ch;
        if (track == 3) {
            EventKey e = new EventKey(time, EventType.SWING_MAIN, "");
            clip().events.add(e);
            clip().sortEvents();
            selEv.add(e);
            primary = e;
            primaryTrack = 3;
        } else {
            Track tr = track(track);
            float[] tmp = new float[12];
            tr.sample(time, tmp);
            Keyframe k;
            if (ch < 0) {
                k = new Keyframe(tr.channels);
                System.arraycopy(tmp, 0, k.v, 0, tr.channels);
                k.time = time;
                k.easing = Easing.SPLINE;
                tr.put(k);
                sel.add(new Sel(track, k, -1));
            } else {
                k = tr.putChannel(time, ch, value != null ? value : tmp[ch], Easing.SPLINE);
                sel.add(new Sel(track, k, ch));
            }
            primary = k;
            primaryTrack = track;
        }
        setTime(time);
        host.changed();
    }

    private void addAtMouse(int ix, int iy) {
        float time = xToTime(ix);
        if (graph) {
            if (activeTrack == 3) return;
            Track tr = track(activeTrack);
            int best = -1;
            float bd = 16f;
            for (int ch = 0; ch < chCount(activeTrack); ch++) {
                if (!vis[activeTrack][ch]) continue;
                float v = tr.sampleChannel(ch, time) * unit(activeTrack, ch);
                float d = Math.abs(midY - (v - gCenter) * gPx - iy);
                if (d < bd) { bd = d; best = ch; }
            }
            if (best < 0) best = activeCh >= 0 && vis[activeTrack][activeCh] ? activeCh : -1;
            if (best < 0) { addKey(activeTrack, -1, time, null); return; }
            float value = ((midY - iy) / gPx + gCenter) / unit(activeTrack, best);
            addKey(activeTrack, best, time, value);
            return;
        }
        for (Row r : rows) {
            if (iy < r.y || iy >= r.y + r.h) continue;
            if (r.type == 0) addKey(r.track, -1, time, null);
            else if (r.type == 1) addKey(r.track, r.ch, time, null);
            else addKey(3, -1, time, null);
            return;
        }
    }

    public void deleteSelection() {
        if (!hasSelection()) return;
        host.pushUndo();
        for (Sel s : sel) {
            Track tr = track(s.track);
            if (s.ch < 0) {
                tr.keys.remove(s.k);
            } else {
                s.k.setHas(s.ch, false);
                if (!s.k.any()) tr.keys.remove(s.k);
            }
        }
        clip().events.removeAll(selEv);
        sel.clear();
        selEv.clear();
        primary = null;
        host.changed();
    }

    public void setEasing(Easing e) {
        if (!hasSelection() && !(primary instanceof Keyframe)) return;
        host.pushUndo();
        for (Sel s : sel) s.k.easing = e;
        if (primary instanceof Keyframe k) k.easing = e;
    }

    public void copy() {
        if (!hasSelection()) return;
        float minT = Float.MAX_VALUE;
        for (Sel s : sel) minT = Math.min(minT, s.k.time);
        for (EventKey e : selEv) minT = Math.min(minT, e.time);
        List<CItem> out = new ArrayList<>();
        for (Sel s : sel) {
            out.add(new CItem(s.track, s.ch, s.k.time - minT, s.k.v.clone(), s.k.has == null ? null : s.k.has.clone(), s.k.easing, null));
        }
        for (EventKey e : selEv) out.add(new CItem(3, -1, e.time - minT, null, null, null, e.copy()));
        clipboard = out;
        host.toast(t("toast.copied") + " " + out.size());
    }

    public void paste() { pasteAt(Playback.time); }

    private void pasteAt(float time) {
        if (clipboard.isEmpty()) return;
        host.pushUndo();
        sel.clear();
        selEv.clear();
        float end = 0f;
        for (CItem c : clipboard) {
            float t = Math.max(0f, time + c.dt);
            end = Math.max(end, t);
            if (c.ev != null) {
                EventKey e = c.ev.copy();
                e.time = t;
                clip().events.add(e);
                selEv.add(e);
            } else {
                Track tr = track(c.track);
                if (c.ch < 0) {
                    for (int ch = 0; ch < tr.channels; ch++) {
                        if (c.has != null && !c.has[ch]) continue;
                        Keyframe k = tr.putChannel(t, ch, c.v[ch], c.e);
                        sel.add(new Sel(c.track, k, ch));
                    }
                } else {
                    Keyframe k = tr.putChannel(t, c.ch, c.v[c.ch], c.e);
                    sel.add(new Sel(c.track, k, c.ch));
                }
            }
        }
        clip().sortEvents();
        if (end > clip().duration) clip().duration = end;
        host.toast(t("toast.pasted") + " " + clipboard.size());
        host.changed();
    }

    public void duplicateSelection() {
        if (!hasSelection()) return;
        float maxT = 0f;
        for (Sel s : sel) maxT = Math.max(maxT, s.k.time);
        for (EventKey e : selEv) maxT = Math.max(maxT, e.time);
        copy();
        pasteAt(maxT + 0.25f);
    }

    private void nudge(float d) {
        if (!hasSelection()) return;
        host.pushUndo();
        float minT = Float.MAX_VALUE;
        for (Sel s : sel) minT = Math.min(minT, s.k.time);
        for (EventKey e : selEv) minT = Math.min(minT, e.time);
        d = Math.max(d, -minT);
        Set<Keyframe> done = new LinkedHashSet<>();
        for (Sel s : sel) if (done.add(s.k)) s.k.time += d;
        for (EventKey e : selEv) e.time += d;
        finishDrag();
    }

    // ============================================================ drag

    private void beginDrag() {
        host.pushUndo();
        orig.clear();
        dragStarted = true;
        Map<Keyframe, List<Integer>> chans = new LinkedHashMap<>();
        Map<Keyframe, Integer> trackOf = new LinkedHashMap<>();
        for (Sel s : sel) {
            chans.computeIfAbsent(s.k, k -> new ArrayList<>()).add(s.ch);
            trackOf.put(s.k, s.track);
        }
        List<Sel> newSel = new ArrayList<>();
        for (Map.Entry<Keyframe, List<Integer>> en : chans.entrySet()) {
            Keyframe key = en.getKey();
            int track = trackOf.get(key);
            List<Integer> chs = en.getValue();
            List<Integer> want = new ArrayList<>();
            if (chs.contains(-1)) {
                for (int c = 0; c < chCount(track); c++) if (key.has(c)) want.add(c);
            } else {
                for (int c : chs) if (key.has(c) && !want.contains(c)) want.add(c);
            }
            Keyframe moving = key;
            if (want.size() < key.count()) moving = detach(track, key, want);
            Orig o = new Orig();
            o.k = moving;
            o.track = track;
            o.time = moving.time;
            o.vals = moving.v.clone();
            o.chs.addAll(want);
            orig.add(o);
            newSel.add(new Sel(track, moving, -1));
        }
        sel.clear();
        sel.addAll(newSel);
        for (EventKey e : selEv) {
            Orig o = new Orig();
            o.ev = e;
            o.track = 3;
            o.time = e.time;
            orig.add(o);
        }
    }

    /** Split the given channels of {@code key} into a new key at the same time. */
    private Keyframe detach(int track, Keyframe key, List<Integer> chs) {
        Track tr = track(track);
        Keyframe nk = new Keyframe(tr.channels);
        nk.time = key.time;
        nk.easing = key.easing;
        nk.has = new boolean[tr.channels];
        for (int c : chs) {
            nk.has[c] = true;
            nk.v[c] = key.v[c];
            key.setHas(c, false);
        }
        if (!key.any()) tr.keys.remove(key);
        tr.keys.add(nk);
        if (primary == key) primary = nk;
        return nk;
    }

    private void applyDrag(int ix, int iy) {
        float dtRaw = xToTime(ix) - pressTime;
        float d = Screen.hasShiftDown() ? dtRaw : Math.round(dtRaw * 20f) / 20f;
        float minT = Float.MAX_VALUE, maxT = 0f;
        for (Orig o : orig) { minT = Math.min(minT, o.time); maxT = Math.max(maxT, o.time); }
        d = Math.max(d, -minT);
        if (maxT + d > clip().duration) clip().duration = maxT + d;
        float dv = graph ? -(iy - pressY) / gPx : 0f;
        for (Orig o : orig) {
            if (o.ev != null) {
                o.ev.time = o.time + d;
            } else {
                o.k.time = o.time + d;
                if (graph) for (int c : o.chs) o.k.v[c] = o.vals[c] + dv / unit(o.track, c);
            }
        }
        if (orig.size() == 1 && orig.get(0).k != null) setTime(orig.get(0).time + d);
    }

    private void finishDrag() {
        for (int i : groups()) {
            Map<Keyframe, Keyframe> rep = track(i).mergeCoincident();
            if (rep.isEmpty()) continue;
            List<Sel> fixed = new ArrayList<>();
            for (Sel s : sel) fixed.add(s.track == i && rep.containsKey(s.k) ? new Sel(i, rep.get(s.k), s.ch) : s);
            sel.clear();
            sel.addAll(fixed);
            if (primary instanceof Keyframe pk && rep.containsKey(pk)) primary = rep.get(pk);
        }
        clip().sortEvents();
        orig.clear();
        dragStarted = false;
    }

    // ============================================================ input

    public boolean mouseClicked(double x, double y, int btn) {
        if (x < left || x >= x1 || y < top || y >= bottom) return false;
        int ix = (int) x, iy = (int) y;
        if (ix < x0) return labelClick(ix, iy, btn);
        if (iy < top + RULER_H) {
            if (btn == 0) { mode = Mode.HEAD; setTime(snap(xToTime(x))); }
            return true;
        }
        if (btn == 2) { mode = Mode.PAN; return true; }

        KP hit = pickKp(ix, iy);
        if (hit != null) {
            if (btn == 1) {
                if (!isSel(hit)) selectOnly(hit);
                setPrimary(hit);
                openKeyMenu(ix, iy);
                return true;
            }
            if (btn != 0) return true;
            boolean add = Screen.hasShiftDown() || Screen.hasControlDown();
            if (add) toggleSel(hit);
            else if (!isSel(hit)) selectOnly(hit);
            setPrimary(hit);
            if (!add) {
                if (hit.k != null) setTime(hit.k.time);
                else if (hit.ev != null) setTime(hit.ev.time);
            }
            if (isSel(hit)) {
                mode = Mode.DRAG;
                moved = false;
                dragStarted = false;
                pressX = ix;
                pressY = iy;
                pressTime = xToTime(x);
            }
            return true;
        }
        if (btn == 1) { openEmptyMenu(ix, iy); return true; }
        if (btn != 0) return true;

        long now = System.nanoTime();
        if (now - lastClickNs < 320_000_000L && Math.abs(ix - lastClickX) < 6 && Math.abs(iy - lastClickY) < 6) {
            lastClickNs = 0;
            addAtMouse(ix, iy);
            return true;
        }
        lastClickNs = now;
        lastClickX = ix;
        lastClickY = iy;
        mode = Mode.MARQUEE;
        moved = false;
        pressX = ix;
        pressY = iy;
        boolean keep = Screen.hasShiftDown();
        baseSel = keep ? new ArrayList<>(sel) : new ArrayList<>();
        baseEv = keep ? new ArrayList<>(selEv) : new ArrayList<>();
        if (!keep) { sel.clear(); selEv.clear(); }
        return true;
    }

    private boolean labelClick(int ix, int iy, int btn) {
        if (iy < bodyTop) return true;
        for (Row r : rows) {
            if (iy < r.y || iy >= r.y + r.h) continue;
            if (r.type == 0) {
                if (!graph) expanded[r.track] = !expanded[r.track];
                activeTrack = r.track;
                activeCh = -1;
                if (graph) pendingGraphFit = true;
            } else if (r.type == 1) {
                if (graph) {
                    vis[r.track][r.ch] = !vis[r.track][r.ch];
                    pendingGraphFit = true;
                }
                activeTrack = r.track;
                activeCh = r.ch;
            } else {
                activeTrack = 3;
                activeCh = -1;
            }
            host.changed();
            return true;
        }
        return true;
    }

    public boolean mouseDragged(double x, double y, int btn, double dx, double dy) {
        int ix = (int) x, iy = (int) y;
        switch (mode) {
            case HEAD -> setTime(snap(xToTime(x)));
            case PAN -> {
                vStartT -= (float) dx / pps;
                vStart = vStartT;
                if (graph) { gCenterT += (float) dy / gPx; gCenter = gCenterT; }
                else { rowScrollT -= (float) dy; rowScroll = rowScrollT; }
            }
            case DRAG -> {
                if (!moved && Math.abs(ix - pressX) + Math.abs(iy - pressY) < 3) return true;
                moved = true;
                if (!dragStarted) beginDrag();
                applyDrag(ix, iy);
            }
            case MARQUEE -> {
                if (!moved && Math.abs(ix - pressX) + Math.abs(iy - pressY) < 3) return true;
                moved = true;
                int ax = Math.min(pressX, ix), bx = Math.max(pressX, ix), ay = Math.min(pressY, iy), by = Math.max(pressY, iy);
                sel.clear();
                selEv.clear();
                sel.addAll(baseSel);
                selEv.addAll(baseEv);
                for (KP p : kps) if (p.x >= ax && p.x <= bx && p.y >= ay && p.y <= by) addSel(p);
            }
            default -> { return false; }
        }
        return true;
    }

    public boolean mouseReleased(double x, double y, int btn) {
        Mode m = mode;
        mode = Mode.NONE;
        if (m == Mode.DRAG && dragStarted) finishDrag();
        if (m == Mode.MARQUEE) {
            if (!moved) {
                setTime(snap(xToTime(pressX)));
            } else {
                Sel last = null;
                for (Sel s : sel) last = s;
                if (last != null) { primary = last.k; primaryTrack = last.track; activeTrack = last.track; }
                else if (!selEv.isEmpty()) { primary = selEv.iterator().next(); primaryTrack = 3; activeTrack = 3; }
                host.changed();
            }
        }
        return m != Mode.NONE;
    }

    public boolean mouseScrolled(double x, double y, double delta) {
        if (x < left || x >= x1 || y < top || y >= bottom) return false;
        int d = delta > 0 ? 1 : -1;
        if (Screen.hasControlDown()) {
            float tUnder = xToTime(x);
            ppsT = Mth.clamp(ppsT * (d > 0 ? 1.18f : 1f / 1.18f), 20f, 700f);
            vStartT = tUnder - (float) ((x - x0) / ppsT);
            return true;
        }
        if (Screen.hasShiftDown()) {
            vStartT -= d * 40f / ppsT;
            return true;
        }
        if (graph) {
            float vUnder = gCenter + (float) ((midY - y) / gPx);
            gPxT = Mth.clamp(gPxT * (d > 0 ? 1.15f : 1f / 1.15f), 0.2f, 40f);
            gCenterT = vUnder - (float) ((midY - y) / gPxT);
            return true;
        }
        rowScrollT -= d * ROW_H * 2f;
        return true;
    }

    /** Timeline shortcuts. Returns true when the key was used. */
    public boolean keyPressed(int key) {
        boolean ctrl = Screen.hasControlDown(), shift = Screen.hasShiftDown(), alt = Screen.hasAltDown();
        switch (key) {
            case GLFW.GLFW_KEY_ESCAPE -> {
                if (!menu.isEmpty()) { menu.clear(); return true; }
                if (hasSelection()) { clearSelection(); return true; }
                return false;
            }
            case GLFW.GLFW_KEY_DELETE, GLFW.GLFW_KEY_BACKSPACE -> {
                if (!hasSelection()) return false;
                deleteSelection();
                return true;
            }
            case GLFW.GLFW_KEY_A -> { if (!ctrl) return false; selectAll(); return true; }
            case GLFW.GLFW_KEY_C -> { if (!ctrl) return false; copy(); return true; }
            case GLFW.GLFW_KEY_V -> { if (!ctrl) return false; paste(); return true; }
            case GLFW.GLFW_KEY_D -> { if (!ctrl) return false; duplicateSelection(); return true; }
            case GLFW.GLFW_KEY_K -> { addKeyAtPlayhead(); return true; }
            case GLFW.GLFW_KEY_F -> { if (graph) fitGraph(); else fitTime(); return true; }
            case GLFW.GLFW_KEY_LEFT -> { if (!alt || !hasSelection()) return false; nudge(shift ? -0.25f : -0.05f); return true; }
            case GLFW.GLFW_KEY_RIGHT -> { if (!alt || !hasSelection()) return false; nudge(shift ? 0.25f : 0.05f); return true; }
            default -> { return false; }
        }
    }

    // ============================================================ small helpers

    private Font font() { return host.font(); }

    private void txt(GuiGraphics g, String s, int x, int y, int c) { g.drawString(font(), s, x, y, c, false); }

    private static void rr(GuiGraphics g, int x, int y, int w, int h, int c) {
        g.fill(x + 1, y, x + w - 1, y + h, c);
        g.fill(x, y + 1, x + w, y + h - 1, c);
    }

    private static int alpha(int c, float a) { return (c & 0xFFFFFF) | ((int) (Mth.clamp(a, 0f, 1f) * 255f) << 24); }

    private static int lerp(int a, int b, float t) {
        t = Mth.clamp(t, 0f, 1f);
        int out = 0;
        for (int s = 0; s <= 24; s += 8) {
            int ca = (a >> s) & 255, cb = (b >> s) & 255;
            out |= ((int) (ca + (cb - ca) * t) & 255) << s;
        }
        return out;
    }

    private static float easeBack(float t) {
        t = Mth.clamp(t, 0f, 1f);
        float c1 = 1.2f, c3 = c1 + 1f, u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }

    private float anim(int id, boolean on) {
        float a = anims.getOrDefault(id, 0f);
        a += ((on ? 1f : 0f) - a) * (1f - (float) Math.exp(-dt * 16f));
        anims.put(id, a);
        return a;
    }
}
