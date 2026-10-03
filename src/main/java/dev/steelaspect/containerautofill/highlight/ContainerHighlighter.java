/*
 * Cytra Container
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.highlight;

import dev.steelaspect.containerautofill.config.Configs;
import dev.steelaspect.containerautofill.filler.ItemMatcher;
import dev.steelaspect.containerautofill.filler.SchematicContainerReader;
import dev.steelaspect.containerautofill.storage.StorageContents;
import fi.dy.masa.malilib.render.RenderUtils;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works out, twice a second, how each nearby placed schematic container compares with the schematic.
 * Contents come from the server (no need to open anything) when it supports it.
 * Containers the schematic expects but that aren't placed (or are a different kind of block) are skipped.
 */
public final class ContainerHighlighter {
    private static final int UPDATE_INTERVAL_TICKS = 10;
    private static final int MAX_CONTAINERS = 2048;
    /** Each container's contents are asked for at most this often... */
    private static final int QUERY_INTERVAL_TICKS = 20;
    /** ...and at most this many per update, nearest first, so the server's read budget is never hit. */
    private static final int MAX_QUERIES_PER_UPDATE = 256;
    private static final int LIQUID_CHECK_TICKS = 2;

    private static volatile Map<BlockPos, ContainerStatus> statuses = Collections.emptyMap();
    private static volatile Set<BlockPos> behindLiquid = Set.of();
    private static final Map<BlockPos, Integer> LAST_QUERY = new HashMap<>();
    private static int tickCounter;

    private ContainerHighlighter() {
    }

    public static Map<BlockPos, ContainerStatus> statuses() {
        return statuses;
    }

    /** Highlighted containers you'd see if it weren't for water or lava in between (drawn on top). */
    public static Set<BlockPos> behindLiquid() {
        return behindLiquid;
    }

    public static void reset() {
        statuses = Collections.emptyMap();
        behindLiquid = Set.of();
        LAST_QUERY.clear();
        SchematicContainerIndex.clear();
        RealContainerCache.clear();
    }

    public static void tick(MinecraftClient client) {
        if (!Configs.HIGHLIGHT_CONTAINERS.getBooleanValue() || client.world == null || client.player == null) {
            statuses = Collections.emptyMap();
            behindLiquid = Set.of();
            return;
        }
        ++tickCounter;
        if (tickCounter % LIQUID_CHECK_TICKS == 0) updateBehindLiquid(client.world);
        if (tickCounter % UPDATE_INTERVAL_TICKS != 0) return;

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
            if (pos.getSquaredDistance(center) <= rangeSq) near.add(pos);
        }
        // Nearest first; with very many in range, the nearest are kept rather than a random subset.
        near.sort(Comparator.comparingDouble(pos -> pos.getSquaredDistance(center)));
        if (near.size() > MAX_CONTAINERS) near = new ArrayList<>(near.subList(0, MAX_CONTAINERS));

        // Ask the server what is inside, so containers don't have to be opened to get a status. Works in
        // singleplayer/LAN and on servers that also run this mod; otherwise use what we have.
        Identifier dimension = client.world.getRegistryKey().getValue();
        boolean serverQueries = StorageContents.isSupported();
        if (serverQueries) {
            requestContents(dimension, near);
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

    /**
     * Asks the server for the nearest containers not asked about in the last second. These go ahead of a
     * linked-storage refresh, which with thousands of linked containers would otherwise keep the highlight
     * waiting (contents unknown or out of date).
     */
    private static void requestContents(Identifier dimension, List<BlockPos> near) {
        if (LAST_QUERY.size() > 4 * MAX_CONTAINERS) LAST_QUERY.clear();
        List<BlockPos> due = new ArrayList<>();
        for (BlockPos pos : near) {
            Integer last = LAST_QUERY.get(pos);
            if (last != null && tickCounter - last < QUERY_INTERVAL_TICKS) continue;
            due.add(pos);
            LAST_QUERY.put(pos, tickCounter);
            if (due.size() >= MAX_QUERIES_PER_UPDATE) break;
        }
        StorageContents.requestFirst(dimension, due);
    }

    /**
     * Containers with water or lava (and nothing solid) between the camera and them. The normal depth test
     * hides their boxes behind the liquid's surface, so the renderer draws these on top instead.
     */
    private static void updateBehindLiquid(ClientWorld world) {
        Map<BlockPos, ContainerStatus> current = statuses;
        if (current.isEmpty() || !Configs.HIGHLIGHT_THROUGH_LIQUIDS.getBooleanValue() || Configs.HIGHLIGHT_THROUGH_WALLS.getBooleanValue()) {
            behindLiquid = Set.of();
            return;
        }
        Vec3d eye = RenderUtils.camPos();
        Set<BlockPos> found = new HashSet<>();
        for (Map.Entry<BlockPos, ContainerStatus> entry : current.entrySet()) {
            if (HighlightRenderer.colorFor(entry.getValue()) == null) continue;
            BlockPos pos = entry.getKey();
            Vec3d target = Vec3d.ofCenter(pos);
            BlockHitResult first = world.raycast(new RaycastContext(eye, target, RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.ANY, ShapeContext.absent()));
            if (first.getType() == HitResult.Type.MISS || first.getBlockPos().equals(pos)) continue; // nothing in the way
            if (world.getFluidState(first.getBlockPos()).isEmpty()) continue; // a solid block is in the way
            BlockHitResult solid = world.raycast(new RaycastContext(eye, target, RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE, ShapeContext.absent()));
            if (solid.getType() == HitResult.Type.MISS || solid.getBlockPos().equals(pos)) found.add(pos);
        }
        behindLiquid = found;
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
        // Expected empty and nothing wrong in it (wrong items returned WRONG above).
        if (expected.isEmpty()) return ContainerStatus.NOTHING_EXPECTED;
        if (!missing) return ContainerStatus.CORRECT;
        return anyPresent ? ContainerStatus.PARTIAL : ContainerStatus.EMPTY;
    }
}
