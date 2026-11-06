package com.fpstudio.client.npc;

import com.fpstudio.model.Actor;
import com.fpstudio.model.Clip;
import com.fpstudio.model.EventKey;
import com.fpstudio.model.EventType;
import com.fpstudio.npc.NpcAsset;
import com.fpstudio.npc.NpcInstance;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Per-frame state of all actors of the playing / edited clip. Everything here is allocation-free in steady state. */
public final class NpcRuntime {
    private static final Map<Actor, NpcInstance> inst = new IdentityHashMap<>();
    private static final List<NpcInstance> list = new ArrayList<>();
    private static final float[] tmp = new float[3];

    /** camera look-at: weight 0..1 and the world target */
    /** bone the studio is editing right now (tinted in the world so you can see which part it is) */
    public static String hlActor = "", hlBone = "";

    public static float lookW;
    public static final float[] lookPos = new float[3];

    private NpcRuntime() {}

    public static void clear() {
        if (!inst.isEmpty()) { inst.clear(); list.clear(); }
        lookW = 0f;
    }

    public static Collection<NpcInstance> all() { return list; }

    public static NpcInstance get(Actor a) { return inst.get(a); }

    public static void update(Clip clip, float time) {
        if (clip.actors.isEmpty()) { clear(); return; }
        boolean changed = false;
        if (inst.size() > clip.actors.size() || !list.stream().allMatch(i -> clip.actors.contains(i.actor))) {
            inst.keySet().removeIf(a -> !clip.actors.contains(a));
            changed = true;
        }
        for (Actor a : clip.actors) {
            NpcAsset as = NpcLibrary.get(a.asset);
            if (as == null) {
                if (inst.remove(a) != null) changed = true;
                continue;
            }
            NpcInstance i = inst.get(a);
            if (i == null || i.asset != as) {
                i = new NpcInstance(a, as);
                inst.put(a, i);
                changed = true;
            }
        }
        if (changed || list.size() != inst.size()) {
            list.clear();
            for (Actor a : clip.actors) { NpcInstance i = inst.get(a); if (i != null) list.add(i); }
        }
        for (NpcInstance i : list) i.update(clip, time);
        updateLook(clip, time);
    }

    private static void updateLook(Clip clip, float time) {
        lookW = 0f;
        for (EventKey e : clip.events) {
            if (e.type != EventType.CAM_LOOK || e.time > time) continue;
            float end = e.dur > 0f ? e.time + e.dur : Float.MAX_VALUE;
            if (time >= end) continue;
            Actor a = clip.actor(NpcInstance.idOf(e.arg));
            NpcInstance ni = a == null ? null : inst.get(a);
            if (ni == null || !ni.visible) continue;
            String bone = NpcInstance.animOf(e.arg);
            ni.boneWorld(bone.isEmpty() ? -1 : ni.asset.model.bone(bone), tmp);
            float in = smooth((time - e.time) / 0.35f), out = e.dur > 0f ? smooth((end - time) / 0.35f) : 1f;
            lookW = Math.min(in, out);
            lookPos[0] = tmp[0]; lookPos[1] = tmp[1]; lookPos[2] = tmp[2];
        }
    }

    private static float smooth(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }
}
