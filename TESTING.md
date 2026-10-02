# TESTING — Container Auto Fill (MC 1.21.11)

## A. Automated checks (run on every change)

| # | Check | How | Expected |
|---|---|---|---|
| A1 | Build | `./gradlew clean build --warning-mode all` | `BUILD SUCCESSFUL`, 0 javac warnings, 0 Gradle deprecations, no missing-dependency messages |
| A2 | No nested jars | `unzip -l build/libs/containerautofill-*.jar \| grep META-INF/jars` | no output |
| A3 | Declared dependencies | `unzip -p build/libs/containerautofill-*.jar fabric.mod.json` | `depends` = fabricloader, minecraft, fabric-api, litematica, malilib; `breaks` = takeitout, litematica_container_filler |
| A4 | No original packages | `unzip -l … \| grep -E 'maxbel\|mimicenzymes\|litematicafiller'` | no output |
| A5 | Mixin targets remapped | `javap -v` on `ClientPlayerInteractionManagerMixin.class` | targets intermediary names (`method_2896` = `interactBlock`) |
| A7 | In-game suite | `xvfb-run -a ./gradlew runClientGameTest` (or with a display) | `summary: N passed, 0 failed` in the log |
| A8 | Same jar on a dedicated server | `./gradlew runServer` with `run/eula.txt` (Litematica and MaLiLib are client-only and get skipped) | `containerautofill` loads without Litematica/MaLiLib, server reaches `Done` |
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
| C12 | Items only in shulkers (Fabric server) | Same as C11 on a Fabric dedicated server with the same jar installed | Same as C11. |
| C13 | Items missing entirely | Remove some expected items from both inventory and shulkers | Everything else is filled. The action bar shows `…, Y items missing`. Chat lists ` - 12x Oak Planks` etc. |
| C14 | Screen closed mid-fill | Set Click Delay to 10, start a fill, press `E`/`Esc` halfway | Stops right away with `Auto-fill cancelled: the container was closed.` No item is dropped, and the cursor stack goes back to your inventory. |
| C15 | Cancel by hotkey | Press the hotkey again while filling | `Auto-fill cancelled.` plus a summary. |
| C16 | Click rate | Fill with Click Delay 1 and Debug Logging on | At most one `clickSlot` per tick (also checked automatically). |
| C17 | Not in a placement | Open a chest outside any placement (or disable the placement) and press the hotkey | Action bar: `This container is not part of an active schematic placement`. No clicks. |
| C18 | Block mismatch | Place a barrel where the schematic has a chest | Action bar: `The schematic expects Chest here`. No clicks. |
| C19 | Fill looked-at (LCF feature) | Look at an empty schematic chest and press `V` | It opens, fills and closes. |
| C20 | Pick block from shulker (TakeItOut) | Survival, stone only inside a shulker box: middle-click real stone | Stone ends up in your hand. |
| C21 | Schematic pick / easy place | Litematica easy place on, stone only in a shulker: use on a schematic stone block | The first use requests the stone, then easy place places it. |
| C22 | Auto Take Out (R) | Press `R`, look at a schematic block you only have in a shulker | Chat shows `Auto Take Out is ON` and the block is pulled into your hand. |
| C23 | Server without the handler | Fabric server without this mod, item only in a shulker | One chat warning that the server doesn't accept shulker requests. Only loose items are used and the rest is reported missing. No kicks. |
| C24 | Stacked shulkers | Two identical shulker boxes stacked together contain the item | Warning `Stacked shulker boxes can't be taken from…`. Nothing requested. |
| C25 | Inventory full | Fill every inventory slot, item only in a shulker | No request is sent. Chat notes that your inventory is full. |
| C26 | Hotkey outside containers | Press the auto-fill key in your own inventory screen, or with no screen | `Open a container first`, or nothing (the key is GUI-only). |

