/*
 * Container Auto Fill
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.filler;

import dev.steelaspect.containerautofill.config.Configs;
import dev.steelaspect.containerautofill.takeitout.ShulkerRetriever;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.CrafterScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Fills one open container towards the schematic's expected contents.
 * <p>
 * Every move is a single vanilla {@code interactionManager.clickSlot} call, at most one per
 * {@code clickDelay} ticks. Before each click the current slot contents are read again, so server
 * corrections or other players changing the container are picked up instead of being fought.
 */
public final class ContainerFillJob {
    private static final int MAX_ACTIONS = 4000;
    private static final int MAX_CLICKS_PER_TRANSFER = 160;
    private static final int MAX_RETRIEVAL_FAILURES = 2;

    private final ScreenHandler handler;
    private final BlockPos pos;
    private final Map<Integer, ItemStack> expected;
    private final SlotMapper mapper;
    private final boolean closeWhenDone;

    private final Set<Integer> touchedSlots = new HashSet<>();
    private final Set<Integer> clearAttempted = new HashSet<>();
    private final Set<Integer> blockedSlots = new HashSet<>();
    private final Set<ItemMatcher.StackKey> unavailable = new HashSet<>();
    private final Map<ItemMatcher.StackKey, Integer> retrievalFailures = new HashMap<>();

    private Transfer transfer;
    private RetrievalWait retrievalWait;
    private int ticksSinceAction = Integer.MAX_VALUE / 2;
    private int actions;
    private int retrievedStacks;
    private boolean inventoryFull;
    private boolean finished;
    private String cancelReasonKey;

    private record Transfer(int sourceSlotId, int targetSlotId, ItemStack item, int goal, int[] clicks) {
    }

    private record RetrievalWait(ItemMatcher.StackKey key, Predicate<ItemStack> matcher, int countBefore) {
    }

    public ContainerFillJob(ScreenHandler handler, BlockPos pos, Map<Integer, ItemStack> expected, SlotMapper mapper, boolean closeWhenDone) {
        this.handler = handler;
        this.pos = pos;
        this.expected = expected;
        this.mapper = mapper;
        this.closeWhenDone = closeWhenDone;

        for (Integer slot : expected.keySet()) {
            if (mapper.getContainerSlot(slot) == null) {
                Configs.debug("Schematic slot {} does not exist in the open container ({} slots), ignored", slot, mapper.containerSize());
            }
        }
    }

    public ScreenHandler handler() {
        return this.handler;
    }

    public BlockPos pos() {
        return this.pos;
    }

    public boolean closeWhenDone() {
        return this.closeWhenDone;
    }

    public boolean isFinished() {
        return this.finished;
    }

    public boolean wasCancelled() {
        return this.cancelReasonKey != null;
    }

    public void cancel(String reasonKey) {
        if (this.finished) return;
        this.cancelReasonKey = reasonKey;
        this.finished = true;
    }

    public void tick(MinecraftClient client) {
        if (this.finished) return;
        if (!isStillOpen(client)) {
            cancel("containerautofill.message.cancelled_screen_closed");
            return;
        }

        this.ticksSinceAction++;

        if (this.retrievalWait != null) {
            if (ShulkerRetriever.isWaiting()) return;
            finishRetrievalWait(client);
        }

        if (this.ticksSinceAction < Configs.CLICK_DELAY.getIntegerValue()) return;

        if (this.actions >= MAX_ACTIONS) {
            cancel("containerautofill.message.cancelled_too_many_actions");
            return;
        }

        if (step(client)) {
            this.actions++;
            this.ticksSinceAction = 0;
        } else {
            this.finished = true;
        }
    }

    private boolean isStillOpen(MinecraftClient client) {
        return client.player != null
                && client.interactionManager != null
                && client.player.currentScreenHandler == this.handler
                && client.currentScreen instanceof HandledScreen<?> screen
                && screen.getScreenHandler() == this.handler;
    }

