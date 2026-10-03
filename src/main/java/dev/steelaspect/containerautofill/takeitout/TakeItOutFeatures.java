/*
 * Cytra Container
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.takeitout;

import dev.steelaspect.containerautofill.config.Configs;
import dev.steelaspect.containerautofill.storage.StorageRetriever;
import fi.dy.masa.litematica.config.Hotkeys;
import fi.dy.masa.litematica.util.EasyPlaceUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

import java.util.function.Predicate;

/**
 * TakeItOut behaviour, reimplemented:
 * <ul>
 *     <li>Pick block (vanilla middle click, and Litematica's schematic pick block / easy place) pulls the
 *     item out of a shulker box in the inventory when it isn't carried loose.</li>
 *     <li>Nothing is pulled just by looking at a block: only easy place and pick block (plus hotbar
 *     refill, restock and the fills) pull items.</li>
 * </ul>
 */
public final class TakeItOutFeatures {
    /** How long an easy place attempt that had to wait for an item stays eligible for the instant retry. */
    private static final int RETRY_WINDOW_TICKS = 100;

    private static long ticks;
    /** Item an easy place attempt is waiting for; easy place runs again the moment it arrives. */
    private static ItemStack retryItem = ItemStack.EMPTY;
    private static long retryUntil;
    /** Single-item buffer: the item easy place is pulling from storage, topped up while the key is held. */
    private static ItemStack bufferItem = ItemStack.EMPTY;

    private TakeItOutFeatures() {
    }

    public static void reset() {
        retryItem = ItemStack.EMPTY;
        bufferItem = ItemStack.EMPTY;
    }

    public static void tick(MinecraftClient client) {
        ticks++;
        topUpBuffer(client);
    }

    /**
     * Litematica schematic pick block / easy place hook. Returns true when a shulker request was sent
     * (or one is already in flight for this item), so Litematica's own pick is skipped this time.
     */
    public static boolean onSchematicPickBlock(ItemStack required, MinecraftClient client) {
        if (!Configs.ENABLE_MOD.getBooleanValue() || !Configs.SHULKER_PICK_BLOCK.getBooleanValue()) return false;
        if (client.player == null || client.player.isCreative() || required.isEmpty()) return false;

        Predicate<ItemStack> matcher = s -> ItemStack.areItemsAndComponentsEqual(s, required);
        if (ShulkerRetriever.countInInventory(client.player.getInventory(), matcher) > 0) return false;
        if (!isSelectedSlotPickBlockable(client)) return false;
        if (ShulkerRetriever.isWaitingFor(required) || StorageRetriever.isWaitingFor(required)) return true;

        boolean requested = requestFromAnySource(client, required, matcher);
        if (requested && fi.dy.masa.litematica.config.Configs.Generic.EASY_PLACE_MODE.getBooleanValue()) {
            retryItem = required.copyWithCount(1);
            retryUntil = ticks + RETRY_WINDOW_TICKS;
            if (StorageRetriever.isWaitingFor(required)) bufferItem = required.copyWithCount(1);
        }
        return requested;
    }

    /**
     * Single-item Buffer: while easy place is held, pull the next few items of the block being placed
     * from linked storage before the last one is used, so placing never waits for the server.
     */
    private static void topUpBuffer(MinecraftClient client) {
        if (bufferItem.isEmpty()) return;
        int buffer = Configs.SINGLE_ITEM_BUFFER.getIntegerValue();
        if (client.player == null || client.currentScreen != null || buffer <= 1 || !Configs.SINGLE_ITEM_MODE.getBooleanValue()
                || !fi.dy.masa.litematica.config.Configs.Generic.EASY_PLACE_MODE.getBooleanValue()
                || !Hotkeys.EASY_PLACE_ACTIVATION.getKeybind().isKeybindHeld()) {
            bufferItem = ItemStack.EMPTY;
            return;
        }
        ItemStack item = bufferItem;
        if (StorageRetriever.isWaitingFor(item)) return;
        Predicate<ItemStack> matcher = s -> ItemStack.areItemsAndComponentsEqual(s, item);
        int have = ShulkerRetriever.countInInventory(client.player.getInventory(), matcher);
        // At 0 the next pick block asks for it anyway; top up once the last one is in use.
        if (have != 1) return;
        if (StorageRetriever.request(client, matcher, buffer, false)) {
            Configs.debug("Single-item buffer: topping up {}x {}", buffer, item);
        } else {
            bufferItem = ItemStack.EMPTY;
        }
    }

