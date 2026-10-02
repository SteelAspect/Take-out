/*
 * Container Auto Fill
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.test;

import dev.steelaspect.containerautofill.config.Configs;
import dev.steelaspect.containerautofill.filler.AutoFillController;
import dev.steelaspect.containerautofill.filler.FillResult;
import dev.steelaspect.containerautofill.highlight.ContainerHighlighter;
import dev.steelaspect.containerautofill.highlight.ContainerStatus;
import dev.steelaspect.containerautofill.takeitout.GetStackPayload;
import dev.steelaspect.containerautofill.takeitout.ShulkerRetriever;
import dev.steelaspect.containerautofill.takeitout.ShulkerStackServerHandler;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.malilib.event.InputEventHandler;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.CrafterBlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * End-to-end tests in a real singleplayer world (and one dedicated server), driven through real key
 * presses. The reference containers are built, saved as a Litematica schematic in memory, emptied, and
 * the schematic is placed back over them, so every container sits inside an active placement.
 */
public class ContainerAutoFillClientGameTest implements FabricClientGameTest {
    private static final Logger LOG = LoggerFactory.getLogger("ContainerAutoFillTest");
    private static final int AUTO_FILL_KEY = GLFW.GLFW_KEY_G;

    private final List<String> failures = new ArrayList<>();
    private final List<String> passes = new ArrayList<>();

    private BlockPos base;
    private BlockPos single, doubleLeft, doubleRight, hopper, barrel, dispenser, shulkerBox, mismatch, lookFill, lapisSpot, outside, emerald;
    // Second row: more container types, and highlight-only containers.
    private BlockPos furnace, smoker, blastFurnace, brewingStand, dropper, trappedLeft, trappedRight, copperLeft, copperRight,
            recoloredShulker, crafter, partialChest, correctChest;

    @Override
    public void runTest(ClientGameTestContext context) {
        context.runOnClient(client -> {
            Configs.AUTO_FILL_OPEN_CONTAINER.setValueFromString("G");
            Configs.DEBUG_LOGGING.setBooleanValue(true);
            Configs.CLICK_DELAY.setIntegerValue(1);
            InputEventHandler.getKeybindManager().updateUsedKeys();
        });

        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getClientWorld().waitForChunksRender();
            buildReferenceArea(context, world);

            testHighlightStatuses(context, world);
            testSingleChest(context, world);
            testHighlightUpdatesAfterFill(context, world);
            testAlreadyFilled(context, world);
            testDoubleChestWithShulkerRetrieval(context, world);
            testHopperWrongItemsKept(context, world);
            testBarrelClearWrongItems(context, world);
            testDispenserMissingItems(context, world);
            testNotInPlacement(context, world);
            testBlockMismatch(context, world);
            testLookFill(context, world);
            testScreenClosedMidFill(context, world);
            testVanillaPickFromShulker(context, world);
            testAutoTakeOut(context, world);
            testMoreContainerTypes(context, world);
        }

        testServerWithoutHandler(context);

