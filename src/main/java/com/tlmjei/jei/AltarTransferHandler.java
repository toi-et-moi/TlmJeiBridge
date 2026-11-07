package com.tlmjei.jei;

import com.tlmjei.network.CPacketAltarTransfer;
import com.tlmjei.network.NetworkHandler;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import net.minecraft.network.chat.Component;
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
    private final IRecipeTransferHandlerHelper helper;

    public AltarTransferHandler(Class<? extends AbstractContainerMenu> containerClass,
                                IRecipeTransferHandlerHelper helper) {
        this.containerClass = containerClass;
        this.helper = helper;
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
        List<List<ItemStack>> groups = new ArrayList<>();
        List<IRecipeSlotView> groupSlots = new ArrayList<>();
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
            groupSlots.add(slotView);
        }

        if (groups.isEmpty()) return null;

        if (player.level().isClientSide) {
            // JEI calls this every tick with doTransfer = false. Query the server for the
            // authoritative availability (it can also see containers and remote storage) and
            // let JEI disable the button / highlight missing slots while anything is missing.
            int recipeKey = TransferAvailabilityCache.recipeKey(groups);
            List<Integer> missing = TransferAvailabilityCache.getMissing(container, recipeKey, groups);

            if (missing == null) {
                if (doTransfer) {
                    NetworkHandler.CHANNEL.sendToServer(new CPacketAltarTransfer(groups));
                    return null;
                }
                // Unknown yet. Return an error whose missing-count hint is resolved later, so
                // JEI's craftable-first sorting does not treat every recipe as available while
                // the preload reply is still in flight.
                return new PendingAvailabilityError(recipeKey);
            }

            IRecipeTransferError error = missingError(missing, groupSlots);
            if (doTransfer) {
                if (error != null) return error;
                NetworkHandler.CHANNEL.sendToServer(new CPacketAltarTransfer(groups));
            }
            return error;
        }

        return null;
    }

    /**
     * Placeholder returned while the server availability reply has not arrived yet. JEI's
     * recipe sorter reads {@link #getMissingCountHint()} after it has built the layout list, at
     * which point the preloaded answer is usually available; until then the recipe is treated
     * as missing instead of craftable so it cannot jump to the front of the list by accident.
     */
    private static final class PendingAvailabilityError implements IRecipeTransferError {
        private final int recipeKey;

        private PendingAvailabilityError(int recipeKey) {
            this.recipeKey = recipeKey;
        }

        @Override
        public Type getType() {
            List<Integer> missing = TransferAvailabilityCache.peekMissing(recipeKey);
            return missing != null && !missing.isEmpty() ? Type.USER_FACING : Type.COSMETIC;
        }

        @Override
        public int getMissingCountHint() {
            List<Integer> missing = TransferAvailabilityCache.peekMissingForSort(recipeKey);
            return missing == null ? -1 : missing.size();
        }
    }

    @Nullable
    private IRecipeTransferError missingError(@Nullable List<Integer> missing,
                                              List<IRecipeSlotView> groupSlots) {
        if (missing == null || missing.isEmpty()) return null;

        List<IRecipeSlotView> missingSlots = new ArrayList<>();
        for (int group : missing) {
            if (group < 0 || group >= groupSlots.size()) continue;
            IRecipeSlotView slot = groupSlots.get(group);
            if (!slot.isEmpty()) {
                missingSlots.add(slot);
            }
        }
        if (missingSlots.isEmpty()) return null;

        // Same message JEI itself uses for the vanilla crafting grid.
        return helper.createUserErrorForMissingSlots(
                Component.translatable("jei.tooltip.error.recipe.transfer.missing"), missingSlots);
    }
}
