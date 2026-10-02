/*
 * Container Auto Fill
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.storage;

import dev.steelaspect.containerautofill.config.Configs;
import dev.steelaspect.containerautofill.takeitout.ShulkerRetriever;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;

import java.util.List;
import java.util.function.Predicate;

/**
 * Pulls a missing item out of linked storage for auto-fill, pick block and auto take-out. Like the
 * shulker retriever, one request is in flight at a time and it resolves when the item arrives or times out.
 */
public final class StorageRetriever {
    private static final int TIMEOUT_TICKS = 60;

    private record Pending(Predicate<ItemStack> matcher, int countBefore, long deadline) {
    }

    private static Pending pending;
    private static long ticks;

    private StorageRetriever() {
    }

    public static boolean isWaiting() {
        return pending != null;
    }

    public static void tick(MinecraftClient client) {
        ticks++;
        if (pending == null) return;
        if (client.player == null || ticks > pending.deadline()
                || ShulkerRetriever.countInInventory(client.player.getInventory(), pending.matcher()) > pending.countBefore()) {
            pending = null;
        }
    }

    public static void reset() {
        pending = null;
    }

    /** True if linked storage is usable and holds a matching item. */
    public static boolean hasItem(MinecraftClient client, Predicate<ItemStack> matcher) {
        return Configs.USE_LINKED_CONTAINERS.getBooleanValue() && StorageContents.isSupported()
                && !StorageActions.findSources(client, matcher).isEmpty();
    }

    /** Requests up to {@code count} matching items. Returns true if a request was sent (or one is pending). */
    public static boolean request(MinecraftClient client, Predicate<ItemStack> matcher, int count, boolean toHand) {
        if (client.player == null || !Configs.USE_LINKED_CONTAINERS.getBooleanValue() || !StorageContents.isSupported()) return false;
        if (pending != null) return true;
        List<StorageActions.Source> sources = StorageActions.findSources(client, matcher);
        if (sources.isEmpty()) return false;

        StorageActions.Source source = sources.get(0);
        int amount = Math.min(count, source.stack().getCount());
        pending = new Pending(matcher, ShulkerRetriever.countInInventory(client.player.getInventory(), matcher), ticks + TIMEOUT_TICKS);
        StorageActions.takeNow(source, amount, toHand);
        Configs.debug("Requested {}x {} from linked container {} slot {}", amount, source.stack().getItem(), source.entry().pos(), source.slot());
        return true;
    }
}
