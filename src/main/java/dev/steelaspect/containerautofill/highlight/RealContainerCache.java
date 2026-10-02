/*
 * Cytra Container
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.highlight;

import dev.steelaspect.containerautofill.config.Configs;
import dev.steelaspect.containerautofill.filler.ContainerTracker;
import dev.steelaspect.containerautofill.filler.SchematicContainerReader;
import dev.steelaspect.containerautofill.filler.SlotMapper;
import fi.dy.masa.litematica.data.EntityDataManager;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What is really inside containers, per block position (each double chest half separately). Sources:
 * <ul>
 *     <li>singleplayer / LAN host: read straight from the integrated server's world;</li>
 *     <li>any server: the contents of a container screen while it is open (kept afterwards);</li>
 *     <li>servers running Servux: Litematica's entity data sync, when enabled in Litematica.</li>
 * </ul>
 */
public final class RealContainerCache {
    private static final int SERVUX_REQUEST_INTERVAL_TICKS = 40;
    private static final int SERVUX_REQUESTS_PER_REFRESH = 8;

    private static final Map<BlockPos, Map<Integer, ItemStack>> CACHE = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Long> LAST_SERVUX_REQUEST = new HashMap<>();
    private static long ticks;

    private RealContainerCache() {
    }

    public static Map<Integer, ItemStack> get(BlockPos pos) {
        return CACHE.get(pos);
    }

    public static void clear() {
        CACHE.clear();
        LAST_SERVUX_REQUEST.clear();
    }

    /** Records the open container screen's contents. */
    public static void tick(MinecraftClient client) {
        ticks++;
        if (client.player == null || client.world == null) return;
        ScreenHandler handler = client.player.currentScreenHandler;
        if (handler == null || handler == client.player.playerScreenHandler) return;

        BlockPos pos = ContainerTracker.getBoundPos(client);
        if (pos == null || !(client.world.getBlockEntity(pos) instanceof Inventory)) return;

        Map<Integer, Slot> slots = new SlotMapper(handler, client.player.getInventory()).containerSlots();
        BlockPos[] halves = SchematicContainerReader.getRealContainerHalves(client.world, pos);
        if (halves.length == 2 && slots.size() == 2 * SchematicContainerReader.CHEST_HALF_SIZE) {
            CACHE.put(halves[0], copy(slots, 0, SchematicContainerReader.CHEST_HALF_SIZE));
            CACHE.put(halves[1], copy(slots, SchematicContainerReader.CHEST_HALF_SIZE, SchematicContainerReader.CHEST_HALF_SIZE));
        } else {
            CACHE.put(pos, copy(slots, 0, slots.size()));
        }
    }

    private static Map<Integer, ItemStack> copy(Map<Integer, Slot> slots, int offset, int count) {
        Map<Integer, ItemStack> items = new HashMap<>();
        for (int i = 0; i < count; i++) {
            Slot slot = slots.get(offset + i);
            if (slot != null && slot.hasStack()) items.put(i, slot.getStack().copy());
        }
        return items;
    }

    /** Singleplayer / LAN host: snapshot the given block entities on the server thread. */
    public static void refreshFromIntegratedServer(MinecraftClient client, Collection<BlockPos> positions) {
        MinecraftServer server = client.getServer();
        if (server == null || client.world == null || positions.isEmpty()) return;
        RegistryKey<World> dimension = client.world.getRegistryKey();
        List<BlockPos> copy = List.copyOf(positions);

        server.execute(() -> {
            ServerWorld world = server.getWorld(dimension);
            if (world == null) return;
            for (BlockPos pos : copy) {
                if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;
                BlockEntity blockEntity = world.getBlockEntity(pos);
                if (blockEntity instanceof Inventory inventory) {
                    CACHE.put(pos, snapshot(inventory, 0, inventory.size()));
                } else {
                    CACHE.remove(pos);
                }
            }
        });
    }

    /** Servers with Servux, when Litematica's entity data sync is on: ask Litematica for the contents. */
    public static void refreshFromServux(MinecraftClient client, Collection<BlockPos> positions) {
        if (client.world == null || !fi.dy.masa.litematica.config.Configs.Generic.ENTITY_DATA_SYNC.getBooleanValue()) return;
        EntityDataManager manager = EntityDataManager.getInstance();
        if (!manager.hasServuxServer()) return;

        int requests = 0;
        for (BlockPos pos : positions) {
            Long last = LAST_SERVUX_REQUEST.get(pos);
            if (last != null && ticks - last < SERVUX_REQUEST_INTERVAL_TICKS) continue;
            if (requests++ >= SERVUX_REQUESTS_PER_REFRESH) break;
            LAST_SERVUX_REQUEST.put(pos, ticks);
            try {
                Inventory inventory = manager.getBlockInventoryWrapped(client.world, pos, true);
                if (inventory == null) continue;
                int half = SchematicContainerReader.CHEST_HALF_SIZE;
                BlockPos[] halves = SchematicContainerReader.getRealContainerHalves(client.world, pos);
                if (halves.length == 2 && inventory.size() == 2 * half) {
                    // Litematica returns double chests as one 54-slot inventory.
                    CACHE.put(halves[0], snapshot(inventory, 0, half));
                    CACHE.put(halves[1], snapshot(inventory, half, half));
                } else {
                    CACHE.put(pos, snapshot(inventory, 0, inventory.size()));
                }
            } catch (Exception e) {
                Configs.debug("Servux inventory request for {} failed: {}", pos, e.toString());
            }
        }
    }

    private static Map<Integer, ItemStack> snapshot(Inventory inventory, int offset, int size) {
        Map<Integer, ItemStack> items = new HashMap<>();
        for (int i = 0; i < size && offset + i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(offset + i);
            if (!stack.isEmpty()) items.put(i, stack.copy());
        }
        return items;
    }
}
