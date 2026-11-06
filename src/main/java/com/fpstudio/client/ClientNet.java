package com.fpstudio.client;

import com.fpstudio.client.gui.NpcEditorScreen;
import com.fpstudio.model.ClipIO;
import com.fpstudio.world.NpcProfile;
import net.minecraft.client.Minecraft;

/** Client side handlers of the server -> client packets. Only referenced through DistExecutor. */
public final class ClientNet {
    private ClientNet() {}

    public static void openEditor(int entityId, String json) {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new NpcEditorScreen(entityId, NpcProfile.fromJson(json)));
    }

    public static void playClip(String name) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        try {
            Playback.clip = ClipIO.load(name);
            Playback.returnToStudio = false;
            Playback.play(0f, true, true);
        } catch (Exception ex) {
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.translatable("fpstudio.msg.load_failed", name), false);
        }
    }
}
