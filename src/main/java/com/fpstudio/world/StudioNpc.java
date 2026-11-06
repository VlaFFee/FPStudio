package com.fpstudio.world;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.UUID;

/**
 * A real world NPC: a mob that looks like a Blockbench/GeckoLib model on the client, walks, looks at players,
 * follows a route and runs scripts (see {@link NpcScript}). All behaviour lives on the server; clients only draw it.
 */
public class StudioNpc extends PathfinderMob {
    public static final EntityDataAccessor<String> D_ASSET = SynchedEntityData.defineId(StudioNpc.class, EntityDataSerializers.STRING);
    public static final EntityDataAccessor<String> D_NAME = SynchedEntityData.defineId(StudioNpc.class, EntityDataSerializers.STRING);
    public static final EntityDataAccessor<String> D_ANIMS = SynchedEntityData.defineId(StudioNpc.class, EntityDataSerializers.STRING);
    public static final EntityDataAccessor<String> D_FORCED = SynchedEntityData.defineId(StudioNpc.class, EntityDataSerializers.STRING);
    public static final EntityDataAccessor<Integer> D_FORCED_SEQ = SynchedEntityData.defineId(StudioNpc.class, EntityDataSerializers.INT);
    public static final EntityDataAccessor<Boolean> D_FORCED_LOOP = SynchedEntityData.defineId(StudioNpc.class, EntityDataSerializers.BOOLEAN);
    public static final EntityDataAccessor<Float> D_SCALE = SynchedEntityData.defineId(StudioNpc.class, EntityDataSerializers.FLOAT);
    public static final EntityDataAccessor<Float> D_W = SynchedEntityData.defineId(StudioNpc.class, EntityDataSerializers.FLOAT);
    public static final EntityDataAccessor<Float> D_H = SynchedEntityData.defineId(StudioNpc.class, EntityDataSerializers.FLOAT);
    public static final EntityDataAccessor<Float> D_ANIM_SPEED = SynchedEntityData.defineId(StudioNpc.class, EntityDataSerializers.FLOAT);
    public static final EntityDataAccessor<Boolean> D_TALKING = SynchedEntityData.defineId(StudioNpc.class, EntityDataSerializers.BOOLEAN);
    public static final EntityDataAccessor<Boolean> D_SITTING = SynchedEntityData.defineId(StudioNpc.class, EntityDataSerializers.BOOLEAN);
    public static final EntityDataAccessor<Boolean> D_RUNNING = SynchedEntityData.defineId(StudioNpc.class, EntityDataSerializers.BOOLEAN);

    public net.minecraft.world.phys.Vec3 lookOverride;
    private NpcProfile profile = new NpcProfile();
    private NpcScript script;
    private int talkTicks;
    private boolean pendingInteract;
    private UUID followTarget;
    private float followDist = 3f;
    private boolean followRun;
    private final java.util.Map<UUID, Integer> nearCooldown = new java.util.HashMap<>();
    private boolean spawnedRun;

