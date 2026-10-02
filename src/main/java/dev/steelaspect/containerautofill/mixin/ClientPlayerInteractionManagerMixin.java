/*
 * Container Auto Fill
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.mixin;

import dev.steelaspect.containerautofill.filler.ContainerTracker;
import dev.steelaspect.containerautofill.restock.Restock;
import dev.steelaspect.containerautofill.takeitout.HotbarRefill;
import dev.steelaspect.containerautofill.takeitout.TakeItOutFeatures;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerInteractionManager.class)
public abstract class ClientPlayerInteractionManagerMixin {
    /** Remembers which block was used so the screen that opens next can be tied to its position. */
    @Inject(method = "interactBlock", at = @At("HEAD"))
    private void containerautofill$rememberTarget(ClientPlayerEntity player, Hand hand, BlockHitResult hitResult,
                                                   CallbackInfoReturnable<ActionResult> cir) {
        ContainerTracker.onInteractBlock(hitResult.getBlockPos());
        HotbarRefill.beforeUse(MinecraftClient.getInstance(), hand);
        Restock.beforeUse(MinecraftClient.getInstance(), hand);
    }

    /** Hotbar refill: placing a block used up the last one in the hand. */
    @Inject(method = "interactBlock", at = @At("RETURN"))
    private void containerautofill$afterInteractBlock(ClientPlayerEntity player, Hand hand, BlockHitResult hitResult,
                                                      CallbackInfoReturnable<ActionResult> cir) {
        HotbarRefill.afterUse(MinecraftClient.getInstance(), hand);
        Restock.afterUse(MinecraftClient.getInstance(), hand);
    }

    @Inject(method = "interactItem", at = @At("HEAD"))
    private void containerautofill$beforeInteractItem(PlayerEntity player, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        HotbarRefill.beforeUse(MinecraftClient.getInstance(), hand);
        Restock.beforeUse(MinecraftClient.getInstance(), hand);
    }

    /** Hotbar refill: using an item (throwing a pearl, a snowball...) used up the last one. */
    @Inject(method = "interactItem", at = @At("RETURN"))
    private void containerautofill$afterInteractItem(PlayerEntity player, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        HotbarRefill.afterUse(MinecraftClient.getInstance(), hand);
        Restock.afterUse(MinecraftClient.getInstance(), hand);
    }

    /** Vanilla pick block: request the block from an inventory shulker first, like TakeItOut. */
    @Inject(method = "pickItemFromBlock", at = @At("HEAD"))
    private void containerautofill$pickFromShulker(BlockPos pos, boolean includeData, CallbackInfo ci) {
        TakeItOutFeatures.onVanillaPickBlock(MinecraftClient.getInstance(), pos);
    }
}
