/*
 * Container Auto Fill
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.takeitout;

import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.collection.DefaultedList;

public final class ShulkerUtil {
    public static final int SHULKER_SLOTS = 27;
    /** Main inventory (hotbar + storage). Armor and offhand are never used as a source. */
    public static final int PLAYER_MAIN_SLOTS = 36;

    private ShulkerUtil() {
    }

    public static boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    /** Returns a mutable 27-slot copy of the box contents, or null if the stack is not a shulker box. */
    public static DefaultedList<ItemStack> getContents(ItemStack box) {
        if (!isShulkerBox(box)) return null;
        DefaultedList<ItemStack> slots = DefaultedList.ofSize(SHULKER_SLOTS, ItemStack.EMPTY);
        ContainerComponent contents = box.get(DataComponentTypes.CONTAINER);
        if (contents != null) {
            contents.copyTo(slots);
        }
        return slots;
    }

    public static void setContents(ItemStack box, DefaultedList<ItemStack> slots) {
        box.set(DataComponentTypes.CONTAINER, ContainerComponent.fromStacks(slots));
    }
}
