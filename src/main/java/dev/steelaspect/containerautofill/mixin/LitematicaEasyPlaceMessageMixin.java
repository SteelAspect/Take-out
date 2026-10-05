/*
 * Cytra Container
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.steelaspect.containerautofill.takeitout.TakeItOutFeatures;
import fi.dy.masa.litematica.util.EasyPlaceUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.malilib.gui.Message;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Easy place fails with "Action prevented by the Easy Place mode" when the block isn't in the hand right after
 * picking it, which is always the case while this mod fetches it from a shulker box or linked container. Skip
 * that message then (the block is placed when it arrives), or let this mod say why it couldn't be fetched.
 */
@Mixin(value = {WorldUtils.class, EasyPlaceUtils.class}, remap = false)
public abstract class LitematicaEasyPlaceMessageMixin {
    @WrapWithCondition(method = {"handleEasyPlace", "handleEasyPlaceWithMessage"}, require = 0,
            at = @At(value = "INVOKE", target = "Lfi/dy/masa/malilib/util/InfoUtils;printActionbarMessage(Ljava/lang/String;[Ljava/lang/Object;)V"))
    private static boolean containerautofill$actionbar(String key, Object[] args) {
        return TakeItOutFeatures.allowEasyPlaceFailMessage();
    }

    @WrapWithCondition(method = {"handleEasyPlace", "handleEasyPlaceWithMessage"}, require = 0,
            at = @At(value = "INVOKE", target = "Lfi/dy/masa/malilib/util/InfoUtils;showGuiOrInGameMessage(Lfi/dy/masa/malilib/gui/Message$MessageType;Ljava/lang/String;[Ljava/lang/Object;)V"))
    private static boolean containerautofill$guiMessage(Message.MessageType type, String key, Object[] args) {
        return TakeItOutFeatures.allowEasyPlaceFailMessage();
    }

    @WrapWithCondition(method = {"handleEasyPlace", "handleEasyPlaceWithMessage"}, require = 0,
            at = @At(value = "INVOKE", target = "Lfi/dy/masa/malilib/util/InfoUtils;showInGameMessage(Lfi/dy/masa/malilib/gui/Message$MessageType;Ljava/lang/String;[Ljava/lang/Object;)V"))
    private static boolean containerautofill$inGameMessage(Message.MessageType type, String key, Object[] args) {
        return TakeItOutFeatures.allowEasyPlaceFailMessage();
    }
}
