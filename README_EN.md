# TLM JEI Bridge

One-click altar crafting for **Touhou Little Maid**, straight from JEI.

Tired of placing six ingredients onto the altar pedestals by hand? Open an altar recipe in JEI, click the **+** button, and the mod gathers the ingredients, places them on the altar, and starts the craft for you.

## Supported Versions

| Minecraft | Loader | JEI | Branch |
|---|---|---|---|
| 1.20.1 | Forge 47.4.0+ | 15.20+ | `master` |
| 1.21.1 | NeoForge 21.1+ | 19.9+ | `1.21.1` |

**Tested with**

- **1.20.1** — Touhou Little Maid 1.5.3, L2Backpack 2.6.0, Sophisticated Backpacks 3.26.12.2206
- **1.21.1** — Touhou Little Maid 1.5.3, L2Backpack 3.1.5, Sophisticated Backpacks 3.25.64.1919

Nearby versions usually work as well. If you run into an incompatibility, please report the exact versions.

## Requirements

- Minecraft with Forge (1.20.1) or NeoForge (1.21.1)
- JEI
- Touhou Little Maid
- **Multiplayer:** install the mod on both the client and the server, using the same version (the network protocol must match)

## Installation

1. Install Forge / NeoForge for your Minecraft version.
2. Install JEI and Touhou Little Maid.
3. Drop the matching `tlmjei-*.jar` into the `mods` folder and launch the game.

## Features

### One-click altar crafting

When you view a Touhou Little Maid altar recipe in JEI, the **+** transfer button appears. Clicking it:

- gathers the required ingredients from every available source,
- places them on the six altar pedestals,
- and triggers the altar craft. The result is spawned at the centre of the altar.

The **+** button is available on:

- the player inventory, crafting table, chests and ender chests (vanilla menus),
- L2Backpack backpack, dimensional storage and quick-swap screens,
- Sophisticated Backpacks screens.

> The button is shown in supported container screens. The transfer itself requires a completed altar nearby; otherwise the mod reports that no altar was found.

### Craftable recipes first

When the altar category opens, JEI puts recipes you can craft right now at the top — counting ingredients in your inventory, backpacks, Curios, nearby containers and other supported sources. Recipes with missing ingredients follow, ordered by the percentage of ingredients you already match.

- Availability is bulk-checked on the server ahead of time, so the order uses real results instead of client-side guesses.
- This uses JEI's built-in `CRAFTABLE` sorter stage. It is part of the default `RecipeSorterStages` in JEI's client config; removing it from that list disables the sorting.
- While a server reply is still pending, that recipe is treated as not craftable so it cannot jump to the front by mistake.

### Smart ingredient handling

- Ingredients already on the pedestals are kept and reused when they match the recipe, so nothing is taken twice.
- If ingredients are missing, the **+** button behaves like the vanilla crafting grid transfer: it is disabled and the missing recipe slots are highlighted in red. It re-enables automatically once you have everything.
- Availability is checked on the server and includes your inventory, backpacks, nearby containers, ender/dimensional storage and Curios, so items the client cannot see are still counted. If you manage to click before the state refreshes, the server still refuses the craft.
- Pedestals must contain exactly the recipe ingredients. Remove unrelated leftovers before crafting (this is a Touhou Little Maid altar rule).

### Ingredient sources (search order)

1. **Player inventory** — main inventory, hotbar, armor slots and offhand
2. **L2Backpack** — backpacks, dimensional storage, ender backpack and drawers
3. **Sophisticated Backpacks** — all tiers
4. **Curios** — L2 / Sophisticated Backpacks worn in curio slots
5. **Nearby containers** — chests, barrels, etc. within 32 blocks

Backpacks stored inside other containers are opened and searched recursively, including backpack-in-backpack setups.

### Shrine lamp power support

Altar recipes consume P points. The mod always uses the player's own P points first. If they are not enough, the shortfall is taken from nearby shrine lamps (maid beacons), nearest first.

If the lamps in range cannot cover the entire shortfall, no lamp power is consumed at all and the original "not enough power" behavior is preserved.

## Configuration

Config file: `config/tlmjei-common.toml`

```toml
[altar]
    # Radius (blocks) to search for a nearby altar. Default 8, range 1-64.
    searchRange = 8.0

[containers]
    # Radius (blocks) to search nearby containers for ingredients. 0 disables. Default 32, range 0-64.
    searchRange = 32.0

[shrineLamp]
    # Radius (blocks) to search nearby shrine lamps for missing power. 0 disables. Default 8, range 0-64.
    searchRange = 8.0
```

In multiplayer, the server's configuration is used.

## Notes

- The altar multiblock must be complete (six pedestals) before the transfer can work.
- The mod does not bypass Touhou Little Maid's recipe requirements or power cost; it only automates gathering the ingredients, placing them and starting the craft.
- The result is generated by the altar itself, so recipe outputs (items or entities) behave exactly as they do in the original mod.

## Credits

Created by Toi_et_moi (威严な大小姐) and Deepseek.
