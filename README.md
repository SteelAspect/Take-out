# Cytra Container

A Fabric mod for **Minecraft Java 1.21.11** by **steelaspect**. Version **1.3.1**, one jar (`cytra-container-1.3.1.jar`) for the client and, optionally, the server.

It brings together the behaviour of two mods, without needing either of them installed:

- **TakeItOut-style shulker retrieval.** When you need a block or item you don't carry loose, the mod pulls it out of a shulker box in your inventory.
- **Litematica container filling.** It fills chests, barrels, shulker boxes, hoppers, dispensers, droppers and similar containers so they match your Litematica schematic.

It also adds a new **auto-fill hotkey** and a **container highlight** that shows which schematic containers are correct, empty, partly filled or wrong. Open a container, press the key, and the mod fills every slot with the item, data components and count that the schematic expects. Items you don't carry loose are pulled from your shulker boxes first.

## Requirements

| Mod | Version |
|---|---|
| Fabric Loader | ≥ 0.19.5 |
| Fabric API | any 1.21.11 build (built against 0.141.6+1.21.11) |
| Litematica | ≥ 0.26.16 for 1.21.11 (client only) |
| MaLiLib | ≥ 0.27.20 for 1.21.11 (client only) |

**One jar for client and server.** On a **Fabric dedicated server**, put the same `cytra-container-<version>.jar` in the server's `mods` folder (with Fabric API) to enable the server-side features: shulker retrieval, linked storage, instant/area fill, server-read highlight status and Restock. The server doesn't need Litematica or MaLiLib; the client does. It isn't needed on the server in singleplayer or LAN. Keep the client and server on the same version.

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
- **No need to open containers:** the colours come from the server reading each container's contents, in singleplayer/LAN and on Fabric servers that also run this mod. On other servers, a container's colour is known once you've opened it (it's remembered afterwards), or live with **Servux** and Litematica's *entityDataSync* on.
- A container whose block differs from the schematic (e.g. a barrel instead of a chest) isn't highlighted.
- A container the schematic expects to be empty gets no box while it is empty. It turns red if something is put in it.
- **Through water and lava:** a box with only water or lava between you and the container (e.g. chests at the bottom of a pool) is drawn on top, so the liquid doesn't hide it. Walls still do, unless *Highlight Through Walls* is on. Turn this off with *Highlight Through Liquids*.
- Contents are re-read about once a second, nearest containers first, and ahead of a linked-storage refresh, so a large linked group doesn't leave the highlight unknown or out of date.
- Toggle the highlight with *Highlight Containers*, which has an optional hotkey. Colours, range, see-through mode and hiding green/grey boxes are all configurable.

### 3. Fill looked-at container (`V`): instant
Look at a schematic container and press **V**. With **Instant Fill** on (the default), the server fills every slot at once and the container is **never opened**. It takes items in this order:

1. your inventory,
2. shulker boxes in your inventory,
3. your linked containers (any distance, while their chunk is loaded),
4. shulker boxes stored in your linked containers.

Both shulker steps follow *Use Shulker Boxes*.

Double chests, crafter slot locks and *Clear Wrong Items* are handled the same way as the click-based fill. The action bar shows "Filled X slots, Y items missing" and chat lists what's missing.

**Area fill (`Shift + V`):** instant-fills every placed schematic container within *Area Fill Range* (default 16 blocks) that isn't already correct, nearest first, all at once and without opening anything. Nearer containers get items first if there aren't enough for all.

**Creative mode:** with *Creative Fill* on (the default), every fill gives containers exactly what the schematic expects, whether or not you have the items anywhere. Instant and area fill do this on the server, which checks that you're really in creative. The click-based fallback uses vanilla's creative inventory to make each item, then clicks it in.

Instant Fill and Area Fill need singleplayer/LAN, or a Fabric server that also runs this mod. Without either (or with Instant Fill turned off), `V` falls back to the original behaviour from Litematica-Container-Filler: it opens the container, fills it with clicks and closes it again (*Close After Look Fill*).

### 4. TakeItOut behaviour (shulker retrieval)
- **Turn it off on its own:** *Enable TakeItOut* at the top of the TakeItOut tab (on by default, optional toggle key). When it's off, nothing is pulled into your hand: no pulls from shulker boxes or linked containers on pick block or easy place, no Single-item Buffer top-ups and no Hotbar Refill. Fills, the highlight, the storage menu and Restock keep working. Fills still take from shulker boxes unless *Use Shulker Boxes* is off too.
- **Pick block from shulkers.** If you pick a block with vanilla middle-click, Litematica's schematic pick-block or easy place, and you don't carry it loose, the mod pulls it from a shulker box in your inventory into your hand.
- **Nothing is pulled just by looking at a block.** Items are only pulled when you use easy place or pick block (plus Hotbar Refill, Restock and the fills). TakeItOut's "Auto Take Out" look-to-pull mode was removed.
- It respects Litematica's `pickBlockableSlots` setting.
- It skips stacked shulker boxes and shows a warning.
- It waits for the server to answer, with a ping-based timeout, before trying again.

