# Container Auto Fill

A client-side Fabric mod for **Minecraft Java 1.21.11** by **steelaspect**.

It brings together the behaviour of two mods, without needing either of them installed:

- **TakeItOut-style shulker retrieval.** When you need a block or item you don't carry loose, the mod pulls it out of a shulker box in your inventory.
- **Litematica container filling.** It fills chests, barrels, shulker boxes, hoppers, dispensers, droppers and similar containers so they match your Litematica schematic.

It also adds a new **auto-fill hotkey** and a **container highlight** that shows which schematic containers are correct, empty, partly filled or wrong. Open a container, press the key, and the mod fills every slot with the item, data components and count that the schematic expects. Items you don't carry loose are pulled from your shulker boxes first.

## Requirements

| Mod | Version |
|---|---|
| Fabric Loader | ≥ 0.19.5 |
| Fabric API | any 1.21.11 build (built against 0.141.6+1.21.11) |
| Litematica | ≥ 0.26.16 for 1.21.11 |
| MaLiLib | ≥ 0.27.20 for 1.21.11 |

For pulling items out of shulker boxes on a **Fabric dedicated server**, also put `containerautofill-server-<version>.jar` (from `build/libs`) in the server's `mods` folder. It needs only Fabric API. It isn't needed in singleplayer or LAN.

TakeItOut and Litematica-Container-Filler are **not** needed. They are declared as `breaks` because they register the same network channel and the same default keys. If either is installed, Fabric stops at launch with a clear message.

## Features

### 1. Auto-fill the open container (new)
1. Right-click a container that is part of an active Litematica placement.
2. Press the **Auto Fill Open Container** hotkey. It is unbound by default; set it under *Hotkeys* in the config screen. The hotkey only works while a container screen is open.
3. The mod:
   - finds the container's position and reads the expected contents from the placement. A double chest is treated as one 54-slot inventory.
   - compares each slot with what is expected, using item **and** data components (names, enchantments, contents, etc.).
   - moves items from your inventory with normal inventory clicks (`clickSlot`). It never sends hand-built click packets or teleports items. By default it makes one click per tick (*Click Delay*).
   - asks for anything you don't carry loose from shulker boxes in your inventory (see §3).
   - leaves items the schematic doesn't expect untouched, unless **Clear Wrong Items** is on.
4. When it finishes, the action bar shows **"Filled X slots, Y items missing"**, and chat lists the missing items with counts.

Press the hotkey again to cancel. Closing the screen also cancels safely, and any item on the cursor goes back to your inventory as usual.

If the container isn't part of an active placement, the action bar says so and nothing happens. It also stops if the schematic expects a different block there.

**Supported containers:** any block container: chests (including double, trapped and copper chests), barrels, shulker boxes, hoppers, dispensers, droppers, furnaces, smokers, blast furnaces, brewing stands, crafters (including their slot locks) and modded block containers whose block entity is an inventory.
- A slightly different block of the same container type still counts, e.g. a blue shulker box where the schematic has a red one, or an oxidised copper chest.
- Slots that can't take items, like a furnace's output, are reported as missing.
- If a slot keeps rejecting items, the fill skips it after a few tries instead of looping.

### 2. Container highlight
Placed schematic containers within *Highlight Range* get a see-through coloured box:

| Colour | Meaning |
|---|---|
| green | matches the schematic |
| blue | empty, but the schematic expects items |
| yellow | partly filled (items missing, nothing wrong) |
| red | holds items the schematic doesn't expect there, or too many |
| grey | contents not known yet |

