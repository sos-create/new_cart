# NewCart — Paper 1.21.4

NewCart expands the existing standard Overworld downward by exactly 100 blocks. The expected original vanilla lower limit is Y=-64; the new limit is Y=-164, while the upper limit remains Y=319.

## Included dependency

The complete release bundle contains two plugins:
- `NewCart-1.0.0.jar`
- `CustomWorldHeight.jar` (Lumine1909, version 1.5.1; release declares Minecraft 1.20.5–1.21.7 support, including Paper 1.21.4)

CustomWorldHeight is required because Paper's Bukkit API does not expose a supported way for NewCart to change the world's dimension height by itself. Keep both JARs in the server's `plugins` folder.

## Block layout

- Y=-164: bedrock
- Y=-163 through Y=-64: exactly 100 layers of deepslate (the old bedrock floor at -64 is replaced)
- Y=-63 and above: left untouched

The plugin scans existing Overworld region files and processes only chunks already stored there. It does not generate or regenerate chunks.

## Installation

1. Stop the server and make a complete backup of the entire world folder.
2. Extract the release ZIP.
3. Copy **both** JAR files from the included `plugins` folder into the server's `plugins` folder. Do not rename either JAR.
4. Start the server once. On its first start, CustomWorldHeight creates its config and disables itself; NewCart cannot load until the dependency is enabled.
5. Stop the server and start it again. Confirm the console shows CustomWorldHeight enabled and NewCart enabled. NewCart will register the expanded height and begin processing existing chunks.
6. Wait for NewCart's `Migration complete` console message before treating the migration as finished.

If Paper reports `Unknown/missing dependency [CustomWorldHeight]`, the CustomWorldHeight JAR is not installed in the active server's `plugins` directory, or its first-start setup has not completed. Check that the file is named `CustomWorldHeight.jar` and that the server was restarted after its config was generated.

Test on a copy first. A dimension-height change affects world metadata and chunk storage. This build targets the standard vanilla Overworld with min Y=-64 and max Y=319. It stops rather than editing chunks if the active world height does not match the expected values.

## Build

Java 21: `gradle clean build`. The GitHub Actions release bundle additionally includes the compatible CustomWorldHeight dependency.
