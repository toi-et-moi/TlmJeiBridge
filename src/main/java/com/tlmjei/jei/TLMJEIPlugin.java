package com.tlmjei.jei;

import com.tlmjei.TLMJEIBridge;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.neoforged.fml.loading.FMLEnvironment;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

@JeiPlugin
public class TLMJEIPlugin implements IModPlugin {
    private static final ResourceLocation UID = ResourceLocation.parse(TLMJEIBridge.MODID + ":main");

    static final RecipeType<Object> ALTAR_TYPE = resolveAltarRecipeType();

    private static IRecipeManager recipeManager;

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        recipeManager = jeiRuntime.getRecipeManager();
        // The prefetcher references client-only classes; never touch it on a dedicated server.
        if (FMLEnvironment.dist.isClient()) {
            AltarAvailabilityPrefetcher.register();
        }
    }

    /** Used by the client-side recipe preload; null until JEI's runtime is available. */
    public static IRecipeManager getRecipeManager() {
        return recipeManager;
    }

    @Override
    public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
        IRecipeTransferHandlerHelper transferHelper = registration.getTransferHelper();

        // JEI resolves transfer handlers by the exact container class, so register one handler
        // per known container screen. This makes the "+" button available in container screens
        // of backpack mods (where players usually browse recipes) instead of only in the player
        // inventory screen.
        for (Class<? extends AbstractContainerMenu> containerClass : transferContainerClasses()) {
            registration.addRecipeTransferHandler(
                    new AltarTransferHandler(containerClass, transferHelper), ALTAR_TYPE);
        }
    }

    private static List<Class<? extends AbstractContainerMenu>> transferContainerClasses() {
        List<Class<? extends AbstractContainerMenu>> classes = new ArrayList<>();
        classes.add(InventoryMenu.class);
        classes.add(CraftingMenu.class);
        // Vanilla chest / ender chest UI (the L2 ender backpack opens a plain ChestMenu).
        classes.add(ChestMenu.class);

        // L2Backpack screens (backpack, dimensional storage, quick swap menus).
        addIfPresent(classes, "dev.xkmc.l2backpack.content.backpack.BackpackMenu");
        addIfPresent(classes, "dev.xkmc.l2backpack.content.remote.dimensional.DimensionalContainer");
        addIfPresent(classes, "dev.xkmc.l2backpack.content.quickswap.common.GenericSwapMenu");
        addIfPresent(classes, "dev.xkmc.l2backpack.content.quickswap.set.ArmorSetBagMenu");
        addIfPresent(classes, "dev.xkmc.l2backpack.content.quickswap.handswap.HandswapMenu");

        // Sophisticated Backpacks screen.
        addIfPresent(classes, "net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer");

        return classes;
    }

    private static void addIfPresent(List<Class<? extends AbstractContainerMenu>> classes, String className) {
        try {
            // initialize=false: only check that the optional mod's menu class exists.
            Class<?> clazz = Class.forName(
                    className, false, TLMJEIPlugin.class.getClassLoader());
            if (AbstractContainerMenu.class.isAssignableFrom(clazz)) {
                classes.add(clazz.asSubclass(AbstractContainerMenu.class));
            }
        } catch (Throwable ignored) {
            // Mod not installed - nothing to register for.
        }
    }

    @SuppressWarnings("unchecked")
    private static RecipeType<Object> resolveAltarRecipeType() {
        try {
            Class<?> clazz = Class.forName(
                    "com.github.tartaricacid.touhoulittlemaid.compat.jei.altar.AltarRecipeCategory");
            Field field = clazz.getField("ALTAR");
            return (RecipeType<Object>) field.get(null);
        } catch (Exception e) {
            return RecipeType.create("touhou_little_maid", "altar", Object.class);
        }
    }
}
