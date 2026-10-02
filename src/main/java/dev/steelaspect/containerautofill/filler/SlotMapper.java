/*
 * Cytra Container
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 *
 * Ported from Litematica-Container-Filler (core/SlotMapper.java),
 * https://github.com/MimicEnzymes/Litematica-Container-Filler, licensed LGPL-3.0-only.
 * Modified by steelaspect, 2026-10-02: keeps Slot objects, exposes the container slot range,
 * and limits player slots to the 36 main inventory slots.
 */
package dev.steelaspect.containerautofill.filler;

import dev.steelaspect.containerautofill.takeitout.ShulkerUtil;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.CrafterScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Maps container inventory indexes and player inventory indexes to the screen handler's slot list.
 * Double chests show up as one 54-slot inventory, so index 0..53 covers both halves.
 */
public final class SlotMapper {
    private final Map<Integer, Slot> playerSlots = new TreeMap<>();
    private final Map<Integer, Slot> containerSlots = new TreeMap<>();

    public SlotMapper(ScreenHandler handler, PlayerInventory playerInventory) {
        for (Slot slot : handler.slots) {
            if (slot.inventory == playerInventory) {
                if (slot.getIndex() < ShulkerUtil.PLAYER_MAIN_SLOTS) {
                    this.playerSlots.putIfAbsent(slot.getIndex(), slot);
                }
            } else {
                // The crafter's result slot is not part of its inventory.
                if (handler instanceof CrafterScreenHandler && slot.getIndex() == 9) continue;
                this.containerSlots.putIfAbsent(slot.getIndex(), slot);
            }
        }
    }

    public Slot getPlayerSlot(int playerInventoryIndex) {
        return this.playerSlots.get(playerInventoryIndex);
    }

    public Slot getContainerSlot(int containerIndex) {
        return this.containerSlots.get(containerIndex);
    }

    /** Container slots ordered by container index. */
    public Map<Integer, Slot> containerSlots() {
        return Collections.unmodifiableMap(this.containerSlots);
    }

    /** Player main-inventory slots ordered by player inventory index. */
    public Map<Integer, Slot> playerSlots() {
        return Collections.unmodifiableMap(this.playerSlots);
    }

    public int containerSize() {
        return this.containerSlots.size();
    }
}
