/*
 * Container Auto Fill
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 *
 * Ported from Litematica-Container-Filler (core/ItemMatcher.java),
 * https://github.com/MimicEnzymes/Litematica-Container-Filler, licensed LGPL-3.0-only.
 * Modified by steelaspect, 2026-10-02: moved to this package, uses this mod's config,
 * added a stack key for aggregation, and dropped the unused hashing helper.
 */
package dev.steelaspect.containerautofill.filler;

import dev.steelaspect.containerautofill.config.Configs;
import dev.steelaspect.containerautofill.takeitout.ShulkerUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.util.collection.DefaultedList;

/**
 * Item equality used for slot-exact filling: same item AND same data components. Shulker boxes can
 * optionally be matched by their contents instead (so a differently coloured box with the same
 * contents counts as correct).
 */
public final class ItemMatcher {
    private ItemMatcher() {
    }

    public static boolean isSameItem(ItemStack current, ItemStack required) {
        if (current.isEmpty() || required.isEmpty()) return false;
        if (ItemStack.areItemsAndComponentsEqual(current, required)) return true;
        return Configs.MATCH_SHULKER_BOXES_BY_CONTENT.getBooleanValue()
                && ShulkerUtil.isShulkerBox(current)
                && ShulkerUtil.isShulkerBox(required)
                && hasSameContainerContents(current, required);
    }

    private static boolean hasSameContainerContents(ItemStack current, ItemStack required) {
        DefaultedList<ItemStack> currentSlots = ShulkerUtil.getContents(current);
        DefaultedList<ItemStack> requiredSlots = ShulkerUtil.getContents(required);
        if (currentSlots == null || requiredSlots == null) return false;

        for (int i = 0; i < currentSlots.size(); i++) {
            ItemStack currentSlot = currentSlots.get(i);
            ItemStack requiredSlot = requiredSlots.get(i);
            if (currentSlot.isEmpty() != requiredSlot.isEmpty()) return false;
            if (currentSlot.isEmpty()) continue;
            if (currentSlot.getCount() != requiredSlot.getCount()) return false;
            if (!isSameItem(currentSlot, requiredSlot)) return false;
        }
        return true;
    }

    /** Hash/equality wrapper (count ignored) for grouping stacks, e.g. in the missing-items report. */
    public static final class StackKey {
        private final ItemStack stack;

        public StackKey(ItemStack stack) {
            this.stack = stack.copyWithCount(1);
        }

        public ItemStack stack() {
            return this.stack;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof StackKey other && ItemStack.areItemsAndComponentsEqual(this.stack, other.stack);
        }

        @Override
        public int hashCode() {
            return ItemStack.hashCode(this.stack);
        }
    }
}
