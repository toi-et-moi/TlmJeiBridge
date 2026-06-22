package com.tlmjei.network;

import com.tlmjei.util.AltarHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class CPacketAltarTransfer {
    private final List<ItemStack> items;

    public CPacketAltarTransfer(List<ItemStack> items) {
        this.items = items;
    }

    public static void encode(CPacketAltarTransfer packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.items.size());
        for (ItemStack stack : packet.items) {
            buf.writeItem(stack);
        }
    }

    public static CPacketAltarTransfer decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            items.add(buf.readItem());
        }
        return new CPacketAltarTransfer(items);
    }

    public static void handle(CPacketAltarTransfer packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            AltarHelper.placeItemsOnNearestAltar(player, packet.items);
        });
        ctx.get().setPacketHandled(true);
    }
}
