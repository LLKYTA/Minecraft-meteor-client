/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.misc;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.RouteRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

public class AutoExplorer extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFlight = settings.createGroup("Flight");

    // Scan
    public final Setting<ScanMode> scanMode = sgGeneral.add(new EnumSetting.Builder<ScanMode>()
        .name("scan-mode")
        .description("The scanning pattern to use.")
        .defaultValue(ScanMode.Spiral)
        .onChanged(s -> { if (isActive()) generatePath(); })
        .build()
    );

    public final Setting<Integer> radius = sgGeneral.add(new IntSetting.Builder()
        .name("radius")
        .description("Scan radius in chunks.")
        .defaultValue(500)
        .min(10)
        .sliderMax(2000)
        .onChanged(i -> { if (isActive()) generatePath(); })
        .build()
    );

    public final Setting<Integer> chunkStep = sgGeneral.add(new IntSetting.Builder()
        .name("chunk-step")
        .description("Distance between waypoints in chunks.")
        .defaultValue(3)
        .min(1)
        .sliderMax(16)
        .onChanged(i -> { if (isActive()) generatePath(); })
        .build()
    );

    public final Setting<Boolean> saveExplored = sgGeneral.add(new BoolSetting.Builder()
        .name("save-explored")
        .description("Save explored chunks to file and skip them.")
        .defaultValue(true)
        .build()
    );

    // Flight
    public final Setting<Double> cruiseHeight = sgFlight.add(new DoubleSetting.Builder()
        .name("cruise-height")
        .description("Target flight height.")
        .defaultValue(200.0)
        .min(10)
        .sliderMax(320)
        .build()
    );

    public final Setting<Double> minHeight = sgFlight.add(new DoubleSetting.Builder()
        .name("min-height")
        .description("Minimum height before auto-pulling up.")
        .defaultValue(100.0)
        .min(0)
        .sliderMax(320)
        .build()
    );

    public final Setting<Boolean> noDurability = sgFlight.add(new BoolSetting.Builder()
        .name("no-durability")
        .description("Prevent elytra from losing durability.")
        .defaultValue(true)
        .build()
    );

    // Internal state
    private final List<Vec3> path = new ArrayList<>();
    private int currentPointIndex = 0;
    private final Set<ChunkPos> exploredChunks = new HashSet<>();
    private boolean finished = false;
    private boolean flying = false;
    private int takeoffTimer = 0;
    private int heightAdjTimer = 0;
    private RouteRenderer routeRenderer;

    public AutoExplorer() {
        super(Categories.Misc, "auto-explorer", "Automatically explores the world using elytra flight. One click to start.");
    }

    @Override
    public void onActivate() {
        // Check elytra
        if (!mc.player.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.GLIDER)) {
            error("You need to equip an elytra first!");
            toggle();
            return;
        }

        // Load explored chunks
        if (saveExplored.get()) loadExploredChunks();

        // Setup route renderer
        routeRenderer = new RouteRenderer();
        MeteorClient.EVENT_BUS.subscribe(routeRenderer);

        // Generate path
        generatePath();

        currentPointIndex = 0;
        finished = false;
        flying = false;
        takeoffTimer = 0;
        heightAdjTimer = 0;

        info("Auto-explorer activated. Jump to take off!");
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

        // Release keys
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);

        // Stop elytra flight by sending the toggle packet
        if (mc.player != null && mc.player.isFallFlying() && mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        }

        currentPointIndex = 0;
        path.clear();
        finished = false;
        flying = false;
    }

    @EventHandler
    private void onPlayerMove(PlayerMoveEvent event) {
        if (!flying || finished) return;

        // Auto takeoff
        if (!mc.player.isFallFlying()) {
            if (mc.options.keyJump.isDown() || takeoffTimer > 0) {
                takeoffTimer++;
                mc.player.startFallFlying();
                if (takeoffTimer > 5) {
                    takeoffTimer = 0;
                }
            }
            return;
        }

        // No durability repair
        if (noDurability.get()) {
            var stack = mc.player.getItemBySlot(EquipmentSlot.CHEST);
            if (stack.getItem() == Items.ELYTRA && stack.getDamageValue() > 0) {
                stack.setDamageValue(0);
            }
        }

        // Height control - steady pitch approach, no oscillation
        double y = mc.player.getY();

        if (y < minHeight.get()) {
            // Emergency: below minimum height - force upward in movement
            mc.player.setXRot(-22f);
            event.movement = new Vec3(event.movement.x, Math.max(event.movement.y, 0.5), event.movement.z);
        } else if (y > cruiseHeight.get() + 30) {
            // Way too high: slight descent
            mc.player.setXRot(-5f);
        } else if (y > cruiseHeight.get() + 10) {
            // Slightly high: level glide
            mc.player.setXRot(-10f);
        } else if (y < cruiseHeight.get() - 10) {
            // Slightly low: gentle climb
            mc.player.setXRot(-15f);
        } else {
            // Optimal zone: neutral glide
            mc.player.setXRot(-12f);
        }

        // Follow target
        if (currentPointIndex < path.size()) {
            followNextPoint();
        }

        // Hold forward
        mc.options.keyUp.setDown(true);
    }

    private void followNextPoint() {
        Vec3 target = path.get(currentPointIndex);
        Vec3 playerPos = mc.player.position();
        double dx = target.x - playerPos.x;
        double dz = target.z - playerPos.z;
        double distance = Math.sqrt(dx * dx + dz * dz);

        if (distance < 8) {
            // Reached waypoint - mark explored and move to next
            if (saveExplored.get()) {
                ChunkPos cp = new ChunkPos((int) target.x >> 4, (int) target.z >> 4);
                exploredChunks.add(cp);
            }
            currentPointIndex++;

            // Check if done
            if (currentPointIndex >= path.size()) {
                finished = true;
                info("Exploration complete! Explored " + exploredChunks.size() + " chunks.");
                toggle();
                return;
            }

            // Update next target
            target = path.get(currentPointIndex);
            dx = target.x - playerPos.x;
            dz = target.z - playerPos.z;
        }

        // Turn towards target
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        mc.player.setYRot(targetYaw);

        // Update route renderer with waypoints at player's current height
        if (routeRenderer != null && currentPointIndex < path.size()) {
            double currentY = mc.player.getY();
            List<Vec3> remainingPath = new ArrayList<>(path.subList(currentPointIndex, path.size()));
            for (int i = 0; i < remainingPath.size(); i++) {
                Vec3 p = remainingPath.get(i);
                remainingPath.set(i, new Vec3(p.x, currentY, p.z));
            }
            routeRenderer.setWaypoints(remainingPath);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (finished || mc.player == null) return;

        // Start flying when player jumps
        if (!flying && mc.options.keyJump.isDown()) {
            flying = true;
            mc.options.keyJump.setDown(true);
            info("Taking off...");
        }

        if (flying && mc.player.isFallFlying()) {
            mc.options.keyUp.setDown(true);
        }
    }

    private void generatePath() {
        path.clear();
        if (mc.player == null) return;

        double flyHeight = Math.max(minHeight.get(), mc.player.getY());
        ChunkPos origin = mc.player.chunkPosition();

        switch (scanMode.get()) {
            case Spiral -> generateSpiralPath(origin, flyHeight);
            case SnakeGrid -> generateSnakeGridPath(origin, flyHeight);
        }

        // Filter explored
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
            routeRenderer.setLineColor(0xFF00FF00);
        }

        if (path.isEmpty()) {
            info("All chunks in range already explored!");
            toggle();
        } else {
            info("Generated " + path.size() + " waypoints. Jump to start flying!");
        }
    }

    private void generateSpiralPath(ChunkPos origin, double height) {
        int r = radius.get();
        int step = chunkStep.get();
        int x = 0, z = 0;
        int dx = 0, dz = -1;
        for (int i = 0; i < r * r; i++) {
            if (-r/2 <= x && x <= r/2 && -r/2 <= z && z <= r/2) {
                if (i % step == 0) {
                    path.add(new Vec3((origin.x() + x) * 16 + 8, height, (origin.z() + z) * 16 + 8));
                }
            }
            if (x == z || (x < 0 && x == -z) || (x > 0 && x == 1 - z)) {
                int temp = dx; dx = -dz; dz = temp;
            }
            x += dx; z += dz;
        }
    }

    private void generateSnakeGridPath(ChunkPos origin, double height) {
        int r = radius.get();
        int step = chunkStep.get();
        int row = 0;
        for (int z = -r/2; z <= r/2; z += step) {
            if (row % 2 == 0) {
                for (int x = -r/2; x <= r/2; x += step)
                    path.add(new Vec3((origin.x() + x) * 16 + 8, height, (origin.z() + z) * 16 + 8));
            } else {
                for (int x = r/2; x >= -r/2; x -= step)
                    path.add(new Vec3((origin.x() + x) * 16 + 8, height, (origin.z() + z) * 16 + 8));
            }
            row++;
        }
    }

    private File getDataFile() {
        String worldName = mc.level != null
            ? Utils.getFileWorldName() + "_" + mc.level.dimension().identifier().toString().replace(':', '_')
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
            for (int i = 0; i < count; i++)
                exploredChunks.add(new ChunkPos(in.readInt(), in.readInt()));
        } catch (IOException e) {
            exploredChunks.clear();
        }
    }

    private void saveExploredChunks() {
        File file = getDataFile();
        File tmpFile = new File(file.getParentFile(), file.getName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(tmpFile))) {
            out.writeInt(exploredChunks.size());
            for (ChunkPos cp : exploredChunks) {
                out.writeInt(cp.x());
                out.writeInt(cp.z());
            }
            out.flush();
            Files.move(tmpFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            tmpFile.delete();
        }
    }

    @Override
    public String getInfoString() {
        if (finished) return "Done";
        if (!flying) return "Jump to start";
        return currentPointIndex + "/" + path.size();
    }

    public enum ScanMode {
        Spiral,
        SnakeGrid
    }
}
