package com.fpstudio;

import net.minecraftforge.common.ForgeConfigSpec;

public final class Config {
    private static final ForgeConfigSpec.Builder B = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue REQUIRE_CREATIVE = B
            .comment("Studio and playback only work in creative mode.")
            .define("requireCreative", true);

    public static final ForgeConfigSpec.BooleanValue FREEZE_INPUT = B
            .comment("Master switch for freezing player input (movement, mouse look, all keys and clicks) while a clip plays.",
                    "When false, clips never freeze input, whatever their own setting says.")
            .define("freezeInputDuringAnimation", true);

    public static final ForgeConfigSpec.BooleanValue STUDIO_FX = B
            .comment("Cinematic studio ambience: vignette, FOV push-in, letterbox bars.")
            .define("studioFx", true);

    public static final ForgeConfigSpec.DoubleValue UI_SPEED = B
            .comment("Speed multiplier of studio interface animations.")
            .defineInRange("uiAnimationSpeed", 1.0, 0.25, 3.0);

    public static final ForgeConfigSpec.DoubleValue UI_SCALE = B
            .comment("Studio interface size multiplier. It is applied on top of Minecraft's own GUI Scale (Options > Video Settings).")
            .defineInRange("uiScale", 0.75, 0.4, 1.6);

    public static final ForgeConfigSpec.BooleanValue FORCE_LOCK_INPUT = B
            .comment("Lock ALL player input (movement, mouse look, buttons, keys) during ANY fullscreen animation, regardless of the clip's own setting. Esc and the Play key always stop the animation.")
            .define("lockInputDuringAnimations", false);

    public static final ForgeConfigSpec.BooleanValue CHAT_TRIGGERS = B
            .comment("Let command blocks / functions start cutscenes: a system message (e.g. /tellraw @a {\"text\":\"[fpstudio:play intro]\"})",
                    "containing [fpstudio:play NAME] plays that clip and [fpstudio:stop] stops it. The message itself is hidden.")
            .define("chatTriggers", true);

    public static final ForgeConfigSpec SPEC = B.build();

    private Config() {}
}