**How retrieval works (same as TakeItOut).** The client sends a `takeitout:getstack(slot, shulker)` request, and the **server** moves the item out of the shulker box. This works:
- in **singleplayer** and when **hosting a LAN world**, because this mod handles the request on the integrated server;
- on **Fabric servers** with this mod installed;
- on servers running TakeItOut's own server mod, since the channel and data format are the same.

On a server without either, the mod notices that the server doesn't accept the channel. It tells you once, then only uses items you carry loose.

### 5. Linked storage (TakeItOut-style storage menu)
Link containers once, then take items from them, or put items into them, **from any distance, as long as their chunk is loaded**. The server moves the items, so this works in singleplayer/LAN and on Fabric servers that also run this mod. Containers locked with a vanilla lock item stay locked.

- **Link:** look at a container and press `H` (press again to unlink). Double chests link both halves. **Box Select Corner:** press on two opposite corners to link every container in between. They are added to the group you have selected; nothing already linked is removed.
- **Storage menu** (`Y`):
  - *All Items*: everything in your linked containers, with search and counts. Left-click takes a stack, right-click takes 1, Shift + left-click takes all of that item.
  - *Containers*: link, unlink and delete, plus Delete All.
  - *Groups*: named sets, one active at a time.
  - *Sharing*: **Share** on one of your groups shares it with everyone on the server. It shows up under *Shared on this server* in every player's Groups tab, with who shared it and how many containers it has. **Add** puts a copy in your own groups and switches to it. Press **Update** to send your latest changes, or **Remove** to stop sharing it. Server operators can remove any shared group. The server keeps shared groups in the world folder (`data/containerautofill_shared_groups.json`), up to 20 per player. Sharing needs Cytra Container 1.3.0 or newer on the server.
  - *Settings*, *Refresh*, *Look At* (marks the containers holding the selected item for 10 s) and *Sort* (name or count).
- **Dump:** mark containers as dump targets (*Mark Dump Container*), then *Dump to Containers* moves your main inventory (not the hotbar) into them.
- **Outlines:** linked containers are outlined in green and dump containers in orange. You can toggle this and change the colours.
- **Shulker boxes inside linked containers:** items in a shulker box that's stored in a linked chest count as linked storage too. The storage menu lists and counts them, and pick block, easy place, Hotbar Refill, the menu and the fills can take from them. The server takes the items out and leaves the box in the chest. Loose items in linked containers are always used first. Stacked shulker boxes are skipped. This needs Cytra Container 1.2.0 or newer **on the server**; with an older server jar the client just doesn't use them.
- **Pulling from storage:** auto-fill, pick block and easy place pull missing items from linked containers too. *Single-item Mode* (`B`) makes pick block and easy place take a few items instead of a full stack: *Single-item Buffer* (default 3) sets how many. While you hold easy place, the next few are pulled before you run out, so placing doesn't wait for the server. You may end up with up to that many spare items. Set it to 1 for strictly one item per pull (slower on servers with high ping).
- **Speed:** pulled items are used the moment they arrive. With easy place, the block is placed as soon as the item reaches your hand, without waiting for the next click or tick. The server sends the item straight away and says if a slot turned out to be empty, so the next container is tried at once instead of after a 3 second wait. Pulls for different items can run at the same time. It also fixes a bug where, in single-item mode, the server sometimes didn't tell the client a new item had arrived, so an extra item was pulled and left in the inventory. On a server, single-item mode still needs one round trip per block, so it's limited by your ping.
- **Hotbar Refill** (on by default, TakeItOut tab, optional toggle key): when placing a block or using an item (pearls, snowballs...) uses up the last one in your hand, the slot is refilled with the same item. It looks in the rest of your inventory first, then shulker boxes you carry, then linked containers and the shulker boxes stored in them. Dropping an item doesn't trigger it, and it's off in creative and while Litematica's easy place is on (easy place picks its own items). Tools that break aren't refilled.
- Links are saved per world/server in `config/containerautofill/storage/`. There's no limit on how many containers you link.

### 6. Restock
Keeps your hotbar and offhand stacks topped up from **restock shulker boxes**: shulker boxes whose name contains *restock* (rename one in an anvil, e.g. "Restock Fireworks"). They can be carried in your inventory or kept in your **ender chest**, which you don't need to open.

- When a hotbar or offhand stack drops below *Restock Below* (default 16), it's filled back to a full stack. For items that stack to 16, like ender pearls, at most half a stack is used as the limit. Fireworks in the offhand for elytra flight never run out while the box has some.
- If you use the last item of a stack, the slot is refilled too.
- Restock has **its own config page** and its own on/off switch with an optional toggle key. It works even when the TakeItOut options, or the rest of the mod (*Enable Mod*), are off.
- The server moves the items, so it needs singleplayer/LAN, or this mod on a Fabric server. It doesn't work in creative.
- **Totems:** when a totem of undying in your offhand or hotbar pops, a new one from a restock box is put in the same slot straight away (*Restock Totems*). A totem you drop or move yourself isn't replaced.
- A message tells you when an item you've been restocking runs out in your restock boxes (*Warn When Empty*).

