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
import dev.steelaspect.containerautofill.network.RestockPayloads;
import dev.steelaspect.containerautofill.restock.Restock;
import dev.steelaspect.containerautofill.takeitout.HotbarRefill;
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
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import dev.steelaspect.containerautofill.network.FillPayloads;
import dev.steelaspect.containerautofill.filler.InstantFill;
import dev.steelaspect.containerautofill.network.StoragePayloads;
import dev.steelaspect.containerautofill.storage.StorageActions;
import dev.steelaspect.containerautofill.storage.StorageContents;
import dev.steelaspect.containerautofill.storage.StorageRetriever;
import dev.steelaspect.containerautofill.storage.StorageStore;
import net.minecraft.client.MinecraftClient;

public class ContainerAutoFillClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        if (!ClientRequirements.met()) return; // Litematica / MaLiLib missing: ClientRequirements reports it
        InitializationHandler.getInstance().registerInitializationHandler(() -> {
            Configs.INSTANCE.load();
            ConfigManager.getInstance().registerConfigHandler(Reference.MOD_ID, Configs.INSTANCE);
            Registry.CONFIG_SCREEN.registerConfigScreenFactory(new ModInfo(Reference.MOD_ID, Reference.MOD_NAME, GuiConfigs::new));
            InputEventHandler.getKeybindManager().registerKeybindProvider(InputHandler.getInstance());
            RenderEventHandler.getInstance().registerWorldLastRenderer(HighlightRenderer.INSTANCE);
        });

        ClientTickEvents.END_CLIENT_TICK.register(ContainerAutoFillClient::onEndTick);
        ClientPlayNetworking.registerGlobalReceiver(StoragePayloads.Contents.ID, (payload, context) -> StorageContents.onContents(payload));
        ClientPlayNetworking.registerGlobalReceiver(RestockPayloads.Result.ID, (payload, context) -> Restock.onResult(context.client(), payload));
        ClientPlayNetworking.registerGlobalReceiver(StoragePayloads.Taken.ID, (payload, context) -> {
            switch (StorageRetriever.onTaken(context.client(), payload)) {
                case ARRIVED -> TakeItOutFeatures.onStorageArrived(context.client());
                case MISSED -> TakeItOutFeatures.onStorageMiss(context.client());
                case NOT_OURS -> {
                }
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(FillPayloads.Result.ID, (payload, context) -> InstantFill.onResult(context.client(), payload));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> StorageStore.load(client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            AutoFillController.reset();
            ShulkerRetriever.reset();
            ContainerHighlighter.reset();
            StorageActions.reset();
            InstantFill.reset();
            StorageRetriever.reset();
            TakeItOutFeatures.reset();
            HotbarRefill.reset();
            Restock.reset();
            StorageContents.clear();
            StorageStore.unload();
        });
    }

    private static void onEndTick(MinecraftClient client) {
        // Restock has its own on/off switch on its own config page and runs even with the rest of the mod off.
        Restock.tick(client);
        if (!Configs.ENABLE_MOD.getBooleanValue()) {
            if (AutoFillController.isRunning()) AutoFillController.reset();
            return;
        }
        ContainerTracker.tick(client);
        ShulkerRetriever.tick(client);
        AutoFillController.tick(client);
        InstantFill.tick(client);
        TakeItOutFeatures.tick(client);
        HotbarRefill.tick(client);
        RealContainerCache.tick(client);
        StorageRetriever.tick(client);
        StorageActions.tick(client);
        ContainerHighlighter.tick(client);
    }
}
