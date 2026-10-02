/*
 * Container Auto Fill
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.storage;

import dev.steelaspect.containerautofill.config.Configs;
import dev.steelaspect.containerautofill.config.GuiConfigs;
import dev.steelaspect.containerautofill.filler.ItemMatcher;
import fi.dy.masa.malilib.gui.GuiBase;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ElementListWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The linked-storage menu: All Items, Containers, Groups, plus Settings / Refresh / Look At / Sort. */
public class StorageScreen extends Screen {
    private enum Tab { ITEMS, CONTAINERS, GROUPS }

    private static final int TEXT = 0xFFFFFFFF;
    private static final int ACCENT = 0xFF7FE0C0;
    private static final int MUTED = 0xFFA0A0A0;
    private static final int GOLD = 0xFFE0C050;

    private static Tab tab = Tab.ITEMS;
    private static boolean sortByCount;
    private static String search = "";

    private final Screen parent;
    private ItemStack selected = ItemStack.EMPTY;
    private int sourceCount;
    private int refreshTimer;
    private boolean confirmDeleteAll;
    private ItemList itemList;
    private TextFieldWidget groupName;

    public StorageScreen(Screen parent) {
        super(Text.translatable("containerautofill.storage.title"));
        this.parent = parent;
    }

    public static void open(MinecraftClient client) {
        StorageActions.refreshAll();
        client.setScreen(new StorageScreen(client.currentScreen));
    }

