/*
 * Container Auto Fill
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
 * Pulls a missing item out of linked storage for auto-fill, pick block and auto take-out. Requests for
 * different items can be in flight together; each resolves as soon as its item lands in the inventory,
 * when the server reports a miss ({@link StoragePayloads.Taken}), or after a ping-based timeout.
 */
public final class StorageRetriever {
    private static final int MAX_IN_FLIGHT = 4;

    private record Pending(Predicate<ItemStack> matcher, ItemStack item, int countBefore,
                           Identifier dimension, BlockPos pos, int slot, long deadline) {
    }

    private static final List<Pending> PENDING = new ArrayList<>();
    private static long ticks;

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
        PENDING.removeIf(p -> ticks > p.deadline());
        onInventoryChanged(client);
    }

    /** Clears requests whose item has arrived. Called every tick and whenever an inventory packet lands. */
    public static boolean onInventoryChanged(MinecraftClient client) {
        if (client.player == null || PENDING.isEmpty()) return false;
        return PENDING.removeIf(p -> ShulkerRetriever.countInInventory(client.player.getInventory(), p.matcher()) > p.countBefore());
    }

    /**
     * Server answer to a take: a miss frees the request at once and refreshes the stale container.
     * Returns true if it was a miss for one of this retriever's requests.
     */
    public static boolean onTaken(MinecraftClient client, StoragePayloads.Taken taken) {
        onInventoryChanged(client);
        boolean removed = PENDING.removeIf(p -> p.pos().equals(taken.pos()) && p.slot() == taken.slot() && p.dimension().equals(taken.dimension()));
        if (removed && taken.moved() <= 0) {
            Configs.debug("Linked container {} slot {} was empty, refreshing it", taken.pos(), taken.slot());
            StorageContents.Snapshot snapshot = StorageContents.get(taken.dimension(), taken.pos());
            if (snapshot != null) snapshot.items().remove(taken.slot());
            StorageContents.requestOne(taken.dimension(), taken.pos());
            return true;
        }
        return false;
    }

    public static void reset() {
        PENDING.clear();
    }

    /** True if linked storage is usable and holds a matching item. */
    public static boolean hasItem(MinecraftClient client, Predicate<ItemStack> matcher) {
        return Configs.USE_LINKED_CONTAINERS.getBooleanValue() && StorageContents.isSupported()
                && !StorageActions.findSources(client, matcher).isEmpty();
    }

    /** Requests up to {@code count} matching items. Returns true if a request was sent (or one is pending). */
    public static boolean request(MinecraftClient client, Predicate<ItemStack> matcher, int count, boolean toHand) {
        if (client.player == null || !Configs.USE_LINKED_CONTAINERS.getBooleanValue() || !StorageContents.isSupported()) return false;
        List<StorageActions.Source> sources = StorageActions.findSources(client, matcher);
        if (sources.isEmpty()) return false;

        StorageActions.Source source = sources.get(0);
        if (isWaitingFor(source.stack())) return true;
        if (PENDING.size() >= MAX_IN_FLIGHT) return true;

        int amount = Math.min(count, source.stack().getCount());
        PENDING.add(new Pending(matcher, source.stack().copyWithCount(1), ShulkerRetriever.countInInventory(client.player.getInventory(), matcher),
                source.entry().dimensionId(), source.entry().pos(), source.slot(), ticks + ShulkerRetriever.timeoutTicks(client)));
        StorageActions.takeNow(source, amount, toHand);
        Configs.debug("Requested {}x {} from linked container {} slot {}", amount, source.stack().getItem(), source.entry().pos(), source.slot());
        return true;
    }
}
