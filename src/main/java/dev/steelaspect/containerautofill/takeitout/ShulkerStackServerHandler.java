/*
 * Container Auto Fill
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.takeitout;

import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.AxeItem;
import net.minecraft.item.BlockItem;
import net.minecraft.item.HoeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ShovelItem;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.collection.DefaultedList;

/**
 * Server side of the shulker retrieval request. Runs on the integrated server in singleplayer and
 * when hosting a LAN world. Behaviour follows TakeItOut so both implementations are interchangeable:
 * the requested stack goes into the main hand and the previous main-hand stack moves to a free slot.
 * When the inventory is full, a plain block item is traded into the shulker slot instead.
 * <p>
 * Every index from the client is validated, so a bad request can never duplicate or delete items.
 */
public final class ShulkerStackServerHandler {
    private ShulkerStackServerHandler() {
    }

    public static void handle(ServerPlayerEntity player, int innerSlot, int shulkerSlot) {
        if (player == null || player.isSpectator() || !player.isAlive()) return;
        if (shulkerSlot < 0 || shulkerSlot >= ShulkerUtil.PLAYER_MAIN_SLOTS) return;
        if (innerSlot < 0 || innerSlot >= ShulkerUtil.SHULKER_SLOTS) return;

        // Flush earlier changes first, so "hand emptied by a placement, then refilled here" is synced as two
        // changes rather than looking unchanged.
        player.currentScreenHandler.sendContentUpdates();
        PlayerInventory inventory = player.getInventory();
        ItemStack box = inventory.getStack(shulkerSlot);
        if (!ShulkerUtil.isShulkerBox(box) || box.getCount() != 1) return;

        DefaultedList<ItemStack> contents = ShulkerUtil.getContents(box);
        if (contents == null) return;
        ItemStack extracted = contents.get(innerSlot);
        if (extracted.isEmpty()) return;

        int freeSlot = inventory.getEmptySlot();
        if (freeSlot >= 0 && freeSlot < ShulkerUtil.PLAYER_MAIN_SLOTS) {
            contents.set(innerSlot, ItemStack.EMPTY);
            ShulkerUtil.setContents(box, contents);
            ItemStack previousHand = inventory.getSelectedStack();
            inventory.setStack(freeSlot, previousHand);
            inventory.setSelectedStack(extracted.copy());
        } else {
            for (int i = ShulkerUtil.PLAYER_MAIN_SLOTS - 1; i >= 0; i--) {
                ItemStack candidate = inventory.getStack(i);
                if (!canTradeIntoShulker(candidate)) continue;

                contents.set(innerSlot, candidate.copy());
                ShulkerUtil.setContents(box, contents);
                inventory.setStack(i, inventory.getSelectedStack());
                inventory.setSelectedStack(extracted.copy());
                break;
            }
        }
        inventory.markDirty();
        // Sync now rather than at the end of the tick, so the item reaches the client sooner.
        player.currentScreenHandler.sendContentUpdates();
    }

    private static boolean canTradeIntoShulker(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.getItem() instanceof HoeItem || stack.getItem() instanceof AxeItem || stack.getItem() instanceof ShovelItem) return false;
        if (!(stack.getItem() instanceof BlockItem blockItem)) return false;
        return !(blockItem.getBlock() instanceof ShulkerBoxBlock) && !(blockItem.getBlock() instanceof EnderChestBlock);
    }
}
