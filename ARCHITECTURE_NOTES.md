# ARCHITECTURE_NOTES — containerautofill (Step 1 research)

Author of this project: steelaspect. Research date: 2026-10-02.

Both upstream repositories were cloned into `reference/` (git-ignored, never on the
Gradle classpath, never packaged). Branches inspected:

| Repo | Default branch | Branch used for this research | MC / mappings on that branch |
|---|---|---|---|
| Rofumer/TakeItOut | `master` (MC 1.21, Yarn `1.21+build.9`) | `master-1.21.11` @ `70900ae` | MC 1.21.11, **official Mojang mappings**, Loom 1.15.5, loader 0.18.2, Fabric API 0.139.5+1.21.11 |
| MimicEnzymes/Litematica-Container-Filler (LCF) | `1.21.10` (MC 1.21.10, Yarn `1.21.10+build.1`) | `1.21.11` @ `f23dd…` ("Declare Fabric API dependency") | MC 1.21.11, Yarn `1.21.11+build.1`, Loom 1.16.3, loader 0.18.4, Fabric API 0.141.3+1.21.11, Litematica 0.26.11, MaLiLib 0.27.16 |

Both repos also have branches for other versions (TakeItOut: 1.20.1 … 26.3 plus Forge/NeoForge;
LCF: 1.21 … 26.2). Only the 1.21.11 branches are relevant to porting.

---

## 0. STOP CONDITION — licenses

| Repo | License file | fabric.mod.json | Copy + redistribute inside a new mod? |
|---|---|---|---|
| TakeItOut | `LICENSE.txt`: `Copyright (c) 2024` / `All rights reserved.` (identical on all 25 branches) | `"license": "All-Rights-Reserved"` | **No.** No permission is granted to copy, modify or redistribute. |
| LCF | `LICENSE`: GNU LGPL v3 (full text) | `"license": "LGPL-3.0-only"` | **Yes, with conditions** (see below). |

**TakeItOut is All-Rights-Reserved, so per the task rules work stops here.** No TakeItOut code
has been or will be copied into this project. A reimplementation from observed behaviour
needs the user's explicit approval (see §5).

**LGPL-3.0-only (LCF) conditions that apply if LCF code is ported:**
- The ported code (and in practice the whole combined mod, since the code is being copied and
  modified, not linked as a separate library) must be distributed under LGPL-3.0-only
  (LGPL §2/GPLv3 §5). The mod can't be ARR or MIT.
- The LGPL + GPLv3 license texts must ship with the source and the jar.
- Modified files must carry a prominent notice saying they were modified, with a date (GPLv3 §5a).
- Source code must be available to anyone who receives the jar (GPLv3 §6). A public repo covers this.
- LCF source files contain **no per-file copyright headers**, so there are no original headers to
  preserve. The repo-level notice is the LICENSE file plus the authorship in fabric.mod.json
  (`MimicEnzymes`; git history also shows a second contributor, `EnderPhantomWing`).
- Tension with "never use any other name in metadata, license or comments": LGPL doesn't force
  MimicEnzymes' name into fabric.mod.json, but honest provenance notes in ported files
  (e.g. "Ported from https://github.com/MimicEnzymes/Litematica-Container-Filler, LGPL-3.0-only,
  modified by steelaspect") contain another name via the URL. This needs a user decision.

---

## 1. TakeItOut — what it actually does

### 1.1 Where it gets items from
**Only from shulker boxes in the player's own inventory (main 36 slots).** It does **not**
read nearby containers, the ender chest, or anything else. Stacked shulkers (count > 1)
are skipped with a warning.

### 1.2 The item pull is server-side, not client-side
This is the most important finding, and it contradicts the task description.

- `net.maxbel.takeitout.Takeitout` (main entrypoint, runs on the **server**) registers a
  C2S custom payload `GetShulkerStackPayload(int slot, int shulker)` with id
  **`takeitout:getstack`** (`PayloadTypeRegistry.playC2S().register(...)`), and a receiver
  (`ServerPlayNetworking.registerGlobalReceiver`) that, on the server thread:
  1. reads the shulker item's `DataComponents.CONTAINER` (Mojang name; Yarn
     `DataComponentTypes.CONTAINER` / `ContainerComponent`),
  2. removes the stack at `slot` and writes the component back
     (`ItemContainerContents.fromItems`),
  3. puts the player's current main-hand stack in a free slot and **sets the extracted stack
     directly into the main hand**. If there is no free slot, it swaps with the last
     non-tool, non-shulker, non-ender-chest block item in slots 35..0.
