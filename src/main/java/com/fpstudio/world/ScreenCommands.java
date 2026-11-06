package com.fpstudio.world;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;

import java.util.Collection;

/**
 * /fpscreen show &lt;targets&gt; &lt;preset&gt; &lt;seconds&gt; &lt;text&gt;   (seconds -1 = preset time)
 * /fpscreen clear &lt;targets&gt; [preset]     /fpscreen edit [preset]     /fpscreen list
 * Works in command blocks. Text supports &amp;c colour codes and the chat markup (&lt;shake&gt;, &lt;gif=name&gt;...).
 */
public final class ScreenCommands {
    private ScreenCommands() {}

    public static void register(RegisterCommandsEvent e) {
        var presets = (com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack>) (c, b) -> SharedSuggestionProvider.suggest(ScreenPresets.all().keySet(), b);
        e.getDispatcher().register(Commands.literal("fpscreen").requires(s -> s.hasPermission(2))
                .then(Commands.literal("show")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("preset", StringArgumentType.word()).suggests(presets)
                                        .then(Commands.argument("seconds", FloatArgumentType.floatArg(-1f, 600f))
                                                .then(Commands.argument("text", StringArgumentType.greedyString()).executes(c -> {
                                                    Collection<ServerPlayer> ps = EntityArgument.getPlayers(c, "targets");
                                                    String preset = StringArgumentType.getString(c, "preset");
                                                    float sec = FloatArgumentType.getFloat(c, "seconds");
                                                    String text = ScreenNet.colors(StringArgumentType.getString(c, "text"));
                                                    for (ServerPlayer p : ps) ScreenNet.show(p, preset, sec, text);
                                                    return ps.size();
                                                }))))))
                .then(Commands.literal("clear")
                        .then(Commands.argument("targets", EntityArgument.players()).executes(c -> {
                            Collection<ServerPlayer> ps = EntityArgument.getPlayers(c, "targets");
                            for (ServerPlayer p : ps) ScreenNet.clear(p, "");
                            return ps.size();
                        }).then(Commands.argument("preset", StringArgumentType.word()).suggests(presets).executes(c -> {
                            Collection<ServerPlayer> ps = EntityArgument.getPlayers(c, "targets");
                            for (ServerPlayer p : ps) ScreenNet.clear(p, StringArgumentType.getString(c, "preset"));
                            return ps.size();
                        }))))
                .then(Commands.literal("edit").executes(c -> {
                    ScreenNet.openEditor(c.getSource().getPlayerOrException(), "default");
                    return 1;
                }).then(Commands.argument("preset", StringArgumentType.word()).suggests(presets).executes(c -> {
                    ScreenNet.openEditor(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "preset"));
                    return 1;
                })))
                .then(Commands.literal("list").executes(c -> {
                    c.getSource().sendSuccess(() -> Component.literal("Screen presets: " + String.join(", ", ScreenPresets.all().keySet())), false);
                    return 1;
                })));
    }
}
