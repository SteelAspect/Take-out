/*
 * Cytra Container
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.storage;

import dev.steelaspect.containerautofill.network.FillPayloads;
import dev.steelaspect.containerautofill.network.MaterialPayloads;
import dev.steelaspect.containerautofill.takeitout.ShulkerUtil;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * "Get Materials": everything Litematica's material list still needs, minus what you already carry (shulker
 * boxes included), is moved by the server from your linked containers into the shulker boxes in your inventory.
 */
public final class MaterialPull {
    private static int nextRequestId;
    private static int pendingId = -1;
    private static MaterialPayloads.Result lastResult;
    private static List<MaterialPayloads.Want> pendingWants = List.of();

    /**
     * What earlier pulls already delivered for the current material list, per item, so a second pull (after the
     * boxes were full and put away) only asks for what's still needed. Blocks placed since then are taken off it.
     */
    private record Kind(net.minecraft.item.Item item, net.minecraft.component.ComponentChanges components) {
        static Kind of(ItemStack stack) {
            return new Kind(stack.getItem(), stack.getComponentChanges());
        }
    }

    private static Object trackedList;
    private static final java.util.Map<Kind, Integer> DELIVERED = new java.util.HashMap<>();
    private static final java.util.Map<Kind, Integer> MISSING_AT_DELIVERY = new java.util.HashMap<>();

    private MaterialPull() {
    }

    public static boolean isSupported() {
        try {
            return ClientPlayNetworking.canSend(MaterialPayloads.Request.ID);
        } catch (Exception e) {
            return false;
        }
    }

    /** Material list entries (still missing in the world) minus what the player carries. */
    public static List<MaterialPayloads.Want> wantsFromMaterialList(MinecraftClient client, MaterialListBase list) {
        if (trackedList != list) {
            trackedList = list;
            DELIVERED.clear();
            MISSING_AT_DELIVERY.clear();
        }
        List<MaterialPayloads.Want> wants = new ArrayList<>();
        for (MaterialListEntry entry : list.getMaterialsAll()) {
            ItemStack kind = entry.getStack();
            if (kind.isEmpty() || entry.getCountMissing() <= 0) continue;
            int missing = entry.getCountMissing();
            Kind key = Kind.of(kind);
            int delivered = DELIVERED.getOrDefault(key, 0);
            if (delivered > 0) {
                // Blocks placed since the delivery came out of what was delivered.
                int placed = Math.max(0, MISSING_AT_DELIVERY.getOrDefault(key, missing) - missing);
                delivered = Math.max(0, delivered - placed);
                DELIVERED.put(key, delivered);
                MISSING_AT_DELIVERY.put(key, missing);
            }
            int need = missing - Math.max(carried(client.player.getInventory(), kind), delivered);
            if (need > 0) wants.add(new MaterialPayloads.Want(kind.copyWithCount(1), need));
            if (wants.size() >= MaterialPayloads.MAX_KINDS) break;
        }
        return wants;
    }

    /** Hotkey / menu button: pull the open Litematica material list. */
    public static void start(MinecraftClient client) {
        if (client.player == null) return;
        if (!isSupported()) {
            message(client, Text.translatable("containerautofill.message.materials_unsupported"));
            return;
        }
        MaterialListBase list = DataManager.getMaterialList();
        if (list == null) {
            message(client, Text.translatable("containerautofill.message.materials_no_list"));
            return;
        }
        List<MaterialPayloads.Want> wants = wantsFromMaterialList(client, list);
        if (wants.isEmpty()) {
            message(client, Text.translatable("containerautofill.message.materials_nothing", list.getTitle()));
            return;
        }
        pull(client, wants);
    }

    public static void pull(MinecraftClient client, List<MaterialPayloads.Want> wants) {
        List<ItemStack> kinds = new ArrayList<>();
        for (MaterialPayloads.Want want : wants) kinds.add(want.kind());
        List<FillPayloads.Source> sources = pickSources(client, kinds);
        pendingId = ++nextRequestId;
        pendingWants = List.copyOf(wants);
        ClientPlayNetworking.send(new MaterialPayloads.Request(pendingId, wants, sources));
        message(client, Text.translatable("containerautofill.message.materials_pulling", wants.size()));
    }

