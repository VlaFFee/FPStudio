package com.fpstudio.client.gui;

import com.fpstudio.client.Keys;
import com.fpstudio.client.Playback;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Invisible screen shown while a clip plays with input frozen. An open screen takes the mouse away from the camera and
 * swallows every key and click, so the player cannot move, look, attack, use items, open the inventory or chat.
 * The clip itself keeps full control (scripted walking, turning and world actions still work).
 * ESC or the "play" key ends the clip.
 */
public class FreezeScreen extends Screen {
    public FreezeScreen() { super(Component.empty()); }

    @Override
    protected void init() {
        Minecraft mc = Minecraft.getInstance();
        // screens normally show the cursor; hide it so the cinematic stays clean
        InputConstants.grabOrReleaseMouse(mc.getWindow().getWindow(), GLFW.GLFW_CURSOR_HIDDEN,
                mc.mouseHandler.xpos(), mc.mouseHandler.ypos());
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public boolean shouldCloseOnEsc() { return false; }

    @Override
    public void renderBackground(GuiGraphics g) { }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float pt) { }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE || Keys.PLAY.matches(key, scan)) Playback.stop(true);
        return true;
    }

    @Override
    public boolean keyReleased(int key, int scan, int mods) { return true; }

    @Override
    public boolean charTyped(char c, int mods) { return true; }

    @Override
    public boolean mouseClicked(double x, double y, int button) { return true; }

    @Override
    public boolean mouseReleased(double x, double y, int button) { return true; }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) { return true; }
}
