/*
 * Cytra Container
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.restock;

import dev.steelaspect.containerautofill.config.Configs;
import dev.steelaspect.containerautofill.network.RestockPayloads;
import dev.steelaspect.containerautofill.network.RestockServerHandler;
import dev.steelaspect.containerautofill.takeitout.ShulkerRetriever;
import dev.steelaspect.containerautofill.takeitout.ShulkerUtil;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Restock: keeps hotbar (and offhand) stacks topped up from shulker boxes named e.g. "Restock", carried in
 * the inventory or kept in the ender chest. A stack below the threshold, or one whose last item was just
 * used, is refilled to a full stack by the server. A totem of undying that pops is replaced in the same slot.
 * Works on its own, whatever the TakeItOut options are.
 */
public final class Restock {
    /** After "nothing found" for an item, wait this long before asking again. */
    private static final int BACKOFF_TICKS = 100;
    private static final int MAX_IN_FLIGHT = 2;
    /** A totem that left a slot this close to a totem pop (either order) counts as used by the pop. */
    private static final int TOTEM_WINDOW_TICKS = 10;

    private record Pending(ItemStack item, long deadline) {
    }

    private record Backoff(ItemStack item, long until) {
    }

    private record Lost(ItemStack item, long tick) {
    }

    private static final Map<Integer, Pending> PENDING = new HashMap<>();
    private static final List<Backoff> BACKOFF = new ArrayList<>();
    /** Items restocked at least once, so running out of them is worth a warning. */
    private static final List<ItemStack> KNOWN = new ArrayList<>();
    /** Slot whose last item was just used up, and what it held. */
    private static final Map<Integer, ItemStack> USED_UP = new HashMap<>();
    private static final ItemStack[] BEFORE_USE = {ItemStack.EMPTY, ItemStack.EMPTY};
    /** Last seen stack per tracked slot, to notice a totem leaving its slot. */
    private static final Map<Integer, ItemStack> LAST = new HashMap<>();
    private static final Map<Integer, Lost> LOST_TOTEMS = new HashMap<>();
    private static long ticks;
    private static long lastTotemPop = Long.MIN_VALUE / 2;

    private Restock() {
    }

