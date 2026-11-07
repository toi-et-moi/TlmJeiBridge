package com.tlmjei.network;

import com.tlmjei.util.AltarHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Client -> server availability query used for the JEI "+" button state.
 *
 * <p>Carries the same ingredient groups as the real transfer request. The server only checks
 * whether those groups can be collected from the player's inventory, backpacks, Curios and
 * nearby containers; nothing is moved.</p>
 */
public record CPacketAltarAvailability(int requestId, int recipeKey, List<List<ItemStack>> groups)
        implements CustomPacketPayload {

    /** Safety caps so a malformed packet cannot allocate huge lists. */
    private static final int MAX_SLOTS = 16;
    private static final int MAX_CANDIDATES_PER_SLOT = 128;

    public static final CustomPacketPayload.Type<CPacketAltarAvailability> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.parse("tlmjei:altar_availability"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CPacketAltarAvailability> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public CPacketAltarAvailability decode(RegistryFriendlyByteBuf buf) {
                    int requestId = buf.readVarInt();
                    int recipeKey = buf.readVarInt();

                    int groupCount = buf.readVarInt();
                    List<List<ItemStack>> groups = new ArrayList<>(Math.min(groupCount, MAX_SLOTS));
                    for (int i = 0; i < groupCount; i++) {
                        int candidateCount = buf.readVarInt();
                        List<ItemStack> candidates = new ArrayList<>(
                                Math.min(candidateCount, MAX_CANDIDATES_PER_SLOT));
                        for (int j = 0; j < candidateCount; j++) {
                            ItemStack stack = ItemStack.STREAM_CODEC.decode(buf);
                            if (j < MAX_CANDIDATES_PER_SLOT) {
                                candidates.add(stack);
                            }
                        }
                        if (i < MAX_SLOTS) {
                            groups.add(candidates);
                        }
                    }
                    return new CPacketAltarAvailability(requestId, recipeKey, groups);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, CPacketAltarAvailability packet) {
                    buf.writeVarInt(packet.requestId);
                    buf.writeVarInt(packet.recipeKey);

                    int groupCount = Math.min(packet.groups.size(), MAX_SLOTS);
                    buf.writeVarInt(groupCount);
                    for (int i = 0; i < groupCount; i++) {
                        List<ItemStack> group = packet.groups.get(i);
                        int candidateCount = Math.min(group.size(), MAX_CANDIDATES_PER_SLOT);
                        buf.writeVarInt(candidateCount);
                        for (int j = 0; j < candidateCount; j++) {
                            ItemStack stack = group.get(j);
                            ItemStack.STREAM_CODEC.encode(
                                    buf, stack == null ? ItemStack.EMPTY : stack);
                        }
                    }
                }
            };

    public static void handle(CPacketAltarAvailability packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                List<Integer> missing =
                        AltarHelper.findMissingIngredientGroups(player, packet.groups);
                PacketDistributor.sendToPlayer(player,
                        new SPacketAltarAvailability(packet.recipeKey, packet.requestId, missing));
            }
        });
    }

    @Override
    public CustomPacketPayload.Type<CPacketAltarAvailability> type() {
        return TYPE;
    }
}
