/*
 * Cytra Container
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.materials;

import dev.steelaspect.containerautofill.filler.SchematicContainerReader;
import dev.steelaspect.containerautofill.highlight.RealContainerCache;
import dev.steelaspect.containerautofill.highlight.SchematicContainerIndex;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.util.ItemType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Litematica material list of what goes inside the placement's containers, or of its blocks plus that.
 * <ul>
 *   <li>Total: every item the schematic's containers hold. Shulker boxes count as a box plus what is inside them.</li>
 *   <li>Missing: per container, what isn't in the real container yet. Real contents are known once the container was
 *       opened, in singleplayer, or through Servux; a container that was never seen counts as empty.</li>
 *   <li>Available: what you carry, filled in by Litematica like for any list.</li>
 * </ul>
 * In {@link Mode#BOTH} Litematica counts the blocks as usual and the container entries are added on top.
 */
@SuppressWarnings("deprecation")
public class ContainerMaterialList extends MaterialListPlacement {
    public enum Mode {
        CONTAINERS("containers"),
        BOTH("blocks + containers");

        public final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    private final SchematicPlacement placement;
    private final Mode mode;

    public ContainerMaterialList(SchematicPlacement placement, Mode mode) {
        super(placement, false);
        this.placement = placement;
        this.mode = mode;
        this.reCreateMaterialList();
    }

    public SchematicPlacement getPlacement() {
        return placement;
    }

    public Mode getMode() {
        return mode;
    }

    @Override
    public String getName() {
        return super.getName() + " (" + mode.label + ")";
    }

    @Override
    public void reCreateMaterialList() {
        if (mode == Mode.BOTH) {
            // Litematica's block count; its result comes back through setMaterialListEntries.
            super.reCreateMaterialList();
        } else {
            super.setMaterialListEntries(containerEntries());
        }
    }

    @Override
    public void setMaterialListEntries(List<MaterialListEntry> list) {
        super.setMaterialListEntries(mode == Mode.BOTH ? merge(list, containerEntries()) : list);
    }

    private List<MaterialListEntry> containerEntries() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || placement == null) return List.of();
        return entries(SchematicContainerIndex.forPlacement(placement, client.world.getRegistryManager()), RealContainerCache::get);
    }

    /** Totals and missing counts for these containers; real contents come from lookup (null = never seen). */
    public static List<MaterialListEntry> entries(Map<BlockPos, SchematicContainerReader.Single> containers,
                                                  java.util.function.Function<BlockPos, Map<Integer, ItemStack>> lookup) {
        Map<ItemType, long[]> counts = new LinkedHashMap<>();
        Map<ItemType, ItemStack> stacks = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, SchematicContainerReader.Single> container : containers.entrySet()) {
            Map<ItemType, Long> expected = expand(container.getValue().items().values(), stacks);
            Map<Integer, ItemStack> real = lookup.apply(container.getKey());
            Map<ItemType, Long> present = real == null ? Map.of() : expand(real.values(), null);
            for (Map.Entry<ItemType, Long> item : expected.entrySet()) {
                long[] c = counts.computeIfAbsent(item.getKey(), k -> new long[2]);
                c[0] += item.getValue();
                c[1] += Math.max(0, item.getValue() - present.getOrDefault(item.getKey(), 0L));
            }
        }
        List<MaterialListEntry> list = new ArrayList<>();
        counts.forEach((type, c) -> list.add(new MaterialListEntry(stacks.get(type), clamp(c[0]), clamp(c[1]), 0, 0)));
        return list;
    }

    /** Item counts with shulker boxes opened up: the box itself (without its contents) plus everything inside it. */
    static Map<ItemType, Long> expand(Collection<ItemStack> items, Map<ItemType, ItemStack> stacks) {
        Map<ItemType, Long> counts = new LinkedHashMap<>();
        for (ItemStack stack : items) {
            if (stack == null || stack.isEmpty()) continue;
            ItemStack plain = stack.copyWithCount(1);
            ContainerComponent contents = plain.remove(DataComponentTypes.CONTAINER);
            add(counts, stacks, plain, stack.getCount());
            if (contents != null) {
                for (ItemStack inner : contents.iterateNonEmpty()) {
                    add(counts, stacks, inner.copyWithCount(1), (long) inner.getCount() * stack.getCount());
                }
            }
        }
        return counts;
    }

    private static void add(Map<ItemType, Long> counts, Map<ItemType, ItemStack> stacks, ItemStack single, long amount) {
        ItemType type = new ItemType(single, false, true);
        counts.merge(type, amount, Long::sum);
        if (stacks != null) stacks.putIfAbsent(type, single);
    }

    private static List<MaterialListEntry> merge(List<MaterialListEntry> blocks, List<MaterialListEntry> containers) {
        Map<ItemType, MaterialListEntry> merged = new LinkedHashMap<>();
        for (MaterialListEntry entry : blocks) merged.put(new ItemType(entry.getStack(), false, true), entry);
        for (MaterialListEntry entry : containers) {
            merged.merge(new ItemType(entry.getStack(), false, true), entry, (a, b) -> new MaterialListEntry(a.getStack(),
                    a.getCountTotal() + b.getCountTotal(), a.getCountMissing() + b.getCountMissing(),
                    a.getCountMismatched() + b.getCountMismatched(), a.getCountAvailable()));
        }
        return new ArrayList<>(merged.values());
    }

    private static int clamp(long value) {
        return (int) Math.min(Integer.MAX_VALUE, value);
    }
}