- The client only **sends the request packet**: `ClientPlayNetworking.send(new
  Takeitout.GetShulkerStackPayload(innerSlot, shulkerSlot))`.
- Consequence: **without TakeItOut (or its separately distributed Paper/Spigot companion
  plugin, whose source is not in the repo) on the server, nothing happens.** On a vanilla or
  plain Paper server, `ClientPlayNetworking.canSend(takeitout:getstack)` is false and the item
  never moves. This is an instant, server-authoritative transfer, not a sequence of vanilla
  slot clicks.
- The README confirms this: "Mod must be installed on a server side too if you want to play
  with that on a public server! Or use added here plugin-companion for Paper/Purpur/Spigot/Bukkit servers."

### 1.3 Client classes (`src/client/java/net/maxbel/takeitout/…`)
| Class | Role |
|---|---|
| `client.TakeitoutClient` | Registers vanilla `KeyMapping` `key.takeitout.toggle` (default **R**, category `KeyMapping.Category.INVENTORY`) via Fabric `KeyBindingHelper`. Toggles static `AUTOTAKEOUT`. `onGameTick()` (called every player tick): if AUTOTAKEOUT, Litematica present, Printer **absent**, player `mayBuild`: ray-traces the schematic world (`RayTraceUtils.traceToSchematicWorld(player, 3, true, true)`); if the target block's item isn't in the inventory, calls `WorldUtils.doSchematicWorldPickBlock(true, mc)`. Uses `Class.forName` to detect Litematica and Printer. |
| `client.Util` | `getShulkerWithStack(Inventory, ItemStack)` (finds first count-1 shulker whose contents contain a matching stack), `getSlotWithStack(Container, ItemStack)`, `areItemsEqual` (`ItemStack.isSameItem`, item type only, **not components**), `getSize` (caps player inventory at 36). |
| `client.ItemStackInventory` | `SimpleContainer` view (27 slots) over a shulker item's CONTAINER component. |
| `client.SchematicBlockState` | Small holder: world + `WorldSchematic` + pos, with target/current block states. |
| `client.TakeItOutKeybindsScreen`, `client.ModMenuCompat` | ModMenu config screen that just lists the keybind(s). Needs ModMenu. |
| `compat.TakeItOutMixinPlugin` | `IMixinConfigPlugin` that applies mixins only if mod ids `litematica`, `tweakeroo`, `litematica_printer` are loaded. |

