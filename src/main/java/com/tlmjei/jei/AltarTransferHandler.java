package com.tlmjei.jei;

import com.tlmjei.network.CPacketAltarTransfer;
import com.tlmjei.network.NetworkHandler;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class AltarTransferHandler implements IRecipeTransferHandler<AbstractContainerMenu, Object> {

    private final Class<? extends AbstractContainerMenu> containerClass;

    public AltarTransferHandler(Class<? extends AbstractContainerMenu> containerClass) {
        this.containerClass = containerClass;
    }

    @Override
    public Class<? extends AbstractContainerMenu> getContainerClass() {
        return containerClass;
    }

    @Override
    public Optional<net.minecraft.world.inventory.MenuType<AbstractContainerMenu>> getMenuType() {
        return Optional.empty();
    }

    @Override
    public RecipeType<Object> getRecipeType() {
        return TLMJEIPlugin.ALTAR_TYPE;
    }

    @Nullable
    @Override
    public IRecipeTransferError transferRecipe(AbstractContainerMenu container, Object recipe,
                                                IRecipeSlotsView recipeSlots, Player player,
                                                boolean maxTransfer, boolean doTransfer) {
        if (!doTransfer) {
            // Always show the button; validation happens server-side on click.
            return null;
        }

        List<ItemStack> inputs = new ArrayList<>();
        for (IRecipeSlotView slotView : recipeSlots.getSlotViews()) {
            Optional<ItemStack> displayed = slotView.getDisplayedItemStack();
            inputs.add(displayed.orElse(ItemStack.EMPTY).copy());
        }

        NetworkHandler.CHANNEL.sendToServer(new CPacketAltarTransfer(inputs));
        return null;
    }
}