    public static void onResult(MinecraftClient client, MaterialPayloads.Result result) {
        if (result.requestId() != pendingId || client.player == null) return;
        pendingId = -1;
        lastResult = result;
        rememberDelivered(result);
        StorageActions.refreshAll();
        if (result.noBoxes()) {
            message(client, Text.translatable("containerautofill.message.materials_no_boxes").formatted(Formatting.YELLOW));
            return;
        }
        int missingItems = result.missing().stream().mapToInt(FillPayloads.Missing::count).sum();
        message(client, Text.translatable("containerautofill.message.materials_done", result.moved(), result.missing().size(), missingItems));
        if (result.boxesFull()) {
            client.player.sendMessage(Text.translatable("containerautofill.message.materials_boxes_full").formatted(Formatting.YELLOW), false);
        }
        int shown = 0;
        for (FillPayloads.Missing missing : result.missing()) {
            if (shown++ >= 10) break;
            client.player.sendMessage(Text.literal(" - " + missing.count() + "x ").append(missing.kind().getName()).formatted(Formatting.GRAY), false);
        }
    }

    private static void rememberDelivered(MaterialPayloads.Result result) {
        if (!(trackedList instanceof MaterialListBase list)) return;
        for (MaterialPayloads.Want want : pendingWants) {
            int notMoved = 0;
            for (FillPayloads.Missing missing : result.missing()) {
                if (ItemStack.areItemsAndComponentsEqual(missing.kind(), want.kind())) notMoved += missing.count();
            }
            int moved = Math.max(0, want.count() - notMoved);
            if (moved <= 0) continue;
            Kind key = Kind.of(want.kind());
            DELIVERED.merge(key, moved, Integer::sum);
            for (MaterialListEntry entry : list.getMaterialsAll()) {
                if (ItemStack.areItemsAndComponentsEqual(entry.getStack(), want.kind())) MISSING_AT_DELIVERY.put(key, entry.getCountMissing());
            }
        }
        pendingWants = List.of();
    }

    public static MaterialPayloads.Result lastResult() {
        return lastResult;
    }

    public static boolean isPending() {
        return pendingId >= 0;
    }

    public static void reset() {
        pendingId = -1;
        trackedList = null;
        DELIVERED.clear();
        MISSING_AT_DELIVERY.clear();
    }

    /** Count in the main inventory and inside the shulker boxes carried there. */
    private static int carried(PlayerInventory inventory, ItemStack kind) {
        int count = 0;
        for (int i = 0; i < ShulkerUtil.PLAYER_MAIN_SLOTS; i++) {
            ItemStack stack = inventory.getStack(i);
            if (ItemStack.areItemsAndComponentsEqual(stack, kind)) count += stack.getCount();
            var contents = ShulkerUtil.getContents(stack);
            if (contents != null && stack.getCount() == 1) {
                for (ItemStack inner : contents) if (ItemStack.areItemsAndComponentsEqual(inner, kind)) count += inner.getCount();
            }
        }
        return count;
    }

    /** Linked containers known to hold a wanted item (loose or in a shulker box) first, then unknown ones. */
    private static List<FillPayloads.Source> pickSources(MinecraftClient client, List<ItemStack> kinds) {
        List<FillPayloads.Source> known = new ArrayList<>();
        List<FillPayloads.Source> unknown = new ArrayList<>();
        for (StorageStore.Entry entry : StorageActions.sortedByDistance(client, StorageStore.linkedEntries())) {
            FillPayloads.Source source = new FillPayloads.Source(entry.dimensionId(), entry.pos());
            StorageContents.Snapshot snapshot = StorageContents.get(entry.dimensionId(), entry.pos());
            if (snapshot == null) {
                unknown.add(source);
                continue;
            }
            if (!snapshot.available()) continue;
            boolean[] holds = {false};
            for (ItemStack stack : snapshot.items().values()) {
                if (kinds.stream().anyMatch(k -> ItemStack.areItemsAndComponentsEqual(stack, k))) holds[0] = true;
                StorageActions.forEachInShulker(stack, (inner, slot) -> {
                    if (kinds.stream().anyMatch(k -> ItemStack.areItemsAndComponentsEqual(inner, k))) holds[0] = true;
                });
                if (holds[0]) break;
            }
            if (holds[0]) known.add(source);
        }
        List<FillPayloads.Source> out = new ArrayList<>(known);
        for (FillPayloads.Source source : unknown) {
            if (out.size() >= FillPayloads.MAX_SOURCES) break;
            out.add(source);
        }
        return out.size() > FillPayloads.MAX_SOURCES ? out.subList(0, FillPayloads.MAX_SOURCES) : out;
    }

    private static void message(MinecraftClient client, Text text) {
        if (client.player != null) client.player.sendMessage(text, true);
    }
}
