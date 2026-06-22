package com.tlmjei.util;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class AltarHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger("tlmjei_altar");

    private static Class<?> teClass;
    private static Method getCanPlaceListMethod;
    private static Method posListGetData;
    private static boolean reflectionReady = false;
    private static boolean reflectionAttempted = false;

    /** Lazily initialize reflection. Retries if TLM classes weren't loaded yet. */
    private static boolean ensureReflection() {
        if (!reflectionAttempted) {
            reflectionAttempted = true;
            try {
                teClass = Class.forName("com.github.tartaricacid.touhoulittlemaid.tileentity.TileEntityAltar");
                getCanPlaceListMethod = teClass.getMethod("getCanPlaceItemPosList");
                Class<?> posListClass = Class.forName("com.github.tartaricacid.touhoulittlemaid.util.PosListData");
                posListGetData = posListClass.getMethod("getData");
                reflectionReady = true;
            } catch (Exception ignored) {
            }
        }
        return reflectionReady;
    }

    /** Client-side check: is the player near an altar with valid pedestal positions? */
    public static boolean getNearestAltarPosition(Player player) {
        return findNearestAltar(player) != null;
    }

    /** Server-side: place items from the list onto the nearest altar's pedestals. */
    public static void placeItemsOnNearestAltar(ServerPlayer player, List<ItemStack> items) {
        if (items.isEmpty()) return;
        if (!ensureReflection()) return;

        Object altarTE = findNearestAltar(player);
        if (altarTE == null) {
            player.displayClientMessage(
                    Component.literal("§c未检测到附近的祭坛"), true);
            return;
        }

        List<BlockPos> pedestals = getPedestalPositions(altarTE);
        if (pedestals == null || pedestals.size() != 6) {
            player.displayClientMessage(
                    Component.literal("§c祭坛结构不完整"), true);
            return;
        }

        Level level = player.level();
        int placed = 0;
        int missing = 0;

        for (int i = 0; i < 6 && i < items.size(); i++) {
            ItemStack needed = items.get(i);
            if (needed.isEmpty()) continue;

            boolean found = false;
            // Try main inventory first
            int slot = findSlotForItem(player, needed);
            if (slot >= 0) {
                ItemStack taken = player.getInventory().removeItem(slot, 1);
                if (!taken.isEmpty()) {
                    placeItemOnPedestal(level, pedestals.get(i), taken, player);
                    placed++;
                    found = true;
                }
            }
            // Fall back to backpack mods
            if (!found) {
                ItemStack taken = com.tlmjei.compat.BackpackCompat.tryTakeItem(player, needed);
                if (!taken.isEmpty()) {
                    placeItemOnPedestal(level, pedestals.get(i), taken, player);
                    placed++;
                    found = true;
                }
            }
            if (!found) missing++;
        }

        if (missing > 0) {
            player.displayClientMessage(
                    Component.literal("§e背包缺少 " + missing + " 种材料"), true);
        }

        if (placed > 0) {
            triggerAltarCraft(level, altarTE, player);
        }
    }

    /** Invoke altarCraft after fixing the blockPosList so that getCentrePos()
     *  calculates the correct center from the 6 pedestal positions.
     *  TLM's getCentrePos() sums X/Z of positions at (centerY - 2) and divides by 8.
     *  We replace blockPosList with 8 copies of the center to get the right result. */
    private static void triggerAltarCraft(Level level, Object altarTE, ServerPlayer player) {
        try {
            Class<?> blockClass = Class.forName("com.github.tartaricacid.touhoulittlemaid.block.BlockAltar");
            BlockPos centerPos = ((BlockEntity) altarTE).getBlockPos();
            Object block = level.getBlockState(centerPos).getBlock();
            if (!blockClass.isInstance(block)) return;

            // Calculate the actual center from pedestal positions
            List<BlockPos> ped = getPedestalPositions(altarTE);
            if (ped == null || ped.size() < 6) return;
            int cx = 0, cz = 0;
            for (BlockPos p : ped) { cx += p.getX(); cz += p.getZ(); }
            cx /= ped.size(); cz /= ped.size();
            int centreY = centerPos.getY() - 2;

            // Replace blockPosList with 8 copies of the centre so getCentrePos /8 yields correct values
            Class<?> posListClass = Class.forName("com.github.tartaricacid.touhoulittlemaid.util.PosListData");
            Object newPosList = posListClass.getConstructor().newInstance();
            Method addMethod = posListClass.getMethod("add", BlockPos.class);
            for (int i = 0; i < 8; i++) {
                addMethod.invoke(newPosList, new BlockPos(cx, centreY, cz));
            }
            Field bpField = teClass.getDeclaredField("blockPosList");
            bpField.setAccessible(true);
            bpField.set(altarTE, newPosList);

            Method craft = blockClass.getDeclaredMethod("altarCraft",
                    Level.class, teClass, Player.class);
            craft.setAccessible(true);
            craft.invoke(block, level, teClass.cast(altarTE), player);
            LOGGER.info("altarCraft completed (center={},{},{})", cx, centreY, cz);
        } catch (Exception e) {
            LOGGER.warn("triggerAltarCraft failed: {}: {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /* ---- internal helpers ---- */

    /** Find the nearest TileEntityAltar within configured range. */
    private static Object findNearestAltar(Player player) {
        if (!ensureReflection()) return null;

        Level level = player.level();
        BlockPos playerPos = player.blockPosition();
        int r = (int) Math.ceil(com.tlmjei.config.TlmJeiConfig.altarSearchRange);

        for (BlockPos pos : BlockPos.betweenClosed(
                playerPos.getX() - r, playerPos.getY() - r, playerPos.getZ() - r,
                playerPos.getX() + r, playerPos.getY() + r, playerPos.getZ() + r)) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null && teClass.isInstance(be) && hasValidPedestals(be)) {
                return be;
            }
        }
        return null;
    }

    /** Check that the altar has 6 valid pedestal positions. */
    private static boolean hasValidPedestals(BlockEntity be) {
        try {
            Object posListData = getCanPlaceListMethod.invoke(be);
            if (posListData == null) return false;
            List<?> positions = (List<?>) posListGetData.invoke(posListData);
            return positions != null && positions.size() == 6;
        } catch (Exception e) {
            return false;
        }
    }

    /** Get the 6 pedestal BlockPos from the altar tile entity. */
    @SuppressWarnings("unchecked")
    private static List<BlockPos> getPedestalPositions(Object altarTE) {
        try {
            Object posListData = getCanPlaceListMethod.invoke(altarTE);
            if (posListData == null) return null;
            return (List<BlockPos>) posListGetData.invoke(posListData);
        } catch (Exception e) {
            return null;
        }
    }

    /** Place an item stack on a pedestal block and mark it as placeable.
     *  Returns any existing item to the player's inventory before replacing. */
    private static void placeItemOnPedestal(Level level, BlockPos pos, ItemStack stack, Player player) {
        try {
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null) {
                LOGGER.warn("Pedestal BE null at {}", pos);
                return;
            }
            if (!teClass.isInstance(be)) {
                LOGGER.warn("Pedestal BE at {} is {}, not TileEntityAltar", pos, be.getClass().getName());
                return;
            }

            // Set canPlaceItem to true so getStorageItem() returns our item (field is private)
            Field canPlaceField = teClass.getDeclaredField("canPlaceItem");
            canPlaceField.setAccessible(true);
            canPlaceField.setBoolean(be, true);

            // Return existing item on the pedestal to the player before replacing
            Object handler = teClass.getField("handler").get(be);
            ItemStack existing = (ItemStack) handler.getClass()
                    .getMethod("getStackInSlot", int.class).invoke(handler, 0);
            if (!existing.isEmpty()) {
                if (!player.getInventory().add(existing)) {
                    player.drop(existing, false);
                }
            }

            // Place new item
            handler.getClass().getMethod("setStackInSlot", int.class, ItemStack.class)
                    .invoke(handler, 0, stack);

            // Force client sync so items appear visually on pedestals
            level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
            be.setChanged();

            LOGGER.info("Placed {} on pedestal at {}", stack.getHoverName().getString(), pos);
        } catch (Exception e) {
            LOGGER.warn("placeItemOnPedestal error at {}: {}", pos, e.getMessage());
        }
    }

    private static int findSlotForItem(Player player, ItemStack stack) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack in = player.getInventory().getItem(i);
            if (!in.isEmpty() && ItemStack.isSameItem(in, stack)) return i;
        }
        return -1;
    }
}
