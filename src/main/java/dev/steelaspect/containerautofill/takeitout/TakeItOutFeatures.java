/*
 * Container Auto Fill
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.takeitout;

import dev.steelaspect.containerautofill.config.Configs;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.function.Predicate;

/**
 * TakeItOut behaviour, reimplemented:
 * <ul>
 *     <li>Pick block (vanilla middle click, and Litematica's schematic pick block / easy place) pulls the
 *     item out of a shulker box in the inventory when it isn't carried loose.</li>
 *     <li>"Auto Take Out" toggle (default R): while on, looking at a schematic block you don't carry
 *     pulls it from a shulker; right-clicking a schematic block outside easy place does a schematic
 *     pick block.</li>
 * </ul>
 */
public final class TakeItOutFeatures {
    private static final double AUTO_LOOK_RANGE = 3.0;
    private static final double RIGHT_CLICK_RANGE = 5.0;

    private static boolean useKeyWasDown;

    private TakeItOutFeatures() {
    }

    public static void toggleAutoTakeOut(MinecraftClient client) {
        Configs.AUTO_TAKE_OUT.toggleBooleanValue();
        if (client.player != null) {
            String key = Configs.AUTO_TAKE_OUT.getBooleanValue() ? "containerautofill.message.auto_take_out_on" : "containerautofill.message.auto_take_out_off";
            client.player.sendMessage(Text.translatable(key), false);
        }
    }

    public static void tick(MinecraftClient client) {
        boolean useDown = client.options.useKey.isPressed();
        boolean useClicked = useDown && !useKeyWasDown;
        useKeyWasDown = useDown;

        if (!Configs.AUTO_TAKE_OUT.getBooleanValue()) return;
        if (client.player == null || client.world == null || client.currentScreen != null) return;
        if (client.player.isCreative() || !client.player.getAbilities().allowModifyWorld) return;
        if (ShulkerRetriever.isWaiting()) return;

        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null) return;

        BlockHitResult near = RayTraceUtils.traceToSchematicWorld(client.player, AUTO_LOOK_RANGE, true, true);
        if (near != null && near.getType() == HitResult.Type.BLOCK && isMissingButInShulker(client, schematic, near.getBlockPos())) {
            WorldUtils.doSchematicWorldPickBlock(true, client);
            return;
        }

        if (useClicked && !fi.dy.masa.litematica.config.Configs.Generic.EASY_PLACE_MODE.getBooleanValue()) {
            BlockHitResult hit = RayTraceUtils.traceToSchematicWorld(client.player, RIGHT_CLICK_RANGE, true, true);
            if (hit != null && hit.getType() == HitResult.Type.BLOCK && !isBehindRealBlock(client, hit)
                    && !schematic.getBlockState(hit.getBlockPos()).isAir()) {
                WorldUtils.doSchematicWorldPickBlock(true, client);
            }
        }
    }

    /**
     * Litematica schematic pick block / easy place hook. Returns true when a shulker request was sent
     * (or one is already in flight for this item), so Litematica's own pick is skipped this time.
     */
    public static boolean onSchematicPickBlock(ItemStack required, MinecraftClient client) {
        if (!Configs.ENABLE_MOD.getBooleanValue() || !Configs.SHULKER_PICK_BLOCK.getBooleanValue()) return false;
        if (client.player == null || client.player.isCreative() || required.isEmpty()) return false;

        Predicate<ItemStack> matcher = s -> ItemStack.areItemsAndComponentsEqual(s, required);
        if (ShulkerRetriever.countInInventory(client.player.getInventory(), matcher) > 0) return false;
        if (!isSelectedSlotPickBlockable(client)) return false;
        if (ShulkerRetriever.isWaitingFor(required)) return true;

        return ShulkerRetriever.request(client, required, matcher) == ShulkerRetriever.Outcome.REQUESTED;
    }

    /** Vanilla pick block on a real block (survival only, as in TakeItOut). */
    public static void onVanillaPickBlock(MinecraftClient client, BlockPos pos) {
        if (!Configs.ENABLE_MOD.getBooleanValue() || !Configs.SHULKER_PICK_BLOCK.getBooleanValue()) return;
        if (client.player == null || client.world == null || client.player.isCreative()) return;

        BlockState state = client.world.getBlockState(pos);
        ItemStack stack = state.getPickStack(client.world, pos, false);
        if (stack.isEmpty()) return;

        Predicate<ItemStack> matcher = s -> ItemStack.areItemsAndComponentsEqual(s, stack);
        if (ShulkerRetriever.countInInventory(client.player.getInventory(), matcher) > 0) return;
        ShulkerRetriever.request(client, stack, matcher);
    }

    private static boolean isMissingButInShulker(MinecraftClient client, WorldSchematic schematic, BlockPos pos) {
        BlockState state = schematic.getBlockState(pos);
        if (state.isAir()) return false;
        ItemStack required = MaterialCache.getInstance().getRequiredBuildItemForState(state, schematic, pos);
        if (required.isEmpty()) return false;

        Predicate<ItemStack> matcher = s -> ItemStack.areItemsAndComponentsEqual(s, required);
        if (ShulkerRetriever.countInInventory(client.player.getInventory(), matcher) > 0) return false;
        return ShulkerRetriever.find(client.player.getInventory(), matcher) != null;
    }

    /** True if a real block is in front of (or at) the schematic block being targeted. */
    private static boolean isBehindRealBlock(MinecraftClient client, BlockHitResult schematicHit) {
        if (!(client.crosshairTarget instanceof BlockHitResult worldHit) || worldHit.getType() != HitResult.Type.BLOCK) return false;
        if (worldHit.getBlockPos().equals(schematicHit.getBlockPos())) return true;
        Vec3d eyes = client.player.getEyePos();
        return eyes.distanceTo(worldHit.getPos()) + 1.0e-6 < eyes.distanceTo(schematicHit.getPos());
    }

    /** Respects Litematica's "pickBlockableSlots" list (1-based, comma separated, ranges allowed). */
    private static boolean isSelectedSlotPickBlockable(MinecraftClient client) {
        String raw = fi.dy.masa.litematica.config.Configs.Generic.PICK_BLOCKABLE_SLOTS.getStringValue();
        if (raw == null || raw.isBlank()) return true;

        int selected = client.player.getInventory().getSelectedSlot() + 1;
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            try {
                int dash = trimmed.indexOf('-');
                if (dash > 0) {
                    int from = Integer.parseInt(trimmed.substring(0, dash).trim());
                    int to = Integer.parseInt(trimmed.substring(dash + 1).trim());
                    if (selected >= Math.min(from, to) && selected <= Math.max(from, to)) return true;
                } else if (Integer.parseInt(trimmed) == selected) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return false;
    }
}
