package com.fpstudio.world;

import com.fpstudio.chat.ChatCfg;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Packets of the "text on the player's screen" feature. They share the channel of {@link NpcNet}. */
public final class ScreenNet {
    private static final int MAX = 120000;

    private ScreenNet() {}

    static int register(int id) {
        NpcNet.CH.messageBuilder(Show.class, id++, NetworkDirection.PLAY_TO_CLIENT).encoder(Show::write).decoder(Show::new).consumerMainThread(Show::handle).add();
        NpcNet.CH.messageBuilder(Clear.class, id++, NetworkDirection.PLAY_TO_CLIENT).encoder(Clear::write).decoder(Clear::new).consumerMainThread(Clear::handle).add();
        NpcNet.CH.messageBuilder(Presets.class, id++, NetworkDirection.PLAY_TO_CLIENT).encoder(Presets::write).decoder(Presets::new).consumerMainThread(Presets::handle).add();
        NpcNet.CH.messageBuilder(OpenEditor.class, id++, NetworkDirection.PLAY_TO_CLIENT).encoder(OpenEditor::write).decoder(OpenEditor::new).consumerMainThread(OpenEditor::handle).add();
        NpcNet.CH.messageBuilder(Save.class, id++, NetworkDirection.PLAY_TO_SERVER).encoder(Save::write).decoder(Save::new).consumerMainThread(Save::handle).add();
        NpcNet.CH.messageBuilder(Delete.class, id++, NetworkDirection.PLAY_TO_SERVER).encoder(Delete::write).decoder(Delete::new).consumerMainThread(Delete::handle).add();
        NpcNet.CH.messageBuilder(Request.class, id++, NetworkDirection.PLAY_TO_SERVER).encoder(Request::write).decoder(Request::new).consumerMainThread(Request::handle).add();
        return id;
    }

    // ------------------------------------------------------------------ server API

    /** Show text with a preset on one player's screen. seconds <= 0: the preset's own time. */
    public static void show(ServerPlayer p, String preset, float seconds, String text) {
        String name = ScreenPresets.clean(preset);
        String json = ScreenPresets.get(name);
        if (json == null) json = ScreenPresets.get("default");
        NpcNet.CH.send(PacketDistributor.PLAYER.with(() -> p), new Show(name, json, seconds, text));
    }

    public static void clear(ServerPlayer p, String preset) {
        NpcNet.CH.send(PacketDistributor.PLAYER.with(() -> p), new Clear(ScreenPresets.clean(preset)));
    }

    public static void sendPresets(ServerPlayer p) {
        NpcNet.CH.send(PacketDistributor.PLAYER.with(() -> p), new Presets(ScreenPresets.all()));
    }

    public static void openEditor(ServerPlayer p, String preset) {
        NpcNet.CH.send(PacketDistributor.PLAYER.with(() -> p), new OpenEditor(ScreenPresets.clean(preset)));
    }

    /** "&c" colour codes for command text */
    public static String colors(String s) { return s.replaceAll("&([0-9a-fk-orA-FK-OR])", "§$1"); }

    private static ServerPlayer editor(Supplier<NetworkEvent.Context> c) {
        ServerPlayer p = c.get().getSender();
        return p != null && NpcNet.canEdit(p) ? p : null;
    }

    // ------------------------------------------------------------------ S2C

    public static final class Show {
        final String id, json, text;
        final float seconds;

        public Show(String id, String json, float seconds, String text) { this.id = id; this.json = json; this.seconds = seconds; this.text = text; }

        Show(FriendlyByteBuf b) { id = b.readUtf(64); json = b.readUtf(MAX); seconds = b.readFloat(); text = b.readUtf(4000); }

        void write(FriendlyByteBuf b) { b.writeUtf(id, 64); b.writeUtf(json, MAX); b.writeFloat(seconds); b.writeUtf(text, 4000); }

        void handle(Supplier<NetworkEvent.Context> c) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.fpstudio.client.screen.ScreenClient.show(id, json, seconds, text));
        }
    }

    public static final class Clear {
        final String id;

        public Clear(String id) { this.id = id; }

        Clear(FriendlyByteBuf b) { id = b.readUtf(64); }

        void write(FriendlyByteBuf b) { b.writeUtf(id, 64); }

        void handle(Supplier<NetworkEvent.Context> c) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.fpstudio.client.screen.ScreenClient.clear(id));
        }
    }

    public static final class Presets {
        final Map<String, String> map;

        public Presets(Map<String, String> map) { this.map = map; }

        Presets(FriendlyByteBuf b) {
            map = new LinkedHashMap<>();
            int n = b.readVarInt();
            for (int i = 0; i < n; i++) map.put(b.readUtf(64), b.readUtf(MAX));
        }

        void write(FriendlyByteBuf b) {
            b.writeVarInt(map.size());
            for (var e : map.entrySet()) { b.writeUtf(e.getKey(), 64); b.writeUtf(e.getValue(), MAX); }
        }

        void handle(Supplier<NetworkEvent.Context> c) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.fpstudio.client.screen.ScreenClient.setPresets(map));
        }
    }

    public static final class OpenEditor {
        final String preset;

        public OpenEditor(String preset) { this.preset = preset; }

        OpenEditor(FriendlyByteBuf b) { preset = b.readUtf(64); }

        void write(FriendlyByteBuf b) { b.writeUtf(preset, 64); }

        void handle(Supplier<NetworkEvent.Context> c) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.fpstudio.client.screen.ScreenClient.openEditor(preset));
        }
    }

    // ------------------------------------------------------------------ C2S

    public static final class Save {
        final String name, json;

        public Save(String name, String json) { this.name = name; this.json = json; }

        Save(FriendlyByteBuf b) { name = b.readUtf(64); json = b.readUtf(MAX); }

        void write(FriendlyByteBuf b) { b.writeUtf(name, 64); b.writeUtf(json, MAX); }

        void handle(Supplier<NetworkEvent.Context> c) {
            ServerPlayer p = editor(c);
            if (p == null) return;
            ScreenPresets.put(name, json);
            ScreenNet.sendPresets(p);
        }
    }

    public static final class Delete {
        final String name;

        public Delete(String name) { this.name = name; }

        Delete(FriendlyByteBuf b) { name = b.readUtf(64); }

        void write(FriendlyByteBuf b) { b.writeUtf(name, 64); }

        void handle(Supplier<NetworkEvent.Context> c) {
            ServerPlayer p = editor(c);
            if (p == null) return;
            ScreenPresets.remove(name);
            ScreenNet.sendPresets(p);
        }
    }

    public static final class Request {
        public Request() {}

        Request(FriendlyByteBuf b) {}

        void write(FriendlyByteBuf b) {}

        void handle(Supplier<NetworkEvent.Context> c) {
            ServerPlayer p = editor(c);
            if (p != null) ScreenNet.sendPresets(p);
        }
    }

}
