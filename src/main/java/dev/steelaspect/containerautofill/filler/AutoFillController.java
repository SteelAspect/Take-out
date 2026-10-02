/*
 * Container Auto Fill
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.filler;

import dev.steelaspect.containerautofill.config.Configs;
import dev.steelaspect.containerautofill.highlight.ContainerHighlighter;
import dev.steelaspect.containerautofill.highlight.ContainerStatus;
import dev.steelaspect.containerautofill.highlight.SchematicContainerIndex;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.inventory.Inventory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Entry points for both fill modes:
 * <ul>
 *     <li>{@link #fillOpenContainer} - the auto-fill hotkey, for a container screen that is already open.</li>
 *     <li>{@link #fillLookedAtContainer} - the Litematica-Container-Filler style hotkey: opens the
 *     schematic container under the crosshair, fills it, then closes it.</li>
 * </ul>
 */
public final class AutoFillController {
    private static final int OPEN_TIMEOUT_TICKS = 40;

    private static ContainerFillJob job;
    private static BlockPos pendingOpenPos;
    private static int pendingOpenTicks;
    private static FillResult lastResult;
    private static String lastMessageKey;

    private AutoFillController() {
    }

    public static boolean isRunning() {
        return job != null;
    }

    /** Result of the most recent fill, or null if none finished yet. */
    public static FillResult getLastResult() {
        return lastResult;
    }

    static void setLastResult(FillResult result) {
        lastResult = result;
    }

    /** Translation key of the most recent action-bar message from the controller. */
    public static String getLastMessageKey() {
        return lastMessageKey;
    }

    public static void fillOpenContainer(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        if (job != null) {
            job.cancel("containerautofill.message.cancelled_by_user");
            return;
        }

        if (!(client.currentScreen instanceof HandledScreen<?> screen)
                || screen instanceof InventoryScreen
                || screen instanceof CreativeInventoryScreen
                || client.player.currentScreenHandler == client.player.playerScreenHandler) {
            actionBar(client, Text.translatable("containerautofill.message.no_container_open"));
            return;
        }

        BlockPos pos = ContainerTracker.getOpenContainerPos(client);
        if (pos == null) {
            actionBar(client, Text.translatable("containerautofill.message.unknown_container_pos"));
            return;
        }
        start(client, client.player.currentScreenHandler, pos, false);
    }

