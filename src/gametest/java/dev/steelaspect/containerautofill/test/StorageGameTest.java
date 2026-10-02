/*
 * Container Auto Fill
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.test;

import dev.steelaspect.containerautofill.config.Configs;
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
    private BlockPos chestA, chestB, dumpChest, farChest, goldBlock;
    private Identifier overworld;

    @Override
    public void runTest(ClientGameTestContext context) {
        context.runOnClient(client -> {
            Configs.BOX_SELECT_CORNER.setValueFromString("J");
            Configs.MARK_DUMP_CONTAINER.setValueFromString("U");
            Configs.DUMP_TO_CONTAINERS.setValueFromString("N");
            Configs.CLICK_DELAY.setIntegerValue(1);
            InputEventHandler.getKeybindManager().updateUsedKeys();
        });

        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getClientWorld().waitForChunksRender();
            setup(context, world);
            testLinkingHotkeys(context, world);
            testMenuScreenshots(context, world);
            testRemoteTake(context, world);
            testPickBlockFromStorage(context, world);
            testDump(context, world);
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
        });
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
