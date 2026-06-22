package com.tlmjei.config;

import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import org.apache.commons.lang3.tuple.Pair;

@EventBusSubscriber(bus = EventBusSubscriber.Bus.MOD)
public class TlmJeiConfig {

    public static double altarSearchRange = 8.0;
    public static double containerSearchRange = 32.0;

    private static final ModConfigSpec SPEC;
    private static final Config CONFIG;

    static {
        Pair<Config, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(Config::new);
        SPEC = pair.getRight();
        CONFIG = pair.getLeft();
    }

    public static ModConfigSpec spec() {
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

    private static class Config {
        private final ModConfigSpec.DoubleValue altarRange;
        private final ModConfigSpec.DoubleValue containerRange;

        Config(ModConfigSpec.Builder builder) {
            builder.push("altar");
            altarRange = builder
                    .comment("Radius (in blocks) to search for a nearby Touhou Little Maid altar.")
                    .defineInRange("searchRange", 8.0, 1.0, 64.0);
            builder.pop();

            builder.push("containers");
            containerRange = builder
                    .comment("Radius (in blocks) to search nearby chests/containers. Set to 0 to disable.")
                    .defineInRange("searchRange", 32.0, 0.0, 64.0);
            builder.pop();
        }
    }
}