### 1.4 Mixins (`takeitout.client.mixins.json`, refmap `takeitout.refmap.json`)
| Mixin | Target | Injection | Purpose |
|---|---|---|---|
| `LitematicaMixin` | `fi.dy.masa.litematica.util.WorldUtils` (remap=false) | `@Inject HEAD doEasyPlaceAction` (cancellable) | If the main-hand item ≠ `MaterialCache.getRequiredBuildItemForState(state)`, triggers pick-block and returns `FAIL` until the item arrives (ping-based wait, 2 retries, hard timeout). |
| | | `@ModifyArg easyPlaceOnUseTick → doEasyPlaceAction` | Per-tick wait/retry bookkeeping. |
| | | `@Inject HEAD doSchematicWorldPickBlock` (cancellable) | Respects Litematica `Configs.Generic.PICK_BLOCKABLE_SLOTS`. If the required item is in the inventory → `InventoryUtils.swapItemToMainHand` (MaLiLib); else finds a shulker containing it and **sends `takeitout:getstack`**. Then calls Litematica's `schematicWorldPickBlock`. |
| `MouseMixin` | `net.minecraft.client.MouseHandler` | `@Inject HEAD "onPress(JIII)V"`, `require = 0` | When AUTOTAKEOUT and **not** easy-place: right-click on a schematic block that isn't occluded by a real block → schematic pick-block. Note: 1.21.9+ changed the mouse-button handler signature, so this descriptor probably **no longer matches on 1.21.11** and silently does nothing because of `require = 0`. Needs verification. |
| `PickBlockMixin` | `net.minecraft.client.Minecraft` | `@Redirect pickBlock → BlockHitResult.getBlockPos()` | Vanilla middle-click on a real block: if survival and not in inventory, finds it in a shulker and sends `takeitout:getstack`. |
| `MixinClientPlayerEntity` | `LocalPlayer` | `@Inject TAIL tick` | Calls `TakeitoutClient.onGameTick()`. |
| `PrinterMixin` | `me.aleksilassila.litematica.printer.Printer` | `@Inject TAIL/HEAD onGameTick` | Printer integration: fetch from shulker, pause printer while waiting. |
| `TweakerooMixin` | `fi.dy.masa.tweakeroo.util.InventoryUtils` | `@Inject TAIL restockNewStackToHand` (LocalCapture) | Tweakeroo hand-restock falls back to shulker fetch. |
| `UpdatedSlot` | `ClientPacketListener` | `@Inject TAIL handleContainerSetSlot` | Clears `awaitingStack` when the requested stack lands in a hotbar slot. |

### 1.5 Exact pull path (summary)
`Util.getShulkerWithStack(inv, required)` → `ItemStackInventory.getInventoryFromShulker(shulker)`
→ `Util.getSlotWithStack(shInv, required)` → `ClientPlayNetworking.send(new
GetShulkerStackPayload(inner, shulkerSlot))` → **server** `Takeitout.onInitialize` receiver
mutates the shulker and the main hand.

### 1.6 Config / settings
None besides the single toggle keybind (default R) and the ModMenu keybind screen.

---

## 2. Litematica-Container-Filler — how it reads and fills

LCF is a large client-only mod (~19,500 lines of Java, 81 files, 18 mixins). Entry point
`LitematicaContainerFillerClient` (ClientModInitializer). It also registers a MaLiLib
`InitHandler` → `Configs.init()`. Mod id `litematica_container_filler`.

### 2.1 Reading the expected (schematic) contents
Two paths, tried in this order (`core.LitematicaContainerReader.getRequiredItems(BlockPos, WrapperLookup)`):

1. **Placement NBT snapshot**: `core.LitematicaPlacementContainerData`
   - Iterates `DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()`
     (enabled placements only), and for each enabled sub-region
     (`placement.getSubRegionBoxes(RequiredEnabled.PLACEMENT_ENABLED)`) reads
     `LitematicaSchematic.getBlockEntityMapForRegion(regionName)` (local pos → raw NBT) and
     `getSubRegionContainer(regionName)`.
   - Converts local → world positions with `PositionUtils.getRelativeEndPositionFromAreaSize`,
     `getMinCorner`, `getTransformedBlockPos`, `getTransformedPlacementPosition`, and
     cross-checks with `SchematicUtils.getSchematicContainerPositionFromWorldPosition`.
   - Keeps only container block entities (`BlockEntity.createFromNbt(...) instanceof Inventory`
     or NBT has `Items`). Result: immutable `Map<BlockPos, NbtCompound>` snapshot, rebuilt on
     placement changes (via the `SchematicPlacementMixin` / `SchematicPlacementManagerMixin` hooks).
2. **Schematic world fallback**: `SchematicWorldHandler.getSchematicWorld().getBlockEntity(pos)`
   → `blockEntity.createNbt(registries)` → `RealContainerCache.parseNbtInventory(nbt, registries)`.
   NBT `Items[]` entries are decoded with `ItemStack.OPTIONAL_CODEC.parse(registries.getOps(NbtOps.INSTANCE), itemNbt)`
   with `Slot` as an unsigned byte. **Full data components are preserved.**

