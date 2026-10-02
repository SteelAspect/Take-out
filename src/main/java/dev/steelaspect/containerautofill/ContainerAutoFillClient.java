/*
 * Container Auto Fill
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill;

import dev.steelaspect.containerautofill.config.Configs;
import dev.steelaspect.containerautofill.config.GuiConfigs;
import dev.steelaspect.containerautofill.filler.AutoFillController;
import dev.steelaspect.containerautofill.filler.ContainerTracker;
import dev.steelaspect.containerautofill.input.InputHandler;
import dev.steelaspect.containerautofill.takeitout.ShulkerRetriever;
import dev.steelaspect.containerautofill.takeitout.TakeItOutFeatures;
import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.event.InitializationHandler;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.event.RenderEventHandler;
import dev.steelaspect.containerautofill.highlight.ContainerHighlighter;
import dev.steelaspect.containerautofill.highlight.HighlightRenderer;
import dev.steelaspect.containerautofill.highlight.RealContainerCache;
import fi.dy.masa.malilib.registry.Registry;
import fi.dy.masa.malilib.util.data.ModInfo;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;

public class ContainerAutoFillClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        InitializationHandler.getInstance().registerInitializationHandler(() -> {
            Configs.INSTANCE.load();
            ConfigManager.getInstance().registerConfigHandler(Reference.MOD_ID, Configs.INSTANCE);
            Registry.CONFIG_SCREEN.registerConfigScreenFactory(new ModInfo(Reference.MOD_ID, Reference.MOD_NAME, GuiConfigs::new));
            InputEventHandler.getKeybindManager().registerKeybindProvider(InputHandler.getInstance());
            RenderEventHandler.getInstance().registerWorldLastRenderer(HighlightRenderer.INSTANCE);
        });

        ClientTickEvents.END_CLIENT_TICK.register(ContainerAutoFillClient::onEndTick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            AutoFillController.reset();
            ShulkerRetriever.reset();
            ContainerHighlighter.reset();
        });
    }

    private static void onEndTick(MinecraftClient client) {
        if (!Configs.ENABLE_MOD.getBooleanValue()) {
            if (AutoFillController.isRunning()) AutoFillController.reset();
            return;
        }
        ContainerTracker.tick(client);
        ShulkerRetriever.tick(client);
        AutoFillController.tick(client);
        TakeItOutFeatures.tick(client);
        RealContainerCache.tick(client);
        ContainerHighlighter.tick(client);
    }
}
