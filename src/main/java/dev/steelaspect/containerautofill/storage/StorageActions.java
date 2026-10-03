/*
 * Cytra Container
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.storage;

import dev.steelaspect.containerautofill.config.Configs;
import dev.steelaspect.containerautofill.filler.ItemMatcher;
import dev.steelaspect.containerautofill.filler.SchematicContainerReader;
import dev.steelaspect.containerautofill.network.StoragePayloads;
import dev.steelaspect.containerautofill.takeitout.ShulkerUtil;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ObjIntConsumer;
import java.util.function.Predicate;

/** Everything the player does with linked storage: linking, taking, dumping, finding. */
public final class StorageActions {
    private static final int LOOK_AT_TICKS = 200;
    private static final int REFRESH_INTERVAL_TICKS = 100;

    private static final Deque<net.minecraft.network.packet.CustomPayload> QUEUE = new ArrayDeque<>();
    private static final Set<StorageContents.Key> DIRTY = new LinkedHashSet<>();
    private static long ticks;
    private static long lastAction;
    private static long lastRefresh = -REFRESH_INTERVAL_TICKS;
    /** A full refresh was asked for while another refresh was still being sent; run it once that's done. */
    private static boolean refreshQueued;
    private static BlockPos boxCorner;
    private static Set<StorageContents.Key> lookAt = Set.of();
    private static long lookAtUntil;

    private StorageActions() {
    }

    public static void tick(MinecraftClient client) {
        ticks++;
        if (client.player == null || StorageStore.worldKey() == null) return;
        StorageContents.tick();

        if (!QUEUE.isEmpty() && ticks - lastAction >= Configs.CLICK_DELAY.getIntegerValue()) {
            ClientPlayNetworking.send(QUEUE.poll());
            lastAction = ticks;
        }
        if (QUEUE.isEmpty() && !DIRTY.isEmpty()) {
            List<StorageStore.Entry> entries = new ArrayList<>();
            for (StorageContents.Key key : DIRTY) entries.add(new StorageStore.Entry(key.dimension(), key.pos()));
            DIRTY.clear();
            StorageContents.request(entries);
        }
        // Keep the cache fresh while something may pull from it (menu open, linked containers in use, auto-fill).
        if ((refreshQueued && !StorageContents.isRefreshing())
                || (ticks - lastRefresh >= REFRESH_INTERVAL_TICKS && wantsRefresh(client))) {
            refreshAll();
        }
    }

    private static boolean wantsRefresh(MinecraftClient client) {
        return client.currentScreen instanceof StorageScreen
                || (Configs.USE_LINKED_CONTAINERS.getBooleanValue() && StorageStore.linkedCount() > 0)
                || dev.steelaspect.containerautofill.filler.AutoFillController.isRunning();
    }

    public static void refreshAll() {
        lastRefresh = ticks;
        refreshQueued = StorageContents.isRefreshing();
        if (refreshQueued) return;
        StorageContents.request(StorageStore.linkedEntries());
    }

    public static boolean isBusy() {
        return !QUEUE.isEmpty();
    }

    public static Identifier dimension(MinecraftClient client) {
        return client.world.getRegistryKey().getValue();
    }

    // ---------------------------------------------------------------- linking

    /** Hotkey: link the looked-at container, or unlink it if it is already linked. */
    public static void toggleLookedAt(MinecraftClient client) {
        BlockPos pos = lookedAtContainer(client);
        if (pos == null) {
            actionBar(client, Text.translatable("containerautofill.message.storage_not_container"));
            return;
        }
        Identifier dim = dimension(client);
        BlockPos[] halves = SchematicContainerReader.getRealContainerHalves(client.world, pos);
        StorageStore.Entry existing = StorageStore.find(dim, pos);
        if (existing != null && existing.linked) {
            for (BlockPos half : halves) {
                StorageStore.Entry e = StorageStore.find(dim, half);
                if (e != null) StorageStore.remove(e);
            }
            actionBar(client, Text.translatable("containerautofill.message.storage_unlinked", StorageStore.linkedCount()));
            return;
        }
        for (BlockPos half : halves) {
            StorageStore.link(dim, half);
            StorageContents.requestOne(dim, half);
        }
        actionBar(client, Text.translatable("containerautofill.message.storage_linked", StorageStore.linkedCount()));
    }

