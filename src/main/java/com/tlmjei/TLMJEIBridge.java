package com.tlmjei;

import com.tlmjei.config.TlmJeiConfig;
import com.tlmjei.network.NetworkHandler;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(TLMJEIBridge.MODID)
public class TLMJEIBridge {
    public static final String MODID = "tlmjei";

    public TLMJEIBridge() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, TlmJeiConfig.spec());

        var bus = FMLJavaModLoadingContext.get().getModEventBus();
        bus.addListener(this::commonSetup);
        bus.register(TlmJeiConfig.class);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        NetworkHandler.register();
    }
}
