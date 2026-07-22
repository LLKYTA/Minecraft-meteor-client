# Auto Explorer 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为 Meteor Client 实现自动探索飞行系统，包含鞘翅飞行增强、自动路径规划、PNG 地图加载显示、3D 路线追踪线。

**架构：** 拆分为 4 个独立组件——ElytraPlus（飞行控制）、AutoExplorer（路径生成）、MapRenderer（地图 GUI）、RouteRenderer（3D 路线渲染），通过 Meteor 的事件总线通信。

**技术栈：** Fabric Loom 1.16, Java 21+, Minecraft 26.1, Orbit 事件总线, NBT 序列化, OpenGL 渲染

---

## 文件清单

### 创建的文件

| 文件 | 职责 |
|------|------|
| `src/main/java/meteordevelopment/meteorclient/systems/modules/movement/ElytraPlus.java` | 自动鞘翅飞行模块（巡航、高度控制、不掉耐久） |
| `src/main/java/meteordevelopment/meteorclient/systems/modules/misc/AutoExplorer.java` | 探索路径生成 + 联动 ElytraPlus |
| `src/main/java/meteordevelopment/meteorclient/renderer/MapRenderer.java` | PNG 地图加载 + 全屏 GUI 显示 |
| `src/main/java/meteordevelopment/meteorclient/renderer/RouteRenderer.java` | 3D 世界路线追踪线绘制 |
| `src/main/java/meteordevelopment/meteorclient/gui/screens/MapScreen.java` | 全屏地图查看 GUI 屏幕 |
| docs/superpowers/plans/2026-07-22-auto-explorer-plan.md | 本文件 |

### 修改的文件

| 文件 | 修改 |
|------|------|
| 无 | 所有组件均为独立新文件，遵循现有模块模式 |

---

## 实现顺序

1. **ElytraPlus** — 先有飞行控制能力
2. **RouteRenderer** — 再看到飞行的路线
3. **AutoExplorer** — 然后自动生成路线
4. **MapRenderer + MapScreen** — 最后锦上添花的地图 UI

---

## 任务 1：ElytraPlus 模块

**路径：** `src/main/java/meteordevelopment/meteorclient/systems/modules/movement/ElytraPlus.java`

`ElytraPlus` 是自动鞘翅飞行的核心模块，提供高度保持、速度控制、自动转向跟随目标、不掉耐久等功能。

- [ ] **步骤 1.1：编写 ElytraPlus.java 文件框架**

```java
/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.movement;

import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

public class ElytraPlus extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSpeed = settings.createGroup("Speed");
    private final SettingGroup sgAutopilot = settings.createGroup("Autopilot");

    // General
    public final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Flight control mode.")
        .defaultValue(Mode.Cruise)
        .build()
    );

    public final Setting<Boolean> noDurability = sgGeneral.add(new BoolSetting.Builder()
        .name("no-durability")
        .description("Prevent elytra from losing durability.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> autoTakeOff = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-take-off")
        .description("Automatically start flying when holding jump.")
        .defaultValue(true)
        .build()
    );

    // Speed
    public final Setting<Double> targetSpeed = sgSpeed.add(new DoubleSetting.Builder()
        .name("target-speed")
        .description("Target horizontal speed.")
        .defaultValue(30)
        .min(0)
        .sliderMax(100)
        .build()
    );

    public final Setting<Double> minHeight = sgSpeed.add(new DoubleSetting.Builder()
        .name("min-height")
        .description("Minimum flight height.")
        .defaultValue(120)
        .min(0)
        .sliderMax(320)
        .build()
    );

    public final Setting<Double> maxHeight = sgSpeed.add(new DoubleSetting.Builder()
        .name("max-height")
        .description("Maximum flight height.")
        .defaultValue(260)
        .min(0)
        .sliderMax(320)
        .build()
    );

    public final Setting<Double> cruiseHeight = sgSpeed.add(new DoubleSetting.Builder()
        .name("cruise-height")
        .description("Target cruise height.")
        .defaultValue(200)
        .min(0)
        .sliderMax(320)
        .build()
    );

    // Autopilot
    public final Setting<Vec3> targetPosition = sgAutopilot.add(new Vec3Setting.Builder()
        .name("target-position")
        .description("Target position to fly towards.")
        .defaultValue(Vec3.ZERO)
        .visible(() -> mode.get() == Mode.FollowRoute)
        .build()
    );

    public final Setting<Boolean> autoLand = sgAutopilot.add(new BoolSetting.Builder()
        .name("auto-land")
        .description("Automatically descend when near target.")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.FollowRoute)
        .build()
    );

    public final Setting<Double> landDistance = sgAutopilot.add(new DoubleSetting.Builder()
        .name("land-distance")
        .description("Distance to target at which to start landing.")
        .defaultValue(10)
        .min(1)
        .sliderMax(50)
        .visible(() -> autoLand.get() && mode.get() == Mode.FollowRoute)
        .build()
    );

    // Internal state
    private Vec3 currentTarget = Vec3.ZERO;
    private boolean hasTarget = false;

    public ElytraPlus() {
        super(Categories.Movement, "elytra-plus", "Enhanced elytra flight with autopilot and route following.");
    }

    @Override
    public void onActivate() {
        if (noDurability.get() && mc.player != null) {
            var stack = mc.player.getItemBySlot(EquipmentSlot.CHEST);
            if (stack.has(DataComponents.GLIDER) && stack.getItem() == Items.ELYTRA && stack.getDamageValue() > 0) {
                stack.setDamageValue(0);
            }
        }
    }

    @Override
    public void onDeactivate() {
        hasTarget = false;
    }

    /** Set the target position for route following mode */
    public void setTarget(Vec3 target) {
        this.currentTarget = target;
        this.hasTarget = true;
    }

    /** Clear current target */
    public void clearTarget() {
        this.hasTarget = false;
    }

    /** Check if module has an active target */
    public boolean hasTarget() {
        return hasTarget;
    }

    public Vec3 getTarget() {
        return currentTarget;
    }

    public enum Mode {
        Cruise,
        FollowRoute
    }
}
```

