/*
 * Cytra Container
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

/** Server-side instant fill: one request per container block, answered with a {@link Result}. */
public final class FillPayloads {
    public static final int MAX_EXPECTED = 256;
    public static final int MAX_SOURCES = 512;

    private FillPayloads() {
    }

    public record Source(Identifier dimension, BlockPos pos) {
        public static final PacketCodec<RegistryByteBuf, Source> CODEC = PacketCodec.tuple(
                Identifier.PACKET_CODEC, Source::dimension,
                BlockPos.PACKET_CODEC, Source::pos,
                Source::new);
    }

    /**
     * Fill the container at {@code pos} so each slot in {@code expected} holds that stack. Items come from the
     * player's inventory, then (optionally) shulker boxes in it, then the listed linked containers, then (with
     * {@code useShulkers}) shulker boxes stored in those linked containers.
     */
    public record Fill(int requestId, Identifier dimension, BlockPos pos, List<StoragePayloads.SlotStack> expected,
                       List<Integer> disabledSlots, boolean applyLocks, boolean clearWrong, boolean useShulkers,
                       boolean creativeFill, List<Source> sources) implements CustomPayload {
        public static final Id<Fill> ID = new Id<>(Identifier.of("containerautofill", "fill"));
        public static final PacketCodec<RegistryByteBuf, Fill> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, Fill::requestId,
                Identifier.PACKET_CODEC, Fill::dimension,
                BlockPos.PACKET_CODEC, Fill::pos,
                StoragePayloads.SlotStack.CODEC.collect(PacketCodecs.toList(MAX_EXPECTED)), Fill::expected,
                PacketCodecs.VAR_INT.collect(PacketCodecs.toList(9)), Fill::disabledSlots,
                PacketCodecs.BOOLEAN, Fill::applyLocks,
                PacketCodecs.BOOLEAN, Fill::clearWrong,
                PacketCodecs.BOOLEAN, Fill::useShulkers,
                PacketCodecs.BOOLEAN, Fill::creativeFill,
                Source.CODEC.collect(PacketCodecs.toList(MAX_SOURCES)), Fill::sources,
                Fill::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public record Missing(ItemStack kind, int count) {
        public static final PacketCodec<RegistryByteBuf, Missing> CODEC = PacketCodec.tuple(
                ItemStack.OPTIONAL_PACKET_CODEC, Missing::kind,
                PacketCodecs.VAR_INT, Missing::count,
                Missing::new);
    }

    /** {@code available=false}: the container wasn't loaded, isn't a container, or is locked. */
    public record Result(int requestId, BlockPos pos, boolean available, int filledSlots, int wrongSlots, List<Missing> missing)
            implements CustomPayload {
        public static final Id<Result> ID = new Id<>(Identifier.of("containerautofill", "fill_result"));
        public static final PacketCodec<RegistryByteBuf, Result> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, Result::requestId,
                BlockPos.PACKET_CODEC, Result::pos,
                PacketCodecs.BOOLEAN, Result::available,
                PacketCodecs.VAR_INT, Result::filledSlots,
                PacketCodecs.VAR_INT, Result::wrongSlots,
                Missing.CODEC.collect(PacketCodecs.toList()), Result::missing,
                Result::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }
}
