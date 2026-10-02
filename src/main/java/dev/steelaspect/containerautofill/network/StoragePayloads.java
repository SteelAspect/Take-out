/*
 * Container Auto Fill
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.network;

import net.minecraft.item.ItemStack;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * Linked-storage protocol. The client names containers by dimension + position; the server moves items
 * between those containers and the player's inventory, as long as the container's chunk is loaded.
 */
public final class StoragePayloads {
    private StoragePayloads() {
    }

    private static Identifier id(String path) {
        return Identifier.of("containerautofill", path);
    }

    /**
     * Move up to {@code count} items from a container slot into the player's inventory (or main hand).
     * {@code requestId} is echoed in the {@link Taken} answer (0 when the client doesn't wait for it).
     */
    public record Take(int requestId, Identifier dimension, BlockPos pos, int slot, int count, boolean toHand) implements CustomPayload {
        public static final Id<Take> ID = new Id<>(id("take"));
        public static final PacketCodec<RegistryByteBuf, Take> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, Take::requestId,
                Identifier.PACKET_CODEC, Take::dimension,
                BlockPos.PACKET_CODEC, Take::pos,
                PacketCodecs.VAR_INT, Take::slot,
                PacketCodecs.VAR_INT, Take::count,
                PacketCodecs.BOOLEAN, Take::toHand,
                Take::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /**
     * Answer to {@link Take}, sent after the player's inventory has been synced: how many items were moved
     * (0 if the slot was empty or the container couldn't be reached), so the client never waits on a miss.
     */
    public record Taken(int requestId, Identifier dimension, BlockPos pos, int slot, int moved) implements CustomPayload {
        public static final Id<Taken> ID = new Id<>(id("taken"));
        public static final PacketCodec<RegistryByteBuf, Taken> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, Taken::requestId,
                Identifier.PACKET_CODEC, Taken::dimension,
                BlockPos.PACKET_CODEC, Taken::pos,
                PacketCodecs.VAR_INT, Taken::slot,
                PacketCodecs.VAR_INT, Taken::moved,
                Taken::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Move up to {@code count} items from a player inventory slot (0..35) into a container. */
    public record Deposit(Identifier dimension, BlockPos pos, int playerSlot, int count) implements CustomPayload {
        public static final Id<Deposit> ID = new Id<>(id("deposit"));
        public static final PacketCodec<RegistryByteBuf, Deposit> CODEC = PacketCodec.tuple(
                Identifier.PACKET_CODEC, Deposit::dimension,
                BlockPos.PACKET_CODEC, Deposit::pos,
                PacketCodecs.VAR_INT, Deposit::playerSlot,
                PacketCodecs.VAR_INT, Deposit::count,
                Deposit::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Ask for the contents of some containers; answered with {@link Contents}. */
    public record Query(Identifier dimension, List<BlockPos> positions) implements CustomPayload {
        public static final Id<Query> ID = new Id<>(id("query"));
        public static final PacketCodec<RegistryByteBuf, Query> CODEC = PacketCodec.tuple(
                Identifier.PACKET_CODEC, Query::dimension,
                BlockPos.PACKET_CODEC.collect(PacketCodecs.toList(StorageServerHandler.MAX_QUERY_POSITIONS)), Query::positions,
                Query::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public record SlotStack(int slot, ItemStack stack) {
        public static final PacketCodec<RegistryByteBuf, SlotStack> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, SlotStack::slot,
                ItemStack.OPTIONAL_PACKET_CODEC, SlotStack::stack,
                SlotStack::new);
    }

    /** One container's contents. {@code available} is false when it isn't loaded, isn't a container, or is locked. */
    public record ContainerContents(BlockPos pos, boolean available, List<SlotStack> stacks) {
        public static final PacketCodec<RegistryByteBuf, ContainerContents> CODEC = PacketCodec.tuple(
                BlockPos.PACKET_CODEC, ContainerContents::pos,
                PacketCodecs.BOOLEAN, ContainerContents::available,
                SlotStack.CODEC.collect(PacketCodecs.toList()), ContainerContents::stacks,
                ContainerContents::new);
    }

    public record Contents(Identifier dimension, List<ContainerContents> containers) implements CustomPayload {
        public static final Id<Contents> ID = new Id<>(id("contents"));
        public static final PacketCodec<RegistryByteBuf, Contents> CODEC = PacketCodec.tuple(
                Identifier.PACKET_CODEC, Contents::dimension,
                ContainerContents.CODEC.collect(PacketCodecs.toList()), Contents::containers,
                Contents::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }
}
