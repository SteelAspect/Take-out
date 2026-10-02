/*
 * Cytra Container
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.filler;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.inventory.Inventory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

/**
 * Remembers which block a container screen belongs to. Vanilla screens don't carry their position,
 * so the last block the player interacted with is bound to the next screen that opens.
 */
public final class ContainerTracker {
    private static final int BIND_WINDOW_TICKS = 40;

    private static long ticks;
    private static BlockPos lastInteractedPos;
    private static long lastInteractedTick;
    private static int boundSyncId = -1;
    private static BlockPos boundPos;

    private ContainerTracker() {
    }

    public static void onInteractBlock(BlockPos pos) {
        lastInteractedPos = pos.toImmutable();
        lastInteractedTick = ticks;
    }

    public static void tick(MinecraftClient client) {
        ticks++;
        if (client.player == null) {
            boundSyncId = -1;
            boundPos = null;
            return;
        }
        ScreenHandler handler = client.player.currentScreenHandler;
        if (handler == null || handler == client.player.playerScreenHandler) {
            boundSyncId = -1;
            boundPos = null;
        } else if (handler.syncId != boundSyncId) {
            bind(handler);
        }
    }

    private static void bind(ScreenHandler handler) {
        boundSyncId = handler.syncId;
        boundPos = lastInteractedPos != null && ticks - lastInteractedTick <= BIND_WINDOW_TICKS ? lastInteractedPos : null;
        lastInteractedPos = null;
    }

    /** Position bound to the open screen through the block that was used to open it (no crosshair guess). */
    public static BlockPos getBoundPos(MinecraftClient client) {
        if (client.player == null) return null;
        ScreenHandler handler = client.player.currentScreenHandler;
        if (handler == null || handler == client.player.playerScreenHandler) return null;
        if (handler.syncId != boundSyncId) bind(handler);
        return boundPos;
    }

    /** Position of the container whose screen is open, or null if unknown. */
    public static BlockPos getOpenContainerPos(MinecraftClient client) {
        if (client.player == null || client.world == null) return null;
        ScreenHandler handler = client.player.currentScreenHandler;
        if (handler == null || handler == client.player.playerScreenHandler) return null;
        if (handler.syncId != boundSyncId) bind(handler);
        if (boundPos != null) return boundPos;

        // Fallback: the inventory block under the crosshair.
        if (client.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            BlockEntity blockEntity = client.world.getBlockEntity(hit.getBlockPos());
            if (blockEntity instanceof Inventory) return hit.getBlockPos().toImmutable();
        }
        return null;
    }
}
