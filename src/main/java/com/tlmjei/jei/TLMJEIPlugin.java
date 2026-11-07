package com.tlmjei.jei;

import com.tlmjei.TLMJEIBridge;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;

@JeiPlugin
public class TLMJEIPlugin implements IModPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger(TLMJEIBridge.MODID);

    @SuppressWarnings("deprecation")
    private static final ResourceLocation UID = new ResourceLocation(TLMJEIBridge.MODID, "main");

    static final RecipeType<Object> ALTAR_TYPE = resolveAltarRecipeType();

    /** Menus that can be open while the JEI "+" button must stay available. */
    private static final String[] L2_MENUS = {
            "dev.xkmc.l2backpack.content.backpack.BackpackMenu",
            "dev.xkmc.l2backpack.content.common.BaseBagMenu",
            "dev.xkmc.l2backpack.content.quickswap.armorswap.ArmorBagMenu",
            "dev.xkmc.l2backpack.content.quickswap.armorswap.ArmorSetBagMenu",
            "dev.xkmc.l2backpack.content.quickswap.handswap.HandswapMenu",
            "dev.xkmc.l2backpack.content.quickswap.merged.EnderSwitchMenu",
            "dev.xkmc.l2backpack.content.quickswap.merged.MultiSwitchMenu",
            "dev.xkmc.l2backpack.content.quickswap.quiver.QuiverMenu",
            "dev.xkmc.l2backpack.content.quickswap.scabbard.ScabbardMenu",
            "dev.xkmc.l2backpack.content.remote.worldchest.WorldChestContainer"
    };

    private static final String SB_BACKPACK_CONTAINER =
            "net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer";

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
        // Player inventory, crafting table and chests (also covers containers opened via
        // the Sophisticated Backpacks upgrade / L2 interfaces that reuse a chest menu).
        registration.addRecipeTransferHandler(
                new AltarTransferHandler(InventoryMenu.class), ALTAR_TYPE);
        registration.addRecipeTransferHandler(
                new AltarTransferHandler(CraftingMenu.class), ALTAR_TYPE);
        registration.addRecipeTransferHandler(
                new AltarTransferHandler(ChestMenu.class), ALTAR_TYPE);

        // Backpack mods: their own menu classes need a dedicated registration, otherwise JEI
        // hides the transfer button on those screens.
        if (ModList.get().isLoaded("l2backpack")) {
            for (String className : L2_MENUS) {
                registerCompatMenu(registration, className);
            }
        }
        if (ModList.get().isLoaded("sophisticatedbackpacks")) {
            registerCompatMenu(registration, SB_BACKPACK_CONTAINER);
        }
    }

    @SuppressWarnings("unchecked")
    private static void registerCompatMenu(IRecipeTransferRegistration registration, String className) {
        try {
            // initialize=false: only check that the optional mod's menu class exists.
            Class<?> clazz = Class.forName(className, false, TLMJEIPlugin.class.getClassLoader());
            if (!AbstractContainerMenu.class.isAssignableFrom(clazz)) return;
            registration.addRecipeTransferHandler(
                    new AltarTransferHandler((Class<? extends AbstractContainerMenu>) clazz),
                    ALTAR_TYPE);
        } catch (Throwable t) {
            // The mod version may not have this menu class; skip it.
            LOGGER.debug("Skipping JEI transfer handler for {}: {}", className, t.toString());
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
