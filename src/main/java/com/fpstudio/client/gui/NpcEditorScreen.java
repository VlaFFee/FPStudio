package com.fpstudio.client.gui;

import com.fpstudio.client.npc.NpcLibrary;
import com.fpstudio.npc.NpcAsset;
import com.fpstudio.world.NpcNet;
import com.fpstudio.world.NpcProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Editor for one world NPC (or the "new NPC" dialog when entityId < 0): model, behaviour, animations, route, scripts.
 * Edits a local copy of the profile; "Save" sends it to the server.
 */
public final class NpcEditorScreen extends Screen {
    private static final int TAB_MAIN = 0, TAB_ANIM = 1, TAB_ROUTE = 2, TAB_SCRIPT = 3;

    private final int entityId;
    private final NpcProfile p;
    private final double sx, sy, sz;
    private final float syaw;
    private int tab;
    private int left, top, pw, ph;
    private final TextArea area = new TextArea();
    private String script = "on_interact";
    private String newScript = "";
    private String testAnim = "";
    private int routePage;
    private boolean showHelp;
    private String status = "";
    private long statusUntil;

    /** Edit an existing NPC. */
    public NpcEditorScreen(int entityId, NpcProfile profile) {
        super(Component.literal("NPC"));
        this.entityId = entityId;
        this.p = profile;
        this.sx = this.sy = this.sz = 0;
        this.syaw = 0;
        init0();
    }

    /** Create a new NPC at the given spot. */
    public NpcEditorScreen(double x, double y, double z, float yaw) {
        super(Component.literal("NPC"));
        this.entityId = -1;
        this.p = new NpcProfile();
        this.sx = x;
        this.sy = y;
        this.sz = z;
        this.syaw = yaw;
        var all = NpcLibrary.all();
        if (!all.isEmpty()) p.asset = all.iterator().next().name;
        init0();
    }

    private void init0() {
        area.onChange(s -> p.scripts.put(script, s));
        if (!p.scripts.containsKey(script)) script = p.scripts.isEmpty() ? "on_interact" : p.scripts.keySet().iterator().next();
        area.set(p.scripts.getOrDefault(script, ""));
    }

    private static String t(String k) { return I18n.get("fpstudio.gui." + k); }

    private NpcAsset asset() { return NpcLibrary.get(p.asset); }

    // ------------------------------------------------------------------ layout helpers

    @Override
    protected void init() {
        pw = Math.min(width - 12, 400);
        ph = Math.min(height - 12, 282);
        left = (width - pw) / 2;
        top = (height - ph) / 2;
        clearWidgets();
        labels.clear();
        String[] tabs = {t("npc.tab_main"), t("npc.tab_anim"), t("npc.tab_route"), t("npc.tab_script")};
        int tw = (pw - 8) / 4;
        for (int i = 0; i < 4; i++) {
            final int ti = i;
            Button b = Button.builder(Component.literal((tab == i ? "§e" : "") + tabs[i]), x -> { tab = ti; rebuild(); })
                    .bounds(left + 4 + i * tw, top + 4, tw - 2, 16).build();
            addRenderableWidget(b);
        }
        switch (tab) {
            case TAB_MAIN -> buildMain();
            case TAB_ANIM -> buildAnim();
            case TAB_ROUTE -> buildRoute();
            default -> buildScript();
        }
        int fy = top + ph - 22, fw = (pw - 12) / 5;
        addRenderableWidget(Button.builder(Component.literal("§a" + t("npc.save")), x -> save()).bounds(left + 4, fy, fw - 2, 18).build());
        if (entityId >= 0) {
            addRenderableWidget(Button.builder(Component.literal(t("npc.tp_here")), x -> act(NpcNet.A_TP_HERE, "")).bounds(left + 4 + fw, fy, fw - 2, 18).build());
            addRenderableWidget(Button.builder(Component.literal("§c" + t("npc.remove")), x -> { act(NpcNet.A_REMOVE, ""); onClose(); }).bounds(left + 4 + fw * 2, fy, fw - 2, 18).build());
        }
        addRenderableWidget(Button.builder(Component.literal(t("npc.folder")), x -> NpcLibrary.openFolder()).bounds(left + 4 + fw * 3, fy, fw - 2, 18).build());
        addRenderableWidget(Button.builder(Component.literal(t("npc.close")), x -> onClose()).bounds(left + 4 + fw * 4, fy, fw - 2, 18).build());
    }

