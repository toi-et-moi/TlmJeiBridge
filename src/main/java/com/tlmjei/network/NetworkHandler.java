package com.tlmjei.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class NetworkHandler {
    private static final String PROTOCOL = "3";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("tlmjei", "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    public static void register() {
        CHANNEL.registerMessage(0, CPacketAltarTransfer.class,
                CPacketAltarTransfer::encode,
                CPacketAltarTransfer::decode,
                CPacketAltarTransfer::handle);
        CHANNEL.registerMessage(1, CPacketAltarAvailability.class,
                CPacketAltarAvailability::encode,
                CPacketAltarAvailability::decode,
                CPacketAltarAvailability::handle);
        CHANNEL.registerMessage(2, SPacketAltarAvailability.class,
                SPacketAltarAvailability::encode,
                SPacketAltarAvailability::decode,
                SPacketAltarAvailability::handle);
        CHANNEL.registerMessage(3, CPacketAltarAvailabilityBatch.class,
                CPacketAltarAvailabilityBatch::encode,
                CPacketAltarAvailabilityBatch::decode,
                CPacketAltarAvailabilityBatch::handle);
    }
}
