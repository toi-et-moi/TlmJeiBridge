package com.tlmjei.jei;

import com.tlmjei.network.CPacketAltarAvailability;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-side cache for the JEI transfer button state.
 *
 * <p>JEI asks the transfer handler for an error every client tick, but the ingredients can
 * live in server-only sources (nearby containers, ender/dimensional storage, Curios, ...).
 * The cache therefore sends a throttled availability query to the server and remembers the
 * missing recipe slots from the reply. It contains no JEI classes so it can safely be loaded
 * on a dedicated server, where only the reply packet would reference it.</p>
 */
public final class TransferAvailabilityCache {

    /** Minimum delay between two queries after the open menu changed. */
    private static final long MIN_REQUEST_INTERVAL_MS = 250L;
    /** Idle refresh interval; catches changes in nearby containers and shared storage. */
    private static final long PERIODIC_REFRESH_MS = 2000L;
    /** A reply older than this is ignored, so a broken connection cannot keep the button disabled. */
    private static final long RESULT_TIMEOUT_MS = 5000L;

    private static final Map<Integer, Entry> ENTRIES = new HashMap<>();
    private static int nextRequestId = 1;

    private TransferAvailabilityCache() {
    }

    private static final class Entry {
        int requestId;
        long requestedAt;
        long updatedAt;
        int menuHash;
        List<Integer> missing = List.of();
    }

    /**
     * Build a stable key for the recipe's input groups. Items are singletons, so their identity
     * hash is stable for the lifetime of the game. Item order inside a group is ignored, so the
     * key also matches between JEI's recipe slots and the client-side preload path.
     */
    public static int recipeKey(List<List<ItemStack>> groups) {
        int key = 17;
        for (List<ItemStack> group : groups) {
            int[] ids = new int[group.size()];
            int count = 0;
            for (ItemStack stack : group) {
                if (stack != null && !stack.isEmpty()) {
                    ids[count++] = System.identityHashCode(stack.getItem());
                }
            }
            Arrays.sort(ids, 0, count);

            int groupHash = count;
            int previous = 0;
            for (int i = 0; i < count; i++) {
                if (i == 0 || ids[i] != previous) {
                    groupHash = 31 * groupHash + ids[i];
                    previous = ids[i];
                }
            }
            key = 31 * key + groupHash;
        }
        return key;
    }

    /** Hash of the open menu's visible contents; used to refresh immediately after changes. */
    private static int menuHash(AbstractContainerMenu container) {
        int hash = 31 + container.slots.size();
        for (Slot slot : container.slots) {
            hash = 31 * hash + stackHash(slot.getItem());
        }
        return 31 * hash + stackHash(container.getCarried());
    }

    private static int stackHash(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        return 31 * System.identityHashCode(stack.getItem()) + stack.getCount();
    }

    /**
     * Returns the missing group indexes from the latest server reply, or {@code null} while no
     * reliable answer is available (the button then stays enabled and the server validates the
     * transfer on click).
     */
    @Nullable
    public static List<Integer> getMissing(AbstractContainerMenu container, int recipeKey,
                                           List<List<ItemStack>> groups) {
        long now = System.currentTimeMillis();
        Entry entry = ENTRIES.computeIfAbsent(recipeKey, ignored -> new Entry());
        int menuHash = menuHash(container);

        boolean menuChanged = entry.menuHash != menuHash;
        boolean firstQuery = entry.requestId == 0;
        long interval = (firstQuery || menuChanged) ? MIN_REQUEST_INTERVAL_MS : PERIODIC_REFRESH_MS;

        if (now - entry.requestedAt >= interval) {
            entry.requestId = nextRequestId++;
            entry.requestedAt = now;
            entry.menuHash = menuHash;
            PacketDistributor.sendToServer(
                    new CPacketAltarAvailability(entry.requestId, recipeKey, groups));
        }

        if (entry.requestId == 0 || now - entry.updatedAt > RESULT_TIMEOUT_MS) {
            return null;
        }
        return entry.missing;
    }

    /**
     * Latest fresh reply for this recipe, without sending a query. Used by the pending-error
     * object while JEI is still initializing its sort order.
     */
    @Nullable
    public static List<Integer> peekMissing(int recipeKey) {
        Entry entry = ENTRIES.get(recipeKey);
        if (entry == null || entry.requestId == 0 || entry.updatedAt == 0) return null;
        if (System.currentTimeMillis() - entry.updatedAt > RESULT_TIMEOUT_MS) return null;
        return entry.missing;
    }

    /**
     * Latest known reply regardless of age. JEI computes its recipe order once when a category
     * is opened; a slightly old answer is still a much better hint than "unknown", so sorting
     * uses this value while the button state keeps using {@link #peekMissing(int)}.
     */
    @Nullable
    public static List<Integer> peekMissingForSort(int recipeKey) {
        Entry entry = ENTRIES.get(recipeKey);
        if (entry == null || entry.requestId == 0 || entry.updatedAt == 0) return null;
        return entry.missing;
    }

    /**
     * Reserve a batch of availability request ids before the preload packet is sent. Each entry
     * gets its own request id, so the normal single-recipe replies can still invalidate them.
     */
    public static int[] beginPrefetch(@Nullable AbstractContainerMenu container, int[] recipeKeys) {
        long now = System.currentTimeMillis();
        int hash = container == null ? 0 : menuHash(container);
        int[] requestIds = new int[recipeKeys.length];
        for (int i = 0; i < recipeKeys.length; i++) {
            Entry entry = ENTRIES.computeIfAbsent(recipeKeys[i], ignored -> new Entry());
            entry.requestId = nextRequestId++;
            entry.requestedAt = now;
            entry.menuHash = hash;
            requestIds[i] = entry.requestId;
        }
        return requestIds;
    }

    /** Called on the client when the server answers an availability query. */
    public static void accept(int recipeKey, int requestId, List<Integer> missingGroups) {
        Entry entry = ENTRIES.get(recipeKey);
        if (entry == null || entry.requestId != requestId) return; // outdated reply
        entry.missing = List.copyOf(missingGroups);
        entry.updatedAt = System.currentTimeMillis();
    }

    /** Drop cached state, e.g. when leaving a world. */
    public static void clear() {
        ENTRIES.clear();
    }
}
