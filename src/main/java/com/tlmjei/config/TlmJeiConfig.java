package com.tlmjei.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import org.apache.commons.lang3.tuple.Pair;

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
public class TlmJeiConfig {

    /** Range in blocks to search for a nearby altar. */
    public static double altarSearchRange = 8.0;

    /** Range in blocks to search nearby containers for materials (0 = disabled). */
    public static double containerSearchRange = 32.0;

    // ---- Forge config spec ----

    private static final ForgeConfigSpec SPEC;
    private static final Config CONFIG;

    static {
        Pair<Config, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(Config::new);
        SPEC = pair.getRight();
        CONFIG = pair.getLeft();
    }

    public static ForgeConfigSpec spec() {
        return SPEC;
    }

    @SubscribeEvent
    public static void onLoad(ModConfigEvent.Loading event) {
        apply();
    }

    @SubscribeEvent
    public static void onReload(ModConfigEvent.Reloading event) {
        apply();
    }

    private static void apply() {
        altarSearchRange = CONFIG.altarRange.get();
        containerSearchRange = CONFIG.containerRange.get();
    }

    // ---- internal config class ----

    private static class Config {
        private final ForgeConfigSpec.DoubleValue altarRange;
        private final ForgeConfigSpec.DoubleValue containerRange;

        Config(ForgeConfigSpec.Builder builder) {
            builder.push("altar");
            altarRange = builder
                    .comment("Radius (in blocks) to search for a nearby Touhou Little Maid altar.")
                    .defineInRange("searchRange", 8.0, 1.0, 64.0);
            builder.pop();

            builder.push("containers");
            containerRange = builder
                    .comment("Radius (in blocks) to search nearby chests/containers for materials. Set to 0 to disable.")
                    .defineInRange("searchRange", 32.0, 0.0, 64.0);
            builder.pop();
        }
    }
}
