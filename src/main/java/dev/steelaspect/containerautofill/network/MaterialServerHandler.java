/*
 * Cytra Container
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.network;

import dev.steelaspect.containerautofill.takeitout.ShulkerUtil;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.collection.DefaultedList;

import java.util.ArrayList;
import java.util.List;

/**
 * Moves a material list out of the listed linked containers (loose stacks first, then shulker boxes stored in
 * them) straight into the shulker boxes in the player's inventory. Items are only moved, never created; what
 * doesn't fit stays where it was.
 */
public final class MaterialServerHandler {
    private MaterialServerHandler() {
    }

    static void register() {
        PayloadTypeRegistry.playC2S().registerLarge(MaterialPayloads.Request.ID, MaterialPayloads.Request.CODEC, MaterialPayloads.MAX_PAYLOAD_BYTES);
        PayloadTypeRegistry.playS2C().register(MaterialPayloads.Result.ID, MaterialPayloads.Result.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(MaterialPayloads.Request.ID, (payload, context) -> {
            MaterialPayloads.Result result = pull(context.player(), payload);
            if (ServerPlayNetworking.canSend(context.player(), MaterialPayloads.Result.ID)) ServerPlayNetworking.send(context.player(), result);
        });
    }

    private static boolean same(ItemStack a, ItemStack b) {
        return !a.isEmpty() && !b.isEmpty() && ItemStack.areItemsAndComponentsEqual(a, b);
    }

    public static MaterialPayloads.Result pull(ServerPlayerEntity player, MaterialPayloads.Request request) {
        PlayerInventory inventory = player.getInventory();
        List<Integer> boxes = new ArrayList<>();
        for (int i = 0; i < ShulkerUtil.PLAYER_MAIN_SLOTS; i++) {
            ItemStack stack = inventory.getStack(i);
            if (ShulkerUtil.isShulkerBox(stack) && stack.getCount() == 1) boxes.add(i);
        }
        List<FillPayloads.Missing> missing = new ArrayList<>();
        if (boxes.isEmpty() && !canSplitBox(inventory)) {
            for (MaterialPayloads.Want want : request.wants()) missing.add(new FillPayloads.Missing(want.kind(), want.count()));
            return new MaterialPayloads.Result(request.requestId(), 0, missing, true, false);
        }

        int moved = 0;
        boolean boxesFull = false;
        for (MaterialPayloads.Want want : request.wants()) {
            ItemStack kind = want.kind();
            int remaining = want.count();
            if (kind.isEmpty() || remaining <= 0) continue;
            if (!kind.getItem().canBeNested()) {
                missing.add(new FillPayloads.Missing(kind, remaining));
                continue;
            }
            boolean full = false;
            for (int pass = 0; pass < 2 && remaining > 0 && !full; pass++) {
                for (FillPayloads.Source source : request.sources()) {
                    if (remaining <= 0 || full) break;
                    Inventory container = StorageServerHandler.inventoryAt(player, source.dimension(), source.pos(), false);
                    if (container == null) continue;
                    boolean changed = false;
                    for (int k = 0; k < container.size() && remaining > 0 && !full; k++) {
                        ItemStack stack = container.getStack(k);
                        if (pass == 0) {
                            if (!same(stack, kind)) continue;
                            int n = Math.min(remaining, stack.getCount());
                            int put = insertIntoBoxes(inventory, boxes, stack.copyWithCount(n));
                            if (put > 0) {
                                stack.decrement(put);
                                changed = true;
                            }
                            remaining -= put;
                            moved += put;
                            if (put < n) full = true;
                        } else {
                            // Shulker boxes stored in the linked container.
                            if (stack.getCount() != 1) continue;
                            DefaultedList<ItemStack> contents = ShulkerUtil.getContents(stack);
                            if (contents == null) continue;
                            boolean boxChanged = false;
                            for (int j = 0; j < contents.size() && remaining > 0 && !full; j++) {
                                ItemStack inner = contents.get(j);
                                if (!same(inner, kind)) continue;
                                int n = Math.min(remaining, inner.getCount());
                                int put = insertIntoBoxes(inventory, boxes, inner.copyWithCount(n));
                                if (put > 0) {
                                    inner.decrement(put);
                                    boxChanged = true;
                                }
                                remaining -= put;
                                moved += put;
                                if (put < n) full = true;
                            }
                            if (boxChanged) {
                                ShulkerUtil.setContents(stack, contents);
                                changed = true;
                            }
                        }
                    }
                    if (changed) container.markDirty();
                }
            }
            if (full) boxesFull = true;
            if (remaining > 0) missing.add(new FillPayloads.Missing(kind.copyWithCount(1), remaining));
        }
        inventory.markDirty();
        player.currentScreenHandler.sendContentUpdates();
        return new MaterialPayloads.Result(request.requestId(), moved, missing, false, boxesFull);
    }

    /** A stack of empty shulker boxes (Carpet's stackable boxes) plus an empty slot to split one into. */
    private static boolean canSplitBox(PlayerInventory inventory) {
        return stackedBoxSlot(inventory) >= 0 && inventory.getEmptySlot() >= 0 && inventory.getEmptySlot() < ShulkerUtil.PLAYER_MAIN_SLOTS;
    }

    private static int stackedBoxSlot(PlayerInventory inventory) {
        for (int i = 0; i < ShulkerUtil.PLAYER_MAIN_SLOTS; i++) {
            ItemStack stack = inventory.getStack(i);
            if (ShulkerUtil.isShulkerBox(stack) && stack.getCount() > 1) {
                DefaultedList<ItemStack> contents = ShulkerUtil.getContents(stack);
                if (contents != null && contents.stream().allMatch(ItemStack::isEmpty)) return i;
            }
        }
        return -1;
    }

    /** Splits one box off a stack of empty boxes into an empty slot, to fill next. Returns false if it can't. */
    private static boolean splitBox(PlayerInventory inventory, List<Integer> boxes) {
        if (!canSplitBox(inventory)) return false;
        int free = inventory.getEmptySlot();
        inventory.setStack(free, inventory.getStack(stackedBoxSlot(inventory)).split(1));
        boxes.add(free);
        return true;
    }

    /**
     * Puts as much of {@code stack} as fits into the carried boxes: onto matching stacks first, then empty slots,
     * then into boxes split off a stack of empty ones while there is inventory space.
     */
    private static int insertIntoBoxes(PlayerInventory inventory, List<Integer> boxes, ItemStack stack) {
        int total = stack.getCount();
        fillBoxes(inventory, boxes, stack);
        while (!stack.isEmpty() && splitBox(inventory, boxes)) fillBoxes(inventory, List.of(boxes.get(boxes.size() - 1)), stack);
        return total - stack.getCount();
    }

    private static void fillBoxes(PlayerInventory inventory, List<Integer> boxes, ItemStack stack) {
        for (int emptyPass = 0; emptyPass < 2 && !stack.isEmpty(); emptyPass++) {
            for (int slot : boxes) {
                if (stack.isEmpty()) break;
                ItemStack box = inventory.getStack(slot);
                DefaultedList<ItemStack> contents = ShulkerUtil.getContents(box);
                if (contents == null) continue;
                boolean changed = false;
                for (int i = 0; i < contents.size() && !stack.isEmpty(); i++) {
                    ItemStack inner = contents.get(i);
                    if (emptyPass == 0 && same(inner, stack) && inner.getCount() < inner.getMaxCount()) {
                        int n = Math.min(stack.getCount(), inner.getMaxCount() - inner.getCount());
                        inner.increment(n);
                        stack.decrement(n);
                        changed = true;
                    } else if (emptyPass == 1 && inner.isEmpty()) {
                        int n = Math.min(stack.getCount(), stack.getMaxCount());
                        contents.set(i, stack.copyWithCount(n));
                        stack.decrement(n);
                        changed = true;
                    }
                }
                if (changed) ShulkerUtil.setContents(box, contents);
            }
        }
    }
}