    /** Performs at most one action. Returns false when there is nothing left to do. */
    private boolean step(MinecraftClient client) {
        if (this.transfer != null) {
            if (continueTransfer(client)) return true;
            this.transfer = null;
        }

        ItemStack cursor = this.handler.getCursorStack();
        if (!cursor.isEmpty()) {
            return stashCursor(client, cursor);
        }

        boolean clearWrong = Configs.CLEAR_WRONG_ITEMS.getBooleanValue();

        for (Map.Entry<Integer, Slot> entry : this.mapper.containerSlots().entrySet()) {
            int index = entry.getKey();
            Slot slot = entry.getValue();
            if (this.blockedSlots.contains(index)) continue;
            if (this.handler instanceof CrafterScreenHandler crafter && crafter.isSlotDisabled(slot.id)) continue;

            ItemStack want = this.expected.getOrDefault(index, ItemStack.EMPTY);
            ItemStack have = slot.getStack();

            if (want.isEmpty()) {
                if (!have.isEmpty() && clearWrong && this.clearAttempted.add(index)) {
                    click(client, slot.id, 0, SlotActionType.QUICK_MOVE);
                    return true;
                }
                continue;
            }

            if (!have.isEmpty() && !ItemMatcher.isSameItem(have, want)) {
                if (clearWrong && this.clearAttempted.add(index)) {
                    click(client, slot.id, 0, SlotActionType.QUICK_MOVE);
                    return true;
                }
                this.blockedSlots.add(index);
                continue;
            }

            int goal = Math.min(want.getCount(), slot.getMaxItemCount(want));
            int need = goal - have.getCount();
            if (need <= 0) continue;
            if (!slot.canInsert(want)) {
                this.blockedSlots.add(index);
                continue;
            }

            ItemMatcher.StackKey key = new ItemMatcher.StackKey(want);
            if (this.unavailable.contains(key)) continue;

            Slot source = findSource(want, need);
            if (source != null) {
                this.touchedSlots.add(index);
                this.transfer = new Transfer(source.id, slot.id, want.copyWithCount(1), goal, new int[1]);
                if (continueTransfer(client)) return true;
                this.transfer = null;
                continue;
            }

            if (Configs.USE_TAKEITOUT_SOURCES.getBooleanValue() && requestFromShulker(client, want, key)) {
                return true;
            }
            this.unavailable.add(key);
        }
        return false;
    }

    private boolean continueTransfer(MinecraftClient client) {
        Transfer t = this.transfer;
        Slot source = this.handler.getSlot(t.sourceSlotId());
        Slot target = this.handler.getSlot(t.targetSlotId());
        int s = countOf(source.getStack(), t.item());
        int tc = countOf(target.getStack(), t.item());
        int c = countOf(this.handler.getCursorStack(), t.item());
        if (s < 0 || tc < 0 || c < 0) return false;
        if (++t.clicks()[0] > MAX_CLICKS_PER_TRANSFER) return false;

        int goal = Math.min(t.goal(), s + tc + c);
        ClickPlanner.Click next = ClickPlanner.firstClick(s, tc, c, goal,
                source.getMaxItemCount(t.item()), target.getMaxItemCount(t.item()));
        if (next == null) return false;

        int slotId = next.side() == ClickPlanner.Side.SOURCE ? t.sourceSlotId() : t.targetSlotId();
        click(client, slotId, next.button(), SlotActionType.PICKUP);
        return true;
    }

    /** Count of {@code item} in the stack, 0 if empty, -1 if it holds something else. */
    private static int countOf(ItemStack stack, ItemStack item) {
        if (stack.isEmpty()) return 0;
        return ItemStack.areItemsAndComponentsEqual(stack, item) ? stack.getCount() : -1;
    }

    /** Prefers the smallest player stack that covers the need, else the largest one. */
    private Slot findSource(ItemStack want, int need) {
        Slot best = null;
        for (Slot slot : this.mapper.playerSlots().values()) {
            ItemStack stack = slot.getStack();
            if (stack.isEmpty() || !ItemStack.areItemsAndComponentsEqual(stack, want)) continue;
            if (!slot.canTakeItems(MinecraftClient.getInstance().player)) continue;
            if (best == null) {
                best = slot;
                continue;
            }
            int bestCount = best.getStack().getCount();
            int count = stack.getCount();
            boolean covers = count >= need;
            boolean bestCovers = bestCount >= need;
            if ((covers && (!bestCovers || count < bestCount)) || (!covers && !bestCovers && count > bestCount)) {
                best = slot;
            }
        }
        return best;
    }

    private boolean requestFromShulker(MinecraftClient client, ItemStack want, ItemMatcher.StackKey key) {
        if (client.player == null) return false;
        Predicate<ItemStack> matcher = s -> ItemStack.areItemsAndComponentsEqual(s, want);
        int before = ShulkerRetriever.countInInventory(client.player.getInventory(), matcher);

        ShulkerRetriever.Outcome outcome = ShulkerRetriever.request(client, want, matcher);
        switch (outcome) {
            case REQUESTED -> {
                this.retrievalWait = new RetrievalWait(key, matcher, before);
                return true;
            }
            case BUSY -> {
                // Another request (e.g. a pick block) is still in flight; wait for it.
                return true;
            }
            case INVENTORY_FULL -> this.inventoryFull = true;
            default -> {
            }
        }
        return false;
    }

