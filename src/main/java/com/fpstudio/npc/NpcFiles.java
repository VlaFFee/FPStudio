package com.fpstudio.npc;

import java.nio.file.Path;

/** Where the NPC library lives. Pure Java so the server side can use it too. */
public final class NpcFiles {
    private static Path dir = Path.of("fpstudio", "npcs");

    private NpcFiles() {}

    public static void setDir(Path p) { dir = p; }

    public static Path dir() { return dir; }
}