- [ ] **步骤 1.2：实现 onPlayerMove 事件处理 — 核心飞行控制**

替换上一步中的空类，添加以下方法到 ElytraPlus：

```java
    @EventHandler
    private void onPlayerMove(PlayerMoveEvent event) {
        if (!(mc.player.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.GLIDER))) return;

        // Auto takeoff
        if (autoTakeOff.get() && !mc.player.isFallFlying() && mc.options.keyJump.isDown()) {
            mc.player.startFallFlying();
            return;
        }

        if (!mc.player.isFallFlying()) return;

        // No durability - repair every tick
        if (noDurability.get()) {
            var stack = mc.player.getItemBySlot(EquipmentSlot.CHEST);
            if (stack.getItem() == Items.ELYTRA && stack.getDamageValue() > 0) {
                stack.setDamageValue(0);
            }
        }

        // Height control
        double y = mc.player.getY();
        if (y < minHeight.get()) {
            // Pitch up to gain height
            mc.player.setXRot(mc.player.getXRot() - 2.0f);
        } else if (y > maxHeight.get()) {
            // Pitch down to lose height
            mc.player.setXRot(mc.player.getXRot() + 2.0f);
        } else if (mode.get() == Mode.Cruise) {
            // Cruise - maintain height
            double heightDiff = cruiseHeight.get() - y;
            mc.player.setXRot(mc.player.getXRot() - (float) (heightDiff * 0.1));
        }

        // Speed control - pitch based
        float pitch = mc.player.getXRot();
        if (pitch < 0) {
            // Going up - slower
            event.movement = event.movement.scale(0.9);
        } else if (pitch > 10) {
            // Going down - faster
            event.movement = event.movement.scale(1.1);
        }

        // Route following
        if (mode.get() == Mode.FollowRoute && hasTarget) {
            followTarget();
            mc.options.keyUp.setDown(true);
        }
    }

    private void followTarget() {
        Vec3 playerPos = mc.player.position();
        double dx = currentTarget.x - playerPos.x;
        double dz = currentTarget.z - playerPos.z;
        double distance = Math.sqrt(dx * dx + dz * dz);

        if (distance < landDistance.get() && autoLand.get()) {
            // Near target - start landing
            mc.player.setXRot(mc.player.getXRot() + 3.0f);
            if (mc.player.onGround()) {
                clearTarget();
                toggle();
            }
            return;
        }

        // Yaw towards target
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        mc.player.setYRot(targetYaw);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive() || mc.player == null) return;
        // Keep auto-pilot forward key pressed in FollowRoute mode
        if (mode.get() == Mode.FollowRoute && hasTarget) {
            mc.options.keyUp.setDown(true);
        }
    }
```