Double containers: `getDoubleContainerHalves` uses `ChestBlock.CHEST_TYPE` / `FACING` to find
the right and left halves. `RealContainerCache.combineDoubleContainerItems(right, left)` merges
them into one 54-slot map (left half at slots 0–26, matching the vanilla `DoubleInventory` /
`GenericContainerScreenHandler` ordering; to be confirmed at port time). Optional Carpet
`largeBarrels` support (config, default OFF).

Other inputs merged in: `MaterialReplacer.replaceInMap` (user item substitution rules, global
and per-schematic), `getIgnoredSlots` (items replaced with `minecraft:air`), `getDisabledSlots`
(crafter `disabled_slots`).

Item comparison: `core.ItemMatcher.isSameItem` → `ItemStack.areItemsAndComponentsEqual`
(**item + data components**), optionally shulker-by-contents (config `matchShulkerBoxesByContent`).

### 2.2 Reading the real container contents
`core.RealContainerCache` (1,550 lines) captures contents from:
- opened screens
- Litematica entity-data sync
- MiniHUD's cache
- Servux `servux:hud_data_*` payloads (`network.ServuxSyncHandler`)
- an optional OP NBT query
- in singleplayer, the integrated server world directly (`AutoFillerStateMachine.getTrueContainerData`)

Details are in §2.6. For the requested feature, the open screen's `ScreenHandler.slots` are the
real contents, so none of these extra sources is needed.

### 2.3 How it fills (`core.AutoFillerStateMachine`, 2,721 lines)
- **Trigger**: MaLiLib hotkey `fillContainer` (default **V**) → `Callbacks.executeFill` →
  queues a `FillTask` for the container under the crosshair (or near the player via
  `AreaScanner.executeScan`). The continuous "Work State" (`workingState`, ConfigBooleanHotkeyed)
  scans an area every few ticks.
- **It opens the container itself**: `client.interactionManager.interactBlock(player,
  MAIN_HAND, hitResult)` (`InteractionTargeting.createBlockHitResult`). It does **not** work from
  an already-open screen. That is the main behavioural difference from the requested feature.
- Phases: `IDLE → AWAITING_DATA → INSPECTING → STASHING → GATHERING → FILLING → RETURNING`.
- **Slot mapping**: `core.SlotMapper` maps container slot index and player inventory index to
  `ScreenHandler.slots` ids by `slot.inventory == playerInv`.
- **All moves are vanilla clicks**:
  `client.interactionManager.clickSlot(syncId, slotId, button, SlotActionType, player)` with
  `PICKUP` (button 0 = whole stack, 1 = single item), `QUICK_MOVE`, `THROW`, and `-999` (drop
  cursor). `fillFromPlayerInv`: pick up source → put all into target → put remainder back, or
  pick up → N right-clicks → put back. `findExactMovePlan` does a BFS over left/right-click
  sequences to move an exact count. Wrong items are swapped out (pickup / place / return) or
  thrown when `dropExtractedItems` is on.
- **Gathering sources**: player inventory; shulker boxes in the inventory opened via
  QuickShulker (`OpenShulkerPacket`, optional dependency) or a "simulate click" right-click on
  the inventory slot (only useful if some other mod opens shulkers on right-click); and
  the **TakeItOut protocol** (§2.4).
- **Throttling**: `getDelay(base) = base + fillDelay` when `enableSafetyDelay` (default on,
  `fillDelay` default **0**). With delay 0, `executeBurstFill` issues **many clicks in a single
  tick**. Optional `network.ClickPacketRateLimiter` (`rateLimitClickPackets`, default **off**,
  `clickPacketRateLimit` default 4/tick) buffers `ClickSlotC2SPacket`,
  `CloseHandledScreenC2SPacket`, `SlotChangedStateC2SPacket` and
  `CreativeInventoryActionC2SPacket` in a mixin on the network handler and replays N per tick.
- **Creative fill** (`creativeFill`, default on) uses creative inventory actions.
- **Safety**: watchdog/timeout reset, `emergencyStop`, "fill state protection" stops on world,
  dimension or player change.

