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
        List<MaterialPayloads.Want> wants = new ArrayList<>();
        for (MaterialListEntry entry : list.getMaterialsAll()) {
            ItemStack kind = entry.getStack();
            if (kind.isEmpty() || entry.getCountMissing() <= 0) continue;
            int need = entry.getCountMissing() - carried(client.player.getInventory(), kind);
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
        ClientPlayNetworking.send(new MaterialPayloads.Request(pendingId, wants, sources));
        message(client, Text.translatable("containerautofill.message.materials_pulling", wants.size()));
    }

    public static void onResult(MinecraftClient client, MaterialPayloads.Result result) {
        if (result.requestId() != pendingId || client.player == null) return;
        pendingId = -1;
        lastResult = result;
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

    public static MaterialPayloads.Result lastResult() {
        return lastResult;
    }

    public static boolean isPending() {
        return pendingId >= 0;
    }

    public static void reset() {
        pendingId = -1;
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
