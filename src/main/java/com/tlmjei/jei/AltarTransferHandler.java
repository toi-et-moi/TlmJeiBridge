package com.tlmjei.jei;

import com.tlmjei.network.NetworkHandler;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class AltarTransferHandler implements IRecipeTransferHandler<AbstractContainerMenu, Object> {

    private static final int MAX_CANDIDATES_PER_SLOT = 128;

    private final Class<? extends AbstractContainerMenu> containerClass;

    public AltarTransferHandler(Class<? extends AbstractContainerMenu> containerClass) {
        this.containerClass = containerClass;
    }

    @Override
    public Class<? extends AbstractContainerMenu> getContainerClass() {
        return containerClass;
    }

    @Override
    public Optional<MenuType<AbstractContainerMenu>> getMenuType() {
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

        List<List<ItemStack>> groups = new ArrayList<>();
        for (IRecipeSlotView slotView : recipeSlots.getSlotViews()) {
            if (slotView.getRole() != RecipeIngredientRole.INPUT) continue;

            Set<Item> seen = new HashSet<>();
            List<ItemStack> candidates = new ArrayList<>();
            slotView.getItemStacks()
                    .filter(stack -> !stack.isEmpty())
                    .filter(stack -> seen.add(stack.getItem()))
                    .limit(MAX_CANDIDATES_PER_SLOT)
                    .forEach(stack -> candidates.add(stack.copy()));
            groups.add(candidates);
        }

        if (groups.isEmpty()) return null;

        if (player.level().isClientSide) {
            var packet = new NetworkHandler.CPacketAltarTransfer(groups);
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(packet);
        }
        return null;
    }
}
