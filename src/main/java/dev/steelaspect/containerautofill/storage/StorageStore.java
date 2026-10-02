/*
 * Container Auto Fill
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.steelaspect.containerautofill.Reference;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Linked containers, organised in named groups, saved per world/server under
 * {@code config/containerautofill/storage/}. Only the active group is used as a source.
 */
public final class StorageStore {
    public static final int MAX_PER_GROUP = 500;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** A container in a group. {@code linked=false} keeps it listed but stops using it as a source. */
    public static final class Entry {
        public String dimension;
        public int x;
        public int y;
        public int z;
        public boolean linked = true;
        public boolean dump;

        public Entry() {
        }

        public Entry(Identifier dimension, BlockPos pos) {
            this.dimension = dimension.toString();
            this.x = pos.getX();
            this.y = pos.getY();
            this.z = pos.getZ();
        }

        public Identifier dimensionId() {
            return Identifier.of(this.dimension);
        }

        public BlockPos pos() {
            return new BlockPos(this.x, this.y, this.z);
        }

        public boolean is(Identifier dimension, BlockPos pos) {
            return this.x == pos.getX() && this.y == pos.getY() && this.z == pos.getZ() && Objects.equals(this.dimension, dimension.toString());
        }
    }

    public static final class Group {
        public String name;
        public List<Entry> containers = new ArrayList<>();

        public Group() {
        }

        public Group(String name) {
            this.name = name;
        }
    }

    private static final class Data {
        String activeGroup;
        List<Group> groups = new ArrayList<>();
    }

    private static String worldKey;
    private static Data data = new Data();

    private StorageStore() {
    }

    public static String worldKey() {
        return worldKey;
    }

    /** Loads (or creates) the data for the world the client just joined. */
    public static void load(MinecraftClient client) {
        worldKey = computeWorldKey(client);
        data = new Data();
        Path file = file();
        if (file != null && Files.isRegularFile(file)) {
            try {
                Data loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
                if (loaded != null && loaded.groups != null) data = loaded;
            } catch (IOException | JsonParseException e) {
                Reference.LOGGER.warn("Could not read linked containers from {}", file, e);
            }
        }
        if (data.groups.isEmpty()) data.groups.add(new Group("default"));
        if (group(data.activeGroup) == null) data.activeGroup = data.groups.get(0).name;
    }

    public static void unload() {
        worldKey = null;
        data = new Data();
    }

    public static void save() {
        Path file = file();
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Reference.LOGGER.warn("Could not save linked containers to {}", file, e);
        }
    }

    private static Path file() {
        if (worldKey == null) return null;
        String safe = worldKey.replaceAll("[^A-Za-z0-9._-]", "_");
        return FabricLoader.getInstance().getConfigDir().resolve(Reference.MOD_ID).resolve("storage").resolve(safe + ".json");
    }

    private static String computeWorldKey(MinecraftClient client) {
        if (client.isIntegratedServerRunning() && client.getServer() != null) {
            return "singleplayer:" + client.getServer().getSaveProperties().getLevelName();
        }
        ServerInfo info = client.getCurrentServerEntry();
        return "server:" + (info != null ? info.address : "unknown");
    }

    // ---------------------------------------------------------------- groups

    public static List<Group> groups() {
        return data.groups;
    }

    public static Group activeGroup() {
        Group group = group(data.activeGroup);
        if (group == null) {
            if (data.groups.isEmpty()) data.groups.add(new Group("default"));
            group = data.groups.get(0);
            data.activeGroup = group.name;
        }
        return group;
    }

    public static Group group(String name) {
        for (Group group : data.groups) {
            if (group.name.equals(name)) return group;
        }
        return null;
    }

    public static void setActive(String name) {
        if (group(name) != null) {
            data.activeGroup = name;
            save();
        }
    }

    public static Group createGroup(String requestedName) {
        String base = requestedName == null || requestedName.isBlank() ? "Group" : requestedName.trim();
        String name = base;
        for (int i = 2; group(name) != null; i++) name = base + " " + i;
        Group group = new Group(name);
        data.groups.add(group);
        data.activeGroup = name;
        save();
        return group;
    }

    public static void deleteGroup(String name) {
        data.groups.removeIf(g -> g.name.equals(name));
        activeGroup();
        save();
    }

    public static String exportGroup(Group group) {
        return GSON.toJson(group);
    }

    public static Group importGroup(String json) {
        try {
            Group imported = GSON.fromJson(json, Group.class);
            if (imported == null || imported.containers == null) return null;
            Group group = createGroup(imported.name);
            for (Entry entry : imported.containers) {
                if (entry != null && entry.dimension != null && group.containers.size() < MAX_PER_GROUP) group.containers.add(entry);
            }
            save();
            return group;
        } catch (JsonParseException | IllegalStateException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- containers (active group)

    public static Entry find(Identifier dimension, BlockPos pos) {
        for (Entry entry : activeGroup().containers) {
            if (entry.is(dimension, pos)) return entry;
        }
        return null;
    }

    /** Adds the container to the active group (or re-links it). Returns false when the group is full. */
    public static boolean link(Identifier dimension, BlockPos pos) {
        Entry existing = find(dimension, pos);
        if (existing != null) {
            existing.linked = true;
            save();
            return true;
        }
        Group group = activeGroup();
        if (group.containers.size() >= MAX_PER_GROUP) return false;
        group.containers.add(new Entry(dimension, pos));
        save();
        return true;
    }

    public static void remove(Entry entry) {
        activeGroup().containers.remove(entry);
        save();
    }

    public static void removeAll() {
        activeGroup().containers.clear();
        save();
    }

    public static List<Entry> linkedEntries() {
        List<Entry> out = new ArrayList<>();
        for (Entry entry : activeGroup().containers) {
            if (entry.linked) out.add(entry);
        }
        return out;
    }

    public static int linkedCount() {
        return linkedEntries().size();
    }
}
