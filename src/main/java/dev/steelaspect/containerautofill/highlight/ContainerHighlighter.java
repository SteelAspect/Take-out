/*
 * Container Auto Fill
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.highlight;

import dev.steelaspect.containerautofill.config.Configs;
import dev.steelaspect.containerautofill.filler.ItemMatcher;
import dev.steelaspect.containerautofill.filler.SchematicContainerReader;
import dev.steelaspect.containerautofill.storage.StorageContents;
import dev.steelaspect.containerautofill.storage.StorageStore;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Works out, twice a second, how each nearby placed schematic container compares with the schematic.
 * Contents come from the server (no need to open anything) when it supports it.
 * Containers the schematic expects but that aren't placed (or are a different kind of block) are skipped.
 */
public final class ContainerHighlighter {
    private static final int UPDATE_INTERVAL_TICKS = 10;
    private static final int MAX_CONTAINERS = 2048;

    private static volatile Map<BlockPos, ContainerStatus> statuses = Collections.emptyMap();
    private static int tickCounter;

    private ContainerHighlighter() {
    }

    public static Map<BlockPos, ContainerStatus> statuses() {
        return statuses;
    }

    public static void reset() {
        statuses = Collections.emptyMap();
        SchematicContainerIndex.clear();
        RealContainerCache.clear();
    }

    public static void tick(MinecraftClient client) {
        if (!Configs.HIGHLIGHT_CONTAINERS.getBooleanValue() || client.world == null || client.player == null) {
            statuses = Collections.emptyMap();
            return;
        }
        if (++tickCounter % UPDATE_INTERVAL_TICKS != 0) return;

        SchematicContainerIndex.refresh(client.world.getRegistryManager());
        Map<BlockPos, SchematicContainerReader.Single> index = SchematicContainerIndex.entries();
        if (index.isEmpty()) {
            statuses = Collections.emptyMap();
            return;
        }

        double range = Configs.HIGHLIGHT_RANGE.getIntegerValue();
        double rangeSq = range * range;
        BlockPos center = client.player.getBlockPos();
        List<BlockPos> near = new ArrayList<>();
        for (BlockPos pos : index.keySet()) {
            if (pos.getSquaredDistance(center) <= rangeSq) {
                near.add(pos);
                if (near.size() >= MAX_CONTAINERS) break;
            }
        }

        // Ask the server what is inside, so containers don't have to be opened to get a status. Works in
        // singleplayer/LAN and on servers with containerautofill-server; otherwise use what we have.
        Identifier dimension = client.world.getRegistryKey().getValue();
        boolean serverQueries = StorageContents.isSupported();
        if (serverQueries) {
            if (!StorageContents.isRefreshing()) {
                List<StorageStore.Entry> entries = new ArrayList<>();
                for (BlockPos pos : near) entries.add(new StorageStore.Entry(dimension, pos));
                StorageContents.request(entries);
            }
        } else if (client.isIntegratedServerRunning()) {
            RealContainerCache.refreshFromIntegratedServer(client, near);
        } else {
            RealContainerCache.refreshFromServux(client, near);
        }

        Map<BlockPos, ContainerStatus> next = new HashMap<>();
        for (BlockPos pos : near) {
            if (!client.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;
            SchematicContainerReader.Single expected = index.get(pos);
            if (client.world.getBlockState(pos).isAir() || !SchematicContainerReader.isCompatible(client.world, pos, expected.state())) {
                continue;
            }
            Map<Integer, ItemStack> actual = null;
            if (serverQueries) {
                StorageContents.Snapshot snapshot = StorageContents.get(dimension, pos);
                if (snapshot != null && snapshot.available()) actual = snapshot.items();
            }
            if (actual == null) actual = RealContainerCache.get(pos);
            next.put(pos, actual == null ? ContainerStatus.UNKNOWN : compare(expected.items(), actual));
        }
        statuses = Collections.unmodifiableMap(next);
    }

    public static ContainerStatus compare(Map<Integer, ItemStack> expected, Map<Integer, ItemStack> actual) {
        for (Map.Entry<Integer, ItemStack> entry : actual.entrySet()) {
            ItemStack have = entry.getValue();
            if (have.isEmpty()) continue;
            ItemStack want = expected.get(entry.getKey());
            if (want == null || !ItemMatcher.isSameItem(have, want) || have.getCount() > want.getCount()) {
                return ContainerStatus.WRONG;
            }
        }

        boolean missing = false;
        boolean anyPresent = false;
        for (Map.Entry<Integer, ItemStack> entry : expected.entrySet()) {
            ItemStack have = actual.get(entry.getKey());
            int count = have == null ? 0 : have.getCount();
            if (count < entry.getValue().getCount()) missing = true;
            if (count > 0) anyPresent = true;
        }
        if (!missing) return ContainerStatus.CORRECT;
        return anyPresent ? ContainerStatus.PARTIAL : ContainerStatus.EMPTY;
    }
}
