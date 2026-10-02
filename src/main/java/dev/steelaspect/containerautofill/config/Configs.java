/*
 * Cytra Container
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
import fi.dy.masa.malilib.config.options.ConfigColor;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.config.options.ConfigString;
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
    public static final ConfigBoolean INSTANT_FILL = new ConfigBoolean("instantFill", true).apply(PREFIX);
    public static final ConfigBoolean CREATIVE_FILL = new ConfigBoolean("creativeFill", true).apply(PREFIX);
    public static final ConfigInteger AREA_FILL_RANGE = new ConfigInteger("areaFillRange", 16, 1, 64).apply(PREFIX);
    public static final ConfigBoolean USE_TAKEITOUT_SOURCES = new ConfigBoolean("useTakeItOutSources", true).apply(PREFIX);
    public static final ConfigBoolean MATCH_SHULKER_BOXES_BY_CONTENT = new ConfigBoolean("matchShulkerBoxesByContent", false).apply(PREFIX);
    public static final ConfigBoolean CLOSE_AFTER_LOOK_FILL = new ConfigBoolean("closeAfterLookFill", true).apply(PREFIX);
    public static final ConfigBoolean SHULKER_PICK_BLOCK = new ConfigBoolean("shulkerPickBlock", true).apply(PREFIX);
    public static final ConfigBooleanHotkeyed AUTO_TAKE_OUT = new ConfigBooleanHotkeyed("autoTakeOut", false, "R").apply(PREFIX);
    public static final ConfigBoolean DEBUG_LOGGING = new ConfigBoolean("debugLogging", false).apply(PREFIX);

    // --- Linked storage (TakeItOut tab) ---
    public static final ConfigBooleanHotkeyed SINGLE_ITEM_MODE = new ConfigBooleanHotkeyed("singleItemMode", false, "B").apply(PREFIX);
    public static final ConfigBooleanHotkeyed HOTBAR_REFILL = new ConfigBooleanHotkeyed("hotbarRefill", true, "").apply(PREFIX);
    public static final ConfigInteger SINGLE_ITEM_BUFFER = new ConfigInteger("singleItemBuffer", 3, 1, 16).apply(PREFIX);
    public static final ConfigBoolean USE_LINKED_CONTAINERS = new ConfigBoolean("useLinkedContainers", true).apply(PREFIX);
    public static final ConfigBooleanHotkeyed LINKED_OUTLINES = new ConfigBooleanHotkeyed("linkedOutlines", true, "").apply(PREFIX);
    public static final ConfigBoolean LINKED_OUTLINES_THROUGH_WALLS = new ConfigBoolean("linkedOutlinesThroughWalls", true).apply(PREFIX);
    public static final ConfigColor LINKED_OUTLINE_COLOR = new ConfigColor("linkedOutlineColor", "0xFF22C55E").apply(PREFIX);
    public static final ConfigColor DUMP_OUTLINE_COLOR = new ConfigColor("dumpOutlineColor", "0xFFF59E0B").apply(PREFIX);
    public static final ConfigBoolean BOX_SELECT_CREATES_NEW_GROUP = new ConfigBoolean("boxSelectCreatesNewGroup", false).apply(PREFIX);

    public static final ImmutableList<IConfigBase> GENERIC = ImmutableList.of(
            ENABLE_MOD,
            INSTANT_FILL,
            AREA_FILL_RANGE,
            CREATIVE_FILL,
            CLICK_DELAY,
            CLEAR_WRONG_ITEMS,
            MATCH_SHULKER_BOXES_BY_CONTENT,
            CLOSE_AFTER_LOOK_FILL,
            DEBUG_LOGGING
    );

    /** TakeItOut behaviour: pulling items out of shulker boxes in the inventory. */
    public static final ImmutableList<IConfigBase> TAKEITOUT = ImmutableList.of(
            AUTO_TAKE_OUT,
            HOTBAR_REFILL,
            SINGLE_ITEM_MODE,
            SINGLE_ITEM_BUFFER,
            SHULKER_PICK_BLOCK,
            USE_TAKEITOUT_SOURCES,
            USE_LINKED_CONTAINERS,
            LINKED_OUTLINES,
            LINKED_OUTLINES_THROUGH_WALLS,
            LINKED_OUTLINE_COLOR,
            DUMP_OUTLINE_COLOR,
            BOX_SELECT_CREATES_NEW_GROUP
    );

    // --- Restock (own page, independent of every other option) ---
    public static final ConfigBooleanHotkeyed RESTOCK_ENABLED = new ConfigBooleanHotkeyed("restockEnabled", true, "").apply(PREFIX);
    public static final ConfigString RESTOCK_NAME = new ConfigString("restockName", "restock").apply(PREFIX);
    public static final ConfigInteger RESTOCK_THRESHOLD = new ConfigInteger("restockThreshold", 16, 1, 63).apply(PREFIX);
    public static final ConfigBoolean RESTOCK_OFFHAND = new ConfigBoolean("restockOffhand", true).apply(PREFIX);
    public static final ConfigBoolean RESTOCK_TOTEMS = new ConfigBoolean("restockTotems", true).apply(PREFIX);
    public static final ConfigBoolean RESTOCK_FROM_INVENTORY = new ConfigBoolean("restockFromInventory", true).apply(PREFIX);
    public static final ConfigBoolean RESTOCK_FROM_ENDER_CHEST = new ConfigBoolean("restockFromEnderChest", true).apply(PREFIX);
    public static final ConfigBoolean RESTOCK_WARN_EMPTY = new ConfigBoolean("restockWarnEmpty", true).apply(PREFIX);

    public static final ImmutableList<IConfigBase> RESTOCK = ImmutableList.of(
            RESTOCK_ENABLED,
            RESTOCK_NAME,
            RESTOCK_THRESHOLD,
            RESTOCK_OFFHAND,
            RESTOCK_TOTEMS,
            RESTOCK_FROM_INVENTORY,
            RESTOCK_FROM_ENDER_CHEST,
            RESTOCK_WARN_EMPTY
    );

    // --- Highlight ---
    public static final ConfigBooleanHotkeyed HIGHLIGHT_CONTAINERS = new ConfigBooleanHotkeyed("highlightContainers", true, "").apply(PREFIX);
    public static final ConfigInteger HIGHLIGHT_RANGE = new ConfigInteger("highlightRange", 32, 4, 128).apply(PREFIX);
    public static final ConfigBoolean HIGHLIGHT_THROUGH_WALLS = new ConfigBoolean("highlightThroughWalls", false).apply(PREFIX);
    public static final ConfigBoolean HIGHLIGHT_SHOW_CORRECT = new ConfigBoolean("highlightShowCorrect", true).apply(PREFIX);
    public static final ConfigBoolean HIGHLIGHT_SHOW_UNKNOWN = new ConfigBoolean("highlightShowUnknown", true).apply(PREFIX);
    public static final ConfigColor COLOR_CORRECT = new ConfigColor("colorCorrect", "0x4033DD55").apply(PREFIX);
    public static final ConfigColor COLOR_EMPTY = new ConfigColor("colorEmpty", "0x403399FF").apply(PREFIX);
    public static final ConfigColor COLOR_PARTIAL = new ConfigColor("colorPartial", "0x40FFC21A").apply(PREFIX);
    public static final ConfigColor COLOR_WRONG = new ConfigColor("colorWrong", "0x50FF3333").apply(PREFIX);
    public static final ConfigColor COLOR_UNKNOWN = new ConfigColor("colorUnknown", "0x30A0A0A0").apply(PREFIX);

    public static final ImmutableList<IConfigBase> HIGHLIGHT = ImmutableList.of(
            HIGHLIGHT_CONTAINERS,
            HIGHLIGHT_RANGE,
            HIGHLIGHT_THROUGH_WALLS,
            HIGHLIGHT_SHOW_CORRECT,
            HIGHLIGHT_SHOW_UNKNOWN,
            COLOR_CORRECT,
            COLOR_EMPTY,
            COLOR_PARTIAL,
            COLOR_WRONG,
            COLOR_UNKNOWN
    );

    /** Boolean options with a toggle hotkey. */
    public static final ImmutableList<ConfigBooleanHotkeyed> TOGGLES = ImmutableList.of(AUTO_TAKE_OUT, HIGHLIGHT_CONTAINERS, SINGLE_ITEM_MODE, LINKED_OUTLINES, HOTBAR_REFILL, RESTOCK_ENABLED);

    // --- Hotkeys ---
    /** Fills the container whose screen is currently open. Unbound by default; only fires inside a GUI. */
    public static final ConfigHotkey AUTO_FILL_OPEN_CONTAINER = new ConfigHotkey("autoFillOpenContainer", "", KeybindSettings.GUI).apply(PREFIX);
    /** Opens and fills the schematic container under the crosshair. */
    public static final ConfigHotkey FILL_LOOKED_AT_CONTAINER = new ConfigHotkey("fillLookedAtContainer", "V").apply(PREFIX);
    /** Instant-fills every schematic container within Area Fill Range. */
    public static final ConfigHotkey AREA_FILL = new ConfigHotkey("areaFill", "LEFT_SHIFT,V").apply(PREFIX);
    public static final ConfigHotkey OPEN_CONFIG_GUI = new ConfigHotkey("openConfigGui", "L,C").apply(PREFIX);
    public static final ConfigHotkey OPEN_STORAGE_MENU = new ConfigHotkey("openStorageMenu", "Y").apply(PREFIX);
    public static final ConfigHotkey LINK_LOOKED_AT = new ConfigHotkey("linkLookedAtContainer", "H").apply(PREFIX);
    public static final ConfigHotkey BOX_SELECT_CORNER = new ConfigHotkey("boxSelectCorner", "").apply(PREFIX);
    public static final ConfigHotkey MARK_DUMP_CONTAINER = new ConfigHotkey("markDumpContainer", "").apply(PREFIX);
    public static final ConfigHotkey DUMP_TO_CONTAINERS = new ConfigHotkey("dumpToContainers", "").apply(PREFIX);

    public static final List<ConfigHotkey> HOTKEYS = ImmutableList.of(
            AUTO_FILL_OPEN_CONTAINER,
            FILL_LOOKED_AT_CONTAINER,
            AREA_FILL,
            OPEN_CONFIG_GUI,
            OPEN_STORAGE_MENU,
            LINK_LOOKED_AT,
            BOX_SELECT_CORNER,
            MARK_DUMP_CONTAINER,
            DUMP_TO_CONTAINERS
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
                ConfigUtils.readConfigBase(root, "Generic", TAKEITOUT); // files saved before the TakeItOut tab existed
                ConfigUtils.readConfigBase(root, "TakeItOut", TAKEITOUT);
                ConfigUtils.readConfigBase(root, "Highlight", HIGHLIGHT);
                ConfigUtils.readConfigBase(root, "Restock", RESTOCK);
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
        ConfigUtils.writeConfigBase(root, "TakeItOut", TAKEITOUT);
        ConfigUtils.writeConfigBase(root, "Highlight", HIGHLIGHT);
        ConfigUtils.writeConfigBase(root, "Restock", RESTOCK);
        ConfigUtils.writeConfigBase(root, "Hotkeys", HOTKEYS);
        JsonUtils.writeJsonToFile(root, dir.resolve(CONFIG_FILE_NAME));
    }
}