### 2.4 LCF's own TakeItOut protocol client (LGPL, written by LCF's author)
`network.TakeItOutPayload` is an independent record `(int slot, int shulker)` with id
`takeitout:getstack` and a `PacketCodec.tuple(INTEGER, INTEGER)`. `network.TakeItOutCompat`
registers it C2S (unless TakeItOut is installed client-side, in which case it reflects into
`net.maxbel.takeitout.Takeitout$GetShulkerStackPayload`) and only sends when
`ClientPlayNetworking.canSend(ID)` is true, i.e. when the server advertises the TakeItOut
channel. `AutoFillerStateMachine.tryTakeItOutFetch/findTakeItOutRequest/hasTakeItOutResultArrived`
choose the shulker slot and inner slot and wait for the item to appear. This is
**protocol interoperability written independently under LGPL; it contains no TakeItOut code.**
The reflection on TakeItOut's class name and `isModLoaded("takeitout")` would have to be removed
under this project's rules.

### 2.5 Config, hotkeys, GUI (MaLiLib)
Hotkeys (`config.Hotkeys`): `openConfigGui` **L,C**, `fillContainer` **V**, and unbound
`toolTrigger`, `toolSwitchMode`, `toolSwitchPrevious`, `toolCloseAll`, `cycleManualOverride`,
`clearManualOverrides`. Plus `workingState` and many ConfigBooleanHotkeyed toggles.
`input.InputHandler` (IKeybindProvider) / `input.Callbacks` (IHotkeyCallback). `gui.GuiConfigs`
(MaLiLib GuiConfigsBase with tabs).

Config options (`config.Configs`, 80 options): enableMod, workingState, carpetLargeBarrelMode,
fillRadius (5), fillDelay (0), enableSafetyDelay (true), enableFillStateProtection (true),
rateLimitClickPackets (false), clickPacketRateLimit (4), containerFilterMode/Scope/List,
materialReplacements, enableDataSync (true), enableOpNbtQuery (false), realContainerCacheSize,
creativeFill (true), enableQsExtraction (true), quickShulkerOpenMode, matchShulkerBoxesByContent
(false), storeOrderly (true), dropExtractedItems (false),
dropItemsFromEmptySchematicContainers (false), hideProjectionFillGui (true), the container
tool group (toolEnabled, mode, HUD style/opacity/scale/position…), and about 30 highlight-render
options (colours, per-state toggles, x-ray, render radius, scan budget, task markers).

### 2.6 Remaining subsystems (summarised from a full read)

**Real container data (`core.RealContainerCache`, `network.ServuxSyncHandler`)**
- The cache is keyed by BlockPos, with a 5-minute TTL and LRU eviction (cap `realContainerCacheSize`, default 8192).
- `tick()` hashes the slots of the open HandledScreen and records them. `interactBlock` is hooked to bind a screen's syncId to a position.
- Lookup order:
  1. local cache
  2. Litematica `EntityDataManager.getInstance().getCache().getBlockEntityNbtFromCache(pos)`, when Litematica entity-data sync is on
  3. MiniHUD inventory cache, found by reflection
  4. its own `servux:hud_data_request` / `servux:hud_data_sync` payloads, sent only if `canSend`
  5. a vanilla `QueryBlockNbtC2SPacket`, which needs OP; option `enableOpNbtQuery`, default off
- In singleplayer it reads the integrated server world directly.

**Area/work mode (`core.AreaScanner`, `HighlightScanner`)**
- Continuous scan of highlighted containers within `fillRadius`, the Litematica render layer range and reach.
- Per-position cooldowns, and a cap of 4, 15 or 40 tasks.

**Container tools (`tool.ContainerToolStateMachine`, 1,650 lines)**
- Modes: CLEAR, FILL_FULL, COPY (sync from a template), PACK (into inventory shulkers) and COLLECT_MATERIALS (pull material-list items from a container).
- Holding the key repeats the action, with a per-container cooldown.
- Containers are opened with `interactBlock`, and all moves use `clickSlot`.

**Highlighting/rendering (`render.*`)**
- `HighlightScanner` (1,334 lines) computes UNFILLED/PARTIAL/SATISFIED/OVERFILLED/WRONG/UNKNOWN/UNPLACED states per container.
- `HighlightRenderer` (841 lines) draws boxes and task markers through MaLiLib `RenderContext` / `MaLiLibPipelines` from `WorldRenderEvents.AFTER_ENTITIES`.
- `ToolHudRenderer` (1,293 lines) draws a HUD card from an `InGameHud.render` mixin.

