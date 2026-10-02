/*
 * Container Auto Fill
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
 * the container being opened. Needs the integrated server (singleplayer/LAN) or containerautofill-server.
 */
public final class InstantFill {
    private static final int TIMEOUT_TICKS = 100;

    private static int nextRequestId;
    private static Pending pending;

    private static final class Pending {
        final int requestId;
        int repliesLeft;
        int filled;
        int wrong;
        boolean unavailable;
        long deadline;
        final Map<ItemMatcher.StackKey, Integer> missing = new LinkedHashMap<>();

        Pending(int requestId, int replies, long deadline) {
            this.requestId = requestId;
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
        Identifier dimension = client.world.getRegistryKey().getValue();
        int requestId = ++nextRequestId;
        pending = new Pending(requestId, halves.parts().size(), ticks + TIMEOUT_TICKS);

        Set<BlockPos> targets = new HashSet<>();
        for (SchematicContainerReader.Part part : halves.parts()) targets.add(part.pos());
        List<ItemStack> wanted = new ArrayList<>();
        for (SchematicContainerReader.Part part : halves.parts()) wanted.addAll(part.single().items().values());
        List<FillPayloads.Source> sources = Configs.USE_LINKED_CONTAINERS.getBooleanValue()
                ? pickSources(client, dimension, targets, wanted) : List.of();

        for (SchematicContainerReader.Part part : halves.parts()) {
            List<StoragePayloads.SlotStack> expected = new ArrayList<>();
            part.single().items().forEach((slot, stack) -> expected.add(new StoragePayloads.SlotStack(slot, stack)));
            boolean crafter = part.single().state().getBlock() instanceof CrafterBlock;
            ClientPlayNetworking.send(new FillPayloads.Fill(requestId, dimension, part.pos(), expected,
                    new ArrayList<>(part.single().disabledSlots()), crafter,
                    Configs.CLEAR_WRONG_ITEMS.getBooleanValue(), Configs.USE_TAKEITOUT_SOURCES.getBooleanValue(), sources));
        }
        Configs.debug("Instant fill #{} of {} ({} part(s), {} linked sources)", requestId, pos, halves.parts().size(), sources.size());
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
    }

    private static void report(MinecraftClient client, Pending p) {
        int missingTotal = p.missing.values().stream().mapToInt(Integer::intValue).sum();
        AutoFillController.setLastResult(new FillResult(p.filled, missingTotal, p.wrong, 0, 0, false));
        if (client.player == null) return;
        if (p.unavailable) {
            client.player.sendMessage(Text.translatable("containerautofill.message.instant_fill_unavailable").formatted(Formatting.RED), false);
        }
        client.player.sendMessage(Text.translatable("containerautofill.message.summary", p.filled, missingTotal), true);
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
