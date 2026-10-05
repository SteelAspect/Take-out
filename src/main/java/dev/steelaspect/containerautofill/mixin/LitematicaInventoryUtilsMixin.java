/*
 * Cytra Container
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.mixin;

import dev.steelaspect.containerautofill.takeitout.TakeItOutFeatures;
import fi.dy.masa.litematica.util.InventoryUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Litematica funnels both schematic pick block and easy place through
 * {@code InventoryUtils.schematicWorldPickBlock}; when the item isn't carried loose, ask for it from an
 * inventory shulker instead.
 */
@Mixin(value = InventoryUtils.class, remap = false)
public abstract class LitematicaInventoryUtilsMixin {
    @Inject(method = "schematicWorldPickBlock", at = @At("HEAD"), cancellable = true)
    private static void containerautofill$pickFromShulker(ItemStack stack, BlockPos pos, World schematicWorld, MinecraftClient mc, CallbackInfo ci) {
        if (TakeItOutFeatures.onSchematicPickBlock(stack, pos, mc)) {
            ci.cancel();
        }
    }
}
