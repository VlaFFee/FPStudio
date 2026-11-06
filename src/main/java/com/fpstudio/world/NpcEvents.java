package com.fpstudio.world;

import net.minecraft.world.entity.player.Player;
import net.minecraftforge.eventbus.api.Event;

/**
 * Hooks for other mods (for example your dialogue mod). Post on MinecraftForge.EVENT_BUS by the `dialog` and `ext` script commands.
 * Subscribe to them on the server side.
 */
public final class NpcEvents {
    private NpcEvents() {}

    /** Script line: `dialog <id>`. */
    public static class Dialogue extends Event {
        public final StudioNpc npc;
        public final Player player;   // may be null (scripts started without a player)
        public final String id;

        public Dialogue(StudioNpc npc, Player player, String id) {
            this.npc = npc;
            this.player = player;
            this.id = id;
        }
    }

    /** Script line: `ext <name> <args...>` - any custom command another mod wants to handle. */
    public static class Custom extends Event {
        public final StudioNpc npc;
        public final Player player;
        public final String name, args;

        public Custom(StudioNpc npc, Player player, String name, String args) {
            this.npc = npc;
            this.player = player;
            this.name = name;
            this.args = args;
        }
    }
}