    /** Hotkey: first press sets one corner, second press links every container in the box. */
    public static void boxSelectCorner(MinecraftClient client) {
        BlockPos pos = lookedAtBlock(client);
        if (pos == null) return;
        if (boxCorner == null) {
            boxCorner = pos;
            actionBar(client, Text.translatable("containerautofill.message.storage_box_first", pos.toShortString()));
            return;
        }
        BlockPos a = boxCorner;
        boxCorner = null;
        if (Configs.BOX_SELECT_CREATES_NEW_GROUP.getBooleanValue()) {
            StorageStore.createGroup("Box");
        }
        Identifier dim = dimension(client);
        int added = 0;
        for (BlockPos p : BlockPos.iterate(a, pos)) {
            if (!client.world.isChunkLoaded(p.getX() >> 4, p.getZ() >> 4)) continue;
            BlockEntity blockEntity = client.world.getBlockEntity(p);
            if (!(blockEntity instanceof Inventory)) continue;
            StorageStore.Entry existing = StorageStore.find(dim, p);
            if (existing != null && existing.linked) continue;
            StorageStore.link(dim, p.toImmutable());
            added++;
        }
        refreshAll();
        actionBar(client, Text.translatable("containerautofill.message.storage_box_done", added, StorageStore.activeGroup().name));
    }

    /** Hotkey: mark/unmark the looked-at container as a dump target (links it if needed). */
    public static void toggleDump(MinecraftClient client) {
        BlockPos pos = lookedAtContainer(client);
        if (pos == null) {
            actionBar(client, Text.translatable("containerautofill.message.storage_not_container"));
            return;
        }
        Identifier dim = dimension(client);
        if (StorageStore.find(dim, pos) == null) StorageStore.link(dim, pos);
        StorageStore.Entry entry = StorageStore.find(dim, pos);
        entry.dump = !entry.dump;
        StorageStore.save();
        actionBar(client, Text.translatable(entry.dump ? "containerautofill.message.storage_dump_on" : "containerautofill.message.storage_dump_off"));
    }

    // ---------------------------------------------------------------- moving items

    /** A stack in a linked container: loose in {@code slot}, or ({@code innerSlot >= 0}) inside the shulker box there. */
    public record Source(StorageStore.Entry entry, int slot, int innerSlot, ItemStack stack) {
        public boolean inShulker() {
            return this.innerSlot >= 0;
        }
    }

    /** Matching stacks in linked containers, loose or inside shulker boxes stored there. */
    public static List<Source> findSources(MinecraftClient client, Predicate<ItemStack> matcher) {
        return findSources(client, matcher, true);
    }

