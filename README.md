# NewCart — Paper 1.21.4

NewCart expands the existing standard Overworld downward by 100 blocks. The expected original vanilla lower limit is Y=-64; the new limit is Y=-164, while the upper limit remains Y=319.

## Required dependency

Install **CustomWorldHeight** (Paper plugin) by Lumine1909 before NewCart. NewCart uses its API to register the dimension height before the world is loaded:
https://github.com/Lumine1909/CustomWorldHeight/releases

Keep CustomWorldHeight installed after migration. NewCart declares it as a hard dependency and will not start without it.

## Block layout

- Y=-164: bedrock
- Y=-163 through Y=-65: deepslate
- Y=-64 and above: left untouched

The plugin scans existing Overworld region files and incrementally processes only chunks already stored there. It does not regenerate existing chunks.

## Installation

1. Stop the server and make a complete backup of the world folder.
2. Install a CustomWorldHeight release compatible with Paper 1.21.4.
3. Put NewCart into the plugins folder.
4. Start the server and watch the console for the registered height and migration-complete messages.

Test on a copy first. A dimension-height change affects world metadata and chunk storage. This initial release targets the standard vanilla Overworld with min Y=-64 and max Y=319.

## Build

Java 21: gradle clean build. Output: build/libs/NewCart-1.0.0.jar.
