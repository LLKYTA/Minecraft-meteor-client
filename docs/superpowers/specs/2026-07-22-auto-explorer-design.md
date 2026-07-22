# Auto Explorer — 自动探索飞行系统

**日期:** 2026-07-22
**作者:** Claude x User
**状态:** 审批通过

## 概述

为 Meteor Client 增加一套自动探索飞行系统，包含鞘翅飞行优化、自动扫描路线规划、地图加载显示、3D 路线追踪线。

## 架构

拆分为 4 个独立组件，依托 Meteor 现有系统架构：

```
AutoExplorer (misc) ──→ ElytraPlus (movement)
      │                        │
      ▼                        ▼
MapRenderer ──────→ RouteRenderer (render)
```

### 组件总览

| 组件 | 类型 | 位置 | 职责 |
|------|------|------|------|
| ElytraPlus | Module | modules/movement/ | 自动鞘翅飞行 + 不掉耐久 |
| AutoExplorer | Module | modules/misc/ | 生成探索路径，联动 ElytraPlus |
| MapRenderer | 渲染 | render/ | 加载 PNG 地图，GUI 全屏查看 |
| RouteRenderer | 渲染 | render/ | 3D 世界路线追踪线 |

---

## 1. ElytraPlus 模块

**路径:** `src/main/java/meteordevelopment/meteorclient/systems/modules/movement/ElytraPlus.java`

### 设置

| 设置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| mode | 枚举 | AutoCruise | Manual / AutoCruise / FollowRoute |
| min_height | Double | 120 | 最低飞行高度 |
| max_height | Double | 256 | 最高飞行高度 |
| cruise_height | Double | 200 | 巡航目标高度 |
| target_speed | Double | 2.0 | 目标速度 (blocks/tick) |
| speed_mode | 枚举 | Constant | Constant / Boost / PingPong |
| no_durability | Boolean | true | 不掉耐久（每 tick 修复） |
| auto_takeoff | Boolean | true | 自动点火起飞 |
| auto_land | Boolean | true | 到达后自动降落 |
| follow_target | Vec3 | — | 当前跟随的路径点坐标 |

### 生命周期

```
onActivate()
  ├── 检查是否穿鞘翅
  ├── 自动点火 (if auto_takeoff)
  └── 注册到 EVENT_BUS

onTick()
  ├── 高度控制 → 调整俯仰
  ├── 速度控制 → 调整姿态
  ├── 转向辅助 → 对准目标方向
  ├── 不掉耐久修复 (if no_durability)
  └── 降落检测 (if auto_land 且接近目标)

onDeactivate()
  └── 从 EVENT_BUS 注销
```

### 不掉耐久机制

每 tick 检测胸甲槽位的鞘翅耐久，受到消耗后立即用 `item.damage = item.maxDamage` 重置。

---

## 2. AutoExplorer 模块

**路径:** `src/main/java/meteordevelopment/meteorclient/systems/modules/misc/AutoExplorer.java`

### 设置

| 设置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| scan_mode | 枚举 | Spiral | Spiral / SnakeGrid / CustomArea |
| radius | Integer | 500 | 扫描半径 (区块) |
| chunk_step | Integer | 3 | 每隔多少区块打一个路径点 |
| save_explored | Boolean | true | 记录已探索区块到 NBT |
| auto_activate_elytra | Boolean | true | 自动开启 ElytraPlus |

### 扫描模式

1. **Spiral（螺旋扫描）** — 从当前位置向外螺旋展开
2. **SnakeGrid（蛇形扫描）** — 在矩形区域内蛇形来回
3. **CustomArea（自定义区域）** — 从指定矩形区域扫描

### 路径生成

```
generatePath(mode, origin, radius)
  ├── 根据模式计算路径点列表
  ├── 过滤掉已探索的区块
  └── 返回 List<Vec3>

onTick()
  ├── if ElytraPlus 空闲 → 发送下一个路径点
  ├── └─ ElytraPlus.setFollowTarget(nextPoint)
  ├── if 所有点完成 → auto_land
  └── 标记当前区块为已探索
```

### 数据文件

```
.minecraft/meteor-client/explorer/<world_name>.nbt
  └── ExploredChunks: Set<ChunkPos>  // 已探索区块集合
```

---

## 3. MapRenderer

**路径:** `src/main/java/meteordevelopment/meteorclient/renderer/MapRenderer.java`

### 地图文件夹

```
.minecraft/meteor-client/maps/
  ├── world_name.png          // 自动匹配当前世界
  ├── custom_overlay.png
  └── ...
```

### 功能

| 功能 | 说明 |
|------|------|
| 加载 PNG | 支持常规 PNG 地图图片 |
| 坐标校准 | 设置图片像素 ↔ Minecraft 坐标映射 |
| 全屏 GUI | 按绑定键打开全屏地图窗口 |
| 玩家标记 | 实时显示玩家位置、朝向 |
| 缩放/拖拽 | 鼠标滚轮缩放，拖拽平移 |
| 已探索覆盖 | 在图片上叠加显示已探索/未探索区域 |

### GUI 控件

```
MapScreen
├── 地图图片作为背景
├── 玩家位置标记 (箭头)
├── 探索覆盖层 (半透明绿 = 已探, 红 = 未探)
├── 工具栏: 缩放 / 重置视角 / 校准
├── 鼠标: 左键拖拽平移, 滚轮缩放, 右键设置路径点
└── 路线显示: 叠加 AutoExplorer 生成的路线
```

---

## 4. RouteRenderer

**路径:** `src/main/java/meteordevelopment/meteorclient/renderer/RouteRenderer.java`

### 功能

| 功能 | 说明 |
|------|------|
| 3D 路线线 | 在世界空间中用线条绘制规划路线 |
| 终点点标记 | 路线终点处显示光柱或方块轮廓 |
| 下一个拐点指示 | HUD 指示标指向下一个拐弯位置 |
| 可配置 | 颜色 / 线宽 / 显示距离限制 |

### 渲染方式

利用 Meteor 现有的 `renderer/` 中的形状渲染（R#renderLines），在每个渲染帧中绘制。

```
onRender3D()
  ├── 从 AutoExplorer 获取当前路径
  ├── 剔除超出显示距离的点
  ├── 绘制折线 (glLineStrip)
  ├── 绘制终点发光标记
  └── 在 HUD 上绘制下一个转向指示
```

---

## 测试计划

- ElytraPlus: 手动切换各模式，验证不掉耐久、自动起飞/降落
- AutoExplorer: 验证 Spiral/SnakeGrid 路径生成是否正确
- MapRenderer: 加载测试 PNG，验证坐标映射
- 集成测试: AutoExplorer → ElytraPlus 联动流程

---

## 实现顺序

1. ElytraPlus（基础飞行控制）
2. RouteRenderer（3D 路线显示）
3. AutoExplorer（路径生成 + 联动）
4. MapRenderer（地图加载 + GUI）
