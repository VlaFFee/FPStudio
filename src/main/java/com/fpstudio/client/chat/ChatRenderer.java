package com.fpstudio.client.chat;

import com.fpstudio.chat.ChatCfg;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Draws the chat panel (anchored, animated). The same code draws the live preview in the editor. */
public final class ChatRenderer {
    /** a drawn text line, in chat space (before cfg.scale), for mouse hit testing */
    public record Hit(float x0, float y0, float x1, float y1, ChatMsg msg, int lineIndex, float scale, float textScale, float textX) {}

    public static final List<Hit> hits = new ArrayList<>();
    /** bounds of the chat box of the last draw, in GUI pixels */
    public static float boxX0, boxY0, boxX1, boxY1;
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm");
    private static long lastNano;

    private ChatRenderer() {}

    public static ResourceLocation fontOf(ChatCfg cfg) {
        try {
            return new ResourceLocation(cfg.font);
        } catch (RuntimeException e) {
            return new ResourceLocation("minecraft", "default");
        }
    }

    // ------------------------------------------------------------------ anchors

    /** {left, baseline, down(0/1)} in GUI pixels. Messages stack upward from the baseline (down=1: downward from it). */
    public static int[] place(ChatCfg c, int sw, int sh) {
        String a = c.anchor;
        boolean top = a.startsWith("top"), mid = a.startsWith("middle");
        boolean l = a.endsWith("left"), r = a.endsWith("right");
        int bw = Math.round(c.width * c.scale);
        int left = l ? c.x : r ? sw - bw - c.x : (sw - bw) / 2 + c.x;
        int base = top ? c.y : mid ? sh / 2 - c.y : sh - c.y;
        return new int[]{left, base, top ? 1 : 0};
    }

    /** Set cfg.anchor/x/y so that the chat keeps the absolute position (left, baseline) under a new anchor. */
    public static void reanchor(ChatCfg c, String anchor, int sw, int sh, int left, int base) {
        boolean top = anchor.startsWith("top"), mid = anchor.startsWith("middle");
        boolean l = anchor.endsWith("left"), r = anchor.endsWith("right");
        int bw = Math.round(c.width * c.scale);
        c.anchor = anchor;
        c.x = l ? left : r ? sw - bw - left : left - (sw - bw) / 2;
        c.y = top ? base : mid ? sh / 2 - base : sh - base;
    }

    /** Default margin position at an anchor (used by the anchor grid). */
    public static void snapToAnchor(ChatCfg c, String anchor) {
        c.anchor = anchor;
        c.x = anchor.endsWith("center") ? 0 : 6;
        c.y = anchor.startsWith("bottom") ? 60 : anchor.startsWith("middle") ? 0 : 8;
    }

    // ------------------------------------------------------------------ layout cache

    private static void layout(ChatMsg m, ChatCfg cfg, Font font) {
        if (m.lines != null && m.cacheVer == cfg.ver && m.cacheW == cfg.width && m.cacheScale == cfg.scale && m.cacheTextScale == cfg.textScale) return;
        float ts = cfg.textScale;
        boolean icon = cfg.icons && ChatIcons.has(m.icon);
        int textW = cfg.width - cfg.padX * 2 - (icon ? cfg.iconSize + cfg.iconGap : 0) - (cfg.style.equals("bar") ? 3 : 0);
        Component body = m.text;
        if (cfg.timestamps) body = Component.literal("[" + TIME.format(new Date(m.time)) + "] ").withStyle(net.minecraft.ChatFormatting.GRAY).append(body);
        Component styled = Component.empty().withStyle(Style.EMPTY.withFont(fontOf(cfg))).append(body);
        m.lines = font.split(styled, Math.max(30, Math.round(textW / ts)));
        float lh = font.lineHeight * ts + cfg.lineSpacing;
        float textH = m.lines.size() * lh - cfg.lineSpacing;
        m.textW = textW;
        m.height = Math.round(Math.max(textH, icon ? cfg.iconSize : 0)) + cfg.padY * 2;
        m.cacheVer = cfg.ver;
        m.cacheW = cfg.width;
        m.cacheScale = cfg.scale;
        m.cacheTextScale = ts;
        m.fxLine = new boolean[m.lines.size()];
        for (int i = 0; i < m.fxLine.length; i++) m.fxLine[i] = TextFx.hasFx(m.lines.get(i));
        if (cfg.typewriter) ChatTyper.schedule(m, cfg);
        else { m.sched = null; m.cps = null; }
    }