    private void rebuild() { init(); }

    private void toast(String s) { status = s; statusUntil = System.currentTimeMillis() + 2500; }

    private EditBox box(int x, int y, int w, String value, Consumer<String> set, int max) {
        EditBox e = new EditBox(font, x, y, w, 16, Component.empty());
        e.setMaxLength(max);
        e.setValue(value == null ? "" : value);
        e.setResponder(set);
        addRenderableWidget(e);
        return e;
    }

    private EditBox numBox(int x, int y, int w, float value, Consumer<Float> set) {
        EditBox e = box(x, y, w, trim(value), s -> {
            try { set.accept(Float.parseFloat(s.trim().replace(',', '.'))); } catch (NumberFormatException ignored) { /* keep old value */ }
        }, 10);
        e.setFilter(s -> s.matches("-?[0-9]*[.,]?[0-9]*"));
        return e;
    }

    private static String trim(float v) {
        String s = String.format(Locale.ROOT, "%.3f", v);
        if (s.indexOf('.') >= 0) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }

    private Button toggle(int x, int y, int w, String label, Supplier<Boolean> get, Consumer<Boolean> set) {
        Button[] ref = new Button[1];
        ref[0] = Button.builder(Component.literal(label + ": " + (get.get() ? t("on") : t("off"))), b -> {
            set.accept(!get.get());
            b.setMessage(Component.literal(label + ": " + (get.get() ? t("on") : t("off"))));
        }).bounds(x, y, w, 16).build();
        addRenderableWidget(ref[0]);
        return ref[0];
    }

    private Button cycle(int x, int y, int w, String label, List<String> opts, Supplier<String> get, Consumer<String> set) {
        return cycle(x, y, w, label, opts, get, set, s -> s.isEmpty() ? t("npc.auto") : s);
    }

    private Button cycle(int x, int y, int w, String label, List<String> opts, Supplier<String> get, Consumer<String> set, java.util.function.Function<String, String> show) {
        Button b = Button.builder(Component.literal(label + ": " + show.apply(get.get())), bb -> {
            if (opts.isEmpty()) return;
            int i = opts.indexOf(get.get());
            i = i < 0 ? 0 : (i + (hasShiftDown() ? opts.size() - 1 : 1)) % opts.size();
            set.accept(opts.get(i));
            bb.setMessage(Component.literal(label + ": " + show.apply(get.get())));
        }).bounds(x, y, w, 16).build();
        addRenderableWidget(b);
        return b;
    }

    private Button btn(int x, int y, int w, String label, Runnable r) {
        Button b = Button.builder(Component.literal(label), bb -> r.run()).bounds(x, y, w, 16).build();
        addRenderableWidget(b);
        return b;
    }

    // ------------------------------------------------------------------ tabs