- [ ] **步骤 1.3：编译验证**

运行构建验证 ElytraPlus 编译通过：

```bash
cd d:/Minecraft-meteor-client
GRADLE_USER_HOME=$(pwd)/.gradle-home ./gradlew build 2>&1 | tail -20
```

预期：BUILD SUCCESSFUL

---

## 任务 2：RouteRenderer — 3D 路线追踪线

**路径：** `src/main/java/meteordevelopment/meteorclient/renderer/RouteRenderer.java`

`RouteRenderer` 监听 `Render3DEvent`，在 3D 世界中绘制飞行路线线和终点标记。它不属于 Module 体系，而是一个独立的渲染组件，由 AutoExplorer 模块激活时注册到 EventBus。

- [ ] **步骤 2.1：创建 RouteRenderer.java**

```java
/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.renderer;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public class RouteRenderer {
    private final List<Vec3> waypoints = new ArrayList<>();
    private boolean active = false;
    private int lineColor = 0xFFFFFFFF;
    private float lineWidth = 2.0f;

    public void setWaypoints(List<Vec3> points) {
        waypoints.clear();
        waypoints.addAll(points);
    }

    public void addWaypoint(Vec3 point) {
        waypoints.add(point);
    }

    public void clearWaypoints() {
        waypoints.clear();
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isActive() {
        return active;
    }

    public void setLineColor(int color) {
        this.lineColor = color;
    }

    public List<Vec3> getWaypoints() {
        return waypoints;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!active || waypoints.size() < 2) return;

        // Draw line connecting waypoints in order
        // Skip waypoints too far from player (beyond 200 blocks)
        Vec3 cameraPos = new Vec3(event.offsetX, event.offsetY, event.offsetZ);

        for (int i = 0; i < waypoints.size() - 1; i++) {
            Vec3 p1 = waypoints.get(i);
            Vec3 p2 = waypoints.get(i + 1);

            // Skip if either point is too far
            if (p1.distanceToSqr(cameraPos) > 40000 || p2.distanceToSqr(cameraPos) > 40000) continue;

            // Draw line segment
            // Using the renderer's line drawing capability
            event.renderer.line(p1.x, p1.y, p1.z, p2.x, p2.y, p2.z, lineColor);
        }

        // Draw end marker (if the last waypoint is within range)
        if (!waypoints.isEmpty()) {
            Vec3 last = waypoints.get(waypoints.size() - 1);
            if (last.distanceToSqr(cameraPos) <= 40000) {
                // Draw a small box/circle at the end point
                float size = 0.5f;
                event.renderer.box(last.x - size, last.y - size, last.z - size,
                    last.x + size, last.y + size, last.z + size, lineColor, Renderer3D.Mode.Lines);
            }
        }
    }
}
```

- [ ] **步骤 2.2：编译验证**

```bash
cd d:/Minecraft-meteor-client
GRADLE_USER_HOME=$(pwd)/.gradle-home ./gradlew build 2>&1 | tail -20
```

预期：BUILD SUCCESSFUL

---

## 任务 3：AutoExplorer 模块

**路径：** `src/main/java/meteordevelopment/meteorclient/systems/modules/misc/AutoExplorer.java`

`AutoExplorer` 是自动探索的核心编排模块：
- 根据扫描模式生成路径点
- 联动 ElytraPlus 逐个飞行
- 记录已探索区块到 NBT 文件
- 激活 RouteRenderer 显示路线

- [ ] **步骤 3.1：创建 AutoExplorer.java 基础结构和设置**

```java
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

    public enum ScanMode {
        Spiral,
        SnakeGrid
    }
}
```

- [ ] **步骤 3.2：实现路径生成算法**

在 AutoExplorer 类中添加以下方法：

```java
    private void generatePath() {
        path.clear();
        ChunkPos origin = new ChunkPos(mc.player.chunkPosition());

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
                    double worldX = (origin.x + x) * 16 + 8;
                    double worldZ = (origin.z + z) * 16 + 8;
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
                    double worldX = (origin.x + x) * 16 + 8;
                    double worldZ = (origin.z + z) * 16 + 8;
                    path.add(new Vec3(worldX, 0, worldZ));
                }
            } else {
                // Right to left (snake)
                for (int x = r/2; x >= -r/2; x -= step) {
                    double worldX = (origin.x + x) * 16 + 8;
                    double worldZ = (origin.z + z) * 16 + 8;
                    path.add(new Vec3(worldX, 0, worldZ));
                }
            }
        }
    }
```

