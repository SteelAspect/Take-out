/*
 * Container Auto Fill
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.filler;

import java.util.ArrayDeque;

/**
 * Finds the shortest sequence of vanilla PICKUP clicks (left = whole stack, right = half / one item)
 * that moves an exact number of items from a source slot to a target slot, ending with an empty
 * cursor. Only one item type is involved, so the state is (source, target, cursor) counts with a
 * constant total, i.e. at most 129 x 65 states.
 */
public final class ClickPlanner {
    public enum Side { SOURCE, TARGET }

    public record Click(Side side, int button) {
    }

    private static final Click[] MOVES = {
            new Click(Side.SOURCE, 0), new Click(Side.SOURCE, 1),
            new Click(Side.TARGET, 0), new Click(Side.TARGET, 1)
    };

    private ClickPlanner() {
    }

    /**
     * @return the first click of a shortest plan reaching {@code target == goal && cursor == 0}, or
     * null if the state already satisfies the goal or no plan exists.
     */
    public static Click firstClick(int source, int target, int cursor, int goal, int sourceMax, int targetMax) {
        if (target == goal && cursor == 0) return null;
        int total = source + target + cursor;
        if (goal > total || goal > targetMax || goal < 0) return null;

        int width = total + 1;
        int[] firstMove = new int[width * width];
        java.util.Arrays.fill(firstMove, -1);
        ArrayDeque<int[]> queue = new ArrayDeque<>();

        int start = target * width + cursor;
        firstMove[start] = Integer.MAX_VALUE;
        queue.add(new int[]{target, cursor});

        while (!queue.isEmpty()) {
            int[] state = queue.poll();
            int t = state[0];
            int c = state[1];
            int s = total - t - c;
            int origin = firstMove[t * width + c];

            for (int m = 0; m < MOVES.length; m++) {
                int[] next = apply(MOVES[m], s, t, c, sourceMax, targetMax);
                if (next == null) continue;
                int key = next[1] * width + next[2];
                if (firstMove[key] != -1) continue;

                int move = origin == Integer.MAX_VALUE ? m : origin;
                firstMove[key] = move;
                if (next[1] == goal && next[2] == 0) return MOVES[move];
                queue.add(new int[]{next[1], next[2]});
            }
        }
        return null;
    }

    /** Applies vanilla PICKUP semantics for a single item type. Returns {source, target, cursor} or null for a no-op. */
    private static int[] apply(Click click, int s, int t, int c, int sourceMax, int targetMax) {
        boolean onSource = click.side() == Side.SOURCE;
        int slot = onSource ? s : t;
        int max = onSource ? sourceMax : targetMax;
        int newSlot;
        int newCursor;

        if (c == 0) {
            if (slot == 0) return null;
            int taken = click.button() == 0 ? slot : (slot + 1) / 2;
            newSlot = slot - taken;
            newCursor = taken;
        } else {
            int room = max - slot;
            if (room <= 0) return null;
            int placed = click.button() == 0 ? Math.min(c, room) : 1;
            newSlot = slot + placed;
            newCursor = c - placed;
        }
        return onSource ? new int[]{newSlot, t, newCursor} : new int[]{s, newSlot, newCursor};
    }
}
