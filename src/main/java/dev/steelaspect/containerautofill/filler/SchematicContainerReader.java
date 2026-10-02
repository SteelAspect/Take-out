/*
 * Container Auto Fill
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 *
 * Ported from Litematica-Container-Filler (core/LitematicaContainerReader.java,
 * core/LitematicaPlacementContainerData.java and RealContainerCache#parseNbtInventory),
 * https://github.com/MimicEnzymes/Litematica-Container-Filler, licensed LGPL-3.0-only.
 * Modified by steelaspect, 2026-10-02: single-position lookup instead of a global snapshot, crafter locks,
 * block entity type compatibility (shulker colours, copper chest oxidation),
 * Litematica 0.26.16 CompoundData block entity maps, double chest halves taken from the real
 * world, block identity checks, and removal of material replacement / large barrel support.
 */
package dev.steelaspect.containerautofill.filler;

import dev.steelaspect.containerautofill.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.SchematicUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import fi.dy.masa.malilib.util.data.tag.converter.DataConverterNbt;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Reads what Litematica's active placements expect inside a container.
 */
public final class SchematicContainerReader {
    public static final int CHEST_HALF_SIZE = 27;

    public enum Status {
        OK,
        /** No enabled placement covers this position (or it has air there). */
        NOT_IN_PLACEMENT,
        /** The schematic has a different block at this position than the world. */
        BLOCK_MISMATCH,
        /** The schematic block at this position is not a block entity container. */
        NOT_A_CONTAINER
    }

    /**
     * @param items         expected stacks keyed by container index
     * @param disabledSlots crafter slots the schematic has locked (empty for other containers)
     * @param expectedBlock the schematic block (for messages)
     */
    public record Result(Status status, Map<Integer, ItemStack> items, Set<Integer> disabledSlots, Block expectedBlock) {
        static Result of(Status status) {
            return new Result(status, Collections.emptyMap(), Collections.emptySet(), null);
        }
    }

    /** What the schematic holds at one block position. */
    public record Single(BlockState state, Map<Integer, ItemStack> items, Set<Integer> disabledSlots) {
    }

    private SchematicContainerReader() {
    }

    /**
     * @param world      the real client world
     * @param pos        any block of the container (either half of a double chest)
     * @param registries registry lookup for item decoding
     * @return expected stacks keyed by container index (0..53 for double chests, right half first like vanilla)
     */
    public static Result read(World world, BlockPos pos, RegistryWrapper.WrapperLookup registries) {
        BlockPos[] halves = getRealContainerHalves(world, pos);
        Map<Integer, ItemStack> combined = new HashMap<>();

        Set<Integer> disabled = new HashSet<>();

        for (int half = 0; half < halves.length; half++) {
            BlockPos halfPos = halves[half];
            Single single = readSingle(halfPos, registries);
            if (single == null) return Result.of(Status.NOT_IN_PLACEMENT);
            if (!single.state().hasBlockEntity()) return Result.of(Status.NOT_A_CONTAINER);
            if (!isCompatible(world, halfPos, single.state())) {
                return new Result(Status.BLOCK_MISMATCH, Collections.emptyMap(), Collections.emptySet(), single.state().getBlock());
            }

            int offset = half * CHEST_HALF_SIZE;
            single.items().forEach((slot, stack) -> {
                if (halves.length > 1 && slot >= CHEST_HALF_SIZE) return;
                combined.put(slot + offset, stack);
            });
            disabled.addAll(single.disabledSlots());
        }

        return new Result(Status.OK, combined, disabled, world.getBlockState(pos).getBlock());
    }

    /**
     * The real block can hold the schematic's contents if its block entity type supports the schematic
     * block. So a red vs white shulker box, or copper chests of different oxidation, still count as the same
     * container; a barrel where the schematic has a chest does not.
     */
    public static boolean isCompatible(World world, BlockPos pos, BlockState schematicState) {
        BlockState realState = world.getBlockState(pos);
        if (realState.getBlock() == schematicState.getBlock()) return true;
        BlockEntity realBlockEntity = world.getBlockEntity(pos);
        return realBlockEntity != null && realBlockEntity.getType().supports(schematicState);
    }

