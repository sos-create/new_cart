package ru.soscreate.newcart;

import io.github.lumine1909.customworldheight.CustomWorldHeight;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NewCartPlugin extends CustomWorldHeight implements Listener {
    private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final int NEW_BOTTOM = -176;
    private static final int NEW_HEIGHT = 496;
    private static final int WORLD_MAX_HEIGHT = 320;
    private static final int BEDROCK_Y = -164;
    private static final int DEEPSLATE_START = -163;
    private static final int DEEPSLATE_END = -64;
    private static final int LAYERS_PER_TICK = 2;
    private static final int SCAN_BATCH_SIZE = 512;
    private static final int SCAN_BATCHES_PER_TICK = 2;
    private static final int MAX_CHUNK_FAILURES = 8;

    private String targetWorldName;
    private final Deque<ChunkPos> queue = new ArrayDeque<>();
    private final Set<Long> queued = new HashSet<>();
    private final Map<Long, Integer> failureCounts = new HashMap<>();
    private final Set<Long> quarantined = new HashSet<>();
    private final ConcurrentLinkedQueue<List<ChunkPos>> discoveredBatches = new ConcurrentLinkedQueue<>();

    private ChunkTask activeTask;
    private boolean migrationStarted;
    private boolean migrationFinished;
    private boolean checkpointNoticeLogged;
    private boolean scanInProgress;
    private volatile boolean regionScanCompleted;
    private volatile long regionEntriesFound;
    private volatile String regionScanFailure;
    private long tickCounter;
    private long completed;
    private long generatedOreChunks;
    private long generatedOreBlocks;

    private NamespacedKey bedrockKey;
    private NamespacedKey baseMigrationKey;
    private NamespacedKey migrationCursorKey;
    private NamespacedKey oreGeneratedKey;

    @Override
    public void onLoad() {
        migrateOldConfigIfNeeded();
        saveDefaultConfig();
        // CustomWorldHeight's onLoad also assigns this static reference.
        // Its first-start auto-disable is intentionally bypassed because
        // NewCart creates/migrates the config before the height engine starts.
        CustomWorldHeight.plugin = this;
    }

    private void migrateOldConfigIfNeeded() {
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.isFile()) return;

        YamlConfiguration current = YamlConfiguration.loadConfiguration(configFile);
        boolean legacyFormat = !current.contains("new-cart.world");
        String worldName = legacyFormat
                ? current.getString("world", "world")
                : current.getString("new-cart.world", "world");

        boolean invalidHeight = current.getInt("new-cart.min-y", NEW_BOTTOM) != NEW_BOTTOM
                || current.getInt("new-cart.height", NEW_HEIGHT) != NEW_HEIGHT;
        if (!legacyFormat && !invalidHeight) return;

        File backup = new File(getDataFolder(), "config.yml.pre-height-fix.bak");
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
            throw new IllegalStateException(
                    "NewCart configuration correction failed; no world chunks were changed.", ex);
        }
    }

    @Override
    public void onEnable() {
        String minecraftVersion = Bukkit.getMinecraftVersion();
        if (!"1.21.4".equals(minecraftVersion)) {
            getLogger().severe("This build is made specifically for Paper 1.21.4; detected Minecraft "
                    + minecraftVersion + ". Disabling safely.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        super.onEnable();

        bedrockKey = new NamespacedKey(this, "bedrock_floor_generated_v1");
        baseMigrationKey = new NamespacedKey(this, "deepslate_base_generated_v1");
        migrationCursorKey = new NamespacedKey(this, "deepslate_migration_cursor_v1");
        oreGeneratedKey = new NamespacedKey(this, "deepslate_ores_generated_v1");

        targetWorldName = getConfig().getString("new-cart.world", "world").trim();
        if (targetWorldName.isEmpty()
                || getConfig().getInt("new-cart.min-y", NEW_BOTTOM) != NEW_BOTTOM
                || getConfig().getInt("new-cart.height", NEW_HEIGHT) != NEW_HEIGHT
                || getConfig().getInt("new-cart.logical-height", 384) != 384
                || !"overworld".equalsIgnoreCase(getConfig().getString("new-cart.dimension-type", "overworld"))) {
            getLogger().severe("Invalid NewCart dimension settings. Required: world name, min-y=-176, "
                    + "height=496, logical-height=384, dimension-type=overworld. No chunks were changed.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("NewCart 1.0.7 enabled for Paper 1.21.4: persistent chunk checkpoints, "
                + "safe retries, asynchronous region scan and deepslate ore generation.");
        Bukkit.getScheduler().runTaskLater(this, this::beginMigration, 40L);
    }

    @Override
    public void onDisable() {
        releaseActiveTicket();
        super.onDisable();
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!event.getWorld().getName().equals(targetWorldName)) return;

        Chunk chunk = event.getChunk();
        long key = chunkKey(chunk.getX(), chunk.getZ());
        if (quarantined.remove(key)) {
            failureCounts.remove(key);
            getLogger().warning("Retrying previously quarantined chunk after it was loaded: "
                    + chunk.getX() + "," + chunk.getZ());
        }
        enqueue(new ChunkPos(chunk.getX(), chunk.getZ()), true);
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        if (!migrationStarted && event.getWorld().getName().equals(targetWorldName)) {
            Bukkit.getScheduler().runTask(this, this::beginMigration);
        }
    }

    private void beginMigration() {
        if (migrationStarted || !isEnabled()) return;

        World world = Bukkit.getWorld(targetWorldName);
        if (world == null) {
            getLogger().warning("World '" + targetWorldName
                    + "' is not loaded yet. NewCart will start when that world loads.");
            return;
        }
        if (world.getMinHeight() != NEW_BOTTOM || world.getMaxHeight() != WORLD_MAX_HEIGHT) {
            getLogger().severe("World height is not active (reported min=" + world.getMinHeight()
                    + ", max=" + world.getMaxHeight() + "). Migration stopped to prevent damage.");
            return;
        }

        migrationStarted = true;
        getLogger().info("World height verified: minY=-176 maxY=320. Existing-world migration is starting; "
                + "chunk data is checkpointed so an interrupted migration can resume.");

        for (Chunk loaded : world.getLoadedChunks()) {
            enqueue(new ChunkPos(loaded.getX(), loaded.getZ()), true);
        }
        enqueue(new ChunkPos(world.getSpawnLocation().getBlockX() >> 4,
                world.getSpawnLocation().getBlockZ() >> 4), true);

        Bukkit.getScheduler().runTaskTimer(this, this::processTick, 1L, 1L);

        Path regionDir = world.getWorldFolder().toPath().resolve("region");
        if (!Files.isDirectory(regionDir)) {
            regionScanCompleted = true;
            getLogger().warning("No region directory found; waiting for player-loaded chunks.");
            return;
        }

        scanInProgress = true;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> scanRegionDirectory(regionDir));
    }

    /**
     * Reads Anvil location tables away from the server thread. Only batches of
     * chunk coordinates cross back to the tick thread; no Bukkit world API is
     * touched by this worker.
     */
    private void scanRegionDirectory(Path regionDir) {
        long found = 0;
        List<ChunkPos> batch = new ArrayList<>(SCAN_BATCH_SIZE);
        try (var files = Files.list(regionDir)) {
            Iterator<Path> iterator = files.iterator();
            while (iterator.hasNext() && !Thread.currentThread().isInterrupted()) {
                Path file = iterator.next();
                Matcher matcher = REGION_NAME.matcher(file.getFileName().toString());
                if (!matcher.matches()) continue;

                int regionX = Integer.parseInt(matcher.group(1));
                int regionZ = Integer.parseInt(matcher.group(2));
                byte[] locations = new byte[4096];

                try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                    if (raf.length() < 8192L) continue;
                    raf.seek(0L);
                    raf.readFully(locations);
                }

                for (int index = 0; index < 1024; index++) {
                    int offset = index * 4;
                    int sectorOffset = ((locations[offset] & 0xFF) << 16)
                            | ((locations[offset + 1] & 0xFF) << 8)
                            | (locations[offset + 2] & 0xFF);
                    int sectorCount = locations[offset + 3] & 0xFF;
                    if (sectorOffset == 0 || sectorCount == 0) continue;

                    int localX = index & 31;
                    int localZ = index >>> 5;
                    batch.add(new ChunkPos(regionX * 32 + localX, regionZ * 32 + localZ));
                    found++;

                    if (batch.size() >= SCAN_BATCH_SIZE) {
                        discoveredBatches.add(List.copyOf(batch));
                        batch.clear();
                    }
                }
            }
        } catch (Exception ex) {
            regionScanFailure = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        } finally {
            if (!batch.isEmpty()) discoveredBatches.add(List.copyOf(batch));
            regionEntriesFound = found;
            regionScanCompleted = true;
        }
    }

    private void drainDiscoveredChunks() {
        for (int i = 0; i < SCAN_BATCHES_PER_TICK; i++) {
            List<ChunkPos> batch = discoveredBatches.poll();
            if (batch == null) break;
            for (ChunkPos pos : batch) enqueue(pos, false);
        }

        if (scanInProgress && regionScanCompleted && discoveredBatches.isEmpty()) {
            scanInProgress = false;
            if (regionScanFailure != null) {
                getLogger().severe("Region scan finished with an error after finding "
                        + regionEntriesFound + " entries: " + regionScanFailure
                        + ". Loaded chunks will still be processed; restart after fixing disk access "
                        + "to retry the remaining region scan.");
            } else {
                getLogger().info("Region scan complete: " + regionEntriesFound
                        + " existing chunk entries found; unique pending chunks=" + queued.size() + ".");
            }
        }
    }

    private void enqueue(ChunkPos pos, boolean prioritize) {
        long key = chunkKey(pos.x, pos.z);

        if (prioritize) {
            quarantined.remove(key);
            failureCounts.remove(key);
        }

        if (activeTask != null && activeTask.pos.x == pos.x && activeTask.pos.z == pos.z) {
            return;
        }

        if (!queued.add(key)) {
            if (prioritize) {
                queue.removeIf(existing -> existing.x == pos.x && existing.z == pos.z);
                queue.addFirst(pos);
                migrationFinished = false;
            }
            return;
        }

        migrationFinished = false;
        if (prioritize) queue.addFirst(pos);
        else queue.addLast(pos);
    }

    private void processTick() {
        if (!migrationStarted || !isEnabled()) return;
        tickCounter++;
        drainDiscoveredChunks();

        World world = Bukkit.getWorld(targetWorldName);
        if (world == null) return;

        if (activeTask == null) {
            ChunkPos next = queue.pollFirst();
            if (next == null) {
                if (!scanInProgress && !migrationFinished) {
                    migrationFinished = true;
                    getLogger().info("Migration complete: " + completed + " chunks completed; "
                            + "new ore blocks generated=" + generatedOreBlocks
                            + " in " + generatedOreChunks + " chunks; quarantined chunks="
                            + quarantined.size() + ". Waiting for newly loaded chunks.");
                }
                return;
            }
            activeTask = new ChunkTask(next);
        }

        try {
            ChunkTask task = activeTask;
            if (!task.ticketAdded) {
                task.ticketWorld = world;
                task.ticketAdded = world.addPluginChunkTicket(task.pos.x, task.pos.z, this);
            }

            Chunk chunk = world.getChunkAt(task.pos.x, task.pos.z, false);
            if (chunk == null) {
                throw new IllegalStateException("Paper did not return the region-backed chunk");
            }

            boolean baseDone = migrateBaseSlice(chunk, task);
            if (baseDone) {
                if (!chunk.getPersistentDataContainer().has(oreGeneratedKey, PersistentDataType.BYTE)) {
                    Map<Material, Integer> placed = generateDeepslateOres(chunk, world);
                    chunk.getPersistentDataContainer().set(oreGeneratedKey, PersistentDataType.BYTE, (byte) 1);
                    int count = placed.values().stream().mapToInt(Integer::intValue).sum();
                    generatedOreBlocks += count;
                    generatedOreChunks++;
                }
                finishActive();
            }
        } catch (Exception ex) {
            retryOrQuarantine(ex);
        }
    }

    /**
     * Returns true after the bedrock and all 100 deepslate layers are complete.
     * The cursor and completion flags live in the chunk PDC, so a normal stop
     * or crash can resume at the last saved layer instead of restarting at -163.
     */
    private boolean migrateBaseSlice(Chunk chunk, ChunkTask task) {
        PersistentDataContainer data = chunk.getPersistentDataContainer();
        if (data.has(baseMigrationKey, PersistentDataType.BYTE)) return true;

        Integer savedY = data.get(migrationCursorKey, PersistentDataType.INTEGER);
        if (savedY == null && isAlreadyMigrated(chunk)) {
            // Compatibility with chunks fully migrated by NewCart 1.0.5/1.0.6.
            data.set(baseMigrationKey, PersistentDataType.BYTE, (byte) 1);
            return true;
        }

        if (!data.has(bedrockKey, PersistentDataType.BYTE)) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    chunk.getBlock(x, BEDROCK_Y, z).setType(Material.BEDROCK, false);
                }
            }
            data.set(bedrockKey, PersistentDataType.BYTE, (byte) 1);
        }

        int nextY = savedY == null ? DEEPSLATE_START : savedY;
        if (nextY < DEEPSLATE_START || nextY > DEEPSLATE_END + 1) {
            getLogger().warning("Invalid saved migration cursor for chunk " + task.pos.x + ","
                    + task.pos.z + " (Y=" + nextY + "); safely restarting that chunk from Y="
                    + DEEPSLATE_START + ".");
            nextY = DEEPSLATE_START;
        } else if (savedY != null && task.lastCursor == null) {
            getLogger().info("Resuming chunk " + task.pos.x + "," + task.pos.z
                    + " from Y=" + nextY + ".");
        }

        if (nextY > DEEPSLATE_END) {
            data.set(baseMigrationKey, PersistentDataType.BYTE, (byte) 1);
            data.remove(migrationCursorKey);
            return true;
        }

        int endY = Math.min(DEEPSLATE_END, nextY + LAYERS_PER_TICK - 1);
        for (int y = nextY; y <= endY; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    chunk.getBlock(x, y, z).setType(Material.DEEPSLATE, false);
                }
            }
        }

        int cursor = endY + 1;
        if (cursor > DEEPSLATE_END) {
            data.set(baseMigrationKey, PersistentDataType.BYTE, (byte) 1);
            data.remove(migrationCursorKey);
            return true;
        }

        data.set(migrationCursorKey, PersistentDataType.INTEGER, cursor);
        task.lastCursor = cursor;
        if (!checkpointNoticeLogged) {
            checkpointNoticeLogged = true;
            getLogger().info("Migration checkpoint saved for chunk " + task.pos.x + ","
                    + task.pos.z + "; next layer Y=" + cursor + ".");
        }
        return false;
    }

    private void retryOrQuarantine(Exception error) {
        if (activeTask == null) return;

        ChunkTask failed = activeTask;
        long key = chunkKey(failed.pos.x, failed.pos.z);
        int attempts = failureCounts.merge(key, 1, Integer::sum);
        releaseActiveTicket();
        activeTask = null;

        if (attempts <= MAX_CHUNK_FAILURES) {
            queue.addLast(failed.pos);
            if (attempts == 1 || attempts == 4 || attempts == MAX_CHUNK_FAILURES) {
                getLogger().warning("Chunk " + failed.pos.x + "," + failed.pos.z
                        + " failed (" + attempts + "/" + MAX_CHUNK_FAILURES + "): "
                        + error.getClass().getSimpleName() + ": " + error.getMessage()
                        + ". It was moved to the queue tail; other chunks will continue.");
            }
            return;
        }

        queued.remove(key);
        failureCounts.remove(key);
        quarantined.add(key);
        getLogger().severe("Chunk " + failed.pos.x + "," + failed.pos.z
                + " failed " + MAX_CHUNK_FAILURES + " times and was quarantined so it cannot block "
                + "the world migration. It will be retried automatically when loaded by a player. Cause: "
                + error.getClass().getSimpleName() + ": " + error.getMessage());
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

    private boolean isAlreadyMigrated(Chunk chunk) {
        return chunk.getBlock(0, BEDROCK_Y, 0).getType() == Material.BEDROCK
                && chunk.getBlock(0, DEEPSLATE_START, 0).getType() == Material.DEEPSLATE
                && chunk.getBlock(0, DEEPSLATE_END, 0).getType() == Material.DEEPSLATE;
    }

    private void finishActive() {
        if (activeTask == null) return;
        long key = chunkKey(activeTask.pos.x, activeTask.pos.z);
        releaseActiveTicket();
        queued.remove(key);
        failureCounts.remove(key);
        completed++;
        activeTask = null;

        if (completed % 100 == 0) {
            getLogger().info("Migration progress: " + completed + " chunks completed; "
                    + queue.size() + " waiting; generated ore blocks=" + generatedOreBlocks + ".");
        }
    }

    private void releaseActiveTicket() {
        if (activeTask == null || !activeTask.ticketAdded || activeTask.ticketWorld == null) return;
        try {
            activeTask.ticketWorld.removePluginChunkTicket(
                    activeTask.pos.x, activeTask.pos.z, (Plugin) this);
        } catch (RuntimeException ex) {
            getLogger().warning("Could not release chunk ticket for "
                    + activeTask.pos.x + "," + activeTask.pos.z + ": " + ex.getMessage());
        }
        activeTask.ticketAdded = false;
        activeTask.ticketWorld = null;
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static final class ChunkTask {
        private final ChunkPos pos;
        private boolean ticketAdded;
        private World ticketWorld;
        private Integer lastCursor;

        private ChunkTask(ChunkPos pos) {
            this.pos = pos;
        }
    }

    private record ChunkPos(int x, int z) {}
}
