/*
 * Cytra Container
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.mixin;

import dev.steelaspect.containerautofill.takeitout.TakeItOutFeatures;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Litematica Printer (optional, CC0): before placing, each guide checks the player has the item. When they
 * don't, fetch it from a shulker box or linked container; the printer uses it once it's in the inventory.
 * Targeted by name, so this mod doesn't need the printer to build; skipped when it isn't installed.
 */
@Mixin(targets = "me.aleksilassila.litematica.printer.guides.Guide", remap = false)
public abstract class PrinterGuideMixin {
    @Shadow
    protected abstract List<ItemStack> getRequiredItems();

    @Inject(method = "playerHasRightItem", at = @At("RETURN"), require = 0)
    private void containerautofill$pullMissing(ClientPlayerEntity player, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) TakeItOutFeatures.onPrinterMissing(this.getRequiredItems());
    }
}