**Restock tab**

| Option | Default | Description |
|---|---|---|
| Restock | on | On/off, optional toggle key. |
| Restock Name | restock | A shulker box counts if its name contains this word (not case-sensitive). |
| Restock Below | 16 | Top a stack back up to full once it drops below this. |
| Restock Offhand | on | Also restock the offhand. |
| Restock Totems | on | Replace a totem of undying that pops, in the same slot. |
| From Inventory Shulkers | on | Use restock boxes in your inventory. |
| From Ender Chest | on | Use restock boxes in your ender chest. |
| Warn When Empty | on | Message when a restocked item runs out. |

## Hotkeys

| Hotkey | Default | Notes |
|---|---|---|
| Auto Fill Open Container | *(unbound)* | Only while a container screen is open. Press again to cancel. |
| Area Fill | `Shift` + `V` | Instant-fills every schematic container within Area Fill Range. |
| Fill Looked At Container | `V` | In game, no screen open. |
| Open Config GUI | `L` + `C` | The config is also listed in MaLiLib's config menu. |
| Open Storage Menu | `Y` | Linked storage menu. |
| Link Looked-at Container | `H` | Link or unlink. |
| Single-item Mode (toggle) | `B` | |
| Box Select Corner / Mark Dump Container / Dump to Containers / Linked Outlines | *(unbound)* | |

None of these defaults clash with vanilla, Litematica or MaLiLib defaults.

## Config options (MaLiLib, `config/containerautofill.json`)

| Option | Default | Description |
|---|---|---|
| Enable Mod | on | Turns every feature on or off. |
| Area Fill Range | 16 | Blocks around you that `Shift + V` fills. |
| Creative Fill | on | In creative, fill containers completely without needing the items. |
| Instant Fill | on | `V` fills the container server-side in one go without opening it. |
| Click Delay (ticks) | 1 | Ticks between automated clicks. 1 means one click per tick. Raise it if a server complains about fast clicking. |
| Clear Wrong Items | **off** | Shift-click unexpected items out of the container before filling. |
| Use TakeItOut Sources (TakeItOut tab) | on | While filling, use shulker boxes in your inventory and shulker boxes stored in linked containers. |
| Match Shulker Boxes By Content | off | A shulker box in a container slot counts as correct if its contents match (from LCF). |
| Close After Look Fill | on | Close the container after *Fill Looked At Container*. |
| Enable TakeItOut (TakeItOut tab) | on | Switch for the TakeItOut part only: when off, nothing is pulled into your hand (pick block, easy place, Single-item Buffer, Hotbar Refill). Fills, highlight, storage menu and Restock keep working. Optional toggle key. |
| Pick Block From Shulkers (TakeItOut tab) | on | TakeItOut's pick-block behaviour. |
| Hotbar Refill (TakeItOut tab) | on | Refill the hand's hotbar slot when its last item is placed or used. |
| Single-item Buffer (TakeItOut tab) | 3 | In Single-item Mode, how many items each pull from linked storage takes; easy place tops up before you run out. 1 = strictly one item. |
| Debug Logging | off | Logs every click and retrieval to `latest.log`. |

**Highlight tab**

| Option | Default | Description |
|---|---|---|
| Highlight Containers | on | Show the coloured boxes. Has an optional toggle hotkey. |
| Highlight Range | 32 | Blocks around you that are checked. |
| Highlight Through Walls | off | Draw boxes through other blocks. |
| Highlight Through Liquids | on | Draw a box on top when only water or lava is in the way. |
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
./gradlew build                 # build/libs/cytra-container-<version>.jar (client and server)
./gradlew runClientGameTest     # automated in-game tests (needs a display, or xvfb-run)
```
You need Java 21. The build uses the Gradle 9.8 wrapper and Fabric Loom 1.17.

## Credits

- **TakeItOut** by **Rofumer (Max Bel)**: <https://github.com/Rofumer/TakeItOut>. TakeItOut is *All Rights Reserved*, so **none of its code is included**. Its behaviour and its `takeitout:getstack` network format were reimplemented from scratch from its observed behaviour.
- **Litematica-Container-Filler** by **MimicEnzymes**: <https://github.com/MimicEnzymes/Litematica-Container-Filler> (LGPL-3.0-only). The schematic container reading, item matching and slot mapping are ported from it. Each ported file says where it came from and what was changed.
- **Litematica** and **MaLiLib** by masa, and **Fabric**.

## License

**LGPL-3.0-only** (see `LICENSE` and `COPYING.GPL`), because this mod contains code ported from Litematica-Container-Filler. Copyright (C) 2026 steelaspect.
