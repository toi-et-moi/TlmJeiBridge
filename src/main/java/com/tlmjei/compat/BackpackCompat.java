package com.tlmjei.compat;

import com.tlmjei.config.TlmJeiConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Searches equipped backpacks and nearby containers for crafting materials.
 * NeoForge 1.21.1 version — uses new Capabilities API (no LazyOptional).
 *
 * <p>Containers are scanned recursively: a backpack (L2 ender backpack, dimensional storage,
 * normal backpack, drawer, ...) that is stored inside another container is opened and searched
 * as well. This is required for setups like a dimensional backpack nested inside an ender backpack.</p>
 */
public class BackpackCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger("tlmjei_backpack");
    private static final int MAX_DEPTH = 8;

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

    /** Try to take one matching item from any source (inventory, backpacks, curios, containers). */
    public static ItemStack tryTakeItem(Player player, ItemStack needed) {
        if (needed.isEmpty()) return ItemStack.EMPTY;
        return tryTakeAnyItem(player, Set.of(needed.getItem()));
    }

    /**
     * Try to take one item whose type is contained in {@code wanted} from any source:
     * the player inventory, equipped backpacks, Curios slots and nearby containers.
     * Backpacks found inside other containers are searched recursively.
     */
    public static ItemStack tryTakeAnyItem(Player player, Collection<Item> wanted) {
        if (wanted.isEmpty()) return ItemStack.EMPTY;
        Set<Item> want = (wanted instanceof Set<Item> set) ? set : new LinkedHashSet<>(wanted);

        // 1. Plain player inventory (main inventory, armor slots and offhand).
        ItemStack fromInventory = takeFromInventory(player, want);
        if (!fromInventory.isEmpty()) return fromInventory;

        Set<ItemStack> visiting = Collections.newSetFromMap(new IdentityHashMap<>());

        // 2. Backpacks / container items carried or worn by the player.
        for (ItemStack stack : allMainItems(player)) {
            if (!isContainerItem(stack)) continue;
            ItemStack result = extractDeep(stack, want, player, 0, visiting);
            if (!result.isEmpty()) return result;
        }

        // 3. Curios slots.
        if (curiosLoaded()) {
            for (ItemStack stack : allCurioItems(player)) {
                if (!isContainerItem(stack)) continue;
                ItemStack result = extractDeep(stack, want, player, 0, visiting);
                if (!result.isEmpty()) return result;
            }
        }

        // 4. Nearby containers (also scans backpacks stored inside those containers).
        if (TlmJeiConfig.containerSearchRange > 0) {
            ItemStack fromContainer = tryTakeFromContainer(player, want, visiting);
            if (!fromContainer.isEmpty()) return fromContainer;
        }

        return ItemStack.EMPTY;
    }

    /**
     * Server-side dry run for the JEI transfer button. Collects every item that
     * {@link #tryTakeAnyItem(Player, Collection)} could reach without removing anything.
     *
     * <p>The map is filled in the same order the extraction searches its sources (player
     * inventory first, then carried/worn backpacks, Curios and nearby containers), so a
     * caller can greedily consume it for the recipe slots and get the same answer the real
     * transfer would produce.</p>
     */
    public static void collectAvailableItems(Player player, Map<Item, Integer> pool) {
        if (player == null || pool == null) return;

        // 1. Plain player inventory (main inventory, armor slots and offhand).
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            addToPool(pool, inventory.getItem(slot));
        }

        Set<ItemStack> visiting = Collections.newSetFromMap(new IdentityHashMap<>());

        // 2. Backpacks / container items carried or worn by the player.
        for (ItemStack stack : allMainItems(player)) {
            if (!isContainerItem(stack)) continue;
            collectDeep(stack, player, pool, 0, visiting);
        }

        // 3. Curios slots.
        if (curiosLoaded()) {
            for (ItemStack stack : allCurioItems(player)) {
                if (!isContainerItem(stack)) continue;
                collectDeep(stack, player, pool, 0, visiting);
            }
        }

        // 4. Nearby containers (also scans backpacks stored inside those containers).
        if (TlmJeiConfig.containerSearchRange > 0) {
            collectFromContainers(player, pool, visiting);
        }
    }

    private static void addToPool(Map<Item, Integer> pool, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        pool.merge(stack.getItem(), stack.getCount(), Integer::sum);
    }

    private static void collectDeep(ItemStack container, Player player, Map<Item, Integer> pool,
                                    int depth, Set<ItemStack> visiting) {
        if (depth > MAX_DEPTH || container.isEmpty()) return;
        if (!visiting.add(container)) return;

        ResourceLocation id = BuiltInRegistries.ITEM.getKey(container.getItem());
        String namespace = id == null ? "" : id.getNamespace();

        try {
            if ("l2backpack".equals(namespace)) {
                collectFromDrawer(container, player, pool);

                IItemHandler invCap = getInvCapHandler(container, player);
                if (invCap != null) {
                    collectFromHandler(invCap, player, pool, depth, visiting);
                }
            }

            IItemHandler handler = container.getCapability(Capabilities.ItemHandler.ITEM);
            if (handler != null) {
                collectFromHandler(handler, player, pool, depth, visiting);
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to inspect container {}: {}", id, e.toString());
        }
    }

    private static void collectFromHandler(IItemHandler handler, Player player, Map<Item, Integer> pool,
                                           int depth, Set<ItemStack> visiting) {
        int slots = handler.getSlots();
        for (int slot = 0; slot < slots; slot++) {
            ItemStack inSlot;
            try {
                inSlot = handler.getStackInSlot(slot);
            } catch (Exception e) {
                continue;
            }
            if (inSlot.isEmpty()) continue;

            addToPool(pool, inSlot);

            if (depth < MAX_DEPTH && isContainerItem(inSlot)) {
                collectDeep(inSlot, player, pool, depth + 1, visiting);
            }
        }
    }

    private static void collectFromDrawer(ItemStack drawer, Player player, Map<Item, Integer> pool) {
        try {
            Object item = drawer.getItem();
            // simulate = true: only ask how much could be taken, do not change the drawer.
            Method takeItem = item.getClass().getMethod(
                    "takeItem", ItemStack.class, int.class, Player.class, boolean.class);
            Object result = takeItem.invoke(item, drawer, Integer.MAX_VALUE, player, true);
            if (result instanceof ItemStack content) {
                addToPool(pool, content);
            }
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            LOGGER.debug("Drawer inspection failed for {}: {}", drawer.getItem(), e.toString());
        }
    }

    private static void collectFromContainers(Player player, Map<Item, Integer> pool,
                                              Set<ItemStack> visiting) {
        int range = (int) Math.ceil(TlmJeiConfig.containerSearchRange);
        if (range <= 0) return;

        try {
            Level level = player.level();
            BlockPos center = player.blockPosition();
            int cx = center.getX() >> 4, cz = center.getZ() >> 4;
            int chunkRadius = (range >> 4) + 1;
            double rangeSq = (double) range * range;

            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                    if (!level.hasChunk(cx + dx, cz + dz)) continue;
                    var chunk = level.getChunk(cx + dx, cz + dz);
                    if (chunk == null) continue;

                    for (var entry : chunk.getBlockEntities().entrySet()) {
                        BlockPos pos = entry.getKey();
                        if (pos.distSqr(center) > rangeSq) continue;
                        collectFromBE(level, entry.getValue(), player, pool, visiting);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Container scan failed: {}", e.toString());
        }
    }

    private static void collectFromBE(Level level, BlockEntity be, Player player, Map<Item, Integer> pool,
                                      Set<ItemStack> visiting) {
        if (be == null) return;
        BlockPos pos = be.getBlockPos();

        IItemHandler handler = level.getCapability(
                Capabilities.ItemHandler.BLOCK, pos, be.getBlockState(), be, null);
        if (handler == null) {
            handler = level.getCapability(
                    Capabilities.ItemHandler.BLOCK, pos, be.getBlockState(), be, Direction.UP);
        }
        if (handler == null) return;

        collectFromHandler(handler, player, pool, 0, visiting);
    }

    // ---- source scanning ----

    private static ItemStack takeFromInventory(Player player, Set<Item> wanted) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !wanted.contains(stack.getItem())) continue;
            ItemStack taken = inventory.removeItem(slot, 1);
            if (!taken.isEmpty()) return taken;
        }
        return ItemStack.EMPTY;
    }

    private static List<ItemStack> allMainItems(Player player) {
        List<ItemStack> list = new ArrayList<>();
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) list.add(stack);
        }
        return list;
    }

    // ---- container detection ----

    private static boolean isContainerItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) return false;
        String namespace = id.getNamespace();
        if ("l2backpack".equals(namespace)) return l2Loaded();
        if ("sophisticatedbackpacks".equals(namespace)) return sbLoaded();
        return false;
    }

    // ---- recursive extraction ----

    private static ItemStack extractDeep(ItemStack container, Set<Item> wanted, Player player,
                                         int depth, Set<ItemStack> visiting) {
        if (depth > MAX_DEPTH || container.isEmpty()) return ItemStack.EMPTY;
        // Identity-based guard so a container that (indirectly) contains itself cannot loop forever.
        if (!visiting.add(container)) return ItemStack.EMPTY;

        ResourceLocation id = BuiltInRegistries.ITEM.getKey(container.getItem());
        String namespace = id == null ? "" : id.getNamespace();

        try {
            if ("l2backpack".equals(namespace)) {
                // Drawers keep their content in components and are not exposed as an item capability.
                ItemStack taken = extractFromDrawer(container, wanted, player);
                if (!taken.isEmpty()) return taken;

                // Ender backpack -> player ender chest, dimensional storage -> shared storage.
                taken = extractFromInvCap(container, wanted, player, depth, visiting);
                if (!taken.isEmpty()) return taken;
            }

            // Normal L2 backpacks/bags and Sophisticated Backpacks expose an item handler capability.
            IItemHandler handler = container.getCapability(Capabilities.ItemHandler.ITEM);
            if (handler != null) {
                return extractFromHandler(handler, wanted, player, depth, visiting);
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to search container {}: {}", id, e.toString());
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack extractFromHandler(IItemHandler handler, Set<Item> wanted, Player player,
                                                int depth, Set<ItemStack> visiting) {
        int slots = handler.getSlots();
        for (int slot = 0; slot < slots; slot++) {
            ItemStack inSlot;
            try {
                inSlot = handler.getStackInSlot(slot);
            } catch (Exception e) {
                continue;
            }
            if (inSlot.isEmpty()) continue;

            if (wanted.contains(inSlot.getItem())) {
                ItemStack taken = handler.extractItem(slot, 1, false);
                if (!taken.isEmpty()) return taken;
            }

            if (depth < MAX_DEPTH && isContainerItem(inSlot)) {
                ItemStack taken = extractDeep(inSlot, wanted, player, depth + 1, visiting);
                if (!taken.isEmpty()) {
                    writeBack(handler, slot, inSlot);
                    return taken;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * Persists a modified nested container stack back into its parent handler. Handlers may return
     * copies of the stacks they hold (component based handlers in particular), in which case the
     * mutated copy has to be written back or the extraction would be lost / duplicated.
     */
    private static void writeBack(IItemHandler handler, int slot, ItemStack stack) {
        if (!(handler instanceof IItemHandlerModifiable modifiable)) return;
        try {
            ItemStack current = handler.getStackInSlot(slot);
            if (current.isEmpty() || !ItemStack.isSameItem(current, stack)) return;
            modifiable.setStackInSlot(slot, stack);
        } catch (Exception e) {
            LOGGER.debug("Failed to write back slot {}: {}", slot, e.toString());
        }
    }

    // ---- L2Backpack getInvCap (ender backpack / dimensional storage) ----

    @Nullable
    private static IItemHandler getInvCapHandler(ItemStack stack, Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) return null;
        try {
            Method method = stack.getItem().getClass()
                    .getMethod("getInvCap", ItemStack.class, ServerPlayer.class);
            Object handler = method.invoke(stack.getItem(), stack, serverPlayer);
            return handler instanceof IItemHandler itemHandler ? itemHandler : null;
        } catch (NoSuchMethodException ignored) {
            return null;
        } catch (Exception e) {
            LOGGER.debug("getInvCap failed for {}: {}", stack.getItem(), e.toString());
            return null;
        }
    }

    private static ItemStack extractFromInvCap(ItemStack stack, Set<Item> wanted, Player player,
                                               int depth, Set<ItemStack> visiting) {
        IItemHandler handler = getInvCapHandler(stack, player);
        if (handler == null) return ItemStack.EMPTY;
        return extractFromHandler(handler, wanted, player, depth, visiting);
    }

    // ---- L2Backpack drawers ----

    private static ItemStack extractFromDrawer(ItemStack drawer, Set<Item> wanted, Player player) {
        try {
            Object item = drawer.getItem();
            Method getContent = item.getClass().getMethod("getDrawerContent", ItemStack.class);
            ItemStack content = (ItemStack) getContent.invoke(item, drawer);
            if (content.isEmpty() || !wanted.contains(content.getItem())) return ItemStack.EMPTY;

            Method takeItem = item.getClass().getMethod(
                    "takeItem", ItemStack.class, int.class, Player.class, boolean.class);
            Object result = takeItem.invoke(item, drawer, 1, player, false);
            return result instanceof ItemStack taken ? taken : ItemStack.EMPTY;
        } catch (NoSuchMethodException ignored) {
            return ItemStack.EMPTY;
        } catch (Exception e) {
            LOGGER.debug("Drawer extraction failed for {}: {}", drawer.getItem(), e.toString());
            return ItemStack.EMPTY;
        }
    }

    // ---- nearby containers ----

    private static ItemStack tryTakeFromContainer(Player player, Set<Item> wanted, Set<ItemStack> visiting) {
        int range = (int) Math.ceil(TlmJeiConfig.containerSearchRange);
        if (range <= 0) return ItemStack.EMPTY;

        try {
            Level level = player.level();
            BlockPos center = player.blockPosition();
            int cx = center.getX() >> 4, cz = center.getZ() >> 4;
            int chunkRadius = (range >> 4) + 1;
            double rangeSq = (double) range * range;

            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                    if (!level.hasChunk(cx + dx, cz + dz)) continue;
                    var chunk = level.getChunk(cx + dx, cz + dz);
                    if (chunk == null) continue;

                    for (var entry : chunk.getBlockEntities().entrySet()) {
                        BlockPos pos = entry.getKey();
                        if (pos.distSqr(center) > rangeSq) continue;

                        ItemStack result = extractFromBE(level, entry.getValue(), wanted, player, visiting);
                        if (!result.isEmpty()) return result;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Container scan failed: {}", e.toString());
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack extractFromBE(Level level, BlockEntity be, Set<Item> wanted,
                                           Player player, Set<ItemStack> visiting) {
        if (be == null) return ItemStack.EMPTY;
        BlockPos pos = be.getBlockPos();

        IItemHandler handler = level.getCapability(
                Capabilities.ItemHandler.BLOCK, pos, be.getBlockState(), be, null);
        if (handler == null) {
            handler = level.getCapability(
                    Capabilities.ItemHandler.BLOCK, pos, be.getBlockState(), be, Direction.UP);
        }
        if (handler == null) return ItemStack.EMPTY;

        return extractFromHandler(handler, wanted, player, 0, visiting);
    }

    // ---- Curios ----

    private static List<ItemStack> allCurioItems(Player player) {
        List<ItemStack> list = new ArrayList<>();
        try {
            Class<?> apiClass = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Object result = apiClass
                    .getMethod("getCuriosInventory", net.minecraft.world.entity.LivingEntity.class)
                    .invoke(null, player);
            Object curiosHandler = unwrapPresent(result);
            if (curiosHandler == null) return list;

            Object curioMap = curiosHandler.getClass().getMethod("getCurios").invoke(curiosHandler);
            for (Object stacksHandler : ((Map<?, ?>) curioMap).values()) {
                Object stacks = stacksHandler.getClass().getMethod("getStacks").invoke(stacksHandler);
                int slots = (int) stacks.getClass().getMethod("getSlots").invoke(stacks);
                for (int i = 0; i < slots; i++) {
                    ItemStack stack = (ItemStack) stacks.getClass()
                            .getMethod("getStackInSlot", int.class).invoke(stacks, i);
                    if (!stack.isEmpty()) list.add(stack);
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Curios scan failed: {}", e.toString());
        }
        return list;
    }

    /**
     * Unwraps both {@link java.util.Optional} (Curios for 1.21) and the old Forge
     * {@code LazyOptional} (Curios for 1.20) without a compile-time dependency on either API.
     */
    @Nullable
    private static Object unwrapPresent(@Nullable Object optionalLike) {
        if (optionalLike == null) return null;
        if (optionalLike instanceof java.util.Optional<?> optional) {
            return optional.orElse(null);
        }
        try {
            boolean present = (boolean) optionalLike.getClass().getMethod("isPresent").invoke(optionalLike);
            if (!present) return null;
            return optionalLike.getClass().getMethod("orElse", Object.class)
                    .invoke(optionalLike, (Object) null);
        } catch (Exception e) {
            return null;
        }
    }
}