- In singleplayer and LAN the highlight is always live.
- On a Fabric server, a container's colour is known once you've opened it (it's remembered afterwards). It's live if the server runs **Servux** and Litematica's *entityDataSync* is on.
- A container whose block differs from the schematic (e.g. a barrel instead of a chest) isn't highlighted.
- Toggle the highlight with *Highlight Containers*, which has an optional hotkey. Colours, range, see-through mode and hiding green/grey boxes are all configurable.

### 3. Fill looked-at container (from Litematica-Container-Filler)
Look at a schematic container and press **V**. The mod opens the container, fills it the same way as above, and closes it again (*Close After Look Fill*).

### 4. TakeItOut behaviour (shulker retrieval)
- **Pick block from shulkers.** If you pick a block with vanilla middle-click, Litematica's schematic pick-block or easy place, and you don't carry it loose, the mod pulls it from a shulker box in your inventory into your hand.
- **Auto Take Out** (toggle with **R**). While it's on, looking at a schematic block you don't have pulls it from a shulker. Right-clicking a schematic block (outside easy place) picks it.
- It respects Litematica's `pickBlockableSlots` setting.
- It skips stacked shulker boxes and shows a warning.
- It waits for the server to answer, with a ping-based timeout, before trying again.

**How retrieval works (same as TakeItOut).** The client sends a `takeitout:getstack(slot, shulker)` request, and the **server** moves the item out of the shulker box. This works:
- in **singleplayer** and when **hosting a LAN world**, because this mod handles the request on the integrated server;
- on **Fabric servers** with `containerautofill-server` installed;
- on servers running TakeItOut's own server mod, since the channel and data format are the same.

On a server without either, the mod notices that the server doesn't accept the channel. It tells you once, then only uses items you carry loose.

## Hotkeys

| Hotkey | Default | Notes |
|---|---|---|
| Auto Fill Open Container | *(unbound)* | Only while a container screen is open. Press again to cancel. |
| Fill Looked At Container | `V` | In game, no screen open. |
| Auto Take Out (toggle) | `R` | Same default as TakeItOut. |
| Open Config GUI | `L` + `C` | The config is also listed in MaLiLib's config menu. |

None of these defaults clash with vanilla, Litematica or MaLiLib defaults.

## Config options (MaLiLib, `config/containerautofill.json`)

| Option | Default | Description |
|---|---|---|
| Enable Mod | on | Turns every feature on or off. |
| Click Delay (ticks) | 1 | Ticks between automated clicks. 1 means one click per tick. Raise it if a server complains about fast clicking. |
| Clear Wrong Items | **off** | Shift-click unexpected items out of the container before filling. |
| Use TakeItOut Sources | on | Use shulker retrieval while auto-filling. |
| Match Shulker Boxes By Content | off | A shulker box in a container slot counts as correct if its contents match (from LCF). |
| Close After Look Fill | on | Close the container after *Fill Looked At Container*. |
| Pick Block From Shulkers | on | TakeItOut's pick-block behaviour. |
| Auto Take Out | off | TakeItOut's toggle mode (key R). |
| Debug Logging | off | Logs every click and retrieval to `latest.log`. |

**Highlight tab**

| Option | Default | Description |
|---|---|---|
| Highlight Containers | on | Show the coloured boxes. Has an optional toggle hotkey. |
| Highlight Range | 32 | Blocks around you that are checked. |
| Highlight Through Walls | off | Draw boxes through other blocks. |
| Show Correct Containers | on | Also show green boxes. |
| Show Unknown Containers | on | Also show grey boxes. |
| Colour: Correct / Empty / Partly Filled / Wrong / Unknown | green / blue / yellow / red / grey | Box colours (with transparency). |

## Known issues
- **Crafters:** in automated tests about one fill in three left one crafter slot empty, because the server rejected the item placed there. The fill still finishes, nothing is lost, and the slot is reported as missing. Pressing the hotkey again fills it. Crafter slot locks are applied as in the schematic.

## Not carried over
- **LCF:** its own highlight renderer (replaced by the simpler highlight above), the render editor, the material list buttons, the item replacement GUIs, container tools, continuous "work state" area filling, Servux/MiniHUD/OP data sync, Carpet large barrels, and QuickShulker / ModMenu integration (they aren't allowed dependencies).
- **TakeItOut:** the Litematica Printer and Tweakeroo integrations (not allowed dependencies) and its ModMenu keybind screen.
- With *Clear Wrong Items* on, the mod clears wrong item types. It does not remove extra items of the correct type beyond the schematic's count.

## Building

```bash
./gradlew build                 # build/libs/containerautofill-<version>.jar (client)
                                # build/libs/containerautofill-server-<version>.jar (Fabric server)
./gradlew runClientGameTest     # automated in-game tests (needs a display, or xvfb-run)
```
You need Java 21. The build uses the Gradle 9.8 wrapper and Fabric Loom 1.17.

## Credits

- **TakeItOut** by **Rofumer (Max Bel)**: <https://github.com/Rofumer/TakeItOut>. TakeItOut is *All Rights Reserved*, so **none of its code is included**. Its behaviour and its `takeitout:getstack` network format were reimplemented from scratch from its observed behaviour.
- **Litematica-Container-Filler** by **MimicEnzymes**: <https://github.com/MimicEnzymes/Litematica-Container-Filler> (LGPL-3.0-only). The schematic container reading, item matching and slot mapping are ported from it. Each ported file says where it came from and what was changed.
- **Litematica** and **MaLiLib** by masa, and **Fabric**.

## License

**LGPL-3.0-only** (see `LICENSE` and `COPYING.GPL`), because this mod contains code ported from Litematica-Container-Filler. Copyright (C) 2026 steelaspect.
