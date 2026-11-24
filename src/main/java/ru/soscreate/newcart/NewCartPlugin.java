package ru.soscreate.newcart;

import io.github.lumine1909.customworldheight.api.BaseDimensionType;
import io.github.lumine1909.customworldheight.api.Height;
import io.github.lumine1909.customworldheight.api.Identifier;
import io.github.lumine1909.customworldheight.api.WorldHeightService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NewCartPlugin extends JavaPlugin {
    private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final int OLD_BOTTOM = -64;
    private static final int NEW_BOTTOM = -164;
    private static final int NEW_HEIGHT = 484;
    private int chunksPerTick;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        String worldName = getConfig().getString("world", "world");
        chunksPerTick = Math.max(1, getConfig().getInt("chunks-per-tick", 1));
        if (getConfig().getInt("old-bottom-y", OLD_BOTTOM) != OLD_BOTTOM || getConfig().getInt("added-depth", 100) != 100) {
            getLogger().severe("This build targets the standard Paper 1.21.4 Overworld bottom Y=-64 and exactly 100 added blocks. No changes made.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            WorldHeightService.get().registerWorld(
                Identifier.of("new_cart", "expanded_overworld"), worldName,
                new Height(NEW_HEIGHT, NEW_BOTTOM, 384, Height.CloudHeight.DEFAULT),
                BaseDimensionType.OVERWORLD, true
            );
        } catch (Throwable error) {
            getLogger().severe("Cannot register new world height. Install a compatible CustomWorldHeight build for Paper 1.21.4. No chunks were changed.");
            error.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("Registered Overworld: minY=-164, maxY=319, height=484.");
        Bukkit.getScheduler().runTaskLater(this, () -> beginMigration(worldName), 40L);
    }

    private void beginMigration(String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            getLogger().severe("World '" + worldName + "' is not loaded. No chunks were changed.");
            return;
        }
        if (world.getMinHeight() != NEW_BOTTOM || world.getMaxHeight() != 320) {
            getLogger().severe("World height is not active (reported min=" + world.getMinHeight() + ", max=" + world.getMaxHeight() + "). Migration stopped to prevent damage.");
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
                Matcher m = REGION_NAME.matcher(file.getFileName().toString());
                if (!m.matches()) continue;
                int rx = Integer.parseInt(m.group(1));
                int rz = Integer.parseInt(m.group(2));
                try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                    if (raf.length() < 8192) continue;
                    for (int localZ = 0; localZ < 32; localZ++) {
                        for (int localX = 0; localX < 32; localX++) {
                            raf.seek((localX + localZ * 32) * 4L);
                            if (raf.readInt() != 0) queue.add(new ChunkPos(rx * 32 + localX, rz * 32 + localZ));
                        }
                    }
                }
            }
        } catch (IOException ex) {
            getLogger().severe("Could not scan region files: " + ex.getMessage());
            return;
        }
        int total = queue.size();
        getLogger().info("Found " + total + " existing chunks. Adding the new 100-block layer incrementally.");
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
                                int bx = (pos.x << 4) + x;
                                int bz = (pos.z << 4) + z;
                                world.getBlockAt(bx, NEW_BOTTOM, bz).setType(Material.BEDROCK, false);
                                for (int y = NEW_BOTTOM + 1; y <= OLD_BOTTOM; y++) {
                                    world.getBlockAt(bx, y, bz).setType(Material.DEEPSLATE, false);
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
            getLogger().info("Migration complete: " + done + "/" + total + " region entries processed. Only the old bedrock layer at Y=-64 was replaced; blocks at Y >= -63 were not touched.");
        } else {
            int nextDone = done;
            Bukkit.getScheduler().runTaskLater(this, () -> processBatch(world, queue, total, nextDone), 1L);
        }
    }

    private record ChunkPos(int x, int z) {}
}
