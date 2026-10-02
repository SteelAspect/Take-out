/*
 * Cytra Container
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill;

import dev.steelaspect.containerautofill.network.StorageServerHandler;
import dev.steelaspect.containerautofill.takeitout.GetStackPayload;
import dev.steelaspect.containerautofill.takeitout.ShulkerStackServerHandler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/**
 * Common entrypoint. Registers the shulker-retrieval channel in both directions so that the client
 * can send requests, and so the integrated server (singleplayer / LAN host) can answer them.
 */
public class ContainerAutoFill implements ModInitializer {
    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playC2S().register(GetStackPayload.ID, GetStackPayload.CODEC);
        // Fabric runs play payload receivers on the server thread, so the request is handled in packet
        // order, before a vanilla pick-block packet sent right after it.
        ServerPlayNetworking.registerGlobalReceiver(GetStackPayload.ID, (payload, context) ->
                ShulkerStackServerHandler.handle(context.player(), payload.slot(), payload.shulker()));
        StorageServerHandler.register();
    }
}
