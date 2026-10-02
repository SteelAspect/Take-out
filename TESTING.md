# TESTING — Container Auto Fill (MC 1.21.11)

## A. Automated checks (run on every change)

| # | Check | How | Expected |
|---|---|---|---|
| A1 | Build | `./gradlew clean build --warning-mode all` | `BUILD SUCCESSFUL`, 0 javac warnings, 0 Gradle deprecations, no missing-dependency messages |
| A2 | No nested jars | `unzip -l build/libs/containerautofill-*.jar \| grep META-INF/jars` | no output |
| A3 | Declared dependencies | `unzip -p build/libs/containerautofill-*.jar fabric.mod.json` | `depends` = fabricloader, minecraft, fabric-api, litematica, malilib; `breaks` = takeitout, litematica_container_filler |
| A4 | No original packages | `unzip -l … \| grep -E 'maxbel\|mimicenzymes\|litematicafiller'` | no output |
| A5 | Mixin targets remapped | `javap -v` on `ClientPlayerInteractionManagerMixin.class` | targets intermediary names (`method_2896` = `interactBlock`) |
| A6 | Click planner | Exhaustive simulation of `ClickPlanner` with vanilla PICKUP rules: every source count 1..64, target 0..63, need 1..64, with max stack sizes 64, 16 and 1 | Every case reaches the exact target count with an empty cursor. The total item count never changes. Worst case is 18 clicks (135,297 cases). |

## B. In-game test setup

1. Put only Fabric API, Litematica 0.26.16+, MaLiLib 0.27.20+ and this jar in `mods/`. When run from source, `./gradlew runClient` loads exactly these.
2. Create a creative test world and build a "reference" storage area: a single chest, a double chest, a barrel, a shulker box, a hopper and a dispenser. Fill them with:
   - stacks of different sizes (1, 7, 16, 63, 64),
   - items with components (a renamed item, an enchanted book, a damaged tool, a filled shulker box),
   - empty slots between filled ones.
3. Save it with Litematica (Area Selection → Save Schematic). Remove the area, place the schematic with a placement offset, and rebuild the containers **empty** at the placement position.
4. Switch to **survival** and give yourself the needed items. Put some of them only inside shulker boxes in your inventory.
5. Bind *Auto Fill Open Container* (config: `L`+`C` → Hotkeys), e.g. to `G`.
6. Turn on *Debug Logging* to see every click in `logs/latest.log`.

## C. In-game test cases

