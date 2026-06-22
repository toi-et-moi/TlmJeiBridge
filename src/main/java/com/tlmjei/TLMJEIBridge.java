package com.tlmjei;

import com.tlmjei.config.TlmJeiConfig;
import com.tlmjei.network.NetworkHandler;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.bus.api.IEventBus;

@Mod(TLMJEIBridge.MODID)
public class TLMJEIBridge {
    public static final String MODID = "tlmjei";

    public TLMJEIBridge(IEventBus modBus) {
        // Register config
        ModList.get().getModContainerById(MODID).ifPresent(mc ->
                mc.registerConfig(ModConfig.Type.COMMON, TlmJeiConfig.spec()));

        modBus.addListener(this::commonSetup);
        modBus.register(TlmJeiConfig.class);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        NetworkHandler.register();
    }
}