**GUIs (`gui.*`, MaLiLib GuiBase)**
- `GuiConfigs`: tabbed config screen.
- `GuiContainerFilter`: block-type grid.
- `GuiItemReplacementPicker` and `GuiGlobalMaterialReplacementPicker`: item replacement pickers.
- `GuiRenderEditor` (1,821 lines): colour and size editor.

**Material list integration**
- `materials.FillMaterialCalculator` adds container contents to Litematica's material list. It uses `DataManager.getMaterialList()`, `MaterialListUtils.updateAvailableCounts`, and reflection on Litematica internals.
- Mixins `GuiMaterialListMixin`, `MaterialListBaseMixin`, `WidgetListMaterialListMixin` and `WidgetMaterialListEntryMixin` add the Blocks/Containers/All toggle and the Locate/Replace buttons.
- `GuiMaterialListMixin` starts a daemon thread that polls every 50 ms. That's fragile, and I'd rewrite it if this part is kept.

**Material replacement (`core.MaterialReplacer`)**
- Rule formats: `id->id`, `id#name->id` and `stack64:` rules. A target of `minecraft:air` means "ignore this item".
- Applies to schematic-world block entities through `BlockEntityMixin` (`createNbt*` RETURN) and through `LootableContainerBlockEntityMixin` / `CrafterBlockEntityMixin` (`getStack` RETURN).

**Container filter (`filter.*`)**
- Whitelist/blacklist of container block ids, with wildcard support.
- Defaults: chest, trapped chest, barrel, shulkers, crafter, hopper, dispenser, dropper, furnaces, brewing stand.

**Manual overrides**
- Per-(dimension, pos) state: AUTO → COMPLETED → NEEDS_FILL.

**Dead code**
- `core.SpatialKDTree` and `dependency.TechUtilsDeceiver` are never referenced. TechUtilsDeceiver would also reflectively clear static fields of another mod.

**LCF mixins**

| Mixin | Target | Injection | Purpose |
|---|---|---|---|
| `ClientCommonNetworkHandlerMixin` | `ClientCommonNetworkHandler.sendPacket` | HEAD, cancellable | Click-packet rate limiter buffer |
| `ClientPlayNetworkHandlerMixin` | `onNbtQueryResponse` | HEAD | OP NBT query responses |
| `ClientPlayerInteractionManagerMixin` | `interactBlock` | HEAD | Remember which block a screen belongs to |
| `ScreenInterceptorMixin` | `MinecraftClient.setScreen` | HEAD, cancellable | **Hides container screens while filling** (`hideProjectionFillGui`, default on) |
| `InGameHudMixin` | `InGameHud.render` | TAIL | Tool HUD |
| `BlockEntityMixin`, `LootableContainerBlockEntityMixin`, `CrafterBlockEntityMixin` | `createNbt*` / `getStack` | RETURN | Material replacement inside the schematic world |
| `SchematicPlacementMixin` | Litematica `setOrigin/setRotation/setMirror/toggleEnabled/setEnabled` | RETURN | Re-index on placement change |
| `SchematicPlacementManagerMixin` | `removeSchematicPlacement`, `removeAllPlacementsOfSchematic` | — | Drop per-schematic rules |
| `GuiMainMenuMixin` | Litematica `GuiMainMenu.initGui` | RETURN | Adds a config button |
| `GuiMaterialListMixin`, `MaterialListBaseMixin`, `WidgetListMaterialListMixin`, `WidgetMaterialListEntryMixin` | Litematica material list | — | Material list integration |
| `ButtonBaseAccessor`, `MaterialListPlacementAccessor`, `WidgetContainerInvoker` | — | Accessors | — |

**Packets LCF can send:**
- `servux:hud_data_request` (only if `canSend`)
- `takeitout:getstack` (only if `canSend`)
- QuickShulker `OpenShulkerPacket` (only with QuickShulker)
- vanilla `QueryBlockNbtC2SPacket` (OP only, opt-in)
- raw `CloseHandledScreenC2SPacket`