| # | Case | Steps | Expected result |
|---|---|---|---|
| C1 | Single chest | Open the empty chest and press the hotkey | Every slot matches the schematic: same item, components and count, in the same slot. Action bar shows `Filled N slots, 0 items missing`. |
| C2 | Double chest | Open either half and press the hotkey | All 54 slots are filled. The left/right halves aren't swapped, whichever half was clicked. |
| C3 | Shulker box | Place an empty shulker box at its schematic position, open it and press the hotkey | All 27 slots are filled. A filled shulker box *inside* the container matches only if its contents are equal (or with *Match Shulker Boxes By Content*). |
| C4 | Barrel | Same as C1 with a barrel | Filled. |
| C5 | Hopper | Same as C1 with a hopper (5 slots) | Filled. |
| C6 | Dispenser | Same as C1 with a dispenser (9 slots), and again with a dropper | Filled. |
| C7 | Partial fill | Put half of the expected items in by hand, some in the wrong slots, then press the hotkey | Missing counts are topped up exactly. Correct partial stacks aren't overfilled. |
| C8 | Already filled | Press the hotkey on a container that already matches | No clicks in the debug log. `Filled 0 slots, 0 items missing`. |
| C9 | Wrong items, *Clear Wrong Items* **off** | Put dirt in a slot that expects stone, and in a slot the schematic leaves empty | Dirt stays in both slots. Chat says `1 slot(s) hold items the schematic doesn't expect…`. The other slots are filled. |
| C10 | Wrong items, *Clear Wrong Items* **on** | Same as C9 with the option on | Dirt is shift-clicked into your inventory and the slot is filled with stone. The dirt in the unexpected slot is also removed. |
| C11 | Items only in shulkers (singleplayer) | Expected items exist only inside a shulker box in your inventory. Keep at least one inventory slot free. | Action bar shows the item being requested. The item appears in your main hand and is clicked into the container. Repeats until done. |
| C12 | Items only in shulkers (server with TakeItOut) | Same as C11 on a Paper server running the TakeItOut companion plugin, or a Fabric server with TakeItOut | Same as C11. |
| C13 | Items missing entirely | Remove some expected items from both inventory and shulkers | Everything else is filled. The action bar shows `…, Y items missing`. Chat lists ` - 12x Oak Planks` etc. |
| C14 | Screen closed mid-fill | Set Click Delay to 10, start a fill, press `E`/`Esc` halfway | Stops right away with `Auto-fill cancelled: the container was closed.` No item is dropped, and the cursor stack goes back to your inventory. |
| C15 | Cancel by hotkey | Press the hotkey again while filling | `Auto-fill cancelled.` plus a summary. |
| C16 | Anti-cheat server | Paper with an inventory anti-cheat (e.g. Grim). Click Delay 1, then 2 | No kicks or flags at delay ≥ 1 (one click per tick). If flagged, raise the delay. The debug log shows at most one `clickSlot` per tick. |
| C17 | Not in a placement | Open a chest outside any placement (or disable the placement) and press the hotkey | Action bar: `This container is not part of an active schematic placement`. No clicks. |
| C18 | Block mismatch | Place a barrel where the schematic has a chest | Action bar: `The schematic expects Chest here`. No clicks. |
| C19 | Fill looked-at (LCF feature) | Look at an empty schematic chest and press `V` | It opens, fills and closes. |
| C20 | Pick block from shulker (TakeItOut) | Survival, stone only inside a shulker box: middle-click real stone | Stone ends up in your hand. |
| C21 | Schematic pick / easy place | Litematica easy place on, stone only in a shulker: use on a schematic stone block | The first use requests the stone, then easy place places it. |
| C22 | Auto Take Out (R) | Press `R`, look at a schematic block you only have in a shulker | Chat shows `Auto Take Out is ON` and the block is pulled into your hand. |
| C23 | Server without TakeItOut | Vanilla/Paper server without the plugin, item only in a shulker | One chat warning that the server doesn't accept shulker requests. Only loose items are used and the rest is reported missing. No kicks. |
| C24 | Stacked shulkers | Two identical shulker boxes stacked together contain the item | Warning `Stacked shulker boxes can't be taken from…`. Nothing requested. |
| C25 | Inventory full | Fill every inventory slot, item only in a shulker | No request is sent. Chat notes that your inventory is full. |
| C26 | Hotkey outside containers | Press the auto-fill key in your own inventory screen, or with no screen | `Open a container first`, or nothing (the key is GUI-only). |

## D. Dependency / launch tests

| # | Case | Steps | Expected |
|---|---|---|---|
| D1 | Only allowed mods | Launch with Fabric API, Litematica, MaLiLib, this mod | Game reaches the title screen; `latest.log` shows `containerautofill 1.0.0+1.21.11` loaded |
| D2 | Litematica missing | Remove Litematica from `mods/` | Fabric's "incompatible mods" screen/error: *Mod 'Container Auto Fill' requires any version ≥0.26.16 of mod 'litematica', which is missing* (no crash) |
| D3 | Original mods installed alongside | Add TakeItOut or Litematica-Container-Filler | Fabric refuses to start with a clear "breaks" message naming the conflicting mod |

## E. Results log

Record each run here (date, version, environment, pass/fail per case).

| Date (UTC) | Version | Environment | Results |
|---|---|---|---|
| 2026-10-02 | 1.0.0+1.21.11 | Cloud build container (Java 21, no display) | A1–A6 pass |