    private void buildMain() {
        int x0 = left + 6, cw = (pw - 18) / 2, x1 = x0 + cw + 6, y = top + 28;
        List<String> models = new ArrayList<>();
        for (NpcAsset a : NpcLibrary.all()) models.add(a.name);
        cycle(x0, y, cw, t("npc.model"), models, () -> p.asset, v -> p.asset = v, s -> s.isEmpty() ? "-" : s);
        btn(x1, y, cw, t("npc.reload"), () -> { NpcLibrary.reload(); rebuild(); toast(t("npc.reloaded")); });
        y += 22;
        label(x0, y, t("npc.name"));
        box(x0, y + 10, cw, p.name, v -> p.name = v, 40);
        toggle(x1, y + 10, cw, t("npc.show_name"), () -> p.showName, v -> p.showName = v);
        y += 30;
        label(x0, y, t("npc.scale"));
        numBox(x0, y + 10, cw / 2 - 2, p.scale, v -> p.scale = v);
        label(x0 + cw / 2 + 2, y, t("npc.width"));
        numBox(x0 + cw / 2 + 2, y + 10, cw / 2 - 2, p.width, v -> p.width = v);
        label(x1, y, t("npc.height"));
        numBox(x1, y + 10, cw / 2 - 2, p.height, v -> p.height = v);
        label(x1 + cw / 2 + 2, y, t("npc.speed"));
        numBox(x1 + cw / 2 + 2, y + 10, cw / 2 - 2, p.speed, v -> p.speed = v);
        y += 30;
        toggle(x0, y, cw, t("npc.invulnerable"), () -> p.invulnerable, v -> p.invulnerable = v);
        toggle(x1, y, cw, t("npc.pushable"), () -> p.pushable, v -> p.pushable = v);
        y += 20;
        toggle(x0, y, cw, t("npc.look_player"), () -> p.lookAtPlayer, v -> p.lookAtPlayer = v);
        toggle(x1, y, cw, t("npc.face_talk"), () -> p.faceOnTalk, v -> p.faceOnTalk = v);
        y += 22;
        label(x0, y, t("npc.look_range"));
        numBox(x0, y + 10, cw / 2 - 2, p.lookRange, v -> p.lookRange = v);
        label(x0 + cw / 2 + 2, y, t("npc.wander"));
        numBox(x0 + cw / 2 + 2, y + 10, cw / 2 - 2, p.wanderRadius, v -> p.wanderRadius = v);
        label(x1, y, t("npc.near_range"));
        numBox(x1, y + 10, cw / 2 - 2, p.nearRange, v -> p.nearRange = v);
        label(x1 + cw / 2 + 2, y, t("npc.chat_range"));
        numBox(x1 + cw / 2 + 2, y + 10, cw / 2 - 2, p.chatRange, v -> p.chatRange = v);
        y += 30;
        label(x0, y, t("npc.main_hand"));
        box(x0, y + 10, cw, p.mainHand, v -> p.mainHand = v.trim(), 60);
        label(x1, y, t("npc.off_hand"));
        box(x1, y + 10, cw, p.offHand, v -> p.offHand = v.trim(), 60);
        y += 30;
        label(x0, y, t("npc.chat_icon"));
        box(x0, y + 10, cw, p.chatIcon, v -> p.chatIcon = v.trim(), 60);
        label(x1, y, t("npc.name_color"));
        box(x1, y + 10, cw, p.nameColor, v -> p.nameColor = v.trim(), 9);
    }

    private void label(int x, int y, String s) { /* drawn in render: collected */ labels.add(new Object[]{x, y, s}); }

    private final List<Object[]> labels = new ArrayList<>();

    private void buildAnim() {
        int x0 = left + 6, cw = (pw - 18) / 2, x1 = x0 + cw + 6, y = top + 28;
        NpcAsset a = asset();
        List<String> names = new ArrayList<>();
        names.add("");
        if (a != null) names.addAll(a.animNames);
        cycle(x0, y, cw, t("npc.a_idle"), names, () -> p.idle, v -> p.idle = v);
        cycle(x1, y, cw, t("npc.a_walk"), names, () -> p.walk, v -> p.walk = v);
        y += 20;
        cycle(x0, y, cw, t("npc.a_run"), names, () -> p.run, v -> p.run = v);
        cycle(x1, y, cw, t("npc.a_talk"), names, () -> p.talk, v -> p.talk = v);
        y += 20;
        cycle(x0, y, cw, t("npc.a_sit"), names, () -> p.sit, v -> p.sit = v);
        label(x1, y, t("npc.walk_speed_anim"));
        numBox(x1, y + 9, cw, p.walkSpeedAnim, v -> p.walkSpeedAnim = v);
        y += 34;
        if (a == null || a.animNames.isEmpty()) { labels.add(new Object[]{x0, y, t("npc.no_anims")}); }
        else {
            if (testAnim.isEmpty() || !a.animNames.contains(testAnim)) testAnim = a.animNames.get(0);
            cycle(x0, y, pw - 12, t("npc.test_anim"), a.animNames, () -> testAnim, v -> testAnim = v, s -> s);
            y += 20;
            int bw = (pw - 12 - 12) / 3;
            btn(x0, y, bw, "▶ " + t("npc.play_once"), () -> act(NpcNet.A_ANIM, testAnim + "|0"));
            btn(x0 + bw + 6, y, bw, "↻ " + t("npc.play_loop"), () -> act(NpcNet.A_ANIM, testAnim + "|1"));
            btn(x0 + 2 * (bw + 6), y, bw, "■ " + t("npc.stop"), () -> act(NpcNet.A_STOP, ""));
            y += 22;
            btn(x0, y, pw - 12, t("npc.edit_anim"), this::editAnim);
        }
        y += 24;
        labels.add(new Object[]{x0, y, t("npc.anim_hint")});
    }

