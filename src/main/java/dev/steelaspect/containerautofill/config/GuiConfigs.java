/*
 * Container Auto Fill
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.config;

import dev.steelaspect.containerautofill.Reference;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.gui.screen.Screen;

import java.util.List;

public class GuiConfigs extends GuiConfigsBase {
    private static Tab tab = Tab.GENERIC;

    public GuiConfigs(Screen parent) {
        super(10, 50, Reference.MOD_ID, parent, Reference.MOD_ID + ".gui.title.configs");
    }

    public GuiConfigs() {
        this(null);
    }

    @Override
    public void initGui() {
        super.initGui();
        this.clearOptions();

        int x = 10;
        int y = 26;
        for (Tab t : Tab.values()) {
            String label = StringUtils.translate(t.translationKey);
            ButtonGeneric button = new ButtonGeneric(x, y, -1, 20, label);
            button.setEnabled(t != tab);
            this.addButton(button, new TabListener(t, this));
            x += button.getWidth() + 2;
        }
    }

    @Override
    protected int getConfigWidth() {
        return tab == Tab.HOTKEYS ? 204 : 120;
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        List<? extends IConfigBase> configs = switch (tab) {
            case GENERIC -> Configs.GENERIC;
            case HIGHLIGHT -> Configs.HIGHLIGHT;
            case HOTKEYS -> Configs.HOTKEYS;
        };
        return ConfigOptionWrapper.createFor(configs);
    }

    private enum Tab {
        GENERIC(Reference.MOD_ID + ".gui.button.config_gui.generic"),
        HIGHLIGHT(Reference.MOD_ID + ".gui.button.config_gui.highlight"),
        HOTKEYS(Reference.MOD_ID + ".gui.button.config_gui.hotkeys");

        private final String translationKey;

        Tab(String translationKey) {
            this.translationKey = translationKey;
        }
    }

    private record TabListener(Tab tab, GuiConfigs parent) implements IButtonActionListener {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton) {
            GuiConfigs.tab = this.tab;
            this.parent.reCreateListWidget();
            this.parent.getListWidget().resetScrollbarPosition();
            this.parent.initGui();
        }
    }
}
