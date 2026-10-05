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

import java.util.List;

/** Pull a material list (item kinds + counts) out of linked containers into the shulker boxes the player carries. */
public final class MaterialPayloads {
    public static final int MAX_KINDS = 1024;
    public static final int MAX_PAYLOAD_BYTES = 1024 * 1024;

    private MaterialPayloads() {
    }

    public record Want(ItemStack kind, int count) {
        public static final PacketCodec<RegistryByteBuf, Want> CODEC = PacketCodec.tuple(
                ItemStack.OPTIONAL_PACKET_CODEC, Want::kind,
                PacketCodecs.VAR_INT, Want::count,
                Want::new);
    }

    public record Request(int requestId, List<Want> wants, List<FillPayloads.Source> sources) implements CustomPayload {
        public static final Id<Request> ID = new Id<>(Identifier.of("containerautofill", "material_pull"));
        public static final PacketCodec<RegistryByteBuf, Request> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, Request::requestId,
                Want.CODEC.collect(PacketCodecs.toList(MAX_KINDS)), Request::wants,
                FillPayloads.Source.CODEC.collect(PacketCodecs.toList(FillPayloads.MAX_SOURCES)), Request::sources,
                Request::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** {@code noBoxes}: no shulker box in the inventory; {@code boxesFull}: some items didn't fit. */
    public record Result(int requestId, int moved, List<FillPayloads.Missing> missing, boolean noBoxes, boolean boxesFull)
            implements CustomPayload {
        public static final Id<Result> ID = new Id<>(Identifier.of("containerautofill", "material_result"));
        public static final PacketCodec<RegistryByteBuf, Result> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, Result::requestId,
                PacketCodecs.VAR_INT, Result::moved,
                FillPayloads.Missing.CODEC.collect(PacketCodecs.toList()), Result::missing,
                PacketCodecs.BOOLEAN, Result::noBoxes,
                PacketCodecs.BOOLEAN, Result::boxesFull,
                Result::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }
}
