/*
 * Cytra Container
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.test;

import dev.steelaspect.containerautofill.config.Configs;
import dev.steelaspect.containerautofill.filler.InstantFill;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.util.math.Direction;
import dev.steelaspect.containerautofill.network.SharedGroupPayloads;
import dev.steelaspect.containerautofill.storage.SharedGroups;
import dev.steelaspect.containerautofill.storage.StorageActions;
import dev.steelaspect.containerautofill.storage.StorageContents;
import dev.steelaspect.containerautofill.storage.StorageRetriever;
import dev.steelaspect.containerautofill.storage.StorageScreen;
import dev.steelaspect.containerautofill.storage.StorageStore;
import fi.dy.masa.malilib.event.InputEventHandler;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/** Linked storage end to end: hotkeys, the menu, remote take, pick block, dump, Look At and groups. */
public class StorageGameTest implements FabricClientGameTest {
    private static final Logger LOG = LoggerFactory.getLogger("ContainerAutoFillTest");

    private final List<String> failures = new ArrayList<>();
    private int passes;
    private BlockPos chestA, chestB, dumpChest, farChest, goldBlock, fillLeft, fillRight, fillHopper, area1, area2, areaFar, area3;
    private LitematicaSchematic schematic;
    private Identifier overworld;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getClientWorld().waitForChunksRender();
            // After joining: MaLiLib reloads its config files when a world loads.
            context.runOnClient(client -> {
                Configs.BOX_SELECT_CORNER.setValueFromString("J");
                Configs.MARK_DUMP_CONTAINER.setValueFromString("U");
                Configs.DUMP_TO_CONTAINERS.setValueFromString("N");
                Configs.CLICK_DELAY.setIntegerValue(1);
                Configs.SHULKER_PICK_BLOCK.setBooleanValue(true);
                Configs.USE_LINKED_CONTAINERS.setBooleanValue(true);
                InputEventHandler.getKeybindManager().updateUsedKeys();
            });
            setup(context, world);
            testLinkingHotkeys(context, world);
            testMenuScreenshots(context, world);
            testRemoteTake(context, world);
            testPickBlockFromStorage(context, world);
            testPullSpeed(context, world);
            testHotbarRefill(context, world);
            testRestock(context, world);
            testDump(context, world);
            testInstantFill(context, world);
            testAreaFillAndServerStatus(context, world);
            testCreativeFill(context, world);
            testShulkersInLinkedChests(context, world);
            testGetMaterials(context, world);
            testLookAtAndGroups(context);
        }

        LOG.info("==== Storage game test summary: {} passed, {} failed ====", passes, failures.size());
        failures.forEach(f -> LOG.error("FAIL {}", f));
        if (!failures.isEmpty()) throw new AssertionError(String.join(" | ", failures));
    }

    private void check(String name, boolean ok, String detail) {
        if (ok) {
            passes++;
            LOG.info("PASS {}", name);
        } else {
            failures.add(name + ": " + detail);
            LOG.error("FAIL {}: {}", name, detail);
        }
    }

    private static ServerPlayerEntity player(MinecraftServer server) {
        return server.getPlayerManager().getPlayerList().get(0);
    }

    private static ItemStack stack(Item item, int count) {
        return new ItemStack(item, count);
    }

    private static void fill(ServerWorld w, BlockPos pos, Map<Integer, ItemStack> items) {
        Inventory inv = (Inventory) w.getBlockEntity(pos);
        items.forEach((slot, s) -> inv.setStack(slot, s.copy()));
        inv.markDirty();
    }

    private void setup(ClientGameTestContext context, TestSingleplayerContext world) {
        BlockPos feet = world.getServer().computeOnServer(server -> player(server).getBlockPos());
        BlockPos base = feet.add(3, 0, 3);
        chestA = base;
        chestB = base.add(2, 0, 0);
        dumpChest = base.add(4, 0, 0);
        goldBlock = base.add(0, 0, 5);
        farChest = base.add(160, 0, 0);
        fillLeft = base.add(0, 0, 9);
        fillRight = base.add(1, 0, 9);
        fillHopper = base.add(3, 0, 9);
        area1 = base.add(5, 0, 9);
        area2 = base.add(7, 0, 9);
        areaFar = base.add(20, 0, 9);
        area3 = base.add(13, 0, 9);
        world.getServer().runCommand(String.format(Locale.ROOT, "forceload add %d %d", farChest.getX(), farChest.getZ()));
        world.getServer().runOnServer(server -> {
            ServerWorld w = server.getOverworld();
            for (BlockPos p : List.of(chestA, chestB, dumpChest, farChest)) w.setBlockState(p, Blocks.CHEST.getDefaultState());
            w.setBlockState(goldBlock, Blocks.GOLD_BLOCK.getDefaultState());
            ItemStack gem = stack(Items.DIAMOND, 2);
            gem.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Gem"));
            fill(w, chestA, Map.of(0, stack(Items.STONE, 64), 1, stack(Items.STONE, 64), 2, stack(Items.IRON_INGOT, 30), 3, gem));
            fill(w, chestB, Map.of(0, stack(Items.GOLD_INGOT, 20), 5, stack(Items.GOLD_BLOCK, 5)));
            fill(w, farChest, Map.of(0, stack(Items.EMERALD, 16)));
            player(server).getInventory().clear();

            // Schematic for instant fill: a double chest and a hopper, saved full, then emptied.
            BlockState north = Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, Direction.NORTH);
            w.setBlockState(fillLeft, north.with(ChestBlock.CHEST_TYPE, ChestType.LEFT));
            w.setBlockState(fillRight, north.with(ChestBlock.CHEST_TYPE, ChestType.RIGHT));
            w.setBlockState(fillHopper, Blocks.HOPPER.getDefaultState());
            ItemStack fillGem = stack(Items.DIAMOND, 1);
            fillGem.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Gem"));
            fill(w, fillRight, Map.of(0, stack(Items.STONE, 64), 1, stack(Items.IRON_INGOT, 30)));
            fill(w, fillLeft, Map.of(0, stack(Items.EMERALD, 5), 3, stack(Items.GOLD_INGOT, 4), 7, fillGem));
            fill(w, fillHopper, Map.of(0, stack(Items.GLASS, 3), 2, stack(Items.TNT, 2)));
            for (BlockPos p : List.of(area1, area2, areaFar, area3)) w.setBlockState(p, Blocks.CHEST.getDefaultState());
            ItemStack namedGem = stack(Items.DIAMOND, 1);
            namedGem.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Gem"));
            fill(w, area3, Map.of(0, stack(Items.BRICKS, 20), 13, namedGem));
            fill(w, area1, Map.of(0, stack(Items.OAK_LOG, 10)));
            fill(w, area2, Map.of(4, stack(Items.BRICKS, 7)));
            fill(w, areaFar, Map.of(0, stack(Items.OAK_LOG, 1)));
            AreaSelection area = new AreaSelection();
            area.addSubRegionBox(new Box(fillLeft, areaFar, "fill"), false);
            area.setExplicitOrigin(fillLeft);
            schematic = LitematicaSchematic.createFromWorld(w, area, new LitematicaSchematic.SchematicSaveInfo(false, true), "steelaspect", msg -> {});
            for (BlockPos p : List.of(fillLeft, fillRight, fillHopper, area1, area2, areaFar, area3)) ((Inventory) w.getBlockEntity(p)).clear();
            fill(w, fillHopper, Map.of(0, stack(Items.DIRT, 1)));
        });
        context.runOnClient(client -> DataManager.getSchematicPlacementManager()
                .addSchematicPlacement(SchematicPlacement.createFor(schematic, fillLeft, "instant-fill-test", true, true), false));
        world.getServer().runCommand("gamemode survival @a");
        world.getServer().runCommand("gamerule announceAdvancements false");
        world.getServer().runCommand("advancement grant @a everything");
        overworld = context.computeOnClient(client -> client.world.getRegistryKey().getValue());
        context.waitTicks(10);
    }

    private void lookAt(ClientGameTestContext context, TestSingleplayerContext world, BlockPos target) {
        float pitch = (float) Math.toDegrees(Math.atan2(1.62 - 0.5, 2.0));
        world.getServer().runCommand(String.format(Locale.ROOT, "tp @p %.1f %d %.1f 180 %.2f",
                target.getX() + 0.5, target.getY(), target.getZ() + 2.5, pitch));
        context.runOnClient(client -> client.player.getInventory().setSelectedSlot(0));
        context.waitTicks(5);
    }

    private void testLinkingHotkeys(ClientGameTestContext context, TestSingleplayerContext world) {
        lookAt(context, world, chestA);
        context.getInput().pressKey(GLFW.GLFW_KEY_H);
        context.waitTicks(2);
        check("S1 H links the looked-at container", StorageStore.find(overworld, chestA) != null, "not linked");

        String groupBefore = StorageStore.activeGroup().name;
        int groupsBefore = StorageStore.groups().size();
        lookAt(context, world, chestB);
        context.getInput().pressKey(GLFW.GLFW_KEY_J);
        lookAt(context, world, dumpChest);
        context.getInput().pressKey(GLFW.GLFW_KEY_J);
        context.waitTicks(2);
        check("S2 box select links every container in the box", StorageStore.find(overworld, chestB) != null
                && StorageStore.find(overworld, dumpChest) != null, "linked=" + StorageStore.linkedCount());
        check("S2 box select adds to the selected group (earlier links kept, no new group)",
                StorageStore.activeGroup().name.equals(groupBefore) && StorageStore.groups().size() == groupsBefore
                        && StorageStore.find(overworld, chestA) != null,
                "active=" + StorageStore.activeGroup().name + " groups=" + StorageStore.groups().size());

        context.getInput().pressKey(GLFW.GLFW_KEY_U);
        context.waitTicks(2);
        StorageStore.Entry dump = StorageStore.find(overworld, dumpChest);
        check("S3 U marks a dump container", dump != null && dump.dump, "dump flag not set");

        context.runOnClient(client -> StorageStore.link(overworld, farChest));
        context.runOnClient(client -> StorageActions.refreshAll());
        try {
            context.waitFor(client -> {
                StorageContents.Snapshot s = StorageContents.get(overworld, farChest);
                return s != null && s.available();
            }, 100);
            check("S4 far (160 blocks, force-loaded) container contents read", true, "");
        } catch (Throwable t) {
            check("S4 far (160 blocks, force-loaded) container contents read", false, "no contents from server");
        }
        check("S5 links saved to disk", Files.isRegularFile(FabricLoader.getInstance().getConfigDir().resolve("containerautofill/storage")
                .resolve(StorageStore.worldKey().replaceAll("[^A-Za-z0-9._-]", "_") + ".json")), "no storage file");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setTab(String name) {
        try {
            Field field = StorageScreen.class.getDeclaredField("tab");
            field.setAccessible(true);
            field.set(null, Enum.valueOf((Class<? extends Enum>) field.getType(), name));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private void testMenuScreenshots(ClientGameTestContext context, TestSingleplayerContext world) {
        lookAt(context, world, chestB);
        context.getInput().pressKey(GLFW.GLFW_KEY_Y);
        try {
            context.waitFor(client -> client.currentScreen instanceof StorageScreen, 40);
            check("S6 Y opens the storage menu", true, "");
        } catch (Throwable t) {
            check("S6 Y opens the storage menu", false, "screen=" + context.computeOnClient(c -> String.valueOf(c.currentScreen)));
        }
        context.waitTicks(30);
        for (String tab : new String[]{"ITEMS", "CONTAINERS", "GROUPS"}) {
            context.runOnClient(client -> {
                setTab(tab);
                client.setScreen(new StorageScreen(null));
            });
            context.waitTicks(25);
            LOG.info("Storage screenshot: {}", context.takeScreenshot("storage-" + tab.toLowerCase(Locale.ROOT)));
        }
        context.runOnClient(client -> {
            setTab("ITEMS");
            client.setScreen(null);
        });
        context.waitTicks(5);
        LOG.info("Storage screenshot: {}", context.takeScreenshot("storage-outlines"));
    }

    private int serverCount(TestSingleplayerContext world, Predicate<ItemStack> matcher) {
        return world.getServer().computeOnServer(server -> {
            PlayerInventory inv = player(server).getInventory();
            int total = 0;
            for (int i = 0; i < inv.size(); i++) if (matcher.test(inv.getStack(i))) total += inv.getStack(i).getCount();
            return total;
        });
    }

    private int containerCount(TestSingleplayerContext world, BlockPos pos, Item item) {
        return world.getServer().computeOnServer(server -> {
            Inventory inv = (Inventory) server.getOverworld().getBlockEntity(pos);
            int total = 0;
            for (int i = 0; i < inv.size(); i++) if (inv.getStack(i).isOf(item)) total += inv.getStack(i).getCount();
            return total;
        });
    }

    private static boolean clientHas(MinecraftClient client, Item item, int atLeast) {
        PlayerInventory inv = client.player.getInventory();
        int total = 0;
        for (int i = 0; i < inv.size(); i++) if (inv.getStack(i).isOf(item)) total += inv.getStack(i).getCount();
        return total >= atLeast;
    }

    private void testRemoteTake(ClientGameTestContext context, TestSingleplayerContext world) {
        context.runOnClient(client -> StorageActions.take(client, stack(Items.EMERALD, 1), 10));
        try {
            context.waitFor(client -> clientHas(client, Items.EMERALD, 10), 100);
        } catch (Throwable ignored) {
        }
        check("S7 take 10 emeralds from a container 160 blocks away", serverCount(world, s -> s.isOf(Items.EMERALD)) == 10
                && containerCount(world, farChest, Items.EMERALD) == 6,
                "player=" + serverCount(world, s -> s.isOf(Items.EMERALD)) + " chest=" + containerCount(world, farChest, Items.EMERALD));
    }

    private void testPickBlockFromStorage(ClientGameTestContext context, TestSingleplayerContext world) {
        context.runOnClient(client -> StorageActions.refreshAll());
        context.waitTicks(10);
        lookAt(context, world, goldBlock);
        context.getInput().pressKey(options -> options.pickItemKey);
        try {
            context.waitFor(client -> client.player.getMainHandStack().isOf(Items.GOLD_BLOCK), 100);
            check("S8 pick block pulls the block from a linked container into the hand", true, "");
        } catch (Throwable t) {
            check("S8 pick block pulls the block from a linked container into the hand", false, "main hand empty");
        }

        // S8b: full inventory: the hand stack goes into the chest slot the gold block came from.
        Map<Integer, ItemStack> full = new java.util.HashMap<>();
        for (int i = 0; i < 36; i++) full.put(i, stack(Items.COBBLESTONE, 64));
        world.getServer().runOnServer(server -> fill(server.getOverworld(), chestB, Map.of(5, stack(Items.GOLD_BLOCK, 5))));
        setPlayerInventory(world, full);
        context.runOnClient(client -> StorageActions.refreshAll());
        lookAt(context, world, goldBlock);
        context.waitFor(client -> client.player.getInventory().getStack(35).isOf(Items.COBBLESTONE), 40);
        context.getInput().pressKey(options -> options.pickItemKey);
        try {
            context.waitFor(client -> client.player.getMainHandStack().isOf(Items.GOLD_BLOCK), 100);
        } catch (Throwable ignored) {
        }
        check("S8b full inventory: pick block swaps the hand item into the linked chest",
                context.computeOnClient(client -> client.player.getMainHandStack().isOf(Items.GOLD_BLOCK))
                        && containerCount(world, chestB, Items.COBBLESTONE) == 64,
                "hand=" + context.computeOnClient(client -> client.player.getMainHandStack().toString()) + " chestB cobble=" + containerCount(world, chestB, Items.COBBLESTONE));
        world.getServer().runOnServer(server -> {
            Inventory inv = (Inventory) server.getOverworld().getBlockEntity(chestB);
            for (int i = 0; i < inv.size(); i++) if (inv.getStack(i).isOf(Items.COBBLESTONE)) inv.setStack(i, ItemStack.EMPTY);
        });
        setPlayerInventory(world, Map.of());
    }

    /** P1-P4: how quickly single-item pulls and easy place with single-item mode run (ticks are logged). */
    private void testPullSpeed(ClientGameTestContext context, TestSingleplayerContext world) {
        Predicate<ItemStack> isStone = s -> s.isOf(Items.STONE);
        context.runOnClient(client -> {
            Configs.SINGLE_ITEM_MODE.setBooleanValue(true);
            StorageActions.refreshAll();
        });
        context.waitTicks(10);

        // P1: ten single-item pulls into the hand, one after another.
        int total = 0, pulls = 0;
        for (int i = 0; i < 10; i++) {
            world.getServer().runOnServer(server -> player(server).getInventory().clear());
            context.waitFor(client -> client.player.getInventory().isEmpty(), 40);
            context.runOnClient(client -> dev.steelaspect.containerautofill.storage.StorageRetriever.request(client, isStone, 1, true));
            try {
                total += context.waitFor(client -> client.player.getMainHandStack().isOf(Items.STONE), 60);
                pulls++;
            } catch (Throwable ignored) {
            }
        }
        double average = pulls == 0 ? 99 : (double) total / pulls;
        LOG.info("SPEED P1 single-item pull: {} of 10 arrived, average {} ticks", pulls, String.format(Locale.ROOT, "%.2f", average));
        check("P1 single-item pulls arrive within 3 ticks on average", pulls == 10 && average <= 3.0, pulls + " pulls, avg " + average);

        // P2: the cache says a slot holds sponge but the container doesn't: the request must not hang.
        context.waitTicks(10); // let pending refreshes of chestA land first, they would replace the fake entry
        boolean sent = context.computeOnClient(client -> {
            StorageContents.get(overworld, chestA).items().put(20, stack(Items.SPONGE, 1));
            return dev.steelaspect.containerautofill.storage.StorageRetriever.request(client, s -> s.isOf(Items.SPONGE), 1, true)
                    && dev.steelaspect.containerautofill.storage.StorageRetriever.isWaiting();
        });
        int missTicks;
        if (!sent) {
            missTicks = 999;
        } else try {
            missTicks = context.waitFor(client -> !dev.steelaspect.containerautofill.storage.StorageRetriever.isWaiting(), 80);
        } catch (Throwable t) {
            missTicks = 999;
        }
        LOG.info("SPEED P2 stale-cache miss released after {} ticks", missTicks);
        check("P2 a miss (stale cache) frees the request within 5 ticks", missTicks <= 5, missTicks + " ticks");

        // P3/P4: easy place with single-item mode, three stone blocks in a row taken one at a time from storage.
        BlockPos stand = chestA.add(-8, 0, 0);
        List<BlockPos> row = List.of(stand.add(0, 1, -3), stand.add(0, 1, -2), stand.add(0, 1, -1));
        LitematicaSchematic[] rowSchematic = new LitematicaSchematic[1];
        world.getServer().runOnServer(server -> {
            ServerWorld w = server.getOverworld();
            for (BlockPos p : row) w.setBlockState(p, Blocks.STONE.getDefaultState());
            AreaSelection area = new AreaSelection();
            area.addSubRegionBox(new Box(row.get(0), row.get(2), "row"), false);
            area.setExplicitOrigin(row.get(0));
            rowSchematic[0] = LitematicaSchematic.createFromWorld(w, area, new LitematicaSchematic.SchematicSaveInfo(false, true), "steelaspect", msg -> {});
            for (BlockPos p : row) w.setBlockState(p, Blocks.AIR.getDefaultState());
            player(server).getInventory().clear();
        });
        world.getServer().runCommand(String.format(Locale.ROOT, "tp @p %.1f %d %.1f 180 0", stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5));
        context.runOnClient(client -> {
            DataManager.getSchematicPlacementManager()
                    .addSchematicPlacement(SchematicPlacement.createFor(rowSchematic[0], row.get(0), "speed-row", true, true), false);
            fi.dy.masa.litematica.config.Configs.Generic.EASY_PLACE_MODE.setBooleanValue(true);
            fi.dy.masa.litematica.config.Configs.Generic.EASY_PLACE_FIRST.setBooleanValue(false);
            Configs.DEBUG_LOGGING.setBooleanValue(true);
            client.player.getInventory().setSelectedSlot(0);
            StorageActions.refreshAll();
        });
        context.waitTicks(20);
        // P3/P4: Single-item Buffer 1 (strictly one item per pull). P5: buffer 3 (pulls ahead).
        for (int buffer : new int[]{1, 3}) {
            int size = buffer;
            world.getServer().runOnServer(server -> {
                for (BlockPos p : row) server.getOverworld().setBlockState(p, Blocks.AIR.getDefaultState());
                player(server).getInventory().clear();
            });
            context.runOnClient(client -> {
                Configs.SINGLE_ITEM_BUFFER.setIntegerValue(size);
                StorageActions.refreshAll();
            });
            context.waitTicks(45); // Litematica won't re-place a position within 2 s of placing it
            int before = containerCount(world, chestA, Items.STONE);
            context.getInput().holdMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            int placeTicks;
            try {
                placeTicks = context.waitFor(client -> row.stream().allMatch(p -> client.world.getBlockState(p).isOf(Blocks.STONE)), 200);
            } catch (Throwable t) {
                placeTicks = -1;
            }
            context.getInput().releaseMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            context.waitTicks(10);
            LOG.info("P3 row after: {}", (Object) context.<String, RuntimeException>computeOnClient(client -> row.stream()
                    .map(p -> client.world.getBlockState(p).getBlock().getName().getString()).toList().toString()));
            int taken = before - containerCount(world, chestA, Items.STONE);
            int left = serverCount(world, isStone);
            LOG.info("SPEED P3 easy place, single-item buffer {}: 3 blocks placed in {} ticks, {} stone taken, {} left over", buffer, placeTicks, taken, left);
            if (buffer == 1) {
                check("P3 easy place with single-item mode places all 3 blocks", placeTicks >= 0, "not all placed");
                check("P4 buffer 1 takes exactly 1 item per block", taken == 3 && left == 0, "taken=" + taken + " left=" + left);
            } else {
                check("P5 buffer 3 places all 3 blocks, at most one top-up left over, none lost",
                        placeTicks >= 0 && left <= buffer && taken == 3 + left, "ticks=" + placeTicks + " taken=" + taken + " left=" + left);
            }
        }
        context.runOnClient(client -> {
            fi.dy.masa.litematica.config.Configs.Generic.EASY_PLACE_MODE.setBooleanValue(false);
            fi.dy.masa.litematica.config.Configs.Generic.EASY_PLACE_FIRST.setBooleanValue(true);
            Configs.SINGLE_ITEM_MODE.setBooleanValue(false);
            Configs.SINGLE_ITEM_BUFFER.setIntegerValue(3);
            Configs.DEBUG_LOGGING.setBooleanValue(false);
            var manager = DataManager.getSchematicPlacementManager();
            for (SchematicPlacement placement : new ArrayList<>(manager.getAllSchematicsPlacements())) {
                if ("speed-row".equals(placement.getName())) manager.removeSchematicPlacement(placement);
            }
        });
    }

    /** R1-R5: hotbar refill after the last item in the hand is placed. */
    private void testHotbarRefill(ClientGameTestContext context, TestSingleplayerContext world) {
        BlockPos target = chestA.add(-14, 0, 6);
        world.getServer().runOnServer(server -> server.getOverworld().setBlockState(target, Blocks.OBSIDIAN.getDefaultState()));

        refillCase(context, world, target, "R1 refill from the rest of the inventory (10 cobblestone from slot 20)",
                Map.of(0, stack(Items.COBBLESTONE, 1), 20, stack(Items.COBBLESTONE, 10)), true,
                c -> c.player.getInventory().getStack(0).isOf(Items.COBBLESTONE) && c.player.getInventory().getStack(0).getCount() == 10
                        && c.player.getInventory().getStack(20).isEmpty());

        ItemStack shulker = new ItemStack(Items.LIME_SHULKER_BOX);
        net.minecraft.util.collection.DefaultedList<ItemStack> inner = net.minecraft.util.collection.DefaultedList.ofSize(27, ItemStack.EMPTY);
        inner.set(4, stack(Items.DIRT, 30));
        shulker.set(DataComponentTypes.CONTAINER, net.minecraft.component.type.ContainerComponent.fromStacks(inner));
        refillCase(context, world, target, "R2 refill from a shulker box in the inventory (30 dirt)",
                Map.of(0, stack(Items.DIRT, 1), 10, shulker), true,
                c -> c.player.getInventory().getStack(0).isOf(Items.DIRT) && c.player.getInventory().getStack(0).getCount() == 30);

        refillCase(context, world, target, "R3 refill from linked storage (stone)",
                Map.of(0, stack(Items.STONE, 1)), true,
                c -> c.player.getInventory().getStack(0).isOf(Items.STONE) && c.player.getInventory().getStack(0).getCount() > 1);

        context.runOnClient(client -> Configs.HOTBAR_REFILL.setBooleanValue(false));
        refillCase(context, world, target, "R4 turned off: slot stays empty",
                Map.of(0, stack(Items.COBBLESTONE, 1), 20, stack(Items.COBBLESTONE, 10)), false,
                c -> c.player.getInventory().getStack(0).isEmpty() && c.player.getInventory().getStack(20).getCount() == 10);
        context.runOnClient(client -> Configs.HOTBAR_REFILL.setBooleanValue(true));

        context.runOnClient(client -> Configs.TAKEITOUT_ENABLED.setBooleanValue(false));
        refillCase(context, world, target, "R6 TakeItOut off: slot stays empty",
                Map.of(0, stack(Items.COBBLESTONE, 1), 20, stack(Items.COBBLESTONE, 10)), false,
                c -> c.player.getInventory().getStack(0).isEmpty() && c.player.getInventory().getStack(20).getCount() == 10);
        context.runOnClient(client -> Configs.TAKEITOUT_ENABLED.setBooleanValue(true));

        // R5: dropping the last item (Q) is not using it up, so nothing is refilled.
        setPlayerInventory(world, Map.of(0, stack(Items.OAK_PLANKS, 1), 20, stack(Items.OAK_PLANKS, 10)));
        context.waitFor(client -> client.player.getInventory().getStack(0).isOf(Items.OAK_PLANKS)
                && client.player.getInventory().getStack(20).isOf(Items.OAK_PLANKS), 40);
        context.runOnClient(client -> client.player.getInventory().setSelectedSlot(0));
        context.getInput().pressKey(options -> options.dropKey);
        context.waitTicks(10);
        boolean dropped = context.computeOnClient(client -> client.player.getInventory().getStack(0).isEmpty()
                && client.player.getInventory().getStack(20).isOf(Items.OAK_PLANKS) && client.player.getInventory().getStack(20).getCount() == 10);
        check("R5 dropping the last item does not refill", dropped, "slot 0 refilled after a drop: " + context.computeOnClient(client ->
                client.player.getInventory().getStack(0) + " / slot20 " + client.player.getInventory().getStack(20)));
        world.getServer().runCommand("kill @e[type=item]");

        // W1-W4: emptying a bucket (placing water, or into a cauldron, where the server turns it empty) brings a full one back.
        bucketCase(context, world, target, "W1 placing water: water bucket refilled from the inventory (empty bucket moved out)", false,
                Map.of(0, stack(Items.WATER_BUCKET, 1), 20, stack(Items.WATER_BUCKET, 1)), true,
                c -> c.player.getInventory().getStack(0).isOf(Items.WATER_BUCKET) && c.player.getInventory().getStack(20).isOf(Items.BUCKET));
        bucketCase(context, world, target, "W2 filling a cauldron: water bucket refilled from a shulker box in the inventory", true,
                Map.of(0, stack(Items.WATER_BUCKET, 1), 10, shulkerWith(null, 5, stack(Items.WATER_BUCKET, 1))), true,
                c -> c.player.getInventory().getStack(0).isOf(Items.WATER_BUCKET) && clientHas(c, Items.BUCKET, 1));
        check("W2 the shulker box gave up its water bucket", innerCount(world, false, 10, Items.WATER_BUCKET) == 0,
                "box=" + innerCount(world, false, 10, Items.WATER_BUCKET));
        bucketCase(context, world, target, "W3 lava bucket refilled too", true,
                Map.of(0, stack(Items.LAVA_BUCKET, 1), 20, stack(Items.LAVA_BUCKET, 1)), true,
                c -> c.player.getInventory().getStack(0).isOf(Items.LAVA_BUCKET) && c.player.getInventory().getStack(20).isOf(Items.BUCKET));
        context.runOnClient(client -> Configs.REFILL_BUCKETS.setBooleanValue(false));
        bucketCase(context, world, target, "W4 Refill Water Buckets off: the empty bucket stays", true,
                Map.of(0, stack(Items.WATER_BUCKET, 1), 20, stack(Items.WATER_BUCKET, 1)), false,
                c -> c.player.getInventory().getStack(0).isOf(Items.BUCKET) && c.player.getInventory().getStack(20).isOf(Items.WATER_BUCKET));
        context.runOnClient(client -> Configs.REFILL_BUCKETS.setBooleanValue(true));
        world.getServer().runOnServer(server -> server.getOverworld().setBlockState(target, Blocks.OBSIDIAN.getDefaultState()));
    }

    private static ItemStack shulkerWith(String name, int innerSlot, ItemStack content) {
        ItemStack box = new ItemStack(Items.ORANGE_SHULKER_BOX);
        net.minecraft.util.collection.DefaultedList<ItemStack> inner = net.minecraft.util.collection.DefaultedList.ofSize(27, ItemStack.EMPTY);
        inner.set(innerSlot, content);
        box.set(DataComponentTypes.CONTAINER, net.minecraft.component.type.ContainerComponent.fromStacks(inner));
        if (name != null) box.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
        return box;
    }

    private int innerCount(TestSingleplayerContext world, boolean enderChest, int slot, Item item) {
        return world.getServer().computeOnServer(server -> {
            ServerPlayerEntity player = player(server);
            ItemStack box = enderChest ? player.getEnderChestInventory().getStack(slot) : player.getInventory().getStack(slot);
            var contents = dev.steelaspect.containerautofill.takeitout.ShulkerUtil.getContents(box);
            if (contents == null) return -1;
            return contents.stream().filter(st -> st.isOf(item)).mapToInt(ItemStack::getCount).sum();
        });
    }

    private int clientCount(ClientGameTestContext context, int slot) {
        return context.computeOnClient(client -> client.player.getInventory().getStack(slot).getCount());
    }

    private int waitForCount(ClientGameTestContext context, int slot, int count) {
        try {
            return context.waitFor(client -> client.player.getInventory().getStack(slot).getCount() == count, 60);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** K1-K6: Restock from named shulker boxes in the inventory and the ender chest. */
    private void testRestock(ClientGameTestContext context, TestSingleplayerContext world) {
        // K1: everything else in the mod off; offhand fireworks restocked from an inventory box named "Restock Fireworks".
        context.runOnClient(client -> {
            Configs.ENABLE_MOD.setBooleanValue(false);
            Configs.SHULKER_PICK_BLOCK.setBooleanValue(false);
            Configs.USE_LINKED_CONTAINERS.setBooleanValue(false);
            Configs.HOTBAR_REFILL.setBooleanValue(false);
            Configs.RESTOCK_ENABLED.setBooleanValue(true);
        });
        context.runOnClient(client -> client.player.getInventory().setSelectedSlot(0));
        setPlayerInventory(world, Map.of(40, stack(Items.FIREWORK_ROCKET, 5), 12, shulkerWith("Restock Fireworks", 3, stack(Items.FIREWORK_ROCKET, 64))));
        int ticks = waitForCount(context, 40, 64);
        LOG.info("SPEED K1 offhand restocked after {} ticks", ticks);
        check("K1 offhand fireworks topped up to 64 from an inventory restock box, rest of the mod off",
                ticks >= 0 && innerCount(world, false, 12, Items.FIREWORK_ROCKET) == 5,
                "offhand=" + clientCount(context, 40) + " box=" + innerCount(world, false, 12, Items.FIREWORK_ROCKET));
        context.runOnClient(client -> Configs.ENABLE_MOD.setBooleanValue(true));

        // K2: hotbar stack restocked from a restock box inside the ender chest.
        world.getServer().runOnServer(server -> player(server).getEnderChestInventory().setStack(0, shulkerWith("restock", 0, stack(Items.COBBLESTONE, 64))));
        setPlayerInventory(world, Map.of(2, stack(Items.COBBLESTONE, 10)));
        ticks = waitForCount(context, 2, 64);
        check("K2 hotbar cobblestone topped up from a restock box in the ender chest",
                ticks >= 0 && innerCount(world, true, 0, Items.COBBLESTONE) == 10,
                "slot2=" + clientCount(context, 2) + " ender box=" + innerCount(world, true, 0, Items.COBBLESTONE));
        world.getServer().runOnServer(server -> player(server).getEnderChestInventory().clear());

        // K3: a box without the restock name is never used.
        setPlayerInventory(world, Map.of(40, stack(Items.FIREWORK_ROCKET, 5), 12, shulkerWith("Fireworks", 3, stack(Items.FIREWORK_ROCKET, 64))));
        context.waitFor(client -> client.player.getOffHandStack().getCount() == 5, 40);
        context.waitTicks(20);
        check("K3 a shulker box without the restock name is not used", clientCount(context, 40) == 5
                && innerCount(world, false, 12, Items.FIREWORK_ROCKET) == 64, "offhand=" + clientCount(context, 40));

        // K4: a stack at or above the threshold (16) is left alone.
        setPlayerInventory(world, Map.of(3, stack(Items.COBBLESTONE, 20), 12, shulkerWith("Restock", 0, stack(Items.COBBLESTONE, 64))));
        context.waitFor(client -> client.player.getInventory().getStack(3).getCount() == 20, 40);
        context.waitTicks(20);
        check("K4 a stack of 20 (threshold 16) is not restocked", clientCount(context, 3) == 20, "slot3=" + clientCount(context, 3));

        // K5: threshold 1, so only using the last item up triggers it: place the last cobblestone.
        context.runOnClient(client -> Configs.RESTOCK_THRESHOLD.setIntegerValue(1));
        BlockPos target = chestA.add(-14, 0, 6);
        refillCase(context, world, target, "K5 last item used up: slot refilled from a restock box",
                Map.of(0, stack(Items.COBBLESTONE, 1), 12, shulkerWith("Restock", 0, stack(Items.COBBLESTONE, 64))), true,
                c -> c.player.getInventory().getStack(0).isOf(Items.COBBLESTONE) && c.player.getInventory().getStack(0).getCount() == 64);
        context.runOnClient(client -> Configs.RESTOCK_THRESHOLD.setIntegerValue(16));

        // K6: Restock turned off.
        context.runOnClient(client -> Configs.RESTOCK_ENABLED.setBooleanValue(false));
        setPlayerInventory(world, Map.of(40, stack(Items.FIREWORK_ROCKET, 5), 12, shulkerWith("Restock Fireworks", 3, stack(Items.FIREWORK_ROCKET, 64))));
        context.waitFor(client -> client.player.getOffHandStack().getCount() == 5, 40);
        context.waitTicks(20);
        check("K6 Restock off: nothing is restocked", clientCount(context, 40) == 5, "offhand=" + clientCount(context, 40));

        context.runOnClient(client -> Configs.RESTOCK_ENABLED.setBooleanValue(true));
        testRestockTotems(context, world);

        context.runOnClient(client -> {
            Configs.RESTOCK_ENABLED.setBooleanValue(true);
            Configs.SHULKER_PICK_BLOCK.setBooleanValue(true);
            Configs.USE_LINKED_CONTAINERS.setBooleanValue(true);
            Configs.HOTBAR_REFILL.setBooleanValue(true);
        });
        setPlayerInventory(world, Map.of());
        context.waitTicks(5);
    }

    /** Client-side count of an item inside a shulker box in the player's inventory (safe inside waitFor). */
    private static int clientInner(MinecraftClient client, int slot, Item item) {
        var contents = dev.steelaspect.containerautofill.takeitout.ShulkerUtil.getContents(client.player.getInventory().getStack(slot));
        return contents == null ? -1 : contents.stream().filter(st -> st.isOf(item)).mapToInt(ItemStack::getCount).sum();
    }

    /** Deals lethal fall damage so a held totem of undying pops. */
    private void popTotem(ClientGameTestContext context, TestSingleplayerContext world) {
        context.waitTicks(20); // past the hurt cooldown of any earlier hit
        world.getServer().runCommand("damage @p 100 minecraft:fall");
    }

    /** K7-K10: a popped totem is replaced in the same slot; dropped totems and Restock Totems off are left alone. */
    private void testRestockTotems(ClientGameTestContext context, TestSingleplayerContext world) {
        Predicate<MinecraftClient> offhandTotem = c -> c.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING);

        // K7: offhand totem pops; a new one comes from a carried restock box.
        setPlayerInventory(world, Map.of(40, stack(Items.TOTEM_OF_UNDYING, 1), 14, shulkerWith("Restock Totems", 0, stack(Items.TOTEM_OF_UNDYING, 3))));
        context.waitFor(offhandTotem, 40);
        popTotem(context, world);
        boolean popped;
        try {
            context.waitFor(c -> !c.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING) || clientInner(c, 14, Items.TOTEM_OF_UNDYING) < 3, 40);
            popped = true;
        } catch (Throwable t) {
            popped = false;
        }
        int ticks;
        try {
            ticks = context.waitFor(c -> offhandTotem.test(c) && clientInner(c, 14, Items.TOTEM_OF_UNDYING) == 2, 60);
        } catch (Throwable t) {
            ticks = -1;
        }
        boolean alive = context.computeOnClient(c -> c.player.isAlive());
        LOG.info("SPEED K7 popped totem replaced after {} ticks", ticks);
        check("K7 popped offhand totem replaced from a carried restock box", popped && ticks >= 0 && alive,
                "popped=" + popped + " offhand=" + context.computeOnClient(c -> c.player.getOffHandStack().toString())
                        + " box=" + innerCount(world, false, 14, Items.TOTEM_OF_UNDYING) + " alive=" + alive);

        // K8: the same with the restock box in the ender chest.
        world.getServer().runOnServer(server -> player(server).getEnderChestInventory().setStack(5, shulkerWith("restock", 2, stack(Items.TOTEM_OF_UNDYING, 2))));
        setPlayerInventory(world, Map.of(40, stack(Items.TOTEM_OF_UNDYING, 1)));
        context.waitFor(offhandTotem, 40);
        popTotem(context, world);
        try {
            context.waitFor(c -> !offhandTotem.test(c), 40); // popped
            context.waitFor(offhandTotem, 60);              // replaced
        } catch (Throwable ignored) {
        }
        check("K8 popped totem replaced from a restock box in the ender chest",
                context.computeOnClient(offhandTotem::test) && innerCount(world, true, 5, Items.TOTEM_OF_UNDYING) == 1,
                "offhand=" + context.computeOnClient(c -> c.player.getOffHandStack().toString()) + " ender box=" + innerCount(world, true, 5, Items.TOTEM_OF_UNDYING));
        world.getServer().runOnServer(server -> player(server).getEnderChestInventory().clear());

        // K9: dropping a totem (Q) is not a pop: nothing is restocked.
        context.waitTicks(20); // well after the last pop
        setPlayerInventory(world, Map.of(0, stack(Items.TOTEM_OF_UNDYING, 1), 14, shulkerWith("Restock", 0, stack(Items.TOTEM_OF_UNDYING, 3))));
        context.runOnClient(client -> client.player.getInventory().setSelectedSlot(0));
        context.waitFor(c -> c.player.getInventory().getStack(0).isOf(Items.TOTEM_OF_UNDYING), 40);
        context.getInput().pressKey(options -> options.dropKey);
        context.waitTicks(25);
        check("K9 a dropped totem is not restocked", context.computeOnClient(c -> c.player.getInventory().getStack(0).isEmpty())
                && innerCount(world, false, 14, Items.TOTEM_OF_UNDYING) == 3, "slot0=" + context.computeOnClient(c -> c.player.getInventory().getStack(0).toString()));
        world.getServer().runCommand("kill @e[type=item]");

        // K10: Restock Totems off: the popped totem isn't replaced.
        context.runOnClient(client -> Configs.RESTOCK_TOTEMS.setBooleanValue(false));
        setPlayerInventory(world, Map.of(40, stack(Items.TOTEM_OF_UNDYING, 1), 14, shulkerWith("Restock", 0, stack(Items.TOTEM_OF_UNDYING, 3))));
        context.waitFor(offhandTotem, 40);
        popTotem(context, world);
        context.waitTicks(30);
        check("K10 Restock Totems off: popped totem not replaced", context.computeOnClient(c -> c.player.getOffHandStack().isEmpty() && c.player.isAlive())
                && innerCount(world, false, 14, Items.TOTEM_OF_UNDYING) == 3, "offhand=" + context.computeOnClient(c -> c.player.getOffHandStack().toString()));
        context.runOnClient(client -> Configs.RESTOCK_TOTEMS.setBooleanValue(true));
        world.getServer().runCommand("effect clear @p");
        world.getServer().runCommand("effect give @p minecraft:instant_health 1 5");
        context.waitTicks(5);
    }

    private void setPlayerInventory(TestSingleplayerContext world, Map<Integer, ItemStack> items) {
        world.getServer().runOnServer(server -> {
            PlayerInventory inv = player(server).getInventory();
            inv.clear();
            items.forEach((slot, st) -> inv.setStack(slot, st.copy()));
        });
    }

    /**
     * Empties the bucket in hotbar slot 0 and checks what's in the hand after: into a cauldron at {@code target}
     * (the server turns the bucket empty), or onto the obsidian there (placing the liquid in front of it).
     */
    private void bucketCase(ClientGameTestContext context, TestSingleplayerContext world, BlockPos target, String name, boolean cauldron,
                            Map<Integer, ItemStack> inventory, boolean expectRefill, Predicate<MinecraftClient> done) {
        world.getServer().runOnServer(server -> {
            for (Direction d : Direction.values()) server.getOverworld().setBlockState(target.offset(d), Blocks.AIR.getDefaultState());
            server.getOverworld().setBlockState(target.down(), Blocks.STONE_BRICKS.getDefaultState());
            server.getOverworld().setBlockState(target, cauldron ? Blocks.CAULDRON.getDefaultState() : Blocks.OBSIDIAN.getDefaultState());
        });
        setPlayerInventory(world, inventory);
        lookAt(context, world, target);
        try {
            context.waitFor(client -> !client.player.getInventory().getStack(0).isEmpty(), 40);
        } catch (Throwable t) {
            check(name, false, "inventory not set up");
            return;
        }
        context.getInput().pressKey(options -> options.useKey);
        boolean ok;
        if (expectRefill) {
            try {
                context.waitFor(done, 60);
                ok = true;
            } catch (Throwable t) {
                ok = false;
            }
        } else {
            context.waitTicks(20);
            ok = context.computeOnClient(done::test);
        }
        String state = context.computeOnClient(client -> client.player.getInventory().getStack(0) + " / slot20 " + client.player.getInventory().getStack(20));
        check(name, ok, "hotbar 0 = " + state);
        context.waitTicks(5);
        // Placed water or lava would spread into later tests' spots.
        world.getServer().runCommand(String.format(Locale.ROOT, "fill %d %d %d %d %d %d air replace water",
                target.getX() - 8, target.getY() - 1, target.getZ() - 8, target.getX() + 8, target.getY() + 2, target.getZ() + 8));
        world.getServer().runCommand(String.format(Locale.ROOT, "fill %d %d %d %d %d %d air replace lava",
                target.getX() - 8, target.getY() - 1, target.getZ() - 8, target.getX() + 8, target.getY() + 2, target.getZ() + 8));
    }

    private void refillCase(ClientGameTestContext context, TestSingleplayerContext world, BlockPos target, String name,
                            Map<Integer, ItemStack> inventory, boolean expectRefill, Predicate<MinecraftClient> done) {
        world.getServer().runOnServer(server -> {
            for (Direction d : Direction.values()) server.getOverworld().setBlockState(target.offset(d), Blocks.AIR.getDefaultState());
            server.getOverworld().setBlockState(target.down(), Blocks.STONE_BRICKS.getDefaultState());
        });
        setPlayerInventory(world, inventory);
        lookAt(context, world, target);
        try {
            context.waitFor(client -> !client.player.getInventory().getStack(0).isEmpty(), 40);
        } catch (Throwable t) {
            String server = world.getServer().computeOnServer(sv -> player(sv).getInventory().getStack(0) + " sel=" + player(sv).getInventory().getSelectedSlot());
            String client = context.computeOnClient(c -> c.player.getInventory().getStack(0) + " sel=" + c.player.getInventory().getSelectedSlot()
                    + " screen=" + c.currentScreen);
            check(name, false, "inventory not set up: server " + server + ", client " + client + " full server inv " + world.getServer()
                    .computeOnServer(sv -> { StringBuilder b = new StringBuilder(); PlayerInventory inv = player(sv).getInventory();
                        for (int i = 0; i < inv.size(); i++) if (!inv.getStack(i).isEmpty()) b.append(i).append('=').append(inv.getStack(i)).append(' ');
                        return b.toString(); }));
            return;
        }
        context.getInput().pressKey(options -> options.useKey);
        boolean ok;
        if (expectRefill) {
            try {
                context.waitFor(done, 40);
                ok = true;
            } catch (Throwable t) {
                ok = false;
            }
        } else {
            context.waitTicks(15);
            ok = context.computeOnClient(done::test);
        }
        String state = context.computeOnClient(client -> client.player.getInventory().getStack(0) + " / slot20 " + client.player.getInventory().getStack(20)
                + " | target " + client.world.getBlockState(target) + " south " + client.world.getBlockState(target.south())
                + " | player " + client.player.getBlockPos() + " yaw " + client.player.getYaw() + " pitch " + client.player.getPitch()
                + " | crosshair " + client.crosshairTarget + (client.crosshairTarget instanceof net.minecraft.util.hit.BlockHitResult b ? " " + b.getBlockPos() + " " + b.getSide() : "")
                + " | screen " + client.currentScreen + " using " + client.player.isUsingItem());
        check(name, ok, "hotbar 0 = " + state);
        context.waitTicks(5); // let the server finish the refill before the next case resets the inventory
    }

    private void testDump(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runOnServer(server -> {
            PlayerInventory inv = player(server).getInventory();
            inv.clear();
            inv.setStack(9, stack(Items.COBBLESTONE, 64));
            inv.setStack(10, stack(Items.DIRT, 10));
            inv.setStack(0, stack(Items.TORCH, 5)); // hotbar: must stay
        });
        context.waitTicks(5);
        context.getInput().pressKey(GLFW.GLFW_KEY_N);
        try {
            context.waitFor(client -> !clientHas(client, Items.COBBLESTONE, 1) && !clientHas(client, Items.DIRT, 1), 100);
        } catch (Throwable ignored) {
        }
        check("S9 dump moves the main inventory into the dump container", containerCount(world, dumpChest, Items.COBBLESTONE) == 64
                && containerCount(world, dumpChest, Items.DIRT) == 10, "cobble=" + containerCount(world, dumpChest, Items.COBBLESTONE)
                + " dirt=" + containerCount(world, dumpChest, Items.DIRT));
        check("S9 hotbar is not dumped", serverCount(world, s -> s.isOf(Items.TORCH)) == 5, "torches moved");
    }

    private Map<Integer, ItemStack> contents(TestSingleplayerContext world, BlockPos pos) {
        return world.getServer().computeOnServer(server -> {
            Inventory inv = (Inventory) server.getOverworld().getBlockEntity(pos);
            Map<Integer, ItemStack> m = new java.util.HashMap<>();
            for (int i = 0; i < inv.size(); i++) if (!inv.getStack(i).isEmpty()) m.put(i, inv.getStack(i).copy());
            return m;
        });
    }

    private static boolean matches(Map<Integer, ItemStack> actual, Map<Integer, ItemStack> expected) {
        if (actual.size() != expected.size()) return false;
        for (Map.Entry<Integer, ItemStack> e : expected.entrySet()) {
            ItemStack a = actual.get(e.getKey());
            if (a == null || !ItemStack.areEqual(a, e.getValue())) return false;
        }
        return true;
    }

    private void testInstantFill(ClientGameTestContext context, TestSingleplayerContext world) {
        check("I0 server supports instant fill", context.computeOnClient(client -> InstantFill.isSupported()), "fill channel missing");
        world.getServer().runOnServer(server -> {
            PlayerInventory inv = player(server).getInventory();
            inv.clear();
            inv.setStack(9, stack(Items.STONE, 64));
            ItemStack box = new ItemStack(Items.PURPLE_SHULKER_BOX);
            net.minecraft.util.collection.DefaultedList<ItemStack> inner = net.minecraft.util.collection.DefaultedList.ofSize(27, ItemStack.EMPTY);
            inner.set(3, stack(Items.IRON_INGOT, 40));
            box.set(DataComponentTypes.CONTAINER, net.minecraft.component.type.ContainerComponent.fromStacks(inner));
            inv.setStack(10, box);
            inv.setStack(11, stack(Items.GLASS, 16));
        });
        context.runOnClient(client -> {
            Configs.INSTANT_FILL.setBooleanValue(true);
            Configs.CLEAR_WRONG_ITEMS.setBooleanValue(false);
            Configs.FILL_LOOKED_AT_CONTAINER.setValueFromString("V");
            InputEventHandler.getKeybindManager().updateUsedKeys();
            StorageActions.refreshAll();
        });
        context.waitTicks(40);

        lookAt(context, world, fillLeft);
        var before = dev.steelaspect.containerautofill.filler.AutoFillController.getLastResult();
        boolean[] screenOpened = {false};
        context.getInput().pressKey(GLFW.GLFW_KEY_V);
        try {
            context.waitFor(client -> {
                if (client.currentScreen != null) screenOpened[0] = true;
                return dev.steelaspect.containerautofill.filler.AutoFillController.getLastResult() != before;
            }, 100);
        } catch (Throwable t) {
            check("I1 instant fill answered", false, "no result");
            return;
        }
        int ticks = 0;
        var result = dev.steelaspect.containerautofill.filler.AutoFillController.getLastResult();
        check("I1 no container screen was opened", !screenOpened[0], "a screen opened");
        ItemStack gem = stack(Items.DIAMOND, 1);
        gem.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Gem"));
        check("I2 double chest right half: loose stone + iron from a shulker in the inventory",
                matches(contents(world, fillRight), Map.of(0, stack(Items.STONE, 64), 1, stack(Items.IRON_INGOT, 30))),
                "right=" + contents(world, fillRight));
        check("I3 double chest left half: items pulled from linked containers (incl. 160 blocks away, named item)",
                matches(contents(world, fillLeft), Map.of(0, stack(Items.EMERALD, 5), 3, stack(Items.GOLD_INGOT, 4), 7, gem)),
                "left=" + contents(world, fillLeft));
        check("I4 nothing missing for the double chest", result.missingItems() == 0 && result.filledSlots() == 5, "result " + result);
        check("I5 items really moved (shulker 40->10 iron, far chest 6->1 emerald)",
                containerCount(world, farChest, Items.EMERALD) == 1 && serverCount(world, s -> {
                    var c = s.get(DataComponentTypes.CONTAINER);
                    return c != null && c.stream().anyMatch(i -> i.isOf(Items.IRON_INGOT) && i.getCount() == 10);
                }) == 1, "far emerald=" + containerCount(world, farChest, Items.EMERALD));

        lookAt(context, world, fillHopper);
        var beforeHopper = dev.steelaspect.containerautofill.filler.AutoFillController.getLastResult();
        context.getInput().pressKey(GLFW.GLFW_KEY_V);
        try {
            context.waitFor(client -> dev.steelaspect.containerautofill.filler.AutoFillController.getLastResult() != beforeHopper, 100);
        } catch (Throwable ignored) {
        }
        var hopperResult = dev.steelaspect.containerautofill.filler.AutoFillController.getLastResult();
        check("I6 wrong item kept (clear wrong off) and missing TNT reported",
                matches(contents(world, fillHopper), Map.of(0, stack(Items.DIRT, 1)))
                        && hopperResult.wrongSlots() == 1 && hopperResult.missingItems() == 5,
                "hopper=" + contents(world, fillHopper) + " result=" + hopperResult);
        context.runOnClient(client -> Configs.CLEAR_WRONG_ITEMS.setBooleanValue(true));
        var beforeClear = dev.steelaspect.containerautofill.filler.AutoFillController.getLastResult();
        context.getInput().pressKey(GLFW.GLFW_KEY_V);
        try {
            context.waitFor(client -> dev.steelaspect.containerautofill.filler.AutoFillController.getLastResult() != beforeClear, 100);
        } catch (Throwable ignored) {
        }
        check("I7 clear wrong items on: dirt removed, glass filled",
                matches(contents(world, fillHopper), Map.of(0, stack(Items.GLASS, 3))) && serverCount(world, s -> s.isOf(Items.DIRT)) == 1,
                "hopper=" + contents(world, fillHopper));
        context.runOnClient(client -> Configs.CLEAR_WRONG_ITEMS.setBooleanValue(false));
    }

    private void testAreaFillAndServerStatus(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runOnServer(server -> {
            PlayerInventory inv = player(server).getInventory();
            inv.clear();
            inv.setStack(9, stack(Items.OAK_LOG, 64));
            inv.setStack(10, stack(Items.BRICKS, 64));
        });
        // Stand next to area1/area2; areaFar is 14 blocks away, the double chest is just out of range.
        world.getServer().runCommand(String.format(Locale.ROOT, "tp @p %.1f %d %.1f 180 30",
                area1.getX() + 1.5, area1.getY(), area1.getZ() + 2.5));
        context.runOnClient(client -> Configs.AREA_FILL_RANGE.setIntegerValue(5));

        var statuses = waitForStatus(context, area1, s -> s != null && s != dev.steelaspect.containerautofill.highlight.ContainerStatus.UNKNOWN);
        check("Q1 status known without opening the container (from the server): EMPTY",
                statuses.get(area1) == dev.steelaspect.containerautofill.highlight.ContainerStatus.EMPTY, "area1=" + statuses.get(area1));
        statuses = waitForStatus(context, fillHopper, s -> s == dev.steelaspect.containerautofill.highlight.ContainerStatus.PARTIAL);
        check("Q2 hopper (glass in, TNT missing) reads PARTIAL from the server without opening it",
                statuses.get(fillHopper) == dev.steelaspect.containerautofill.highlight.ContainerStatus.PARTIAL, "hopper=" + statuses.get(fillHopper));

        overviewShot(context, world, "area-fill-1-before");
        world.getServer().runCommand(String.format(Locale.ROOT, "tp @p %.1f %d %.1f 180 30",
                area1.getX() + 1.5, area1.getY(), area1.getZ() + 2.5));
        context.waitTicks(15);

        var before = dev.steelaspect.containerautofill.filler.AutoFillController.getLastResult();
        boolean[] screenOpened = {false};
        context.getInput().holdKey(GLFW.GLFW_KEY_LEFT_SHIFT);
        context.getInput().pressKey(GLFW.GLFW_KEY_V);
        context.getInput().releaseKey(GLFW.GLFW_KEY_LEFT_SHIFT);
        try {
            context.waitFor(client -> {
                if (client.currentScreen != null) screenOpened[0] = true;
                return dev.steelaspect.containerautofill.filler.AutoFillController.getLastResult() != before;
            }, 100);
        } catch (Throwable t) {
            check("A1 area fill answered", false, "no result");
            return;
        }
        check("A1 area fill (Shift+V) filled both nearby containers without opening them",
                !screenOpened[0] && matches(contents(world, area1), Map.of(0, stack(Items.OAK_LOG, 10)))
                        && matches(contents(world, area2), Map.of(4, stack(Items.BRICKS, 7))),
                "area1=" + contents(world, area1) + " area2=" + contents(world, area2) + " screen=" + screenOpened[0]);
        check("A2 container outside Area Fill Range left alone", contents(world, areaFar).isEmpty(), "far=" + contents(world, areaFar));
        check("A3 correct containers skipped, out-of-range double chest untouched",
                matches(contents(world, fillHopper), Map.of(0, stack(Items.GLASS, 3))) && contents(world, fillLeft).size() == 3,
                "hopper=" + contents(world, fillHopper));
        // Back to the overview straight away, so the action bar summary is still showing.
        world.getServer().runCommand(String.format(Locale.ROOT, "tp @p %.1f %d %.1f 180 32",
                fillLeft.getX() + 6.5, fillLeft.getY() + 3, fillLeft.getZ() + 7.5));
        context.waitTicks(12);
        context.runOnClient(client -> client.inGameHud.getChatHud().clear(false));
        LOG.info("Screenshot: {}", context.takeScreenshot("area-fill-2-after"));
        var after = waitForStatus(context, area1, s -> s == dev.steelaspect.containerautofill.highlight.ContainerStatus.CORRECT);
        check("Q3 highlight turns CORRECT after area fill", after.get(area1) == dev.steelaspect.containerautofill.highlight.ContainerStatus.CORRECT,
                "area1=" + after.get(area1));
    }

    private void testCreativeFill(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runCommand("gamemode creative @a");
        world.getServer().runOnServer(server -> player(server).getInventory().clear());
        context.runOnClient(client -> {
            Configs.CREATIVE_FILL.setBooleanValue(true);
            Configs.INSTANT_FILL.setBooleanValue(true);
        });
        context.waitTicks(10);

        lookAt(context, world, areaFar);
        var before = dev.steelaspect.containerautofill.filler.AutoFillController.getLastResult();
        context.getInput().pressKey(GLFW.GLFW_KEY_V);
        try {
            context.waitFor(client -> dev.steelaspect.containerautofill.filler.AutoFillController.getLastResult() != before, 100);
        } catch (Throwable ignored) {
        }
        check("C1 creative instant fill with an empty inventory and nothing linked fills the container",
                matches(contents(world, areaFar), Map.of(0, stack(Items.OAK_LOG, 1))), "far=" + contents(world, areaFar));

        // Click-based fallback (Instant Fill off): items are made with creative inventory actions, then clicked in.
        context.runOnClient(client -> Configs.INSTANT_FILL.setBooleanValue(false));
        lookAt(context, world, area3);
        var beforeClicks = dev.steelaspect.containerautofill.filler.AutoFillController.getLastResult();
        context.getInput().pressKey(GLFW.GLFW_KEY_V);
        try {
            context.waitFor(client -> dev.steelaspect.containerautofill.filler.AutoFillController.getLastResult() != beforeClicks
                    && client.currentScreen == null, 400);
        } catch (Throwable ignored) {
        }
        ItemStack gem = stack(Items.DIAMOND, 1);
        gem.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Gem"));
        check("C2 creative click fill (Instant Fill off) fills exactly, including a named item",
                matches(contents(world, area3), Map.of(0, stack(Items.BRICKS, 20), 13, gem)), "area3=" + contents(world, area3));
        context.runOnClient(client -> Configs.INSTANT_FILL.setBooleanValue(true));
        world.getServer().runCommand("gamemode survival @a");
        context.waitTicks(5);
    }

    /** Wide view of the schematic row (double chest, hopper, area chests) from behind and above. */
    private void overviewShot(ClientGameTestContext context, TestSingleplayerContext world, String name) {
        world.getServer().runCommand(String.format(Locale.ROOT, "tp @p %.1f %d %.1f 180 32",
                fillLeft.getX() + 6.5, fillLeft.getY() + 3, fillLeft.getZ() + 7.5));
        context.waitTicks(25);
        context.runOnClient(client -> {
            client.inGameHud.getChatHud().clear(false);
            client.getToastManager().clear();
        });
        context.waitTicks(2);
        LOG.info("Screenshot: {}", context.takeScreenshot(name));
    }

    private Map<BlockPos, dev.steelaspect.containerautofill.highlight.ContainerStatus> waitForStatus(ClientGameTestContext context, BlockPos pos,
            Predicate<dev.steelaspect.containerautofill.highlight.ContainerStatus> ready) {
        try {
            context.waitFor(client -> ready.test(dev.steelaspect.containerautofill.highlight.ContainerHighlighter.statuses().get(pos)), 200);
        } catch (Throwable ignored) {
        }
        return dev.steelaspect.containerautofill.highlight.ContainerHighlighter.statuses();
    }

    /** N1-N7: items inside shulker boxes stored in a linked chest (menu, take, pick block, fills). */
    private void testShulkersInLinkedChests(ClientGameTestContext context, TestSingleplayerContext world) {
        BlockPos boxChest = chestA.add(8, 0, 0);
        BlockPos coalSpot = chestA.add(10, 0, 5);
        world.getServer().runCommand("gamemode survival @a");
        setPlayerInventory(world, Map.of());
        List<BlockPos> linked = context.computeOnClient(client -> StorageStore.linkedEntries().stream().map(StorageStore.Entry::pos).toList());
        world.getServer().runOnServer(server -> {
            ServerWorld w = server.getOverworld();
            w.setBlockState(boxChest, Blocks.CHEST.getDefaultState());
            w.setBlockState(coalSpot, Blocks.COAL_BLOCK.getDefaultState());
            ItemStack box = shulkerWith(null, 4, stack(Items.LAPIS_BLOCK, 20));
            fill(w, boxChest, Map.of(
                    0, box,
                    1, shulkerWith(null, 0, stack(Items.COAL_BLOCK, 8)),
                    2, stack(Items.BONE_BLOCK, 2),
                    3, shulkerWith(null, 7, stack(Items.BONE_BLOCK, 10)),
                    4, shulkerWith(null, 2, stack(Items.OAK_LOG, 30))));
            // Oak logs only inside that shulker box: none loose in other linked containers.
            for (BlockPos pos : linked) {
                if (!(w.getBlockEntity(pos) instanceof Inventory inv)) continue;
                for (int i = 0; i < inv.size(); i++) if (inv.getStack(i).isOf(Items.OAK_LOG)) inv.setStack(i, ItemStack.EMPTY);
            }
            ((Inventory) w.getBlockEntity(area1)).clear();
        });
        context.runOnClient(client -> {
            StorageStore.link(overworld, boxChest);
            Configs.USE_TAKEITOUT_SOURCES.setBooleanValue(true);
            StorageActions.refreshAll();
        });
        context.waitTicks(30);

        check("N1 supported: the server can take from shulker boxes in linked containers",
                context.computeOnClient(client -> StorageContents.isShulkerTakeSupported()), "take_from_shulker channel missing");
        int lapis = context.computeOnClient(client -> StorageActions.countAvailable(client, s -> s.isOf(Items.LAPIS_BLOCK)));
        check("N1 storage menu counts items inside shulker boxes in a linked chest", lapis == 20, "lapis=" + lapis);

        context.runOnClient(client -> StorageActions.take(client, stack(Items.LAPIS_BLOCK, 1), 5));
        try {
            context.waitFor(client -> clientHas(client, Items.LAPIS_BLOCK, 5), 100);
        } catch (Throwable ignored) {
        }
        check("N2 menu take pulls 5 out of the shulker box in the linked chest",
                serverCount(world, s -> s.isOf(Items.LAPIS_BLOCK)) == 5 && boxCount(world, boxChest, 0, Items.LAPIS_BLOCK) == 15,
                "player=" + serverCount(world, s -> s.isOf(Items.LAPIS_BLOCK)) + " box=" + boxCount(world, boxChest, 0, Items.LAPIS_BLOCK));

        lookAt(context, world, coalSpot);
        context.getInput().pressKey(options -> options.pickItemKey);
        try {
            context.waitFor(client -> client.player.getMainHandStack().isOf(Items.COAL_BLOCK), 100);
        } catch (Throwable ignored) {
        }
        check("N3 pick block pulls from a shulker box in a linked chest into the hand",
                context.computeOnClient(client -> client.player.getMainHandStack().isOf(Items.COAL_BLOCK))
                        && boxCount(world, boxChest, 1, Items.COAL_BLOCK) == 0,
                "hand=" + context.computeOnClient(client -> client.player.getMainHandStack().toString()) + " box=" + boxCount(world, boxChest, 1, Items.COAL_BLOCK));

        context.runOnClient(client -> StorageRetriever.request(client, s -> s.isOf(Items.BONE_BLOCK), 2, false));
        try {
            context.waitFor(client -> clientHas(client, Items.BONE_BLOCK, 2), 100);
        } catch (Throwable ignored) {
        }
        check("N4 loose items are taken before shulker contents",
                containerCount(world, boxChest, Items.BONE_BLOCK) == 0 && boxCount(world, boxChest, 3, Items.BONE_BLOCK) == 10,
                "loose=" + containerCount(world, boxChest, Items.BONE_BLOCK) + " box=" + boxCount(world, boxChest, 3, Items.BONE_BLOCK));

        // Instant fill: area1 expects 10 oak logs, which are only in the shulker box.
        context.runOnClient(client -> StorageActions.refreshAll());
        context.waitTicks(20);
        lookAt(context, world, area1);
        var before = dev.steelaspect.containerautofill.filler.AutoFillController.getLastResult();
        context.getInput().pressKey(GLFW.GLFW_KEY_V);
        try {
            context.waitFor(client -> dev.steelaspect.containerautofill.filler.AutoFillController.getLastResult() != before, 100);
        } catch (Throwable ignored) {
        }
        check("N5 instant fill takes from a shulker box in a linked chest",
                matches(contents(world, area1), Map.of(0, stack(Items.OAK_LOG, 10))) && boxCount(world, boxChest, 4, Items.OAK_LOG) == 20,
                "area1=" + contents(world, area1) + " box=" + boxCount(world, boxChest, 4, Items.OAK_LOG));

        world.getServer().runOnServer(server -> ((Inventory) server.getOverworld().getBlockEntity(area1)).clear());
        context.runOnClient(client -> {
            Configs.USE_TAKEITOUT_SOURCES.setBooleanValue(false);
            StorageActions.refreshAll();
        });
        context.waitTicks(20);
        var beforeOff = dev.steelaspect.containerautofill.filler.AutoFillController.getLastResult();
        context.getInput().pressKey(GLFW.GLFW_KEY_V);
        try {
            context.waitFor(client -> dev.steelaspect.containerautofill.filler.AutoFillController.getLastResult() != beforeOff, 100);
        } catch (Throwable ignored) {
        }
        check("N6 Use Shulker Boxes off: instant fill leaves shulker boxes in linked chests alone",
                contents(world, area1).isEmpty() && boxCount(world, boxChest, 4, Items.OAK_LOG) == 20,
                "area1=" + contents(world, area1) + " box=" + boxCount(world, boxChest, 4, Items.OAK_LOG));
        context.runOnClient(client -> Configs.USE_TAKEITOUT_SOURCES.setBooleanValue(true));

        int boxes = 0;
        for (int slot : new int[]{0, 1, 3, 4}) {
            final int s = slot;
            boolean isBox = world.getServer().computeOnServer(server ->
                    ((Inventory) server.getOverworld().getBlockEntity(boxChest)).getStack(s).isIn(net.minecraft.registry.tag.ItemTags.SHULKER_BOXES));
            if (isBox) boxes++;
        }
        check("N7 the shulker boxes themselves stay in the chest", boxes == 4, "boxes=" + boxes);
        context.runOnClient(client -> StorageStore.remove(StorageStore.find(overworld, boxChest)));
        setPlayerInventory(world, Map.of());
    }

    /** M1-M4: Get Materials (Litematica material list into the carried shulker boxes). */
    private void testGetMaterials(ClientGameTestContext context, TestSingleplayerContext world) {
        // M1: wants come from Litematica's material list: break the placed hopper, so one hopper is missing.
        world.getServer().runOnServer(server -> server.getOverworld().setBlockState(fillHopper, Blocks.AIR.getDefaultState()));
        context.waitTicks(5);
        context.runOnClient(client -> {
            var placement = DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().get(0);
            DataManager.setMaterialList(new fi.dy.masa.litematica.materials.MaterialListPlacement(placement, true));
        });
        try {
            context.waitFor(client -> !DataManager.getMaterialList().getMaterialsAll().isEmpty(), 200);
        } catch (Throwable ignored) {
        }
        var wants = context.computeOnClient(client -> dev.steelaspect.containerautofill.storage.MaterialPull.wantsFromMaterialList(client, DataManager.getMaterialList()));
        check("M1 material list wants include the missing hopper", wants.stream().anyMatch(w -> w.kind().isOf(Items.HOPPER) && w.count() == 1),
                "wants=" + wants);
        world.getServer().runOnServer(server -> server.getOverworld().setBlockState(fillHopper, Blocks.HOPPER.getDefaultState()));

        // M2-M3: pull into carried boxes from a linked chest (loose + in a shulker box there).
        BlockPos source = chestA.add(8, 0, 2);
        world.getServer().runOnServer(server -> {
            server.getOverworld().setBlockState(source, Blocks.CHEST.getDefaultState());
            fill(server.getOverworld(), source, Map.of(0, stack(Items.BRICKS, 64), 1, stack(Items.BRICKS, 36),
                    2, shulkerWith(null, 3, stack(Items.OAK_PLANKS, 30))));
        });
        setPlayerInventory(world, Map.of(10, shulkerWith(null, 0, stack(Items.BRICKS, 5)), 11, new ItemStack(Items.WHITE_SHULKER_BOX)));
        context.runOnClient(client -> {
            StorageStore.link(overworld, source);
            StorageActions.refreshAll();
        });
        context.waitTicks(30);
        var before = context.computeOnClient(client -> dev.steelaspect.containerautofill.storage.MaterialPull.lastResult());
        context.runOnClient(client -> dev.steelaspect.containerautofill.storage.MaterialPull.pull(client, List.of(
                new dev.steelaspect.containerautofill.network.MaterialPayloads.Want(stack(Items.BRICKS, 1), 80),
                new dev.steelaspect.containerautofill.network.MaterialPayloads.Want(stack(Items.OAK_PLANKS, 1), 20),
                new dev.steelaspect.containerautofill.network.MaterialPayloads.Want(stack(Items.DIAMOND, 1), 5))));
        try {
            context.waitFor(client -> dev.steelaspect.containerautofill.storage.MaterialPull.lastResult() != before, 100);
        } catch (Throwable ignored) {
        }
        var result = context.computeOnClient(client -> dev.steelaspect.containerautofill.storage.MaterialPull.lastResult());
        int bricks = innerCount(world, false, 10, Items.BRICKS) + innerCount(world, false, 11, Items.BRICKS);
        int planks = innerCount(world, false, 10, Items.OAK_PLANKS) + innerCount(world, false, 11, Items.OAK_PLANKS);
        check("M2 materials moved into the carried shulker boxes (bricks topped up 5 -> 85, 20 planks from a box in the chest)",
                bricks == 85 && planks == 20 && containerCount(world, source, Items.BRICKS) == 20
                        && boxCount(world, source, 2, Items.OAK_PLANKS) == 10,
                "bricks=" + bricks + " planks=" + planks + " chest bricks=" + containerCount(world, source, Items.BRICKS)
                        + " chest box planks=" + boxCount(world, source, 2, Items.OAK_PLANKS));
        check("M3 what isn't anywhere is reported missing (5 diamonds)", result != null && result.moved() == 100
                && result.missing().size() == 1 && result.missing().get(0).kind().isOf(Items.DIAMOND) && result.missing().get(0).count() == 5,
                "result=" + result);

        // M4: no shulker boxes carried.
        setPlayerInventory(world, Map.of());
        context.waitTicks(5);
        var beforeNone = context.computeOnClient(client -> dev.steelaspect.containerautofill.storage.MaterialPull.lastResult());
        context.runOnClient(client -> dev.steelaspect.containerautofill.storage.MaterialPull.pull(client, List.of(
                new dev.steelaspect.containerautofill.network.MaterialPayloads.Want(stack(Items.BRICKS, 1), 5))));
        try {
            context.waitFor(client -> dev.steelaspect.containerautofill.storage.MaterialPull.lastResult() != beforeNone, 100);
        } catch (Throwable ignored) {
        }
        var none = context.computeOnClient(client -> dev.steelaspect.containerautofill.storage.MaterialPull.lastResult());
        check("M4 no shulker boxes carried: nothing moved, said so", none != null && none.noBoxes() && none.moved() == 0
                && containerCount(world, source, Items.BRICKS) == 20, "result=" + none);
        context.runOnClient(client -> StorageStore.remove(StorageStore.find(overworld, source)));
    }

    /** How many of {@code item} the shulker box in a container slot holds. */
    private int boxCount(TestSingleplayerContext world, BlockPos pos, int slot, Item item) {
        return world.getServer().computeOnServer(server -> {
            ItemStack box = ((Inventory) server.getOverworld().getBlockEntity(pos)).getStack(slot);
            var contents = box.get(DataComponentTypes.CONTAINER);
            return contents == null ? 0 : contents.stream().filter(i -> i.isOf(item)).mapToInt(ItemStack::getCount).sum();
        });
    }

    private void testLookAtAndGroups(ClientGameTestContext context) {
        context.runOnClient(client -> StorageActions.lookAt(client, stack(Items.IRON_INGOT, 1)));
        check("S10 Look At marks the containers holding the item",
                StorageActions.lookAtTargets().contains(new StorageContents.Key(overworld, chestA)), "targets=" + StorageActions.lookAtTargets());

        String original = StorageStore.activeGroup().name;
        int linked = StorageStore.linkedCount();
        context.runOnClient(client -> StorageStore.createGroup("Second"));
        check("S11 new group becomes active and starts empty", StorageStore.activeGroup().name.equals("Second") && StorageStore.linkedCount() == 0,
                "active=" + StorageStore.activeGroup().name);
        context.runOnClient(client -> StorageStore.setActive(original));
        check("S11 switching back restores the links", StorageStore.linkedCount() == linked, "count=" + StorageStore.linkedCount());
        testSharedGroups(context, original);
        context.runOnClient(client -> StorageStore.setActive(original));
    }

    /** G1-G6: sharing a group on the server, adding it, updating it and removing it. */
    private void testSharedGroups(ClientGameTestContext context, String original) {
        check("G1 server supports shared groups", context.computeOnClient(client -> SharedGroups.isSupported()), "group_share channel missing");
        int size = StorageStore.group(original).containers.size();
        context.runOnClient(client -> SharedGroups.share(client, StorageStore.group(original)));
        SharedGroupPayloads.Summary shared = waitForShared(context, original);
        check("G2 shared group is listed for everyone, with its owner and size",
                shared != null && shared.mine() && shared.removable() && shared.count() == size && !shared.owner().isBlank(),
                "summary=" + shared);
        if (shared == null) return;
        context.runOnClient(client -> {
            setTab("GROUPS");
            client.setScreen(new StorageScreen(null));
        });
        context.waitTicks(20);
        LOG.info("Storage screenshot: {}", context.takeScreenshot("storage-groups-shared"));
        context.runOnClient(client -> {
            setTab("ITEMS");
            client.setScreen(null);
        });

        int groups = StorageStore.groups().size();
        context.runOnClient(client -> SharedGroups.add(shared.groupId()));
        try {
            context.waitFor(client -> StorageStore.groups().size() == groups + 1, 100);
        } catch (Throwable ignored) {
        }
        StorageStore.Group added = StorageStore.activeGroup();
        check("G3 Add puts a copy in your groups (new name, same containers, linked/dump kept)",
                StorageStore.groups().size() == groups + 1 && !added.name.equals(original) && added.containers.size() == size
                        && added.containers.stream().anyMatch(e -> e.dump),
                "groups=" + StorageStore.groups().size() + " active=" + added.name + " size=" + added.containers.size());

        context.runOnClient(client -> SharedGroups.share(client, StorageStore.group(original)));
        context.waitTicks(10);
        long sameName = SharedGroups.list().stream().filter(g -> g.name().equals(original)).count();
        check("G4 sharing again updates it instead of adding a second one", sameName == 1, "entries=" + sameName);

        boolean saved = context.computeOnClient(client -> client.getServer() != null && Files.isRegularFile(client.getServer()
                .getSavePath(net.minecraft.util.WorldSavePath.ROOT).resolve("data").resolve("containerautofill_shared_groups.json")));
        check("G5 shared groups saved in the world folder", saved, "no file");

        context.runOnClient(client -> SharedGroups.unshare(shared.groupId()));
        try {
            context.waitFor(client -> SharedGroups.list().isEmpty(), 100);
        } catch (Throwable ignored) {
        }
        check("G6 Remove stops sharing it", SharedGroups.list().isEmpty(), "list=" + SharedGroups.list());
        String addedName = added.name;
        context.runOnClient(client -> StorageStore.deleteGroup(addedName));
    }

    private SharedGroupPayloads.Summary waitForShared(ClientGameTestContext context, String name) {
        try {
            context.waitFor(client -> SharedGroups.list().stream().anyMatch(g -> g.name().equals(name)), 100);
        } catch (Throwable ignored) {
        }
        return SharedGroups.list().stream().filter(g -> g.name().equals(name)).findFirst().orElse(null);
    }
}
