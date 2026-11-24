package ru.soscreate.newcart;

import io.github.lumine1909.customworldheight.CustomWorldHeight;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NewCartPlugin extends CustomWorldHeight {
    private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final int OLD_BOTTOM = -64;
    private static final int NEW_BOTTOM = -176;
    private static final int NEW_HEIGHT = 496;
    private static final int BEDROCK_Y = -164;
    private static final int DEEPSLATE_START = -163;
    private int chunksPerTick;

    @Override
    public void onLoad() {
        migrateOldConfigIfNeeded();
        // Initialize the embedded engine without its first-run self-disable/restart cycle.
        // The parent onLoad only sets this static plugin reference and disables itself
        // when config.yml is absent; we create the default config here instead.
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

            // Paper/Minecraft 1.21.4 requires minY and height to align to 16-block sections.
            // minY=-176 and height=496 keep maxY=319 while preserving the requested
            // 100 deepslate layers (-163..-64) and bedrock at -164.
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
        // Registers the embedded CustomWorldHeight world-init handler.
        super.onEnable();

        String worldName = getConfig().getString("new-cart.world", "world");
        chunksPerTick = Math.max(1, getConfig().getInt("chunks-per-tick", 1));
        if (getConfig().getInt("new-cart.min-y", NEW_BOTTOM) != NEW_BOTTOM
                || getConfig().getInt("new-cart.height", NEW_HEIGHT) != NEW_HEIGHT) {
            getLogger().severe("This build requires min-y=-176 and height=496. No chunks were changed.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("NewCart is running as a single plugin. Target world: " + worldName
                + "; minY=-176; maxY=319; height=496.");
        Bukkit.getScheduler().runTaskLater(this, () -> beginMigration(worldName), 40L);
    }

    private void beginMigration(String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            getLogger().severe("World '" + worldName + "' is not loaded. No chunks were changed.");
            return;
        }
        if (world.getMinHeight() != NEW_BOTTOM || world.getMaxHeight() != 320) {
            getLogger().severe("World height is not active (reported min=" + world.getMinHeight()
                    + ", max=" + world.getMaxHeight() + "). Migration stopped to prevent damage.");
            return;
        }

        Path regionDir = world.getWorldFolder().toPath().resolve("region");
        if (!Files.isDirectory(regionDir)) {
            getLogger().warning("No region directory found; no existing chunks to migrate.");
            return;
        }

        Queue<ChunkPos> queue = new ArrayDeque<>();
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
                            if (raf.readInt() != 0) queue.add(new ChunkPos(regionX * 32 + localX, regionZ * 32 + localZ));
                        }
                    }
                }
            }
        } catch (IOException ex) {
            getLogger().severe("Could not scan region files: " + ex.getMessage());
            return;
        }

        int total = queue.size();
        getLogger().info("Found " + total + " existing chunks. Adding the new 96-block layer incrementally.");
        processBatch(world, queue, total, 0);
    }

    private void processBatch(World world, Queue<ChunkPos> queue, int total, int done) {
        int processed = 0;
        while (processed < chunksPerTick && !queue.isEmpty()) {
            ChunkPos pos = queue.remove();
            try {
                if (world.isChunkGenerated(pos.x, pos.z)) {
                    var chunk = world.getChunkAt(pos.x, pos.z, false);
                    if (chunk != null) {
                        for (int x = 0; x < 16; x++) {
                            for (int z = 0; z < 16; z++) {
                                int blockX = (pos.x << 4) + x;
                                int blockZ = (pos.z << 4) + z;
                                world.getBlockAt(blockX, BEDROCK_Y, blockZ).setType(Material.BEDROCK, false);
                                for (int y = DEEPSLATE_START; y <= OLD_BOTTOM; y++) {
                                    world.getBlockAt(blockX, y, blockZ).setType(Material.DEEPSLATE, false);
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ex) {
                getLogger().warning("Chunk " + pos.x + "," + pos.z + " failed: " + ex.getMessage());
            }
            processed++;
            done++;
        }

        if (queue.isEmpty()) {
            world.save();
            getLogger().info("Migration complete: " + done + "/" + total
                    + " existing region entries processed. Y=-164 is bedrock; Y=-163..-64 is 100 deepslate layers; Y>=-63 was not modified.");
        } else {
            int nextDone = done;
            Bukkit.getScheduler().runTaskLater(this, () -> processBatch(world, queue, total, nextDone), 1L);
        }
    }

    private record ChunkPos(int x, int z) {}
}
