package com.tlmjei.jei;

import com.tlmjei.network.CPacketAltarAvailabilityBatch;
import com.tlmjei.util.AltarHelper;
import mezz.jei.api.recipe.IRecipeManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Preloads altar recipe availability before JEI builds its recipe list.
 *
 * <p>JEI's craftable-first sorting reads the transfer handler's missing-count hint once, while
 * the recipe category is being opened. Our availability comes from the server (so backpacks,
 * Curios and nearby containers count), which is asynchronous. This class asks for a bulk
 * snapshot when a container screen is opened near an altar, so by the time the player opens the
 * altar category the answers are already cached and JEI can put craftable recipes first.</p>
 */
public final class AltarAvailabilityPrefetcher {

    /** Matches the transfer handler's per-slot candidate cap. */
    private static final int MAX_CANDIDATES_PER_SLOT = 128;
    private static final long MIN_INTERVAL_MS = 500L;

    private static final Map<Class<?>, Method> INPUT_METHODS = new HashMap<>();
    private static boolean registered;
    private static long lastPrefetchAt;
    private static AbstractContainerMenu lastContainer;

    private AltarAvailabilityPrefetcher() {
    }

    /** Called from the JEI plugin once JEI's runtime (and therefore the client) is available. */
    public static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(
                ScreenEvent.Init.Post.class, AltarAvailabilityPrefetcher::onScreenInit);
    }

    public static void onScreenInit(ScreenEvent.Init.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) return;

        Screen screen = event.getScreen();
        if (!(screen instanceof AbstractContainerScreen<?>) && !isJeiRecipesGui(screen)) {
            return;
        }

        AbstractContainerMenu container = player.containerMenu;
        if (container == null) return;
        if (!AltarHelper.getNearestAltarPosition(player)) return;

        long now = System.currentTimeMillis();
        if (container == lastContainer && now - lastPrefetchAt < MIN_INTERVAL_MS) {
            return;
        }

        IRecipeManager recipeManager = TLMJEIPlugin.getRecipeManager();
        if (recipeManager == null) return;

        List<PrefetchedRecipe> recipes = collectRecipes(recipeManager);
        if (recipes.isEmpty()) return;

        lastPrefetchAt = now;
        lastContainer = container;

        int[] recipeKeys = new int[recipes.size()];
        for (int i = 0; i < recipes.size(); i++) {
            recipeKeys[i] = recipes.get(i).recipeKey;
        }
        int[] requestIds = TransferAvailabilityCache.beginPrefetch(container, recipeKeys);

        List<CPacketAltarAvailabilityBatch.Query> queries = new ArrayList<>(recipes.size());
        for (int i = 0; i < recipes.size(); i++) {
            queries.add(new CPacketAltarAvailabilityBatch.Query(
                    recipeKeys[i],
                    requestIds[i],
                    toItemGroups(recipes.get(i).groups)));
        }
        PacketDistributor.sendToServer(new CPacketAltarAvailabilityBatch(queries));
    }

    private static List<PrefetchedRecipe> collectRecipes(IRecipeManager recipeManager) {
        List<PrefetchedRecipe> recipes = new ArrayList<>();
        try {
            recipeManager.createRecipeLookup(TLMJEIPlugin.ALTAR_TYPE).get().forEach(recipe -> {
                if (recipes.size() >= CPacketAltarAvailabilityBatch.MAX_RECIPES) return;
                List<List<ItemStack>> groups = inputGroups(recipe);
                if (groups == null) return;
                recipes.add(new PrefetchedRecipe(
                        TransferAvailabilityCache.recipeKey(groups), groups));
            });
        } catch (Throwable ignored) {
            // JEI was reloaded or the TLM category is unavailable; the normal per-recipe
            // availability queries still keep the button state correct.
        }
        return recipes;
    }

    /**
     * Read the TLM altar recipe inputs without a compile-time dependency on TLM. The JEI
     * category adds exactly six input slots, so the same six groups are built here and the
     * resulting recipe key matches the transfer handler's key.
     */
    @Nullable
    private static List<List<ItemStack>> inputGroups(Object recipe) {
        try {
            Method method = INPUT_METHODS.computeIfAbsent(recipe.getClass(), clazz -> {
                try {
                    return clazz.getMethod("getInputs");
                } catch (NoSuchMethodException e) {
                    return null;
                }
            });
            if (method == null) return null;

            Object value = method.invoke(recipe);
            if (!(value instanceof List<?> inputs)) return null;

            List<List<ItemStack>> groups = new ArrayList<>(6);
            for (int i = 0; i < 6; i++) {
                List<ItemStack> candidates = new ArrayList<>();
                if (i < inputs.size() && inputs.get(i) instanceof List<?> input) {
                    Set<Item> seen = new HashSet<>();
                    for (Object element : input) {
                        if (!(element instanceof ItemStack stack) || stack.isEmpty()) continue;
                        if (!seen.add(stack.getItem())) continue;
                        candidates.add(stack.copy());
                        if (candidates.size() >= MAX_CANDIDATES_PER_SLOT) break;
                    }
                }
                groups.add(candidates);
            }
            return groups;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static List<List<Item>> toItemGroups(List<List<ItemStack>> groups) {
        List<List<Item>> result = new ArrayList<>(groups.size());
        for (List<ItemStack> group : groups) {
            List<Item> items = new ArrayList<>(group.size());
            for (ItemStack stack : group) {
                if (stack != null && !stack.isEmpty()) {
                    items.add(stack.getItem());
                }
            }
            result.add(items);
        }
        return result;
    }

    private static boolean isJeiRecipesGui(Screen screen) {
        return screen.getClass().getName().equals("mezz.jei.gui.recipes.RecipesGui");
    }

    private record PrefetchedRecipe(int recipeKey, List<List<ItemStack>> groups) {
    }
}