    public static void fillLookedAtContainer(MinecraftClient client) {
        if (client.player == null || client.world == null || client.interactionManager == null) return;
        if (client.currentScreen != null) return;
        if (job != null || pendingOpenPos != null || InstantFill.isRunning()) {
            actionBar(client, Text.translatable("containerautofill.message.already_running"));
            return;
        }

        if (!(client.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            actionBar(client, Text.translatable("containerautofill.message.not_looking_at_container"));
            return;
        }
        BlockPos pos = hit.getBlockPos().toImmutable();
        BlockEntity blockEntity = client.world.getBlockEntity(pos);
        if (!(blockEntity instanceof Inventory)) {
            actionBar(client, Text.translatable("containerautofill.message.not_looking_at_container"));
            return;
        }
        SchematicContainerReader.Result result = SchematicContainerReader.read(client.world, pos, client.world.getRegistryManager());
        if (!reportReadProblem(client, result)) return;

        // Server-side instant fill: nothing is opened, every slot is filled at once.
        if (Configs.INSTANT_FILL.getBooleanValue() && InstantFill.isSupported()) {
            InstantFill.start(client, pos, SchematicContainerReader.readHalves(client.world, pos, client.world.getRegistryManager()));
            return;
        }

        if (client.player.isSneaking()) {
            actionBar(client, Text.translatable("containerautofill.message.stop_sneaking"));
            return;
        }

        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
        pendingOpenPos = pos;
        pendingOpenTicks = 0;
    }

    /**
     * Area key: instant-fills every placed schematic container within Area Fill Range that isn't already
     * correct, nearest first. Needs server support (singleplayer/LAN or containerautofill-server).
     */
    public static void fillArea(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        if (!InstantFill.isSupported()) {
            actionBar(client, Text.translatable("containerautofill.message.area_fill_unsupported"));
            return;
        }
        if (job != null || pendingOpenPos != null || InstantFill.isRunning()) {
            actionBar(client, Text.translatable("containerautofill.message.already_running"));
            return;
        }

        SchematicContainerIndex.refresh(client.world.getRegistryManager());
        double range = Configs.AREA_FILL_RANGE.getIntegerValue();
        BlockPos center = client.player.getBlockPos();
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos pos : SchematicContainerIndex.entries().keySet()) {
            if (pos.getSquaredDistance(center) <= range * range) candidates.add(pos);
        }
        candidates.sort(Comparator.comparingDouble(pos -> pos.getSquaredDistance(center)));

        Map<BlockPos, ContainerStatus> statuses = ContainerHighlighter.statuses();
        Set<BlockPos> seen = new HashSet<>();
        List<SchematicContainerReader.Halves> toFill = new ArrayList<>();
        for (BlockPos pos : candidates) {
            if (seen.contains(pos) || !client.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;
            SchematicContainerReader.Halves halves = SchematicContainerReader.readHalves(client.world, pos, client.world.getRegistryManager());
            if (halves.status() != SchematicContainerReader.Status.OK) {
                seen.add(pos);
                continue;
            }
            boolean allCorrect = true;
            for (SchematicContainerReader.Part part : halves.parts()) {
                seen.add(part.pos());
                if (statuses.get(part.pos()) != ContainerStatus.CORRECT) allCorrect = false;
            }
            if (!allCorrect) toFill.add(halves);
        }

        if (toFill.isEmpty()) {
            actionBar(client, Text.translatable("containerautofill.message.area_fill_nothing"));
            return;
        }
        InstantFill.startMany(client, toFill);
    }

    public static void tick(MinecraftClient client) {
        if (pendingOpenPos != null) {
            tickPendingOpen(client);
        }

        if (job != null) {
            job.tick(client);
            if (job.isFinished()) {
                ContainerFillJob done = job;
                job = null;
                lastResult = done.report(client);
                if (done.closeWhenDone() && !done.wasCancelled() && client.player != null
                        && client.player.currentScreenHandler == done.handler()) {
                    client.player.closeHandledScreen();
                }
            }
        }
    }

    /** Stops everything without a report (world change, mod disabled). */
    public static void reset() {
        job = null;
        pendingOpenPos = null;
    }

    private static void tickPendingOpen(MinecraftClient client) {
        if (client.player == null) {
            pendingOpenPos = null;
            return;
        }
        ScreenHandler handler = client.player.currentScreenHandler;
        if (client.currentScreen instanceof HandledScreen<?> && handler != client.player.playerScreenHandler) {
            BlockPos pos = pendingOpenPos;
            pendingOpenPos = null;
            if (pos.equals(ContainerTracker.getOpenContainerPos(client))) {
                start(client, handler, pos, Configs.CLOSE_AFTER_LOOK_FILL.getBooleanValue());
            }
        } else if (++pendingOpenTicks > OPEN_TIMEOUT_TICKS) {
            pendingOpenPos = null;
            actionBar(client, Text.translatable("containerautofill.message.container_did_not_open"));
        }
    }

    private static void start(MinecraftClient client, ScreenHandler handler, BlockPos pos, boolean closeWhenDone) {
        SchematicContainerReader.Result result = SchematicContainerReader.read(client.world, pos, client.world.getRegistryManager());
        if (!reportReadProblem(client, result)) return;

        SlotMapper mapper = new SlotMapper(handler, client.player.getInventory());
        if (mapper.containerSize() == 0) {
            actionBar(client, Text.translatable("containerautofill.message.no_container_open"));
            return;
        }
        Configs.debug("Starting fill of {} ({} container slots, {} expected stacks)", pos, mapper.containerSize(), result.items().size());
        job = new ContainerFillJob(handler, pos, result.items(), result.disabledSlots(), mapper, closeWhenDone);
    }

    /** Shows why a container can't be filled. Returns true if the read result is usable. */
    private static boolean reportReadProblem(MinecraftClient client, SchematicContainerReader.Result result) {
        switch (result.status()) {
            case OK -> {
                return true;
            }
            case NOT_IN_PLACEMENT -> actionBar(client, Text.translatable("containerautofill.message.not_in_placement"));
            case NOT_A_CONTAINER -> actionBar(client, Text.translatable("containerautofill.message.schematic_not_container"));
            case BLOCK_MISMATCH -> {
                Block expected = result.expectedBlock();
                actionBar(client, Text.translatable("containerautofill.message.block_mismatch",
                        expected != null ? expected.getName() : Text.literal("?")));
            }
        }
        return false;
    }

    private static void actionBar(MinecraftClient client, Text text) {
        if (text.getContent() instanceof net.minecraft.text.TranslatableTextContent translatable) {
            lastMessageKey = translatable.getKey();
        }
        if (client.player != null) {
            client.player.sendMessage(text.copy().formatted(Formatting.YELLOW), true);
        }
    }
}
