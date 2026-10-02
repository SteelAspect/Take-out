/*
 * Container Auto Fill
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.test;

import dev.steelaspect.containerautofill.config.GuiConfigs;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;

/** Screenshots of each config tab, for documentation. */
public class MenuScreenshotsGameTest implements FabricClientGameTest {
    private static final Logger LOG = LoggerFactory.getLogger("ContainerAutoFillTest");

    @Override
    public void runTest(ClientGameTestContext context) {
        for (String tab : new String[]{"GENERIC", "TAKEITOUT", "HIGHLIGHT", "RESTOCK", "HOTKEYS"}) {
            context.runOnClient(client -> {
                setTab(tab);
                client.setScreen(new GuiConfigs());
            });
            context.waitTicks(10);
            LOG.info("Menu screenshot: {}", context.takeScreenshot(TestScreenshotOptions.of("menu-" + tab.toLowerCase())));
        }
        context.runOnClient(client -> client.setScreen(null));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setTab(String name) {
        try {
            Field field = GuiConfigs.class.getDeclaredField("tab");
            field.setAccessible(true);
            Class<? extends Enum> type = (Class<? extends Enum>) field.getType();
            field.set(null, Enum.valueOf(type, name));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
