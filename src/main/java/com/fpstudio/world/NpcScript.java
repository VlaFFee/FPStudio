package com.fpstudio.world;

import com.fpstudio.FPStudio;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Line based NPC script. One instruction per line, '#' starts a comment. See README for the full command list.
 * The interpreter runs on the server, one slice per tick, and stops at every wait.
 */
public final class NpcScript {
    private enum Wait { NONE, TICKS, ARRIVE, ANIM, INTERACT, NEAR }

    private static final class Frame {
        final String name;
        final String[] lines;
        final Map<String, Integer> labels = new HashMap<>();
        int pc;

        Frame(String name, String src) {
            this.name = name;
            lines = src.split("\\r?\\n");
            for (int i = 0; i < lines.length; i++) {
                String t = lines[i].trim();
                if (t.regionMatches(true, 0, "label ", 0, 6)) labels.put(t.substring(6).trim().toLowerCase(Locale.ROOT), i);
                else if (t.endsWith(":") && !t.contains(" ")) labels.put(t.substring(0, t.length() - 1).toLowerCase(Locale.ROOT), i);
            }
        }
    }

    private final StudioNpc npc;
    private final UUID ctx;
    private final List<Frame> stack = new ArrayList<>();
    private boolean done;
    private Wait wait = Wait.NONE;
    private int ticks, waitedFor;
    private double tx, ty, tz, nearRange;
    private int endTick;

    public NpcScript(StudioNpc npc, String name, String src, UUID ctx) {
        this.npc = npc;
        this.ctx = ctx;
        stack.add(new Frame(name, src));
    }

    public boolean running() { return !done; }

    private Player player() { return ctx == null ? null : npc.level().getPlayerByUUID(ctx); }

    // ------------------------------------------------------------------ loop

    public void tick() {
        if (done) return;
        if (!waitFinished()) return;
        wait = Wait.NONE;
        int guard = 0;
        while (!done && wait == Wait.NONE && guard++ < 300) {
            if (stack.isEmpty()) { done = true; break; }
            Frame f = stack.get(stack.size() - 1);
            if (f.pc >= f.lines.length) { stack.remove(stack.size() - 1); continue; }
            String line = f.lines[f.pc++].trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue;
            try {
                exec(f, line);
            } catch (RuntimeException ex) {
                FPStudio.LOGGER.warn("fpstudio npc script '{}' line '{}': {}", f.name, line, ex.toString());
            }
        }
        if (stack.isEmpty()) done = true;
    }

    private boolean waitFinished() {
        switch (wait) {
            case NONE: return true;
            case TICKS: return --ticks <= 0;
            case ANIM: return npc.tickCount >= endTick;
            case INTERACT: return npc.takeInteract();
            case NEAR: {
                Player p = player();
                return p == null || p.distanceToSqr(npc) <= nearRange * nearRange;
            }
            case ARRIVE: {
                waitedFor++;
                double dx = tx - npc.getX(), dz = tz - npc.getZ();
                boolean near = dx * dx + dz * dz < 1.7;
                boolean idle = waitedFor > 10 && npc.getNavigation().isDone();
                if (near || idle || waitedFor > 20 * 40) {
                    npc.getNavigation().stop();
                    npc.setRunning(false);
                    return true;
                }
                return false;
            }
            default: return true;
        }
    }

    // ------------------------------------------------------------------ helpers