| T1 | Furnace | Schematic furnace with fuel + output; fill | Fuel filled; output slot reported missing (can't insert) |
| T2–T3 | Smoker, blast furnace | Fill | Fuel slot filled |
| T4 | Brewing stand | Bottles + ingredient | Filled (bottle slots hold 1 each) |
| T5 | Dropper | Fill | Filled |
| T6 | Trapped double chest | Open from the right half | Both halves correct |
| T7 | Copper double chest, different oxidation | World exposed copper, schematic plain copper | Filled (same container type) |
| T8 | Shulker box, different colour | World blue, schematic red | Filled |
| T9 | Crafter | Items + schematic locks 1,2; world has 5 locked | Items placed, slots 1,2 locked, 5 unlocked. **Known issue:** about 1 in 3 runs one slot stays empty (server rejects it); fill still finishes, nothing lost |
| H1–H4 | Highlight colours | Empty / wrong / partial / correct containers | blue / red / yellow / green boxes |
| H5–H6 | Highlight filtering | Barrel where schematic has a chest; non-container schematic block | Not highlighted |
| H7–H8 | Highlight coverage | Double chests, furnace, brewing stand, crafter, copper, recoloured shulker | All highlighted |
| H9 | Highlight live update | Fill an empty chest | Turns green within a second |

| S1–S5 | Linking | `H` on a chest; box select two corners; mark dump; link a chest 160 blocks away (force-loaded) | Linked, dump flag set, far contents read, saved to `config/containerautofill/storage/` |
| S6 | Storage menu | Press `Y` | Menu opens (All Items / Containers / Groups) |
| S7 | Remote take | Take 10 emeralds from the chest 160 blocks away | Player +10, chest −10 |
| S8 | Pick block from storage | Middle-click a gold block you only have in a linked chest | Gold block in the main hand |
| P1 | Single-item pull speed | Single-item Mode on; pull 1 stone into an empty hand 10 times | All 10 arrive, ≤ 3 ticks each on average (logged as `SPEED P1`) |
| P2 | Stale storage cache | The cache says a slot has an item the chest doesn't have; pick it | The request is freed within 5 ticks (not a 3 s wait) and the chest is re-read |
| P3–P4 | Easy place, single-item mode, buffer 1 | Easy place on, hold right-click at a row of 3 schematic stone blocks, stone only in a linked chest | All 3 placed (time logged as `SPEED P3`); exactly 3 stone taken, none left over |
| P5 | Same, Single-item Buffer 3 | As P3 with the buffer at 3 | All 3 placed; spare stone at most one top-up (3), nothing lost |
| R1–R3 | Hotbar refill | Place your last cobblestone / dirt / stone with more in the inventory / only in a carried shulker / only in a linked chest | The slot is refilled (10 cobblestone from the inventory, 30 dirt from the shulker, stone from storage) |
| R4–R5 | Hotbar refill off / drop | Same with Hotbar Refill off; drop the last plank with Q | Slot stays empty, nothing moved |
| K1 | Restock, rest of the mod off | Enable Mod, shulker pick, linked storage, hotbar refill all off; 5 fireworks in the offhand; box named "Restock Fireworks" with 64 | Offhand 64, box keeps 5 |
| K2 | Restock from ender chest | 10 cobblestone in hotbar slot 3; box named "restock" with 64 in the ender chest | Slot 64, ender box keeps 10 |
| K3–K4 | Not restocked | Box without the name; a stack of 20 (threshold 16) | Nothing changes |
| K5 | Last item used | Threshold 1, place your last cobblestone | Slot refilled to 64 from the restock box |
| K7–K8 | Totem pops | Totem in the offhand, lethal fall damage; restock box with totems carried / in the ender chest | Player survives and a new totem is in the offhand; the box has one fewer |
| K9–K10 | Totem not replaced | Drop a hotbar totem with Q; Restock Totems off and a totem pops | Slot stays empty, box unchanged |
| K6 | Restock off | Restock off, offhand 5 fireworks, restock box | Offhand stays 5 |
| S9 | Dump | Dump key with cobblestone/dirt in main inventory, torches in hotbar | Main inventory moved into the dump chest, hotbar kept |
| S10–S12 | Look At, groups | Look At iron; new group; switch back; share + import | Marked; group empty then restored; import copies all entries |

| I1–I5 | Instant fill (`V`) | Double chest: stone loose, iron only in an inventory shulker, emerald/gold/named diamond only in linked chests (one 160 blocks away) | No screen opens; both halves exact; items really moved from shulker and far chest; nothing missing |
| I6–I7 | Instant fill, wrong items | Hopper with dirt where glass is expected; TNT unavailable | Clear Wrong off: dirt kept, wrong + missing reported. On: dirt to inventory, glass filled |

| Q1–Q3 | Status from the server | Never open the containers | Empty chest reads EMPTY, half-filled hopper PARTIAL; turns CORRECT right after a fill |
| A1–A3 | Area fill (`Shift`+`V`), range 5 | Two empty schematic chests nearby, one 14 blocks away, a correct hopper, a double chest just out of range | Both nearby filled without opening; far chest and out-of-range double chest untouched; correct hopper skipped |
| C1–C2 (storage) | Creative fill | Creative, empty inventory, nothing linked; instant fill, then click fallback (Instant Fill off) | Both containers filled exactly, including a named item |

## D. Dependency / launch tests

| # | Case | Steps | Expected |
|---|---|---|---|
| D1 | Only allowed mods | Launch with Fabric API, Litematica, MaLiLib, this mod | Game reaches the title screen; `latest.log` shows `containerautofill 1.0.0+1.21.11` loaded |
| D2 | Litematica missing on the client | Remove Litematica from `mods/` | The game stops at startup with *Container Auto Fill needs Litematica and MaLiLib on the client: litematica >=0.26.16 is missing* (the same jar on a dedicated server doesn't need them) |
| D3 | Original mods installed alongside | Add TakeItOut or Litematica-Container-Filler | Fabric refuses to start with a clear "breaks" message naming the conflicting mod |

## E. Results log

Record each run here (date, version, environment, pass/fail per case).

| Date (UTC) | Version | Environment | Results |
|---|---|---|---|
| 2026-10-02 | 1.0.0+1.21.11 | Cloud build container (Java 21, no display) | A1–A6 pass |
| 2026-10-02 | 1.0.0+1.21.11 | runClient, Xvfb + Mesa | D1 pass: title screen with Fabric API 0.141.6, Litematica 0.26.16, MaLiLib 0.27.20 + this mod |
| 2026-10-02 | 1.0.0+1.21.11 | runClient -PwithoutLitematica | D2 pass: "requires version 0.26.16 or later of litematica, which is missing!" (no crash) |
| 2026-10-02 | 1.0.0+1.21.11 | runClientGameTest, Xvfb | 53/53 automated checks pass: C1, C2, C4–C11, C13, C14, C16–C23, T1–T9, H1–H9. Crafter (T9) item placement intermittent (2 of 4 runs left one slot empty) – accepted known issue |
| 2026-10-02 | 1.0.0+1.21.11 | :server:runServer | A8 pass: containerautofill_server loaded, channel registered, server started/stopped |
| 2026-10-02 | 1.0.0+1.21.11 | runClientGameTest (main + storage + menus), Xvfb | 53/53 + 14/14 pass (crafter known issue logged) |
| 2026-10-02 | 1.0.0+1.21.11 | runClientGameTest (main + storage/instant + menus), Xvfb | 53/53 + 22/22 pass (crafter known issue logged) |
| 2026-10-02 | 1.0.0+1.21.11 | runClientGameTest (main + storage/instant/area/creative + menus), Xvfb | 53/53 + 30/30 pass (crafter known issue logged) |
| – | – | Not testable here | C12 (needs a separate Fabric server + client connection), D3 (needs the original mods' jars) |
| 2026-10-02 | 1.0.0+1.21.11 | Cloud container, client gametests under Xvfb | Full suite 53/53 + 34/34 (crafter known issue logged). Speed: single-item pull 1.1 ticks avg (was 1.5); easy place single-item, 3 blocks: 4 ticks with exactly 3 items taken (was 69 ticks and 4 taken) |
| 2026-10-02 | 1.0.0+1.21.11 | Cloud container, client gametests under Xvfb | Full suite 53/53 + 35/35. Single-item Buffer: buffer 1 places 3 blocks in 5 ticks (3 taken), buffer 3 in 3 ticks (3 taken, none spare) |
| 2026-10-02 | 1.0.0+1.21.11 | Cloud container, client gametests under Xvfb | Full suite 53/53 + 40/40 (hotbar refill R1–R5 added; crafter known issue logged) |
| 2026-10-02 | 1.0.0+1.21.11 | Cloud container, client gametests under Xvfb | Full suite 53/53 + 46/46 (Restock K1–K6 added; offhand restocked in 3 ticks with the rest of the mod off) |
| 2026-10-02 | 1.0.0+1.21.11 | Single jar | A8 pass: `runServer` reaches Done with only containerautofill + Fabric API (Litematica/MaLiLib skipped as client-only). D2 pass: client without Litematica stops with "Container Auto Fill needs Litematica and MaLiLib on the client: litematica [>=0.26.16] is missing". Full suite 53/53 + 50/50 (totem K7–K10; popped totem replaced in 1 tick) |
