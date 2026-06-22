package com.tlmjei.compat;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.Direction;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.items.IItemHandler;
import net.minecraft.core.registries.BuiltInRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Searches equipped backpacks and nearby containers for crafting materials.
 * NeoForge 1.21.1 version — uses new Capabilities API (no LazyOptional).
 */
public class BackpackCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger("tlmjei_backpack");
    private static Boolean l2Loaded, sbLoaded, curiosLoaded;

    private static boolean l2Loaded() {
        if (l2Loaded == null) l2Loaded = ModList.get().isLoaded("l2backpack");
        return l2Loaded;
    }

    private static boolean sbLoaded() {
        if (sbLoaded == null) sbLoaded = ModList.get().isLoaded("sophisticatedbackpacks");
        return sbLoaded;
    }

    private static boolean curiosLoaded() {
        if (curiosLoaded == null) curiosLoaded = ModList.get().isLoaded("curios");
        return curiosLoaded;
    }

    /** Try to take one matching item from any source (backpacks, curios, containers). */
    public static ItemStack tryTakeItem(Player player, ItemStack needed) {
        LOGGER.info("tryTakeItem called, l2={} sb={} curios={}", l2Loaded(), sbLoaded(), curiosLoaded());
        if (needed.isEmpty()) return ItemStack.EMPTY;
        if (!l2Loaded() && !sbLoaded()) return ItemStack.EMPTY;

        // Scan main inventory + offhand
        for (ItemStack stack : allMainItems(player)) {
            if (stack.isEmpty() || !isBackpackItem(stack)) continue;
            LOGGER.info("Found backpack: {}", BuiltInRegistries.ITEM.getKey(stack.getItem()));
            ItemStack result = extractFrom(stack, needed, player);
            if (!result.isEmpty()) return result;
        }

        // Scan Curios slots
        if (curiosLoaded()) {
            for (ItemStack stack : allCurioItems(player)) {
                if (stack.isEmpty() || !isBackpackItem(stack)) continue;
                LOGGER.info("Found backpack in Curios: {}", BuiltInRegistries.ITEM.getKey(stack.getItem()));
                ItemStack result = extractFrom(stack, needed, player);
                if (!result.isEmpty()) return result;
            }
        }

        // Scan nearby containers
        if (com.tlmjei.config.TlmJeiConfig.containerSearchRange > 0) {
            ItemStack fromContainer = tryTakeFromContainer(player, needed);
            if (!fromContainer.isEmpty()) return fromContainer;
        }

        LOGGER.info("tryTakeItem: item not found");
        return ItemStack.EMPTY;
    }

    // ---- detection ----

    private static boolean isBackpackItem(ItemStack stack) {
        String ns = BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace();
        return (l2Loaded() && ns.equals("l2backpack")) ||
               (sbLoaded() && ns.equals("sophisticatedbackpacks"));
    }

    // ---- extraction ----

    private static ItemStack extractFrom(ItemStack backpack, ItemStack needed, @Nullable Player player) {
        String ns = BuiltInRegistries.ITEM.getKey(backpack.getItem()).getNamespace();

        if (ns.equals("l2backpack")) {
            ItemStack taken = extractFromL2Nbt(backpack, needed);
            if (!taken.isEmpty()) return taken;
            taken = extractFromL2InvCap(backpack, needed, player);
            if (!taken.isEmpty()) return taken;
            taken = extractFromL2Drawer(backpack, needed, player);
            if (!taken.isEmpty()) return taken;
        }

        // Fallback: ITEM_HANDLER item capability (NeoForge 1.21 style)
        var handler = backpack.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.ITEM);
        if (handler != null) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack inSlot = handler.getStackInSlot(slot);
                if (!inSlot.isEmpty() && ItemStack.isSameItem(inSlot, needed)) {
                    ItemStack taken = handler.extractItem(slot, 1, false);
                    if (!taken.isEmpty()) return taken;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    // ---- L2Backpack NBT ----

    private static ItemStack extractFromL2Nbt(ItemStack backpack, ItemStack needed) {
        try {
            Object item = backpack.getItem();
            Class<?> itemClass = item.getClass();
            try {
                Method getItems = findMethod(itemClass, "getItems", ItemStack.class);
                if (getItems != null) {
                    List<?> items = (List<?>) getItems.invoke(item, backpack);
                    return findAndRemove(items, needed);
                }
            } catch (Exception ignored) {}
            try {
                Method getContent = findMethod(itemClass, "getContent", ItemStack.class);
                if (getContent != null) {
                    List<?> items = (List<?>) getContent.invoke(item, backpack);
                    return findAndRemove(items, needed);
                }
            } catch (Exception ignored) {}
        } catch (Exception e) {
            LOGGER.warn("L2Nbt error: {}", e.getMessage());
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack findAndRemove(List<?> items, ItemStack needed) {
        for (int i = 0; i < items.size(); i++) {
            ItemStack st = (ItemStack) items.get(i);
            if (!st.isEmpty() && ItemStack.isSameItem(st, needed)) {
                ItemStack taken = st.copy();
                taken.setCount(1);
                st.shrink(1);
                return taken;
            }
        }
        return ItemStack.EMPTY;
    }

    // ---- L2Backpack getInvCap (WorldChestItem / EnderBackpackItem) ----

    private static ItemStack extractFromL2InvCap(ItemStack stack, ItemStack needed, @Nullable Player player) {
        if (!(player instanceof net.minecraft.server.level.ServerPlayer sp)) return ItemStack.EMPTY;
        try {
            Method getInvCap = stack.getItem().getClass()
                    .getMethod("getInvCap", ItemStack.class, net.minecraft.server.level.ServerPlayer.class);
            Object handler = getInvCap.invoke(stack.getItem(), stack, sp);
            if (handler instanceof IItemHandler ih) {
                for (int slot = 0; slot < ih.getSlots(); slot++) {
                    ItemStack in = ih.getStackInSlot(slot);
                    if (!in.isEmpty() && ItemStack.isSameItem(in, needed)) {
                        ItemStack taken = ih.extractItem(slot, 1, false);
                        if (!taken.isEmpty()) return taken;
                    }
                }
            }
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            LOGGER.warn("L2InvCap error: {}", e.getMessage());
        }
        return ItemStack.EMPTY;
    }

    // ---- L2Backpack drawers ----

    private static ItemStack extractFromL2Drawer(ItemStack stack, ItemStack needed, @Nullable Player player) {
        try {
            Method getItem = stack.getItem().getClass().getMethod("getItem", ItemStack.class);
            Method getCount = stack.getItem().getClass().getMethod("getCount", ItemStack.class);
            Method setCount = stack.getItem().getClass().getMethod("setCount", ItemStack.class, int.class);

            net.minecraft.world.item.Item storedItem = (net.minecraft.world.item.Item) getItem.invoke(stack.getItem(), stack);
            int count = (int) getCount.invoke(stack.getItem(), stack);
            if (!storedItem.equals(needed.getItem()) || count <= 0) return ItemStack.EMPTY;

            setCount.invoke(stack.getItem(), stack, count - 1);
            return new ItemStack(storedItem, 1);
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            LOGGER.warn("L2Drawer error: {}", e.getMessage());
        }
        return ItemStack.EMPTY;
    }

    // ---- container scanning ----

    private static ItemStack tryTakeFromContainer(Player player, ItemStack needed) {
        int range = (int) Math.ceil(com.tlmjei.config.TlmJeiConfig.containerSearchRange);
        if (range <= 0) return ItemStack.EMPTY;

        try {
            Level level = player.level();
            BlockPos center = player.blockPosition();
            int cx = center.getX() >> 4, cz = center.getZ() >> 4;
            int chunkRadius = (range >> 4) + 1;

            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                    if (!level.hasChunk(cx + dx, cz + dz)) continue;
                    var chunk = level.getChunk(cx + dx, cz + dz);
                    if (chunk == null) continue;

                    for (var entry : chunk.getBlockEntities().entrySet()) {
                        BlockPos pos = entry.getKey();
                        if (Math.sqrt(pos.distSqr(center)) > range) continue;

                        ItemStack result = extractFromBE(entry.getValue(), needed);
                        if (!result.isEmpty()) return result;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Container scan error: {}", e.getMessage());
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack extractFromBE(BlockEntity be, ItemStack needed) {
        // NeoForge 1.21: BlockCapabilities return directly or null
        var handler = be.getLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, be.getBlockPos(), be.getBlockState(), be, Direction.UP);
        if (handler == null) return ItemStack.EMPTY;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack inSlot = handler.getStackInSlot(slot);
            if (!inSlot.isEmpty() && ItemStack.isSameItem(inSlot, needed)) {
                ItemStack taken = handler.extractItem(slot, 1, false);
                if (!taken.isEmpty()) return taken;
            }
        }
        return ItemStack.EMPTY;
    }

    // ---- Curios ----

    private static List<ItemStack> allCurioItems(Player player) {
        List<ItemStack> list = new ArrayList<>();
        try {
            Class<?> apiClass = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Method getInv = apiClass.getMethod("getCuriosInventory", net.minecraft.world.entity.LivingEntity.class);
            Object lazyOpt = getInv.invoke(null, player);
            if (lazyOpt == null) return list;
            if (!(boolean) lazyOpt.getClass().getMethod("isPresent").invoke(lazyOpt)) return list;

            Object icih;
            try {
                icih = lazyOpt.getClass().getMethod("orElse", Object.class).invoke(lazyOpt, (Object) null);
            } catch (NoSuchMethodException e) {
                return list;
            }
            if (icih == null) return list;

            Object curioMap = icih.getClass().getMethod("getCurios").invoke(icih);
            for (Object handler : ((java.util.Map<?, ?>) curioMap).values()) {
                Object stacks = handler.getClass().getMethod("getStacks").invoke(handler);
                int slots = (int) stacks.getClass().getMethod("getSlots").invoke(stacks);
                for (int i = 0; i < slots; i++) {
                    ItemStack st = (ItemStack) stacks.getClass().getMethod("getStackInSlot", int.class).invoke(stacks, i);
                    if (!st.isEmpty()) list.add(st);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Curios scan error: {}", e.getMessage());
        }
        return list;
    }

    // ---- utility ----

    private static List<ItemStack> allMainItems(Player player) {
        List<ItemStack> list = new ArrayList<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (!s.isEmpty()) list.add(s);
        }
        ItemStack off = player.getOffhandItem();
        if (!off.isEmpty()) list.add(off);
        return list;
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        try { return clazz.getMethod(name, paramTypes); } catch (Exception e) { return null; }
    }
}
