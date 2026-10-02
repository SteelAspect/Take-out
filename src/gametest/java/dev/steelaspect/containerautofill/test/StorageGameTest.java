/*
 * Container Auto Fill
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
import dev.steelaspect.containerautofill.storage.StorageActions;
import dev.steelaspect.containerautofill.storage.StorageContents;
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
    private BlockPos chestA, chestB, dumpChest, farChest, goldBlock, fillLeft, fillRight, fillHopper;
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
            testDump(context, world);
            testInstantFill(context, world);
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
            AreaSelection area = new AreaSelection();
            area.addSubRegionBox(new Box(fillLeft, fillHopper, "fill"), false);
            area.setExplicitOrigin(fillLeft);
            schematic = LitematicaSchematic.createFromWorld(w, area, new LitematicaSchematic.SchematicSaveInfo(false, true), "steelaspect", msg -> {});
            for (BlockPos p : List.of(fillLeft, fillRight, fillHopper)) ((Inventory) w.getBlockEntity(p)).clear();
            fill(w, fillHopper, Map.of(0, stack(Items.DIRT, 1)));
        });
        context.runOnClient(client -> DataManager.getSchematicPlacementManager()
                .addSchematicPlacement(SchematicPlacement.createFor(schematic, fillLeft, "instant-fill-test", true, true), false));
        world.getServer().runCommand("gamemode survival @a");
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

        lookAt(context, world, chestB);
        context.getInput().pressKey(GLFW.GLFW_KEY_J);
        lookAt(context, world, dumpChest);
        context.getInput().pressKey(GLFW.GLFW_KEY_J);
        context.waitTicks(2);
        check("S2 box select links every container in the box", StorageStore.find(overworld, chestB) != null
                && StorageStore.find(overworld, dumpChest) != null, "linked=" + StorageStore.linkedCount());

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

    private void testLookAtAndGroups(ClientGameTestContext context) {
        context.runOnClient(client -> StorageActions.lookAt(client, stack(Items.IRON_INGOT, 1)));
        check("S10 Look At marks the containers holding the item",
                StorageActions.lookAtTargets().contains(new StorageContents.Key(overworld, chestA)), "targets=" + StorageActions.lookAtTargets());

        String original = StorageStore.activeGroup().name;
        int linked = StorageStore.linkedCount();
        context.runOnClient(client -> StorageStore.createGroup("Second"));
        check("S11 new group becomes active and starts empty", StorageStore.activeGroup().name.equals("Second") && StorageStore.linkedCount() == 0,
                "active=" + StorageStore.activeGroup().name);
        String exported = StorageStore.exportGroup(StorageStore.group(original));
        context.runOnClient(client -> StorageStore.setActive(original));
        check("S11 switching back restores the links", StorageStore.linkedCount() == linked, "count=" + StorageStore.linkedCount());
        context.runOnClient(client -> StorageStore.importGroup(exported));
        check("S12 share/import copies a group", StorageStore.activeGroup().containers.size() == StorageStore.group(original).containers.size(),
                "imported=" + StorageStore.activeGroup().containers.size());
        context.runOnClient(client -> StorageStore.setActive(original));
    }
}
