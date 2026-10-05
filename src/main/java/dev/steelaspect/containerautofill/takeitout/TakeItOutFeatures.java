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
import fi.dy.masa.litematica.util.RayTraceUtils;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
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
    /** After a single click (key already released), the item is still placed if it arrives this soon. */
    private static final int CLICK_RETRY_TICKS = 30;

    private static long ticks;
    /** Item an easy place attempt is waiting for; easy place runs again the moment it arrives. */
    private static ItemStack retryItem = ItemStack.EMPTY;
    private static long retryUntil;
    /** The schematic position that attempt was for; a released-key retry only places while still aiming at it. */
    private static BlockPos retryPos;
    private static long clickRetryUntil;
    /**
     * For Litematica's "Action prevented by the Easy Place mode", which follows our pick in the same tick when the
     * item isn't in the hand yet: stay quiet while it's being fetched, or say why it couldn't be.
     */
    private static long easyPlaceNoteTick = -1;
    private static Text easyPlaceNote;
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
    public static boolean onSchematicPickBlock(ItemStack required, BlockPos pos, MinecraftClient client) {
        easyPlaceNoteTick = -1;
        if (!isOn() || !Configs.SHULKER_PICK_BLOCK.getBooleanValue()) return false;
        if (client.player == null || client.player.isCreative() || required.isEmpty()) return false;

        PlayerInventory inventory = client.player.getInventory();
        Predicate<ItemStack> matcher = s -> ItemStack.areItemsAndComponentsEqual(s, required);
        if (ShulkerRetriever.countInInventory(inventory, matcher) > 0) return false;
        if (ShulkerRetriever.isWaitingFor(required) || StorageRetriever.isWaitingFor(required)) {
            noteEasyPlace(null);
            return true;
        }

        // The item goes into the selected slot, so pick it the way Litematica does (Pick Blockable Slots).
        int slot = pickTargetSlot(inventory);
        if (slot < 0) {
            noteEasyPlace(Text.translatable("containerautofill.message.easy_place_no_slot", required.getName()));
            return false;
        }
        int previous = inventory.getSelectedSlot();
        selectSlot(client, slot);

        Text problem = requestFromAnySource(client, required, matcher);
        boolean requested = problem == null;
        if (!requested) selectSlot(client, previous);
        noteEasyPlace(problem);
        if (requested && fi.dy.masa.litematica.config.Configs.Generic.EASY_PLACE_MODE.getBooleanValue()) {
            retryItem = required.copyWithCount(1);
            retryUntil = ticks + RETRY_WINDOW_TICKS;
            retryPos = pos.toImmutable();
            clickRetryUntil = ticks + CLICK_RETRY_TICKS;
            if (StorageRetriever.isWaitingFor(required)) bufferItem = required.copyWithCount(1);
        }
        return requested;
    }

    /** Printer lookups per tick (it asks for every reachable block) and how long a missing item is left alone. */
    private static final int PRINTER_LOOKUPS_PER_TICK = 2;
    private static final int PRINTER_MISS_COOLDOWN_TICKS = 40;
    private static final java.util.Map<PrinterItem, Long> PRINTER_MISSES = new java.util.HashMap<>();
    private static long printerLookupTick = -1;
    private static int printerLookups;

    private record PrinterItem(net.minecraft.item.Item item, net.minecraft.component.ComponentChanges components) {
        static PrinterItem of(ItemStack stack) {
            return new PrinterItem(stack.getItem(), stack.getComponentChanges());
        }
    }

    /**
     * Litematica Printer found no item for a block it wants to print: fetch the first one it accepts from a shulker
     * box or linked container (into the inventory; the printer picks it from there).
     */
    public static void onPrinterMissing(List<ItemStack> required) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!isOn() || !Configs.PRINTER_PULLS.getBooleanValue() || client.player == null || client.player.isCreative()) return;
        if (printerLookupTick != ticks) {
            printerLookupTick = ticks;
            printerLookups = 0;
        }
        for (ItemStack wanted : required) {
            if (wanted.isEmpty()) continue;
            if (ShulkerRetriever.isWaitingFor(wanted) || StorageRetriever.isWaitingFor(wanted)) return; // on its way
            PrinterItem key = PrinterItem.of(wanted);
            Long until = PRINTER_MISSES.get(key);
            if (until != null && ticks < until) continue;
            if (printerLookups >= PRINTER_LOOKUPS_PER_TICK) return;
            printerLookups++;
            Predicate<ItemStack> matcher = s -> ItemStack.areItemsAndComponentsEqual(s, wanted);
            if (ShulkerRetriever.countInInventory(client.player.getInventory(), matcher) > 0) return;
            if (requestFromAnySource(client, wanted, matcher, false) == null) {
                Configs.debug("Printer needs {}: fetching it", wanted);
                return;
            }
            PRINTER_MISSES.put(key, ticks + PRINTER_MISS_COOLDOWN_TICKS);
        }
        if (PRINTER_MISSES.size() > 512) PRINTER_MISSES.clear();
    }

    private static void noteEasyPlace(Text note) {
        easyPlaceNoteTick = ticks;
        easyPlaceNote = note;
    }

    /**
     * Litematica is about to show "Action prevented by the Easy Place mode". Returns false when the pick in the
     * same tick was ours: the item is on its way (nothing to say), or the reason it can't be fetched is shown instead.
     */
    public static boolean allowEasyPlaceFailMessage() {
        if (easyPlaceNoteTick != ticks) return true;
        easyPlaceNoteTick = -1;
        MinecraftClient client = MinecraftClient.getInstance();
        if (easyPlaceNote != null && client.player != null) client.player.sendMessage(easyPlaceNote, true);
        return false;
    }

    /**
     * Single-item Buffer: while easy place is held, pull the next few items of the block being placed
     * from linked storage before the last one is used, so placing never waits for the server.
     */
    private static void topUpBuffer(MinecraftClient client) {
        if (bufferItem.isEmpty()) return;
        int buffer = Configs.SINGLE_ITEM_BUFFER.getIntegerValue();
        if (!isOn() || client.player == null || client.currentScreen != null || buffer <= 1 || !Configs.SINGLE_ITEM_MODE.getBooleanValue()
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
        // Key still held: place as easy place would. Released (a single click): place only soon after the
        // click and only while still aiming at the same schematic block.
        boolean held = Hotkeys.EASY_PLACE_ACTIVATION.getKeybind().isKeybindHeld();
        if (!held && (ticks > clickRetryUntil || !isAimingAt(client, retryPos))) return;

        boolean rewrite = fi.dy.masa.litematica.config.Configs.Generic.EASY_PLACE_POST_REWRITE.getBooleanValue();
        boolean hold = held && fi.dy.masa.litematica.config.Configs.Generic.EASY_PLACE_HOLD_ENABLED.getBooleanValue();
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

    /**
     * Shulker in the inventory first (like TakeItOut), then linked storage; the item goes to the main hand.
     * Returns null when a request was sent, otherwise why not.
     */
    private static Text requestFromAnySource(MinecraftClient client, ItemStack required, Predicate<ItemStack> matcher) {
        return requestFromAnySource(client, required, matcher, true);
    }

    private static Text requestFromAnySource(MinecraftClient client, ItemStack required, Predicate<ItemStack> matcher, boolean toHand) {
        ShulkerRetriever.Outcome outcome = ShulkerRetriever.request(client, required, matcher, true);
        if (outcome == ShulkerRetriever.Outcome.REQUESTED) return null;
        int count = Configs.SINGLE_ITEM_MODE.getBooleanValue() ? Configs.SINGLE_ITEM_BUFFER.getIntegerValue() : required.getMaxCount();
        if (StorageRetriever.request(client, matcher, count, toHand)) return null;
        String key = switch (outcome) {
            case INVENTORY_FULL -> "containerautofill.message.easy_place_inventory_full";
            case UNSUPPORTED -> "containerautofill.message.easy_place_unsupported";
            case BUSY -> "containerautofill.message.easy_place_busy";
            default -> "containerautofill.message.easy_place_not_found";
        };
        return Text.translatable(key, required.getName());
    }

    /** Litematica's choice: the selected slot if allowed, else an empty allowed slot, else one without a tool. */
    private static int pickTargetSlot(PlayerInventory inventory) {
        List<Integer> allowed = pickBlockableSlots();
        int selected = inventory.getSelectedSlot();
        if (allowed.contains(selected) && canPickTo(inventory.getStack(selected))) return selected;
        for (int slot : allowed) {
            if (inventory.getStack(slot).isEmpty()) return slot;
        }
        for (int slot : allowed) {
            if (canPickTo(inventory.getStack(slot))) return slot;
        }
        return -1;
    }

    private static boolean canPickTo(ItemStack stack) {
        if (stack.isEmpty() || !stack.isDamageable()) return true;
        return !fi.dy.masa.litematica.config.Configs.Generic.PICK_BLOCK_AVOID_DAMAGEABLE.getBooleanValue()
                && !fi.dy.masa.litematica.config.Configs.Generic.PICK_BLOCK_AVOID_TOOLS.getBooleanValue();
    }

    /** Switches the hotbar slot and tells the server first, so an item it puts "in the hand" lands there. */
    private static void selectSlot(MinecraftClient client, int slot) {
        PlayerInventory inventory = client.player.getInventory();
        if (inventory.getSelectedSlot() == slot || client.getNetworkHandler() == null) return;
        inventory.setSelectedSlot(slot);
        client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
    }

    private static boolean isAimingAt(MinecraftClient client, BlockPos pos) {
        if (pos == null || client.world == null || client.player == null) return false;
        double range = WorldUtils.getValidBlockRange(client);
        RayTraceUtils.RayTraceWrapper trace = fi.dy.masa.litematica.config.Configs.Generic.EASY_PLACE_FIRST.getBooleanValue()
                ? RayTraceUtils.getGenericTrace(client.world, client.player, range, true, false, false)
                : RayTraceUtils.getFurthestSchematicWorldTraceBeforeVanilla(client.world, client.player, range);
        return trace != null && trace.getHitType() == RayTraceUtils.RayTraceWrapper.HitType.SCHEMATIC_BLOCK
                && trace.getBlockHitResult() != null && trace.getBlockHitResult().getBlockPos().equals(pos);
    }

    /** Vanilla pick block on a real block (survival only, as in TakeItOut). */
    public static void onVanillaPickBlock(MinecraftClient client, BlockPos pos) {
        if (!isOn() || !Configs.SHULKER_PICK_BLOCK.getBooleanValue()) return;
        if (client.player == null || client.world == null || client.player.isCreative()) return;

        BlockState state = client.world.getBlockState(pos);
        ItemStack stack = state.getPickStack(client.world, pos, false);
        if (stack.isEmpty()) return;

        Predicate<ItemStack> matcher = s -> ItemStack.areItemsAndComponentsEqual(s, stack);
        if (ShulkerRetriever.countInInventory(client.player.getInventory(), matcher) > 0) return;
        if (ShulkerRetriever.isWaitingFor(stack) || StorageRetriever.isWaitingFor(stack)) return;
        requestFromAnySource(client, stack, matcher);
    }

    /** The mod and its TakeItOut switch are both on; otherwise nothing is pulled into the hand. */
    public static boolean isOn() {
        return Configs.ENABLE_MOD.getBooleanValue() && Configs.TAKEITOUT_ENABLED.getBooleanValue();
    }

    /** Litematica's "pickBlockableSlots" list as 0-based hotbar slots (1-based, comma separated, ranges allowed). */
    private static List<Integer> pickBlockableSlots() {
        String raw = fi.dy.masa.litematica.config.Configs.Generic.PICK_BLOCKABLE_SLOTS.getStringValue();
        List<Integer> slots = new ArrayList<>();
        if (raw != null && !raw.isBlank()) {
            for (String part : raw.split(",")) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) continue;
                try {
                    int dash = trimmed.indexOf('-');
                    int from = Integer.parseInt((dash > 0 ? trimmed.substring(0, dash) : trimmed).trim());
                    int to = dash > 0 ? Integer.parseInt(trimmed.substring(dash + 1).trim()) : from;
                    for (int n = Math.min(from, to); n <= Math.max(from, to); n++) {
                        if (n >= 1 && n <= 9 && !slots.contains(n - 1)) slots.add(n - 1);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (slots.isEmpty()) {
            for (int i = 0; i < 9; i++) slots.add(i);
        }
        return slots;
    }
}
