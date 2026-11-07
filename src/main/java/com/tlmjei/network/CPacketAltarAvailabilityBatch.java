package com.tlmjei.network;

import com.tlmjei.util.AltarHelper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Client -> server bulk availability query. JEI computes its craftable-first recipe order once,
 * as soon as the altar category is opened, so the answer for every recipe has to be on the
 * client before that. This packet asks the server to check a whole category at once; the server
 * scans the nearby containers once and replies with the usual per-recipe availability packets.
 */
public class CPacketAltarAvailabilityBatch {

    /** Safety caps so a malformed packet cannot allocate huge lists. */
    public static final int MAX_RECIPES = 512;
    private static final int MAX_GROUPS = 16;
    private static final int MAX_CANDIDATES_PER_GROUP = 128;
    private static final int MAX_UNIQUE_ITEMS = 8192;

    public record Query(int recipeKey, int requestId, List<List<Item>> groups) {
    }

    private final List<Query> queries;

    public CPacketAltarAvailabilityBatch(List<Query> queries) {
        this.queries = queries;
    }

    public static void encode(CPacketAltarAvailabilityBatch packet, FriendlyByteBuf buf) {
        int queryCount = Math.min(packet.queries.size(), MAX_RECIPES);

        Map<Item, Integer> itemIds = new LinkedHashMap<>();
        for (int i = 0; i < queryCount; i++) {
            for (List<Item> group : packet.queries.get(i).groups) {
                int candidates = Math.min(group.size(), MAX_CANDIDATES_PER_GROUP);
                for (int j = 0; j < candidates; j++) {
                    Item item = group.get(j);
                    if (item != null && !itemIds.containsKey(item)) {
                        itemIds.put(item, itemIds.size());
                    }
                }
            }
        }

        buf.writeVarInt(queryCount);
        buf.writeVarInt(itemIds.size());
        for (Item item : itemIds.keySet()) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            buf.writeResourceLocation(id == null
                    ? new ResourceLocation("minecraft", "air") : id);
        }

        for (int i = 0; i < queryCount; i++) {
            Query query = packet.queries.get(i);
            buf.writeVarInt(query.recipeKey);
            buf.writeVarInt(query.requestId);

            int groupCount = Math.min(query.groups.size(), MAX_GROUPS);
            buf.writeVarInt(groupCount);
            for (int groupIndex = 0; groupIndex < groupCount; groupIndex++) {
                List<Item> group = query.groups.get(groupIndex);
                int candidateCount = Math.min(group.size(), MAX_CANDIDATES_PER_GROUP);
                buf.writeVarInt(candidateCount);
                for (int j = 0; j < candidateCount; j++) {
                    Item item = group.get(j);
                    Integer index = item == null ? null : itemIds.get(item);
                    buf.writeVarInt(index == null ? 0 : index);
                }
            }
        }
    }

    public static CPacketAltarAvailabilityBatch decode(FriendlyByteBuf buf) {
        int queryCount = buf.readVarInt();
        int uniqueCount = buf.readVarInt();

        List<Item> items = new ArrayList<>(Math.min(uniqueCount, MAX_UNIQUE_ITEMS));
        for (int i = 0; i < uniqueCount; i++) {
            ResourceLocation id = buf.readResourceLocation();
            if (i < MAX_UNIQUE_ITEMS) {
                Item item = BuiltInRegistries.ITEM.get(id);
                items.add(item == null ? Items.AIR : item);
            }
        }

        List<Query> queries = new ArrayList<>(Math.min(queryCount, MAX_RECIPES));
        for (int i = 0; i < queryCount; i++) {
            int recipeKey = buf.readVarInt();
            int requestId = buf.readVarInt();

            int groupCount = buf.readVarInt();
            List<List<Item>> groups = new ArrayList<>(Math.min(groupCount, MAX_GROUPS));
            for (int groupIndex = 0; groupIndex < groupCount; groupIndex++) {
                int candidateCount = buf.readVarInt();
                List<Item> group = new ArrayList<>(
                        Math.min(candidateCount, MAX_CANDIDATES_PER_GROUP));
                for (int j = 0; j < candidateCount; j++) {
                    int index = buf.readVarInt();
                    if (j < MAX_CANDIDATES_PER_GROUP && index >= 0 && index < items.size()) {
                        group.add(items.get(index));
                    }
                }
                if (groupIndex < MAX_GROUPS) {
                    groups.add(group);
                }
            }
            if (i < MAX_RECIPES) {
                queries.add(new Query(recipeKey, requestId, groups));
            }
        }
        return new CPacketAltarAvailabilityBatch(queries);
    }

    public static void handle(CPacketAltarAvailabilityBatch packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            List<List<List<ItemStack>>> recipes = new ArrayList<>(packet.queries.size());
            for (Query query : packet.queries) {
                List<List<ItemStack>> groups = new ArrayList<>(query.groups.size());
                for (List<Item> group : query.groups) {
                    List<ItemStack> stacks = new ArrayList<>(group.size());
                    for (Item item : group) {
                        stacks.add(new ItemStack(item));
                    }
                    groups.add(stacks);
                }
                recipes.add(groups);
            }

            List<List<Integer>> missing = AltarHelper.findMissingIngredientGroupsBatch(player, recipes);
            for (int i = 0; i < packet.queries.size(); i++) {
                Query query = packet.queries.get(i);
                List<Integer> groups = i < missing.size() ? missing.get(i) : List.of();
                NetworkHandler.CHANNEL.reply(
                        new SPacketAltarAvailability(query.recipeKey, query.requestId, groups),
                        ctx.get());
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
