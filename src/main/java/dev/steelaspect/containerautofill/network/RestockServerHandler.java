/*
 * Cytra Container
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.network;

import dev.steelaspect.containerautofill.takeitout.ShulkerUtil;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.collection.DefaultedList;

import java.util.Locale;

/**
 * Server side of Restock. Items are only moved, never created: they come out of restock shulker boxes
 * (in the player's inventory or ender chest) and go into the named hotbar/offhand slot, up to a full stack.
 */
public final class RestockServerHandler {
    public static final int OFFHAND_SLOT = 40;

    private RestockServerHandler() {
    }

    static void register() {
        PayloadTypeRegistry.playC2S().register(RestockPayloads.Request.ID, RestockPayloads.Request.CODEC);
        PayloadTypeRegistry.playS2C().register(RestockPayloads.Result.ID, RestockPayloads.Result.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(RestockPayloads.Request.ID, (p, ctx) -> handle(ctx.player(), p));
    }

    private static void handle(ServerPlayerEntity player, RestockPayloads.Request request) {
        if (player == null) return;
        // Flush earlier changes first so the slot's new count is never mistaken for "unchanged" by the sync.
        player.currentScreenHandler.sendContentUpdates();
        int moved = restock(player, request);
        player.currentScreenHandler.sendContentUpdates();
        if (ServerPlayNetworking.canSend(player, RestockPayloads.Result.ID)) {
            ServerPlayNetworking.send(player, new RestockPayloads.Result(request.slot(), moved));
        }
    }

    private static int restock(ServerPlayerEntity player, RestockPayloads.Request request) {
        if (player.isSpectator() || !player.isAlive()) return 0;
        int slot = request.slot();
        if ((slot < 0 || slot >= PlayerInventory.getHotbarSize()) && slot != OFFHAND_SLOT) return 0;
        ItemStack wanted = request.item();
        if (wanted.isEmpty() || ShulkerUtil.isShulkerBox(wanted)) return 0;
        String name = request.name().trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty()) return 0;

        PlayerInventory inventory = player.getInventory();
        ItemStack target = inventory.getStack(slot);
        if (!target.isEmpty() && !ItemStack.areItemsAndComponentsEqual(target, wanted)) return 0;
        int need = wanted.getMaxCount() - target.getCount();
        if (need <= 0) return 0;

        int moved = 0;
        if (request.fromInventory()) moved += pull(inventory, ShulkerUtil.PLAYER_MAIN_SLOTS, slot, wanted, name, need);
        if (request.fromEnderChest() && moved < need) {
            moved += pull(player.getEnderChestInventory(), player.getEnderChestInventory().size(), -1, wanted, name, need - moved);
        }
        if (moved <= 0) return 0;

        if (target.isEmpty()) inventory.setStack(slot, wanted.copyWithCount(moved));
        else target.increment(moved);
        inventory.markDirty();
        return moved;
    }

    /** Takes up to {@code limit} matching items out of restock shulker boxes held in {@code holder}. */
    private static int pull(Inventory holder, int size, int skipSlot, ItemStack wanted, String name, int limit) {
        int moved = 0;
        for (int i = 0; i < size && moved < limit; i++) {
            if (i == skipSlot) continue;
            ItemStack box = holder.getStack(i);
            if (box.getCount() != 1 || !isRestockBox(box, name)) continue;
            DefaultedList<ItemStack> contents = ShulkerUtil.getContents(box);
            if (contents == null) continue;

            boolean changed = false;
            for (int j = 0; j < contents.size() && moved < limit; j++) {
                ItemStack inner = contents.get(j);
                if (inner.isEmpty() || !ItemStack.areItemsAndComponentsEqual(inner, wanted)) continue;
                int take = Math.min(limit - moved, inner.getCount());
                inner.decrement(take);
                if (inner.isEmpty()) contents.set(j, ItemStack.EMPTY);
                moved += take;
                changed = true;
            }
            if (changed) {
                ShulkerUtil.setContents(box, contents);
                holder.markDirty();
            }
        }
        return moved;
    }

    public static boolean isRestockBox(ItemStack box, String lowerCaseName) {
        if (!ShulkerUtil.isShulkerBox(box)) return false;
        Text custom = box.get(DataComponentTypes.CUSTOM_NAME);
        return custom != null && custom.getString().toLowerCase(Locale.ROOT).contains(lowerCaseName);
    }
}
