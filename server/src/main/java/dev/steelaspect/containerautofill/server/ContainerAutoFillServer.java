/*
 * Container Auto Fill
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.server;

import dev.steelaspect.containerautofill.takeitout.GetStackPayload;
import dev.steelaspect.containerautofill.takeitout.ShulkerStackServerHandler;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Dedicated server side of Container Auto Fill's shulker retrieval. */
public class ContainerAutoFillServer implements DedicatedServerModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Container Auto Fill (server)");

    @Override
    public void onInitializeServer() {
        PayloadTypeRegistry.playC2S().register(GetStackPayload.ID, GetStackPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(GetStackPayload.ID, (payload, context) ->
                ShulkerStackServerHandler.handle(context.player(), payload.slot(), payload.shulker()));
        LOGGER.info("Shulker retrieval channel {} ready", GetStackPayload.ID.id());
    }
}