- [ ] **步骤 3.3：实现 Tick 事件处理 — 路线联动**

```java
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
```

- [ ] **步骤 3.4：实现 NBT 持久化 — 已探索区块保存/加载**

```java
    private File getDataFile() {
        String worldName = mc.getCurrentWorld() != null && mc.getCurrentWorld().getLevel() != null
            ? mc.getCurrentWorld().getLevel().toString()
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
                out.writeInt(cp.x);
                out.writeInt(cp.z);
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
```

- [ ] **步骤 3.5：编译验证**

```bash
cd d:/Minecraft-meteor-client
GRADLE_USER_HOME=$(pwd)/.gradle-home ./gradlew build 2>&1 | tail -20
```

预期：BUILD SUCCESSFUL

---

## 任务 4：MapScreen — 全屏地图 GUI

**路径：** `src/main/java/meteordevelopment/meteorclient/gui/screens/MapScreen.java`

`MapScreen` 是一个全屏 GUI 界面，用于查看 PNG 地图文件，显示玩家位置和探索覆盖层。

- [ ] **步骤 4.1：创建 MapScreen.java**

```java
/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.gui.screens;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11.*;

public class MapScreen extends WidgetScreen {
    private static final int MAP_SIZE = 512;

    private final File mapFile;
    private BufferedImage mapImage;
    private int glTextureId = -1;
    private double offsetX = 0, offsetY = 0;
    private double zoom = 1.0;
    private boolean dragging = false;
    private double lastMouseX, lastMouseY;

    public MapScreen(GuiTheme theme, File mapFile) {
        super(theme, "Map Viewer");
        this.mapFile = mapFile;
        loadMap();
    }

    private void loadMap() {
        try {
            mapImage = ImageIO.read(mapFile);
            if (mapImage != null) {
                uploadTexture();
            }
        } catch (IOException e) {
            // Could not load map image
            mapImage = null;
        }
    }

    private void uploadTexture() {
        if (mapImage == null) return;

        glTextureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, glTextureId);

        // Set texture parameters
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP);

        // Convert BufferedImage to ByteBuffer (RGBA format)
        int w = mapImage.getWidth();
        int h = mapImage.getHeight();
        int[] pixels = new int[w * h];
        mapImage.getRGB(0, 0, w, h, pixels, 0, w);

        ByteBuffer buffer = ByteBuffer.allocateDirect(w * h * 4);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pixel = pixels[y * w + x];
                buffer.put((byte) ((pixel >> 16) & 0xFF)); // R
                buffer.put((byte) ((pixel >> 8) & 0xFF));  // G
                buffer.put((byte) (pixel & 0xFF));          // B
                buffer.put((byte) ((pixel >> 24) & 0xFF)); // A
            }
        }
        buffer.flip();

        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
    }

    @Override
    protected void onClosed() {
        if (glTextureId != -1) {
            glDeleteTextures(glTextureId);
            glTextureId = -1;
        }
    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float tickDelta) {
        super.renderWidget(guiGraphics, mouseX, mouseY, tickDelta);

        if (glTextureId == -1) {
            // Draw placeholder text if no map loaded
            guiGraphics.drawString(mc.font, "No map loaded. Place .png files in .minecraft/meteor-client/maps/",
                width / 2 - 150, height / 2, 0xFFAAAAAA, false);
            return;
        }

        // Draw map texture centered, with pan and zoom
        int screenCenterX = width / 2;
        int screenCenterY = height / 2;

        double mapW = mapImage.getWidth() * zoom;
        double mapH = mapImage.getHeight() * zoom;

        double mapLeft = screenCenterX - mapW / 2 + offsetX;
        double mapTop = screenCenterY - mapH / 2 + offsetY;

        // Draw the map
        renderTexture(guiGraphics, glTextureId, mapLeft, mapTop, mapW, mapH);

        // Draw player position marker
        if (mc.player != null) {
            // Simple dot at center of screen (player is center of map for now)
            guiGraphics.fill((int) (screenCenterX + offsetX - 3), (int) (screenCenterY + offsetY - 3),
                (int) (screenCenterX + offsetX + 3), (int) (screenCenterY + offsetY + 3),
                0xFFFF0000);
        }

        // Draw calibration info
        guiGraphics.drawString(mc.font, "Zoom: " + String.format("%.1f", zoom), 10, 10, 0xFFFFFFFF, false);
        guiGraphics.drawString(mc.font, "Scroll to zoom, drag to pan", 10, 25, 0xFFAAAAAA, false);
    }

    private void renderTexture(GuiGraphics guiGraphics, double x, double y, double w, double h) {
        // A simple textured quad rendering
        // In practice we'd use the Renderer2D texture system, but for simplicity
        // we use immediate mode OpenGL
        glEnable(GL_TEXTURE_2D);
        glBindTexture(GL_TEXTURE_2D, glTextureId);
        glBegin(GL_QUADS);
        glTexCoord2f(0, 0); glVertex2d(x, y);
        glTexCoord2f(1, 0); glVertex2d(x + w, y);
        glTexCoord2f(1, 1); glVertex2d(x + w, y + h);
        glTexCoord2f(0, 1); glVertex2d(x, y + h);
        glEnd();
        glDisable(GL_TEXTURE_2D);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            dragging = true;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            dragging = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (dragging) {
            offsetX += mouseX - lastMouseX;
            offsetY += mouseY - lastMouseY;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        zoom *= (scrollY > 0) ? 1.1 : 0.9;
        zoom = Math.max(0.1, Math.min(zoom, 10.0));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
```