    /**
     * Matching stacks in linked containers, nearest container first: every loose stack, then (if
     * {@code inShulkers} and the server supports it) stacks inside shulker boxes stored in them.
     */
    public static List<Source> findSources(MinecraftClient client, Predicate<ItemStack> matcher, boolean inShulkers) {
        List<Source> sources = new ArrayList<>();
        List<Source> nested = new ArrayList<>();
        boolean openBoxes = inShulkers && StorageContents.isShulkerTakeSupported();
        for (StorageStore.Entry entry : sortedByDistance(client, StorageStore.linkedEntries())) {
            StorageContents.Snapshot snapshot = StorageContents.get(entry.dimensionId(), entry.pos());
            if (snapshot == null || !snapshot.available()) continue;
            snapshot.items().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> {
                        if (matcher.test(e.getValue())) {
                            sources.add(new Source(entry, e.getKey(), -1, e.getValue()));
                        } else if (openBoxes) {
                            forEachInShulker(e.getValue(), (inner, innerSlot) -> {
                                if (matcher.test(inner)) nested.add(new Source(entry, e.getKey(), innerSlot, inner));
                            });
                        }
                    });
        }
        sources.addAll(nested);
        return sources;
    }

    /** Calls {@code action} for each non-empty slot of a single (unstacked) shulker box; nothing for other stacks. */
    public static void forEachInShulker(ItemStack box, ObjIntConsumer<ItemStack> action) {
        if (box.getCount() != 1) return;
        DefaultedList<ItemStack> contents = ShulkerUtil.getContents(box);
        if (contents == null) return;
        for (int i = 0; i < contents.size(); i++) {
            if (!contents.get(i).isEmpty()) action.accept(contents.get(i), i);
        }
    }

    public static int countAvailable(MinecraftClient client, Predicate<ItemStack> matcher) {
        return findSources(client, matcher).stream().mapToInt(s -> s.stack().getCount()).sum();
    }

    /** Menu: take {@code amount} items of a kind (queued, one request per click delay). */
    public static void take(MinecraftClient client, ItemStack kind, int amount) {
        if (!StorageContents.isSupported()) {
            actionBar(client, Text.translatable("containerautofill.message.storage_unsupported"));
            return;
        }
        int remaining = amount;
        for (Source source : findSources(client, s -> ItemStack.areItemsAndComponentsEqual(s, kind))) {
            if (remaining <= 0) break;
            int n = Math.min(remaining, source.stack().getCount());
            queueTake(source, n, false);
            remaining -= n;
        }
    }

    /** Sends one take request right away (used by retrieval, which waits for it). */
    public static void takeNow(int requestId, Source source, int count, boolean toHand) {
        ClientPlayNetworking.send(takePayload(requestId, source, count, toHand));
        DIRTY.add(new StorageContents.Key(source.entry().dimensionId(), source.entry().pos()));
        StorageContents.Snapshot snapshot = StorageContents.get(source.entry().dimensionId(), source.entry().pos());
        if (snapshot == null) return;
        ItemStack left = source.stack().copyWithCount(Math.max(0, source.stack().getCount() - count));
        if (!source.inShulker()) {
            if (left.isEmpty()) snapshot.items().remove(source.slot());
            else snapshot.items().put(source.slot(), left);
            return;
        }
        ItemStack box = snapshot.items().get(source.slot());
        DefaultedList<ItemStack> contents = box == null ? null : ShulkerUtil.getContents(box);
        if (contents == null || source.innerSlot() >= contents.size()) return;
        contents.set(source.innerSlot(), left.isEmpty() ? ItemStack.EMPTY : left);
        ItemStack updated = box.copy();
        ShulkerUtil.setContents(updated, contents);
        snapshot.items().put(source.slot(), updated);
    }

    private static void queueTake(Source source, int count, boolean toHand) {
        QUEUE.add(takePayload(0, source, count, toHand));
        DIRTY.add(new StorageContents.Key(source.entry().dimensionId(), source.entry().pos()));
    }

    private static net.minecraft.network.packet.CustomPayload takePayload(int requestId, Source source, int count, boolean toHand) {
        Identifier dimension = source.entry().dimensionId();
        BlockPos pos = source.entry().pos();
        return source.inShulker()
                ? new StoragePayloads.TakeFromShulker(requestId, dimension, pos, source.slot(), source.innerSlot(), count, toHand)
                : new StoragePayloads.Take(requestId, dimension, pos, source.slot(), count, toHand);
    }

    /** Hotkey: move the main inventory (not the hotbar) into the dump containers of the active group. */
    public static void dumpInventory(MinecraftClient client) {
        if (!StorageContents.isSupported()) {
            actionBar(client, Text.translatable("containerautofill.message.storage_unsupported"));
            return;
        }
        List<StorageStore.Entry> dumps = new ArrayList<>();
        for (StorageStore.Entry entry : StorageStore.linkedEntries()) {
            if (entry.dump) dumps.add(entry);
        }
        if (dumps.isEmpty()) {
            actionBar(client, Text.translatable("containerautofill.message.storage_no_dumps"));
            return;
        }
        dumps = sortedByDistance(client, dumps);
        int queued = 0;
        for (int slot = 9; slot < ShulkerUtil.PLAYER_MAIN_SLOTS; slot++) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (stack.isEmpty()) continue;
            StorageStore.Entry target = pickDumpTarget(dumps, stack);
            QUEUE.add(new StoragePayloads.Deposit(target.dimensionId(), target.pos(), slot, stack.getCount()));
            DIRTY.add(new StorageContents.Key(target.dimensionId(), target.pos()));
            queued++;
        }
        actionBar(client, Text.translatable("containerautofill.message.storage_dumping", queued));
    }

    /** Prefers a dump container that already holds this item, then one with a free slot. */
    private static StorageStore.Entry pickDumpTarget(List<StorageStore.Entry> dumps, ItemStack stack) {
        StorageStore.Entry withSpace = null;
        for (StorageStore.Entry entry : dumps) {
            StorageContents.Snapshot snapshot = StorageContents.get(entry.dimensionId(), entry.pos());
            if (snapshot == null || !snapshot.available()) continue;
            for (ItemStack inside : snapshot.items().values()) {
                if (ItemStack.areItemsAndComponentsEqual(inside, stack) && inside.getCount() < inside.getMaxCount()) return entry;
            }
            if (withSpace == null && snapshot.items().size() < 27) withSpace = entry;
        }
        return withSpace != null ? withSpace : dumps.get(0);
    }

    // ---------------------------------------------------------------- look at

    /** Marks every linked container holding this item for a while and reports the nearest. */
    public static void lookAt(MinecraftClient client, ItemStack kind) {
        Set<StorageContents.Key> keys = new HashSet<>();
        List<Source> sources = findSources(client, s -> ItemMatcher.isSameItem(s, kind));
        for (Source source : sources) keys.add(new StorageContents.Key(source.entry().dimensionId(), source.entry().pos()));
        lookAt = keys;
        lookAtUntil = ticks + LOOK_AT_TICKS;
        if (client.player != null) {
            if (sources.isEmpty()) {
                client.player.sendMessage(Text.translatable("containerautofill.message.storage_look_none", kind.getName()), false);
            } else {
                BlockPos nearest = sources.get(0).entry().pos();
                client.player.sendMessage(Text.translatable("containerautofill.message.storage_look_found",
                        kind.getName(), keys.size(), nearest.toShortString()).formatted(Formatting.AQUA), false);
            }
        }
    }

    public static Set<StorageContents.Key> lookAtTargets() {
        return ticks < lookAtUntil ? lookAt : Set.of();
    }

    // ---------------------------------------------------------------- helpers

    public static List<StorageStore.Entry> sortedByDistance(MinecraftClient client, List<StorageStore.Entry> entries) {
        if (client.player == null || client.world == null) return entries;
        Identifier dim = dimension(client);
        Vec3d eye = client.player.getEyePos();
        List<StorageStore.Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingDouble(e -> e.dimension.equals(dim.toString())
                ? e.pos().getSquaredDistance(eye) : Double.MAX_VALUE));
        return sorted;
    }

    private static BlockPos lookedAtBlock(MinecraftClient client) {
        if (client.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            return hit.getBlockPos().toImmutable();
        }
        return null;
    }

    private static BlockPos lookedAtContainer(MinecraftClient client) {
        BlockPos pos = lookedAtBlock(client);
        if (pos == null || client.world == null) return null;
        return client.world.getBlockEntity(pos) instanceof Inventory ? pos : null;
    }

    private static void actionBar(MinecraftClient client, Text text) {
        if (client.player != null) client.player.sendMessage(text, true);
    }

    public static void reset() {
        QUEUE.clear();
        DIRTY.clear();
        refreshQueued = false;
        boxCorner = null;
        lookAt = Set.of();
    }
}
