package com.tlmjei.jei;

import com.tlmjei.network.NetworkHandler;
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

    @Override
    public Class<? extends AbstractContainerMenu> getContainerClass() {
        return net.minecraft.world.inventory.InventoryMenu.class;
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
        for (var slotView : recipeSlots.getSlotViews()) {
            var displayed = slotView.getDisplayedItemStack();
            inputs.add(displayed.orElse(ItemStack.EMPTY).copy());
        }

        // Send from client to server via NeoForge payload networking
        if (player.level().isClientSide) {
            var packet = new NetworkHandler.CPacketAltarTransfer(inputs);
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(packet);
        }
        return null;
    }
}
