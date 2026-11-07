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
 * JEI "+" button payload. Every element of {@code groups} represents one recipe slot and holds
 * all ingredients JEI considers valid for it, so tag based recipes (e.g. any planks) can be
 * satisfied too.
 */
public class CPacketAltarTransfer {

    /** Safety caps so a malformed packet cannot allocate huge lists. */
    private static final int MAX_SLOTS = 16;
    private static final int MAX_CANDIDATES_PER_SLOT = 128;

    private final List<List<ItemStack>> groups;

    public CPacketAltarTransfer(List<List<ItemStack>> groups) {
        this.groups = groups;
    }

    public static void encode(CPacketAltarTransfer packet, FriendlyByteBuf buf) {
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

    public static CPacketAltarTransfer decode(FriendlyByteBuf buf) {
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
        return new CPacketAltarTransfer(groups);
    }

    public static void handle(CPacketAltarTransfer packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            AltarHelper.placeItemsOnNearestAltar(player, packet.groups);
        });
        ctx.get().setPacketHandled(true);
    }
}
