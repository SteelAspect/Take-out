/*
 * Cytra Container
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.mixin;

import dev.steelaspect.containerautofill.materials.ContainerMaterialList;
import dev.steelaspect.containerautofill.materials.MaterialListModes;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.StringUtils;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds the "Contents: Blocks / Containers / Both" button to placement material lists, after the bottom row. */
@Mixin(value = GuiMaterialList.class, remap = false)
public abstract class LitematicaGuiMaterialListMixin {
    @Shadow
    @Final
    private MaterialListBase materialList;

    @Inject(method = "initGui", at = @At("TAIL"))
    private void containerautofill$addContentsButton(CallbackInfo ci) {
        if (MaterialListModes.placementOf(materialList) == null) return;
        GuiBase self = (GuiBase) (Object) this;
        int y = self.getScreenHeight() - 22;
        int x = 12;
        for (ButtonBase button : ((GuiBaseAccessor) self).containerautofill$getButtons()) {
            if (button.getY() == y) x = Math.max(x, button.getX() + button.getWidth() + 2);
        }
        String label = StringUtils.translate("containerautofill.gui.button.material_contents", MaterialListModes.label(materialList));
        ButtonGeneric button = new ButtonGeneric(x, y, -1, 20, label);
        button.setHoverStrings("containerautofill.gui.button.hover.material_contents");
        self.addButton(button, (b, mouseButton) -> {
            MaterialListBase next = MaterialListModes.next(materialList, mouseButton == 1);
            GuiMaterialList gui = new GuiMaterialList(next);
            gui.setParent(self.getParent());
            GuiBase.openGui(gui);
        });
    }
}
