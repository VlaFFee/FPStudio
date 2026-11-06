package com.fpstudio.client.chat;

import com.fpstudio.chat.ChatCfg;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Collects incoming chat messages and prepares them (player restyle, icon marker, rules). */
public final class ChatStore {
    public static final String MARKER = "fpicon:";
    private static final List<ChatMsg> msgs = new ArrayList<>();
    private static final Map<String, Pattern> patterns = new HashMap<>();
    /** how many lines the chat is scrolled up (0 = newest at the bottom), counted in messages */
    public static int scroll;

    private ChatStore() {}

    public static List<ChatMsg> all() { return msgs; }

    public static void clear() { msgs.clear(); scroll = 0; }

    private static Pattern pat(String s) {
        Pattern p = patterns.get(s);
        if (p == null && !patterns.containsKey(s)) {
            try {
                p = Pattern.compile(s, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            } catch (RuntimeException e) {
                p = null;
            }
            patterns.put(s, p);
        }
        return p;
    }

    public static void add(Component raw) {
        ChatMsg m = process(raw, ChatCfg.get(), System.currentTimeMillis());
        if (m == null) return;
        msgs.add(m);
        while (msgs.size() > 300) msgs.remove(0);
        if (scroll > 0) scroll++;
    }

    /** Turn a raw component into a chat entry (null = hidden by a rule). Also used by the editor preview. */
    public static ChatMsg process(Component raw, ChatCfg cfg, long time) {
        String icon = "";
        Component body = raw;
        // 1. icon marker put in front by a server (the NPC `say` command)
        var sib = raw.getSiblings();
        if (!sib.isEmpty()) {
            Style st = sib.get(0).getStyle();
            String ins = st == null ? null : st.getInsertion();
            if (ins != null && ins.startsWith(MARKER)) {
                icon = ins.substring(MARKER.length());
                MutableComponent rest = Component.empty().withStyle(raw.getStyle());
                for (int i = 1; i < sib.size(); i++) rest.append(sib.get(i));
                body = rest;
            }
        }
        // 2. vanilla player chat  <Name> text  ->  [Name]: text   (+ head icon)
        int nameRgb = CfgColors.rgb(cfg.nameColor);
        if (body.getContents() instanceof TranslatableContents tc && tc.getKey().equals("chat.type.text") && tc.getArgs().length >= 2) {
            Object a0 = tc.getArgs()[0], a1 = tc.getArgs()[1];
            Component nameC = a0 instanceof Component c ? c : Component.literal(String.valueOf(a0));
            Component textC = a1 instanceof Component c ? c : Component.literal(String.valueOf(a1));
            if (icon.isEmpty() && cfg.playerHeads) icon = "player:" + nameC.getString();
            if (cfg.restylePlayers) {
                MutableComponent n = nameC.copy();
                if (n.getStyle().getColor() == null) n = n.withStyle(Style.EMPTY.withColor(nameRgb));
                else nameRgb = n.getStyle().getColor().getValue();
                MutableComponent out = Component.empty();
                if (cfg.bracketNames) out.append(Component.literal("[").withStyle(n.getStyle()));
                out.append(n);
                if (cfg.bracketNames) out.append(Component.literal("]").withStyle(n.getStyle()));
                out.append(Component.literal(": "));
                out.append(textC);
                body = out;
            }
        }
        body = TextFx.parse(body, cfg);
        // 3. rules
        String plain = body.getString();
        for (ChatCfg.Rule r : cfg.rules) {
            if (!r.enabled || r.pattern.isEmpty()) continue;
            Pattern p = pat(r.pattern);
            if (p == null || !p.matcher(plain).find()) continue;
            if (r.hide) return null;
            if (r.fx != null && !r.fx.isBlank()) body = TextFx.wrap(body, r.fx);
            if (!r.icon.isEmpty()) icon = r.icon;
            if (!r.color.isEmpty()) {
                int rgb = ChatCfg.rgb(r.color, -1);
                if (rgb >= 0) { body = recolorLead(body, rgb); nameRgb = rgb; }
            }
        }
        if (cfg.baseFx != null && !cfg.baseFx.isBlank()) body = TextFx.wrap(body, cfg.baseFx);
        if (icon.isEmpty() && !cfg.iconDefault.isEmpty()) icon = cfg.iconDefault;
        return new ChatMsg(body, icon, time, nameRgb);
    }

    /** Colour the leading "[...]" token of a message. */
    static Component recolorLead(Component msg, int rgb) {
        List<Object[]> segs = new ArrayList<>();   // {Style, String}
        msg.visit((style, s) -> { segs.add(new Object[]{style, s}); return java.util.Optional.empty(); }, Style.EMPTY);
        MutableComponent out = Component.empty();
        boolean started = false, done = false;
        for (Object[] sg : segs) {
            Style st = (Style) sg[0];
            String s = (String) sg[1];
            if (done || s.isEmpty()) { out.append(Component.literal(s).withStyle(st)); continue; }
            StringBuilder lead = new StringBuilder(), rest = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (!done && (started || c == '[')) {
                    started = true;
                    lead.append(c);
                    if (c == ']') { done = true; started = false; }
                } else {
                    rest.append(c);
                }
            }
            if (lead.length() > 0) out.append(Component.literal(lead.toString()).withStyle(st.withColor(TextColor.fromRgb(rgb))));
            if (rest.length() > 0) out.append(Component.literal(rest.toString()).withStyle(st));
        }
        return out;
    }

    /** helper so the store does not depend on the screen code for colour parsing */
    private static final class CfgColors {
        static int rgb(String s) { return ChatCfg.rgb(s, 0xB9F26B); }
    }
}
