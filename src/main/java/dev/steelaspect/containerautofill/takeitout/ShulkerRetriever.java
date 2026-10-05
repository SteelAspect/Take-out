/*
 * Cytra Container
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.takeitout;

import dev.steelaspect.containerautofill.config.Configs;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.collection.DefaultedList;

import java.util.function.Predicate;

/**
 * Client side of the TakeItOut-style item source: finds a stack inside a shulker box in the player's
 * inventory and asks the server to move it out ({@code takeitout:getstack}). Only one request is in
 * flight at a time; it resolves when the item shows up in the inventory or after a ping-based timeout.
 */
public final class ShulkerRetriever {
    public enum Outcome { REQUESTED, BUSY, NOT_FOUND, INVENTORY_FULL, UNSUPPORTED }

    public record Location(int shulkerSlot, int innerSlot, ItemStack stack) {
    }

    private record Pending(ItemStack item, int countBefore, int shulkerSlot, int innerSlot, long deadline) {
    }

    private static Pending pending;
    private static long ticks;
    private static boolean warnedUnsupported;
    private static boolean lastFindSkippedStacked;

    private ShulkerRetriever() {
    }

    /** True if the server accepts the request channel (integrated server, TakeItOut server mod or plugin). */
    public static boolean isSupported() {
        try {
            return ClientPlayNetworking.canSend(GetStackPayload.ID);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isWaiting() {
        return pending != null;
    }

    public static boolean isWaitingFor(ItemStack stack) {
        return pending != null && ItemStack.areItemsAndComponentsEqual(pending.item(), stack);
    }

    public static void tick(MinecraftClient client) {
        ticks++;
        if (pending == null) return;

        if (client.player == null) {
            pending = null;
            return;
        }
        if (hasArrived(client.player.getInventory(), pending)) {
            Configs.debug("Shulker request for {} arrived", pending.item());
            pending = null;
        } else if (ticks > pending.deadline()) {
            Configs.debug("Shulker request for {} timed out", pending.item());
            pending = null;
        }
    }

    /**
     * Inventory packet hook: clears the request as soon as the item itself is in the inventory. (Unlike the
     * tick check this ignores the shulker changing, which can be synced a moment before the item.)
     */
    public static boolean onInventoryChanged(MinecraftClient client) {
        if (pending == null || client.player == null) return false;
        ItemStack item = pending.item();
        if (countInInventory(client.player.getInventory(), s -> ItemStack.areItemsAndComponentsEqual(s, item)) <= pending.countBefore()) return false;
        Configs.debug("Shulker request for {} arrived", item);
        pending = null;
        return true;
    }

    public static void reset() {
        pending = null;
        warnedUnsupported = false;
    }

    /** Requests one stack matching {@code wanted} (same item and components) from an inventory shulker. */
    public static Outcome request(MinecraftClient client, ItemStack wanted, Predicate<ItemStack> matcher) {
        return request(client, wanted, matcher, false);
    }

    /**
     * @param allowSwap with a full inventory, let the server make room the way TakeItOut does: a block from the
     *                  inventory goes into the box in place of the item taken out (pick block / easy place only)
     */
    public static Outcome request(MinecraftClient client, ItemStack wanted, Predicate<ItemStack> matcher, boolean allowSwap) {
        if (client.player == null || wanted.isEmpty()) return Outcome.NOT_FOUND;
        if (pending != null) return Outcome.BUSY;

        PlayerInventory inventory = client.player.getInventory();
        Location location = find(inventory, matcher);
        if (location == null) {
            if (lastFindSkippedStacked) {
                client.player.sendMessage(Text.translatable("containerautofill.message.stacked_shulker_skipped").formatted(Formatting.YELLOW), true);
            }
            return Outcome.NOT_FOUND;
        }

        if (!isSupported()) {
            if (!warnedUnsupported) {
                warnedUnsupported = true;
                client.player.sendMessage(Text.translatable("containerautofill.message.takeitout_unsupported").formatted(Formatting.YELLOW), false);
            }
            return Outcome.UNSUPPORTED;
        }

        int free = inventory.getEmptySlot();
        // No empty slot: the hand item can still go into a matching stack or the box itself (server decides).
        if ((free < 0 || free >= ShulkerUtil.PLAYER_MAIN_SLOTS) && !(allowSwap
                && (inventory.getSelectedStack().getItem().canBeNested() || hasSwappableItem(inventory)))) return Outcome.INVENTORY_FULL;

        ClientPlayNetworking.send(new GetStackPayload(location.innerSlot(), location.shulkerSlot()));
        pending = new Pending(location.stack().copyWithCount(1), countInInventory(inventory, matcher),
                location.shulkerSlot(), location.innerSlot(), ticks + timeoutTicks(client));
        Configs.debug("Requested {} from shulker in slot {} (inner slot {})", location.stack(), location.shulkerSlot(), location.innerSlot());
        return Outcome.REQUESTED;
    }

    /** First non-stacked shulker box (slot order 0..35) containing a matching stack. */
    public static Location find(PlayerInventory inventory, Predicate<ItemStack> matcher) {
        boolean skippedStacked = false;
        for (int slot = 0; slot < ShulkerUtil.PLAYER_MAIN_SLOTS; slot++) {
            ItemStack box = inventory.getStack(slot);
            DefaultedList<ItemStack> contents = ShulkerUtil.getContents(box);
            if (contents == null) continue;

            for (int inner = 0; inner < contents.size(); inner++) {
                ItemStack stack = contents.get(inner);
                if (stack.isEmpty() || !matcher.test(stack)) continue;
                if (box.getCount() != 1) {
                    skippedStacked = true;
                    break;
                }
                lastFindSkippedStacked = false;
                return new Location(slot, inner, stack);
            }
        }
        lastFindSkippedStacked = skippedStacked;
        return null;
    }

    public static int countInInventory(PlayerInventory inventory, Predicate<ItemStack> matcher) {
        int count = 0;
        for (int slot = 0; slot < ShulkerUtil.PLAYER_MAIN_SLOTS; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && matcher.test(stack)) count += stack.getCount();
        }
        return count;
    }

    private static boolean hasArrived(PlayerInventory inventory, Pending request) {
        if (countInInventory(inventory, s -> ItemStack.areItemsAndComponentsEqual(s, request.item())) > request.countBefore()) {
            return true;
        }
        DefaultedList<ItemStack> contents = ShulkerUtil.getContents(inventory.getStack(request.shulkerSlot()));
        if (contents == null) return true;
        ItemStack inner = contents.get(request.innerSlot());
        return inner.isEmpty() || !ItemStack.areItemsAndComponentsEqual(inner, request.item());
    }

    /** Round trip allowance if no answer comes: 2.5x the player's ping plus 200 ms, clamped to 0.5..10 seconds. */
    /** Same rule as {@link ShulkerStackServerHandler}: a block (not a shulker box or ender chest) it can put into the box. */
    private static boolean hasSwappableItem(PlayerInventory inventory) {
        for (int i = 0; i < ShulkerUtil.PLAYER_MAIN_SLOTS; i++) {
            if (ShulkerStackServerHandler.canTradeIntoShulker(inventory.getStack(i))) return true;
        }
        return false;
    }

    public static int timeoutTicks(MinecraftClient client) {
        int pingMs = 0;
        if (client.getNetworkHandler() != null && client.player != null) {
            PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(client.player.getUuid());
            if (entry != null) pingMs = entry.getLatency();
        }
        if (pingMs <= 0) pingMs = 180;
        int ticks = (int) Math.ceil((pingMs * 2.5 + 200) / 50.0);
        return Math.max(10, Math.min(ticks, 200));
    }
}