    /**
     * Called when an inventory packet has been applied: clears finished requests and, if easy place was
     * waiting for this item, places it right away instead of on the next use tick.
     */
    public static void onInventoryPacket(MinecraftClient client) {
        if (ShulkerRetriever.onInventoryChanged(client)) retryEasyPlace(client, false);
    }

    /** The server confirmed a linked-storage pull; the item is already in the inventory. */
    public static void onStorageArrived(MinecraftClient client) {
        retryEasyPlace(client, false);
    }

    /** The server found a linked slot empty: easy place tries again now, which picks the next source. */
    public static void onStorageMiss(MinecraftClient client) {
        retryEasyPlace(client, true);
    }

    @SuppressWarnings("deprecation")
    private static void retryEasyPlace(MinecraftClient client, boolean evenIfMissing) {
        if (retryItem.isEmpty() || client.player == null) return;
        if (ticks > retryUntil) {
            retryItem = ItemStack.EMPTY;
            return;
        }
        ItemStack item = retryItem;
        boolean present = ShulkerRetriever.countInInventory(client.player.getInventory(), s -> ItemStack.areItemsAndComponentsEqual(s, item)) > 0;
        if (!present && !evenIfMissing) return;
        retryItem = ItemStack.EMPTY;

        if (client.currentScreen != null || client.world == null) return;
        if (!Configs.ENABLE_MOD.getBooleanValue()) return;
        if (!fi.dy.masa.litematica.config.Configs.Generic.EASY_PLACE_MODE.getBooleanValue()) return;
        if (!Hotkeys.EASY_PLACE_ACTIVATION.getKeybind().isKeybindHeld()) return;

        boolean rewrite = fi.dy.masa.litematica.config.Configs.Generic.EASY_PLACE_POST_REWRITE.getBooleanValue();
        boolean hold = fi.dy.masa.litematica.config.Configs.Generic.EASY_PLACE_HOLD_ENABLED.getBooleanValue();
        Configs.debug("Item {} ready, retrying easy place now", item);
        if (rewrite) {
            if (hold) EasyPlaceUtils.easyPlaceOnUseTick();
            else EasyPlaceUtils.handleEasyPlaceWithMessage();
        } else if (hold) {
            WorldUtils.easyPlaceOnUseTick(client);
        } else {
            WorldUtils.handleEasyPlace(client);
        }
    }

    /** Shulker in the inventory first (like TakeItOut), then linked storage; the item goes to the main hand. */
    private static boolean requestFromAnySource(MinecraftClient client, ItemStack required, Predicate<ItemStack> matcher) {
        if (ShulkerRetriever.request(client, required, matcher) == ShulkerRetriever.Outcome.REQUESTED) return true;
        int count = Configs.SINGLE_ITEM_MODE.getBooleanValue() ? Configs.SINGLE_ITEM_BUFFER.getIntegerValue() : required.getMaxCount();
        return StorageRetriever.request(client, matcher, count, true);
    }

    /** Vanilla pick block on a real block (survival only, as in TakeItOut). */
    public static void onVanillaPickBlock(MinecraftClient client, BlockPos pos) {
        if (!Configs.ENABLE_MOD.getBooleanValue() || !Configs.SHULKER_PICK_BLOCK.getBooleanValue()) return;
        if (client.player == null || client.world == null || client.player.isCreative()) return;

        BlockState state = client.world.getBlockState(pos);
        ItemStack stack = state.getPickStack(client.world, pos, false);
        if (stack.isEmpty()) return;

        Predicate<ItemStack> matcher = s -> ItemStack.areItemsAndComponentsEqual(s, stack);
        if (ShulkerRetriever.countInInventory(client.player.getInventory(), matcher) > 0) return;
        if (ShulkerRetriever.isWaitingFor(stack) || StorageRetriever.isWaitingFor(stack)) return;
        requestFromAnySource(client, stack, matcher);
    }

    /** Respects Litematica's "pickBlockableSlots" list (1-based, comma separated, ranges allowed). */
    private static boolean isSelectedSlotPickBlockable(MinecraftClient client) {
        String raw = fi.dy.masa.litematica.config.Configs.Generic.PICK_BLOCKABLE_SLOTS.getStringValue();
        if (raw == null || raw.isBlank()) return true;

        int selected = client.player.getInventory().getSelectedSlot() + 1;
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            try {
                int dash = trimmed.indexOf('-');
                if (dash > 0) {
                    int from = Integer.parseInt(trimmed.substring(0, dash).trim());
                    int to = Integer.parseInt(trimmed.substring(dash + 1).trim());
                    if (selected >= Math.min(from, to) && selected <= Math.max(from, to)) return true;
                } else if (Integer.parseInt(trimmed) == selected) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return false;
    }
}