    private static float easeOut(float t) { float u = 1f - t; return 1f - u * u * u; }

    private static float easeBack(float t) {
        float c1 = 1.70158f, c3 = c1 + 1f, u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }

    // ------------------------------------------------------------------ draw

    /**
     * Draw the chat. {@code openAmt} 0..1 = how open the chat screen is (1: every message visible and scrollable);
     * at 0 only recent messages show and fade out. {@code glide}: smooth movement of messages. Returns drawn height (GUI px).
     */
    public static int draw(GuiGraphics g, List<ChatMsg> list, ChatCfg cfg, int sw, int sh, float openAmt, int scroll, boolean glide, boolean collectHits) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        if (collectHits) hits.clear();
        long nn = System.nanoTime();
        float dt = lastNano == 0 ? 0.016f : Math.min(0.1f, (nn - lastNano) / 1e9f);
        lastNano = nn;
        float k = glide ? 1f - (float) Math.exp(-dt * 16f) : 1f;
        long now = System.currentTimeMillis();

        int[] pl = place(cfg, sw, sh);
        float s = cfg.scale, ts = cfg.textScale;
        boolean down = pl[2] == 1;
        boolean rightSide = cfg.anchor.endsWith("right");
        float left = pl[0] / s, w = cfg.width;
        float y = pl[1] / s;
        int bgRgb = ChatCfg.rgb(cfg.bgColor, 0);
        int textRgb = ChatCfg.rgb(cfg.textColor, 0xFFFFFF);
        int lh = Math.round(font.lineHeight * ts) + cfg.lineSpacing;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        boolean open = openAmt > 0.01f;
        int maxShow = open ? cfg.openMessages : cfg.idleMessages;
        int shown = 0;