    public static boolean isSupported() {
        try {
            return ClientPlayNetworking.canSend(RestockPayloads.Request.ID);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isPending(int slot) {
        return PENDING.containsKey(slot);
    }

    public static void reset() {
        PENDING.clear();
        BACKOFF.clear();
        KNOWN.clear();
        USED_UP.clear();
        LAST.clear();
        LOST_TOTEMS.clear();
        lastTotemPop = Long.MIN_VALUE / 2;
    }

    public static void toggle(MinecraftClient client) {
        Configs.RESTOCK_ENABLED.toggleBooleanValue();
        if (client.player != null) {
            client.player.sendMessage(Text.translatable(Configs.RESTOCK_ENABLED.getBooleanValue()
                    ? "containerautofill.message.option_on" : "containerautofill.message.option_off", Configs.RESTOCK_ENABLED.getPrettyName()), true);
        }
    }

    // ------------------------------------------------------------ "used up" detection (interaction hooks)

    public static void beforeUse(MinecraftClient client, Hand hand) {
        if (client.player == null) return;
        BEFORE_USE[hand.ordinal()] = client.player.getStackInHand(hand).copy();
    }

    public static void afterUse(MinecraftClient client, Hand hand) {
        if (client.player == null) return;
        ItemStack before = BEFORE_USE[hand.ordinal()];
        BEFORE_USE[hand.ordinal()] = ItemStack.EMPTY;
        if (before.isEmpty() || !client.player.getStackInHand(hand).isEmpty()) return;
        int slot = hand == Hand.MAIN_HAND ? client.player.getInventory().getSelectedSlot() : RestockServerHandler.OFFHAND_SLOT;
        USED_UP.put(slot, before.copyWithCount(1));
    }

    // ------------------------------------------------------------ totems

    /** The local player's totem of undying just activated (entity status 35). */
    public static void onTotemPop(MinecraftClient client) {
        lastTotemPop = ticks;
        Configs.debug("Restock: totem popped");
        trackSlots(client);
        scan(client);
    }

    /** An inventory packet was applied: notice a popped totem's slot emptying right away. */
    public static void onInventoryPacket(MinecraftClient client) {
        if (LOST_TOTEMS.isEmpty() && !trackSlots(client)) return;
        scan(client);
    }

    public static boolean isTotem(ItemStack stack) {
        return !stack.isEmpty() && stack.contains(DataComponentTypes.DEATH_PROTECTION);
    }

    /** Updates the last-seen slots; returns true if a totem just left one of them. */
    private static boolean trackSlots(MinecraftClient client) {
        if (client.player == null) return false;
        PlayerInventory inventory = client.player.getInventory();
        boolean lost = false;
        for (int slot : allSlots()) {
            ItemStack now = inventory.getStack(slot);
            ItemStack before = LAST.get(slot);
            if (before != null && isTotem(before) && now.isEmpty()) {
                LOST_TOTEMS.put(slot, new Lost(before.copyWithCount(1), ticks));
                lost = true;
            }
            LAST.put(slot, now.copy());
        }
        LOST_TOTEMS.values().removeIf(l -> ticks - l.tick() > TOTEM_WINDOW_TICKS);
        // Only a totem that went missing around a pop was used; one dropped or moved by hand is ignored.
        if (Math.abs(ticks - lastTotemPop) <= TOTEM_WINDOW_TICKS && Configs.RESTOCK_TOTEMS.getBooleanValue()) {
            LOST_TOTEMS.forEach((slot, l) -> {
                if (Math.abs(l.tick() - lastTotemPop) <= TOTEM_WINDOW_TICKS) USED_UP.put(slot, l.item());
            });
            LOST_TOTEMS.keySet().removeAll(USED_UP.keySet());
        }
        return lost;
    }

    // ------------------------------------------------------------ tick

    public static void tick(MinecraftClient client) {
        ticks++;
        PENDING.values().removeIf(p -> ticks > p.deadline());
        BACKOFF.removeIf(b -> ticks > b.until());
        trackSlots(client);
        scan(client);
    }

    /** Sends at most one restock request for a tracked slot that is low or was just used up. */
    private static void scan(MinecraftClient client) {
        if (!Configs.RESTOCK_ENABLED.getBooleanValue() || client.player == null || client.interactionManager == null) {
            USED_UP.clear();
            return;
        }
        if (client.player.isCreative() || client.player.isSpectator() || client.currentScreen != null) return;
        if (PENDING.size() >= MAX_IN_FLIGHT || !isSupported()) return;
        String name = Configs.RESTOCK_NAME.getStringValue().trim();
        if (name.isEmpty()) return;

        PlayerInventory inventory = client.player.getInventory();
        for (int slot : allSlots()) {
            if (PENDING.containsKey(slot)) continue;
            // With Restock Offhand off, the offhand only gets its popped totem replaced.
            boolean offhandOff = slot == RestockServerHandler.OFFHAND_SLOT && !Configs.RESTOCK_OFFHAND.getBooleanValue();
            ItemStack stack = inventory.getStack(slot);
            ItemStack wanted;
            if (stack.isEmpty()) {
                wanted = USED_UP.remove(slot);
                if (wanted == null || (offhandOff && !isTotem(wanted))) continue;
            } else {
                USED_UP.remove(slot);
                if (offhandOff) continue;
                if (stack.getCount() >= threshold(stack.getMaxCount())) continue;
                wanted = stack.copyWithCount(1);
            }
            if (ShulkerUtil.isShulkerBox(wanted) || isBackedOff(wanted)) continue;

            String sent = name.length() > RestockPayloads.MAX_NAME_LENGTH ? name.substring(0, RestockPayloads.MAX_NAME_LENGTH) : name;
            ClientPlayNetworking.send(new RestockPayloads.Request(slot, wanted, sent,
                    Configs.RESTOCK_FROM_INVENTORY.getBooleanValue(), Configs.RESTOCK_FROM_ENDER_CHEST.getBooleanValue()));
            PENDING.put(slot, new Pending(wanted, ticks + ShulkerRetriever.timeoutTicks(client)));
            Configs.debug("Restock: asked for {} in slot {}", wanted, slot);
            return; // one request per tick
        }
    }

    /** Server answer: the slot was topped up by {@code moved} items (0 = no restock box had any). */
    public static void onResult(MinecraftClient client, RestockPayloads.Result result) {
        Pending pending = PENDING.remove(result.slot());
        if (pending == null) return;
        ItemStack item = pending.item();
        if (result.moved() > 0) {
            Configs.debug("Restock: slot {} topped up with {} {}", result.slot(), result.moved(), item);
            if (!isKnown(item)) KNOWN.add(item);
            return;
        }
        BACKOFF.add(new Backoff(item, ticks + BACKOFF_TICKS));
        for (Iterator<ItemStack> it = KNOWN.iterator(); it.hasNext(); ) {
            if (!ItemStack.areItemsAndComponentsEqual(it.next(), item)) continue;
            it.remove();
            if (Configs.RESTOCK_WARN_EMPTY.getBooleanValue() && client.player != null) {
                client.player.sendMessage(Text.translatable("containerautofill.message.restock_empty", item.getName())
                        .formatted(Formatting.YELLOW), true);
            }
        }
    }

    /** Restock below this count: the setting, but at most half a stack for small stacks (pearls, snowballs). */
    public static int threshold(int maxCount) {
        if (maxCount <= 1) return 1;
        return Math.max(1, Math.min(Configs.RESTOCK_THRESHOLD.getIntegerValue(), maxCount / 2));
    }

    /** Every slot that can hold a totem we track, whatever the offhand option says. */
    private static int[] allSlots() {
        int hotbar = PlayerInventory.getHotbarSize();
        int[] slots = new int[hotbar + 1];
        slots[0] = RestockServerHandler.OFFHAND_SLOT;
        for (int s = 0; s < hotbar; s++) slots[s + 1] = s;
        return slots;
    }

    private static boolean isBackedOff(ItemStack item) {
        for (Backoff b : BACKOFF) if (ItemStack.areItemsAndComponentsEqual(b.item(), item)) return true;
        return false;
    }

    private static boolean isKnown(ItemStack item) {
        for (ItemStack known : KNOWN) if (ItemStack.areItemsAndComponentsEqual(known, item)) return true;
        return false;
    }
}
