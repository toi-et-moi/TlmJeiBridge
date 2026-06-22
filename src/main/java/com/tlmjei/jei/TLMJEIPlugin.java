package com.tlmjei.jei;

import com.tlmjei.TLMJEIBridge;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;

import java.lang.reflect.Field;

@JeiPlugin
public class TLMJEIPlugin implements IModPlugin {
    private static final ResourceLocation UID = ResourceLocation.parse(TLMJEIBridge.MODID + ":main");

    static final RecipeType<Object> ALTAR_TYPE = resolveAltarRecipeType();

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
        registration.addRecipeTransferHandler(
                new AltarTransferHandler(), ALTAR_TYPE);
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
