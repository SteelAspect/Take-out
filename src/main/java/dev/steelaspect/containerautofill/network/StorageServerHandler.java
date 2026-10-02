/*
 * Container Auto Fill
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.network;

import dev.steelaspect.containerautofill.takeitout.ShulkerUtil;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.block.entity.LockableContainerBlockEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server side of linked storage. Any container in a loaded chunk can be used (the owner's chosen rule),
 * except containers locked with a vanilla lock item the player doesn't hold. Items are only ever moved,
 * never created: leftovers go back to where they came from.
 */
public final class StorageServerHandler {
    public static final int MAX_QUERY_POSITIONS = 128;
    private static final int CONTAINERS_PER_REPLY = 32;
    /** Container reads per player per second. */
    private static final int QUERY_BUDGET_PER_SECOND = 4096;

    private static final Map<UUID, long[]> QUERY_BUDGETS = new ConcurrentHashMap<>();

    private StorageServerHandler() {
    }

    /** Registers payload types and receivers. Called from the mod's common entrypoint and the server jar. */
    public static void register() {
        PayloadTypeRegistry.playC2S().register(StoragePayloads.Take.ID, StoragePayloads.Take.CODEC);
        PayloadTypeRegistry.playC2S().register(StoragePayloads.Deposit.ID, StoragePayloads.Deposit.CODEC);
        PayloadTypeRegistry.playC2S().register(StoragePayloads.Query.ID, StoragePayloads.Query.CODEC);
        PayloadTypeRegistry.playS2C().register(StoragePayloads.Contents.ID, StoragePayloads.Contents.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(StoragePayloads.Take.ID, (p, ctx) -> take(ctx.player(), p));
        ServerPlayNetworking.registerGlobalReceiver(StoragePayloads.Deposit.ID, (p, ctx) -> deposit(ctx.player(), p));
        ServerPlayNetworking.registerGlobalReceiver(StoragePayloads.Query.ID, (p, ctx) -> query(ctx.player(), p));
        FillServerHandler.register();
    }

    static Inventory inventoryAt(ServerPlayerEntity player, Identifier dimension, BlockPos pos, boolean announceLock) {
        if (player == null || player.isSpectator() || !player.isAlive()) return null;
        ServerWorld world = player.getEntityWorld().getServer().getWorld(RegistryKey.of(RegistryKeys.WORLD, dimension));
        if (world == null || !world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) return null;
        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (blockEntity instanceof LockableContainerBlockEntity lockable && lockable.isLocked()) {
            if (!announceLock || !lockable.checkUnlocked(player)) return null;
        }
        return blockEntity instanceof Inventory inventory ? inventory : null;
    }

    private static void take(ServerPlayerEntity player, StoragePayloads.Take request) {
        Inventory inventory = inventoryAt(player, request.dimension(), request.pos(), true);
        if (inventory == null || request.slot() < 0 || request.slot() >= inventory.size() || request.count() <= 0) return;

        ItemStack inSlot = inventory.getStack(request.slot());
        if (inSlot.isEmpty()) return;
        ItemStack moved = inventory.removeStack(request.slot(), Math.min(request.count(), inSlot.getCount()));
        if (moved.isEmpty()) return;
        inventory.markDirty();

        PlayerInventory playerInventory = player.getInventory();
        if (request.toHand()) {
            int free = playerInventory.getEmptySlot();
            if (free >= 0 && free < ShulkerUtil.PLAYER_MAIN_SLOTS) {
                playerInventory.setStack(free, playerInventory.getSelectedStack());
                playerInventory.setSelectedStack(moved);
                return;
            }
        }
        playerInventory.insertStack(moved);
        if (!moved.isEmpty()) {
            ItemStack left = HopperBlockEntity.transfer(null, inventory, moved, null);
            if (!left.isEmpty()) player.dropItem(left, false);
            inventory.markDirty();
        }
    }

    private static void deposit(ServerPlayerEntity player, StoragePayloads.Deposit request) {
        if (request.playerSlot() < 0 || request.playerSlot() >= ShulkerUtil.PLAYER_MAIN_SLOTS || request.count() <= 0) return;
        Inventory inventory = inventoryAt(player, request.dimension(), request.pos(), true);
        if (inventory == null) return;

        ItemStack source = player.getInventory().getStack(request.playerSlot());
        if (source.isEmpty()) return;
        int amount = Math.min(request.count(), source.getCount());
        ItemStack remainder = HopperBlockEntity.transfer(null, inventory, source.copyWithCount(amount), null);
        int moved = amount - remainder.getCount();
        if (moved > 0) {
            source.decrement(moved);
            inventory.markDirty();
            player.getInventory().markDirty();
        }
    }

    private static void query(ServerPlayerEntity player, StoragePayloads.Query request) {
        if (!takeBudget(player, request.positions().size())) return;
        if (!ServerPlayNetworking.canSend(player, StoragePayloads.Contents.ID)) return;

        List<StoragePayloads.ContainerContents> batch = new ArrayList<>();
        for (BlockPos pos : request.positions()) {
            Inventory inventory = inventoryAt(player, request.dimension(), pos, false);
            List<StoragePayloads.SlotStack> stacks = new ArrayList<>();
            if (inventory != null) {
                for (int i = 0; i < inventory.size(); i++) {
                    ItemStack stack = inventory.getStack(i);
                    if (!stack.isEmpty()) stacks.add(new StoragePayloads.SlotStack(i, stack.copy()));
                }
            }
            batch.add(new StoragePayloads.ContainerContents(pos.toImmutable(), inventory != null, stacks));
            if (batch.size() >= CONTAINERS_PER_REPLY) {
                ServerPlayNetworking.send(player, new StoragePayloads.Contents(request.dimension(), batch));
                batch = new ArrayList<>();
            }
        }
        if (!batch.isEmpty()) {
            ServerPlayNetworking.send(player, new StoragePayloads.Contents(request.dimension(), batch));
        }
    }

    private static boolean takeBudget(ServerPlayerEntity player, int amount) {
        long now = System.currentTimeMillis() / 1000L;
        long[] budget = QUERY_BUDGETS.computeIfAbsent(player.getUuid(), u -> new long[]{now, 0});
        synchronized (budget) {
            if (budget[0] != now) {
                budget[0] = now;
                budget[1] = 0;
            }
            if (budget[1] + amount > QUERY_BUDGET_PER_SECOND) return false;
            budget[1] += amount;
            return true;
        }
    }
}
