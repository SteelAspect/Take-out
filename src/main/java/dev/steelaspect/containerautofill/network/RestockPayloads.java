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

/**
 * Restock protocol: the client names a hotbar/offhand slot that is running low (or was just used up) and
 * the server tops it up to a full stack from shulker boxes whose name contains the restock word.
 */
public final class RestockPayloads {
    public static final int MAX_NAME_LENGTH = 64;

    private RestockPayloads() {
    }

    /** {@code slot}: 0-8 hotbar or 40 offhand. {@code item}: the item to restock (count ignored). */
    public record Request(int slot, ItemStack item, String name, boolean fromInventory, boolean fromEnderChest) implements CustomPayload {
        public static final Id<Request> ID = new Id<>(Identifier.of("containerautofill", "restock"));
        public static final PacketCodec<RegistryByteBuf, Request> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, Request::slot,
                ItemStack.OPTIONAL_PACKET_CODEC, Request::item,
                PacketCodecs.string(MAX_NAME_LENGTH), Request::name,
                PacketCodecs.BOOLEAN, Request::fromInventory,
                PacketCodecs.BOOLEAN, Request::fromEnderChest,
                Request::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Answer, sent after the inventory sync: how many items were added to the slot (0 = nothing found). */
    public record Result(int slot, int moved) implements CustomPayload {
        public static final Id<Result> ID = new Id<>(Identifier.of("containerautofill", "restock_result"));
        public static final PacketCodec<RegistryByteBuf, Result> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, Result::slot,
                PacketCodecs.VAR_INT, Result::moved,
                Result::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }
}
