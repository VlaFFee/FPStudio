package com.fpstudio.world;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.RegisterCommandsEvent;

/** /fpnpc spawn|edit|run|stop|remove|list - server side helpers (also usable from command blocks, except edit). */
public final class NpcCommands {
    private NpcCommands() {}

    public static void register(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("fpnpc").requires(s -> s.hasPermission(2))
                .then(Commands.literal("spawn")
                        .then(Commands.argument("model", StringArgumentType.word())
                                .executes(c -> spawn(c.getSource(), StringArgumentType.getString(c, "model"), "NPC"))
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(c -> spawn(c.getSource(), StringArgumentType.getString(c, "model"), StringArgumentType.getString(c, "name"))))))
                .then(Commands.literal("edit")
                        .then(Commands.argument("npc", EntityArgument.entity()).executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            Entity en = EntityArgument.getEntity(c, "npc");
                            if (!(en instanceof StudioNpc n)) { c.getSource().sendFailure(Component.translatable("fpstudio.msg.npc_not_npc")); return 0; }
                            NpcNet.openEditor(p, n);
                            return 1;
                        })))
                .then(Commands.literal("run")
                        .then(Commands.argument("npc", EntityArgument.entity())
                                .then(Commands.argument("script", StringArgumentType.word()).executes(c -> {
                                    Entity en = EntityArgument.getEntity(c, "npc");
                                    if (!(en instanceof StudioNpc n)) return 0;
                                    n.stopScript();
                                    net.minecraft.world.entity.player.Player pl = c.getSource().getEntity() instanceof net.minecraft.world.entity.player.Player p ? p
                                            : n.level().getNearestPlayer(n, 16);
                                    return n.runScript(StringArgumentType.getString(c, "script"), pl) ? 1 : 0;
                                }))))
                .then(Commands.literal("stop")
                        .then(Commands.argument("npc", EntityArgument.entity()).executes(c -> {
                            if (EntityArgument.getEntity(c, "npc") instanceof StudioNpc n) { n.stopScript(); n.stopAnim(); n.lookOverride = null; return 1; }
                            return 0;
                        })))
                .then(Commands.literal("remove")
                        .then(Commands.argument("npc", EntityArgument.entity()).executes(c -> {
                            Entity en = EntityArgument.getEntity(c, "npc");
                            if (!(en instanceof StudioNpc)) return 0;
                            en.discard();
                            return 1;
                        })))
                .then(Commands.literal("list").executes(c -> {
                    int n = 0;
                    for (var lvl : c.getSource().getServer().getAllLevels())
                        for (Entity en : lvl.getAllEntities())
                            if (en instanceof StudioNpc s) {
                                n++;
                                c.getSource().sendSuccess(() -> Component.literal(StudioNpc.describe(s) + " @ " + (int) s.getX() + " " + (int) s.getY() + " " + (int) s.getZ()), false);
                            }
                    return n;
                }))
                .then(Commands.literal("var")
                        .then(Commands.argument("key", StringArgumentType.word())
                                .then(Commands.argument("value", StringArgumentType.greedyString()).executes(c -> {
                                    NpcWorldData.get(c.getSource().getServer()).set("g." + StringArgumentType.getString(c, "key"), StringArgumentType.getString(c, "value"));
                                    return 1;
                                })))));
    }

    private static int spawn(CommandSourceStack src, String model, String name) {
        try {
            ServerPlayer p = src.getPlayerOrException();
            NpcProfile pr = new NpcProfile();
            pr.asset = model;
            pr.name = name;
            NpcNet.spawn(p, pr, p.getX(), p.getY(), p.getZ(), p.getYRot() + 180f);
            return 1;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException ex) {
            return 0;
        }
    }
}