    private void editAnim() {
        NpcAsset a = asset();
        if (a == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!com.fpstudio.client.Actions.allowed(mc.player)) { toast(t("npc.need_creative")); return; }
        mc.setScreen(new StudioScreen());
        if (mc.screen instanceof StudioScreen ss) ss.editNpcAnimation(p.asset, testAnim);
    }

    private void buildRoute() {
        int x0 = left + 6, cw = (pw - 18) / 2, x1 = x0 + cw + 6, y = top + 28;
        cycle(x0, y, cw, t("npc.route_mode"), List.of("none", "loop", "pingpong", "once"), () -> p.routeMode, v -> p.routeMode = v, s -> t("npc.rm_" + s));
        toggle(x1, y, cw, t("npc.route_run"), () -> p.routeRun, v -> p.routeRun = v);
        y += 20;
        btn(x0, y, cw, t("npc.pt_me"), () -> { var pl = Minecraft.getInstance().player; if (pl != null) { p.route.add(new NpcProfile.Point(Math.floor(pl.getX()) + 0.5, pl.getY(), Math.floor(pl.getZ()) + 0.5)); rebuild(); } });
        btn(x1, y, cw, t("npc.pt_clear"), () -> { p.route.clear(); routePage = 0; rebuild(); });
        y += 22;
        int perPage = Math.max(1, Math.min(8, (ph - 28 - 20 - 22 - 44) / 20));
        int pages = Math.max(1, (p.route.size() + perPage - 1) / perPage);
        routePage = Math.max(0, Math.min(routePage, pages - 1));
        for (int i = routePage * perPage; i < Math.min(p.route.size(), (routePage + 1) * perPage); i++) {
            final int idx = i;
            NpcProfile.Point pt = p.route.get(i);
            labels.add(new Object[]{x0, y + 4, "#" + (i + 1) + "  " + (int) Math.floor(pt.x) + " " + (int) Math.floor(pt.y) + " " + (int) Math.floor(pt.z)});
            numBox(x0 + 120, y, 40, pt.wait, v -> pt.wait = Math.max(0f, v));
            box(x0 + 164, y, 100, pt.script, v -> pt.script = v.trim(), 30);
            btn(x0 + 268, y, 16, "↑", () -> { if (idx > 0) { var q = p.route.remove(idx); p.route.add(idx - 1, q); rebuild(); } });
            btn(x0 + 286, y, 16, "x", () -> { p.route.remove(idx); rebuild(); });
            y += 20;
        }
        int by = top + ph - 44;
        if (pages > 1) {
            btn(x0, by, 40, "<", () -> { routePage = Math.max(0, routePage - 1); rebuild(); });
            btn(x0 + 44, by, 40, ">", () -> { routePage = Math.min(pages - 1, routePage + 1); rebuild(); });
            labels.add(new Object[]{x0 + 90, by + 4, (routePage + 1) + "/" + pages});
        }
        labels.add(new Object[]{x0 + 120, top + 60, t("npc.route_cols")});
    }

    private void buildScript() {
        int x0 = left + 6, y = top + 26;
        List<String> names = new ArrayList<>(List.of(NpcProfile.EVENTS));
        for (String k : p.scripts.keySet()) if (!names.contains(k)) names.add(k);
        int cw = pw - 12;
        cycle(x0, y, cw / 2 - 2, t("npc.script"), names, () -> script, v -> { p.scripts.put(script, area.text()); script = v; area.set(p.scripts.getOrDefault(script, "")); }, s -> s);
        int bw = (cw / 2 - 6) / 3;
        btn(x0 + cw / 2 + 2, y, bw, "▶ " + t("npc.run"), () -> { p.scripts.put(script, area.text()); save(); act(NpcNet.A_RUN, script); });
        btn(x0 + cw / 2 + 4 + bw, y, bw, "■ " + t("npc.stop"), () -> act(NpcNet.A_STOP, ""));
        btn(x0 + cw / 2 + 6 + 2 * bw, y, bw, "? " + t("npc.help"), () -> { showHelp = !showHelp; });
        y += 20;
        box(x0, y, cw / 2 - 2, newScript, v -> newScript = v.trim().replaceAll("[^A-Za-z0-9_]", ""), 24);
        btn(x0 + cw / 2 + 2, y, bw, "+ " + t("npc.new_script"), () -> {
            if (newScript.isEmpty() || p.scripts.containsKey(newScript)) return;
            p.scripts.put(script, area.text());
            script = newScript;
            p.scripts.put(script, "# " + script + "\n");
            area.set(p.scripts.get(script));
            newScript = "";
            rebuild();
        });
        btn(x0 + cw / 2 + 4 + bw, y, bw * 2 + 2, "✕ " + t("npc.del_script"), () -> {
            if (isEvent(script)) { toast(t("npc.cant_delete")); return; }
            p.scripts.remove(script);
            script = "on_interact";
            area.set(p.scripts.getOrDefault(script, ""));
            rebuild();
        });
        y += 22;
        area.x = x0;
        area.y = y;
        area.w = showHelp ? cw * 5 / 9 : cw;
        area.h = top + ph - 28 - y;
        area.focus = false;
    }

