/*
 * Cytra Container
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.input;

import dev.steelaspect.containerautofill.Reference;
import dev.steelaspect.containerautofill.config.Configs;
import dev.steelaspect.containerautofill.config.GuiConfigs;
import dev.steelaspect.containerautofill.filler.AutoFillController;
import dev.steelaspect.containerautofill.storage.StorageActions;
import dev.steelaspect.containerautofill.storage.StorageScreen;
import dev.steelaspect.containerautofill.takeitout.TakeItOutFeatures;
import fi.dy.masa.malilib.config.options.ConfigBooleanHotkeyed;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.hotkeys.IHotkeyCallback;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.IKeybindManager;
import fi.dy.masa.malilib.hotkeys.IKeybindProvider;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.List;

public final class InputHandler implements IKeybindProvider, IHotkeyCallback {
    private static final InputHandler INSTANCE = new InputHandler();

    private InputHandler() {
        for (ConfigHotkey hotkey : Configs.HOTKEYS) {
            hotkey.getKeybind().setCallback(this);
        }
        for (ConfigBooleanHotkeyed toggle : Configs.TOGGLES) {
            toggle.getKeybind().setCallback(this);
        }
    }

    public static InputHandler getInstance() {
        return INSTANCE;
    }

    @Override
    public void addKeysToMap(IKeybindManager manager) {
        for (ConfigHotkey hotkey : Configs.HOTKEYS) {
            manager.addKeybindToMap(hotkey.getKeybind());
        }
        for (ConfigBooleanHotkeyed toggle : Configs.TOGGLES) {
            manager.addKeybindToMap(toggle.getKeybind());
        }
    }

    @Override
    public void addHotkeys(IKeybindManager manager) {
        List<fi.dy.masa.malilib.hotkeys.IHotkey> all = new ArrayList<>(Configs.HOTKEYS);
        all.addAll(Configs.TOGGLES);
        manager.addHotkeysForCategory(Reference.MOD_NAME, Reference.MOD_ID + ".hotkeys.category.generic", all);
    }

    @Override
    public boolean onKeyAction(KeyAction action, IKeybind key) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (key == Configs.OPEN_CONFIG_GUI.getKeybind()) {
            GuiBase.openGui(new GuiConfigs());
            return true;
        }
        if (key == Configs.RESTOCK_ENABLED.getKeybind() && client.player != null) {
            dev.steelaspect.containerautofill.restock.Restock.toggle(client);
            return true;
        }
        if (!Configs.ENABLE_MOD.getBooleanValue() || client.player == null) {
            return false;
        }
        if (key == Configs.TAKEITOUT_ENABLED.getKeybind() || key == Configs.SINGLE_ITEM_MODE.getKeybind()
                || key == Configs.LINKED_OUTLINES.getKeybind() || key == Configs.HOTBAR_REFILL.getKeybind()) {
            ConfigBooleanHotkeyed option = key == Configs.TAKEITOUT_ENABLED.getKeybind() ? Configs.TAKEITOUT_ENABLED
                    : key == Configs.SINGLE_ITEM_MODE.getKeybind() ? Configs.SINGLE_ITEM_MODE
                    : key == Configs.LINKED_OUTLINES.getKeybind() ? Configs.LINKED_OUTLINES : Configs.HOTBAR_REFILL;
            option.toggleBooleanValue();
            client.player.sendMessage(net.minecraft.text.Text.translatable(option.getBooleanValue()
                    ? "containerautofill.message.option_on" : "containerautofill.message.option_off", option.getPrettyName()), true);
            return true;
        }
        if (key == Configs.OPEN_STORAGE_MENU.getKeybind()) {
            StorageScreen.open(client);
            return true;
        }
        if (key == Configs.LINK_LOOKED_AT.getKeybind()) {
            StorageActions.toggleLookedAt(client);
            return true;
        }
        if (key == Configs.BOX_SELECT_CORNER.getKeybind()) {
            StorageActions.boxSelectCorner(client);
            return true;
        }
        if (key == Configs.MARK_DUMP_CONTAINER.getKeybind()) {
            StorageActions.toggleDump(client);
            return true;
        }
        if (key == Configs.DUMP_TO_CONTAINERS.getKeybind()) {
            StorageActions.dumpInventory(client);
            return true;
        }
        if (key == Configs.HIGHLIGHT_CONTAINERS.getKeybind()) {
            Configs.HIGHLIGHT_CONTAINERS.toggleBooleanValue();
            String messageKey = Configs.HIGHLIGHT_CONTAINERS.getBooleanValue()
                    ? "containerautofill.message.highlight_on" : "containerautofill.message.highlight_off";
            client.player.sendMessage(net.minecraft.text.Text.translatable(messageKey), true);
            return true;
        }
        if (key == Configs.AUTO_FILL_OPEN_CONTAINER.getKeybind()) {
            AutoFillController.fillOpenContainer(client);
            return true;
        }
        if (key == Configs.AREA_FILL.getKeybind()) {
            AutoFillController.fillArea(client);
            return true;
        }
        if (key == Configs.FILL_LOOKED_AT_CONTAINER.getKeybind()) {
            AutoFillController.fillLookedAtContainer(client);
            return true;
        }
        return false;
    }
}
