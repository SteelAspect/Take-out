/*
 * Cytra Container
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.storage;

import dev.steelaspect.containerautofill.network.StoragePayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Last known contents of linked containers, as reported by the server. */
public final class StorageContents {
    public record Key(Identifier dimension, BlockPos pos) {
    }

    /** {@code available=false}: the server couldn't read it (chunk unloaded, block gone, or locked). */
    public record Snapshot(boolean available, Map<Integer, ItemStack> items) {
    }

    private static final Map<Key, Snapshot> CACHE = new ConcurrentHashMap<>();
    /** Query batches waiting to be sent, one per tick, so thousands of linked containers don't flood the server. */
    private static final java.util.Deque<StoragePayloads.Query> OUTBOX = new java.util.ArrayDeque<>();

    private StorageContents() {
    }

    public static boolean isSupported() {
        try {
            return ClientPlayNetworking.canSend(StoragePayloads.Query.ID) && ClientPlayNetworking.canSend(StoragePayloads.Take.ID);
        } catch (Exception e) {
            return false;
        }
    }

    /** True if the server can also take items out of shulker boxes stored in linked containers (Cytra Container 1.2.0+). */
    public static boolean isShulkerTakeSupported() {
        try {
            return isSupported() && ClientPlayNetworking.canSend(StoragePayloads.TakeFromShulker.ID);
        } catch (Exception e) {
            return false;
        }
    }

    public static Snapshot get(Identifier dimension, BlockPos pos) {
        return CACHE.get(new Key(dimension, pos));
    }

    public static void clear() {
        CACHE.clear();
        OUTBOX.clear();
    }

    /** True while a refresh is still being sent. */
    public static boolean isRefreshing() {
        return !OUTBOX.isEmpty();
    }

    public static void tick() {
        if (!OUTBOX.isEmpty() && isSupported()) {
            ClientPlayNetworking.send(OUTBOX.poll());
        }
    }

    public static void onContents(StoragePayloads.Contents payload) {
        for (StoragePayloads.ContainerContents container : payload.containers()) {
            Map<Integer, ItemStack> items = new HashMap<>();
            for (StoragePayloads.SlotStack slotStack : container.stacks()) {
                if (!slotStack.stack().isEmpty()) items.put(slotStack.slot(), slotStack.stack());
            }
            CACHE.put(new Key(payload.dimension(), container.pos()), new Snapshot(container.available(), items));
        }
    }

    /** Asks the server for the contents of the given containers (grouped by dimension, in batches). */
    public static void request(List<StorageStore.Entry> entries) {
        if (!isSupported() || entries.isEmpty()) return;
        Map<Identifier, List<BlockPos>> byDimension = new HashMap<>();
        for (StorageStore.Entry entry : entries) {
            byDimension.computeIfAbsent(entry.dimensionId(), d -> new ArrayList<>()).add(entry.pos());
        }
        byDimension.forEach((dimension, positions) -> {
            for (int i = 0; i < positions.size(); i += StoragePayloadsLimits.QUERY_BATCH) {
                List<BlockPos> batch = List.copyOf(positions.subList(i, Math.min(positions.size(), i + StoragePayloadsLimits.QUERY_BATCH)));
                OUTBOX.add(new StoragePayloads.Query(dimension, batch));
            }
        });
    }

    public static void requestOne(Identifier dimension, BlockPos pos) {
        if (!isSupported()) return;
        OUTBOX.addFirst(new StoragePayloads.Query(dimension, List.of(pos)));
    }

    private static final class StoragePayloadsLimits {
        static final int QUERY_BATCH = 64;
    }
}
