/*
 * Cytra Container
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.takeitout;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Request to move one stack out of a shulker box in the player's own inventory.
 * <p>
 * The channel id and wire format ({@code takeitout:getstack}, two big-endian ints: slot inside the
 * shulker, then the shulker's player-inventory slot) match the TakeItOut protocol, so servers that run
 * TakeItOut or its Paper companion plugin answer these requests too.
 */
public record GetStackPayload(int slot, int shulker) implements CustomPayload {
    public static final CustomPayload.Id<GetStackPayload> ID = new CustomPayload.Id<>(Identifier.of("takeitout", "getstack"));
    public static final PacketCodec<RegistryByteBuf, GetStackPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.INTEGER, GetStackPayload::slot,
            PacketCodecs.INTEGER, GetStackPayload::shulker,
            GetStackPayload::new
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
