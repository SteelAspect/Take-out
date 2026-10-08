package dev.steelaspect.containerautofill.test;

import dev.steelaspect.containerautofill.highlight.RealContainerCache;
import dev.steelaspect.containerautofill.materials.ContainerMaterialList;
import dev.steelaspect.containerautofill.materials.MaterialListModes;
import dev.steelaspect.containerautofill.mixin.GuiBaseAccessor;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListPlacement;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The material list "Contents" button: Blocks / Containers / Both. A schematic of a chest (stone + a shulker box of
 * redstone) and a barrel (diamonds); the real chest already has some stone, the barrel is empty.
 */
public class MaterialListGameTest implements FabricClientGameTest {
    private static final Logger LOG = LoggerFactory.getLogger("MaterialListTest");
    private final List<String> failures = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getClientWorld().waitForChunksRender();
            BlockPos base = world.getServer().computeOnServer(server -> server.getPlayerManager().getPlayerList().get(0).getBlockPos()).add(3, 0, 3);
            BlockPos chest = base;
            BlockPos barrel = base.add(2, 0, 0);
            LitematicaSchematic schematic = world.getServer().computeOnServer(server -> {
                ServerWorld w = server.getOverworld();
                w.setBlockState(chest, Blocks.CHEST.getDefaultState());
                w.setBlockState(barrel, Blocks.BARREL.getDefaultState());
                fill(w, chest, Map.of(0, new ItemStack(Items.STONE, 10), 1, shulkerWith(new ItemStack(Items.REDSTONE, 64), new ItemStack(Items.REDSTONE, 64))));
                fill(w, barrel, Map.of(4, new ItemStack(Items.DIAMOND, 5)));
                AreaSelection area = new AreaSelection();
                area.addSubRegionBox(new Box(chest, barrel, "main"), false);
                area.setExplicitOrigin(base);
                LitematicaSchematic s = LitematicaSchematic.createFromWorld(w, area, new LitematicaSchematic.SchematicSaveInfo(false, true),
                        "steelaspect", msg -> LOG.warn("schematic: {}", msg));
                ((Inventory) w.getBlockEntity(chest)).clear();
                ((Inventory) w.getBlockEntity(barrel)).clear();
                fill(w, chest, Map.of(3, new ItemStack(Items.STONE, 4)));
                return s;
            });
            check("schematic created", schematic != null, "");
            context.runOnClient(client -> {
                SchematicPlacement placement = SchematicPlacement.createFor(schematic, base, "material-test", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
                RealContainerCache.refreshFromIntegratedServer(client, List.of(chest, barrel));
                MaterialListBase blocks = new MaterialListPlacement(placement, true);
                DataManager.setMaterialList(blocks);
                blocks.getHudRenderer().toggleShouldRender();
                fi.dy.masa.litematica.render.infohud.InfoHud.getInstance().addInfoHudRenderer(blocks.getHudRenderer(), true);
                GuiBase.openGui(new GuiMaterialList(blocks));
            });
            context.waitTicks(20);

            String label = context.computeOnClient(client -> {
                for (ButtonBase b : ((GuiBaseAccessor) client.currentScreen).containerautofill$getButtons()) {
                    String text = label(b);
                    if (text != null && text.startsWith("Contents: ")) return text;
                }
                return null;
            });
            check("button shown in placement material list", "Contents: Blocks".equals(label), "label=" + label);

            // Blocks -> Containers
            context.runOnClient(client -> MaterialListModes.next(DataManager.getMaterialList(), false));
            context.waitTicks(10);
            context.runOnClient(client -> GuiBase.openGui(new GuiMaterialList(DataManager.getMaterialList())));
            context.waitTicks(10);
            context.takeScreenshot("material-list-containers");
            context.runOnClient(client -> {
                MaterialListBase list = DataManager.getMaterialList();
                check("containers list active", list instanceof ContainerMaterialList c && c.getMode() == ContainerMaterialList.Mode.CONTAINERS, String.valueOf(list));
                check("HUD stays on after switching", list.getHudRenderer().getShouldRenderCustom(), "");
                expect(list, Items.STONE, 10, 6);
                expect(list, Items.REDSTONE, 128, 128);
                expect(list, Items.WHITE_SHULKER_BOX, 1, 1);
                expect(list, Items.DIAMOND, 5, 5);
                check("no blocks in containers list", find(list, Items.CHEST) == null && find(list, Items.BARREL) == null, describe(list));
            });

            // Containers -> Both (Litematica counts the blocks asynchronously)
            context.runOnClient(client -> MaterialListModes.next(DataManager.getMaterialList(), false));
            context.waitFor(client -> find(DataManager.getMaterialList(), Items.CHEST) != null, 200);
            context.runOnClient(client -> client.setScreen(null));
            context.waitTicks(40);
            context.takeScreenshot("material-list-hud-both");
            context.runOnClient(client -> {
                MaterialListBase list = DataManager.getMaterialList();
                check("both list active", list instanceof ContainerMaterialList c && c.getMode() == ContainerMaterialList.Mode.BOTH, String.valueOf(list));
                expect(list, Items.STONE, 10, 6);
                expect(list, Items.DIAMOND, 5, 5);
                check("both has the chest block", find(list, Items.CHEST) != null, describe(list));
                check("HUD still on", list.getHudRenderer().getShouldRenderCustom(), "");
            });

            // Both -> Blocks, and right click goes backwards.
            context.runOnClient(client -> MaterialListModes.next(DataManager.getMaterialList(), false));
            context.runOnClient(client -> check("back to blocks", !(DataManager.getMaterialList() instanceof ContainerMaterialList)
                    && DataManager.getMaterialList() instanceof MaterialListPlacement, ""));
            context.runOnClient(client -> MaterialListModes.next(DataManager.getMaterialList(), true));
            context.runOnClient(client -> check("right click goes Blocks -> Both",
                    DataManager.getMaterialList() instanceof ContainerMaterialList c && c.getMode() == ContainerMaterialList.Mode.BOTH, ""));
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " check(s) failed: " + String.join(" | ", failures));
        }
        LOG.info("material list test passed");
    }

    /** ButtonBase has no getter for its label. */
    private static String label(ButtonBase button) {
        for (Class<?> c = button.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField("displayString");
                f.setAccessible(true);
                return (String) f.get(button);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    private static ItemStack shulkerWith(ItemStack... inner) {
        ItemStack box = new ItemStack(Items.WHITE_SHULKER_BOX);
        DefaultedList<ItemStack> contents = DefaultedList.ofSize(27, ItemStack.EMPTY);
        for (int i = 0; i < inner.length; i++) contents.set(i, inner[i]);
        box.set(DataComponentTypes.CONTAINER, ContainerComponent.fromStacks(contents));
        return box;
    }

    private static void fill(ServerWorld w, BlockPos pos, Map<Integer, ItemStack> items) {
        Inventory inv = (Inventory) w.getBlockEntity(pos);
        items.forEach((slot, s) -> inv.setStack(slot, s.copy()));
        inv.markDirty();
    }

    private static MaterialListEntry find(MaterialListBase list, Item item) {
        if (list == null) return null;
        for (MaterialListEntry e : list.getMaterialsAll()) {
            if (e.getStack().isOf(item)) return e;
        }
        return null;
    }

    private void expect(MaterialListBase list, Item item, int total, int missing) {
        MaterialListEntry e = find(list, item);
        check(item + " total/missing", e != null && e.getCountTotal() == total && e.getCountMissing() == missing,
                e == null ? "missing entry, list=" + describe(list) : "total=" + e.getCountTotal() + " missing=" + e.getCountMissing());
    }

    private static String describe(MaterialListBase list) {
        StringBuilder sb = new StringBuilder();
        for (MaterialListEntry e : list.getMaterialsAll()) sb.append(e.getStack().getItem()).append('=').append(e.getCountTotal()).append('/').append(e.getCountMissing()).append(' ');
        return sb.toString();
    }

    private void check(String name, boolean ok, String detail) {
        if (ok) {
            LOG.info("PASS {}", name);
        } else {
            LOG.error("FAIL {}: {}", name, detail);
            failures.add(name + ": " + detail);
        }
    }
}
