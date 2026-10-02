/*
 * Cytra Container
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.filler;

/** Outcome of a finished or cancelled fill, as reported to the player. */
public record FillResult(int filledSlots, int missingItems, int wrongSlots, int actions, int shulkerRetrievals, boolean cancelled) {
}
