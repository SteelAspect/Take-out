/*
 * Container Auto Fill
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.input;

import dev.steelaspect.containerautofill.Reference;
import dev.steelaspect.containerautofill.config.Configs;
import dev.steelaspect.containerautofill.config.GuiConfigs;
import dev.steelaspect.containerautofill.filler.AutoFillController;
import dev.steelaspect.containerautofill.takeitout.TakeItOutFeatures;
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
        Configs.AUTO_TAKE_OUT.getKeybind().setCallback(this);
    }

    public static InputHandler getInstance() {
        return INSTANCE;
    }

    @Override
    public void addKeysToMap(IKeybindManager manager) {
        for (ConfigHotkey hotkey : Configs.HOTKEYS) {
            manager.addKeybindToMap(hotkey.getKeybind());
        }
        manager.addKeybindToMap(Configs.AUTO_TAKE_OUT.getKeybind());
    }

    @Override
    public void addHotkeys(IKeybindManager manager) {
        List<fi.dy.masa.malilib.hotkeys.IHotkey> all = new ArrayList<>(Configs.HOTKEYS);
        all.add(Configs.AUTO_TAKE_OUT);
        manager.addHotkeysForCategory(Reference.MOD_NAME, Reference.MOD_ID + ".hotkeys.category.generic", all);
    }

    @Override
    public boolean onKeyAction(KeyAction action, IKeybind key) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (key == Configs.OPEN_CONFIG_GUI.getKeybind()) {
            GuiBase.openGui(new GuiConfigs());
            return true;
        }
        if (!Configs.ENABLE_MOD.getBooleanValue() || client.player == null) {
            return false;
        }
        if (key == Configs.AUTO_TAKE_OUT.getKeybind()) {
            TakeItOutFeatures.toggleAutoTakeOut(client);
            return true;
        }
        if (key == Configs.AUTO_FILL_OPEN_CONTAINER.getKeybind()) {
            AutoFillController.fillOpenContainer(client);
            return true;
        }
        if (key == Configs.FILL_LOOKED_AT_CONTAINER.getKeybind()) {
            AutoFillController.fillLookedAtContainer(client);
            return true;
        }
        return false;
    }
}
