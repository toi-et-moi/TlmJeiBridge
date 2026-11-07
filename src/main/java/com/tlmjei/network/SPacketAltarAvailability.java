package com.tlmjei.network;

import com.tlmjei.jei.TransferAvailabilityCache;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server -> client reply to {@link CPacketAltarAvailability}. Only the recipe key is echoed
 * back; {@code missingGroups} holds the indexes of ingredient groups that cannot be collected.
 */
public record SPacketAltarAvailability(int recipeKey, int requestId, List<Integer> missingGroups)
        implements CustomPacketPayload {

    private static final int MAX_SLOTS = 16;

    public static final CustomPacketPayload.Type<SPacketAltarAvailability> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.parse("tlmjei:altar_availability_reply"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SPacketAltarAvailability> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public SPacketAltarAvailability decode(RegistryFriendlyByteBuf buf) {
                    int recipeKey = buf.readVarInt();
                    int requestId = buf.readVarInt();

                    int count = buf.readVarInt();
                    List<Integer> missing = new ArrayList<>(Math.min(count, MAX_SLOTS));
                    for (int i = 0; i < count; i++) {
                        int group = buf.readVarInt();
                        if (i < MAX_SLOTS) {
                            missing.add(group);
                        }
                    }
                    return new SPacketAltarAvailability(recipeKey, requestId, missing);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, SPacketAltarAvailability packet) {
                    buf.writeVarInt(packet.recipeKey);
                    buf.writeVarInt(packet.requestId);

                    int count = Math.min(packet.missingGroups.size(), MAX_SLOTS);
                    buf.writeVarInt(count);
                    for (int i = 0; i < count; i++) {
                        buf.writeVarInt(packet.missingGroups.get(i));
                    }
                }
            };

    public static void handle(SPacketAltarAvailability packet, IPayloadContext context) {
        context.enqueueWork(() ->
                TransferAvailabilityCache.accept(
                        packet.recipeKey, packet.requestId, packet.missingGroups));
    }

    @Override
    public CustomPacketPayload.Type<SPacketAltarAvailability> type() {
        return TYPE;
    }
}
