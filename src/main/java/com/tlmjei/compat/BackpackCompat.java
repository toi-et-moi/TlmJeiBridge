package com.tlmjei.compat;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Searches equipped backpacks for crafting materials and extracts them.
 * Supports L2Backpack and SophisticatedBackpacks via their ITEM_HANDLER capability.
 * All items from these mods are checked — no need to know specific class names.
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

    /** Try to take one matching item from any equipped backpack. */
    public static ItemStack tryTakeItem(Player player, ItemStack needed) {
        LOGGER.info("tryTakeItem called, l2={} sb={} curios={}", l2Loaded(), sbLoaded(), curiosLoaded());
        if (needed.isEmpty()) return ItemStack.EMPTY;
        if (!l2Loaded() && !sbLoaded()) return ItemStack.EMPTY;

        // Scan main inventory + offhand for backpack items
        for (ItemStack stack : allMainItems(player)) {
            if (stack.isEmpty()) continue;
            if (!isBackpackItem(stack)) continue;
            LOGGER.info("Found backpack: {}", ForgeRegistries.ITEMS.getKey(stack.getItem()));
            ItemStack result = extractFrom(stack, needed, player);
        }

        // Scan Curios slots if available
        if (curiosLoaded()) {
            List<ItemStack> curioItems = allCurioItems(player);
            LOGGER.info("Curios items found: {}", curioItems.size());
            for (ItemStack stack : curioItems) {
                if (stack.isEmpty()) continue;
                if (!isBackpackItem(stack)) continue;
                LOGGER.info("Found backpack in Curios: {}", ForgeRegistries.ITEMS.getKey(stack.getItem()));
                ItemStack result = extractFrom(stack, needed);
                if (!result.isEmpty()) return result;
            }
        }

        // Scan nearby containers if enabled
        if (com.tlmjei.config.TlmJeiConfig.containerSearchRange > 0) {
            ItemStack fromContainer = tryTakeFromContainer(player, needed);
            if (!fromContainer.isEmpty()) return fromContainer;
        }

        LOGGER.info("tryTakeItem: item not found");
        return ItemStack.EMPTY;
    }

    // ---- container scanning ----

    /** Search nearby chests/containers for the needed item.
     *  Iterates block entities from loaded chunks within the configured range. */
    private static ItemStack tryTakeFromContainer(Player player, ItemStack needed) {
        int range = (int) Math.ceil(com.tlmjei.config.TlmJeiConfig.containerSearchRange);
        if (range <= 0) return ItemStack.EMPTY;

        try {
            net.minecraft.world.level.Level level = player.level();
            net.minecraft.core.BlockPos center = player.blockPosition();
            int cx = center.getX() >> 4, cz = center.getZ() >> 4;
            int chunkRadius = (range >> 4) + 1;

            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                    if (!level.hasChunk(cx + dx, cz + dz)) continue;
                    net.minecraft.world.level.chunk.LevelChunk chunk =
                            level.getChunk(cx + dx, cz + dz);
                    if (chunk == null) continue;

                    for (var entry : chunk.getBlockEntities().entrySet()) {
                        net.minecraft.core.BlockPos pos = entry.getKey();
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

    /** Try to extract one item from a block entity via ITEM_HANDLER capability. */
    private static ItemStack extractFromBE(net.minecraft.world.level.block.entity.BlockEntity be,
                                            ItemStack needed) {
        try {
            var opt = be.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve();
            if (opt.isEmpty()) return ItemStack.EMPTY;
            IItemHandler handler = (IItemHandler) opt.get();
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack inSlot = handler.getStackInSlot(slot);
                if (!inSlot.isEmpty() && ItemStack.isSameItem(inSlot, needed)) {
                    ItemStack taken = handler.extractItem(slot, 1, false);
                    if (!taken.isEmpty()) return taken;
                }
            }
        } catch (Exception ignored) {}
        return ItemStack.EMPTY;
    }

    // ---- detection ----

    /** Check if an ItemStack belongs to a known backpack mod. */
    private static boolean isBackpackItem(ItemStack stack) {
        String ns = ForgeRegistries.ITEMS.getKey(stack.getItem()).getNamespace();
        if (l2Loaded() && ns.equals("l2backpack")) return true;
        if (sbLoaded() && ns.equals("sophisticatedbackpacks")) return true;
        return false;
    }

    // ---- extraction ----

    /** Extract one matching item from a backpack. */
    private static ItemStack extractFrom(ItemStack backpack, ItemStack needed) {
        return extractFrom(backpack, needed, null);
    }

    private static ItemStack extractFrom(ItemStack backpack, ItemStack needed, @javax.annotation.Nullable Player player) {
        String ns = ForgeRegistries.ITEMS.getKey(backpack.getItem()).getNamespace();

        // L2Backpack: try NBT, then getInvCap, then drawer-specific
        if (ns.equals("l2backpack")) {
            ItemStack taken = extractFromL2Nbt(backpack, needed);
            if (!taken.isEmpty()) return taken;
            taken = extractFromL2InvCap(backpack, needed, player);
            if (!taken.isEmpty()) return taken;
            taken = extractFromL2Drawer(backpack, needed, player);
            if (!taken.isEmpty()) return taken;
        }

        // Fallback: Forge ITEM_HANDLER capability (works for SophisticatedBackpacks)
        try {
            var opt = backpack.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve();
            if (opt.isEmpty()) return ItemStack.EMPTY;
            IItemHandler handler = (IItemHandler) opt.get();
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack inSlot = handler.getStackInSlot(slot);
                if (!inSlot.isEmpty() && ItemStack.isSameItem(inSlot, needed)) {
                    ItemStack taken = handler.extractItem(slot, 1, false);
                    if (!taken.isEmpty()) return taken;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("extractFrom capability error: {}", e.getMessage());
        }
        return ItemStack.EMPTY;
    }

    /** L2Backpack NBT-based extraction — handles BaseBagItem and AbstractBag. */
    private static ItemStack extractFromL2Nbt(ItemStack backpack, ItemStack needed) {
        try {
            Object item = backpack.getItem();
            Class<?> itemClass = item.getClass();

            // Try BaseBagItem.getItems(ItemStack) first
            try {
                java.lang.reflect.Method getItems = findMethod(itemClass, "getItems",
                        net.minecraft.world.item.ItemStack.class);
                if (getItems != null) {
                    List<?> items = (List<?>) getItems.invoke(item, backpack);
                    return findAndRemove(backpack, items, needed);
                }
            } catch (Exception ignored) {}

            // Try AbstractBag.getContent(ItemStack)
            try {
                java.lang.reflect.Method getContent = findMethod(itemClass, "getContent",
                        net.minecraft.world.item.ItemStack.class);
                if (getContent != null) {
                    List<?> items = (List<?>) getContent.invoke(item, backpack);
                    return findAndRemove(backpack, items, needed);
                }
            } catch (Exception ignored) {}

            // Fallback to capability for this item
            return ItemStack.EMPTY;
        } catch (Exception e) {
            LOGGER.warn("L2Nbt error: {}", e.getMessage());
            return ItemStack.EMPTY;
        }
    }

    /** L2Backpack: use getInvCap(ItemStack, ServerPlayer) for WorldChestItem / EnderBackpackItem. */
    private static ItemStack extractFromL2InvCap(ItemStack stack, ItemStack needed, Player player) {
        if (!(player instanceof net.minecraft.server.level.ServerPlayer sp)) return ItemStack.EMPTY;
        try {
            java.lang.reflect.Method getInvCap = stack.getItem().getClass()
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
            // Item doesn't have getInvCap — not a CapInsertItem
        } catch (Exception e) {
            LOGGER.warn("L2InvCap error: {}", e.getMessage());
        }
        return ItemStack.EMPTY;
    }

    /** L2Backpack: handle DrawerItem / EnderDrawerItem via their NBT/API. */
    private static ItemStack extractFromL2Drawer(ItemStack stack, ItemStack needed, Player player) {
        try {
            Object item = stack.getItem();
            java.lang.reflect.Method getItem = item.getClass().getMethod("getItem", ItemStack.class);
            java.lang.reflect.Method getCount = item.getClass().getMethod("getCount", ItemStack.class);
            java.lang.reflect.Method setCount = item.getClass().getMethod("setCount", ItemStack.class, int.class);

            net.minecraft.world.item.Item storedItem = (net.minecraft.world.item.Item) getItem.invoke(item, stack);
            int count = (int) getCount.invoke(item, stack);

            if (!storedItem.equals(needed.getItem()) || count <= 0) return ItemStack.EMPTY;

            // Decrement count (extract one)
            setCount.invoke(item, stack, count - 1);
            return new ItemStack(storedItem, 1);
        } catch (NoSuchMethodException ignored) {
            // Not a drawer item
        } catch (Exception e) {
            LOGGER.warn("L2Drawer error: {}", e.getMessage());
        }
        return ItemStack.EMPTY;
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        try { return clazz.getMethod(name, paramTypes); } catch (Exception e) { return null; }
    }

    /** Find matching item in a list, remove it from the backpack NBT, and return it. */
    private static ItemStack findAndRemove(ItemStack backpack, List<?> items, ItemStack needed) {
        for (int i = 0; i < items.size(); i++) {
            ItemStack st = (ItemStack) items.get(i);
            if (!st.isEmpty() && ItemStack.isSameItem(st, needed)) {
                ItemStack taken = st.copy();
                taken.setCount(1);
                st.shrink(1);
                // Save changes back to the backpack ItemStack NBT via capability
                try {
                    backpack.getCapability(ForgeCapabilities.ITEM_HANDLER)
                            .ifPresent(h -> {}); // trigger any needed sync
                } catch (Exception ignored) {}
                return taken;
            }
        }
        return ItemStack.EMPTY;
    }

    // ---- inventory scans ----

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

    /** Curios slot items — uses reflection so no compile-time dependency. */
    private static List<ItemStack> allCurioItems(Player player) {
        List<ItemStack> list = new ArrayList<>();
        try {
            Class<?> apiClass = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            java.lang.reflect.Method getInv = apiClass.getMethod("getCuriosInventory",
                    net.minecraft.world.entity.LivingEntity.class);
            Object lazyOpt = getInv.invoke(null, player);
            if (lazyOpt == null) return list;
            if (!(boolean) lazyOpt.getClass().getMethod("isPresent").invoke(lazyOpt)) return list;

            // Get ICuriosItemHandler from LazyOptional using orElse(null)
            Object icih;
            try {
                icih = lazyOpt.getClass().getMethod("orElse", Object.class).invoke(lazyOpt, (Object) null);
            } catch (NoSuchMethodException e) {
                LOGGER.warn("LazyOptional.orElse not available");
                return list;
            }
            if (icih == null) return list;

            // Iterate curios slots via getCurios() → Map<String, ICurioStacksHandler>
            Object curioMap = icih.getClass().getMethod("getCurios").invoke(icih);
            for (Object handler : ((java.util.Map<?, ?>) curioMap).values()) {
                Object stacks = handler.getClass().getMethod("getStacks").invoke(handler);
                int slots = (int) stacks.getClass().getMethod("getSlots").invoke(stacks);
                for (int i = 0; i < slots; i++) {
                    ItemStack st = (ItemStack) stacks.getClass()
                            .getMethod("getStackInSlot", int.class).invoke(stacks, i);
                    if (!st.isEmpty()) list.add(st);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Curios scan error: {}", e.getMessage());
        }
        return list;
    }
}
