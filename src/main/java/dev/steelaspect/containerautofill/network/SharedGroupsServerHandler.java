/*
 * Cytra Container
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.steelaspect.containerautofill.Reference;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Server side of shared groups. Groups are kept in the world folder
 * ({@code data/containerautofill_shared_groups.json}), and every change is sent to all players running
 * this mod, so an open Groups tab updates straight away.
 */
public final class SharedGroupsServerHandler {
    private static final int MAX_PER_PLAYER = 20;
    private static final Gson GSON = new GsonBuilder().create();

    private static final class StoredContainer {
        String dimension;
        int x;
        int y;
        int z;
        boolean linked = true;
        boolean dump;
    }

    private static final class StoredGroup {
        int id;
        String name;
        String ownerId;
        String ownerName;
        List<StoredContainer> containers = new ArrayList<>();
    }

    private static final class Data {
        int nextId = 1;
        List<StoredGroup> groups = new ArrayList<>();
    }

    private static MinecraftServer loadedFor;
    private static Data data = new Data();

    private SharedGroupsServerHandler() {
    }

    static void register() {
        PayloadTypeRegistry.playC2S().registerLarge(SharedGroupPayloads.Share.ID, SharedGroupPayloads.Share.CODEC, SharedGroupPayloads.MAX_PAYLOAD_BYTES);
        PayloadTypeRegistry.playC2S().register(SharedGroupPayloads.Unshare.ID, SharedGroupPayloads.Unshare.CODEC);
        PayloadTypeRegistry.playC2S().register(SharedGroupPayloads.ListRequest.ID, SharedGroupPayloads.ListRequest.CODEC);
        PayloadTypeRegistry.playC2S().register(SharedGroupPayloads.Get.ID, SharedGroupPayloads.Get.CODEC);
        PayloadTypeRegistry.playS2C().register(SharedGroupPayloads.GroupList.ID, SharedGroupPayloads.GroupList.CODEC);
        PayloadTypeRegistry.playS2C().registerLarge(SharedGroupPayloads.GroupData.ID, SharedGroupPayloads.GroupData.CODEC, SharedGroupPayloads.MAX_PAYLOAD_BYTES);

        ServerPlayNetworking.registerGlobalReceiver(SharedGroupPayloads.Share.ID, (p, ctx) -> share(ctx.player(), p));
        ServerPlayNetworking.registerGlobalReceiver(SharedGroupPayloads.Unshare.ID, (p, ctx) -> unshare(ctx.player(), p.groupId()));
        ServerPlayNetworking.registerGlobalReceiver(SharedGroupPayloads.ListRequest.ID, (p, ctx) -> sendList(ctx.player()));
        ServerPlayNetworking.registerGlobalReceiver(SharedGroupPayloads.Get.ID, (p, ctx) -> sendGroup(ctx.player(), p.groupId()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (loadedFor == server) {
                loadedFor = null;
                data = new Data();
            }
        });
    }

    private static void share(ServerPlayerEntity player, SharedGroupPayloads.Share request) {
        MinecraftServer server = player.getEntityWorld().getServer();
        Data d = data(server);
        String name = request.name().trim().isEmpty() ? "Group" : request.name().trim();
        String owner = player.getUuidAsString();

        StoredGroup group = null;
        int owned = 0;
        for (StoredGroup g : d.groups) {
            if (!owner.equals(g.ownerId)) continue;
            owned++;
            if (g.name.equals(name)) group = g;
        }
        if (group == null) {
            if (owned >= MAX_PER_PLAYER || d.groups.size() >= SharedGroupPayloads.MAX_GROUPS) {
                player.sendMessage(Text.translatable("containerautofill.message.shared_limit", MAX_PER_PLAYER), false);
                return;
            }
            group = new StoredGroup();
            group.id = d.nextId++;
            group.name = name;
            group.ownerId = owner;
            d.groups.add(group);
        }
        group.ownerName = player.getName().getString();
        group.containers = new ArrayList<>();
        for (SharedGroupPayloads.Container c : request.containers()) {
            StoredContainer stored = new StoredContainer();
            stored.dimension = c.dimension().toString();
            stored.x = c.pos().getX();
            stored.y = c.pos().getY();
            stored.z = c.pos().getZ();
            stored.linked = c.linked();
            stored.dump = c.dump();
            group.containers.add(stored);
        }
        save(server);
        broadcast(server);
    }

    private static void unshare(ServerPlayerEntity player, int groupId) {
        MinecraftServer server = player.getEntityWorld().getServer();
        boolean removed = data(server).groups.removeIf(g -> g.id == groupId && canRemove(player, g));
        if (removed) {
            save(server);
            broadcast(server);
        } else {
            sendList(player);
        }
    }

    private static boolean canRemove(ServerPlayerEntity player, StoredGroup group) {
        return player.getUuidAsString().equals(group.ownerId)
                || player.getEntityWorld().getServer().getPlayerManager().isOperator(player.getPlayerConfigEntry());
    }

    private static void sendList(ServerPlayerEntity player) {
        if (!ServerPlayNetworking.canSend(player, SharedGroupPayloads.GroupList.ID)) return;
        List<SharedGroupPayloads.Summary> list = new ArrayList<>();
        for (StoredGroup g : data(player.getEntityWorld().getServer()).groups) {
            list.add(new SharedGroupPayloads.Summary(g.id, g.name, g.ownerName == null ? "?" : g.ownerName, g.containers.size(),
                    player.getUuidAsString().equals(g.ownerId), canRemove(player, g)));
        }
        ServerPlayNetworking.send(player, new SharedGroupPayloads.GroupList(list));
    }

    private static void sendGroup(ServerPlayerEntity player, int groupId) {
        if (!ServerPlayNetworking.canSend(player, SharedGroupPayloads.GroupData.ID)) return;
        for (StoredGroup g : data(player.getEntityWorld().getServer()).groups) {
            if (g.id != groupId) continue;
            List<SharedGroupPayloads.Container> containers = new ArrayList<>();
            for (StoredContainer c : g.containers) {
                Identifier dimension = Identifier.tryParse(c.dimension);
                if (dimension != null) containers.add(new SharedGroupPayloads.Container(dimension, new BlockPos(c.x, c.y, c.z), c.linked, c.dump));
            }
            ServerPlayNetworking.send(player, new SharedGroupPayloads.GroupData(g.id, g.name, containers));
            return;
        }
        sendList(player); // gone in the meantime: refresh the menu instead
    }

    private static void broadcast(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) sendList(player);
    }

    // ---------------------------------------------------------------- file

    private static Path file(MinecraftServer server) {
        return server.getSavePath(WorldSavePath.ROOT).resolve("data").resolve("containerautofill_shared_groups.json");
    }

    private static Data data(MinecraftServer server) {
        if (loadedFor == server) return data;
        loadedFor = server;
        data = new Data();
        Path file = file(server);
        if (Files.isRegularFile(file)) {
            try {
                Data loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
                if (loaded != null && loaded.groups != null) {
                    loaded.groups.removeIf(g -> g == null || g.name == null || g.containers == null);
                    data = loaded;
                }
            } catch (IOException | JsonParseException e) {
                Reference.LOGGER.warn("Could not read shared groups from {}; starting empty (old file kept as .broken)", file, e);
                try {
                    Files.move(file, file.resolveSibling(file.getFileName() + ".broken"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                }
            }
        }
        return data;
    }

    private static void save(MinecraftServer server) {
        Path file = file(server);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(data(server)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Reference.LOGGER.warn("Could not save shared groups to {}", file, e);
        }
    }
}