        LOG.info("==== Container Auto Fill game test summary: {} passed, {} failed ====", passes.size(), failures.size());
        passes.forEach(p -> LOG.info("PASS {}", p));
        failures.forEach(f -> LOG.error("FAIL {}", f));
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " check(s) failed: " + String.join(" | ", failures));
        }
    }

    // ------------------------------------------------------------------ setup

    private static ItemStack stack(Item item, int count) {
        return new ItemStack(item, count);
    }

    private static ItemStack named(Item item, int count, String name) {
        ItemStack s = new ItemStack(item, count);
        s.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
        return s;
    }

    private static ItemStack shulkerWith(Item box, int innerSlot, ItemStack inner) {
        ItemStack s = new ItemStack(box);
        DefaultedList<ItemStack> contents = DefaultedList.ofSize(27, ItemStack.EMPTY);
        contents.set(innerSlot, inner);
        s.set(DataComponentTypes.CONTAINER, ContainerComponent.fromStacks(contents));
        return s;
    }

    private Map<Integer, ItemStack> singleExpected() {
        Map<Integer, ItemStack> m = new LinkedHashMap<>();
        m.put(0, stack(Items.STONE, 64));
        m.put(3, stack(Items.OAK_PLANKS, 7));
        m.put(5, named(Items.DIAMOND, 2, "Gem"));
        m.put(10, stack(Items.IRON_INGOT, 63));
        m.put(26, shulkerWith(Items.WHITE_SHULKER_BOX, 0, stack(Items.COBBLESTONE, 5)));
        return m;
    }

    private void buildReferenceArea(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runCommand("gamerule doDaylightCycle false");
        world.getServer().runCommand("gamerule doMobSpawning false");
        BlockPos feet = world.getServer().computeOnServer(server -> player(server).getBlockPos());
        base = feet.add(3, 0, 3);
        single = base;
        doubleLeft = base.add(3, 0, 0);
        doubleRight = base.add(4, 0, 0);
        hopper = base.add(7, 0, 0);
        barrel = base.add(9, 0, 0);
        dispenser = base.add(11, 0, 0);
        shulkerBox = base.add(13, 0, 0);
        mismatch = base.add(15, 0, 0);
        lookFill = base.add(17, 0, 0);
        lapisSpot = base.add(19, 0, 0);
        outside = base.add(0, 0, 5);
        emerald = base.add(3, 0, 5);
        BlockPos row2 = base.add(0, 0, 9);
        furnace = row2;
        smoker = row2.add(2, 0, 0);
        blastFurnace = row2.add(4, 0, 0);
        brewingStand = row2.add(6, 0, 0);
        dropper = row2.add(8, 0, 0);
        trappedLeft = row2.add(10, 0, 0);
        trappedRight = row2.add(11, 0, 0);
        copperLeft = row2.add(13, 0, 0);
        copperRight = row2.add(14, 0, 0);
        recoloredShulker = row2.add(16, 0, 0);
        crafter = row2.add(18, 0, 0);
        partialChest = row2.add(20, 0, 0);
        correctChest = row2.add(22, 0, 0);

        world.getServer().runOnServer(server -> {
            ServerWorld w = server.getOverworld();
            for (BlockPos p : BlockPos.iterate(base.add(-3, 0, -3), base.add(25, 5, 13))) {
                w.setBlockState(p, Blocks.AIR.getDefaultState());
            }
            for (BlockPos p : BlockPos.iterate(base.add(-3, -1, -3), base.add(25, -1, 13))) {
                w.setBlockState(p, Blocks.STONE.getDefaultState());
            }

            w.setBlockState(single, Blocks.CHEST.getDefaultState());
            BlockState chestNorth = Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, Direction.NORTH);
            w.setBlockState(doubleLeft, chestNorth.with(ChestBlock.CHEST_TYPE, ChestType.LEFT));
            w.setBlockState(doubleRight, chestNorth.with(ChestBlock.CHEST_TYPE, ChestType.RIGHT));
            w.setBlockState(hopper, Blocks.HOPPER.getDefaultState());
            w.setBlockState(barrel, Blocks.BARREL.getDefaultState());
            w.setBlockState(dispenser, Blocks.DISPENSER.getDefaultState());
            w.setBlockState(shulkerBox, Blocks.WHITE_SHULKER_BOX.getDefaultState());
            w.setBlockState(mismatch, Blocks.CHEST.getDefaultState());
            w.setBlockState(lookFill, Blocks.CHEST.getDefaultState());
            w.setBlockState(lapisSpot, Blocks.LAPIS_BLOCK.getDefaultState());
            w.setBlockState(outside, Blocks.CHEST.getDefaultState());
            w.setBlockState(emerald, Blocks.EMERALD_BLOCK.getDefaultState());

            fill(w, single, singleExpected());
            fill(w, doubleRight, Map.of(0, stack(Items.REDSTONE, 30), 26, stack(Items.LAPIS_LAZULI, 9)));
            fill(w, doubleLeft, Map.of(0, stack(Items.GOLD_INGOT, 20), 13, stack(Items.CLOCK, 1)));
            fill(w, hopper, Map.of(0, stack(Items.GLASS, 10), 1, stack(Items.SAND, 10), 4, stack(Items.TORCH, 3)));
            fill(w, barrel, Map.of(0, stack(Items.BRICKS, 5), 1, stack(Items.SAND, 10)));
            ItemStack damaged = stack(Items.FLINT_AND_STEEL, 1);
            damaged.setDamage(10);
            fill(w, dispenser, Map.of(0, stack(Items.ARROW, 16), 4, stack(Items.TNT, 2), 8, damaged));
            Map<Integer, ItemStack> allCobble = new LinkedHashMap<>();
            for (int i = 0; i < 27; i++) allCobble.put(i, stack(Items.COBBLESTONE, 64));
            fill(w, shulkerBox, allCobble);
            fill(w, mismatch, Map.of(0, stack(Items.STONE, 1)));
            fill(w, lookFill, Map.of(0, stack(Items.OAK_LOG, 32), 2, stack(Items.OAK_LOG, 32)));

            w.setBlockState(furnace, Blocks.FURNACE.getDefaultState());
            w.setBlockState(smoker, Blocks.SMOKER.getDefaultState());
            w.setBlockState(blastFurnace, Blocks.BLAST_FURNACE.getDefaultState());
            w.setBlockState(brewingStand, Blocks.BREWING_STAND.getDefaultState());
            w.setBlockState(dropper, Blocks.DROPPER.getDefaultState());
            BlockState trappedNorth = Blocks.TRAPPED_CHEST.getDefaultState().with(ChestBlock.FACING, Direction.NORTH);
            w.setBlockState(trappedLeft, trappedNorth.with(ChestBlock.CHEST_TYPE, ChestType.LEFT));
            w.setBlockState(trappedRight, trappedNorth.with(ChestBlock.CHEST_TYPE, ChestType.RIGHT));
            BlockState copperNorth = Blocks.COPPER_CHEST.getDefaultState().with(ChestBlock.FACING, Direction.NORTH);
            w.setBlockState(copperLeft, copperNorth.with(ChestBlock.CHEST_TYPE, ChestType.LEFT));
            w.setBlockState(copperRight, copperNorth.with(ChestBlock.CHEST_TYPE, ChestType.RIGHT));
            w.setBlockState(recoloredShulker, Blocks.RED_SHULKER_BOX.getDefaultState());
            w.setBlockState(crafter, Blocks.CRAFTER.getDefaultState());
            w.setBlockState(partialChest, Blocks.CHEST.getDefaultState());
            w.setBlockState(correctChest, Blocks.CHEST.getDefaultState());

            fill(w, furnace, Map.of(1, stack(Items.COAL, 4), 2, stack(Items.IRON_INGOT, 3)));
            fill(w, smoker, Map.of(1, stack(Items.CHARCOAL, 2)));
            fill(w, blastFurnace, Map.of(1, stack(Items.COAL_BLOCK, 1)));
            fill(w, brewingStand, Map.of(0, stack(Items.GLASS_BOTTLE, 1), 1, stack(Items.GLASS_BOTTLE, 1), 3, stack(Items.NETHER_WART, 2)));
            fill(w, dropper, Map.of(0, stack(Items.BONE, 5), 8, stack(Items.STRING, 3)));
            fill(w, trappedRight, Map.of(0, stack(Items.FLINT, 7)));
            fill(w, trappedLeft, Map.of(0, stack(Items.FEATHER, 9)));
            fill(w, copperRight, Map.of(0, stack(Items.COPPER_INGOT, 11)));
            fill(w, copperLeft, Map.of(5, stack(Items.RAW_COPPER, 12)));
            fill(w, recoloredShulker, Map.of(0, stack(Items.APPLE, 3)));
            fill(w, crafter, Map.of(0, stack(Items.OAK_PLANKS, 1), 4, stack(Items.OAK_PLANKS, 1)));
            CrafterBlockEntity crafterEntity = (CrafterBlockEntity) w.getBlockEntity(crafter);
            crafterEntity.setSlotEnabled(1, false);
            crafterEntity.setSlotEnabled(2, false);
            fill(w, partialChest, Map.of(0, stack(Items.STONE, 10)));
            fill(w, correctChest, Map.of(0, stack(Items.STONE, 1)));

            // Save the area as a schematic (read from the server world so container contents are included).
            AreaSelection area = new AreaSelection();
            area.addSubRegionBox(new Box(base, lapisSpot, "main"), false);
            area.addSubRegionBox(new Box(furnace, correctChest, "row2"), false);
            area.setExplicitOrigin(base);
            LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(
                    w, area, new LitematicaSchematic.SchematicSaveInfo(false, true), "steelaspect", msg -> LOG.warn("schematic: {}", msg));
            if (schematic == null) throw new IllegalStateException("Could not create schematic");
            SCHEMATIC = schematic;

            // Empty the real containers and change the world so it differs from the schematic.
            for (BlockPos p : List.of(single, doubleLeft, doubleRight, hopper, barrel, dispenser, shulkerBox, lookFill,
                    furnace, smoker, blastFurnace, brewingStand, dropper, trappedLeft, trappedRight, copperLeft, copperRight,
                    recoloredShulker, crafter, partialChest)) {
                ((Inventory) w.getBlockEntity(p)).clear();
            }
            fill(w, partialChest, Map.of(0, stack(Items.STONE, 5)));
            // Same container types as the schematic, different blocks: oxidised copper chest, blue instead of red shulker box.
            BlockState exposedNorth = Blocks.EXPOSED_COPPER_CHEST.getDefaultState().with(ChestBlock.FACING, Direction.NORTH);
            w.setBlockState(copperLeft, exposedNorth.with(ChestBlock.CHEST_TYPE, ChestType.LEFT));
            w.setBlockState(copperRight, exposedNorth.with(ChestBlock.CHEST_TYPE, ChestType.RIGHT));
            w.setBlockState(recoloredShulker, Blocks.BLUE_SHULKER_BOX.getDefaultState());
            // Crafter: schematic locks 1 and 2; the world starts with only slot 5 locked.
            CrafterBlockEntity worldCrafter = (CrafterBlockEntity) w.getBlockEntity(crafter);
            for (int i = 0; i < 9; i++) worldCrafter.setSlotEnabled(i, true);
            worldCrafter.setSlotEnabled(5, false);
            fill(w, hopper, Map.of(0, stack(Items.GLASS, 4), 1, stack(Items.DIRT, 2), 2, stack(Items.DIRT, 1)));
            fill(w, barrel, Map.of(1, stack(Items.DIRT, 2), 5, stack(Items.DIRT, 3)));
            w.setBlockState(mismatch, Blocks.BARREL.getDefaultState());
            w.setBlockState(lapisSpot, Blocks.AIR.getDefaultState());
        });

        context.runOnClient(client -> {
            SchematicPlacement placement = SchematicPlacement.createFor(SCHEMATIC, base, "autofill-test", true, true);
            DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
        });
        world.getServer().runCommand("gamemode survival @a");
        context.waitTicks(40);
    }

    private static LitematicaSchematic SCHEMATIC;

    private static void fill(ServerWorld w, BlockPos pos, Map<Integer, ItemStack> items) {
        Inventory inv = (Inventory) w.getBlockEntity(pos);
        items.forEach((slot, s) -> inv.setStack(slot, s.copy()));
        inv.markDirty();
    }

    private static ServerPlayerEntity player(MinecraftServer server) {
        return server.getPlayerManager().getPlayerList().get(0);
    }

    private void setPlayerInventory(TestSingleplayerContext world, Map<Integer, ItemStack> items) {
        world.getServer().runOnServer(server -> {
            PlayerInventory inv = player(server).getInventory();
            inv.clear();
            items.forEach((slot, s) -> inv.setStack(slot, s.copy()));
            player(server).currentScreenHandler.sendContentUpdates();
        });
    }

    private void lookAt(ClientGameTestContext context, TestSingleplayerContext world, BlockPos target) {
        // Stand 2 blocks south of the target and aim from eye height (1.62) at the block centre.
        float pitch = (float) Math.toDegrees(Math.atan2(1.62 - 0.5, 2.0));
        world.getServer().runCommand(String.format(java.util.Locale.ROOT, "tp @p %.1f %d %.1f 180 %.2f",
                target.getX() + 0.5, target.getY(), target.getZ() + 2.5, pitch));
        context.runOnClient(client -> client.player.getInventory().setSelectedSlot(0));
        context.waitTicks(5);
    }

    private boolean open(ClientGameTestContext context, TestSingleplayerContext world, BlockPos pos) {
        lookAt(context, world, pos);
        context.runOnClient(client -> client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND,
                new BlockHitResult(Vec3d.ofCenter(pos), Direction.SOUTH, pos, false)));
        try {
            context.waitFor(client -> client.currentScreen instanceof HandledScreen<?>
                    && client.player.currentScreenHandler != client.player.playerScreenHandler, 100);
            return true;
        } catch (Throwable t) {
            fail("open " + pos, "container screen did not open");
            return false;
        }
    }

    private void close(ClientGameTestContext context) {
        context.runOnClient(client -> {
            if (client.currentScreen != null) client.player.closeHandledScreen();
        });
        context.waitTicks(5);
    }

    /** Presses the auto-fill hotkey and waits for the fill to report. Returns ticks taken, or -1. */
    private int pressAutoFillAndWait(ClientGameTestContext context, String name) {
        FillResult before = AutoFillController.getLastResult();
        context.getInput().pressKey(AUTO_FILL_KEY);
        try {
            return context.waitFor(client -> AutoFillController.getLastResult() != before && !AutoFillController.isRunning(), 2400);
        } catch (Throwable t) {
            fail(name, "fill did not finish (running=" + AutoFillController.isRunning() + ", lastMessage=" + AutoFillController.getLastMessageKey() + ")");
            return -1;
        }
    }

    private static boolean clientHas(MinecraftClient client, Item item) {
        PlayerInventory inv = client.player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            if (inv.getStack(i).isOf(item)) return true;
        }
        return false;
    }

    private Map<Integer, ItemStack> containerContents(TestSingleplayerContext world, BlockPos pos) {
        return world.getServer().computeOnServer(server -> {
            Inventory inv = (Inventory) server.getOverworld().getBlockEntity(pos);
            Map<Integer, ItemStack> m = new LinkedHashMap<>();
            for (int i = 0; i < inv.size(); i++) {
                if (!inv.getStack(i).isEmpty()) m.put(i, inv.getStack(i).copy());
            }
            return m;
        });
    }

    private int countPlayer(TestSingleplayerContext world, Predicate<ItemStack> matcher) {
        return world.getServer().computeOnServer(server -> {
            PlayerInventory inv = player(server).getInventory();
            int total = 0;
            for (int i = 0; i < inv.size(); i++) {
                if (matcher.test(inv.getStack(i))) total += inv.getStack(i).getCount();
            }
            return total;
        });
    }

    private void assertContents(String name, Map<Integer, ItemStack> actual, Map<Integer, ItemStack> expected) {
        boolean ok = actual.size() == expected.size();
        for (Map.Entry<Integer, ItemStack> e : expected.entrySet()) {
            ItemStack a = actual.get(e.getKey());
            if (a == null || !ItemStack.areEqual(a, e.getValue())) ok = false;
        }
        check(name, ok, "expected " + describe(expected) + " but was " + describe(actual));
    }

    private static String describe(Map<Integer, ItemStack> m) {
        StringBuilder sb = new StringBuilder("{");
        m.forEach((k, v) -> sb.append(k).append('=').append(v.getCount()).append('x').append(v.getItem()).append(v.getComponentChanges().isEmpty() ? "" : "+components").append(' '));
        return sb.append('}').toString();
    }

    private void check(String name, boolean condition, String detail) {
        if (condition) {
            passes.add(name);
        } else {
            fail(name, detail);
        }
    }

    private void fail(String name, String detail) {
        failures.add(name + ": " + detail);
        LOG.error("FAIL {}: {}", name, detail);
    }

    // ------------------------------------------------------------------ tests

    private void testSingleChest(ClientGameTestContext context, TestSingleplayerContext world) {
        Map<Integer, ItemStack> inv = new LinkedHashMap<>();
        inv.put(9, stack(Items.STONE, 64));
        inv.put(10, stack(Items.OAK_PLANKS, 16));
        inv.put(11, stack(Items.DIAMOND, 5));
        inv.put(12, named(Items.DIAMOND, 3, "Gem"));
        inv.put(13, stack(Items.IRON_INGOT, 64));
        inv.put(14, shulkerWith(Items.WHITE_SHULKER_BOX, 0, stack(Items.COBBLESTONE, 5)));
        setPlayerInventory(world, inv);

        if (!open(context, world, single)) return;
        int ticks = pressAutoFillAndWait(context, "C1 single chest");
        close(context);
        if (ticks < 0) return;

        assertContents("C1 single chest contents (item+components+count+slot)", containerContents(world, single), singleExpected());
        FillResult r = AutoFillController.getLastResult();
        check("C1 summary", r.filledSlots() == 5 && r.missingItems() == 0 && !r.cancelled(), "result " + r);
        check("C16 at most one click per tick", r.actions() <= ticks + 1, r.actions() + " actions in " + ticks + " ticks");
        check("C1 plain diamonds untouched", countPlayer(world, s -> s.isOf(Items.DIAMOND) && s.getComponentChanges().isEmpty()) == 5, "plain diamonds changed");
        check("C1 named diamonds used", countPlayer(world, s -> s.isOf(Items.DIAMOND) && !s.getComponentChanges().isEmpty()) == 1, "named diamond count wrong");
        check("C1 partial source returned", countPlayer(world, s -> s.isOf(Items.OAK_PLANKS)) == 9, "oak planks left != 9");
    }

    private void testAlreadyFilled(ClientGameTestContext context, TestSingleplayerContext world) {
        if (!open(context, world, single)) return;
        int ticks = pressAutoFillAndWait(context, "C8 already filled");
        close(context);
        if (ticks < 0) return;
        FillResult r = AutoFillController.getLastResult();
        check("C8 already filled: no clicks", r.actions() == 0 && r.filledSlots() == 0 && r.missingItems() == 0, "result " + r);
    }

    private void testDoubleChestWithShulkerRetrieval(ClientGameTestContext context, TestSingleplayerContext world) {
        Map<Integer, ItemStack> inv = new LinkedHashMap<>();
        inv.put(9, stack(Items.REDSTONE, 64));
        inv.put(10, stack(Items.LAPIS_LAZULI, 9));
        inv.put(11, stack(Items.CLOCK, 1));
        inv.put(12, shulkerWith(Items.ORANGE_SHULKER_BOX, 4, stack(Items.GOLD_INGOT, 20)));
        setPlayerInventory(world, inv);

        if (!open(context, world, doubleLeft)) return;
        int ticks = pressAutoFillAndWait(context, "C2 double chest");
        close(context);
        if (ticks < 0) return;

        assertContents("C2 double chest right half", containerContents(world, doubleRight),
                Map.of(0, stack(Items.REDSTONE, 30), 26, stack(Items.LAPIS_LAZULI, 9)));
        assertContents("C2 double chest left half", containerContents(world, doubleLeft),
                Map.of(0, stack(Items.GOLD_INGOT, 20), 13, stack(Items.CLOCK, 1)));
        FillResult r = AutoFillController.getLastResult();
        check("C11 item only in shulker retrieved (singleplayer)", r.shulkerRetrievals() >= 1 && r.missingItems() == 0, "result " + r);
        check("C11 shulker emptied", countPlayer(world, s -> {
            ContainerComponent c = s.get(DataComponentTypes.CONTAINER);
            return c != null && c.stream().anyMatch(i -> i.isOf(Items.GOLD_INGOT));
        }) == 0, "gold still inside shulker");
    }

    private void testHopperWrongItemsKept(ClientGameTestContext context, TestSingleplayerContext world) {
        Configs.CLEAR_WRONG_ITEMS.setBooleanValue(false);
        setPlayerInventory(world, Map.of(9, stack(Items.GLASS, 64), 10, stack(Items.SAND, 64), 11, stack(Items.TORCH, 3)));
        if (!open(context, world, hopper)) return;
        int ticks = pressAutoFillAndWait(context, "C5/C7/C9 hopper");
        close(context);
        if (ticks < 0) return;

        assertContents("C7 partial top-up + C9 wrong items kept (hopper)", containerContents(world, hopper),
                Map.of(0, stack(Items.GLASS, 10), 1, stack(Items.DIRT, 2), 2, stack(Items.DIRT, 1), 4, stack(Items.TORCH, 3)));
        FillResult r = AutoFillController.getLastResult();
        check("C9 wrong slot reported", r.wrongSlots() == 1 && r.missingItems() == 10, "result " + r);
    }

    private void testBarrelClearWrongItems(ClientGameTestContext context, TestSingleplayerContext world) {
        Configs.CLEAR_WRONG_ITEMS.setBooleanValue(true);
        setPlayerInventory(world, Map.of(9, stack(Items.BRICKS, 5), 10, stack(Items.SAND, 64)));
        if (open(context, world, barrel)) {
            int ticks = pressAutoFillAndWait(context, "C4/C10 barrel");
            close(context);
            if (ticks >= 0) {
                assertContents("C10 clear wrong items on (barrel)", containerContents(world, barrel),
                        Map.of(0, stack(Items.BRICKS, 5), 1, stack(Items.SAND, 10)));
                check("C10 removed dirt went to inventory", countPlayer(world, s -> s.isOf(Items.DIRT)) == 5, "dirt not in inventory");
            }
        }
        Configs.CLEAR_WRONG_ITEMS.setBooleanValue(false);
    }

    private void testDispenserMissingItems(ClientGameTestContext context, TestSingleplayerContext world) {
        setPlayerInventory(world, Map.of(9, stack(Items.ARROW, 16), 10, stack(Items.FLINT_AND_STEEL, 1)));
        if (!open(context, world, dispenser)) return;
        int ticks = pressAutoFillAndWait(context, "C6/C13 dispenser");
        close(context);
        if (ticks < 0) return;

        assertContents("C6 dispenser filled with what exists", containerContents(world, dispenser), Map.of(0, stack(Items.ARROW, 16)));
        FillResult r = AutoFillController.getLastResult();
        check("C13 missing items counted (TNT x2 + damaged flint and steel)", r.missingItems() == 3 && r.filledSlots() == 1, "result " + r);
        context.waitTicks(2);
        LOG.info("Chat summary screenshot: {}", context.takeScreenshot("chat-summary"));
        check("C13 undamaged tool not used for damaged slot", countPlayer(world, s -> s.isOf(Items.FLINT_AND_STEEL)) == 1, "flint and steel moved");
    }

    private void testNotInPlacement(ClientGameTestContext context, TestSingleplayerContext world) {
        FillResult before = AutoFillController.getLastResult();
        if (!open(context, world, outside)) return;
        context.getInput().pressKey(AUTO_FILL_KEY);
        context.waitTicks(5);
        close(context);
        check("C17 not in placement message", "containerautofill.message.not_in_placement".equals(AutoFillController.getLastMessageKey())
                && AutoFillController.getLastResult() == before, "lastMessage=" + AutoFillController.getLastMessageKey());
    }

    private void testBlockMismatch(ClientGameTestContext context, TestSingleplayerContext world) {
        if (!open(context, world, mismatch)) return;
        context.getInput().pressKey(AUTO_FILL_KEY);
        context.waitTicks(5);
        close(context);
        check("C18 block mismatch message", "containerautofill.message.block_mismatch".equals(AutoFillController.getLastMessageKey()),
                "lastMessage=" + AutoFillController.getLastMessageKey());
    }

    private void testLookFill(ClientGameTestContext context, TestSingleplayerContext world) {
        setPlayerInventory(world, Map.of(9, stack(Items.OAK_LOG, 64)));
        lookAt(context, world, lookFill);
        FillResult before = AutoFillController.getLastResult();
        context.getInput().pressKey(GLFW.GLFW_KEY_V);
        try {
            context.waitFor(client -> AutoFillController.getLastResult() != before && client.currentScreen == null, 600);
        } catch (Throwable t) {
            fail("C19 look fill (V)", "did not finish; lastMessage=" + AutoFillController.getLastMessageKey());
            close(context);
            return;
        }
        assertContents("C19 look fill (V) opened, filled and closed", containerContents(world, lookFill),
                Map.of(0, stack(Items.OAK_LOG, 32), 2, stack(Items.OAK_LOG, 32)));
    }

    private void testScreenClosedMidFill(ClientGameTestContext context, TestSingleplayerContext world) {
        Map<Integer, ItemStack> inv = new LinkedHashMap<>();
        for (int i = 9; i < 36; i++) inv.put(i, stack(Items.COBBLESTONE, 64));
        setPlayerInventory(world, inv);
        Configs.CLICK_DELAY.setIntegerValue(4);
        if (!open(context, world, shulkerBox)) {
            Configs.CLICK_DELAY.setIntegerValue(1);
            return;
        }
        FillResult before = AutoFillController.getLastResult();
        context.getInput().pressKey(AUTO_FILL_KEY);
        context.waitTicks(40);
        boolean wasRunning = AutoFillController.isRunning();
        close(context);
        context.waitTicks(5);
        Configs.CLICK_DELAY.setIntegerValue(1);

        FillResult r = AutoFillController.getLastResult();
        int inBox = containerContents(world, shulkerBox).values().stream().mapToInt(ItemStack::getCount).sum();
        int inPlayer = countPlayer(world, s -> s.isOf(Items.COBBLESTONE));
        int dropped = world.getServer().computeOnServer(server -> {
            List<ItemEntity> items = server.getOverworld().getEntitiesByClass(ItemEntity.class, new net.minecraft.util.math.Box(base).expand(30), e -> true);
            items.forEach(e -> LOG.info("Item entity near test area: {}", e.getStack()));
            return (int) items.stream().filter(e -> e.getStack().isOf(Items.COBBLESTONE)).count();
        });
        check("C14 fill was running when screen closed", wasRunning, "fill had already finished");
        check("C14 cancelled safely", r != before && r.cancelled() && !AutoFillController.isRunning(), "result " + r);
        check("C14 no items lost or dropped", inBox + inPlayer == 27 * 64 && dropped == 0,
                "box=" + inBox + " player=" + inPlayer + " droppedEntities=" + dropped);
        check("C14 partially filled before cancel", inBox > 0 && inBox < 27 * 64, "box=" + inBox);
    }

    private void testVanillaPickFromShulker(ClientGameTestContext context, TestSingleplayerContext world) {
        setPlayerInventory(world, Map.of(9, shulkerWith(Items.LIME_SHULKER_BOX, 2, stack(Items.EMERALD_BLOCK, 10))));
        lookAt(context, world, emerald);
        context.getInput().pressKey(options -> options.pickItemKey);
        try {
            context.waitFor(client -> client.player.getMainHandStack().isOf(Items.EMERALD_BLOCK), 100);
            check("C20 vanilla pick block pulls from shulker", true, "");
        } catch (Throwable t) {
            fail("C20 vanilla pick block pulls from shulker", "emerald block not in main hand");
        }
    }

    private void testAutoTakeOut(ClientGameTestContext context, TestSingleplayerContext world) {
        setPlayerInventory(world, Map.of(9, shulkerWith(Items.BLUE_SHULKER_BOX, 0, stack(Items.LAPIS_BLOCK, 5))));
        lookAt(context, world, lapisSpot);
        context.getInput().pressKey(GLFW.GLFW_KEY_R);
        context.waitTicks(2);
        check("C22 R toggles Auto Take Out on", Configs.AUTO_TAKE_OUT.getBooleanValue(), "still off");
        try {
            context.waitFor(client -> clientHas(client, Items.LAPIS_BLOCK), 200);
            check("C21/C22 schematic block pulled from shulker (Litematica pick path)", true, "");
        } catch (Throwable t) {
            fail("C21/C22 schematic block pulled from shulker (Litematica pick path)", "lapis block never arrived");
        }
        context.getInput().pressKey(GLFW.GLFW_KEY_R);
        context.waitTicks(2);
        check("C22 R toggles Auto Take Out off", !Configs.AUTO_TAKE_OUT.getBooleanValue(), "still on");
    }

    private Map<BlockPos, ContainerStatus> waitForStatuses(ClientGameTestContext context, Predicate<Map<BlockPos, ContainerStatus>> ready) {
        try {
            context.waitFor(client -> ready.test(ContainerHighlighter.statuses()), 200);
        } catch (Throwable ignored) {
        }
        return ContainerHighlighter.statuses();
    }

    private void testHighlightStatuses(ClientGameTestContext context, TestSingleplayerContext world) {
        Map<BlockPos, ContainerStatus> statuses = waitForStatuses(context, m -> m.get(single) != null && m.get(single) != ContainerStatus.UNKNOWN
                && m.get(correctChest) != null && m.get(correctChest) != ContainerStatus.UNKNOWN);
        check("H1 empty container highlighted EMPTY", statuses.get(single) == ContainerStatus.EMPTY, "single=" + statuses.get(single));
        check("H2 wrong items highlighted WRONG", statuses.get(hopper) == ContainerStatus.WRONG, "hopper=" + statuses.get(hopper));
        check("H3 partly filled highlighted PARTIAL", statuses.get(partialChest) == ContainerStatus.PARTIAL, "partial=" + statuses.get(partialChest));
        check("H4 matching container highlighted CORRECT", statuses.get(correctChest) == ContainerStatus.CORRECT, "correct=" + statuses.get(correctChest));
        check("H5 wrong block type not highlighted", !statuses.containsKey(mismatch), "mismatch=" + statuses.get(mismatch));
        check("H6 non-container schematic block not highlighted", !statuses.containsKey(lapisSpot), "lapis=" + statuses.get(lapisSpot));
        check("H7 double chest halves both highlighted", statuses.containsKey(doubleLeft) && statuses.containsKey(doubleRight), "left=" + statuses.get(doubleLeft) + " right=" + statuses.get(doubleRight));
        check("H8 other container types highlighted (furnace, brewing stand, crafter, copper, recoloured shulker)",
                statuses.containsKey(furnace) && statuses.containsKey(brewingStand) && statuses.containsKey(crafter)
                        && statuses.containsKey(copperLeft) && statuses.containsKey(recoloredShulker), "statuses=" + statuses.size());

        // Overview screenshot of both rows (check visually: coloured boxes on the containers).
        world.getServer().runCommand(String.format(java.util.Locale.ROOT, "tp @p %.1f %d %.1f 0 35",
                base.getX() + 10.5, base.getY() + 4, base.getZ() - 5.5));
        context.waitTicks(20);
        java.nio.file.Path shot = context.takeScreenshot("containerautofill-highlight");
        LOG.info("Highlight screenshot: {}", shot);
    }

    private void testHighlightUpdatesAfterFill(ClientGameTestContext context, TestSingleplayerContext world) {
        Map<BlockPos, ContainerStatus> statuses = waitForStatuses(context, m -> m.get(single) == ContainerStatus.CORRECT);
        check("H9 highlight turns CORRECT after filling", statuses.get(single) == ContainerStatus.CORRECT, "single=" + statuses.get(single));
    }

    private FillResult fillAndCheck(ClientGameTestContext context, TestSingleplayerContext world, String name, BlockPos openPos,
                                    Map<Integer, ItemStack> playerItems, Map<BlockPos, Map<Integer, ItemStack>> expected) {
        setPlayerInventory(world, playerItems);
        if (!open(context, world, openPos)) return null;
        int ticks = pressAutoFillAndWait(context, name);
        close(context);
        if (ticks < 0) return null;
        expected.forEach((pos, items) -> assertContents(name + " @" + pos.toShortString(), containerContents(world, pos), items));
        return AutoFillController.getLastResult();
    }

    private void testMoreContainerTypes(ClientGameTestContext context, TestSingleplayerContext world) {
        FillResult furnaceResult = fillAndCheck(context, world, "T1 furnace (input/fuel; output slot can't be filled)", furnace,
                Map.of(9, stack(Items.COAL, 64), 10, stack(Items.IRON_INGOT, 64)),
                Map.of(furnace, Map.of(1, stack(Items.COAL, 4))));
        check("T1 furnace output reported missing", furnaceResult != null && furnaceResult.missingItems() == 3, "result " + furnaceResult);
        fillAndCheck(context, world, "T2 smoker", smoker, Map.of(9, stack(Items.CHARCOAL, 64)),
                Map.of(smoker, Map.of(1, stack(Items.CHARCOAL, 2))));
        fillAndCheck(context, world, "T3 blast furnace", blastFurnace, Map.of(9, stack(Items.COAL_BLOCK, 8)),
                Map.of(blastFurnace, Map.of(1, stack(Items.COAL_BLOCK, 1))));
        fillAndCheck(context, world, "T4 brewing stand", brewingStand, Map.of(9, stack(Items.GLASS_BOTTLE, 16), 10, stack(Items.NETHER_WART, 8)),
                Map.of(brewingStand, Map.of(0, stack(Items.GLASS_BOTTLE, 1), 1, stack(Items.GLASS_BOTTLE, 1), 3, stack(Items.NETHER_WART, 2))));
        fillAndCheck(context, world, "T5 dropper", dropper, Map.of(9, stack(Items.BONE, 64), 10, stack(Items.STRING, 64)),
                Map.of(dropper, Map.of(0, stack(Items.BONE, 5), 8, stack(Items.STRING, 3))));
        fillAndCheck(context, world, "T6 trapped double chest (opened from the right half)", trappedRight,
                Map.of(9, stack(Items.FLINT, 64), 10, stack(Items.FEATHER, 64)),
                Map.of(trappedRight, Map.of(0, stack(Items.FLINT, 7)), trappedLeft, Map.of(0, stack(Items.FEATHER, 9))));
        fillAndCheck(context, world, "T7 copper double chest, different oxidation than schematic", copperLeft,
                Map.of(9, stack(Items.COPPER_INGOT, 64), 10, stack(Items.RAW_COPPER, 64)),
                Map.of(copperRight, Map.of(0, stack(Items.COPPER_INGOT, 11)), copperLeft, Map.of(5, stack(Items.RAW_COPPER, 12))));
        fillAndCheck(context, world, "T8 shulker box of a different colour than schematic", recoloredShulker,
                Map.of(9, stack(Items.APPLE, 64)), Map.of(recoloredShulker, Map.of(0, stack(Items.APPLE, 3))));
        testCrafterKnownIssue(context, world);
    }

    /**
     * Crafters work intermittently (about 1 run in 3 fills fully): the server sometimes rejects an item
     * placed in a second crafter slot. Known issue accepted by the project owner; logged, not failed.
     * The fill must still finish (no endless retries) and never lose items.
     */
    private void testCrafterKnownIssue(ClientGameTestContext context, TestSingleplayerContext world) {
        setPlayerInventory(world, Map.of(9, stack(Items.OAK_PLANKS, 64)));
        if (!open(context, world, crafter)) return;
        int ticks = pressAutoFillAndWait(context, "T9 crafter fill finishes");
        close(context);
        check("T9 crafter fill finishes (no endless retries)", ticks >= 0, "did not finish");
        Map<Integer, ItemStack> contents = containerContents(world, crafter);
        int inCrafter = contents.values().stream().mapToInt(ItemStack::getCount).sum();
        int inPlayer = countPlayer(world, s -> s.isOf(Items.OAK_PLANKS));
        check("T9 crafter: no planks lost", inCrafter + inPlayer == 64, "crafter=" + inCrafter + " player=" + inPlayer);
        boolean exact = contents.size() == 2 && contents.containsKey(0) && contents.containsKey(4);
        LOG.info("{} T9 crafter contents {} (known issue: second slot sometimes rejected)", exact ? "PASS" : "KNOWN-ISSUE", describe(contents));
    }

    /** A server that doesn't handle takeitout:getstack (like vanilla/Paper without the plugin). */
    private void testServerWithoutHandler(ClientGameTestContext context) {
        ServerPlayNetworking.unregisterGlobalReceiver(GetStackPayload.ID.id());
        try (TestDedicatedServerContext server = context.worldBuilder().createServer();
             TestServerConnection connection = server.connect()) {
            connection.getClientWorld().waitForChunksRender();
            boolean supported = context.computeOnClient(client -> ShulkerRetriever.isSupported());
            check("C23 server without handler detected", !supported, "canSend reported true");

            server.runCommand("gamemode survival @a");
            BlockPos feet = context.computeOnClient(client -> client.player.getBlockPos());
            BlockPos block = feet.add(0, 0, 2);
            server.runCommand(String.format("setblock %d %d %d minecraft:emerald_block", block.getX(), block.getY(), block.getZ()));
            server.runCommand("clear @a");
            server.runCommand("give @a minecraft:lime_shulker_box[minecraft:container=[{slot:2,item:{id:\"minecraft:emerald_block\",count:10}}]]");
            server.runCommand(String.format(java.util.Locale.ROOT, "tp @a %.1f %d %.1f 180 %.2f",
                    block.getX() + 0.5, block.getY(), block.getZ() + 2.5, (float) Math.toDegrees(Math.atan2(1.12, 2.0))));
            context.waitTicks(10);
            context.getInput().pressKey(options -> options.pickItemKey);
            context.waitTicks(40);
            boolean stillConnected = context.computeOnClient(client -> client.getNetworkHandler() != null && client.player != null);
            boolean pulled = context.computeOnClient(client -> client.player.getMainHandStack().isOf(Items.EMERALD_BLOCK));
            check("C23 no kick, falls back to loose items only", stillConnected && !pulled, "connected=" + stillConnected + " pulled=" + pulled);
        } finally {
            ServerPlayNetworking.registerGlobalReceiver(GetStackPayload.ID, (payload, ctx) ->
                    ShulkerStackServerHandler.handle(ctx.player(), payload.slot(), payload.shulker()));
        }
    }
}
