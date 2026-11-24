# NewCart — Paper 1.21.4

NewCart is a **single self-contained Paper plugin JAR**. The CustomWorldHeight engine is embedded inside NewCart; no second plugin is required.

## Existing worlds

NewCart targets an already created, standard Paper 1.21.4 Overworld. You do not need to create a new world, reset the world, or regenerate existing chunks. The height engine is initialized during the server's startup lifecycle, before the world is initialized, and applies the expanded dimension to the existing world.

Default world folder name: `world`. Change `new-cart.world` in the generated NewCart config if your Overworld folder has another name.

## World layout

- New minimum Y: **-176**
- Maximum Y: **319** (unchanged)
- Y=-164: bedrock
- Y=-163 through Y=-64: 100 layers of deepslate
- Y=-63 and above: untouched

Only chunks already present in the world's `region/*.mca` files are processed. Chunks are handled gradually using `chunks-per-tick: 1`; new chunks remain under normal Paper world generation.

## Installation

1. Stop the server and make a complete backup of the existing world folder.
2. Remove the old separate `CustomWorldHeight.jar` and old `NewCart-*.jar` from `plugins` to avoid duplicate height handlers.
3. Put only the new `NewCart-1.0.2.jar` in `plugins`.
4. Start the server. On a NewCart creates its config automatically on first startup; no manual restart cycle is required.
5. Check the console for NewCart's height and migration messages. Wait for `Migration complete` before considering the block migration finished.

If upgrading from the previous two-JAR NewCart package, the old NewCart config is backed up as `plugins/NewCart/config.yml.pre-single-jar.bak` and migrated automatically. Keep the world backup until the server has been checked in-game.

## Safety notes

This is a dimension-height change, not merely a block edit. Test on a copy first. Do not lower the world height after expanding it. The plugin aborts the block migration if the active world's reported height is not minY=-176 and maxY=320. The GitHub build verifies packaging and class inclusion; only a live Paper server test can confirm runtime behavior on a particular server.
