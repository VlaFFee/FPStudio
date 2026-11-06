package com.fpstudio.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;

/** World-wide script variables (names starting with "g." in scripts). */
public final class NpcWorldData extends SavedData {
    private static final String NAME = "fpstudio_npc";
    public final Map<String, String> vars = new LinkedHashMap<>();

    public static NpcWorldData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(NpcWorldData::load, NpcWorldData::new, NAME);
    }

    public static NpcWorldData load(CompoundTag tag) {
        NpcWorldData d = new NpcWorldData();
        CompoundTag v = tag.getCompound("vars");
        for (String k : v.getAllKeys()) d.vars.put(k, v.getString(k));
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag v = new CompoundTag();
        for (Map.Entry<String, String> e : vars.entrySet()) v.putString(e.getKey(), e.getValue());
        tag.put("vars", v);
        return tag;
    }

    public void set(String k, String val) {
        vars.put(k, val);
        setDirty();
    }
}