    private String subst(String s) {
        if (s.indexOf('{') < 0) return s;
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int a = s.indexOf('{', i);
            if (a < 0) { out.append(s, i, s.length()); break; }
            int b = s.indexOf('}', a);
            if (b < 0) { out.append(s, i, s.length()); break; }
            out.append(s, i, a);
            out.append(lookup(s.substring(a + 1, b)));
            i = b + 1;
        }
        return out.toString();
    }

    private String lookup(String key) {
        Player p = player();
        switch (key) {
            case "player": return p == null ? "" : p.getGameProfile().getName();
            case "npc": case "name": return npc.profile().name;
            case "x": return String.valueOf((int) Math.floor(npc.getX()));
            case "y": return String.valueOf((int) Math.floor(npc.getY()));
            case "z": return String.valueOf((int) Math.floor(npc.getZ()));
            case "time": return String.valueOf(npc.level().getDayTime() % 24000L);
            default: break;
        }
        String v = getVar(key);
        return v == null ? "" : v;
    }

    private String getVar(String key) {
        if (key.startsWith("g.")) {
            var srv = npc.level().getServer();
            return srv == null ? null : NpcWorldData.get(srv).vars.get(key);
        }
        return npc.profile().vars.get(key);
    }

    private void setVar(String key, String val) {
        if (key.startsWith("g.")) {
            var srv = npc.level().getServer();
            if (srv != null) NpcWorldData.get(srv).set(key, val);
        } else {
            npc.profile().vars.put(key, val);
        }
    }

    private static String colors(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length() && "0123456789abcdefklmnorABCDEFKLMNOR".indexOf(s.charAt(i + 1)) >= 0) b.append('§');
            else b.append(c);
        }
        return b.toString();
    }

    private static double num(String s, double def) {
        try {
            return Double.parseDouble(s.trim().replace(',', '.'));
        } catch (RuntimeException e) {
            return def;
        }
    }

    private double coord(String tok, double base) {
        if (tok.startsWith("~")) return base + (tok.length() == 1 ? 0 : num(tok.substring(1), 0));
        return num(tok, base);
    }

    private void say(String text, boolean toCtxOnly) {
        var pr = npc.profile();
        int rgb = 0xB9F26B;
        try {
            String h = pr.nameColor.startsWith("#") ? pr.nameColor.substring(1) : pr.nameColor;
            rgb = Integer.parseInt(h, 16);
        } catch (RuntimeException ignored) {
            // keep the default colour
        }
        String icon = pr.chatIcon.isEmpty() ? (pr.asset.isEmpty() ? "" : "file:" + pr.asset) : pr.chatIcon;
        net.minecraft.network.chat.MutableComponent c = Component.empty();
        // invisible marker: the client chat reads the icon from it (see ChatStore)
        c.append(Component.empty().withStyle(net.minecraft.network.chat.Style.EMPTY.withInsertion("fpicon:" + icon)));
        c.append(Component.literal("[" + pr.name + "]").withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(net.minecraft.network.chat.TextColor.fromRgb(rgb))));
        c.append(Component.literal(": "));
        c.append(Component.literal(colors(text)));
        if (toCtxOnly) {
            Player p = player();
            if (p instanceof ServerPlayer sp) sp.sendSystemMessage(c);
            return;
        }
        double r = pr.chatRange;
        if (npc.level() instanceof ServerLevel sl) {
            for (ServerPlayer sp : sl.players()) if (r <= 0 || sp.distanceToSqr(npc) <= r * r) sp.sendSystemMessage(c);
        }
        npc.talk(Math.min(240, 20 + text.length() * 2));
    }

    private void waitTicks(int t) {
        if (t <= 0) return;
        wait = Wait.TICKS;
        ticks = t;
    }

    private Item item(String id) {
        return ForgeRegistries.ITEMS.getValue(new ResourceLocation(id.contains(":") ? id : "minecraft:" + id));
    }

    private boolean hasItem(Player p, Item it, int n) {
        if (p == null || it == null) return false;
        int have = 0;
        for (ItemStack st : p.getInventory().items) if (st.is(it)) have += st.getCount();
        return have >= n;
    }

    private boolean compare(String a, String op, String b) {
        a = a.isEmpty() ? "0" : a;
        b = b.isEmpty() ? "0" : b;
        boolean numeric = true;
        double x = 0, y = 0;
        try {
            x = Double.parseDouble(a);
            y = Double.parseDouble(b);
        } catch (NumberFormatException e) {
            numeric = false;
        }
        int c = numeric ? Double.compare(x, y) : a.compareTo(b);
        return switch (op) {
            case "==", "=" -> c == 0;
            case "!=" -> c != 0;
            case ">" -> c > 0;
            case "<" -> c < 0;
            case ">=" -> c >= 0;
            case "<=" -> c <= 0;
            default -> false;
        };
    }

    private void jump(Frame f, String label) {
        Integer to = f.labels.get(label.toLowerCase(Locale.ROOT));
        if (to != null) f.pc = to + 1;
        else FPStudio.LOGGER.warn("fpstudio npc script '{}': no label '{}'", f.name, label);
    }

    // ------------------------------------------------------------------ instructions

    private void exec(Frame f, String line) {
        int sp = line.indexOf(' ');
        String cmd = (sp < 0 ? line : line.substring(0, sp)).toLowerCase(Locale.ROOT);
        String raw = sp < 0 ? "" : line.substring(sp + 1).trim();
        String rest = subst(raw);
        String[] a = rest.isEmpty() ? new String[0] : rest.split("\\s+");
        Player p = player();
        switch (cmd) {
            case "label": return;
            case "say": say(rest, false); return;
            case "saywait": say(rest, false); waitTicks(30 + rest.length() * 2); return;
            case "whisper": say(rest, true); return;
            case "actionbar": if (p instanceof ServerPlayer s) s.displayClientMessage(Component.literal(colors(rest)), true); return;
            case "screen": {
                // screen <preset> <seconds|-1> <text...>
                if (a.length < 3) return;
                int i0 = rest.indexOf(a[0]) + a[0].length();
                String text = ScreenNet.colors(rest.substring(rest.indexOf(a[1], i0) + a[1].length()).trim());
                float secs = (float) num(a[1], -1);
                if (npc.level() instanceof ServerLevel sl) {
                    double r = npc.profile().chatRange;
                    for (ServerPlayer tp : sl.players()) if (r <= 0 || tp.distanceToSqr(npc) <= r * r) ScreenNet.show(tp, a[0], secs, text);
                }
                return;
            }
            case "screenclear": {
                if (npc.level() instanceof ServerLevel sl) {
                    double r = npc.profile().chatRange;
                    for (ServerPlayer tp : sl.players()) if (r <= 0 || tp.distanceToSqr(npc) <= r * r) ScreenNet.clear(tp, a.length > 0 ? a[0] : "");
                }
                return;
            }
            case "title": if (p instanceof ServerPlayer s) s.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(Component.literal(colors(rest)))); return;
            case "wait": waitTicks((int) Math.round(num(a.length > 0 ? a[0] : "1", 1) * 20)); return;
            case "waitinteract": wait = Wait.INTERACT; npc.takeInteract(); return;
            case "waitnear": wait = Wait.NEAR; nearRange = a.length > 0 ? num(a[0], 3) : 3; return;
            case "end": case "stop_script": stack.clear(); done = true; return;
            case "stop": npc.getNavigation().stop(); npc.startFollow(null, 3, false); npc.setRunning(false); return;
            case "goto": if (a.length > 0) jump(f, a[0]); return;
            case "call": {
                if (a.length == 0 || stack.size() > 12) return;
                String s = npc.profile().scripts.get(a[0]);
                if (s != null) stack.add(new Frame(a[0], s));
                return;
            }
            case "set": if (a.length >= 1) { String key = raw.split("\\s+")[0]; setVar(key, a.length > 1 ? rest.substring(rest.indexOf(' ') + 1).trim() : "1"); } return;
            case "add": if (a.length >= 1) {
                String key = raw.split("\\s+")[0];
                String cur = getVar(key);
                setVar(key, trimNum(num(cur == null ? "0" : cur, 0) + (a.length > 1 ? num(a[1], 1) : 1)));
            } return;
            case "if": doIf(f, raw); return;
            case "anim": {
                if (a.length == 0) return;
                npc.playAnim(a[0], a.length > 1 && a[1].equalsIgnoreCase("loop"));
                return;
            }
            case "animwait": {
                if (a.length == 0) return;
                npc.playAnim(a[0], false);
                float len = a.length > 1 ? (float) num(a[1], 1) : NpcAnimLengths.get(npc.profile().asset, a[0]);
                if (len < 0) len = 1f;
                wait = Wait.ANIM;
                endTick = npc.tickCount + Math.max(1, Math.round(len * 20f));
                return;
            }
            case "stopanim": npc.stopAnim(); return;
            case "sit": npc.setSitting(true); return;
            case "stand": npc.setSitting(false); return;
            case "look": {
                if (a.length == 0) return;
                if (a[0].equalsIgnoreCase("off")) npc.lookOverride = null;
                else if (a[0].equalsIgnoreCase("player")) { if (p != null) npc.lookOverride = new Vec3(p.getX(), p.getEyeY(), p.getZ()); }
                else if (a.length >= 3) npc.lookOverride = new Vec3(coord(a[0], npc.getX()), coord(a[1], npc.getEyeY()), coord(a[2], npc.getZ()));
                return;
            }
            case "turn": if (a.length > 0) { float y = (float) num(a[0], npc.getYRot()); npc.setYRot(y); npc.setYHeadRot(y); npc.setYBodyRot(y); } return;
            case "face": if (p != null) npc.faceTowards(p.position()); return;
            case "walk": {
                if (a.length < 3) return;
                tx = coord(a[0], npc.getX());
                ty = coord(a[1], npc.getY());
                tz = coord(a[2], npc.getZ());
                boolean run = a.length > 3 && a[3].equalsIgnoreCase("run");
                npc.setSitting(false);
                npc.getNavigation().moveTo(tx, ty, tz, run ? npc.profile().runSpeedMul : 1.0);
                npc.setRunning(run);
                wait = Wait.ARRIVE;
                waitedFor = 0;
                return;
            }
            case "walkto": {
                if (p == null) return;
                tx = p.getX(); ty = p.getY(); tz = p.getZ();
                boolean run = a.length > 0 && a[0].equalsIgnoreCase("run");
                npc.setSitting(false);
                npc.getNavigation().moveTo(p, run ? npc.profile().runSpeedMul : 1.0);
                npc.setRunning(run);
                wait = Wait.ARRIVE;
                waitedFor = 0;
                return;
            }
            case "follow": {
                if (p == null) return;
                float dist = a.length > 0 ? (float) num(a[0], 3) : 3f;
                npc.setSitting(false);
                npc.startFollow(p, dist, a.length > 1 && a[1].equalsIgnoreCase("run"));
                return;
            }
            case "tp": {
                if (a.length < 3) return;
                npc.getNavigation().stop();
                npc.teleportTo(coord(a[0], npc.getX()), coord(a[1], npc.getY()), coord(a[2], npc.getZ()));
                if (a.length > 3) { float y = (float) num(a[3], 0); npc.setYRot(y); npc.setYHeadRot(y); npc.setYBodyRot(y); }
                return;
            }
            case "tpplayer": {
                if (a.length < 3 || !(p instanceof ServerPlayer s)) return;
                s.teleportTo(coord(a[0], s.getX()), coord(a[1], s.getY()), coord(a[2], s.getZ()));
                return;
            }
            case "command": case "cmd": {
                var srv = npc.level().getServer();
                if (srv == null || rest.isEmpty()) return;
                CommandSourceStack src = npc.createCommandSourceStack().withPermission(4).withSuppressedOutput();
                srv.getCommands().performPrefixedCommand(src, rest);
                return;
            }
            case "give": {
                if (a.length == 0 || p == null) return;
                Item it = item(a[0]);
                if (it == null) return;
                ItemStack st = new ItemStack(it, a.length > 1 ? (int) num(a[1], 1) : 1);
                if (!p.getInventory().add(st)) p.drop(st, false);
                return;
            }
            case "take": {
                if (a.length == 0 || p == null) return;
                Item it = item(a[0]);
                int n = a.length > 1 ? (int) num(a[1], 1) : 1;
                if (it == null) return;
                for (ItemStack st : p.getInventory().items) {
                    if (n <= 0) break;
                    if (st.is(it)) { int d = Math.min(n, st.getCount()); st.shrink(d); n -= d; }
                }
                return;
            }
            case "sound": {
                if (a.length == 0) return;
                SoundEvent se = ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation(a[0]));
                if (se != null) npc.level().playSound(null, npc.getX(), npc.getY(), npc.getZ(), se, SoundSource.NEUTRAL,
                        a.length > 1 ? (float) num(a[1], 1) : 1f, a.length > 2 ? (float) num(a[2], 1) : 1f);
                return;
            }
            case "equip": {
                if (a.length < 2) return;
                var pr = npc.profile();
                switch (a[0].toLowerCase(Locale.ROOT)) {
                    case "mainhand", "hand" -> pr.mainHand = a[1].equals("none") ? "" : a[1];
                    case "offhand" -> pr.offHand = a[1].equals("none") ? "" : a[1];
                    case "head" -> pr.head = a[1].equals("none") ? "" : a[1];
                    case "chest" -> pr.chest = a[1].equals("none") ? "" : a[1];
                    case "legs" -> pr.legs = a[1].equals("none") ? "" : a[1];
                    case "feet" -> pr.feet = a[1].equals("none") ? "" : a[1];
                    default -> { return; }
                }
                npc.setProfile(pr);
                return;
            }
            case "setname": {
                var pr = npc.profile();
                pr.name = rest;
                npc.setProfile(pr);
                return;
            }
            case "cutscene": case "clip": {
                if (p instanceof ServerPlayer s && !rest.isEmpty()) NpcNet.sendPlayClip(s, rest);
                return;
            }
            case "dialog": {
                MinecraftForge.EVENT_BUS.post(new NpcEvents.Dialogue(npc, p, rest));
                return;
            }
            case "ext": {
                String name = a.length > 0 ? a[0] : "";
                String args = a.length > 1 ? rest.substring(rest.indexOf(' ') + 1).trim() : "";
                MinecraftForge.EVENT_BUS.post(new NpcEvents.Custom(npc, p, name, args));
                return;
            }
            default:
                FPStudio.LOGGER.warn("fpstudio npc script '{}': unknown command '{}'", f.name, cmd);
        }
    }

    private static String trimNum(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e12 ? String.valueOf((long) v) : String.valueOf(v);
    }

    /** if [!]cond ... goto label */
    private void doIf(Frame f, String raw) {
        String[] t = subst(raw).split("\\s+");
        int g = -1;
        for (int i = 0; i < t.length; i++) if (t[i].equalsIgnoreCase("goto")) { g = i; break; }
        if (g < 1 || g + 1 >= t.length) return;
        String label = t[g + 1];
        boolean neg = t[0].startsWith("!");
        String first = (neg ? t[0].substring(1) : t[0]).toLowerCase(Locale.ROOT);
        boolean res;
        switch (first) {
            case "hasitem": res = t.length > 2 && hasItem(player(), item(t[1]), g > 2 ? (int) num(t[2], 1) : 1); break;
            case "near": {
                Player p = player();
                res = p != null && p.distanceToSqr(npc) <= num(t.length > 1 ? t[1] : "4", 4) * num(t.length > 1 ? t[1] : "4", 4);
                break;
            }
            case "day": res = npc.level().getDayTime() % 24000L < 13000L; break;
            case "night": res = npc.level().getDayTime() % 24000L >= 13000L; break;
            case "sneaking": res = player() != null && player().isShiftKeyDown(); break;
            default: {
                if (g < 3) return;
                String left = neg ? t[0].substring(1) : t[0];
                res = compare(left, t[1], t[2]);
            }
        }
        if (res != neg) jump(f, label);
    }
}
