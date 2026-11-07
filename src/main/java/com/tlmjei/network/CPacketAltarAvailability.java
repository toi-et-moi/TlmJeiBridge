package com.tlmjei.network;

import com.tlmjei.util.AltarHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Client -> server availability query used for the JEI "+" button state.
 *
 * <p>Carries the same ingredient groups as the real transfer request. The server only checks
 * whether those groups can be collected from the player's inventory, backpacks, Curios and
 * nearby containers; nothing is moved.</p>
 */
public class CPacketAltarAvailability {

    /** Safety caps so a malformed packet cannot allocate huge lists. */
    private static final int MAX_SLOTS = 16;
    private static final int MAX_CANDIDATES_PER_SLOT = 128;

    private final int requestId;
    private final int recipeKey;
    private final List<List<ItemStack>> groups;

    public CPacketAltarAvailability(int requestId, int recipeKey, List<List<ItemStack>> groups) {
        this.requestId = requestId;
        this.recipeKey = recipeKey;
        this.groups = groups;
    }

    public static void encode(CPacketAltarAvailability packet, FriendlyByteBuf buf) {
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
                buf.writeItem(stack == null ? ItemStack.EMPTY : stack);
            }
        }
    }

    public static CPacketAltarAvailability decode(FriendlyByteBuf buf) {
        int requestId = buf.readVarInt();
        int recipeKey = buf.readVarInt();

        int groupCount = buf.readVarInt();
        List<List<ItemStack>> groups = new ArrayList<>(Math.min(groupCount, MAX_SLOTS));
        for (int i = 0; i < groupCount; i++) {
            int candidateCount = buf.readVarInt();
            List<ItemStack> candidates = new ArrayList<>(
                    Math.min(candidateCount, MAX_CANDIDATES_PER_SLOT));
            for (int j = 0; j < candidateCount; j++) {
                ItemStack stack = buf.readItem();
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

    public static void handle(CPacketAltarAvailability packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            List<Integer> missing = AltarHelper.findMissingIngredientGroups(player, packet.groups);
            NetworkHandler.CHANNEL.reply(
                    new SPacketAltarAvailability(packet.recipeKey, packet.requestId, missing),
                    ctx.get());
        });
        ctx.get().setPacketHandled(true);
    }
}
