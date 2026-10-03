/*
 * Cytra Container
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.network;

import dev.steelaspect.containerautofill.takeitout.ShulkerUtil;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.CrafterBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fills a container server-side in one go, without the player opening it. Items are only moved, never
 * created: from the player's inventory, shulker boxes in it, and the linked containers the client lists.
 * The same container access rules as linked storage apply (loaded chunk, vanilla lock items respected).
 * Players the server sees in creative mode can ask for a creative fill: the expected items are created.
 */
public final class FillServerHandler {
    private FillServerHandler() {
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(FillPayloads.Fill.ID, FillPayloads.Fill.CODEC);
        PayloadTypeRegistry.playS2C().register(FillPayloads.Result.ID, FillPayloads.Result.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(FillPayloads.Fill.ID, (payload, context) -> {
            FillPayloads.Result result = fill(context.player(), payload);
            if (ServerPlayNetworking.canSend(context.player(), FillPayloads.Result.ID)) {
                ServerPlayNetworking.send(context.player(), result);
            }
        });
    }

    private static boolean same(ItemStack a, ItemStack b) {
        return !a.isEmpty() && !b.isEmpty() && ItemStack.areItemsAndComponentsEqual(a, b);
    }

    private static final class Key {
        final ItemStack stack;

        Key(ItemStack stack) {
            this.stack = stack.copyWithCount(1);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && ItemStack.areItemsAndComponentsEqual(this.stack, k.stack);
        }

        @Override
        public int hashCode() {
            return ItemStack.hashCode(this.stack);
        }
    }

    public static FillPayloads.Result fill(ServerPlayerEntity player, FillPayloads.Fill request) {
        Inventory target = StorageServerHandler.inventoryAt(player, request.dimension(), request.pos(), true);
        Map<Integer, ItemStack> expected = new HashMap<>();
        for (StoragePayloads.SlotStack slotStack : request.expected()) {
            if (!slotStack.stack().isEmpty()) expected.put(slotStack.slot(), slotStack.stack());
        }
        Map<Key, Integer> missing = new LinkedHashMap<>();
        if (target == null) {
            expected.values().forEach(want -> missing.merge(new Key(want), want.getCount(), Integer::sum));
            return result(request, false, 0, 0, missing);
        }

        PlayerInventory playerInventory = player.getInventory();
        int wrong = 0;

        // Items the schematic doesn't expect in a slot: move them to the player, or leave them.
        for (int slot = 0; slot < target.size(); slot++) {
            ItemStack have = target.getStack(slot);
            ItemStack want = expected.get(slot);
            if (have.isEmpty() || (want != null && same(have, want))) continue;
            if (request.clearWrong()) {
                ItemStack removed = target.removeStack(slot);
                playerInventory.insertStack(removed);
                if (!removed.isEmpty()) {
                    target.setStack(slot, removed); // player inventory full: put it back
                    if (want != null) wrong++;
                }
            } else if (want != null) {
                wrong++;
            }
        }

        CrafterBlockEntity crafter = request.applyLocks() && target instanceof CrafterBlockEntity c ? c : null;
        Set<Integer> locked = Set.copyOf(request.disabledSlots());
        if (crafter != null) {
            for (int i = 0; i < 9; i++) {
                if (!locked.contains(i) && crafter.isSlotDisabled(i)) crafter.setSlotEnabled(i, true);
            }
        }

        boolean creative = request.creativeFill() && player.isInCreativeMode();
        int filled = 0;
        for (Map.Entry<Integer, ItemStack> entry : expected.entrySet()) {
            int slot = entry.getKey();
            ItemStack want = entry.getValue();
            if (slot < 0 || slot >= target.size()) continue;
            ItemStack have = target.getStack(slot);
            if (!have.isEmpty() && !same(have, want)) {
                missing.merge(new Key(want), want.getCount(), Integer::sum);
                continue;
            }
            boolean insertable = target.isValid(slot, want)
                    && !(target instanceof ShulkerBoxBlockEntity && Block.getBlockFromItem(want.getItem()) instanceof ShulkerBoxBlock);
            int goal = Math.min(want.getCount(), Math.min(want.getMaxCount(), target.getMaxCount(want)));
            int before = have.getCount();
            int need = goal - before;
            if (need > 0 && insertable) {
                ItemStack pulled = creative ? want.copyWithCount(need) : pull(player, want, need, request);
                if (!pulled.isEmpty()) {
                    if (have.isEmpty()) {
                        target.setStack(slot, pulled);
                    } else {
                        have.increment(pulled.getCount());
                    }
                }
            }
            int now = target.getStack(slot).getCount();
            if (same(target.getStack(slot), want) && now >= goal && now > before) filled++;
            if (now < want.getCount()) missing.merge(new Key(want), want.getCount() - now, Integer::sum);
        }

        if (crafter != null) {
            for (int i : locked) {
                if (i >= 0 && i < 9 && crafter.getStack(i).isEmpty()) crafter.setSlotEnabled(i, false);
            }
        }
        target.markDirty();
        playerInventory.markDirty();
        return result(request, true, filled, wrong, missing);
    }

    /**
     * Takes up to {@code need} matching items: inventory, then shulkers in it, then linked containers, then
     * shulkers inside linked containers. Both shulker steps follow {@code useShulkers}.
     */
    private static ItemStack pull(ServerPlayerEntity player, ItemStack want, int need, FillPayloads.Fill request) {
        ItemStack result = ItemStack.EMPTY;
        int remaining = need;
        PlayerInventory inventory = player.getInventory();

        for (int i = 0; i < ShulkerUtil.PLAYER_MAIN_SLOTS && remaining > 0; i++) {
            ItemStack stack = inventory.getStack(i);
            if (!same(stack, want)) continue;
            int n = Math.min(remaining, stack.getCount());
            result = merge(result, stack.split(n));
            remaining -= n;
        }

        if (request.useShulkers()) {
            for (int i = 0; i < ShulkerUtil.PLAYER_MAIN_SLOTS && remaining > 0; i++) {
                ItemStack box = inventory.getStack(i);
                if (box.getCount() != 1) continue;
                DefaultedList<ItemStack> contents = ShulkerUtil.getContents(box);
                if (contents == null) continue;
                boolean changed = false;
                for (int j = 0; j < contents.size() && remaining > 0; j++) {
                    ItemStack inner = contents.get(j);
                    if (!same(inner, want)) continue;
                    int n = Math.min(remaining, inner.getCount());
                    result = merge(result, inner.split(n));
                    remaining -= n;
                    changed = true;
                }
                if (changed) ShulkerUtil.setContents(box, contents);
            }
        }

        for (FillPayloads.Source source : request.sources()) {
            if (remaining <= 0) break;
            if (source.pos().equals(request.pos()) && source.dimension().equals(request.dimension())) continue;
            Inventory container = StorageServerHandler.inventoryAt(player, source.dimension(), source.pos(), false);
            if (container == null) continue;
            boolean changed = false;
            for (int k = 0; k < container.size() && remaining > 0; k++) {
                ItemStack stack = container.getStack(k);
                if (!same(stack, want)) continue;
                int n = Math.min(remaining, stack.getCount());
                result = merge(result, container.removeStack(k, n));
                remaining -= n;
                changed = true;
            }
            if (changed) container.markDirty();
        }

        // Last: shulker boxes stored in those linked containers.
        if (request.useShulkers()) {
            for (FillPayloads.Source source : request.sources()) {
                if (remaining <= 0) break;
                if (source.pos().equals(request.pos()) && source.dimension().equals(request.dimension())) continue;
                Inventory container = StorageServerHandler.inventoryAt(player, source.dimension(), source.pos(), false);
                if (container == null) continue;
                boolean changed = false;
                for (int k = 0; k < container.size() && remaining > 0; k++) {
                    ItemStack box = container.getStack(k);
                    if (box.getCount() != 1) continue;
                    DefaultedList<ItemStack> contents = ShulkerUtil.getContents(box);
                    if (contents == null) continue;
                    boolean boxChanged = false;
                    for (int j = 0; j < contents.size() && remaining > 0; j++) {
                        ItemStack inner = contents.get(j);
                        if (!same(inner, want)) continue;
                        int n = Math.min(remaining, inner.getCount());
                        result = merge(result, inner.split(n));
                        remaining -= n;
                        boxChanged = true;
                    }
                    if (boxChanged) {
                        ShulkerUtil.setContents(box, contents);
                        changed = true;
                    }
                }
                if (changed) container.markDirty();
            }
        }
        return result;
    }

    private static ItemStack merge(ItemStack into, ItemStack add) {
        if (add.isEmpty()) return into;
        if (into.isEmpty()) return add;
        into.increment(add.getCount());
        return into;
    }

    private static FillPayloads.Result result(FillPayloads.Fill request, boolean available, int filled, int wrong, Map<Key, Integer> missing) {
        List<FillPayloads.Missing> list = new ArrayList<>();
        missing.forEach((key, count) -> list.add(new FillPayloads.Missing(key.stack, count)));
        return new FillPayloads.Result(request.requestId(), request.pos(), available, filled, wrong, list);
    }
}