    /**
     * Double chests are taken from the real world so the slot order always matches the open screen:
     * the RIGHT-type half is the first 27 slots, as in vanilla's DoubleInventory.
     */
    public static BlockPos[] getRealContainerHalves(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.contains(ChestBlock.CHEST_TYPE)) {
            ChestType type = state.get(ChestBlock.CHEST_TYPE);
            if (type != ChestType.SINGLE) {
                BlockPos other = pos.offset(ChestBlock.getFacing(state));
                BlockState otherState = world.getBlockState(other);
                // Any ChestBlock (normal, trapped, copper of any oxidation level) can be the other half.
                if (otherState.getBlock() instanceof ChestBlock && otherState.contains(ChestBlock.CHEST_TYPE)
                        && otherState.get(ChestBlock.CHEST_TYPE) == type.getOpposite()
                        && otherState.get(ChestBlock.FACING) == state.get(ChestBlock.FACING)) {
                    BlockPos right = type == ChestType.RIGHT ? pos : other;
                    BlockPos left = type == ChestType.LEFT ? pos : other;
                    return new BlockPos[]{right.toImmutable(), left.toImmutable()};
                }
            }
        }
        return new BlockPos[]{pos.toImmutable()};
    }

    /** True if an enabled placement has a container block entity at this position. */
    public static boolean isSchematicContainer(BlockPos pos, RegistryWrapper.WrapperLookup registries) {
        Single single = readSingle(pos, registries);
        return single != null && single.state().hasBlockEntity();
    }

    /** Expected contents of the schematic block at one position, or null if no enabled placement has a block there. */
    public static Single readSingle(BlockPos worldPos, RegistryWrapper.WrapperLookup registries) {
        Single fromPlacement = readFromPlacements(worldPos, registries);
        if (fromPlacement != null) return fromPlacement;
        return readFromSchematicWorld(worldPos, registries);
    }

    private static Single readFromPlacements(BlockPos worldPos, RegistryWrapper.WrapperLookup registries) {
        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        if (manager == null) return null;

        for (SchematicPlacement placement : manager.getAllSchematicsPlacements()) {
            if (placement == null || !placement.isEnabled()) continue;
            LitematicaSchematic schematic = placement.getSchematic();
            if (schematic == null) continue;

            for (Map.Entry<String, Box> entry : placement.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED).entrySet()) {
                if (!contains(entry.getValue(), worldPos)) continue;

                String regionName = entry.getKey();
                SubRegionPlacement regionPlacement = placement.getRelativeSubRegionPlacement(regionName);
                LitematicaBlockStateContainer container = schematic.getSubRegionContainer(regionName);
                if (regionPlacement == null || container == null) continue;

                BlockPos localPos;
                try {
                    localPos = SchematicUtils.getSchematicContainerPositionFromWorldPosition(
                            worldPos, schematic, regionName, placement, regionPlacement, container);
                } catch (Exception e) {
                    Configs.debug("Position transform failed for {} in region {}: {}", worldPos, regionName, e.toString());
                    continue;
                }
                if (localPos == null) continue;

                BlockState state = container.get(localPos.getX(), localPos.getY(), localPos.getZ());
                if (state == null || state.isAir()) continue;

                Map<BlockPos, CompoundData> blockEntities = schematic.getBlockEntityMapForRegion(regionName);
                CompoundData data = blockEntities != null ? blockEntities.get(localPos) : null;
                Single single = fromNbt(state, data != null ? DataConverterNbt.toVanillaCompound(data) : null, registries);
                Configs.debug("Schematic '{}' region '{}' local {} -> {} with {} stacks",
                        placement.getName(), regionName, localPos, state, single.items().size());
                return single;
            }
        }
        return null;
    }

    private static Single readFromSchematicWorld(BlockPos worldPos, RegistryWrapper.WrapperLookup registries) {
        WorldSchematic schematicWorld = SchematicWorldHandler.getSchematicWorld();
        if (schematicWorld == null) return null;

        BlockState state = schematicWorld.getBlockState(worldPos);
        if (state.isAir()) return null;

        BlockEntity blockEntity = schematicWorld.getBlockEntity(worldPos);
        return fromNbt(state, blockEntity != null ? blockEntity.createNbt(registries) : null, registries);
    }

    public static Single fromNbt(BlockState state, NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        if (nbt == null) return new Single(state, Collections.emptyMap(), Collections.emptySet());
        return new Single(state, parseItems(nbt, registries), parseDisabledSlots(nbt));
    }

    /** Crafter {@code disabled_slots} (int array). */
    public static Set<Integer> parseDisabledSlots(NbtCompound nbt) {
        Set<Integer> disabled = new HashSet<>();
        for (int slot : nbt.getIntArray("disabled_slots").orElse(new int[0])) {
            disabled.add(slot);
        }
        return disabled;
    }

    private static boolean contains(Box box, BlockPos pos) {
        BlockPos p1 = box.getPos1();
        BlockPos p2 = box.getPos2();
        if (p1 == null || p2 == null) return false;
        return pos.getX() >= Math.min(p1.getX(), p2.getX()) && pos.getX() <= Math.max(p1.getX(), p2.getX())
                && pos.getY() >= Math.min(p1.getY(), p2.getY()) && pos.getY() <= Math.max(p1.getY(), p2.getY())
                && pos.getZ() >= Math.min(p1.getZ(), p2.getZ()) && pos.getZ() <= Math.max(p1.getZ(), p2.getZ());
    }

    /** Decodes a block entity's {@code Items} list, keeping full data components. */
    public static Map<Integer, ItemStack> parseItems(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        if (nbt == null) return Collections.emptyMap();

        Map<Integer, ItemStack> items = new HashMap<>();
        NbtList list = nbt.getListOrEmpty("Items");
        for (NbtElement element : list) {
            if (!(element instanceof NbtCompound itemTag)) continue;
            int slot = itemTag.getByte("Slot", (byte) 0) & 0xFF;
            ItemStack stack = ItemStack.OPTIONAL_CODEC
                    .parse(registries.getOps(NbtOps.INSTANCE), itemTag)
                    .resultOrPartial(error -> Configs.debug("Could not decode schematic item in slot {}: {}", slot, error))
                    .orElse(ItemStack.EMPTY);
            if (!stack.isEmpty()) {
                items.put(slot, stack);
            }
        }
        return items;
    }
}
