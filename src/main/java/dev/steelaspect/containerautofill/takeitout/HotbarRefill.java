/*
 * Container Auto Fill
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.takeitout;

import dev.steelaspect.containerautofill.config.Configs;
import dev.steelaspect.containerautofill.storage.StorageRetriever;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;

import java.util.function.Predicate;

/**
 * Hotbar auto-refill: when using the main-hand item (placing a block, throwing a pearl...) uses up the
 * last one, the same hotbar slot is refilled with the same item (and components). Sources, in order:
 * the rest of the inventory, shulker boxes in the inventory, then linked containers.
 * Skipped in creative and while Litematica's easy place is on (it picks its own items).
 */
public final class HotbarRefill {
    private static final int MAX_WAIT_TICKS = 5;

    private static ItemStack before = ItemStack.EMPTY;
    private static int beforeSlot = -1;
    private static ItemStack wanted = ItemStack.EMPTY;
    private static int wantedSlot = -1;
    private static int waited;

    private HotbarRefill() {
    }

    /** Called at the start of interactBlock / interactItem. */
    public static void beforeUse(MinecraftClient client, Hand hand) {
        before = ItemStack.EMPTY;
        if (hand != Hand.MAIN_HAND || client.player == null) return;
        before = client.player.getMainHandStack().copy();
        beforeSlot = client.player.getInventory().getSelectedSlot();
    }

    /** Called when interactBlock / interactItem returns: was the last item just used up? */
    public static void afterUse(MinecraftClient client, Hand hand) {
        if (hand != Hand.MAIN_HAND || client.player == null || before.isEmpty()) return;
        PlayerInventory inventory = client.player.getInventory();
        if (inventory.getSelectedSlot() == beforeSlot && client.player.getMainHandStack().isEmpty()) {
            wanted = before.copyWithCount(1);
            wantedSlot = beforeSlot;
            waited = 0;
        }
        before = ItemStack.EMPTY;
    }

    public static void reset() {
        before = ItemStack.EMPTY;
        wanted = ItemStack.EMPTY;
    }

    public static void tick(MinecraftClient client) {
        if (wanted.isEmpty()) return;
        ItemStack item = wanted;
        int slot = wantedSlot;
        wanted = ItemStack.EMPTY;

        if (!Configs.ENABLE_MOD.getBooleanValue() || !Configs.HOTBAR_REFILL.getBooleanValue()) return;
        if (client.player == null || client.interactionManager == null || client.player.isCreative()) return;
        if (fi.dy.masa.litematica.config.Configs.Generic.EASY_PLACE_MODE.getBooleanValue()) return;
        if (client.currentScreen != null || client.player.currentScreenHandler != client.player.playerScreenHandler) return;

        PlayerInventory inventory = client.player.getInventory();
        // The slot must still be the empty, selected one (the player didn't switch or put something there).
        if (inventory.getSelectedSlot() != slot || !inventory.getStack(slot).isEmpty()) return;
        if (dev.steelaspect.containerautofill.restock.Restock.isPending(slot)) {
            // Restock asked the server first; only step in if no restock box had the item.
            wanted = item;
            wantedSlot = slot;
            return;
        }

        Predicate<ItemStack> matcher = s -> ItemStack.areItemsAndComponentsEqual(s, item);
        // 1. Same item elsewhere in the inventory: swap it into the slot (normal inventory click).
        for (int i = PlayerInventory.getHotbarSize(); i < ShulkerUtil.PLAYER_MAIN_SLOTS; i++) {
            if (!matcher.test(inventory.getStack(i))) continue;
            client.interactionManager.clickSlot(client.player.playerScreenHandler.syncId, i, slot, SlotActionType.SWAP, client.player);
            Configs.debug("Hotbar refill: moved {} from inventory slot {} into hotbar slot {}", item, i, slot);
            return;
        }
        for (int i = 0; i < PlayerInventory.getHotbarSize(); i++) {
            if (i == slot || !matcher.test(inventory.getStack(i))) continue;
            // Another hotbar slot already holds it: leave that alone rather than shuffle the hotbar.
            return;
        }

        // 2./3. A shulker box in the inventory, then linked containers (straight into the hand).
        if (ShulkerRetriever.isWaitingFor(item) || StorageRetriever.isWaitingFor(item)) return;
        if (Configs.SHULKER_PICK_BLOCK.getBooleanValue()
                && ShulkerRetriever.request(client, item, matcher) == ShulkerRetriever.Outcome.REQUESTED) {
            Configs.debug("Hotbar refill: {} requested from a shulker box", item);
            return;
        }
        if (ShulkerRetriever.isWaiting() && ShulkerRetriever.find(inventory, matcher) != null && ++waited < MAX_WAIT_TICKS) {
            // Another shulker pull is still running; try again next tick.
            wanted = item;
            wantedSlot = slot;
            return;
        }
        int count = Configs.SINGLE_ITEM_MODE.getBooleanValue() ? Configs.SINGLE_ITEM_BUFFER.getIntegerValue() : item.getMaxCount();
        if (StorageRetriever.request(client, matcher, count, true)) {
            Configs.debug("Hotbar refill: {}x {} requested from linked storage", count, item);
        }
    }
}
