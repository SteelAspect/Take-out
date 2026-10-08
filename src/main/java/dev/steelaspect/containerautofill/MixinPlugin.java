/*
 * Cytra Container
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Skips the Litematica mixin when Litematica isn't installed, so a client without it reaches
 * {@link ClientRequirements} and its clear message instead of failing on a missing mixin target.
 */
public class MixinPlugin implements IMixinConfigPlugin {
    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (targetClassName.startsWith("fi.dy.masa.litematica.")) return FabricLoader.getInstance().isModLoaded("litematica");
        if (targetClassName.startsWith("me.aleksilassila.litematica.printer.")) return FabricLoader.getInstance().isModLoaded("litematica_printer");
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    /**
     * Mixins into other client mods are only added when that mod is installed: a mixin whose target class is
     * missing fails while the config is prepared (before {@link #shouldApplyMixin}) and stops the game starting.
     */
    @Override
    public List<String> getMixins() {
        FabricLoader loader = FabricLoader.getInstance();
        if (loader.getEnvironmentType() != EnvType.CLIENT) return null;
        List<String> mixins = new ArrayList<>();
        if (loader.isModLoaded("litematica")) {
            mixins.add("LitematicaEasyPlaceMessageMixin");
            mixins.add("LitematicaInventoryUtilsMixin");
            mixins.add("LitematicaGuiMaterialListMixin");
            mixins.add("MaterialListPlacementAccessor");
            mixins.add("GuiBaseAccessor");
        }
        if (loader.isModLoaded("litematica_printer")) mixins.add("PrinterGuideMixin");
        return mixins;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