    @Override
    protected void init() {
        String[] labels = {
                "containerautofill.storage.tab.items", "containerautofill.storage.tab.containers", "containerautofill.storage.tab.groups",
                "containerautofill.storage.settings", "containerautofill.storage.refresh", "containerautofill.storage.look_at"
        };
        int count = labels.length + 1;
        int gap = 4;
        int buttonWidth = Math.min(150, (this.width - 20 - gap * (count - 1)) / count);
        int x = (this.width - (buttonWidth * count + gap * (count - 1))) / 2;
        int y = 12;
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            ButtonWidget button = ButtonWidget.builder(Text.translatable(labels[i]), b -> onTopButton(index)).dimensions(x, y, buttonWidth, 20).build();
            if (i < 3) button.active = Tab.values()[i] != tab;
            if (i == 5) button.active = tab == Tab.ITEMS && !this.selected.isEmpty();
            this.addDrawableChild(button);
            x += buttonWidth + gap;
        }
        this.addDrawableChild(ButtonWidget.builder(Text.translatable(sortByCount ? "containerautofill.storage.sort_count" : "containerautofill.storage.sort_name"),
                b -> {
                    sortByCount = !sortByCount;
                    this.clearAndInit();
                }).dimensions(x, y, buttonWidth, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), b -> this.close())
                .dimensions(this.width / 2 - 100, this.height - 28, 200, 20).build());

        switch (tab) {
            case ITEMS -> initItems();
            case CONTAINERS -> initContainers();
            case GROUPS -> initGroups();
        }
    }

    private void onTopButton(int index) {
        switch (index) {
            case 0 -> switchTab(Tab.ITEMS);
            case 1 -> switchTab(Tab.CONTAINERS);
            case 2 -> switchTab(Tab.GROUPS);
            case 3 -> GuiBase.openGui(GuiConfigs.forTakeItOut(this));
            case 4 -> {
                StorageActions.refreshAll();
                this.refreshTimer = 10;
            }
            case 5 -> {
                if (!this.selected.isEmpty()) {
                    StorageActions.lookAt(this.client, this.selected);
                    this.close();
                }
            }
            default -> {
            }
        }
    }

    private void switchTab(Tab next) {
        tab = next;
        this.confirmDeleteAll = false;
        this.clearAndInit();
    }

    @Override
    public void close() {
        this.client.setScreen(this.parent);
    }

    @Override
    public void tick() {
        if (++this.refreshTimer % 20 == 0 && tab == Tab.ITEMS && this.itemList != null) {
            this.itemList.rebuild();
        } else if (this.refreshTimer % 20 == 0 && tab == Tab.CONTAINERS) {
            this.clearAndInit();
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 2, TEXT);
        int left = this.width / 2 - panelWidth() / 2;
        switch (tab) {
            case ITEMS -> {
                context.drawTextWithShadow(this.textRenderer, Text.translatable("containerautofill.storage.sources", this.sourceCount), left, 62, ACCENT);
                Text hint = Text.translatable("containerautofill.storage.items_hint");
                context.drawTextWithShadow(this.textRenderer, hint, left + panelWidth() - this.textRenderer.getWidth(hint), 62, MUTED);
                if (!StorageContents.isSupported()) {
                    context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("containerautofill.message.storage_unsupported"),
                            this.width / 2, this.height / 2, GOLD);
                }
            }
            case CONTAINERS -> context.drawTextWithShadow(this.textRenderer, Text.translatable("containerautofill.storage.world",
                    StorageStore.worldKey(), StorageStore.activeGroup().name, StorageStore.linkedCount(), StorageStore.MAX_PER_GROUP), left, 46, ACCENT);
            case GROUPS -> context.drawTextWithShadow(this.textRenderer, Text.translatable("containerautofill.storage.groups"), left, 46, ACCENT);
        }
    }

    private int panelWidth() {
        return Math.min(this.width - 20, tab == Tab.ITEMS ? 340 : 460);
    }

    // ---------------------------------------------------------------- All Items

    private void initItems() {
        int left = this.width / 2 - panelWidth() / 2;
        TextFieldWidget field = new TextFieldWidget(this.textRenderer, left, 40, panelWidth(), 18, Text.translatable("containerautofill.storage.search"));
        field.setPlaceholder(Text.translatable("containerautofill.storage.search").formatted(Formatting.DARK_GRAY));
        field.setText(search);
        field.setChangedListener(text -> {
            search = text;
            if (this.itemList != null) this.itemList.rebuild();
        });
        this.addDrawableChild(field);
        this.itemList = new ItemList(this.client, panelWidth(), this.height - 36 - 74, 74);
        this.itemList.setX(left);
        this.itemList.rebuild();
        this.addDrawableChild(this.itemList);
    }

    private void takeFromList(ItemStack kind, int total, boolean all) {
        int amount = all ? total : Configs.SINGLE_ITEM_MODE.getBooleanValue() ? 1 : kind.getMaxCount();
        StorageActions.take(this.client, kind, Math.min(amount, total));
    }

    private final class ItemList extends AlwaysSelectedEntryListWidget<ItemEntry> {
        ItemList(MinecraftClient client, int width, int height, int y) {
            super(client, width, height, y, 20);
        }

        void rebuild() {
            double scroll = this.getScrollY();
            Map<ItemMatcher.StackKey, Integer> totals = new LinkedHashMap<>();
            int sources = 0;
            for (StorageStore.Entry entry : StorageStore.linkedEntries()) {
                StorageContents.Snapshot snapshot = StorageContents.get(entry.dimensionId(), entry.pos());
                if (snapshot == null || !snapshot.available()) continue;
                sources++;
                for (ItemStack stack : snapshot.items().values()) {
                    totals.merge(new ItemMatcher.StackKey(stack), stack.getCount(), Integer::sum);
                }
            }
            StorageScreen.this.sourceCount = sources;

            String filter = search.toLowerCase(Locale.ROOT).trim();
            List<Map.Entry<ItemMatcher.StackKey, Integer>> rows = new ArrayList<>();
            for (Map.Entry<ItemMatcher.StackKey, Integer> e : totals.entrySet()) {
                if (filter.isEmpty() || e.getKey().stack().getName().getString().toLowerCase(Locale.ROOT).contains(filter)) rows.add(e);
            }
            Comparator<Map.Entry<ItemMatcher.StackKey, Integer>> byName = Comparator.comparing(e -> e.getKey().stack().getName().getString());
            rows.sort(sortByCount ? Comparator.<Map.Entry<ItemMatcher.StackKey, Integer>>comparingInt(Map.Entry::getValue).reversed().thenComparing(byName) : byName);

            this.clearEntries();
            for (Map.Entry<ItemMatcher.StackKey, Integer> row : rows) {
                ItemEntry entry = new ItemEntry(row.getKey().stack(), row.getValue());
                this.addEntry(entry);
                if (!StorageScreen.this.selected.isEmpty() && ItemStack.areItemsAndComponentsEqual(StorageScreen.this.selected, entry.stack)) {
                    this.setSelected(entry);
                }
            }
            this.setScrollY(scroll);
        }

        @Override
        public int getRowWidth() {
            return this.getWidth() - 12;
        }
    }

    private final class ItemEntry extends AlwaysSelectedEntryListWidget.Entry<ItemEntry> {
        private final ItemStack stack;
        private final int count;

        ItemEntry(ItemStack stack, int count) {
            this.stack = stack;
            this.count = count;
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
            int x = this.getContentX();
            int y = this.getContentY();
            context.drawItem(this.stack, x + 2, y + 1);
            context.drawTextWithShadow(StorageScreen.this.textRenderer, this.stack.getName(), x + 24, y + 5, TEXT);
            String amount = "x" + this.count;
            context.drawTextWithShadow(StorageScreen.this.textRenderer, amount,
                    x + this.getContentWidth() - StorageScreen.this.textRenderer.getWidth(amount) - 2, y + 5, ACCENT);
        }

        @Override
        public boolean mouseClicked(Click click, boolean doubled) {
            StorageScreen.this.selected = this.stack;
            StorageScreen.this.itemList.setSelected(this);
            StorageScreen.this.clearAndInitKeepList();
            if (click.button() == 0) {
                takeFromList(this.stack, this.count, click.hasShift());
            }
            return true;
        }

        @Override
        public Text getNarration() {
            return this.stack.getName();
        }
    }

    /** Re-enables the Look At button without rebuilding the item list. */
    private void clearAndInitKeepList() {
        for (Element child : this.children()) {
            if (child instanceof ButtonWidget button && button.getMessage().getContent() instanceof net.minecraft.text.TranslatableTextContent t
                    && t.getKey().equals("containerautofill.storage.look_at")) {
                button.active = !this.selected.isEmpty();
            }
        }
    }

    // ---------------------------------------------------------------- Containers

    private void initContainers() {
        int left = this.width / 2 - panelWidth() / 2;
        this.addDrawableChild(ButtonWidget.builder(Text.translatable(this.confirmDeleteAll ? "containerautofill.storage.delete_all_confirm" : "containerautofill.storage.delete_all"),
                b -> {
                    if (this.confirmDeleteAll) {
                        StorageStore.removeAll();
                        this.confirmDeleteAll = false;
                    } else {
                        this.confirmDeleteAll = true;
                    }
                    this.clearAndInit();
                }).dimensions(left + panelWidth() - 90, 40, 90, 18).build());

        ContainerList list = new ContainerList(this.client, panelWidth(), this.height - 36 - 64, 64);
        list.setX(left);
        for (StorageStore.Entry entry : StorageActions.sortedByDistance(this.client, new ArrayList<>(StorageStore.activeGroup().containers))) {
            list.add(new ContainerRow(entry));
        }
        this.addDrawableChild(list);
    }

    private static final class ContainerList extends ElementListWidget<ContainerRow> {
        ContainerList(MinecraftClient client, int width, int height, int y) {
            super(client, width, height, y, 24);
        }

        void add(ContainerRow row) {
            this.addEntry(row);
        }

        @Override
        public int getRowWidth() {
            return this.getWidth() - 12;
        }
    }

    private final class ContainerRow extends ElementListWidget.Entry<ContainerRow> {
        private final StorageStore.Entry entry;
        private final ButtonWidget toggle;
        private final ButtonWidget delete;

        ContainerRow(StorageStore.Entry entry) {
            this.entry = entry;
            this.toggle = ButtonWidget.builder(Text.translatable(entry.linked ? "containerautofill.storage.unlink" : "containerautofill.storage.link"), b -> {
                entry.linked = !entry.linked;
                StorageStore.save();
                if (entry.linked) StorageContents.requestOne(entry.dimensionId(), entry.pos());
                StorageScreen.this.clearAndInit();
            }).dimensions(0, 0, 60, 20).build();
            this.delete = ButtonWidget.builder(Text.translatable("containerautofill.storage.delete"), b -> {
                StorageStore.remove(entry);
                StorageScreen.this.clearAndInit();
            }).dimensions(0, 0, 60, 20).build();
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
            int x = this.getContentX();
            int y = this.getContentY();
            StorageContents.Snapshot snapshot = StorageContents.get(this.entry.dimensionId(), this.entry.pos());
            String state = this.entry.linked ? "Linked" : "Unlinked";
            String text = state + " | " + this.entry.x + " " + this.entry.y + " " + this.entry.z + " | " + this.entry.dimension
                    + (this.entry.dump ? " | dump" : "") + (snapshot != null && !snapshot.available() ? " | not loaded" : "");
            context.drawTextWithShadow(StorageScreen.this.textRenderer, text, x + 2, y + 6, this.entry.linked ? TEXT : MUTED);
            this.delete.setPosition(x + this.getContentWidth() - 62, y);
            this.toggle.setPosition(x + this.getContentWidth() - 126, y);
            this.toggle.render(context, mouseX, mouseY, deltaTicks);
            this.delete.render(context, mouseX, mouseY, deltaTicks);
        }

        @Override
        public List<? extends Element> children() {
            return List.of(this.toggle, this.delete);
        }

        @Override
        public List<? extends Selectable> selectableChildren() {
            return List.of(this.toggle, this.delete);
        }
    }

    // ---------------------------------------------------------------- Groups

    private void initGroups() {
        int left = this.width / 2 - panelWidth() / 2;
        int right = left + panelWidth();
        this.groupName = new TextFieldWidget(this.textRenderer, right - 330, 40, 140, 18, Text.translatable("containerautofill.storage.group_name"));
        this.groupName.setPlaceholder(Text.translatable("containerautofill.storage.group_name").formatted(Formatting.DARK_GRAY));
        this.addDrawableChild(this.groupName);
        this.addDrawableChild(ButtonWidget.builder(Text.translatable("containerautofill.storage.new_group"), b -> {
            StorageStore.createGroup(this.groupName.getText());
            this.clearAndInit();
        }).dimensions(right - 186, 40, 90, 18).build());
        this.addDrawableChild(ButtonWidget.builder(Text.translatable("containerautofill.storage.import"), b -> {
            StorageStore.Group imported = StorageStore.importGroup(this.client.keyboard.getClipboard());
            if (this.client.player != null) {
                this.client.player.sendMessage(imported == null ? Text.translatable("containerautofill.message.storage_import_failed")
                        : Text.translatable("containerautofill.message.storage_imported", imported.name, imported.containers.size()), true);
            }
            StorageActions.refreshAll();
            this.clearAndInit();
        }).dimensions(right - 92, 40, 92, 18).build());

        GroupList list = new GroupList(this.client, panelWidth(), this.height - 36 - 64, 64);
        list.setX(left);
        for (StorageStore.Group group : StorageStore.groups()) list.add(new GroupRow(group));
        this.addDrawableChild(list);
    }

    private static final class GroupList extends ElementListWidget<GroupRow> {
        GroupList(MinecraftClient client, int width, int height, int y) {
            super(client, width, height, y, 24);
        }

        void add(GroupRow row) {
            this.addEntry(row);
        }

        @Override
        public int getRowWidth() {
            return this.getWidth() - 12;
        }
    }

    private final class GroupRow extends ElementListWidget.Entry<GroupRow> {
        private final StorageStore.Group group;
        private final ButtonWidget use;
        private final ButtonWidget share;
        private final ButtonWidget delete;

        GroupRow(StorageStore.Group group) {
            this.group = group;
            boolean active = StorageStore.activeGroup() == group;
            this.use = ButtonWidget.builder(Text.translatable("containerautofill.storage.use"), b -> {
                StorageStore.setActive(group.name);
                StorageActions.refreshAll();
                StorageScreen.this.clearAndInit();
            }).dimensions(0, 0, 50, 20).build();
            this.use.active = !active;
            this.share = ButtonWidget.builder(Text.translatable("containerautofill.storage.share"), b -> {
                StorageScreen.this.client.keyboard.setClipboard(StorageStore.exportGroup(group));
                if (StorageScreen.this.client.player != null) {
                    StorageScreen.this.client.player.sendMessage(Text.translatable("containerautofill.message.storage_shared", group.name), true);
                }
            }).dimensions(0, 0, 50, 20).build();
            this.delete = ButtonWidget.builder(Text.translatable("containerautofill.storage.delete"), b -> {
                StorageStore.deleteGroup(group.name);
                StorageScreen.this.clearAndInit();
            }).dimensions(0, 0, 50, 20).build();
            this.delete.active = StorageStore.groups().size() > 1;
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
            int x = this.getContentX();
            int y = this.getContentY();
            boolean active = StorageStore.activeGroup() == this.group;
            if (active) {
                context.fill(x, y - 1, x + this.getContentWidth(), y + 21, 0x6022A0B0);
                context.fill(x, y - 1, x + 2, y + 21, 0xFF22D0E0);
            }
            String label = (active ? "(active) " : "") + this.group.name + "  (" + this.group.containers.size() + ")";
            context.drawTextWithShadow(StorageScreen.this.textRenderer, label, x + 6, y + 6, active ? ACCENT : TEXT);
            int right = x + this.getContentWidth();
            this.delete.setPosition(right - 52, y);
            this.share.setPosition(right - 104, y);
            this.use.setPosition(right - 156, y);
            this.use.render(context, mouseX, mouseY, deltaTicks);
            this.share.render(context, mouseX, mouseY, deltaTicks);
            this.delete.render(context, mouseX, mouseY, deltaTicks);
        }

        @Override
        public List<? extends Element> children() {
            return List.of(this.use, this.share, this.delete);
        }

        @Override
        public List<? extends Selectable> selectableChildren() {
            return List.of(this.use, this.share, this.delete);
        }
    }
}