    private void finishRetrievalWait(MinecraftClient client) {
        RetrievalWait wait = this.retrievalWait;
        this.retrievalWait = null;
        if (client.player == null) return;

        int now = ShulkerRetriever.countInInventory(client.player.getInventory(), wait.matcher());
        if (now > wait.countBefore()) {
            this.retrievedStacks++;
            this.retrievalFailures.remove(wait.key());
        } else {
            int failures = this.retrievalFailures.merge(wait.key(), 1, Integer::sum);
            Configs.debug("Shulker retrieval for {} failed ({}x)", wait.key().stack(), failures);
            if (failures >= MAX_RETRIEVAL_FAILURES) {
                this.unavailable.add(wait.key());
            }
        }
    }

    /** Puts a leftover cursor stack back into the player inventory (never drops it). */
    private boolean stashCursor(MinecraftClient client, ItemStack cursor) {
        Slot empty = null;
        for (Slot slot : this.mapper.playerSlots().values()) {
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) {
                if (empty == null) empty = slot;
            } else if (ItemStack.areItemsAndComponentsEqual(stack, cursor) && stack.getCount() < slot.getMaxItemCount(stack)) {
                click(client, slot.id, 0, SlotActionType.PICKUP);
                return true;
            }
        }
        if (empty != null) {
            click(client, empty.id, 0, SlotActionType.PICKUP);
            return true;
        }
        this.inventoryFull = true;
        cancel("containerautofill.message.cancelled_cursor_stuck");
        return false;
    }

    private void click(MinecraftClient client, int slotId, int button, SlotActionType action) {
        Configs.debug("clickSlot(sync={}, slot={}, button={}, {})", this.handler.syncId, slotId, button, action);
        client.interactionManager.clickSlot(this.handler.syncId, slotId, button, action, client.player);
    }

    /** Action bar summary plus a chat list of missing items. */
    public void report(MinecraftClient client) {
        if (client.player == null) return;

        int filledSlots = 0;
        int wrongSlots = 0;
        int missingTotal = 0;
        Map<ItemMatcher.StackKey, Integer> missing = new LinkedHashMap<>();

        for (Map.Entry<Integer, Slot> entry : this.mapper.containerSlots().entrySet()) {
            ItemStack want = this.expected.getOrDefault(entry.getKey(), ItemStack.EMPTY);
            if (want.isEmpty()) continue;
            Slot slot = entry.getValue();
            ItemStack have = slot.getStack();
            int goal = Math.min(want.getCount(), slot.getMaxItemCount(want));

            int deficit;
            if (have.isEmpty()) {
                deficit = goal;
            } else if (ItemMatcher.isSameItem(have, want)) {
                deficit = Math.max(0, goal - have.getCount());
            } else {
                deficit = goal;
                wrongSlots++;
            }

            if (deficit == 0 && this.touchedSlots.contains(entry.getKey())) filledSlots++;
            if (deficit > 0) {
                missingTotal += deficit;
                missing.merge(new ItemMatcher.StackKey(want), deficit, Integer::sum);
            }
        }

        if (this.cancelReasonKey != null) {
            client.player.sendMessage(Text.translatable(this.cancelReasonKey).formatted(Formatting.RED), false);
        }
        client.player.sendMessage(Text.translatable("containerautofill.message.summary", filledSlots, missingTotal), true);

        if (!missing.isEmpty()) {
            client.player.sendMessage(Text.translatable("containerautofill.message.missing_header", missingTotal).formatted(Formatting.GOLD), false);
            for (Map.Entry<ItemMatcher.StackKey, Integer> e : missing.entrySet()) {
                MutableText line = Text.literal(" - " + e.getValue() + "x ").formatted(Formatting.GRAY)
                        .append(e.getKey().stack().getName().copy().formatted(Formatting.WHITE));
                client.player.sendMessage(line, false);
            }
        }
        if (wrongSlots > 0) {
            String key = Configs.CLEAR_WRONG_ITEMS.getBooleanValue()
                    ? "containerautofill.message.wrong_items_remaining"
                    : "containerautofill.message.wrong_items_kept";
            client.player.sendMessage(Text.translatable(key, wrongSlots).formatted(Formatting.YELLOW), false);
        }
        if (this.inventoryFull) {
            client.player.sendMessage(Text.translatable("containerautofill.message.inventory_full").formatted(Formatting.YELLOW), false);
        }
        Configs.debug("Fill of {} finished: {} actions, {} shulker retrievals, {} filled, {} missing",
                this.pos, this.actions, this.retrievedStacks, filledSlots, missingTotal);
    }
}