    public StudioNpc(EntityType<? extends StudioNpc> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        setMaxUpStep(1.0f);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.FOLLOW_RANGE, 48.0);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(D_ASSET, "");
        entityData.define(D_NAME, "NPC");
        entityData.define(D_ANIMS, "||||");
        entityData.define(D_FORCED, "");
        entityData.define(D_FORCED_SEQ, 0);
        entityData.define(D_FORCED_LOOP, false);
        entityData.define(D_SCALE, 1f);
        entityData.define(D_W, 0.6f);
        entityData.define(D_H, 1.8f);
        entityData.define(D_ANIM_SPEED, 1f);
        entityData.define(D_TALKING, false);
        entityData.define(D_SITTING, false);
        entityData.define(D_RUNNING, false);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new NpcGoals.Follow(this));
        goalSelector.addGoal(2, new NpcGoals.Route(this));
        goalSelector.addGoal(3, new NpcGoals.Wander(this));
        goalSelector.addGoal(4, new NpcGoals.LookAtPlayer(this));
    }

    // ------------------------------------------------------------------ profile

    public NpcProfile profile() { return profile; }

    /** Replace the profile (server) and push everything the clients need into the synced data. */
    public void setProfile(NpcProfile p) {
        profile = p.fix();
        entityData.set(D_ASSET, profile.asset);
        entityData.set(D_NAME, profile.name);
        entityData.set(D_ANIMS, profile.animPack());
        entityData.set(D_SCALE, profile.scale);
        entityData.set(D_W, profile.width);
        entityData.set(D_H, profile.height);
        entityData.set(D_ANIM_SPEED, profile.walkSpeedAnim);
        setCustomName(Component.literal(profile.name));
        setCustomNameVisible(profile.showName);
        var attr = getAttribute(Attributes.MOVEMENT_SPEED);
        if (attr != null) attr.setBaseValue(Math.max(0.01, profile.speed));
        if (profile.wanderRadius > 0.5f) restrictTo(blockPosition(), Math.round(profile.wanderRadius)); else clearRestriction();
        equip(EquipmentSlot.MAINHAND, profile.mainHand);
        equip(EquipmentSlot.OFFHAND, profile.offHand);
        equip(EquipmentSlot.HEAD, profile.head);
        equip(EquipmentSlot.CHEST, profile.chest);
        equip(EquipmentSlot.LEGS, profile.legs);
        equip(EquipmentSlot.FEET, profile.feet);
        refreshDimensions();
    }

    private void equip(EquipmentSlot slot, String id) {
        ItemStack st = ItemStack.EMPTY;
        if (id != null && !id.isBlank()) {
            try {
                Item it = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id.trim()));
                if (it != null) st = new ItemStack(it);
            } catch (RuntimeException ignored) {
                // bad id: leave the slot empty
            }
        }
        setItemSlot(slot, st);
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        float s = entityData.get(D_SCALE);
        return EntityDimensions.scalable(entityData.get(D_W) * s, entityData.get(D_H) * s);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        if (key == D_SCALE || key == D_W || key == D_H) refreshDimensions();
        super.onSyncedDataUpdated(key);
    }

    @Override
    protected float getStandingEyeHeight(Pose pose, EntityDimensions dim) { return dim.height * 0.9f; }

    @Override
    public boolean isPushable() { return profile.pushable && !isSitting(); }

    @Override
    public boolean removeWhenFarAway(double d) { return false; }

    @Override
    public boolean isInvulnerableTo(DamageSource src) {
        if (super.isInvulnerableTo(src)) return true;
        return profile.invulnerable && !src.is(DamageTypeTags.BYPASSES_INVULNERABILITY);
    }

    // ------------------------------------------------------------------ state used by the goals / scripts

    public boolean isSitting() { return entityData.get(D_SITTING); }

    public void setSitting(boolean s) {
        entityData.set(D_SITTING, s);
        if (s) getNavigation().stop();
    }

    public boolean scriptBusy() { return script != null && script.running(); }

    public boolean frozen() { return isSitting(); }

    public void startFollow(Player p, float dist, boolean run) {
        followTarget = p == null ? null : p.getUUID();
        followDist = Math.max(1.5f, dist);
        followRun = run;
    }

    public Player followTarget() { return followTarget == null ? null : level().getPlayerByUUID(followTarget); }

    public float followDist() { return followDist; }

    public boolean followRun() { return followRun; }

    public void setRunning(boolean r) {
        if (entityData.get(D_RUNNING) != r) entityData.set(D_RUNNING, r);
    }

    public void playAnim(String name, boolean loop) {
        entityData.set(D_FORCED, name == null ? "" : name);
        entityData.set(D_FORCED_LOOP, loop);
        entityData.set(D_FORCED_SEQ, entityData.get(D_FORCED_SEQ) + 1);
    }

    public void stopAnim() { entityData.set(D_FORCED, ""); entityData.set(D_FORCED_SEQ, entityData.get(D_FORCED_SEQ) + 1); }

    public void talk(int ticks) { talkTicks = Math.max(talkTicks, ticks); }

    public void faceTowards(Vec3 p) {
        double dx = p.x - getX(), dz = p.z - getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        setYRot(yaw);
        setYHeadRot(yaw);
        setYBodyRot(yaw);
    }

    // ------------------------------------------------------------------ scripts

    /** Start a named script (on_interact, a custom name ...). Returns false if the NPC is busy or the script does not exist. */
    public boolean runScript(String name, Player ctx) {
        if (level().isClientSide) return false;
        String src = profile.scripts.get(name);
        if (src == null || src.isBlank()) return false;
        if (scriptBusy()) return false;
        script = new NpcScript(this, name, src, ctx == null ? null : ctx.getUUID());
        if (ctx != null && profile.faceOnTalk) faceTowards(ctx.position());
        return true;
    }

    public void stopScript() { script = null; getNavigation().stop(); }

    public void notifyInteract() { pendingInteract = true; }

    public boolean takeInteract() { boolean b = pendingInteract; pendingInteract = false; return b; }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (level().isClientSide) return InteractionResult.SUCCESS;
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (player.isShiftKeyDown() && NpcNet.canEdit(player)) {
            if (player instanceof ServerPlayer sp) NpcNet.openEditor(sp, this);
            return InteractionResult.CONSUME;
        }
        if (script != null && script.running()) notifyInteract();
        else runScript("on_interact", player);
        return InteractionResult.CONSUME;
    }

    @Override
    public boolean hurt(DamageSource src, float amount) {
        if (!level().isClientSide && src.getEntity() instanceof Player p) {
            if (!scriptBusy()) runScript("on_hurt", p);
        }
        return super.hurt(src, amount);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) return;
        if (lookOverride != null) getLookControl().setLookAt(lookOverride.x, lookOverride.y, lookOverride.z, 30f, 30f);
        if (script != null) {
            script.tick();
            if (!script.running()) script = null;
        }
        if (talkTicks > 0) {
            talkTicks--;
            if (talkTicks == 0) entityData.set(D_TALKING, false);
            else if (!entityData.get(D_TALKING)) entityData.set(D_TALKING, true);
        }
        if (tickCount == 5 && !spawnedRun) {
            spawnedRun = true;
            runScript("on_spawn", null);
        }
        if (tickCount % 10 == 0) checkNear();
    }

    private void checkNear() {
        if (!profile.scripts.containsKey("on_near")) return;
        nearCooldown.replaceAll((k, v) -> Math.max(0, v - 10));
        nearCooldown.values().removeIf(v -> v <= 0);
        if (scriptBusy()) return;
        Player p = level().getNearestPlayer(this, Math.max(1f, profile.nearRange));
        if (p == null || p.isSpectator() || nearCooldown.containsKey(p.getUUID())) return;
        if (runScript("on_near", p)) nearCooldown.put(p.getUUID(), Math.max(20, profile.nearCooldown));
    }

    // ------------------------------------------------------------------ persistence

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("FpProfile", profile.toJson());
        tag.putBoolean("FpSit", isSitting());
        tag.putBoolean("FpSpawned", spawnedRun);
        BlockPos h = getRestrictCenter();
        if (hasRestriction()) { tag.putInt("FpHx", h.getX()); tag.putInt("FpHy", h.getY()); tag.putInt("FpHz", h.getZ()); }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("FpProfile")) setProfile(NpcProfile.fromJson(tag.getString("FpProfile")));
        entityData.set(D_SITTING, tag.getBoolean("FpSit"));
        spawnedRun = tag.getBoolean("FpSpawned");
        if (tag.contains("FpHx") && profile.wanderRadius > 0.5f) restrictTo(new BlockPos(tag.getInt("FpHx"), tag.getInt("FpHy"), tag.getInt("FpHz")), Math.round(profile.wanderRadius));
    }

    public static String describe(StudioNpc n) { return n.profile.name + " (" + n.profile.asset + ") #" + n.getId(); }
}