    private static boolean isEvent(String s) {
        for (String e : NpcProfile.EVENTS) if (e.equals(s)) return true;
        return false;
    }

    // ------------------------------------------------------------------ actions

    private void act(int type, String arg) {
        if (entityId < 0) { toast(t("npc.save_first")); return; }
        NpcNet.CH.sendToServer(new NpcNet.Action(entityId, type, arg));
    }

    private void save() {
        p.scripts.put(script, area.text());
        p.scripts.values().removeIf(s -> s == null || s.isBlank());
        String json = p.toJson();
        if (json.length() > 100000) { toast(t("npc.too_big")); return; }
        if (entityId >= 0) NpcNet.CH.sendToServer(new NpcNet.SaveProfile(entityId, json));
        else {
            NpcNet.CH.sendToServer(new NpcNet.Spawn(json, sx, sy, sz, syaw));
            onClose();
            return;
        }
        toast(t("npc.saved"));
    }

    // ------------------------------------------------------------------ render / input

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        g.fill(left, top, left + pw, top + ph, 0xE8121120);
        g.fill(left, top, left + pw, top + 1, 0xFF8A82C8);
        g.fill(left, top + ph - 1, left + pw, top + ph, 0xFF8A82C8);
        g.fill(left, top, left + 1, top + ph, 0xFF8A82C8);
        g.fill(left + pw - 1, top, left + pw, top + ph, 0xFF8A82C8);
        for (Object[] l : labels) g.drawString(font, (String) l[2], (Integer) l[0], (Integer) l[1], 0xFFB9B6D8, false);
        if (tab == TAB_SCRIPT) {
            area.render(g, font, mx, my);
            if (showHelp) renderHelp(g);
        }
        super.render(g, mx, my, pt);
        if (!status.isEmpty() && System.currentTimeMillis() < statusUntil) {
            g.drawCenteredString(font, status, width / 2, top + ph - 36, 0xFFFFE9A8);
        }
        String sub = entityId < 0 ? t("npc.new_title") : t("npc.edit_title") + " #" + entityId;
        g.drawString(font, sub, left + 6, top - 10, 0xFFFFFFFF, true);
    }

    private void renderHelp(GuiGraphics g) {
        int hx = area.x + area.w + 6, hw = left + pw - 6 - hx;
        g.fill(hx, area.y, hx + hw, area.y + area.h, 0xFF0E0D16);
        String[] lines = t("npc.help_text").split("\n");
        int yy = area.y + 3;
        g.enableScissor(hx, area.y, hx + hw, area.y + area.h);
        for (String l : lines) {
            for (var seg : font.split(Component.literal(l), hw - 6)) {
                g.drawString(font, seg, hx + 3, yy, 0xFFC8C6E4, false);
                yy += font.lineHeight + 1;
            }
        }
        g.disableScissor();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (tab == TAB_SCRIPT && area.hit(mx, my)) {
            area.focus = true;
            setFocused(null);
            area.click(mx, my, font);
            return true;
        }
        area.focus = false;
        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double d) {
        if (tab == TAB_SCRIPT && area.hit(mx, my)) { area.scrollBy(d > 0 ? -2 : 2); return true; }
        return super.mouseScrolled(mx, my, d);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (area.focus) { area.focus = false; return true; }
            if (getFocused() instanceof EditBox eb && eb.isFocused()) { setFocused(null); return true; }
            onClose();
            return true;
        }
        if (tab == TAB_SCRIPT && area.key(key, mods)) return true;
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (tab == TAB_SCRIPT && area.charTyped(c)) return true;
        return super.charTyped(c, mods);
    }
}