- [ ] **步骤 4.2：编译验证**

```bash
cd d:/Minecraft-meteor-client
GRADLE_USER_HOME=$(pwd)/.gradle-home ./gradlew build 2>&1 | tail -20
```

预期：BUILD SUCCESSFUL

---

## 任务 5：MapRenderer — 地图加载管理器

**路径：** `src/main/java/meteordevelopment/meteorclient/renderer/MapRenderer.java`

`MapRenderer` 管理 `meteor-client/maps/` 文件夹，提供打开 MapScreen 的能力。

- [ ] **步骤 5.1：创建 MapRenderer.java**

```java
/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.renderer;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.screens.MapScreen;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MapRenderer {
    private static final File MAPS_DIR = new File(MeteorClient.FOLDER, "maps");

    static {
        MAPS_DIR.mkdirs();
    }

    /** Get list of available map PNG files */
    public static List<File> getAvailableMaps() {
        List<File> maps = new ArrayList<>();
        File[] files = MAPS_DIR.listFiles((dir, name) -> name.toLowerCase().endsWith(".png"));
        if (files != null) {
            for (File f : files) {
                maps.add(f);
            }
        }
        return maps;
    }

    /** Open the map viewer screen for a given map file */
    public static void openMapViewer(File mapFile) {
        if (mapFile.exists() && mapFile.getName().toLowerCase().endsWith(".png")) {
            Minecraft.getInstance().setScreen(new MapScreen(GuiThemes.get(), mapFile));
        }
    }

    /** Get the maps directory */
    public static File getMapsDir() {
        return MAPS_DIR;
    }
}
```

- [ ] **步骤 5.2：编译验证**

```bash
cd d:/Minecraft-meteor-client
GRADLE_USER_HOME=$(pwd)/.gradle-home ./gradlew build 2>&1 | tail -20
```

预期：BUILD SUCCESSFUL

---

## 任务 6：集成验证

- [ ] **步骤 6.1：完整构建**

```bash
cd d:/Minecraft-meteor-client
GRADLE_USER_HOME=$(pwd)/.gradle-home ./gradlew build
```

预期：BUILD SUCCESSFUL，无错误

- [ ] **步骤 6.2：验证产物**

```bash
ls -la build/libs/meteor-client-26.1.2-local.jar
```

预期：文件存在，大小正常

---

## 后续扩展方向（不在本次计划内）

- [ ] **自动匹配地图** — 根据当前世界名称自动加载对应的 PNG
- [ ] **坐标校准 GUI** — 在 MapScreen 中点击设置地图 ↔ Minecraft 坐标映射
- [ ] **探索覆盖层** — 在地图上以颜色区块显示已探索/未探索区域
- [ ] **GUI 设置面板** — 在 MapScreen 中直接调整扫描参数
- [ ] **地图标记** — 在地图上标记路径点、死亡点等
