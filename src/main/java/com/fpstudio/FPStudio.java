package com.fpstudio;

import com.fpstudio.model.ClipIO;
import com.fpstudio.world.ModEntities;
import com.fpstudio.world.NpcCommands;
import com.fpstudio.world.NpcNet;
import com.fpstudio.world.StudioNpc;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

@Mod(FPStudio.MODID)
public class FPStudio {
    public static final String MODID = "fpstudio";
    public static final Logger LOGGER = LogUtils.getLogger();

    public FPStudio() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, Config.SPEC);
        ClipIO.setDir(FMLPaths.CONFIGDIR.get().resolve("fpstudio").resolve("clips"));
        com.fpstudio.npc.NpcFiles.setDir(FMLPaths.CONFIGDIR.get().resolve("fpstudio").resolve("npcs"));
        com.fpstudio.chat.ChatCfg.setDir(FMLPaths.CONFIGDIR.get().resolve("fpstudio"));
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ModEntities.TYPES.register(bus);
        bus.addListener((FMLCommonSetupEvent e) -> e.enqueueWork(NpcNet::register));
        bus.addListener((EntityAttributeCreationEvent e) -> e.put(ModEntities.NPC.get(), StudioNpc.createAttributes().build()));
        MinecraftForge.EVENT_BUS.addListener(NpcCommands::register);
        MinecraftForge.EVENT_BUS.addListener(com.fpstudio.world.ScreenCommands::register);
    }
}
