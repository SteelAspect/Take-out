/*
 * Container Auto Fill
 * Copyright (C) 2026 steelaspect
 * SPDX-License-Identifier: LGPL-3.0-only
 */
package dev.steelaspect.containerautofill.config;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.steelaspect.containerautofill.Reference;
import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigBooleanHotkeyed;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.hotkeys.KeybindSettings;
import fi.dy.masa.malilib.util.data.json.JsonUtils;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Configs implements IConfigHandler {
    public static final Configs INSTANCE = new Configs();
    private static final String CONFIG_FILE_NAME = Reference.MOD_ID + ".json";
    private static final String PREFIX = Reference.MOD_ID + ".config";

    // --- Generic ---
    public static final ConfigBoolean ENABLE_MOD = new ConfigBoolean("enableMod", true).apply(PREFIX);
    public static final ConfigInteger CLICK_DELAY = new ConfigInteger("clickDelay", 1, 1, 40).apply(PREFIX);
    public static final ConfigBoolean CLEAR_WRONG_ITEMS = new ConfigBoolean("clearWrongItems", false).apply(PREFIX);
    public static final ConfigBoolean USE_TAKEITOUT_SOURCES = new ConfigBoolean("useTakeItOutSources", true).apply(PREFIX);
    public static final ConfigBoolean MATCH_SHULKER_BOXES_BY_CONTENT = new ConfigBoolean("matchShulkerBoxesByContent", false).apply(PREFIX);
    public static final ConfigBoolean CLOSE_AFTER_LOOK_FILL = new ConfigBoolean("closeAfterLookFill", true).apply(PREFIX);
    public static final ConfigBoolean SHULKER_PICK_BLOCK = new ConfigBoolean("shulkerPickBlock", true).apply(PREFIX);
    public static final ConfigBooleanHotkeyed AUTO_TAKE_OUT = new ConfigBooleanHotkeyed("autoTakeOut", false, "R").apply(PREFIX);
    public static final ConfigBoolean DEBUG_LOGGING = new ConfigBoolean("debugLogging", false).apply(PREFIX);

    public static final ImmutableList<IConfigBase> GENERIC = ImmutableList.of(
            ENABLE_MOD,
            CLICK_DELAY,
            CLEAR_WRONG_ITEMS,
            USE_TAKEITOUT_SOURCES,
            MATCH_SHULKER_BOXES_BY_CONTENT,
            CLOSE_AFTER_LOOK_FILL,
            SHULKER_PICK_BLOCK,
            AUTO_TAKE_OUT,
            DEBUG_LOGGING
    );

    // --- Hotkeys ---
    /** Fills the container whose screen is currently open. Unbound by default; only fires inside a GUI. */
    public static final ConfigHotkey AUTO_FILL_OPEN_CONTAINER = new ConfigHotkey("autoFillOpenContainer", "", KeybindSettings.GUI).apply(PREFIX);
    /** Opens and fills the schematic container under the crosshair. */
    public static final ConfigHotkey FILL_LOOKED_AT_CONTAINER = new ConfigHotkey("fillLookedAtContainer", "V").apply(PREFIX);
    public static final ConfigHotkey OPEN_CONFIG_GUI = new ConfigHotkey("openConfigGui", "L,C").apply(PREFIX);

    public static final List<ConfigHotkey> HOTKEYS = ImmutableList.of(
            AUTO_FILL_OPEN_CONTAINER,
            FILL_LOOKED_AT_CONTAINER,
            OPEN_CONFIG_GUI
    );

    private Configs() {
    }

    public static boolean debug() {
        return DEBUG_LOGGING.getBooleanValue();
    }

    public static void debug(String message, Object... args) {
        if (debug()) {
            Reference.LOGGER.info("[debug] " + message, args);
        }
    }

    @Override
    public void load() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);
        if (Files.isRegularFile(file) && Files.isReadable(file)) {
            JsonElement element = JsonUtils.parseJsonFile(file);
            if (element != null && element.isJsonObject()) {
                JsonObject root = element.getAsJsonObject();
                ConfigUtils.readConfigBase(root, "Generic", GENERIC);
                ConfigUtils.readConfigBase(root, "Hotkeys", HOTKEYS);
            }
        }
    }

    @Override
    public void save() {
        Path dir = FabricLoader.getInstance().getConfigDir();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            Reference.LOGGER.error("Could not create config directory {}", dir, e);
            return;
        }
        JsonObject root = new JsonObject();
        ConfigUtils.writeConfigBase(root, "Generic", GENERIC);
        ConfigUtils.writeConfigBase(root, "Hotkeys", HOTKEYS);
        JsonUtils.writeJsonToFile(root, dir.resolve(CONFIG_FILE_NAME));
    }
}
