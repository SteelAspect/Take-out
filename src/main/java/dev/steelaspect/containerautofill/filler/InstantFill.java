/*
 * Cytra Container
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.filler;

import dev.steelaspect.containerautofill.config.Configs;
import dev.steelaspect.containerautofill.network.FillPayloads;
import dev.steelaspect.containerautofill.network.StoragePayloads;
import dev.steelaspect.containerautofill.storage.StorageActions;
import dev.steelaspect.containerautofill.storage.StorageContents;
import dev.steelaspect.containerautofill.storage.StorageStore;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.block.CrafterBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Server-side instant fill: the server puts every expected item into the container in one go, without
 * the container being opened. Needs the integrated server (singleplayer/LAN) or the mod installed on the server.
 */
public final class InstantFill {
    private static final int TIMEOUT_TICKS = 100;

    private static int nextRequestId;
    private static Pending pending;

    private static final class Pending {
        final int requestId;
        final int containers;
        int repliesLeft;
        int filled;
        int wrong;
        boolean unavailable;
        long deadline;
        final Map<ItemMatcher.StackKey, Integer> missing = new LinkedHashMap<>();
        final List<BlockPos> targets = new ArrayList<>();

        Pending(int requestId, int containers, int replies, long deadline) {
            this.requestId = requestId;
            this.containers = containers;
            this.repliesLeft = replies;
            this.deadline = deadline;
        }
    }

    private static long ticks;

    private InstantFill() {
    }

