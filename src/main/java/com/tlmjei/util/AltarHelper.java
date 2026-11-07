package com.tlmjei.util;

import com.tlmjei.compat.BackpackCompat;
import com.tlmjei.config.TlmJeiConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class AltarHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger("tlmjei_altar");

    private static Class<?> teClass;
    private static Method getCanPlaceListMethod;
    private static Method posListGetData;
    private static Field handlerField;
    private static Field canPlaceItemField;
    private static Field blockPosListField;
    private static Class<?> posListClass;
    private static Class<?> blockAltarClass;
    private static Method altarCraftMethod;
    private static boolean reflectionReady = false;
    private static boolean reflectionAttempted = false;

    // Shrine lamp (maid beacon) power support, initialized lazily and only on the server.
    private static Class<?> beaconClass;
    private static Method beaconGetStoragePower;
    private static Method beaconSetStoragePower;
    private static Object powerCapability;
    private static Method entityGetCapability;
    private static Method powerGet;
    private static Method powerSet;
    private static Class<?> altarRecipeInventoryClass;
    private static Object altarRecipeType;
    private static float maxPlayerPower = 5.0f;
    private static boolean powerReflectionReady = false;
    private static boolean powerReflectionAttempted = false;

    /** Lazily initialize reflection. Retries if TLM classes weren't loaded yet. */
    private static boolean ensureReflection() {
        if (reflectionAttempted) return reflectionReady;
        reflectionAttempted = true;
        try {
            teClass = Class.forName("com.github.tartaricacid.touhoulittlemaid.tileentity.TileEntityAltar");
            getCanPlaceListMethod = teClass.getMethod("getCanPlaceItemPosList");
            posListClass = Class.forName("com.github.tartaricacid.touhoulittlemaid.util.PosListData");
            posListGetData = posListClass.getMethod("getData");
            handlerField = teClass.getField("handler");
            canPlaceItemField = teClass.getDeclaredField("canPlaceItem");
            canPlaceItemField.setAccessible(true);
            blockPosListField = teClass.getDeclaredField("blockPosList");
            blockPosListField.setAccessible(true);

            blockAltarClass = Class.forName("com.github.tartaricacid.touhoulittlemaid.block.BlockAltar");
            altarCraftMethod = blockAltarClass.getDeclaredMethod(
                    "altarCraft", Level.class, teClass, Player.class);
            altarCraftMethod.setAccessible(true);

            reflectionReady = true;
        } catch (Throwable e) {
            LOGGER.warn("Altar reflection unavailable: {}", e.toString());
            reflectionReady = false;
        }
        return reflectionReady;
    }

    /** Lazily initialize the TLM classes used for shrine lamp power and recipe cost lookup. */
    private static boolean ensurePowerReflection() {
        if (powerReflectionAttempted) return powerReflectionReady;
        powerReflectionAttempted = true;
        try {
            beaconClass = Class.forName(
                    "com.github.tartaricacid.touhoulittlemaid.tileentity.TileEntityMaidBeacon");
            beaconGetStoragePower = beaconClass.getMethod("getStoragePower");
            beaconSetStoragePower = beaconClass.getMethod("setStoragePower", float.class);

            Class<?> powerProviderClass = Class.forName(
                    "com.github.tartaricacid.touhoulittlemaid.capability.PowerCapabilityProvider");
            powerCapability = powerProviderClass.getField("POWER_CAP").get(null);

            Class<?> powerClass = Class.forName(
                    "com.github.tartaricacid.touhoulittlemaid.capability.PowerCapability");
            powerGet = powerClass.getMethod("get");
            powerSet = powerClass.getMethod("set", float.class);
            maxPlayerPower = powerClass.getField("MAX_POWER").getFloat(null);

            entityGetCapability = net.minecraft.world.entity.Entity.class.getMethod(
                    "getCapability", Capability.class, Direction.class);

            Class<?> initRecipesClass = Class.forName(
                    "com.github.tartaricacid.touhoulittlemaid.init.InitRecipes");
            altarRecipeType = initRecipesClass.getField("ALTAR_CRAFTING").get(null);
            altarRecipeInventoryClass = Class.forName(
                    "com.github.tartaricacid.touhoulittlemaid.inventory.AltarRecipeInventory");

            powerReflectionReady = beaconClass != null && powerCapability != null
                    && altarRecipeType != null && altarRecipeInventoryClass != null;
        } catch (Throwable e) {
            LOGGER.warn("Shrine lamp reflection unavailable: {}", e.toString());
            powerReflectionReady = false;
        }
        return powerReflectionReady;
    }

    /** Client-side check: is the player near an altar with valid pedestal positions? */
    public static boolean getNearestAltarPosition(Player player) {
        return findNearestAltar(player) != null;
    }

    /**
     * Server-side: place the recipe ingredients onto the nearest altar's pedestals and trigger
     * the craft. Each entry of {@code ingredientGroups} is one recipe slot and contains all
     * acceptable item alternatives for that slot (for tag based ingredients).
     */
    public static void placeItemsOnNearestAltar(ServerPlayer player, List<List<ItemStack>> ingredientGroups) {
        if (ingredientGroups == null || ingredientGroups.isEmpty()) return;
        if (!ensureReflection()) return;

        Object altarTE = findNearestAltar(player);
        if (altarTE == null) {
            player.displayClientMessage(
                    Component.translatable("tlmjei.message.altar_not_found"), true);
            return;
        }

        List<BlockPos> pedestals = getPedestalPositions(altarTE);
        if (pedestals == null || pedestals.size() != 6) {
            player.displayClientMessage(
                    Component.translatable("tlmjei.message.altar_incomplete"), true);
            return;
        }

        Level level = player.level();
        for (BlockPos pedestal : pedestals) {
            markPlaceable(level, pedestal);
        }

        // Item types accepted by every recipe slot. JEI always provides 6 input slots, but some
        // altar recipes only use 2-3 ingredients, so empty slots are optional and must not be
        // reported as missing.
        List<Set<Item>> wantedGroups = buildWantedGroups(ingredientGroups);

        ItemStack[] existing = new ItemStack[6];
        boolean[] usedPedestal = new boolean[6];
        boolean[] satisfiedGroup = new boolean[6];
        for (int i = 0; i < 6; i++) {
            existing[i] = getPedestalItem(level, pedestals.get(i));
        }

        // Keep items that are already on a pedestal when the recipe accepts them.
        int placed = matchExistingPedestals(wantedGroups, existing, usedPedestal, satisfiedGroup);

        // Fetch the remaining ingredients from the inventory, backpacks, Curios or containers.
        int missing = 0;
        for (int group = 0; group < 6; group++) {
            if (satisfiedGroup[group]) continue;

            Set<Item> wanted = wantedGroups.get(group);
            if (wanted.isEmpty()) continue; // optional slot of a recipe with fewer ingredients

            int pedestalIndex = firstFreePedestal(usedPedestal);
            if (pedestalIndex < 0) {
                missing++;
                continue;
            }

            ItemStack taken = BackpackCompat.tryTakeAnyItem(player, wanted);
            if (taken.isEmpty()) {
                missing++;
                continue;
            }

            if (placeItemOnPedestal(level, pedestals.get(pedestalIndex), taken, player)) {
                usedPedestal[pedestalIndex] = true;
                placed++;
            } else {
                giveBack(player, taken);
                missing++;
            }
        }

        // Craft once every required slot is satisfied. Recipes with fewer than 6 ingredients
        // only need their non-empty slots filled. Missing materials are surfaced by JEI's
        // disabled + button and red slot highlights, so no extra chat/action-bar message.
        if (placed > 0 && missing == 0) {
            triggerAltarCraft(level, altarTE, player);
        }
    }

    /**
     * Server-side dry run used for the JEI button state. Mirrors
     * {@link #placeItemsOnNearestAltar(ServerPlayer, List)}: items already on the pedestals
     * are reused first, then the remaining slots are matched against the same sources the
     * real transfer searches. Returns the indexes of the recipe slots that cannot be filled.
     */
    public static List<Integer> findMissingIngredientGroups(ServerPlayer player,
                                                            List<List<ItemStack>> ingredientGroups) {
        List<Integer> missing = new ArrayList<>();
        if (player == null || ingredientGroups == null || ingredientGroups.isEmpty()) return missing;
        if (!ensureReflection()) return missing;

        Object altarTE = findNearestAltar(player);
        Level level = player.level();
        List<BlockPos> pedestals = altarTE == null ? null : getPedestalPositions(altarTE);
        boolean hasPedestals = pedestals != null && pedestals.size() == 6;

        ItemStack[] existing = new ItemStack[6];
        for (int i = 0; i < 6; i++) {
            existing[i] = hasPedestals ? getPedestalItem(level, pedestals.get(i)) : ItemStack.EMPTY;
        }

        Map<Item, Integer> pool = new LinkedHashMap<>();
        BackpackCompat.collectAvailableItems(player, pool);
        return dryRun(ingredientGroups, existing, hasPedestals, pool);
    }

    /**
     * Batch version used by the JEI recipe preload. The relatively expensive nearby-container
     * scan is performed once and every recipe is then matched against the same snapshot.
     */
    public static List<List<Integer>> findMissingIngredientGroupsBatch(
            ServerPlayer player, List<List<List<ItemStack>>> recipes) {
        List<List<Integer>> results = new ArrayList<>(recipes.size());
        if (player == null || !ensureReflection()) {
            for (int i = 0; i < recipes.size(); i++) results.add(new ArrayList<>());
            return results;
        }

        Object altarTE = findNearestAltar(player);
        Level level = player.level();
        List<BlockPos> pedestals = altarTE == null ? null : getPedestalPositions(altarTE);
        boolean hasPedestals = pedestals != null && pedestals.size() == 6;

        ItemStack[] existing = new ItemStack[6];
        for (int i = 0; i < 6; i++) {
            existing[i] = hasPedestals ? getPedestalItem(level, pedestals.get(i)) : ItemStack.EMPTY;
        }

        Map<Item, Integer> basePool = new LinkedHashMap<>();
        BackpackCompat.collectAvailableItems(player, basePool);

        for (List<List<ItemStack>> groups : recipes) {
            results.add(dryRun(groups, existing, hasPedestals, basePool));
        }
        return results;
    }

    /** Match one recipe against an already collected inventory snapshot. */
    private static List<Integer> dryRun(List<List<ItemStack>> ingredientGroups,
                                        ItemStack[] existing, boolean hasPedestals,
                                        Map<Item, Integer> basePool) {
        List<Integer> missing = new ArrayList<>();
        List<Set<Item>> wantedGroups = buildWantedGroups(ingredientGroups);
        boolean[] usedPedestal = new boolean[6];
        boolean[] satisfiedGroup = new boolean[6];
        matchExistingPedestals(wantedGroups, existing, usedPedestal, satisfiedGroup);

        // Each recipe must see the full pool; consuming one recipe must not affect the next one.
        Map<Item, Integer> pool = new LinkedHashMap<>(basePool);

        for (int group = 0; group < 6; group++) {
            if (satisfiedGroup[group]) continue;

            Set<Item> wanted = wantedGroups.get(group);
            if (wanted.isEmpty()) continue; // optional slot of a recipe with fewer ingredients

            int pedestalIndex = hasPedestals ? firstFreePedestal(usedPedestal) : 0;
            if (hasPedestals && pedestalIndex < 0) {
                missing.add(group);
                continue;
            }

            if (!consumeFromPool(pool, wanted)) {
                missing.add(group);
                continue;
            }

            if (hasPedestals) {
                usedPedestal[pedestalIndex] = true;
            }
        }
        return missing;
    }

    /** Build the six ingredient groups (one accepted-item set per altar slot). */
    private static List<Set<Item>> buildWantedGroups(List<List<ItemStack>> ingredientGroups) {
        List<Set<Item>> wantedGroups = new ArrayList<>(6);
        for (int i = 0; i < 6; i++) {
            Set<Item> wanted = new LinkedHashSet<>();
            if (i < ingredientGroups.size()) {
                for (ItemStack candidate : ingredientGroups.get(i)) {
                    if (candidate != null && !candidate.isEmpty()) {
                        wanted.add(candidate.getItem());
                    }
                }
            }
            wantedGroups.add(wanted);
        }
        return wantedGroups;
    }

    /**
     * Match recipe slots against items already lying on pedestals. Returns the number of
     * reused pedestals (also added to the caller's placement counter).
     */
    private static int matchExistingPedestals(List<Set<Item>> wantedGroups, ItemStack[] existing,
                                              boolean[] usedPedestal, boolean[] satisfiedGroup) {
        int matched = 0;
        for (int group = 0; group < 6; group++) {
            Set<Item> wanted = wantedGroups.get(group);
            if (wanted.isEmpty()) continue;
            for (int p = 0; p < 6; p++) {
                if (usedPedestal[p] || existing[p].isEmpty()) continue;
                if (wanted.contains(existing[p].getItem())) {
                    usedPedestal[p] = true;
                    satisfiedGroup[group] = true;
                    matched++;
                    break;
                }
            }
        }
        return matched;
    }

    /** Consume one matching item from the dry-run pool, mirroring the extraction order. */
    private static boolean consumeFromPool(Map<Item, Integer> pool, Set<Item> wanted) {
        var iterator = pool.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue() <= 0 || !wanted.contains(entry.getKey())) continue;
            if (entry.getValue() <= 1) {
                iterator.remove();
            } else {
                entry.setValue(entry.getValue() - 1);
            }
            return true;
        }
        return false;
    }

    /**
     * Invoke TLM's altar craft logic on a pedestal tile entity. Passing a pedestal makes TLM's
     * own centre calculation work because all altar tile entities share the same position list.
     * For non-pedestal tile entities (legacy worlds) the centre list is patched temporarily.
     */
    private static void triggerAltarCraft(Level level, Object altarTE, ServerPlayer player) {
        Object originalPosList = null;
        boolean patchedPosList = false;
        try {
            BlockEntity be = (BlockEntity) altarTE;
            BlockPos pos = be.getBlockPos();
            Object block = level.getBlockState(pos).getBlock();
            if (!blockAltarClass.isInstance(block)) return;

            // Cover a power shortfall from nearby shrine lamps before TLM checks and deducts power.
            applyShrineLampPower(level, altarTE, player);

            List<BlockPos> pedestals = getPedestalPositions(altarTE);
            if (pedestals != null && !pedestals.contains(pos)) {
                // Fallback for a non-pedestal tile entity: make getCentrePos() resolve to the
                // actual structure centre. The original list is restored afterwards.
                int cx = 0, cz = 0;
                for (BlockPos p : pedestals) {
                    cx += p.getX();
                    cz += p.getZ();
                }
                cx /= pedestals.size();
                cz /= pedestals.size();
                int centreY = pos.getY() - 2;

                Object newPosList = posListClass.getConstructor().newInstance();
                Method addMethod = posListClass.getMethod("add", BlockPos.class);
                for (int i = 0; i < 8; i++) {
                    addMethod.invoke(newPosList, new BlockPos(cx, centreY, cz));
                }
                originalPosList = blockPosListField.get(altarTE);
                blockPosListField.set(altarTE, newPosList);
                patchedPosList = true;
            }

            altarCraftMethod.invoke(block, level, teClass.cast(altarTE), player);
            LOGGER.debug("altarCraft invoked at {}", pos);
        } catch (Throwable t) {
            LOGGER.warn("altarCraft failed: {}", t.toString());
        } finally {
            if (patchedPosList) {
                try {
                    blockPosListField.set(altarTE, originalPosList);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * TLM always deducts the full recipe power from the player, so a shrine lamp shortfall is
     * handled by topping the player's capability up before the craft. The net result is that the
     * player's own P points are consumed first and nearby shrine lamps only pay the difference.
     */
    private static void applyShrineLampPower(Level level, Object altarTE, ServerPlayer player) {
        if (!ensurePowerReflection()) return;

        float cost = getRecipePowerCost(level, altarTE);
        if (cost <= 1.0e-4f) return;

        Object power = getPlayerPower(player);
        if (power == null) return;

        float playerPower = getCapabilityPower(power);
        if (playerPower + 1.0e-4f >= cost) return; // the player can pay on their own
        if (cost > maxPlayerPower + 1.0e-4f) return; // cannot be crafted even with lamp power

        float shortfall = cost - playerPower;
        if (!takePowerFromShrineLamps(player, shortfall)) return;

        try {
            // Set the exact recipe cost to avoid float rounding leaving the player just below it.
            powerSet.invoke(power, cost);
            LOGGER.debug("Shrine lamps covered {} P of an altar craft costing {} P", shortfall, cost);
        } catch (Throwable t) {
            LOGGER.warn("Failed to add shrine lamp power to player: {}", t.toString());
        }
    }

    /**
     * Resolve the power cost of the altar recipe currently placed on the pedestals, using the
     * same crafting input TLM builds when it runs the craft.
     */
    private static float getRecipePowerCost(Level level, Object altarTE) {
        if (!ensurePowerReflection()) return 0.0f;
        try {
            List<BlockPos> pedestals = getPedestalPositions(altarTE);
            if (pedestals == null || pedestals.size() != 6) return 0.0f;

            Container inventory = (Container) altarRecipeInventoryClass
                    .getConstructor().newInstance();
            for (int i = 0; i < 6; i++) {
                inventory.setItem(i, getPedestalItem(level, pedestals.get(i)));
            }
            if (inventory.isEmpty()) return 0.0f;

            @SuppressWarnings({"rawtypes", "unchecked"})
            Optional<?> found = level.getRecipeManager()
                    .getRecipeFor((RecipeType) altarRecipeType, inventory, level);
            if (found.isEmpty()) return 0.0f;

            Object recipe = found.get();
            return ((Number) recipe.getClass().getMethod("getPowerCost").invoke(recipe)).floatValue();
        } catch (Throwable t) {
            LOGGER.warn("Failed to resolve altar recipe power: {}", t.toString());
            return 0.0f;
        }
    }

    /**
     * Drain the shortfall from the nearest shrine lamps first. If the lamps in range cannot cover
     * the whole shortfall, nothing is transferred so the original TLM behavior is preserved.
     */
    private static boolean takePowerFromShrineLamps(ServerPlayer player, float needed) {
        double range = TlmJeiConfig.shrineLampRange;
        if (range <= 0.0 || needed <= 0.0f) return false;

        Level level = player.level();
        BlockPos playerPos = player.blockPosition();
        int search = (int) Math.ceil(range);
        double rangeSqr = range * range;

        List<BlockEntity> lamps = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                playerPos.getX() - search, playerPos.getY() - search, playerPos.getZ() - search,
                playerPos.getX() + search, playerPos.getY() + search, playerPos.getZ() + search)) {
            BlockEntity be = level.getBlockEntity(pos);
            if (!beaconClass.isInstance(be)) continue;
            if (be.getBlockPos().distSqr(playerPos) > rangeSqr) continue;
            lamps.add(be);
        }
        if (lamps.isEmpty()) return false;

        lamps.sort(Comparator.comparingDouble(be -> be.getBlockPos().distSqr(playerPos)));

        double total = 0.0;
        for (BlockEntity lamp : lamps) {
            total += getLampPower(lamp);
        }
        if (total + 1.0e-4 < needed) return false; // keep the lamps untouched if they cannot help

        float remaining = needed;
        for (BlockEntity lamp : lamps) {
            if (remaining <= 1.0e-4f) break;
            float stored = getLampPower(lamp);
            float take = Math.min(stored, remaining);
            if (take <= 0.0f) continue;
            if (setLampPower(lamp, Math.max(0.0f, stored - take))) {
                remaining -= take;
            }
        }

        if (remaining > 1.0e-3f) {
            LOGGER.warn("Shrine lamps could not supply the full {} P shortfall", needed);
            return false;
        }
        return true;
    }

    @Nullable
    private static Object getPlayerPower(ServerPlayer player) {
        try {
            Object optional = entityGetCapability.invoke(player, powerCapability, null);
            return unwrapPresent(optional);
        } catch (Throwable t) {
            LOGGER.warn("Failed to read player power: {}", t.toString());
            return null;
        }
    }

    private static float getCapabilityPower(Object power) {
        try {
            return ((Number) powerGet.invoke(power)).floatValue();
        } catch (Throwable t) {
            return 0.0f;
        }
    }

    private static float getLampPower(BlockEntity lamp) {
        try {
            return ((Number) beaconGetStoragePower.invoke(lamp)).floatValue();
        } catch (Throwable t) {
            return 0.0f;
        }
    }

    private static boolean setLampPower(BlockEntity lamp, float power) {
        try {
            beaconSetStoragePower.invoke(lamp, power);
            return true;
        } catch (Throwable t) {
            LOGGER.warn("Failed to update shrine lamp power: {}", t.toString());
            return false;
        }
    }

    /**
     * Unwraps the Forge {@code LazyOptional} returned by entity capabilities and plain
     * {@link java.util.Optional} without a compile-time dependency on the former.
     */
    @Nullable
    private static Object unwrapPresent(@Nullable Object optionalLike) {
        if (optionalLike == null) return null;
        if (optionalLike instanceof Optional<?> optional) {
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

    /* ---- internal helpers ---- */

    /** Find the nearest pedestal tile entity of an altar within the configured range. */
    @Nullable
    private static Object findNearestAltar(Player player) {
        if (!ensureReflection()) return null;

        Level level = player.level();
        int range = (int) Math.ceil(TlmJeiConfig.altarSearchRange);
        if (range <= 0) return null;
        BlockPos playerPos = player.blockPosition();

        Object bestPedestal = null;
        double bestPedestalDistance = Double.MAX_VALUE;
        Object bestFallback = null;
        double bestFallbackDistance = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.betweenClosed(
                playerPos.getX() - range, playerPos.getY() - range, playerPos.getZ() - range,
                playerPos.getX() + range, playerPos.getY() + range, playerPos.getZ() + range)) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null || !teClass.isInstance(be)) continue;

            List<BlockPos> pedestals = getPedestalPositions(be);
            if (pedestals == null || pedestals.size() != 6) continue;

            double distance = be.getBlockPos().distSqr(playerPos);
            if (pedestals.contains(be.getBlockPos())) {
                // Only pedestals are used to trigger the craft so the centre math is correct.
                if (distance < bestPedestalDistance) {
                    bestPedestalDistance = distance;
                    bestPedestal = be;
                }
            } else if (distance < bestFallbackDistance) {
                bestFallbackDistance = distance;
                bestFallback = be;
            }
        }
        return bestPedestal != null ? bestPedestal : bestFallback;
    }

    /** Get the 6 pedestal BlockPos from the altar tile entity. */
    @SuppressWarnings("unchecked")
    @Nullable
    private static List<BlockPos> getPedestalPositions(Object altarTE) {
        try {
            Object posListData = getCanPlaceListMethod.invoke(altarTE);
            if (posListData == null) return null;
            return (List<BlockPos>) posListGetData.invoke(posListData);
        } catch (Exception e) {
            return null;
        }
    }

    private static void markPlaceable(Level level, BlockPos pos) {
        try {
            BlockEntity be = level.getBlockEntity(pos);
            if (teClass.isInstance(be)) {
                canPlaceItemField.setBoolean(be, true);
            }
        } catch (Exception ignored) {
        }
    }

    private static ItemStack getPedestalItem(Level level, BlockPos pos) {
        try {
            BlockEntity be = level.getBlockEntity(pos);
            if (!teClass.isInstance(be)) return ItemStack.EMPTY;
            Object handler = handlerField.get(be);
            if (handler instanceof IItemHandler itemHandler) {
                return itemHandler.getStackInSlot(0).copy();
            }
        } catch (Exception ignored) {
        }
        return ItemStack.EMPTY;
    }

    private static int firstFreePedestal(boolean[] used) {
        for (int i = 0; i < used.length; i++) {
            if (!used[i]) return i;
        }
        return -1;
    }

    /** Place a single item on a pedestal, returning any previous item to the player. */
    private static boolean placeItemOnPedestal(Level level, BlockPos pos, ItemStack stack, Player player) {
        try {
            BlockEntity be = level.getBlockEntity(pos);
            if (!teClass.isInstance(be)) return false;
            Object handlerObject = handlerField.get(be);
            if (!(handlerObject instanceof IItemHandlerModifiable handler)) return false;

            canPlaceItemField.setBoolean(be, true);

            ItemStack existing = handler.getStackInSlot(0).copy();
            if (!existing.isEmpty()) {
                handler.setStackInSlot(0, ItemStack.EMPTY);
                giveBack(player, existing);
            }

            handler.setStackInSlot(0, stack.copyWithCount(1));
            if (stack.getCount() > 1) {
                giveBack(player, stack.copyWithCount(stack.getCount() - 1));
            }

            // Force client sync so items appear visually on pedestals.
            level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
            be.setChanged();
            return true;
        } catch (Exception e) {
            LOGGER.warn("Failed to place item on pedestal at {}: {}", pos, e.toString());
            return false;
        }
    }

    private static void giveBack(Player player, ItemStack stack) {
        if (stack.isEmpty()) return;
        ItemStack copy = stack.copy();
        if (!player.getInventory().add(copy)) {
            player.drop(copy, false);
        }
    }
}
