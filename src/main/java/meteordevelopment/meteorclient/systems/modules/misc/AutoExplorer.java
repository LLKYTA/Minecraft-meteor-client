/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.misc;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.RouteRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.ElytraPlus;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.io.*;
import java.util.*;

public class AutoExplorer extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgScan = settings.createGroup("Scan");

    // General
    public final Setting<ScanMode> scanMode = sgGeneral.add(new EnumSetting.Builder<ScanMode>()
        .name("scan-mode")
        .description("The scanning pattern to use.")
        .defaultValue(ScanMode.Spiral)
        .onChanged(s -> generatePath())
        .build()
    );

    public final Setting<Integer> radius = sgGeneral.add(new IntSetting.Builder()
        .name("radius")
        .description("Scan radius in chunks.")
        .defaultValue(500)
        .min(10)
        .sliderMax(2000)
        .onChanged(i -> generatePath())
        .build()
    );

    public final Setting<Integer> chunkStep = sgGeneral.add(new IntSetting.Builder()
        .name("chunk-step")
        .description("Distance between waypoints in chunks.")
        .defaultValue(3)
        .min(1)
        .sliderMax(16)
        .onChanged(i -> generatePath())
        .build()
    );

    public final Setting<Boolean> saveExplored = sgGeneral.add(new BoolSetting.Builder()
        .name("save-explored")
        .description("Save explored chunks to file and skip them.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> autoActivateElytra = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-activate-elytra")
        .description("Automatically enable ElytraPlus when exploring.")
        .defaultValue(true)
        .build()
    );

    // Internal state
    private final List<Vec3> path = new ArrayList<>();
    private int currentPointIndex = 0;
    private final Set<ChunkPos> exploredChunks = new HashSet<>();
    private boolean finished = false;
    private RouteRenderer routeRenderer;

    public AutoExplorer() {
        super(Categories.Misc, "auto-explorer", "Automatically explores the world using elytra flight.");
    }

    @Override
    public void onActivate() {
        // Load explored chunks
        if (saveExplored.get()) loadExploredChunks();

        // Get or create RouteRenderer
        routeRenderer = new RouteRenderer();
        MeteorClient.EVENT_BUS.subscribe(routeRenderer);

        // Generate initial path
        generatePath();

        // Activate ElytraPlus
        if (autoActivateElytra.get()) {
            ElytraPlus elytraPlus = Modules.get().get(ElytraPlus.class);
            if (!elytraPlus.isActive()) elytraPlus.toggle();
            elytraPlus.mode.set(ElytraPlus.Mode.FollowRoute);
        }

        currentPointIndex = 0;
        finished = false;
    }

    @Override
    public void onDeactivate() {
        // Save explored chunks
        if (saveExplored.get()) saveExploredChunks();

        // Remove route renderer
        if (routeRenderer != null) {
            MeteorClient.EVENT_BUS.unsubscribe(routeRenderer);
            routeRenderer.clearWaypoints();
            routeRenderer.setActive(false);
        }

        // Turn off forward key
        mc.options.keyUp.setDown(false);

        currentPointIndex = 0;
        path.clear();
        finished = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (finished || path.isEmpty()) return;

        ElytraPlus elytraPlus = Modules.get().get(ElytraPlus.class);

        // Check if ElytraPlus is ready for next waypoint
        if (!elytraPlus.isActive()) {
            if (autoActivateElytra.get()) {
                elytraPlus.toggle();
                elytraPlus.mode.set(ElytraPlus.Mode.FollowRoute);
            }
            return;
        }

        if (!elytraPlus.hasTarget()) {
            // Send next waypoint
            if (currentPointIndex < path.size()) {
                Vec3 nextPoint = path.get(currentPointIndex);
                elytraPlus.setTarget(nextPoint);

                // Mark chunk as explored
                if (saveExplored.get()) {
                    ChunkPos cp = new ChunkPos((int) nextPoint.x >> 4, (int) nextPoint.z >> 4);
                    exploredChunks.add(cp);
                }

                currentPointIndex++;
            } else {
                // All waypoints completed
                finished = true;
                elytraPlus.clearTarget();
                elytraPlus.toggle();
                info("Exploration complete! Explored " + exploredChunks.size() + " chunks.");
            }
        }

        // Update route renderer to show remaining path
        if (routeRenderer != null && currentPointIndex < path.size()) {
            routeRenderer.setWaypoints(path.subList(currentPointIndex, path.size()));
        }
    }

    private void generatePath() {
        path.clear();
        if (mc.player == null) return;

        ChunkPos origin = mc.player.chunkPosition();

        switch (scanMode.get()) {
            case Spiral -> generateSpiralPath(origin);
            case SnakeGrid -> generateSnakeGridPath(origin);
        }

        // Filter out already explored chunks
        if (saveExplored.get()) {
            path.removeIf(p -> {
                ChunkPos cp = new ChunkPos((int) p.x >> 4, (int) p.z >> 4);
                return exploredChunks.contains(cp);
            });
        }

        // Update route renderer
        if (routeRenderer != null) {
            routeRenderer.setWaypoints(path);
            routeRenderer.setActive(true);
            routeRenderer.setLineColor(0xFF00FF00); // Green
        }

        info("Generated " + path.size() + " waypoints for exploration.");
    }

    private void generateSpiralPath(ChunkPos origin) {
        int r = radius.get();
        int step = chunkStep.get();
        int x = 0, z = 0;
        int dx = 0, dz = -1;

        for (int i = 0; i < r * r; i++) {
            if (-r/2 <= x && x <= r/2 && -r/2 <= z && z <= r/2) {
                // Only place waypoint every `step` chunks
                if (i % step == 0) {
                    double worldX = (origin.x() + x) * 16 + 8;
                    double worldZ = (origin.z() + z) * 16 + 8;
                    path.add(new Vec3(worldX, 0, worldZ));
                }
            }

            if (x == z || (x < 0 && x == -z) || (x > 0 && x == 1 - z)) {
                int temp = dx;
                dx = -dz;
                dz = temp;
            }

            x += dx;
            z += dz;
        }
    }

    private void generateSnakeGridPath(ChunkPos origin) {
        int r = radius.get();
        int step = chunkStep.get();

        for (int z = -r/2; z <= r/2; z += step) {
            if (z % (step * 2) == 0) {
                // Left to right
                for (int x = -r/2; x <= r/2; x += step) {
                    double worldX = (origin.x() + x) * 16 + 8;
                    double worldZ = (origin.z() + z) * 16 + 8;
                    path.add(new Vec3(worldX, 0, worldZ));
                }
            } else {
                // Right to left (snake)
                for (int x = r/2; x >= -r/2; x -= step) {
                    double worldX = (origin.x() + x) * 16 + 8;
                    double worldZ = (origin.z() + z) * 16 + 8;
                    path.add(new Vec3(worldX, 0, worldZ));
                }
            }
        }
    }

    private File getDataFile() {
        String worldName = mc.level != null
            ? mc.level.dimension().identifier().toString().replace(':', '_')
            : "unknown";
        File dir = new File(MeteorClient.FOLDER, "explorer");
        dir.mkdirs();
        return new File(dir, worldName + ".nbt");
    }

    private void loadExploredChunks() {
        exploredChunks.clear();
        File file = getDataFile();
        if (!file.exists()) return;

        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                int x = in.readInt();
                int z = in.readInt();
                exploredChunks.add(new ChunkPos(x, z));
            }
        } catch (IOException e) {
            // File corrupted, start fresh
            exploredChunks.clear();
        }
    }

    private void saveExploredChunks() {
        File file = getDataFile();
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file))) {
            out.writeInt(exploredChunks.size());
            for (ChunkPos cp : exploredChunks) {
                out.writeInt(cp.x());
                out.writeInt(cp.z());
            }
        } catch (IOException e) {
            error("Failed to save explored chunks.");
        }
    }

    @Override
    public String getInfoString() {
        if (finished) return "Done";
        return exploredChunks.size() + " chunks";
    }

    public enum ScanMode {
        Spiral,
        SnakeGrid
    }
}
