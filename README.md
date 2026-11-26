# NewCart — Paper 1.21.4

NewCart is a single self-contained Paper plugin JAR. The CustomWorldHeight engine is embedded in the JAR; a second height plugin is not required.

## What it changes

NewCart expands the **already existing Overworld** downward:

- Minimum Y: `-176`
- Maximum Y: `319` (exclusive API limit: `320`)
- Bedrock floor: `Y=-164`
- Deepslate: `Y=-163..-64` (100 layers)
- `Y=-63` and everything above it are left unchanged by the migration.

It adds deterministic deepslate ore veins in the new lower layer range: coal, copper, iron, gold, redstone, lapis, diamond and mountain-biome emerald ore. Ore placement only replaces DEEPSLATE, so it does not overwrite an existing ore or a non-deepslate block. Each chunk receives this ore pass once.

## Existing worlds and upgrades

- The plugin processes chunks already recorded in the world's `region/*.mca` files and chunks loaded later by players.
- The region location tables are read asynchronously; block changes are performed on Paper's server thread.
- Processing is gradual: two deepslate layers per server tick.
- Chunk-level persistent checkpoints are written after each vertical slice. A normal restart resumes from the saved Y cursor instead of restarting that chunk from the bottom.
- Failed chunks are retried without blocking the whole queue. After eight consecutive failures they are quarantined; loading that chunk by a player triggers another attempt.
- Player-loaded chunks are promoted ahead of the historical backlog, and a coordinate is queued only once even when several players load the same chunk.
- The region-file scanner runs asynchronously with a bounded four-batch buffer; the main-thread historical backlog is capped at 2,048 chunks. When the backlog is full, scanning waits instead of accumulating an unbounded list in RAM.
- Migration work adapts to Paper's one-minute TPS sample: it pauses below 18 TPS, processes one layer per tick from 18 to 19.5 TPS, and processes at most two layers per tick at 19.5 TPS or higher. A paused task releases its temporary chunk ticket and resumes from its saved cursor after TPS recovers.
- Chunks already migrated by NewCart 1.0.5 or 1.0.6 are recognized and receive the one-time ore pass without rebuilding the deepslate layers.

The plugin does not regenerate the world and does not require a new world folder. **Back up the complete world before changing its dimension height.** Never lower the expanded height after the world has been opened with NewCart.

## Installation

1. Stop the Paper 1.21.4 server.
2. Make a full backup of the server and the existing Overworld folder.
3. Remove old `NewCart-*.jar` and separate `CustomWorldHeight*.jar` copies from `plugins` to avoid duplicate height handlers.
4. Put only `NewCart-1.0.8.jar` in `plugins`.
5. Start the server and check the console for `World height verified: minY=-176 maxY=320`.
6. Let the gradual migration finish. The final summary reports processed chunks, ore blocks generated and any quarantined chunks.

The default world folder is `world`. If the Overworld folder has another name, change `new-cart.world` in `plugins/NewCart/config.yml`, then restart.

## Chunk delivery and resource limits

NewCart prioritizes its own terrain migration for chunks already loaded by players; it does not create a second network packet queue. Paper remains responsible for sending chunk packets to clients, reusing loaded chunk data, and controlling packet rates. The public Paper plugin API does not expose the client's distant-chunk render queue, so NewCart cannot force the client to draw far chunks sooner.

The bounded region buffer and 2,048-entry historical backlog limit NewCart's additional memory use. The TPS guard limits its block-edit work but does not change Paper's view distance, chunk-send rate, worker-thread count, or server-wide chunk cache.

## Compatibility and limits

This build deliberately targets **Paper 1.21.4 / Java 21** and safely disables itself on another Minecraft version. It is not a Folia build and should not be used on Folia. A successful CI smoke test covers Paper 1.21.4, an already-created world, checkpoint/restart recovery, the lower bedrock/deepslate positions, preservation at Y=-63, ore generation and a separately loaded chunk.

The smoke test cannot guarantee compatibility with every third-party plugin, custom world generator, damaged region file or modified server fork. Keep the backup until you have checked the world in-game.
