package com.tlmjei.network;

import com.tlmjei.TLMJEIBridge;
import com.tlmjei.util.AltarHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.List;

@EventBusSubscriber(bus = EventBusSubscriber.Bus.MOD)
public class NetworkHandler {

    /** Safety caps so a malformed packet cannot allocate huge lists. */
    private static final int MAX_SLOTS = 16;
    private static final int MAX_CANDIDATES_PER_SLOT = 128;

    public static void register() {
        // Registration is done via the event handler below
    }

    @SubscribeEvent
    public static void onPayloadRegister(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(TLMJEIBridge.MODID);
        registrar.playToServer(CPacketAltarTransfer.TYPE, CPacketAltarTransfer.STREAM_CODEC,
                NetworkHandler::handle);
    }

    private static void handle(CPacketAltarTransfer packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sp) {
                AltarHelper.placeItemsOnNearestAltar(sp, packet.inputs());
            }
        });
    }

    /**
     * Payload for the JEI "+" button. Every element of {@code inputs} represents one recipe slot
     * and holds all ingredients JEI considers valid for it, so tag based recipes (e.g. any planks)
     * can be satisfied too.
     */
    public record CPacketAltarTransfer(List<List<ItemStack>> inputs) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<CPacketAltarTransfer> TYPE =
                new CustomPacketPayload.Type<>(
                        ResourceLocation.parse("tlmjei:altar_transfer"));

        public static final StreamCodec<RegistryFriendlyByteBuf, CPacketAltarTransfer> STREAM_CODEC =
                new StreamCodec<>() {
                    @Override
                    public CPacketAltarTransfer decode(RegistryFriendlyByteBuf buf) {
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
                        return new CPacketAltarTransfer(groups);
                    }

                    @Override
                    public void encode(RegistryFriendlyByteBuf buf, CPacketAltarTransfer pkt) {
                        int groupCount = Math.min(pkt.inputs.size(), MAX_SLOTS);
                        buf.writeVarInt(groupCount);
                        for (int i = 0; i < groupCount; i++) {
                            List<ItemStack> group = pkt.inputs.get(i);
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

        @Override
        public CustomPacketPayload.Type<CPacketAltarTransfer> type() {
            return TYPE;
        }
    }
}
