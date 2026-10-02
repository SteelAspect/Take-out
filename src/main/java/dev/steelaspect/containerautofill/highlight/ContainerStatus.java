/*
 * Cytra Container
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.highlight;

/** How a real container compares with the schematic. */
public enum ContainerStatus {
    /** Every slot holds exactly what the schematic expects. */
    CORRECT,
    /** Nothing in it yet, but the schematic expects items. */
    EMPTY,
    /** Some expected items are missing; nothing wrong is in it. */
    PARTIAL,
    /** It holds an item the schematic doesn't expect in that slot, or too many of one. */
    WRONG,
    /** Contents unknown (multiplayer: open it once, or use Servux). */
    UNKNOWN
}
