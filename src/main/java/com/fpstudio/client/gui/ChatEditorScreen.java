package com.fpstudio.client.gui;

import com.fpstudio.chat.ChatCfg;
import com.fpstudio.client.screen.ScreenClient;
import com.fpstudio.world.ScreenNet;
import com.fpstudio.world.ScreenPresets;
import com.fpstudio.client.chat.ChatGifs;
import com.fpstudio.client.chat.ChatIcons;
import com.fpstudio.client.chat.ChatMsg;
import com.fpstudio.client.chat.ChatTyper;
import com.fpstudio.client.chat.UserAssets;
import com.fpstudio.client.chat.ChatRenderer;
import com.fpstudio.client.chat.ChatStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Editor of the custom chat. Left: settings (position with an anchor grid, look, text, animations, icons, rules).
 * Right: a mini game screen that draws the real chat renderer; drag the chat with the mouse, drag its edge to resize.
 */
public final class ChatEditorScreen extends Screen {
    private static final int BG = 0xF0121120, ACC = 0xFF8A7CF5, ACC2 = 0xFF5CE0C0, DIM = 0xFF8C89AE, TXT = 0xFFE9E7FA, LINE = 0xFF2D2B48;
    private static final int PANEL_W = 276;
    private static boolean snapAnchor = true;
    private static boolean liveMode = false;

    private final Screen parent;
    private ChatCfg cfg;
    private final boolean scr;                 // screen text preset mode
    private String presetName = "default";
    private String sampleText = "";
    private ChatMsg scrMsg;
    private String scrSig = "";
    private static boolean scrHold = true;
    private static String newName = "";
    private static ChatEditorScreen current;
    private int tab;
    private int cTop, cBot, left;
    private final List<AbstractWidget> body = new ArrayList<>();
    private final List<Integer> bodyY = new ArrayList<>();
    private final List<Object[]> headings = new ArrayList<>();
    private final List<Object[]> fontButtons = new ArrayList<>();
    private final List<Object[]> iconButtons = new ArrayList<>();
    private int cy, scroll, contentH;
    private String status = "";
    private long statusUntil;
    private List<String> fontList;

    // preview
    private final List<ChatMsg> openSample = new ArrayList<>();
    private final List<ChatMsg> live = new ArrayList<>();
    private int sampleVer = -1, liveIdx;
    private long liveNext;
    private float sx0, sy0, sk = 1f;      // mini screen origin and scale
    private int dragMode;                  // 0 none, 1 move, 2 resize
    private double dragRx, dragRy;
    private int dragX, dragY, dragW;

    // undo
    private final List<String> hist = new ArrayList<>();
    private int hpos = -1, lastVer = -1;
    private long snapAt;

    public ChatEditorScreen(Screen parent) { this(parent, null); }

    /** preset != null: edit a screen text preset of the world instead of the chat. */
    public ChatEditorScreen(Screen parent, String preset) {
        super(Component.literal(preset == null ? "Chat" : "Screen text"));
        this.parent = parent;
        this.scr = preset != null;
        if (scr) {
            presetName = ScreenPresets.clean(preset).isEmpty() ? "default" : ScreenPresets.clean(preset);
            String json = ScreenClient.presets().get(presetName);
            cfg = ChatCfg.fromJson(json != null ? json : ScreenPresets.builtin().get("default"));
            sampleText = I18n.get("fpstudio.gui.chat.scr_sample");
            ScreenClient.requestPresets();
        } else {
            cfg = ChatCfg.get();
        }
        current = this;
        pushHist();
    }

    /** the world sent a fresh preset list */
    public static void presetsChanged() {
        if (current != null && current.scr && Minecraft.getInstance().screen == current) current.init();
    }

    private String snap() { return scr ? cfg.toJson() : ChatCfg.snapshot(); }

    private void saveCurrent() {
        if (scr) ScreenClient.savePreset(presetName, cfg);
        else ChatCfg.save();
    }

    private static String t(String k) { return I18n.get("fpstudio.gui." + k); }

    // ------------------------------------------------------------------ widgets

    /** Slider with a value box next to it (the box accepts values beyond the slider range). */
    private final class Num extends AbstractWidget {
        private final String label;
        private final double min, max;
        private final boolean isInt;
        private final Consumer<Double> set;
        private double val;
        private EditBox box;
        private boolean sync;

        Num(int x, int y, int w, String label, double min, double max, double cur, boolean isInt, Consumer<Double> set) {
            super(x, y, w, 16, Component.empty());
            this.label = label; this.min = min; this.max = max; this.isInt = isInt; this.set = set; this.val = cur;
        }

        private String fmt(double v) {
            if (isInt) return String.valueOf(Math.round(v));
            String s = String.format(Locale.ROOT, "%.2f", v);
            return s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }

        void bind(EditBox b) {
            box = b;
            b.setValue(fmt(val));
            b.setResponder(s -> {
                if (sync) return;
                try {
                    apply(Double.parseDouble(s.trim().replace(',', '.')), true);
                } catch (NumberFormatException ignored) {
                    // wait for a valid number
                }
            });
        }

        private void apply(double v, boolean fromBox) {
            val = isInt ? Math.round(v) : Math.round(v * 100.0) / 100.0;
            set.accept(val);
            cfg.touch();
            if (!fromBox) { sync = true; box.setValue(fmt(val)); sync = false; }
        }

        private void fromMouse(double mx) {
            double f = (mx - getX() - 2) / (double) (width - 4);
            f = Math.max(0, Math.min(1, f));
            apply(min + (max - min) * f, false);
        }

        @Override public void onClick(double mx, double my) { fromMouse(mx); }

        @Override protected void onDrag(double mx, double my, double dx, double dy) { fromMouse(mx); }

        @Override
        public void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            boolean hov = isHoveredOrFocused();
            g.fill(getX(), getY(), getX() + width, getY() + height, hov ? 0xFF2A2842 : 0xFF1F1D33);
            double f = Math.max(0, Math.min(1, (val - min) / (max - min)));
            int fw = (int) ((width - 2) * f);
            g.fill(getX() + 1, getY() + height - 4, getX() + 1 + fw, getY() + height - 1, ACC);
            g.fill(getX() + 1 + fw - 1, getY() + 1, getX() + 1 + fw + 1, getY() + height - 1, hov ? 0xFFFFFFFF : ACC2);
            g.drawString(font, label, getX() + 4, getY() + 4, TXT, false);
        }

