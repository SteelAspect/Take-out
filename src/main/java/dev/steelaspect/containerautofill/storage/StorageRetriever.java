/*
 * Cytra Container
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.storage;

import dev.steelaspect.containerautofill.config.Configs;
import dev.steelaspect.containerautofill.network.StoragePayloads;
import dev.steelaspect.containerautofill.takeitout.ShulkerRetriever;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Pulls a missing item out of linked storage for auto-fill, pick block and easy place. Requests for
 * different items can be in flight together. Each resolves when the server answers it
 * ({@link StoragePayloads.Taken}, sent after the inventory sync, so the item is already there), or after a
 * ping-based timeout. Inventory counts aren't used: a late server correction can briefly show one item
 * more than the client's own prediction and would look like an arrival.
 */
public final class StorageRetriever {
    private static final int MAX_IN_FLIGHT = 4;

    private record Pending(int id, ItemStack item,
                           Identifier dimension, BlockPos pos, int slot, boolean inShulker, long deadline) {
    }

    private static final List<Pending> PENDING = new ArrayList<>();
    private static long ticks;
    private static int nextId = 1;

    private StorageRetriever() {
    }

    public static boolean isWaiting() {
        return !PENDING.isEmpty();
    }

    /** True while a request for this exact item (and components) is in flight. */
    public static boolean isWaitingFor(ItemStack stack) {
        for (Pending pending : PENDING) {
            if (ItemStack.areItemsAndComponentsEqual(pending.item(), stack)) return true;
        }
        return false;
    }

    public static void tick(MinecraftClient client) {
        ticks++;
        if (client.player == null) {
            PENDING.clear();
            return;
        }
        PENDING.removeIf(p -> {
            if (ticks <= p.deadline()) return false;
            Configs.debug("Linked storage request {} for {} timed out", p.id(), p.item());
            return true;
        });
    }

    public enum Answer { NOT_OURS, ARRIVED, MISSED }

    /** Server answer to a take. A miss also drops the stale slot from the cache and re-reads the container. */
    public static Answer onTaken(MinecraftClient client, StoragePayloads.Taken taken) {
        if (taken.requestId() == 0) return Answer.NOT_OURS;
        Configs.debug("Server answered request {}: moved {}", taken.requestId(), taken.moved());
        Pending answered = PENDING.stream().filter(p -> p.id() == taken.requestId()).findFirst().orElse(null);
        if (answered == null) return Answer.NOT_OURS;
        PENDING.remove(answered);
        if (taken.moved() > 0) return Answer.ARRIVED;
        Configs.debug("Linked container {} slot {} was empty, refreshing it", taken.pos(), taken.slot());
        StorageContents.Snapshot snapshot = StorageContents.get(taken.dimension(), taken.pos());
        // A miss inside a shulker box leaves the box itself in the cache until the re-read.
        if (snapshot != null && !answered.inShulker()) snapshot.items().remove(taken.slot());
        StorageContents.requestOne(taken.dimension(), taken.pos());
        return Answer.MISSED;
    }

    public static void reset() {
        PENDING.clear();
    }

    /** True if linked storage is usable and holds a matching item (loose, or in a shulker box if {@code inShulkers}). */
    public static boolean hasItem(MinecraftClient client, Predicate<ItemStack> matcher, boolean inShulkers) {
        return Configs.USE_LINKED_CONTAINERS.getBooleanValue() && StorageContents.isSupported()
                && !StorageActions.findSources(client, matcher, inShulkers).isEmpty();
    }

    /** Requests up to {@code count} matching items, also from shulker boxes stored in linked containers. */
    public static boolean request(MinecraftClient client, Predicate<ItemStack> matcher, int count, boolean toHand) {
        return request(client, matcher, count, toHand, true);
    }

    /**
     * Requests up to {@code count} matching items: loose ones first, then (if {@code inShulkers}) from shulker
     * boxes stored in linked containers. Returns true if a request was sent (or one is pending).
     */
    public static boolean request(MinecraftClient client, Predicate<ItemStack> matcher, int count, boolean toHand, boolean inShulkers) {
        if (client.player == null || !Configs.USE_LINKED_CONTAINERS.getBooleanValue() || !StorageContents.isSupported()) return false;
        List<StorageActions.Source> sources = StorageActions.findSources(client, matcher, inShulkers);
        if (sources.isEmpty()) return false;

        StorageActions.Source source = sources.get(0);
        if (isWaitingFor(source.stack())) return true;
        if (PENDING.size() >= MAX_IN_FLIGHT) return true;

        int amount = Math.min(count, source.stack().getCount());
        int id = nextId;
        nextId = nextId == Integer.MAX_VALUE ? 1 : nextId + 1;
        PENDING.add(new Pending(id, source.stack().copyWithCount(1), source.entry().dimensionId(), source.entry().pos(), source.slot(),
                source.inShulker(), ticks + ShulkerRetriever.timeoutTicks(client)));
        StorageActions.takeNow(id, source, amount, toHand);
        Configs.debug("Request {} (tick {}): {}x {} from linked container {} slot {}{}", id, ticks, amount, source.stack().getItem(), source.entry().pos(), source.slot(),
                source.inShulker() ? " (shulker slot " + source.innerSlot() + ")" : "");
        return true;
    }
}