    public static boolean isSupported() {
        try {
            return ClientPlayNetworking.canSend(FillPayloads.Fill.ID);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isRunning() {
        return pending != null;
    }

    public static void reset() {
        pending = null;
    }

    public static void tick(MinecraftClient client) {
        ticks++;
        if (pending != null && ticks > pending.deadline) {
            pending = null;
            if (client.player != null) {
                client.player.sendMessage(Text.translatable("containerautofill.message.instant_fill_timeout").formatted(Formatting.RED), true);
            }
        }
    }

    /** Sends the fill request(s) for the container at {@code pos}. The caller already checked the schematic. */
    public static void start(MinecraftClient client, BlockPos pos, SchematicContainerReader.Halves halves) {
        startMany(client, List.of(halves));
    }

    /**
     * Fills several containers in one go (area fill). The server handles the requests in order, so list the
     * nearest container first: it gets items first when there aren't enough for all.
     */
    public static void startMany(MinecraftClient client, List<SchematicContainerReader.Halves> containers) {
        Identifier dimension = client.world.getRegistryKey().getValue();
        int requestId = ++nextRequestId;
        Set<BlockPos> targets = new HashSet<>();
        int parts = 0;
        for (SchematicContainerReader.Halves halves : containers) {
            for (SchematicContainerReader.Part part : halves.parts()) {
                targets.add(part.pos());
                parts++;
            }
        }
        pending = new Pending(requestId, containers.size(), parts, ticks + TIMEOUT_TICKS + parts);
        pending.targets.addAll(targets);

        for (SchematicContainerReader.Halves halves : containers) {
            for (SchematicContainerReader.Part part : halves.parts()) {
                send(client, requestId, dimension, part, targets);
            }
        }
        Configs.debug("Instant fill #{}: {} container(s), {} part(s)", requestId, containers.size(), parts);
    }

    private static void send(MinecraftClient client, int requestId, Identifier dimension, SchematicContainerReader.Part part, Set<BlockPos> targets) {
        List<FillPayloads.Source> sources = Configs.USE_LINKED_CONTAINERS.getBooleanValue()
                ? pickSources(client, dimension, targets, List.copyOf(part.single().items().values())) : List.of();
        List<StoragePayloads.SlotStack> expected = new ArrayList<>();
        part.single().items().forEach((slot, stack) -> expected.add(new StoragePayloads.SlotStack(slot, stack)));
        boolean crafter = part.single().state().getBlock() instanceof CrafterBlock;
        ClientPlayNetworking.send(new FillPayloads.Fill(requestId, dimension, part.pos(), expected,
                new ArrayList<>(part.single().disabledSlots()), crafter,
                Configs.CLEAR_WRONG_ITEMS.getBooleanValue(), Configs.USE_TAKEITOUT_SOURCES.getBooleanValue(),
                Configs.CREATIVE_FILL.getBooleanValue(), sources));
    }

    /**
     * Linked containers to offer the server: ones known to hold a needed item first, then ones whose contents
     * aren't known yet. Capped, so any number of linked containers fits in one request.
     */
    private static List<FillPayloads.Source> pickSources(MinecraftClient client, Identifier dimension, Set<BlockPos> targets, List<ItemStack> wanted) {
        List<FillPayloads.Source> known = new ArrayList<>();
        List<FillPayloads.Source> unknown = new ArrayList<>();
        for (StorageStore.Entry entry : StorageActions.sortedByDistance(client, StorageStore.linkedEntries())) {
            if (entry.dimensionId().equals(dimension) && targets.contains(entry.pos())) continue;
            FillPayloads.Source source = new FillPayloads.Source(entry.dimensionId(), entry.pos());
            StorageContents.Snapshot snapshot = StorageContents.get(entry.dimensionId(), entry.pos());
            if (snapshot == null) {
                unknown.add(source);
            } else if (snapshot.available() && snapshot.items().values().stream()
                    .anyMatch(have -> wanted.stream().anyMatch(want -> ItemStack.areItemsAndComponentsEqual(have, want)))) {
                known.add(source);
            }
        }
        List<FillPayloads.Source> out = new ArrayList<>(known);
        for (FillPayloads.Source source : unknown) {
            if (out.size() >= FillPayloads.MAX_SOURCES) break;
            out.add(source);
        }
        return out.size() > FillPayloads.MAX_SOURCES ? out.subList(0, FillPayloads.MAX_SOURCES) : out;
    }

    public static void onResult(MinecraftClient client, FillPayloads.Result result) {
        Pending p = pending;
        if (p == null || p.requestId != result.requestId()) return;
        p.filled += result.filledSlots();
        p.wrong += result.wrongSlots();
        p.unavailable |= !result.available();
        for (FillPayloads.Missing missing : result.missing()) {
            p.missing.merge(new ItemMatcher.StackKey(missing.kind()), missing.count(), Integer::sum);
        }
        if (--p.repliesLeft > 0) return;
        pending = null;
        report(client, p);
        StorageActions.refreshAll();
        // Re-read the filled containers right away so the highlight shows the new state.
        if (client.world != null) {
            Identifier dimension = client.world.getRegistryKey().getValue();
            for (BlockPos target : p.targets) StorageContents.requestOne(dimension, target);
        }
    }

    private static void report(MinecraftClient client, Pending p) {
        int missingTotal = p.missing.values().stream().mapToInt(Integer::intValue).sum();
        AutoFillController.setLastResult(new FillResult(p.filled, missingTotal, p.wrong, 0, 0, false));
        if (client.player == null) return;
        if (p.unavailable) {
            client.player.sendMessage(Text.translatable("containerautofill.message.instant_fill_unavailable").formatted(Formatting.RED), false);
        }
        if (p.containers > 1) {
            client.player.sendMessage(Text.translatable("containerautofill.message.area_summary", p.filled, p.containers, missingTotal), true);
        } else {
            client.player.sendMessage(Text.translatable("containerautofill.message.summary", p.filled, missingTotal), true);
        }
        if (!p.missing.isEmpty()) {
            client.player.sendMessage(Text.translatable("containerautofill.message.missing_header", missingTotal).formatted(Formatting.GOLD), false);
            for (Map.Entry<ItemMatcher.StackKey, Integer> e : p.missing.entrySet()) {
                MutableText line = Text.literal(" - " + e.getValue() + "x ").formatted(Formatting.GRAY)
                        .append(e.getKey().stack().getName().copy().formatted(Formatting.WHITE));
                client.player.sendMessage(line, false);
            }
        }
        if (p.wrong > 0) {
            String key = Configs.CLEAR_WRONG_ITEMS.getBooleanValue()
                    ? "containerautofill.message.wrong_items_remaining" : "containerautofill.message.wrong_items_kept";
            client.player.sendMessage(Text.translatable(key, p.wrong).formatted(Formatting.YELLOW), false);
        }
    }
}
