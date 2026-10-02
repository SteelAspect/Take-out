/*
 * Container Auto Fill
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
 * used, is refilled to a full stack by the server. Works on its own, whatever the TakeItOut options are.
 */
public final class Restock {
    /** After "nothing found" for an item, wait this long before asking again. */
    private static final int BACKOFF_TICKS = 100;
    private static final int MAX_IN_FLIGHT = 2;

    private record Pending(ItemStack item, long deadline) {
    }

    private record Backoff(ItemStack item, long until) {
    }

    private static final Map<Integer, Pending> PENDING = new HashMap<>();
    private static final List<Backoff> BACKOFF = new ArrayList<>();
    /** Items restocked at least once, so running out of them is worth a warning. */
    private static final List<ItemStack> KNOWN = new ArrayList<>();
    /** Slot whose last item was just used up, and what it held. */
    private static final Map<Integer, ItemStack> USED_UP = new HashMap<>();
    private static final ItemStack[] BEFORE_USE = {ItemStack.EMPTY, ItemStack.EMPTY};
    private static long ticks;

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

    // ------------------------------------------------------------ tick

    public static void tick(MinecraftClient client) {
        ticks++;
        PENDING.values().removeIf(p -> ticks > p.deadline());
        BACKOFF.removeIf(b -> ticks > b.until());

        if (!Configs.RESTOCK_ENABLED.getBooleanValue() || client.player == null || client.interactionManager == null) {
            USED_UP.clear();
            return;
        }
        if (client.player.isCreative() || client.player.isSpectator() || client.currentScreen != null) return;
        if (PENDING.size() >= MAX_IN_FLIGHT || !isSupported()) return;
        String name = Configs.RESTOCK_NAME.getStringValue().trim();
        if (name.isEmpty()) return;

        PlayerInventory inventory = client.player.getInventory();
        for (int slot : slots()) {
            if (PENDING.containsKey(slot)) continue;
            ItemStack stack = inventory.getStack(slot);
            ItemStack wanted;
            if (stack.isEmpty()) {
                wanted = USED_UP.remove(slot);
                if (wanted == null) continue;
            } else {
                USED_UP.remove(slot);
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

    private static int[] slots() {
        int hotbar = PlayerInventory.getHotbarSize();
        int[] slots = new int[Configs.RESTOCK_OFFHAND.getBooleanValue() ? hotbar + 1 : hotbar];
        // Offhand first: it's the usual place for elytra fireworks.
        int i = 0;
        if (slots.length > hotbar) slots[i++] = RestockServerHandler.OFFHAND_SLOT;
        for (int s = 0; s < hotbar; s++) slots[i++] = s;
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