None of these forge vanilla inventory clicks.

**isModLoaded / reflection checks:**
- `isModLoaded`: `quickshulker`, `takeitout`, `techutils`.
- `Class.forName`: MiniHUD and TakeItOut class names, plus Litematica internals.
- The `takeitout` checks must be removed under this project's rules.

### 2.7 Dependencies declared by LCF
`depends`: fabricloader ≥0.18.4, minecraft ~1.21.11, java ≥21, fabric-api, malilib, litematica.
`suggests`: quickshulker, modmenu. Build: Litematica/MaLiLib jars from `libs/` (flatDir),
`modCompileOnly` QuickShulker 2.10.0 and ModMenu 17.0.0-beta.2.
**QuickShulker and ModMenu are not in this project's allowed dependency list**, so their
integrations must be dropped (or a dependency approved by the user).

---

## 3. API changes relevant to porting to 1.21.11 (Yarn)

Both upstream projects already have **1.21.11 branches**, so the port target matches. The
version jumps that matter are from each default branch (TakeItOut `master` = 1.21; LCF `1.21.10`)
and, for TakeItOut, the **mapping set** (Mojang → Yarn).

| Area | Change between 1.21 / 1.21.10 and 1.21.11 | Impact |
|---|---|---|
| Mappings | TakeItOut-1.21.11 uses Mojang names (`KeyMapping`, `LocalPlayer`, `ItemContainerContents`, `Identifier.fromNamespaceAndPath`). This project uses Yarn `1.21.11+build.6` (latest; **1.21.11 is the last Yarn-mapped release**; 26.x is unobfuscated). | Every TakeItOut symbol would need renaming (moot while it can't be copied). LCF is already Yarn. |
| ItemStack data components | Components since 1.20.5; `DataComponentTypes.CONTAINER` / `ContainerComponent` (`stream()`, `copyTo(DefaultedList)`, `fromStacks`). Equality: `ItemStack.areItemsAndComponentsEqual`. `ItemStack.OPTIONAL_CODEC` for NBT. | Use components for every comparison. TakeItOut's `isSameItem` (type only) is too weak for slot-exact fills. |
| NBT API (1.21.5) | `NbtCompound` getters return `Optional` / `getXOr(key, default)`. | LCF already uses `nbt.get()` + `instanceof AbstractNbtNumber`. |
| Block entity (de)serialisation (1.21.6) | `readNbt/writeNbt` → `readData(ReadView)` / `writeData(WriteView)`; `createNbt(WrapperLookup)` still exists. | LCF mixins on `LootableContainerBlockEntity` / `CrafterBlockEntity` / `BlockEntity` target the 1.21.11 shapes already. |
| Slot click | `ClientPlayerInteractionManager.clickSlot(int syncId, int slotId, int button, SlotActionType, PlayerEntity)` unchanged. Since 1.21.5 `ClickSlotC2SPacket` carries hashed stacks (`ItemStackHash`) rather than full stacks, which makes hand-built packets impractical. | Use `clickSlot` only, as required. |
| PlayerInventory (1.21.5) | `selectedSlot` / `main` became private → `getSelectedSlot()`, `getMainStacks()`. | Use the accessors. |
| ScreenHandler / HandledScreen | 1.21.9 input refactor: `mouseClicked(Click, boolean)`, `keyPressed(KeyInput)`; `ScreenHandler.slots`, `syncId`, `getCursorStack()` unchanged. | Don't mix into screen input methods; use MaLiLib's keybind manager. |
| KeyBinding (1.21.9) | `KeyBinding` category is now a `KeyBinding.Category` object, not a String. Mouse handler became `onMouseButton(long, MouseInput, int)`. | Affects a vanilla keybind (TakeItOut's toggle). The new hotkey uses MaLiLib `ConfigHotkey`. |
| Networking | `CustomPayload` + `PayloadTypeRegistry.playC2S()` + `ClientPlayNetworking.send/canSend` (since 1.20.5) unchanged through 1.21.11. | Relevant only if a TakeItOut-protocol client is kept (§5). |
| Entity world | 1.21.9: `Entity.getWorld()` → `getEntityWorld()`. | LCF already uses the new name. |
| Rendering / HUD | 1.21.5–1.21.11 render pipeline rework (`RenderPipeline`, GUI render state in 1.21.6, Fabric `rendering.v1.world.WorldRenderEvents` in 1.21.9+). | LCF's HighlightRenderer / ToolHudRenderer already target 1.21.11. |
| Messages | `ClientPlayerEntity.sendMessage(Text, boolean overlay)` for action bar. | For the "Filled X slots" summary. |

Exact signatures will be confirmed against the decompiled 1.21.11 Yarn jar during Step 2.

---

## 4. How the requested feature maps onto the sources

| Requested | Where it exists today |
|---|---|
| Read expected container contents from schematic, incl. components and double chests | LCF `LitematicaContainerReader` + `LitematicaPlacementContainerData` (LGPL, portable) |
| Slot-by-slot diff, component-aware | LCF `ItemMatcher` + `AutoFillerStateMachine` (LGPL) |
| Move via `clickSlot` only, exact counts | LCF `fillFromPlayerInv` / `findExactMovePlan` (LGPL) |
| Throttle per action | LCF `fillDelay` (default 0) and `ClickPacketRateLimiter`. Needs a stricter "≥1 tick per action" scheduler for this feature. |
| Fill while the screen is already open | **Not in either mod.** LCF always opens the target itself. New code. |
| Pull missing items via TakeItOut | TakeItOut: **server-side packet only, ARR**. LCF: independent LGPL protocol client for the same packet (works only when the server runs TakeItOut / its plugin). |
| Nearby containers / ender chest as sources | **Not in either mod.** |

---

## 5. Blockers and questions for the user (work is paused)

1. **TakeItOut is All-Rights-Reserved.** Its code can't be copied. Possible paths:
   a) The user gets written permission from Rofumer (rofumer@gmail.com, or the Discord linked
      in its fabric.mod.json); or
   b) the user approves a **reimplementation from observed behaviour**. The behaviour is small:
      find a shulker in the inventory containing the item and request it over the
      `takeitout:getstack(int slot, int shulker)` channel. LCF's LGPL `TakeItOutPayload/Compat`
      already implement that protocol independently and could be used instead. Note that this
      session has read the TakeItOut source (Step 1 required it), so a reimplementation here
      isn't a strict clean room.