        @Override protected void updateWidgetNarration(NarrationElementOutput out) { defaultButtonNarrationText(out); }
    }

    /** 3x3 anchor picker. */
    private final class AnchorGrid extends AbstractWidget {
        AnchorGrid(int x, int y) { super(x, y, 66, 44, Component.empty()); }

        @Override
        public void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            for (int i = 0; i < 9; i++) {
                int cx = getX() + (i % 3) * 22, cyy = getY() + (i / 3) * 15;
                boolean sel = ChatCfg.ANCHORS.get(i).equals(cfg.anchor);
                boolean hov = mx >= cx && mx < cx + 20 && my >= cyy && my < cyy + 13;
                g.fill(cx, cyy, cx + 20, cyy + 13, sel ? ACC : hov ? 0xFF3A3860 : 0xFF23213A);
                int dx = cx + (i % 3 == 0 ? 3 : i % 3 == 1 ? 8 : 13), dy = cyy + (i / 3 == 0 ? 3 : i / 3 == 1 ? 5 : 8);
                g.fill(dx, dy, dx + 4, dy + 2, sel ? 0xFFFFFFFF : DIM);
            }
        }

        @Override
        public void onClick(double mx, double my) {
            int col = (int) ((mx - getX()) / 22), row = (int) ((my - getY()) / 15);
            if (col < 0 || col > 2 || row < 0 || row > 2) return;
            ChatRenderer.snapToAnchor(cfg, ChatCfg.ANCHORS.get(row * 3 + col));
            cfg.touch();
            init();
        }

        @Override protected void updateWidgetNarration(NarrationElementOutput out) { defaultButtonNarrationText(out); }
    }

    private <T extends AbstractWidget> T put(T w) {
        body.add(w);
        bodyY.add(w.getY());
        addWidget(w);
        return w;
    }

    private void num(String label, double min, double max, double cur, boolean isInt, Consumer<Double> set) {
        int w = PANEL_W - 28 - 48;
        Num n = new Num(left + 8, cy, w, label, min, max, cur, isInt, set);
        EditBox b = new EditBox(font, left + 8 + w + 4, cy, 44, 16, Component.empty());
        b.setMaxLength(8);
        n.bind(b);
        put(n);
        put(b);
        cy += 20;
    }

    private void toggle(String label, Supplier<Boolean> get, Consumer<Boolean> set) {
        put(Button.builder(Component.literal(label + ": " + (get.get() ? "§a" + t("on") : "§7" + t("off"))), bb -> {
            set.accept(!get.get());
            cfg.touch();
            bb.setMessage(Component.literal(label + ": " + (get.get() ? "§a" + t("on") : "§7" + t("off"))));
        }).bounds(left + 8, cy, PANEL_W - 28, 16).build());
        cy += 20;
    }

    private void cycle(String label, List<String> opts, Supplier<String> get, Consumer<String> set, java.util.function.Function<String, String> show) {
        put(Button.builder(Component.literal(label + ": " + show.apply(get.get())), bb -> {
            int i = opts.indexOf(get.get());
            i = i < 0 ? 0 : (i + (hasShiftDown() ? opts.size() - 1 : 1)) % opts.size();
            set.accept(opts.get(i));
            cfg.touch();
            bb.setMessage(Component.literal(label + ": " + show.apply(get.get())));
        }).bounds(left + 8, cy, PANEL_W - 28, 16).build());
        cy += 20;
    }

    private Button btn(int x, int w, String label, Runnable r) {
        return put(Button.builder(Component.literal(label), b -> r.run()).bounds(x, cy, w, 16).build());
    }

    private EditBox box(int x, int w, String value, int max, Consumer<String> set) {
        EditBox e = new EditBox(font, x, cy, w, 16, Component.empty());
        e.setMaxLength(max);
        e.setValue(value);
        e.setResponder(s -> { set.accept(s); cfg.touch(); });
        return put(e);
    }

    private void heading(String s) { headings.add(new Object[]{cy, s, 0}); cy += 14; }

    private void label(String s) { headings.add(new Object[]{cy, s, 2}); cy += 11; }

    private void note(String s) {
        for (var seg : font.split(Component.literal(s), PANEL_W - 28)) { headings.add(new Object[]{cy, seg, 1}); cy += 10; }
        cy += 4;
    }

    // ------------------------------------------------------------------ build

    @Override
    protected void init() {
        left = 8;
        cTop = 8 + 76;
        cBot = height - 8 - 30;
        clearWidgets();
        body.clear(); bodyY.clear(); headings.clear(); fontButtons.clear(); iconButtons.clear();
        cy = cTop;
        String[] names = {t("chat.tab_pos"), t("chat.tab_look"), t("chat.tab_font"), t("chat.tab_anim"), t("chat.tab_type"), t("chat.tab_fx"), t("chat.tab_icons"), scr ? t("chat.tab_screen") : t("chat.tab_rules")};
        int tw = (PANEL_W - 12 - 6) / 4;
        for (int i = 0; i < 8; i++) {
            final int ti = i;
            addRenderableWidget(Button.builder(Component.literal((tab == i ? "§b" : "") + names[i]), b -> { tab = ti; scroll = 0; init(); })
                    .bounds(left + 6 + (i % 4) * (tw + 2), 8 + 36 + (i / 4) * 19, tw, 17).build());
        }
        if (scr) {
            addRenderableWidget(Button.builder(Component.literal(t("chat.preset") + ": §b" + presetName + " §7\u25b8"), b -> {
                List<String> pn = new ArrayList<>(ScreenClient.presets().keySet());
                if (!pn.contains(presetName)) pn.add(presetName);
                switchPreset(pn.get((pn.indexOf(presetName) + 1) % pn.size()));
            }).bounds(left + 6, 8 + 16, PANEL_W - 12, 17).build());
        } else {
            addRenderableWidget(Button.builder(Component.literal(t("chat.title_on") + ": " + (cfg.enabled ? "§a" + t("on") : "§c" + t("off"))), b -> {
                cfg.enabled = !cfg.enabled;
                cfg.touch();
                init();
            }).bounds(left + 6, 8 + 16, PANEL_W - 12, 17).build());
        }
        switch (tab) {
            case 0 -> buildPos();
            case 1 -> buildLook();
            case 2 -> buildFont();
            case 3 -> buildAnim();
            case 4 -> buildType();
            case 5 -> buildFx();
            case 6 -> buildIcons();
            default -> { if (scr) buildScreen(); else buildRules(); }
        }
        contentH = cy - cTop;
        int fw = (PANEL_W - 16) / 4;
        int fy = height - 8 - 24;
        addRenderableWidget(Button.builder(Component.literal("↶ " + t("chat.undo")), b -> undo(-1)).bounds(left + 6, fy, fw - 2, 18).build());
        addRenderableWidget(Button.builder(Component.literal(t("chat.reset")), b -> { if (scr) { cfg = ChatCfg.fromJson(ScreenPresets.builtin().getOrDefault(presetName, ScreenPresets.builtin().get("default"))); scrMsg = null; } else { ChatCfg.reset(); cfg = ChatCfg.get(); } pushHist(); init(); toast(t("chat.was_reset")); })
                .bounds(left + 6 + fw, fy, fw - 2, 18).build());
        addRenderableWidget(Button.builder(Component.literal(t("chat.icon_folder")), b -> ChatIcons.openFolder()).bounds(left + 6 + 2 * fw, fy, fw - 2, 18).build());
        addRenderableWidget(Button.builder(Component.literal("§a" + t("chat.done")), b -> onClose()).bounds(left + 6 + 3 * fw, fy, fw - 2, 18).build());
    }

    private void applyPreset(int n) {
        ChatCfg c = cfg;
        switch (n) {
            case 0 -> { c.anchor = "bottom_center"; c.x = 0; c.y = 60; c.width = 380; c.scale = 1f; c.textScale = 1f; c.style = "box"; c.bgAlpha = 55; c.rounded = true;
                c.icons = true; c.shadow = true; c.animIn = "slide_up"; c.animOut = "fade"; c.timestamps = false; c.showSeconds = 10f; }
            case 1 -> { c.anchor = "bottom_left"; c.x = 4; c.y = 44; c.width = 340; c.scale = 1f; c.textScale = 1f; c.style = "box"; c.bgAlpha = 40; c.rounded = false;
                c.icons = false; c.shadow = true; c.animIn = "fade"; c.animOut = "fade"; }
            case 2 -> { c.anchor = "bottom_left"; c.x = 6; c.y = 44; c.width = 420; c.textScale = 0.9f; c.style = "none"; c.icons = false; c.shadow = true; c.animIn = "fade"; c.animOut = "fade"; }
            case 3 -> { c.anchor = "bottom_center"; c.x = 0; c.y = 36; c.width = 520; c.textScale = 1.15f; c.style = "none"; c.icons = false; c.shadow = true;
                c.animIn = "fade"; c.animOut = "fade"; c.showSeconds = 6f; c.fadeSeconds = 1.6f; }
            default -> { c.anchor = "top_right"; c.x = 6; c.y = 8; c.width = 320; c.scale = 1f; c.textScale = 1f; c.style = "bar"; c.bgAlpha = 50; c.icons = true;
                c.animIn = "slide_side"; c.animOut = "slide"; }
        }
        cfg.touch();
        init();
    }

    private void buildPos() {
        heading(t("chat.h_presets"));
        int hw = (PANEL_W - 28 - 4) / 2;
        String[] pn = {t("chat.p_frame"), t("chat.p_classic"), t("chat.p_min"), t("chat.p_cine"), t("chat.p_topright")};
        for (int i = 0; i < pn.length; i++) {
            final int pi = i;
            btn(left + 8 + (i % 2) * (hw + 4), hw, pn[i], () -> applyPreset(pi));
            if (i % 2 == 1 || i == pn.length - 1) cy += 19;
        }
        cy += 4;
        heading(t("chat.h_anchor"));
        put(new AnchorGrid(left + 8, cy));
        headings.add(new Object[]{cy + 2, t("chat.anchor_" + cfg.anchor), 3});
        int hl = 0;
        for (var seg : font.split(Component.literal(t("chat.anchor_hint")), PANEL_W - 28 - 74)) {
            if (hl++ > 2) break;
            headings.add(new Object[]{cy + 14 + (hl - 1) * 10, seg, 4});
        }
        cy += 50;
        toggle(t("chat.snap"), () -> snapAnchor, v -> snapAnchor = v);
        heading(t("chat.h_offset"));
        num(t("chat.x"), -400, 800, cfg.x, true, v -> cfg.x = v.intValue());
        num(t("chat.y"), -300, 800, cfg.y, true, v -> cfg.y = v.intValue());
        heading(t("chat.h_size"));
        num(t("chat.width"), 120, 900, cfg.width, true, v -> cfg.width = v.intValue());
        num(t("chat.scale"), 0.5, 3, cfg.scale, false, v -> cfg.scale = v.floatValue());
        num(t("chat.text_scale"), 0.5, 3, cfg.textScale, false, v -> cfg.textScale = v.floatValue());
        num(t("chat.idle_msgs"), 1, 30, cfg.idleMessages, true, v -> cfg.idleMessages = v.intValue());
        num(t("chat.open_msgs"), 1, 60, cfg.openMessages, true, v -> cfg.openMessages = v.intValue());
        note(t("chat.drag_hint"));
    }

    private void buildLook() {
        heading(t("chat.h_box"));
        cycle(t("chat.style"), List.of("box", "bar", "none"), () -> cfg.style, v -> cfg.style = v, s -> t("chat.style_" + s));
        toggle(t("chat.rounded"), () -> cfg.rounded, v -> cfg.rounded = v);
        num(t("chat.bg_alpha"), 0, 100, cfg.bgAlpha, true, v -> cfg.bgAlpha = v.intValue());
        label(t("chat.bg_color"));
        box(left + 8, PANEL_W - 28, cfg.bgColor, 7, v -> cfg.bgColor = v);
        cy += 22;
        heading(t("chat.h_spacing"));
        num(t("chat.pad_x"), 0, 16, cfg.padX, true, v -> cfg.padX = v.intValue());
        num(t("chat.pad_y"), 0, 16, cfg.padY, true, v -> cfg.padY = v.intValue());
        num(t("chat.gap"), 0, 12, cfg.gap, true, v -> cfg.gap = v.intValue());
        num(t("chat.line_spacing"), 0, 8, cfg.lineSpacing, true, v -> cfg.lineSpacing = v.intValue());
        heading(t("chat.h_msgs"));
        toggle(t("chat.timestamps"), () -> cfg.timestamps, v -> cfg.timestamps = v);
        toggle(t("chat.restyle"), () -> cfg.restylePlayers, v -> cfg.restylePlayers = v);
        toggle(t("chat.brackets"), () -> cfg.bracketNames, v -> cfg.bracketNames = v);
    }

    private List<String> fonts() {
        if (fontList != null) return fontList;
        List<String> out = new ArrayList<>(List.of("minecraft:default", "minecraft:uniform", "minecraft:alt", "minecraft:illageralt"));
        try {
            for (ResourceLocation rl : Minecraft.getInstance().getResourceManager().listResources("font", r -> r.getPath().endsWith(".json")).keySet()) {
                String p = rl.getPath();
                p = p.substring(5, p.length() - 5);
                if (p.startsWith("include/")) continue;
                String id = rl.getNamespace() + ":" + p;
                if (!out.contains(id)) out.add(id);
            }
        } catch (RuntimeException ignored) {
            // keep the built-in list
        }
        for (String id : UserAssets.fontIds()) if (!out.contains(id)) out.add(id);
        fontList = out;
        return out;
    }

    private void buildFont() {
        heading(t("chat.h_font"));
        note(t("chat.font_note"));
        int hw = (PANEL_W - 28 - 2) / 2;
        btn(left + 8, hw, t("chat.open_fonts"), () -> UserAssets.openFolder(UserAssets.fontsDir()));
        btn(left + 8 + hw + 2, hw, t("chat.open_sounds"), () -> UserAssets.openFolder(UserAssets.soundsDir()));
        cy += 20;
        btn(left + 8, PANEL_W - 28, "\u27f3 " + t("chat.apply_assets"), () -> { toast(UserAssets.applyNow(cfg)); fontList = null; });
        cy += 20;
        num(t("chat.ttf_size"), 4, 48, cfg.ttfSize, false, v -> cfg.ttfSize = v.floatValue());
        num(t("chat.ttf_shift"), -10, 10, cfg.ttfShiftY, false, v -> cfg.ttfShiftY = v.floatValue());
        note(t("chat.ttf_note"));
        for (String id : fonts()) {
            final String fid = id;
            Button b = Button.builder(Component.empty(), bb -> { cfg.font = fid; cfg.touch(); }).bounds(left + 8, cy, PANEL_W - 28, 20).build();
            fontButtons.add(new Object[]{b, id});
            put(b);
            cy += 22;
        }
        heading(t("chat.h_custom_font"));
        box(left + 8, PANEL_W - 28, cfg.font, 80, v -> cfg.font = v.trim());
        cy += 22;
        heading(t("chat.h_text"));
        num(t("chat.text_scale"), 0.5, 3, cfg.textScale, false, v -> cfg.textScale = v.floatValue());
        num(t("chat.line_spacing"), 0, 8, cfg.lineSpacing, true, v -> cfg.lineSpacing = v.intValue());
        label(t("chat.text_color"));
        box(left + 8, PANEL_W - 28, cfg.textColor, 7, v -> cfg.textColor = v);
        cy += 22;
        label(t("chat.name_color"));
        box(left + 8, PANEL_W - 28, cfg.nameColor, 7, v -> cfg.nameColor = v);
        cy += 22;
        toggle(t("chat.shadow"), () -> cfg.shadow, v -> cfg.shadow = v);
    }

    private void switchPreset(String name) {
        saveCurrent();
        String json = ScreenClient.presets().get(name);
        presetName = name;
        cfg = ChatCfg.fromJson(json != null ? json : ScreenPresets.builtin().get("default"));
        hist.clear();
        hpos = -1;
        pushHist();
        scrMsg = null;
        tab = 7;
        scroll = 0;
        init();
    }

    private void buildScreen() {
        heading(t("chat.h_scr_presets"));
        for (String n : ScreenClient.presets().keySet()) {
            final String nn = n;
            put(Button.builder(Component.literal((n.equals(presetName) ? "§b\u25b6 " : "   ") + n), b -> switchPreset(nn)).bounds(left + 8, cy, PANEL_W - 28, 16).build());
            cy += 18;
        }
        label(t("chat.scr_name"));
        box(left + 8, PANEL_W - 28, newName, 32, v -> newName = ScreenPresets.clean(v));
        cy += 20;
        int tw = (PANEL_W - 28 - 4) / 3;
        btn(left + 8, tw, t("chat.scr_new"), () -> {
            if (newName.isEmpty()) return;
            ScreenClient.savePreset(newName, ChatCfg.fromJson(ScreenPresets.builtin().get("default")));
            switchPreset(newName);
        });
        btn(left + 8 + tw + 2, tw, t("chat.scr_copy"), () -> {
            if (newName.isEmpty()) return;
            ScreenClient.savePreset(newName, cfg);
            switchPreset(newName);
        });
        btn(left + 8 + (tw + 2) * 2, tw, "§c" + t("chat.scr_delete"), () -> {
            ScreenClient.deletePreset(presetName);
            switchPreset("default");
        });
        cy += 22;
        heading(t("chat.h_scr_layout"));
        toggle(t("chat.vcenter"), () -> cfg.vcenter, v -> cfg.vcenter = v);
        cycle(t("chat.align"), List.of("0", "1", "2"), () -> String.valueOf(cfg.align), v -> cfg.align = Integer.parseInt(v), s -> t("chat.align_" + s));
        label(t("chat.base_fx"));
        box(left + 8, PANEL_W - 28, cfg.baseFx, 80, v -> cfg.baseFx = v.trim());
        cy += 22;
        note(t("chat.base_fx_note"));
        heading(t("chat.h_scr_try"));
        label(t("chat.scr_text"));
        box(left + 8, PANEL_W - 28, sampleText, 300, v -> { sampleText = v; });
        cy += 22;
        toggle(t("chat.scr_hold"), () -> scrHold, v -> scrHold = v);
        btn(left + 8, PANEL_W - 28, "\u25b6 " + t("chat.replay"), this::resetLive);
        cy += 20;
        btn(left + 8, PANEL_W - 28, t("chat.scr_save"), () -> { saveCurrent(); toast(t("chat.scr_saved")); });
        cy += 20;
        btn(left + 8, PANEL_W - 28, "\u25b6 " + t("chat.scr_send_all"), () -> {
            saveCurrent();
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.player.connection.sendCommand("fpscreen show @a " + presetName + " -1 " + sampleText);
        });
        cy += 20;
        note(t("chat.scr_help"));
    }

    private void copyTag(String tag) {
        Minecraft.getInstance().keyboardHandler.setClipboard(tag);
        toast(t("chat.copied") + " " + tag);
    }

    private static final String[] FX_NAMES = {"shake", "wave", "glitch", "flicker", "blood", "glow", "ghost", "corrupt", "rainbow", "robot", "color"};

    private void buildFx() {
        heading(t("chat.h_fx"));
        toggle(t("chat.fx_on"), () -> cfg.fxEnabled, v -> cfg.fxEnabled = v);
        toggle(t("chat.fx_tags"), () -> cfg.fxTags, v -> cfg.fxTags = v);
        num(t("chat.fx_speed"), 0.2, 3, cfg.fxSpeed, false, v -> cfg.fxSpeed = v.floatValue());
        num(t("chat.fx_strength"), 0, 3, cfg.fxStrength, false, v -> cfg.fxStrength = v.floatValue());
        note(t("chat.fx_note"));
        heading(t("chat.h_fx_list"));
        for (String id : FX_NAMES) {
            final String fid = id;
            String tag = fid.equals("color") ? "<color=#FF8800>" + t("chat.fx_text") + "</color>" : "<" + fid + ">" + t("chat.fx_text") + "</" + fid + ">";
            put(Button.builder(Component.literal("<" + fid + ">  " + t("chat.fx_" + fid)), b -> copyTag(tag)).bounds(left + 8, cy, PANEL_W - 28, 16).build());
            cy += 18;
        }
        note(t("chat.fx_args"));
        heading(t("chat.h_gifs"));
        toggle(t("chat.gifs_on"), () -> cfg.gifsOn, v -> cfg.gifsOn = v);
        num(t("chat.gif_size"), 4, 64, cfg.gifSize, true, v -> cfg.gifSize = v.intValue());
        int hw = (PANEL_W - 28 - 2) / 2;
        btn(left + 8, hw, t("chat.open_gifs"), () -> UserAssets.openFolder(ChatGifs.dir()));
        btn(left + 8 + hw + 2, hw, t("chat.gif_reload"), () -> { ChatGifs.reload(); toast(t("chat.reloaded")); init(); });
        cy += 20;
        note(t("chat.gif_note"));
        List<String> gifs = ChatGifs.list();
        if (gifs.isEmpty()) note(t("chat.no_gifs"));
        for (String name : gifs) {
            final String gname = name;
            put(Button.builder(Component.literal("<gif=" + gname + ">"), b -> copyTag("<gif=" + gname + ">")).bounds(left + 8, cy, PANEL_W - 28 - 62, 16).build());
            put(Button.builder(Component.literal(t("chat.gif_as_icon")), b -> { cfg.iconDefault = "gif:" + gname; cfg.touch(); toast("gif:" + gname); }).bounds(left + 8 + PANEL_W - 28 - 60, cy, 60, 16).build());
            cy += 18;
        }
    }

    private void buildType() {
        heading(t("chat.h_type"));
        toggle(t("chat.typewriter"), () -> cfg.typewriter, v -> cfg.typewriter = v);
        note(t("chat.type_note"));
        num(t("chat.type_cps"), 2, 200, cfg.typeCps, false, v -> cfg.typeCps = v.floatValue());
        num(t("chat.type_delay"), 0, 3, cfg.typeDelay, false, v -> cfg.typeDelay = v.floatValue());
        num(t("chat.type_punct"), 0, 2, cfg.typePausePunct, false, v -> cfg.typePausePunct = v.floatValue());
        num(t("chat.type_comma"), 0, 2, cfg.typePauseComma, false, v -> cfg.typePauseComma = v.floatValue());
        toggle(t("chat.type_name"), () -> cfg.typeNameInstant, v -> cfg.typeNameInstant = v);
        btn(left + 8, PANEL_W - 28, "\u25b6 " + t("chat.replay"), () -> { liveMode = true; resetLive(); });
        cy += 22;
        heading(t("chat.h_voice"));
        voiceControls(cfg.voice, false, -1);
        heading(t("chat.h_voices"));
        note(t("chat.voices_note"));
        btn(left + 8, PANEL_W - 28, "+ " + t("chat.voice_add"), () -> {
            ChatCfg.Voice v = new ChatCfg.Voice();
            v.pattern = "^\\[Name\\]";
            cfg.voices.add(v);
            cfg.touch();
            init();
        });
        cy += 22;
        for (int i = 0; i < cfg.voices.size(); i++) {
            headings.add(new Object[]{cy, "#" + (i + 1), 0});
            cy += 14;
            voiceControls(cfg.voices.get(i), true, i);
            cy += 4;
        }
    }

    private void voiceControls(ChatCfg.Voice v, boolean extra, int idx) {
        int fullW = PANEL_W - 28;
        if (extra) {
            label(t("chat.voice_pattern"));
            box(left + 8, fullW - 36, v.pattern, 80, x -> v.pattern = x);
            put(Button.builder(Component.literal(v.enabled ? "\u00a7a\u2714" : "\u00a77\u2718"), b -> { v.enabled = !v.enabled; cfg.touch(); b.setMessage(Component.literal(v.enabled ? "\u00a7a\u2714" : "\u00a77\u2718")); })
                    .bounds(left + 8 + fullW - 34, cy, 16, 16).build());
            put(Button.builder(Component.literal("\u00a7cx"), b -> { cfg.voices.remove(idx); cfg.touch(); init(); }).bounds(left + 8 + fullW - 16, cy, 16, 16).build());
            cy += 22;
        } else {
            toggle(t("chat.voice_on"), () -> v.enabled, x -> v.enabled = x);
        }
        label(t("chat.voice_sound"));
        box(left + 8, fullW - 40, v.sound, 80, x -> v.sound = x.trim());
        put(Button.builder(Component.literal("\u25b6"), b -> ChatTyper.play(v.sound, v.volume, v.pitch)).bounds(left + 8 + fullW - 38, cy, 18, 16).build());
        put(Button.builder(Component.literal("\u2630"), b -> {
            List<String> sg = ChatTyper.suggestions();
            int k = sg.indexOf(v.sound);
            v.sound = sg.get((k + 1) % sg.size());
            cfg.touch();
            ChatTyper.play(v.sound, v.volume, v.pitch);
            init();
        }).bounds(left + 8 + fullW - 18, cy, 18, 16).build());
        cy += 22;
        num(t("chat.voice_volume"), 0, 2, v.volume, false, x -> v.volume = x.floatValue());
        num(t("chat.voice_pitch"), 0.3, 3, v.pitch, false, x -> v.pitch = x.floatValue());
        num(t("chat.voice_var"), 0, 0.6, v.pitchVar, false, x -> v.pitchVar = x.floatValue());
        num(t("chat.voice_every"), 1, 8, v.everyN, true, x -> v.everyN = x.intValue());
        label(t("chat.voice_mute"));
        box(left + 8, fullW, v.mute, 60, x -> v.mute = x);
        cy += 22;
        label(t("chat.voice_map"));
        box(left + 8, fullW, v.map, 300, x -> v.map = x);
        cy += 22;
        if (!extra) note(t("chat.voice_map_note"));
    }

    private void buildAnim() {
        heading(t("chat.h_anim_in"));
        cycle(t("chat.anim_in"), List.of("none", "fade", "slide_up", "slide_side", "pop"), () -> cfg.animIn, v -> cfg.animIn = v, s -> t("chat.ai_" + s));
        num(t("chat.in_sec"), 0, 1.5, cfg.inSeconds, false, v -> cfg.inSeconds = v.floatValue());
        num(t("chat.slide_px"), 0, 80, cfg.slidePx, true, v -> cfg.slidePx = v.intValue());
        toggle(t("chat.glide"), () -> cfg.glide, v -> cfg.glide = v);
        heading(t("chat.h_anim_out"));
        cycle(t("chat.anim_out"), List.of("none", "fade", "slide", "shrink"), () -> cfg.animOut, v -> cfg.animOut = v, s -> t("chat.ao_" + s));
        num(t("chat.show_sec"), 2, 60, cfg.showSeconds, false, v -> cfg.showSeconds = v.floatValue());
        num(t("chat.fade_sec"), 0, 5, cfg.fadeSeconds, false, v -> cfg.fadeSeconds = v.floatValue());
        heading(t("chat.h_try"));
        btn(left + 8, PANEL_W - 28, "▶ " + t("chat.replay"), () -> { liveMode = true; resetLive(); });
        cy += 20;
        note(t("chat.anim_note"));
    }

    private void buildIcons() {
        heading(t("chat.h_icons"));
        toggle(t("chat.icons"), () -> cfg.icons, v -> cfg.icons = v);
        toggle(t("chat.heads"), () -> cfg.playerHeads, v -> cfg.playerHeads = v);
        num(t("chat.icon_size"), 8, 48, cfg.iconSize, true, v -> cfg.iconSize = v.intValue());
        num(t("chat.icon_gap"), 0, 12, cfg.iconGap, true, v -> cfg.iconGap = v.intValue());
        label(t("chat.icon_default"));
        box(left + 8, PANEL_W - 28, cfg.iconDefault, 60, v -> cfg.iconDefault = v.trim());
        cy += 22;
        note(t("chat.icon_note"));
        heading(t("chat.h_library"));
        btn(left + 8, PANEL_W - 28, t("chat.icon_reload"), () -> { ChatIcons.reload(); toast(t("chat.reloaded")); init(); });
        cy += 20;
        List<String> files = ChatIcons.list();
        if (files.isEmpty()) note(t("chat.no_icons"));
        for (String f : files) {
            final String name = f;
            Button b = Button.builder(Component.literal("   " + name), bb -> { cfg.iconDefault = "file:" + name; cfg.touch(); init(); }).bounds(left + 8, cy, PANEL_W - 28, 20).build();
            iconButtons.add(new Object[]{b, name});
            put(b);
            cy += 22;
        }
    }

    private void buildRules() {
        heading(t("chat.h_rules"));
        note(t("chat.rules_note"));
        btn(left + 8, PANEL_W - 28, "+ " + t("chat.rule_add"), () -> { cfg.rules.add(new ChatCfg.Rule()); cfg.touch(); init(); });
        cy += 22;
        int fullW = PANEL_W - 28;
        for (int i = 0; i < cfg.rules.size(); i++) {
            final ChatCfg.Rule r = cfg.rules.get(i);
            final int idx = i;
            headings.add(new Object[]{cy + 4, "#" + (i + 1), 0});
            put(Button.builder(Component.literal(r.enabled ? "§a✔" : "§7✘"), b -> { r.enabled = !r.enabled; cfg.touch(); b.setMessage(Component.literal(r.enabled ? "§a✔" : "§7✘")); })
                    .bounds(left + 28, cy, 18, 16).build());
            box(left + 48, fullW - 40 - 20, r.pattern, 80, v -> r.pattern = v);
            put(Button.builder(Component.literal("§cx"), b -> { cfg.rules.remove(idx); cfg.touch(); init(); }).bounds(left + 8 + fullW - 16, cy, 16, 16).build());
            cy += 19;
            int half = (fullW - 20) / 2;
            box(left + 8 + 20, half - 4, r.icon, 60, v -> r.icon = v.trim());
            box(left + 8 + 20 + half, half - 20 - 24, r.color, 7, v -> r.color = v.trim());
            put(Button.builder(Component.literal(r.hide ? "§c" + t("chat.hide") : t("chat.show")), b -> { r.hide = !r.hide; cfg.touch(); b.setMessage(Component.literal(r.hide ? "§c" + t("chat.hide") : t("chat.show"))); })
                    .bounds(left + 8 + fullW - 44, cy, 44, 16).build());
            cy += 19;
            box(left + 8 + 20, fullW - 20, r.fx, 60, v -> r.fx = v.trim());
            cy += 26;
        }
        if (!cfg.rules.isEmpty()) note(t("chat.rule_cols"));
    }

    private void toast(String s) { status = s; statusUntil = System.currentTimeMillis() + 2200; }

    // ------------------------------------------------------------------ undo

    private void pushHist() {
        String snap = snap();
        if (hpos >= 0 && hist.get(hpos).equals(snap)) return;
        while (hist.size() > hpos + 1) hist.remove(hist.size() - 1);
        hist.add(snap);
        if (hist.size() > 80) hist.remove(0);
        hpos = hist.size() - 1;
    }

    private void undo(int dir) {
        pushHist();
        int np = hpos + dir;
        if (np < 0 || np >= hist.size()) return;
        hpos = np;
        if (scr) cfg = ChatCfg.fromJson(hist.get(hpos));
        else { ChatCfg.restore(hist.get(hpos)); cfg = ChatCfg.get(); }
        scrMsg = null;
        lastVer = cfg.ver;
        init();
    }

    // ------------------------------------------------------------------ preview data

    private List<Component> samples() {
        Minecraft mc = Minecraft.getInstance();
        String me = mc.player != null ? mc.player.getGameProfile().getName() : "Steve";
        return List.of(
                Component.translatable("chat.type.text", Component.literal(me), Component.literal(t("chat.s_player"))),
                marked("item:minecraft:player_head", "???", 0xD98BE8, t("chat.s_npc1")),
                marked("player:" + me, t("chat.s_name2"), 0x6FE08A, t("chat.s_npc2")),
                marked("item:minecraft:wither_skeleton_skull", t("chat.s_name3"), 0xE04848, t("chat.s_fx") + gifSample()),
                Component.literal(t("chat.s_system")).withStyle(net.minecraft.ChatFormatting.YELLOW));
    }

    private static String gifSample() {
        List<String> g = ChatGifs.list();
        return g.isEmpty() ? "" : " <gif=" + g.get(0) + ">";
    }

    private static Component marked(String icon, String name, int rgb, String text) {
        MutableComponent c = Component.empty();
        c.append(Component.empty().withStyle(Style.EMPTY.withInsertion(ChatStore.MARKER + icon)));
        c.append(Component.literal("[" + name + "]").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb))));
        c.append(Component.literal(": " + text));
        return c;
    }

    private void rebuildOpenSample() {
        openSample.clear();
        long now = System.currentTimeMillis();
        int i = 0;
        for (Component c : samples()) {
            ChatMsg m = ChatStore.process(c, cfg, now - 60000 + i++ * 1000L);
            if (m != null) openSample.add(m);
        }
        sampleVer = cfg.ver;
    }

    private void resetLive() { live.clear(); liveIdx = 0; liveNext = 0; scrMsg = null; }

    private void tickLive() {
        long now = System.currentTimeMillis();
        List<Component> s = samples();
        if (liveIdx < s.size() && now >= liveNext) {
            ChatMsg m = ChatStore.process(s.get(liveIdx), cfg, now);
            if (m != null) live.add(m);
            liveIdx++;
            liveNext = now + 650;
        } else if (liveIdx >= s.size() && now - liveNext > (long) ((cfg.showSeconds + cfg.fadeSeconds) * 1000f) + 600L) {
            resetLive();
        }
    }

    // ------------------------------------------------------------------ render

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        if (cfg.ver != lastVer) { lastVer = cfg.ver; snapAt = System.currentTimeMillis() + 350; }
        if (snapAt != 0 && System.currentTimeMillis() > snapAt && dragMode == 0) { snapAt = 0; pushHist(); }
        renderPreview(g, mx, my);
        renderPanel(g, mx, my, pt);
        if (!status.isEmpty() && System.currentTimeMillis() < statusUntil) g.drawCenteredString(font, status, left + PANEL_W / 2, height - 42, 0xFFFFE9A8);
    }

    private void renderPreview(GuiGraphics g, int mx, int my) {
        Minecraft mc = Minecraft.getInstance();
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        int ax0 = left + PANEL_W + 12, ax1 = width - 8, ay0 = 8 + 22, ay1 = height - 8;
        g.fill(ax0, 8, ax1, ay0 - 2, 0xCC0E0D16);
        // header: preview mode
        String m1 = scr ? "" : (!liveMode ? "§b" : "") + t("chat.pv_open"), m2 = scr ? "" : (liveMode ? "§b" : "") + t("chat.pv_live");
        int hx = ax0 + 6;
        g.drawString(font, t("chat.preview"), hx, 8 + 5, TXT, false);
        int tx = ax1 - 8 - font.width(m2) - font.width(m1) - 14;
        g.drawString(font, m1, tx, 8 + 5, TXT, false);
        g.drawString(font, m2, tx + font.width(m1) + 14, 8 + 5, TXT, false);
        previewTabX1 = tx; previewTabX2 = tx + font.width(m1) + 14; previewTabEnd = ax1 - 8;
        sk = Math.min((ax1 - ax0) / (float) sw, (ay1 - ay0) / (float) sh);
        float pw = sw * sk, ph = sh * sk;
        sx0 = ax0 + (ax1 - ax0 - pw) / 2f;
        sy0 = ay0 + (ay1 - ay0 - ph) / 2f;
        int x0 = Math.round(sx0), y0 = Math.round(sy0), x1 = Math.round(sx0 + pw), y1 = Math.round(sy0 + ph);
        g.fillGradient(x0, y0, x1, y1, 0xFF2F4A38, 0xFF16241B);
        g.enableScissor(x0, y0, x1, y1);
        g.pose().pushPose();
        g.pose().translate(sx0, sy0, 0f);
        g.pose().scale(sk, sk, 1f);
        for (int gx = 0; gx < sw; gx += 40) g.fill(gx, 0, gx + 1, sh, 0x10FFFFFF);
        for (int gy = 0; gy < sh; gy += 40) g.fill(0, gy, sw, gy + 1, 0x10FFFFFF);
        // mock hud: hotbar and crosshair
        g.fill(sw / 2 - 91, sh - 22, sw / 2 + 91, sh, 0x90000000);
        for (int i = 0; i < 9; i++) g.fill(sw / 2 - 90 + i * 20, sh - 21, sw / 2 - 90 + i * 20 + 18, sh - 3, 0x30FFFFFF);
        g.fill(sw / 2 - 5, sh / 2, sw / 2 + 6, sh / 2 + 1, 0xA0FFFFFF);
        g.fill(sw / 2, sh / 2 - 5, sw / 2 + 1, sh / 2 + 6, 0xA0FFFFFF);
        if (scr) {
            drawScr(g, sw, sh);
        } else {
            if (sampleVer != cfg.ver) rebuildOpenSample();
            if (liveMode) tickLive();
            List<ChatMsg> list = liveMode ? live : openSample;
            ChatRenderer.draw(g, list, cfg, sw, sh, liveMode ? 0f : 1f, 0, liveMode && dragMode == 0, false);
        }
        // selection frame + guides
        int bx0 = Math.round(ChatRenderer.boxX0), bx1 = Math.round(ChatRenderer.boxX1);
        int by0 = Math.round(ChatRenderer.boxY0), by1 = Math.round(ChatRenderer.boxY1);
        boolean hovBox = hit(mx, my) > 0;
        int fc = dragMode != 0 ? 0xFFFFFFFF : hovBox ? ACC2 : 0x667C6CF0;
        g.fill(bx0 - 1, by0 - 1, bx1 + 1, by0, fc);
        g.fill(bx0 - 1, by1, bx1 + 1, by1 + 1, fc);
        g.fill(bx0 - 1, by0, bx0, by1, fc);
        g.fill(bx1, by0, bx1 + 1, by1, fc);
        boolean rightA = cfg.anchor.endsWith("right");
        int hxh = rightA ? bx0 : bx1;
        g.fill(hxh - 2, (by0 + by1) / 2 - 8, hxh + 2, (by0 + by1) / 2 + 8, hovBox || dragMode == 2 ? 0xFFFFFFFF : ACC);
        if (dragMode != 0) {
            if (Math.abs((bx0 + bx1) / 2 - sw / 2) <= 2) g.fill(sw / 2, 0, sw / 2 + 1, sh, 0xAAFF6AC1);
            if (Math.abs((by0 + by1) / 2 - sh / 2) <= 2) g.fill(0, sh / 2, sw, sh / 2 + 1, 0xAAFF6AC1);
        }
        g.pose().popPose();
        g.disableScissor();
        g.fill(x0 - 1, y0 - 1, x1 + 1, y0, LINE);
        g.fill(x0 - 1, y1, x1 + 1, y1 + 1, LINE);
        g.fill(x0 - 1, y0, x0, y1, LINE);
        g.fill(x1, y0, x1 + 1, y1, LINE);
        String info = t("chat.anchor_" + cfg.anchor) + "  x " + cfg.x + "  y " + cfg.y + "  " + cfg.width + " px  ×" + trimF(cfg.scale);
        g.drawString(font, info, x0 + 4, y1 + 3 > height - 12 ? y1 - 11 : y1 + 3, DIM, false);
    }

    private int previewTabX1, previewTabX2, previewTabEnd;

    private void drawScr(GuiGraphics g, int sw, int sh) {
        long now = System.currentTimeMillis();
        String sig = cfg.baseFx + "|" + cfg.iconDefault + "|" + cfg.fxTags + cfg.gifsOn + "|" + cfg.gifSize + "|" + sampleText;
        boolean over = scrMsg != null && !scrHold && now - scrMsg.time > (long) ((cfg.showSeconds + cfg.fadeSeconds) * 1000f) + 700L;
        if (scrMsg == null || over || !sig.equals(scrSig)) {
            scrSig = sig;
            cfg.idleMessages = 1;
            scrMsg = ChatStore.process(Component.literal(ScreenNet.colors(sampleText)), cfg, now);
        }
        if (scrMsg == null) return;
        float real = cfg.showSeconds;
        if (scrHold) cfg.showSeconds = 9999f;
        ChatRenderer.draw(g, List.of(scrMsg), cfg, sw, sh, 0f, 0, false, false);
        cfg.showSeconds = real;
    }

    private static String trimF(float v) { return String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", "").replaceAll("\\.$", ""); }

    /** 0 = no, 1 = inside the chat box, 2 = on the resize handle (mouse in GUI pixels of this screen) */
    private int hit(double mx, double my) {
        double rx = (mx - sx0) / sk, ry = (my - sy0) / sk;
        if (rx < ChatRenderer.boxX0 - 4 || rx > ChatRenderer.boxX1 + 4 || ry < ChatRenderer.boxY0 - 4 || ry > ChatRenderer.boxY1 + 4) return 0;
        boolean rightA = cfg.anchor.endsWith("right");
        double edge = rightA ? ChatRenderer.boxX0 : ChatRenderer.boxX1;
        if (Math.abs(rx - edge) <= 6) return 2;
        return 1;
    }

    private void renderPanel(GuiGraphics g, int mx, int my, float pt) {
        g.fill(left, 8, left + PANEL_W, height - 8, BG);
        g.fill(left, 8, left + PANEL_W, 9, ACC);
        g.fill(left, 8, left + 1, height - 8, LINE);
        g.fill(left + PANEL_W - 1, 8, left + PANEL_W, height - 8, LINE);
        g.fill(left, height - 9, left + PANEL_W, height - 8, LINE);
        g.drawString(font, "§l" + t("chat.title"), left + 8, 11, TXT, false);
        g.fill(left + 4, cTop - 2, left + PANEL_W - 4, cBot + 2, 0x66000000);
        int maxScroll = Math.max(0, contentH - (cBot - cTop) + 4);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        g.enableScissor(left + 4, cTop, left + PANEL_W - 4, cBot);
        for (Object[] h : headings) {
            int yy = (Integer) h[0] - scroll;
            if (yy < cTop - 12 || yy > cBot) continue;
            int kind = (Integer) h[2];
            int xo = kind >= 3 ? 74 : 0;
            if (h[1] instanceof String s) {
                g.drawString(font, s, left + 8 + xo, yy, kind == 0 || kind == 3 ? ACC2 : TXT, false);
            } else if (h[1] instanceof net.minecraft.util.FormattedCharSequence seq) {
                g.drawString(font, seq, left + 8 + xo, yy, DIM, false);
            }
        }
        for (int i = 0; i < body.size(); i++) {
            AbstractWidget w = body.get(i);
            int yy = bodyY.get(i) - scroll;
            w.setY(yy);
            boolean vis = yy + w.getHeight() > cTop && yy < cBot;
            w.visible = vis;
            if (vis) w.render(g, mx, my, pt);
        }
        for (Object[] fb : fontButtons) {
            AbstractWidget w = (AbstractWidget) fb[0];
            if (!w.visible) continue;
            String id = (String) fb[1];
            if (id.equals(cfg.font)) g.fill(w.getX() + 1, w.getY() + 1, w.getX() + 3, w.getY() + w.getHeight() - 1, ACC2);
            Component sample = Component.literal(id.replace("minecraft:", "") + "  Aa Бб 123").withStyle(Style.EMPTY.withFont(new ResourceLocation(id)));
            g.drawString(font, sample, w.getX() + 8, w.getY() + 6, TXT, false);
        }
        for (Object[] ib : iconButtons) {
            AbstractWidget w = (AbstractWidget) ib[0];
            if (w.visible) ChatIcons.draw(g, "file:" + ib[1], w.getX() + 3, w.getY() + 2, 16, 1f);
        }
        g.disableScissor();
        if (maxScroll > 0) {
            int th = Math.max(14, (cBot - cTop) * (cBot - cTop) / (contentH + 4));
            int ty = cTop + (cBot - cTop - th) * scroll / maxScroll;
            g.fill(left + PANEL_W - 6, ty, left + PANEL_W - 4, ty + th, ACC);
        }
        super.render(g, mx, my, pt);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseScrolled(double mx, double my, double d) {
        if (mx < left + PANEL_W) { scroll -= (int) (d * 22); return true; }
        int h = hit(mx, my);
        if (h > 0) {   // wheel over the chat resizes the whole chat
            cfg.scale = Math.max(0.3f, Math.min(4f, cfg.scale + (d > 0 ? 0.05f : -0.05f)));
            cfg.touch();
            return true;
        }
        return super.mouseScrolled(mx, my, d);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        for (AbstractWidget w : body) if (my < cTop || my > cBot) w.visible = false;
        // preview mode tabs
        if (!scr && my >= 8 && my < 28 && mx >= previewTabX1 && mx < previewTabEnd) {
            liveMode = mx >= previewTabX2;
            if (liveMode) resetLive();
            return true;
        }
        if (btn == 0 && mx > left + PANEL_W) {
            int h = hit(mx, my);
            if (h > 0) {
                dragMode = h;
                dragRx = (mx - sx0) / sk;
                dragRy = (my - sy0) / sk;
                dragX = cfg.x; dragY = cfg.y; dragW = cfg.width;
                setFocused(null);
                return true;
            }
        }
        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (dragMode != 0) {
            double rx = (mx - sx0) / sk - dragRx, ry = (my - sy0) / sk - dragRy;
            String a = cfg.anchor;
            boolean top = a.startsWith("top"), rightA = a.endsWith("right"), center = a.endsWith("center");
            if (dragMode == 1) {
                cfg.x = dragX + (int) Math.round(rightA ? -rx : rx);
                cfg.y = dragY + (int) Math.round(top ? ry : -ry);
                // magnet to the anchor edge / centre
                if (Math.abs(cfg.x) < 4) cfg.x = 0;
                if (Math.abs(cfg.y) < 4 && !a.startsWith("bottom")) cfg.y = 0;
            } else {
                double dw = (rightA ? -rx : rx) * (center ? 2.0 : 1.0) / cfg.scale;
                cfg.width = (int) Math.round(dragW + dw);
            }
            cfg.touch();
            return true;
        }
        return super.mouseDragged(mx, my, btn, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        if (dragMode == 1 && snapAnchor) {
            Minecraft mc = Minecraft.getInstance();
            int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
            float cxm = (ChatRenderer.boxX0 + ChatRenderer.boxX1) / 2f, cym = (ChatRenderer.boxY0 + ChatRenderer.boxY1) / 2f;
            int col = Math.max(0, Math.min(2, (int) (cxm / sw * 3f))), row = Math.max(0, Math.min(2, (int) (cym / sh * 3f)));
            String na = ChatCfg.ANCHORS.get(row * 3 + col);
            if (!na.equals(cfg.anchor)) {
                int base = cfg.vcenter ? Math.round((ChatRenderer.boxY0 + ChatRenderer.boxY1) / 2f) : na.startsWith("top") ? Math.round(ChatRenderer.boxY0) : Math.round(ChatRenderer.boxY1);
                ChatRenderer.reanchor(cfg, na, sw, sh, Math.round(ChatRenderer.boxX0), base);
                cfg.touch();
                init();
            }
        }
        dragMode = 0;
        return super.mouseReleased(mx, my, btn);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        boolean ctrl = (mods & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean typing = getFocused() instanceof EditBox eb && eb.isFocused();
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (typing) { setFocused(null); return true; }
            onClose();
            return true;
        }
        if (!typing) {
            if (ctrl && key == GLFW.GLFW_KEY_Z) { undo(-1); return true; }
            if (ctrl && key == GLFW.GLFW_KEY_Y) { undo(1); return true; }
            int step = (mods & GLFW.GLFW_MOD_SHIFT) != 0 ? 5 : 1;
            boolean top = cfg.anchor.startsWith("top"), rightA = cfg.anchor.endsWith("right");
            boolean moved = true;
            switch (key) {
                case GLFW.GLFW_KEY_LEFT -> cfg.x += rightA ? step : -step;
                case GLFW.GLFW_KEY_RIGHT -> cfg.x += rightA ? -step : step;
                case GLFW.GLFW_KEY_UP -> cfg.y += top ? -step : step;
                case GLFW.GLFW_KEY_DOWN -> cfg.y += top ? step : -step;
                default -> moved = false;
            }
            if (moved) { cfg.touch(); return true; }
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void onClose() {
        saveCurrent();
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void removed() {
        saveCurrent();
        if (current == this) current = null;
    }
}
