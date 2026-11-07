package com.tlmjei.network;

import com.tlmjei.jei.TransferAvailabilityCache;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server -> client reply to {@link CPacketAltarAvailability}. Only the recipe key is echoed
 * back; {@code missingGroups} holds the indexes of ingredient groups that cannot be collected.
 */
public class SPacketAltarAvailability {

    private static final int MAX_SLOTS = 16;

    private final int recipeKey;
    private final int requestId;
    private final List<Integer> missingGroups;

    public SPacketAltarAvailability(int recipeKey, int requestId, List<Integer> missingGroups) {
        this.recipeKey = recipeKey;
        this.requestId = requestId;
        this.missingGroups = missingGroups;
    }

    public static void encode(SPacketAltarAvailability packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.recipeKey);
        buf.writeVarInt(packet.requestId);

        int count = Math.min(packet.missingGroups.size(), MAX_SLOTS);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buf.writeVarInt(packet.missingGroups.get(i));
        }
    }

    public static SPacketAltarAvailability decode(FriendlyByteBuf buf) {
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

    public static void handle(SPacketAltarAvailability packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                TransferAvailabilityCache.accept(packet.recipeKey, packet.requestId, packet.missingGroups));
        ctx.get().setPacketHandled(true);
    }
}
