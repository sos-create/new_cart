package ru.soscreate.newcart;

import io.github.lumine1909.customworldheight.CustomWorldHeight;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.Queue;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NewCartPlugin extends CustomWorldHeight implements Listener {
    private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final int NEW_BOTTOM = -176;
    private static final int NEW_HEIGHT = 496;
    private static final int BEDROCK_Y = -164;
    private static final int DEEPSLATE_START = -163;
    private static final int DEEPSLATE_END = -64;

    // A small vertical slice is changed each tick. This avoids doing 25,856
    // block writes in one server tick for every chunk.
    private static final int LAYERS_PER_TICK = 2;

    private String targetWorldName;
    private final Deque<ChunkPos> queue = new ArrayDeque<>();
    private final Set<Long> queued = new HashSet<>();
    private ChunkTask activeTask;
    private long totalQueued;
    private long completed;
    private boolean migrationFinished;
    private NamespacedKey oreGeneratedKey;

    @Override
    public void onLoad() {
        migrateOldConfigIfNeeded();
        saveDefaultConfig();
        CustomWorldHeight.plugin = this;
    }

    private void migrateOldConfigIfNeeded() {
        File folder = getDataFolder();
        File configFile = new File(folder, "config.yml");
        if (!configFile.isFile()) return;

        YamlConfiguration current = YamlConfiguration.loadConfiguration(configFile);
        boolean legacyFormat = !current.contains("new-cart.world");
        String worldName = legacyFormat
                ? current.getString("world", "world")
                : current.getString("new-cart.world", "world");

        boolean invalidHeight = current.getInt("new-cart.min-y", NEW_BOTTOM) != NEW_BOTTOM
                || current.getInt("new-cart.height", NEW_HEIGHT) != NEW_HEIGHT;
        if (!legacyFormat && !invalidHeight) return;

        File backup = new File(folder, "config.yml.pre-height-fix.bak");
        try {
            if (!backup.exists()) Files.copy(configFile.toPath(), backup.toPath());
            if (legacyFormat) current = new YamlConfiguration();
            current.set("new-cart.world", worldName);
            current.set("new-cart.min-y", NEW_BOTTOM);
            current.set("new-cart.height", NEW_HEIGHT);
            current.set("new-cart.logical-height", 384);
            current.set("new-cart.cloud-height", "default");
            current.set("new-cart.dimension-type", "overworld");
            current.save(configFile);
            getLogger().warning("Corrected world-height settings to min-y=-176 and height=496. "
                    + "Previous config backed up as config.yml.pre-height-fix.bak");
        } catch (IOException ex) {
            getLogger().severe("Could not correct config.yml: " + ex.getMessage());
            throw new IllegalStateException("NewCart configuration correction failed; no world chunks were changed.", ex);
        }
    }

    @Override
    public void onEnable() {
        String minecraftVersion = Bukkit.getMinecraftVersion();
        if (!"1.21.4".equals(minecraftVersion)) {
            getLogger().severe("This build is made specifically for Paper 1.21.4; detected Minecraft " + minecraftVersion + ". Disabling safely.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        super.onEnable();
        oreGeneratedKey = new NamespacedKey(this, "deepslate_ores_generated_v1");

        targetWorldName = getConfig().getString("new-cart.world", "world");
        if (getConfig().getInt("new-cart.min-y", NEW_BOTTOM) != NEW_BOTTOM
                || getConfig().getInt("new-cart.height", NEW_HEIGHT) != NEW_HEIGHT) {
            getLogger().severe("This build requires min-y=-176 and height=496. No chunks were changed.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("NewCart 1.0.6 enabled: existing-world migration, automatic chunk processing and deepslate ore generation; "
                + "2 deepslate layers per tick; bedrock at Y=-164.");
        Bukkit.getScheduler().runTaskLater(this, this::beginMigration, 40L);
    }

    // Every chunk loaded after the plugin starts is added to the front of the
    // queue, so chunks the player visits are not left waiting behind a huge
    // old-world scan.
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!event.getWorld().getName().equals(targetWorldName)) return;
        Chunk chunk = event.getChunk();
        enqueue(new ChunkPos(chunk.getX(), chunk.getZ()), true);
    }

    private void beginMigration() {
        World world = Bukkit.getWorld(targetWorldName);
        if (world == null) {
            getLogger().severe("World '" + targetWorldName + "' is not loaded. No chunks were changed.");
            return;
        }
        if (world.getMinHeight() != NEW_BOTTOM || world.getMaxHeight() != 320) {
            getLogger().severe("World height is not active (reported min=" + world.getMinHeight()
                    + ", max=" + world.getMaxHeight() + "). Migration stopped to prevent damage.");
            return;
        }

        getLogger().info("World height verified: minY=-176 maxY=320. Scanning existing region chunks; "
                + "newly loaded chunks are also captured automatically.");
        Bukkit.getScheduler().runTaskTimer(this, this::processTick, 1L, 1L);
        for (Chunk loaded : world.getLoadedChunks()) {
            enqueue(new ChunkPos(loaded.getX(), loaded.getZ()), true);
        }
        // Finish the world-spawn chunk first on startup; once players join,
        // their newly loaded chunks are promoted ahead of the remaining queue.
        enqueue(new ChunkPos(world.getSpawnLocation().getBlockX() >> 4,
                world.getSpawnLocation().getBlockZ() >> 4), true);
        Path regionDir = world.getWorldFolder().toPath().resolve("region");
        if (!Files.isDirectory(regionDir)) {
            getLogger().warning("No region directory found; waiting for player-loaded chunks.");
            return;
        }

        long found = 0;
        try (var files = Files.list(regionDir)) {
            for (Path file : files.filter(p -> REGION_NAME.matcher(p.getFileName().toString()).matches()).toList()) {
                Matcher matcher = REGION_NAME.matcher(file.getFileName().toString());
                if (!matcher.matches()) continue;
                int regionX = Integer.parseInt(matcher.group(1));
                int regionZ = Integer.parseInt(matcher.group(2));
                try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                    if (raf.length() < 8192) continue;
                    for (int localZ = 0; localZ < 32; localZ++) {
                        for (int localX = 0; localX < 32; localX++) {
                            raf.seek((localX + localZ * 32) * 4L);
                            if (raf.readInt() != 0) {
                                enqueue(new ChunkPos(regionX * 32 + localX, regionZ * 32 + localZ), false);
                                found++;
                            }
                        }
                    }
                }
            }
        } catch (IOException ex) {
            getLogger().severe("Could not scan region files: " + ex.getMessage());
            return;
        }

        getLogger().info("Found " + found + " existing region entries. Pending unique chunks: " + queued.size()
                + ". Processing continues in small slices; player-loaded chunks have priority.");
    }

    private void enqueue(ChunkPos pos, boolean prioritize) {
        long key = chunkKey(pos.x, pos.z);

        // Do not enqueue a second copy of the chunk currently being sliced.
        if (prioritize && activeTask != null
                && activeTask.pos.x == pos.x && activeTask.pos.z == pos.z) {
            return;
        }

        if (!queued.add(key)) {
            if (prioritize) {
                // A player can enter a chunk that is already in the historical
                // backlog. Move that existing entry to the front instead of
                // silently ignoring the priority request.
                queue.removeIf(existing -> existing.x == pos.x && existing.z == pos.z);
                queue.addFirst(pos);
                migrationFinished = false;
            }
            return;
        }

        migrationFinished = false;
        if (prioritize) {
            queue.addFirst(pos);
        } else {
            queue.addLast(pos);
        }
        totalQueued++;
    }

    private void processTick() {
        World world = Bukkit.getWorld(targetWorldName);
        if (world == null) return;

        if (activeTask == null) {
            ChunkPos next = queue.poll();
            if (next == null) {
                if (!migrationFinished) {
                    migrationFinished = true;
                    getLogger().info("Migration complete: " + completed + " chunks completed; waiting for newly loaded chunks.");
                }
                return;
            }
            activeTask = new ChunkTask(next);
        }

        Chunk chunk;
        try {
            // Queue entries come either from an actual ChunkLoadEvent or a non-empty
            // Anvil region location-table entry. Do not gate on isChunkGenerated():
            // some Paper builds report false for valid region-backed chunks during startup.
            chunk = world.getChunkAt(activeTask.pos.x, activeTask.pos.z, false);
            if (chunk == null) {
                finishActive();
                return;
            }

            // Chunks migrated by older NewCart versions still need the ore pass.
            if (!activeTask.baseDone && isAlreadyMigrated(chunk)) {
                activeTask.baseDone = true;
                activeTask.nextY = DEEPSLATE_END + 1;
            }

            if (!activeTask.baseDone) {
                if (!activeTask.bedrockDone) {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            chunk.getBlock(x, BEDROCK_Y, z).setType(Material.BEDROCK, false);
                        }
                    }
                    activeTask.bedrockDone = true;
                }

                int endY = Math.min(DEEPSLATE_END, activeTask.nextY + LAYERS_PER_TICK - 1);
                for (int y = activeTask.nextY; y <= endY; y++) {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            chunk.getBlock(x, y, z).setType(Material.DEEPSLATE, false);
                        }
                    }
                }
                activeTask.nextY = endY + 1;
                activeTask.baseDone = activeTask.nextY > DEEPSLATE_END;
            }

            if (activeTask.baseDone) {
                ChunkPos finishedPos = activeTask.pos;
                if (!chunk.getPersistentDataContainer().has(oreGeneratedKey, PersistentDataType.BYTE)) {
                    Map<Material, Integer> placed = generateDeepslateOres(chunk, world);
                    chunk.getPersistentDataContainer().set(oreGeneratedKey, PersistentDataType.BYTE, (byte) 1);
                    int totalOres = placed.values().stream().mapToInt(Integer::intValue).sum();
                    getLogger().info("Ore generation finished: chunk " + finishedPos.x + "," + finishedPos.z
                            + "; total=" + totalOres + "; " + formatOreCounts(placed));
                }
                finishActive();
                getLogger().info("Chunk migration finished: " + finishedPos.x + "," + finishedPos.z);
                if (completed % 100 == 0) {
                    getLogger().info("Migration progress: " + completed + " chunks completed; "
                            + queue.size() + " waiting.");
                }
            }
        } catch (Throwable ex) {
            getLogger().severe("Chunk " + activeTask.pos.x + "," + activeTask.pos.z
                    + " migration slice failed; it will be retried: " + ex.getMessage());
            // Keep the task and cursor so a transient failure does not silently
            // discard the chunk or stop the complete migration queue.
        }
    }

    private Map<Material, Integer> generateDeepslateOres(Chunk chunk, World world) {
        long seed = world.getSeed()
                ^ ((long) chunk.getX() * 341873128712L)
                ^ ((long) chunk.getZ() * 132897987541L)
                ^ 0x9E3779B97F4A7C15L;
        Random random = new Random(seed);
        Map<Material, Integer> placed = new LinkedHashMap<>();

        placeOreType(chunk, random, placed, Material.DEEPSLATE_COAL_ORE, 1, 2, 6, 9, -86, -64, false);
        placeOreType(chunk, random, placed, Material.DEEPSLATE_COPPER_ORE, 2, 3, 6, 10, -125, -76, false);
        placeOreType(chunk, random, placed, Material.DEEPSLATE_IRON_ORE, 2, 4, 5, 9, -160, -68, false);
        placeOreType(chunk, random, placed, Material.DEEPSLATE_GOLD_ORE, 1, 2, 4, 7, -163, -112, false);
        placeOreType(chunk, random, placed, Material.DEEPSLATE_REDSTONE_ORE, 2, 3, 6, 10, -163, -122, false);
        placeOreType(chunk, random, placed, Material.DEEPSLATE_LAPIS_ORE, 1, 2, 4, 7, -150, -92, false);
        placeOreType(chunk, random, placed, Material.DEEPSLATE_DIAMOND_ORE, 1, 2, 3, 6, -163, -142, false);
        placeOreType(chunk, random, placed, Material.DEEPSLATE_EMERALD_ORE, 1, 1, 1, 3, -160, -112, true);
        return placed;
    }

    private void placeOreType(Chunk chunk, Random random, Map<Material, Integer> placed,
                              Material ore, int minVeins, int maxVeins,
                              int minSize, int maxSize, int minY, int maxY,
                              boolean mountainOnly) {
        int veins = minVeins + random.nextInt(maxVeins - minVeins + 1);
        int count = 0;
        for (int vein = 0; vein < veins; vein++) {
            int x = random.nextInt(16);
            int z = random.nextInt(16);
            int y = minY + random.nextInt(maxY - minY + 1);
            if (mountainOnly && !isMountainBiome(chunk.getBlock(x, y, z))) continue;

            int size = minSize + random.nextInt(maxSize - minSize + 1);
            for (int i = 0; i < size; i++) {
                Block block = chunk.getBlock(x, y, z);
                if (block.getType() == Material.DEEPSLATE) {
                    block.setType(ore, false);
                    count++;
                }
                x = Math.max(0, Math.min(15, x + random.nextInt(3) - 1));
                z = Math.max(0, Math.min(15, z + random.nextInt(3) - 1));
                y = Math.max(minY, Math.min(maxY, y + random.nextInt(3) - 1));
            }
        }
        placed.put(ore, count);
    }

    private boolean isMountainBiome(Block block) {
        String biome = block.getBiome().name();
        return biome.contains("MOUNTAIN") || biome.contains("PEAKS")
                || biome.contains("HILLS") || biome.contains("SLOPE");
    }

    private String formatOreCounts(Map<Material, Integer> placed) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<Material, Integer> entry : placed.entrySet()) {
            if (result.length() > 0) result.append(", ");
            result.append(entry.getKey().name()).append('=').append(entry.getValue());
        }
        return result.toString();
    }

    private boolean isAlreadyMigrated(Chunk chunk) {
        return chunk.getBlock(0, BEDROCK_Y, 0).getType() == Material.BEDROCK
                && chunk.getBlock(0, DEEPSLATE_START, 0).getType() == Material.DEEPSLATE
                && chunk.getBlock(0, DEEPSLATE_END, 0).getType() == Material.DEEPSLATE;
    }

    private void finishActive() {
        if (activeTask == null) return;
        queued.remove(chunkKey(activeTask.pos.x, activeTask.pos.z));
        completed++;
        activeTask = null;
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static final class ChunkTask {
        private final ChunkPos pos;
        private int nextY = DEEPSLATE_START;
        private boolean bedrockDone;
        private boolean baseDone;

        private ChunkTask(ChunkPos pos) {
            this.pos = pos;
        }
    }

    private record ChunkPos(int x, int z) {}
}
