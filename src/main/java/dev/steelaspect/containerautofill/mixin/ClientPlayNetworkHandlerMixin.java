/*
 * Cytra Container
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.mixin;

import dev.steelaspect.containerautofill.restock.Restock;
import dev.steelaspect.containerautofill.takeitout.TakeItOutFeatures;
import net.minecraft.entity.EntityStatuses;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.SetPlayerInventoryS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notices a retrieved item the moment its inventory packet is applied, instead of on the next client
 * tick. These handlers first hop to the client thread (throwing on the network thread), so RETURN only
 * runs once the change is applied, on the client thread.
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
    @Inject(method = "onScreenHandlerSlotUpdate", at = @At("RETURN"))
    private void containerautofill$onSlotUpdate(ScreenHandlerSlotUpdateS2CPacket packet, CallbackInfo ci) {
        containerautofill$inventoryChanged();
    }

    @Inject(method = "onSetPlayerInventory", at = @At("RETURN"))
    private void containerautofill$onSetPlayerInventory(SetPlayerInventoryS2CPacket packet, CallbackInfo ci) {
        containerautofill$inventoryChanged();
    }

    @Inject(method = "onInventory", at = @At("RETURN"))
    private void containerautofill$onInventory(InventoryS2CPacket packet, CallbackInfo ci) {
        containerautofill$inventoryChanged();
    }

    /** Restock: the local player's totem of undying popped. */
    @Inject(method = "onEntityStatus", at = @At("RETURN"))
    private void containerautofill$onEntityStatus(EntityStatusS2CPacket packet, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (packet.getStatus() == EntityStatuses.USE_TOTEM_OF_UNDYING && client.world != null && client.player != null
                && packet.getEntity(client.world) == client.player) {
            Restock.onTotemPop(client);
        }
    }

    private static void containerautofill$inventoryChanged() {
        MinecraftClient client = MinecraftClient.getInstance();
        TakeItOutFeatures.onInventoryPacket(client);
        Restock.onInventoryPacket(client);
    }
}