2. **TakeItOut's retrieval can't work client-only on vanilla or Paper.** It needs the TakeItOut
   server mod or its companion plugin. It's also an instant server-side transfer into the main
   hand, which conflicts with "vanilla + Paper, clickSlot only, no instant transfers". On a server
   without it, the only legitimate client-side sources are the player inventory and, via real
   open/click/close sequences, other containers or a placed ender chest. Those would have to
   close the open target screen and reopen it, which neither original mod does.
3. **"Keep both original features working":** TakeItOut's features (auto-take-out toggle R, pick-block
   from shulker, easy-place, Printer and Tweakeroo hooks) all rely on that same server packet, plus
   optional Printer/Tweakeroo/ModMenu, which aren't allowed dependencies.
4. **LGPL consequence:** porting LCF means this mod must be **LGPL-3.0-only**, with source available.
5. **Scope of the LCF port:** about 19.5k lines, including a large highlight renderer, render
   editor, material-list integration, item-replacement GUIs and a container-tool suite.
   QuickShulker and ModMenu integrations must be dropped (not allowed dependencies).
6. **Conflicts if LCF is installed alongside:** same default hotkeys (V, L+C) and mixins on the
   same Litematica/MC methods → plan: `"breaks": {"litematica_container_filler": "*"}`. TakeItOut
   has no conflicting mixins with LCF-derived code apart from both hooking pick/easy-place if
   reimplemented; plan a startup warning.
7. **runClient** needs a desktop/GPU and would launch Minecraft. This cloud container is headless,
   so in-game verification of every feature isn't possible here (and launching it needs the
   user's permission).
