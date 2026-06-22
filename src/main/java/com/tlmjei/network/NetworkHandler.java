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

    public record CPacketAltarTransfer(List<ItemStack> inputs) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<CPacketAltarTransfer> TYPE =
                new CustomPacketPayload.Type<>(
                        ResourceLocation.parse("tlmjei:altar_transfer"));

        public static final StreamCodec<RegistryFriendlyByteBuf, CPacketAltarTransfer> STREAM_CODEC =
                new StreamCodec<>() {
                    @Override
                    public CPacketAltarTransfer decode(RegistryFriendlyByteBuf buf) {
                        int size = buf.readVarInt();
                        List<ItemStack> items = new ArrayList<>(size);
                        for (int i = 0; i < size; i++) {
                            items.add(ItemStack.STREAM_CODEC.decode(buf));
                        }
                        return new CPacketAltarTransfer(items);
                    }

                    @Override
                    public void encode(RegistryFriendlyByteBuf buf, CPacketAltarTransfer pkt) {
                        buf.writeVarInt(pkt.inputs.size());
                        for (ItemStack stack : pkt.inputs) {
                            ItemStack.STREAM_CODEC.encode(buf, stack);
                        }
                    }
                };

        @Override
        public CustomPacketPayload.Type<CPacketAltarTransfer> type() {
            return TYPE;
        }
    }
}
