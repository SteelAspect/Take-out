/*
 * Cytra Container
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * Linked-container groups shared with everyone on the server. A player shares a group, every player
 * running this mod sees it in the storage menu's Groups tab, and anyone can add a copy to their own groups.
 */
public final class SharedGroupPayloads {
    public static final int MAX_NAME = 64;
    public static final int MAX_CONTAINERS = 20000;
    public static final int MAX_GROUPS = 200;
    /** Big groups go over vanilla's packet limits; Fabric splits these payloads. */
    public static final int MAX_PAYLOAD_BYTES = 2 * 1024 * 1024;

    private SharedGroupPayloads() {
    }

    private static Identifier id(String path) {
        return Identifier.of("containerautofill", path);
    }

    public record Container(Identifier dimension, BlockPos pos, boolean linked, boolean dump) {
        public static final PacketCodec<RegistryByteBuf, Container> CODEC = PacketCodec.tuple(
                Identifier.PACKET_CODEC, Container::dimension,
                BlockPos.PACKET_CODEC, Container::pos,
                PacketCodecs.BOOLEAN, Container::linked,
                PacketCodecs.BOOLEAN, Container::dump,
                Container::new);
    }

    /** Share a group (or update the one this player already shared under that name). */
    public record Share(String name, List<Container> containers) implements CustomPayload {
        public static final Id<Share> ID = new Id<>(id("group_share"));
        public static final PacketCodec<RegistryByteBuf, Share> CODEC = PacketCodec.tuple(
                PacketCodecs.string(MAX_NAME), Share::name,
                Container.CODEC.collect(PacketCodecs.toList(MAX_CONTAINERS)), Share::containers,
                Share::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Stop sharing a group: its owner, or a server operator. */
    public record Unshare(int groupId) implements CustomPayload {
        public static final Id<Unshare> ID = new Id<>(id("group_unshare"));
        public static final PacketCodec<RegistryByteBuf, Unshare> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, Unshare::groupId,
                Unshare::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Ask for the list of shared groups; answered with {@link GroupList}. */
    public record ListRequest() implements CustomPayload {
        public static final ListRequest INSTANCE = new ListRequest();
        public static final Id<ListRequest> ID = new Id<>(id("group_list_request"));
        public static final PacketCodec<RegistryByteBuf, ListRequest> CODEC = PacketCodec.unit(INSTANCE);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Ask for a shared group's containers (to add it); answered with {@link GroupData}. */
    public record Get(int groupId) implements CustomPayload {
        public static final Id<Get> ID = new Id<>(id("group_get"));
        public static final PacketCodec<RegistryByteBuf, Get> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, Get::groupId,
                Get::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** One shared group as listed in the menu. {@code mine}: shared by the receiving player. */
    public record Summary(int groupId, String name, String owner, int count, boolean mine, boolean removable) {
        public static final PacketCodec<RegistryByteBuf, Summary> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, Summary::groupId,
                PacketCodecs.string(MAX_NAME), Summary::name,
                PacketCodecs.string(64), Summary::owner,
                PacketCodecs.VAR_INT, Summary::count,
                PacketCodecs.BOOLEAN, Summary::mine,
                PacketCodecs.BOOLEAN, Summary::removable,
                Summary::new);
    }

    /** All shared groups; sent on request and to everyone whenever a group is shared or removed. */
    public record GroupList(List<Summary> groups) implements CustomPayload {
        public static final Id<GroupList> ID = new Id<>(id("group_list"));
        public static final PacketCodec<RegistryByteBuf, GroupList> CODEC = PacketCodec.tuple(
                Summary.CODEC.collect(PacketCodecs.toList(MAX_GROUPS)), GroupList::groups,
                GroupList::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public record GroupData(int groupId, String name, List<Container> containers) implements CustomPayload {
        public static final Id<GroupData> ID = new Id<>(id("group_data"));
        public static final PacketCodec<RegistryByteBuf, GroupData> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, GroupData::groupId,
                PacketCodecs.string(MAX_NAME), GroupData::name,
                Container.CODEC.collect(PacketCodecs.toList(MAX_CONTAINERS)), GroupData::containers,
                GroupData::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }
}
