package com.fpstudio.world;

import com.fpstudio.FPStudio;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> TYPES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, FPStudio.MODID);

    public static final RegistryObject<EntityType<StudioNpc>> NPC = TYPES.register("npc", () ->
            EntityType.Builder.<StudioNpc>of(StudioNpc::new, MobCategory.MISC)
                    .sized(0.6f, 1.8f)
                    .clientTrackingRange(10)
                    .updateInterval(2)
                    .build(FPStudio.MODID + ":npc"));

    private ModEntities() {}
}