        g.pose().pushPose();
        g.pose().scale(s, s, 1f);
        for (int i = list.size() - 1 - (openAmt > 0.5f ? Math.max(0, scroll) : 0); i >= 0 && shown < maxShow; i--) {
            ChatMsg m = list.get(i);
            float age = (now - m.time) / 1000f;
            // ---- timed visibility (closed chat)
            float outT = cfg.fadeSeconds <= 0.001f ? (age > cfg.showSeconds ? 1f : 0f) : Math.max(0f, Math.min(1f, (age - cfg.showSeconds) / cfg.fadeSeconds));
            if (cfg.animOut.equals("none")) outT = age > cfg.showSeconds ? 1f : 0f;
            float timed = shown >= cfg.idleMessages ? 0f : 1f - outT;
            float outShift = outT * (1f - openAmt);
            float alphaOut = Math.max(timed, openAmt);
            if (alphaOut < 0.01f) {
                if (!open && age > cfg.showSeconds + cfg.fadeSeconds) break;
                continue;
            }
            // ---- entry animation
            float inT = cfg.animIn.equals("none") || cfg.inSeconds <= 0.001f ? 1f : Math.max(0f, Math.min(1f, age / cfg.inSeconds));
            float e = cfg.animIn.equals("pop") ? easeBack(inT) : easeOut(inT);
            float alpha = alphaOut * (cfg.animIn.equals("none") ? 1f : Math.min(1f, inT * 1.6f));
            float ox = 0f, oy = 0f, ms = 1f;
            switch (cfg.animIn) {
                case "slide_up" -> oy = (1f - e) * cfg.slidePx * (down ? -1f : 1f);
                case "slide_side" -> ox = (1f - e) * cfg.slidePx * (rightSide ? 1f : -1f);
                case "pop" -> ms = 0.8f + 0.2f * e;
                default -> { }
            }
            switch (cfg.animOut) {
                case "slide" -> ox += outShift * cfg.slidePx * (rightSide ? 1f : -1f);
                case "shrink" -> ms *= 1f - 0.12f * outShift;
                default -> { }
            }
            layout(m, cfg, font);
            float target = down ? y : y - m.height;
            if (cfg.vcenter) target = y - m.height / 2f;
            if (Float.isNaN(m.shownY)) m.shownY = target; else m.shownY += (target - m.shownY) * k;
            if (Math.abs(target - m.shownY) < 0.15f) m.shownY = target;
            y = down ? y + m.height + cfg.gap : target - cfg.gap;

            int top = Math.round(m.shownY), bot = top + m.height, lx = Math.round(left), rx = Math.round(left + w);
            minY = Math.min(minY, top);
            maxY = Math.max(maxY, bot);
            float cxm = left + w / 2f, cym = top + m.height / 2f;
            g.pose().pushPose();
            g.pose().translate(cxm + ox, cym + oy, 0f);
            g.pose().scale(ms, ms, 1f);
            g.pose().translate(-cxm, -cym, 0f);

            int ba = Math.round(cfg.bgAlpha / 100f * 255f * alpha);
            if (!cfg.style.equals("none") && ba > 0) {
                int col = (ba << 24) | bgRgb;
                if (cfg.rounded && m.height > 6) {
                    g.fill(lx + 1, top, rx - 1, bot, col);
                    g.fill(lx, top + 1, lx + 1, bot - 1, col);
                    g.fill(rx - 1, top + 1, rx, bot - 1, col);
                } else {
                    g.fill(lx, top, rx, bot, col);
                }
            }
            int cx = lx + cfg.padX;
            if (cfg.style.equals("bar")) {
                g.fill(lx, top, lx + 2, bot, ((Math.max(4, Math.round(255 * alpha))) << 24) | (m.nameRgb & 0xFFFFFF));
                cx += 3;
            }
            if (cfg.icons && ChatIcons.has(m.icon)) {
                ChatIcons.draw(g, m.icon, cx, top + (m.height - cfg.iconSize) / 2, cfg.iconSize, alpha);
                cx += cfg.iconSize + cfg.iconGap;
            }
            int textH = m.lines.size() * lh - cfg.lineSpacing;
            int ty = top + (m.height - textH) / 2;
            int color = ((Math.max(4, Math.round(255 * alpha))) << 24) | textRgb;
            int rev = Integer.MAX_VALUE;
            if (cfg.typewriter && m.sched != null) {
                rev = ChatTyper.revealed(m, age);
                if (rev > m.lastRevealed) {
                    if (m.lastRevealed > 0 || age < m.typeTotal + 0.5f) ChatTyper.blips(m, cfg, m.lastRevealed, Math.min(rev, m.sched.length));
                    m.lastRevealed = rev;
                }
            }
            for (int l = 0; l < m.lines.size(); l++) {
                int vis = rev == Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.min(m.lineLen[l], rev - m.lineOff[l]);
                if (vis <= 0) break;
                FormattedCharSequence seq = vis >= (m.lineLen == null ? Integer.MAX_VALUE : m.lineLen[l]) ? m.lines.get(l) : ChatTyper.cut(m.lines.get(l), vis);
                float ly = ty + l * lh;
                float ax = 0f;
                if (cfg.align > 0) ax = Math.max(0f, (m.textW - font.width(m.lines.get(l)) * ts) * (cfg.align == 1 ? 0.5f : 1f));
                g.pose().pushPose();
                g.pose().translate(cx + ax, ly, 0f);
                g.pose().scale(ts, ts, 1f);
                if (cfg.fxEnabled && m.fxLine != null && m.fxLine[l]) TextFx.drawLine(g, font, seq, color, cfg.shadow, cfg, System.identityHashCode(m) * 31 + l * 101);
                else g.drawString(font, seq, 0, 0, color, cfg.shadow);
                g.pose().popPose();
                if (collectHits) hits.add(new Hit(cx, ly, rx, ly + font.lineHeight * ts, m, l, s, ts, cx));
            }
            g.pose().popPose();
            shown++;
        }
        g.pose().popPose();
        if (minY == Float.MAX_VALUE) {
            minY = maxY = y;
        }
        boxX0 = pl[0];
        boxX1 = pl[0] + cfg.width * s;
        boxY0 = minY * s;
        boxY1 = maxY * s;
        return Math.round((maxY - minY) * s);
    }

    /** Style under the mouse (GUI pixels), or null. */
    public static Style styleAt(double mx, double my) {
        Minecraft mc = Minecraft.getInstance();
        for (Hit h : hits) {
            double px = mx / h.scale(), py = my / h.scale();
            if (px >= h.x0() && px < h.x1() && py >= h.y0() && py < h.y1()) {
                return mc.font.getSplitter().componentStyleAtWidth(h.msg().lines.get(h.lineIndex()), (int) ((px - h.textX()) / h.textScale()));
            }
        }
        return null;
    }
}
