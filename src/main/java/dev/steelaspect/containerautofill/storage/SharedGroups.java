/*
 * Cytra Container
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.storage;

import dev.steelaspect.containerautofill.network.SharedGroupPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** Client side of shared groups: the server's list, sharing your own groups, and adding someone else's. */
public final class SharedGroups {
    private static List<SharedGroupPayloads.Summary> list = List.of();

    private SharedGroups() {
    }

    /** The server runs Cytra Container 1.3.0 or newer. */
    public static boolean isSupported() {
        try {
            return ClientPlayNetworking.canSend(SharedGroupPayloads.Share.ID);
        } catch (Exception e) {
            return false;
        }
    }

    public static List<SharedGroupPayloads.Summary> list() {
        return list;
    }

    /** The group of yours shared under this name, if any. */
    public static SharedGroupPayloads.Summary sharedByMe(String name) {
        for (SharedGroupPayloads.Summary summary : list) {
            if (summary.mine() && summary.name().equals(name)) return summary;
        }
        return null;
    }

    public static void requestList() {
        if (isSupported()) ClientPlayNetworking.send(SharedGroupPayloads.ListRequest.INSTANCE);
    }

    /** Shares the group with everyone on the server (or updates the copy you shared before). */
    public static void share(MinecraftClient client, StorageStore.Group group) {
        if (!isSupported()) {
            message(client, Text.translatable("containerautofill.message.shared_unsupported"));
            return;
        }
        List<SharedGroupPayloads.Container> containers = new ArrayList<>();
        for (StorageStore.Entry entry : group.containers) {
            if (containers.size() >= SharedGroupPayloads.MAX_CONTAINERS) break;
            containers.add(new SharedGroupPayloads.Container(entry.dimensionId(), entry.pos(), entry.linked, entry.dump));
        }
        String name = group.name.length() > SharedGroupPayloads.MAX_NAME ? group.name.substring(0, SharedGroupPayloads.MAX_NAME) : group.name;
        ClientPlayNetworking.send(new SharedGroupPayloads.Share(name, containers));
        message(client, Text.translatable("containerautofill.message.shared", group.name, containers.size()));
    }

    public static void unshare(int groupId) {
        if (isSupported()) ClientPlayNetworking.send(new SharedGroupPayloads.Unshare(groupId));
    }

    /** Asks for the group's containers; {@link #onData} adds it to your groups. */
    public static void add(int groupId) {
        if (isSupported()) ClientPlayNetworking.send(new SharedGroupPayloads.Get(groupId));
    }

    public static void onList(MinecraftClient client, SharedGroupPayloads.GroupList payload) {
        list = List.copyOf(payload.groups());
        if (client.currentScreen instanceof StorageScreen screen) screen.onSharedGroupsChanged();
    }

    public static void onData(MinecraftClient client, SharedGroupPayloads.GroupData payload) {
        List<StorageStore.Entry> entries = new ArrayList<>();
        for (SharedGroupPayloads.Container c : payload.containers()) {
            StorageStore.Entry entry = new StorageStore.Entry(c.dimension(), c.pos());
            entry.linked = c.linked();
            entry.dump = c.dump();
            entries.add(entry);
        }
        StorageStore.Group group = StorageStore.addGroup(payload.name(), entries);
        StorageActions.refreshAll();
        message(client, Text.translatable("containerautofill.message.shared_added", group.name, entries.size()));
        if (client.currentScreen instanceof StorageScreen screen) screen.onSharedGroupsChanged();
    }

    public static void reset() {
        list = List.of();
    }

    private static void message(MinecraftClient client, Text text) {
        if (client.player != null) client.player.sendMessage(text, true);
    }
}
