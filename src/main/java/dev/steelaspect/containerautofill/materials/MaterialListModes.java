/*
 * Cytra Container
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.materials;

import dev.steelaspect.containerautofill.mixin.MaterialListPlacementAccessor;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListHudRenderer;
import fi.dy.masa.litematica.materials.MaterialListPlacement;
import fi.dy.masa.litematica.render.infohud.InfoHud;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import org.jetbrains.annotations.Nullable;

/** Switches a placement's material list between Litematica's blocks, container contents, and both. */
public final class MaterialListModes {
    private MaterialListModes() {
    }

    /** The placement behind a placement material list, or null for other lists (schematic files, area analyzer). */
    public static @Nullable SchematicPlacement placementOf(MaterialListBase list) {
        if (list instanceof ContainerMaterialList containers) return containers.getPlacement();
        if (list instanceof MaterialListPlacement) return ((MaterialListPlacementAccessor) list).containerautofill$getPlacement();
        return null;
    }

    public static String label(MaterialListBase list) {
        if (list instanceof ContainerMaterialList containers) {
            return containers.getMode() == ContainerMaterialList.Mode.CONTAINERS ? "Containers" : "Both";
        }
        return "Blocks";
    }

    /**
     * Builds the next list (Blocks, Containers, Both; backwards with a right click) for the same placement, makes it
     * the active material list and keeps the info HUD on if it was on.
     */
    public static MaterialListBase next(MaterialListBase current, boolean backwards) {
        SchematicPlacement placement = placementOf(current);
        int index = current instanceof ContainerMaterialList c ? (c.getMode() == ContainerMaterialList.Mode.CONTAINERS ? 1 : 2) : 0;
        index = (index + (backwards ? 2 : 1)) % 3;
        MaterialListBase next = switch (index) {
            case 1 -> new ContainerMaterialList(placement, ContainerMaterialList.Mode.CONTAINERS);
            case 2 -> new ContainerMaterialList(placement, ContainerMaterialList.Mode.BOTH);
            default -> new MaterialListPlacement(placement, true);
        };
        next.setMultiplier(current.getMultiplier());
        next.setHideAvailable(current.getHideAvailable());
        boolean hud = current.getHudRenderer().getShouldRenderCustom();
        DataManager.setMaterialList(next);
        if (hud) {
            MaterialListHudRenderer renderer = next.getHudRenderer();
            if (!renderer.getShouldRenderCustom()) renderer.toggleShouldRender();
            InfoHud.getInstance().addInfoHudRenderer(renderer, true);
        }
        return next;
    }
}
