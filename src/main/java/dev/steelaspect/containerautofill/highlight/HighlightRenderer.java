/*
 * Cytra Container
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.highlight;

import dev.steelaspect.containerautofill.Reference;
import dev.steelaspect.containerautofill.config.Configs;
import fi.dy.masa.malilib.interfaces.IRenderer;
import fi.dy.masa.malilib.render.MaLiLibPipelines;
import fi.dy.masa.malilib.render.RenderContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.data.Color4f;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.Map;
import java.util.Set;
import dev.steelaspect.containerautofill.storage.StorageActions;
import dev.steelaspect.containerautofill.storage.StorageContents;
import dev.steelaspect.containerautofill.storage.StorageStore;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

/** Draws a see-through coloured box and an outline on every highlighted container. */
public final class HighlightRenderer implements IRenderer {
    public static final HighlightRenderer INSTANCE = new HighlightRenderer();
    private static final double EXPAND = 0.004;
    private static final float LINE_WIDTH = 2.0f;

    private boolean loggedError;

    private HighlightRenderer() {
    }

    @Override
    public void onRenderWorldLast(Matrix4f posMatrix, Matrix4f projMatrix) {
        if (!Configs.ENABLE_MOD.getBooleanValue()) return;
        renderLinkedOutlines();
        if (!Configs.HIGHLIGHT_CONTAINERS.getBooleanValue()) return;
        Map<BlockPos, ContainerStatus> statuses = ContainerHighlighter.statuses();
        if (statuses.isEmpty()) return;

        boolean throughWalls = Configs.HIGHLIGHT_THROUGH_WALLS.getBooleanValue();
        Vec3d camera = RenderUtils.camPos();

        try {
            try (RenderContext quads = new RenderContext(() -> Reference.MOD_ID + ":highlight/quads", throughWalls
                    ? MaLiLibPipelines.POSITION_COLOR_TRANSLUCENT_NO_DEPTH_NO_CULL
                    : MaLiLibPipelines.POSITION_COLOR_TRANSLUCENT_LEQUAL_DEPTH_OFFSET_2)) {
                BufferBuilder buffer = quads.getBuilder();
                for (Map.Entry<BlockPos, ContainerStatus> entry : statuses.entrySet()) {
                    Color4f color = colorFor(entry.getValue());
                    if (color != null) {
                        RenderUtils.drawBlockBoundingBoxSidesBatchedQuads(entry.getKey(), camera, color, EXPAND, buffer);
                    }
                }
                draw(quads, buffer);
            }

            try (RenderContext lines = new RenderContext(() -> Reference.MOD_ID + ":highlight/lines", throughWalls
                    ? MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_NO_DEPTH_NO_CULL
                    : MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_LEQUAL_DEPTH)) {
                BufferBuilder buffer = lines.getBuilder();
                for (Map.Entry<BlockPos, ContainerStatus> entry : statuses.entrySet()) {
                    Color4f color = colorFor(entry.getValue());
                    if (color != null) {
                        Color4f outline = new Color4f(color.r, color.g, color.b, 0.9f);
                        RenderUtils.drawBlockBoundingBoxOutlinesBatchedLinesSimple(entry.getKey(), outline, EXPAND, LINE_WIDTH, buffer);
                    }
                }
                draw(lines, buffer);
            }
        } catch (Exception e) {
            if (!this.loggedError) {
                this.loggedError = true;
                Reference.LOGGER.warn("Container highlight rendering failed", e);
            }
        }
    }

    /** Outlines on linked containers (dump containers in their own colour) and pulsing Look At markers. */
    private void renderLinkedOutlines() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || StorageStore.worldKey() == null) return;
        boolean outlines = Configs.LINKED_OUTLINES.getBooleanValue();
        Set<StorageContents.Key> lookAt = StorageActions.lookAtTargets();
        if (!outlines && lookAt.isEmpty()) return;

        Identifier dimension = client.world.getRegistryKey().getValue();
        boolean throughWalls = Configs.LINKED_OUTLINES_THROUGH_WALLS.getBooleanValue();
        try (RenderContext lines = new RenderContext(() -> Reference.MOD_ID + ":linked/lines", throughWalls
                ? MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_NO_DEPTH_NO_CULL
                : MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_LEQUAL_DEPTH)) {
            BufferBuilder buffer = lines.getBuilder();
            if (outlines) {
                Color4f linked = Configs.LINKED_OUTLINE_COLOR.getColor();
                Color4f dump = Configs.DUMP_OUTLINE_COLOR.getColor();
                for (StorageStore.Entry entry : StorageStore.linkedEntries()) {
                    if (!entry.dimension.equals(dimension.toString())) continue;
                    RenderUtils.drawBlockBoundingBoxOutlinesBatchedLinesSimple(entry.pos(), entry.dump ? dump : linked, 0.002, LINE_WIDTH, buffer);
                }
            }
            float pulse = (float) (0.55 + 0.45 * Math.sin(System.currentTimeMillis() / 150.0));
            Color4f marker = new Color4f(1.0f, 0.2f, 1.0f, pulse);
            for (StorageContents.Key key : lookAt) {
                if (key.dimension().equals(dimension)) {
                    RenderUtils.drawBlockBoundingBoxOutlinesBatchedLinesSimple(key.pos(), marker, 0.03, 4.0f, buffer);
                }
            }
            draw(lines, buffer);
        } catch (Exception e) {
            if (!this.loggedError) {
                this.loggedError = true;
                Reference.LOGGER.warn("Linked container outline rendering failed", e);
            }
        }
    }

    private static void draw(RenderContext context, BufferBuilder buffer) {
        BuiltBuffer mesh = buffer.endNullable();
        if (mesh != null) {
            context.draw(mesh, false, true);
            mesh.close();
        }
    }

    /** Colour for a status, or null when that status is hidden in the config. */
    public static Color4f colorFor(ContainerStatus status) {
        return switch (status) {
            case CORRECT -> Configs.HIGHLIGHT_SHOW_CORRECT.getBooleanValue() ? Configs.COLOR_CORRECT.getColor() : null;
            case EMPTY -> Configs.COLOR_EMPTY.getColor();
            case PARTIAL -> Configs.COLOR_PARTIAL.getColor();
            case WRONG -> Configs.COLOR_WRONG.getColor();
            case UNKNOWN -> Configs.HIGHLIGHT_SHOW_UNKNOWN.getBooleanValue() ? Configs.COLOR_UNKNOWN.getColor() : null;
        };
    }
}
