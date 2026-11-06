package com.fpstudio.world;

import com.fpstudio.FPStudio;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/** Network channel between the editor (client) and the NPC entities (server). */
public final class NpcNet {
    private static final String PROTO = "2";
    private static final int MAX = 120000;
    public static final SimpleChannel CH = NetworkRegistry.newSimpleChannel(new ResourceLocation(FPStudio.MODID, "main"),
            () -> PROTO, PROTO::equals, PROTO::equals);
    private static boolean registered;

    public static final int A_REMOVE = 0, A_TP_HERE = 1, A_ANIM = 2, A_RUN = 3, A_STOP = 4, A_EDIT = 5, A_PUSH_HOME = 6;

    private NpcNet() {}

    public static boolean canEdit(Player p) { return p.isCreative() || p.hasPermissions(2); }

    public static void register() {
        if (registered) return;
        registered = true;
        int id = 0;
        CH.messageBuilder(SaveProfile.class, id++, NetworkDirection.PLAY_TO_SERVER).encoder(SaveProfile::write).decoder(SaveProfile::new).consumerMainThread(SaveProfile::handle).add();
        CH.messageBuilder(Spawn.class, id++, NetworkDirection.PLAY_TO_SERVER).encoder(Spawn::write).decoder(Spawn::new).consumerMainThread(Spawn::handle).add();
        CH.messageBuilder(Action.class, id++, NetworkDirection.PLAY_TO_SERVER).encoder(Action::write).decoder(Action::new).consumerMainThread(Action::handle).add();
        CH.messageBuilder(OpenEditor.class, id++, NetworkDirection.PLAY_TO_CLIENT).encoder(OpenEditor::write).decoder(OpenEditor::new).consumerMainThread(OpenEditor::handle).add();
        CH.messageBuilder(PlayClip.class, id++, NetworkDirection.PLAY_TO_CLIENT).encoder(PlayClip::write).decoder(PlayClip::new).consumerMainThread(PlayClip::handle).add();
        ScreenNet.register(id);
    }

    public static void openEditor(ServerPlayer p, StudioNpc n) {
        CH.send(PacketDistributor.PLAYER.with(() -> p), new OpenEditor(n.getId(), n.profile().toJson()));
    }

    public static void sendPlayClip(ServerPlayer p, String name) {
        CH.send(PacketDistributor.PLAYER.with(() -> p), new PlayClip(name));
    }

    private static StudioNpc npc(ServerPlayer p, int id) {
        Entity e = p.level().getEntity(id);
        return e instanceof StudioNpc n ? n : null;
    }

    private static ServerPlayer sender(Supplier<NetworkEvent.Context> c) {
        ServerPlayer p = c.get().getSender();
        if (p == null || !canEdit(p)) {
            if (p != null) p.displayClientMessage(Component.translatable("fpstudio.msg.npc_no_permission"), true);
            return null;
        }
        return p;
    }

    // ------------------------------------------------------------------ C2S

    public static final class SaveProfile {
        final int id;
        final String json;

        public SaveProfile(int id, String json) { this.id = id; this.json = json; }

        SaveProfile(FriendlyByteBuf b) { id = b.readVarInt(); json = b.readUtf(MAX); }

        void write(FriendlyByteBuf b) { b.writeVarInt(id); b.writeUtf(json, MAX); }

        void handle(Supplier<NetworkEvent.Context> c) {
            ServerPlayer p = sender(c);
            if (p == null) return;
            StudioNpc n = npc(p, id);
            if (n == null) return;
            NpcProfile np = NpcProfile.fromJson(json);
            np.vars = n.profile().vars;          // runtime variables belong to the world, not to the editor
            n.setProfile(np);
        }
    }

    public static final class Spawn {
        final String json;
        final double x, y, z;
        final float yaw;

        public Spawn(String json, double x, double y, double z, float yaw) { this.json = json; this.x = x; this.y = y; this.z = z; this.yaw = yaw; }

        Spawn(FriendlyByteBuf b) { json = b.readUtf(MAX); x = b.readDouble(); y = b.readDouble(); z = b.readDouble(); yaw = b.readFloat(); }

        void write(FriendlyByteBuf b) { b.writeUtf(json, MAX); b.writeDouble(x); b.writeDouble(y); b.writeDouble(z); b.writeFloat(yaw); }

        void handle(Supplier<NetworkEvent.Context> c) {
            ServerPlayer p = sender(c);
            if (p == null) return;
            spawn(p, NpcProfile.fromJson(json), x, y, z, yaw);
        }
    }

    public static StudioNpc spawn(ServerPlayer p, NpcProfile prof, double x, double y, double z, float yaw) {
        StudioNpc n = ModEntities.NPC.get().create(p.serverLevel());
        if (n == null) return null;
        n.moveTo(x, y, z, yaw, 0f);
        n.setYHeadRot(yaw);
        n.setYBodyRot(yaw);
        n.setProfile(prof);
        p.serverLevel().addFreshEntity(n);
        return n;
    }

    public static final class Action {
        final int id, type;
        final String arg;

        public Action(int id, int type, String arg) { this.id = id; this.type = type; this.arg = arg == null ? "" : arg; }

        Action(FriendlyByteBuf b) { id = b.readVarInt(); type = b.readVarInt(); arg = b.readUtf(4096); }

        void write(FriendlyByteBuf b) { b.writeVarInt(id); b.writeVarInt(type); b.writeUtf(arg, 4096); }

        void handle(Supplier<NetworkEvent.Context> c) {
            ServerPlayer p = sender(c);
            if (p == null) return;
            StudioNpc n = npc(p, id);
            if (n == null) return;
            switch (type) {
                case A_REMOVE -> n.discard();
                case A_TP_HERE -> { n.getNavigation().stop(); n.teleportTo(p.getX(), p.getY(), p.getZ()); }
                case A_ANIM -> {
                    int i = arg.lastIndexOf('|');
                    if (i < 0) n.playAnim(arg, false); else n.playAnim(arg.substring(0, i), arg.substring(i + 1).equals("1"));
                }
                case A_RUN -> { n.stopScript(); n.runScript(arg, p); }
                case A_STOP -> { n.stopScript(); n.stopAnim(); n.startFollow(null, 3, false); n.lookOverride = null; }
                case A_EDIT -> openEditor(p, n);
                case A_PUSH_HOME -> { var pr = n.profile(); n.setProfile(pr); }
                default -> { }
            }
        }
    }

    // ------------------------------------------------------------------ S2C

    public static final class OpenEditor {
        final int id;
        final String json;

        public OpenEditor(int id, String json) { this.id = id; this.json = json; }

        OpenEditor(FriendlyByteBuf b) { id = b.readVarInt(); json = b.readUtf(MAX); }

        void write(FriendlyByteBuf b) { b.writeVarInt(id); b.writeUtf(json, MAX); }

        void handle(Supplier<NetworkEvent.Context> c) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.fpstudio.client.ClientNet.openEditor(id, json));
        }
    }

    public static final class PlayClip {
        final String name;

        public PlayClip(String name) { this.name = name; }

        PlayClip(FriendlyByteBuf b) { name = b.readUtf(256); }

        void write(FriendlyByteBuf b) { b.writeUtf(name, 256); }

        void handle(Supplier<NetworkEvent.Context> c) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.fpstudio.client.ClientNet.playClip(name));
        }
    }
}
