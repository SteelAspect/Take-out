/*
 * Cytra Container
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 *
 * Ported from Litematica-Container-Filler (core/LitematicaPlacementContainerData.java),
 * https://github.com/MimicEnzymes/Litematica-Container-Filler, licensed LGPL-3.0-only.
 * Modified by steelaspect, 2026-10-02: Litematica 0.26.16 CompoundData maps, expected contents decoded once
 * per rebuild, placement-change detection by signature instead of mixins, material replacement removed.
 */
package dev.steelaspect.containerautofill.highlight;

import dev.steelaspect.containerautofill.config.Configs;
import dev.steelaspect.containerautofill.filler.SchematicContainerReader;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.litematica.util.SchematicUtils;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import fi.dy.masa.malilib.util.data.tag.converter.DataConverterNbt;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.inventory.Inventory;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Every container block entity in the enabled Litematica placements, keyed by world position, with its
 * expected contents already decoded. Rebuilt when the placements change (moved, rotated, mirrored,
 * enabled/disabled, added or removed).
 */
public final class SchematicContainerIndex {
    private static Map<BlockPos, SchematicContainerReader.Single> entries = Collections.emptyMap();
    private static long signature = Long.MIN_VALUE;

    private SchematicContainerIndex() {
    }

    public static Map<BlockPos, SchematicContainerReader.Single> entries() {
        return entries;
    }

    public static void clear() {
        entries = Collections.emptyMap();
        signature = Long.MIN_VALUE;
    }

    /** Rebuilds the index if the placements changed. Returns true if it was rebuilt. */
    public static boolean refresh(RegistryWrapper.WrapperLookup registries) {
        long current = computeSignature();
        if (current == signature) return false;
        signature = current;
        entries = build(registries);
        Configs.debug("Schematic container index rebuilt: {} containers", entries.size());
        return true;
    }

    private static long computeSignature() {
        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        if (manager == null) return 0L;
        long hash = 17L;
        for (SchematicPlacement placement : manager.getAllSchematicsPlacements()) {
            if (placement == null) continue;
            hash = hash * 31 + System.identityHashCode(placement);
            hash = hash * 31 + (placement.isEnabled() ? 1 : 0);
            hash = hash * 31 + placement.getOrigin().hashCode();
            hash = hash * 31 + placement.getRotation().ordinal();
            hash = hash * 31 + placement.getMirror().ordinal();
            hash = hash * 31 + System.identityHashCode(placement.getSchematic());
            for (Map.Entry<String, Box> box : placement.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED).entrySet()) {
                hash = hash * 31 + box.getKey().hashCode();
                hash = hash * 31 + String.valueOf(box.getValue().getPos1()).hashCode();
                hash = hash * 31 + String.valueOf(box.getValue().getPos2()).hashCode();
            }
        }
        return hash;
    }

    private static Map<BlockPos, SchematicContainerReader.Single> build(RegistryWrapper.WrapperLookup registries) {
        Map<BlockPos, SchematicContainerReader.Single> result = new HashMap<>();
        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        if (manager == null) return result;

        for (SchematicPlacement placement : manager.getAllSchematicsPlacements()) {
            if (placement == null || !placement.isEnabled()) continue;
            collect(placement, registries, result);
        }
        return Collections.unmodifiableMap(result);
    }

    /** The containers of one placement (enabled or not), by world position. Used by the container material list. */
    public static Map<BlockPos, SchematicContainerReader.Single> forPlacement(SchematicPlacement placement,
                                                                             RegistryWrapper.WrapperLookup registries) {
        Map<BlockPos, SchematicContainerReader.Single> result = new HashMap<>();
        if (placement != null) collect(placement, registries, result);
        return result;
    }

    private static void collect(SchematicPlacement placement, RegistryWrapper.WrapperLookup registries,
                                Map<BlockPos, SchematicContainerReader.Single> result) {
            LitematicaSchematic schematic = placement.getSchematic();
            if (schematic == null) return;

            for (String regionName : placement.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED).keySet()) {
                SubRegionPlacement regionPlacement = placement.getRelativeSubRegionPlacement(regionName);
                LitematicaBlockStateContainer container = schematic.getSubRegionContainer(regionName);
                Map<BlockPos, CompoundData> blockEntities = schematic.getBlockEntityMapForRegion(regionName);
                if (regionPlacement == null || container == null || blockEntities == null) continue;

                for (Map.Entry<BlockPos, CompoundData> entry : blockEntities.entrySet()) {
                    BlockPos localPos = entry.getKey();
                    if (localPos == null || entry.getValue() == null) continue;

                    BlockState state = container.get(localPos.getX(), localPos.getY(), localPos.getZ());
                    if (state == null || state.isAir() || !isInventoryBlock(state)) continue;

                    BlockPos worldPos = toWorldPos(localPos, schematic, regionName, placement, regionPlacement);
                    if (worldPos == null || !mapsBack(worldPos, localPos, schematic, regionName, placement, regionPlacement, container)) continue;

                    var nbt = DataConverterNbt.toVanillaCompound(entry.getValue());
                    result.putIfAbsent(worldPos.toImmutable(), SchematicContainerReader.fromNbt(state, nbt, registries));
                }
            }
    }

    private static final Map<BlockState, Boolean> INVENTORY_BLOCKS = new java.util.concurrent.ConcurrentHashMap<>();

    /** True for blocks whose block entity is an inventory (chests, furnaces, modded storage, ...). */
    public static boolean isInventoryBlock(BlockState state) {
        if (!state.hasBlockEntity() || !(state.getBlock() instanceof BlockEntityProvider provider)) return false;
        return INVENTORY_BLOCKS.computeIfAbsent(state, s -> {
            try {
                return provider.createBlockEntity(BlockPos.ORIGIN, s) instanceof Inventory;
            } catch (Exception e) {
                return false;
            }
        });
    }

    private static BlockPos toWorldPos(BlockPos localPos, LitematicaSchematic schematic, String regionName,
                                       SchematicPlacement placement, SubRegionPlacement regionPlacement) {
        try {
            BlockPos regionPos = regionPlacement.getPos();
            BlockPos regionSize = schematic.getAreaSize(regionName);
            if (regionSize == null) return null;

            BlockPos regionEnd = PositionUtils.getRelativeEndPositionFromAreaSize(regionSize).add(regionPos);
            BlockPos regionMin = PositionUtils.getMinCorner(regionPos, regionEnd);
            BlockPos posWithinSubRegion = new BlockPos(
                    regionMin.getX() + localPos.getX() - regionPos.getX(),
                    regionMin.getY() + localPos.getY() - regionPos.getY(),
                    regionMin.getZ() + localPos.getZ() - regionPos.getZ());
            BlockPos regionPosTransformed = PositionUtils.getTransformedBlockPos(regionPos, placement.getMirror(), placement.getRotation());
            BlockPos transformedLocal = PositionUtils.getTransformedPlacementPosition(posWithinSubRegion, placement, regionPlacement);
            return placement.getOrigin().add(regionPosTransformed).add(transformedLocal);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean mapsBack(BlockPos worldPos, BlockPos localPos, LitematicaSchematic schematic, String regionName,
                                    SchematicPlacement placement, SubRegionPlacement regionPlacement, LitematicaBlockStateContainer container) {
        try {
            return localPos.equals(SchematicUtils.getSchematicContainerPositionFromWorldPosition(
                    worldPos, schematic, regionName, placement, regionPlacement, container));
        } catch (Exception e) {
            return false;
        }
    }
}
