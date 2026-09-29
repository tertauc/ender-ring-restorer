# Ender Ring Restorer

[English](README.md) | **[简体中文](README.zh-CN.md)** | [繁體中文](README.zh-TW.md)

在 Minecraft **26.3（Wilderness Bound）** 上复现末地「星环 / 环形虚空」地形 bug（[MC-159283](https://bugs.mojang.com/browse/MC/issues/MC-159283)）的 Fabric mod。

- 目标版本：Minecraft 26.3
- 加载器：Fabric Loader 0.19.5+
- Java：25+
- 产物：`ender-ring-restorer/build/libs/ender-ring-restorer-0.1.0+mc26.3.jar`

---

## 1. 现象

自 1.14.4 / 19w36a 起，末地（The End）在距离原点约 **370,720 格**以外的地方开始出现**同心圆环状的虚空带**：一圈陆地、一圈虚空交替出现，环间距随半径增大而逐渐缩小。26.3 版本该 bug 被**意外修复**（修复方式见第 3 节），且官方并未把它当成「特性」恢复。本 mod 用**原本的代码错误**把它重新带回来。

## 2. 成因

旧版 `EndIslandFunction.getHeightValue` 里，主岛密度是这样算的（`x`、`z` 为区块坐标，即 `blockX / 8`）：

```java
float errorWithNaN = 100 - Mth.sqrt(x * x + z * z) * 8;   // ← 问题所在
errorWithNaN = Mth.clamp(errorWithNaN, -100, 80);
```

`x * x + z * z` 是 **32 位 int 运算**。当 `x² + z² > 2³¹ - 1` 时发生**整数溢出**，回绕成负数：

```
s_actual = (x² + z²) - 2³²   →   负数
Mth.sqrt(负数) = NaN
```

NaN 顺着 `sloped_cheese → lerp → squeeze → final_density` 一路传播，整根密度柱变成 NaN，判定为空气 → **虚空**。

### 为什么是「同心圆环」而不是一整块虚空

溢出以 `2³²` 为周期。记 `s = x² + z²`，则：

- `s mod 2³² ∈ [0, 2³¹)` → 有符号 int 为正 → **陆地**
- `s mod 2³² ∈ [2³¹, 2³²)` → 有符号 int 为负 → **虚空**

由于 `s` 只与到原点的距离有关，每一段区间都对应一个**圆环带**，因此形成同心圆环。第 k 个虚空环的半径范围（换算成方块，乘以 8）：

```
r_start(k) = 8 · √(k·2³² + 2³¹)
r_end(k)   = 8 · √((k+1)·2³²)
```

关键数值（已在 `RingVerify2` 中逐一核对）：

| k | 虚空环起点（格） | 虚空环终点（格） |
|---|-----------------|-----------------|
| 0 | 370,727.6 | 524,288 |
| 1 | 642,119.0 | 741,455.2 |
| 2 | 828,972.1 | 908,093.5 |
| 3 | 980,853.0 | 1,048,576 |

每个环的理论面积恒为 `π · 2³⁷ ≈ 4.32 × 10¹¹ 方块²`，环间距 `ΔR_k ≈ 524,288 / √k`（因此越往外环越密，但收敛极慢，在 30,000,000 的世界边界内看不到收敛点）。

## 3. 26.3 为什么被「意外修复」

26.3 把主岛密度从 Java 代码搬到了**数据驱动的密度函数**。`end/islands.json` 现在是：

```json
{
  "type": "minecraft:max",
  "left": {
    "type": "minecraft:mul",
    "left": {
      "type": "minecraft:slice", "axis": "y", "coordinate": 0,
      "input": {
        "type": "minecraft:sub",
        "left": {
          "type": "minecraft:clamp",
          "input": {
            "type": "minecraft:sub",
            "left": 100.0,
            "right": { "type": "minecraft:distance_to_point", "metric": "euclidean", "point": [0,0,0] }
          },
          "min": -100.0, "max": 80.0
        },
        "right": 8.0
      }
    },
    "right": 0.0078125
  },
  "right": { "type": "minecraft:end_outer_islands" }
}
```

主岛项改由 `distance_to_point` 计算。它在内部用 **float**（`Mth.length`）而非 int，`sqrt` 永远不会溢出，于是环形虚空消失。原来的 `EndIslandFunction` 被改名为 `end_outer_islands`，只负责外围小岛。

> 数学上二者等价：`√((x/8)²+(z/8)²)·8 = √(x²+z²)`，所以修复版与非溢出区间的旧版结果一致——**唯一的区别就是不再溢出**。

## 4. 为什么做 Mod 而不是数据包

| 方案 | 能否复现 | 原因 |
|------|---------|------|
| **数据包** | ✗ | 数据包密度函数只有 float/double 运算，**无法表达 int 溢出**。用 `distance_to_point` + 取模去硬凑环带只是「模拟外观」，不是「原本的代码错误」，违背需求。 |
| **Fabric Mod** | ✓ | 可以直接在 `distance_to_point` 的采样点里重新执行那句 int 表达式，**精确还原** NaN 的产生条件。 |

因此选择 Fabric mod，注入点选在**主岛项**（`distance_to_point`）而非外围小岛。

## 5. 工作原理（Mixin 细节）

`DistanceToPointFunctionSamplerMixin` 注入
`net.minecraft.world.level.levelgen.densityfunction.generator.DistanceToPointFunction$Sampler#sampleValue`：

```java
if (metric != EUCLIDEAN || x != 0 || y != 0 || z != 0) return;  // 仅命中 end/islands 的主岛项
int sectionX = blockX / 8;
int sectionZ = blockZ / 8;
if (sectionX * sectionX + sectionZ * sectionZ < 0) {           // 还原 int 溢出
    cir.setReturnValue(Float.NaN);
}
```

不溢出时原样返回 26.3 的真实距离，因此**非环带区域与 26.3 表现完全一致**。

### 为什么注入主岛项，而不是 `end_outer_islands`？

26.3 的结构是 `max(主岛项, end_outer_islands)`，而 `MaxSampler` 的两条采样路径对 NaN 的处理不同：

- **标量路径** `sampleValue`：用 `Math.max`，NaN 会传播；
- **批量路径** `sampleVolume`（世界生成实际走的路径，配合 `interpolated` 使用）：先把**左侧**写入输出缓冲，再用 `if (right > left)` 决定是否覆盖。`NaN` 参与比较恒为 `false`，所以：

  - 左侧（主岛项）是 NaN → 缓冲保持 NaN → **虚空成立** ✅
  - 右侧（外围小岛）是 NaN → 不覆盖，NaN 被丢弃 ❌

所以必须让**主岛项**（`max` 的左侧）变 NaN。若注入 `end_outer_islands`，NaN 会在批量路径里被吃掉，环带根本不会出现。

NaN 之后会依次穿过 `sub / clamp / slice / mul / max / add / lerp / blend_density / squeeze` 等算子，这些算子全部保持 NaN（已逐类核对 26.3 反编译字节码），最终 `final_density = NaN`，判定为空气。

## 6. 安装

1. 安装 **Fabric Loader 0.19.5+**（Minecraft 26.3）与 **Java 25**。
2. 把 `ender-ring-restorer-0.1.0+mc26.3.jar` 放进 `.minecraft/mods/`。
3. 启动游戏。日志出现 `Ender Ring Restorer loaded ...` 即成功。

## 7. 使用与验证

环带只影响**新生成**的区块，对已生成的区块无效。进入末地后飞到远处观察：

```
# 切旁观/创造，直接传送到环带附近
/execute in minecraft:the_end run tp @s 400000 80 0     # 应落在虚空环带内
/execute in minecraft:the_end run tp @s 524288 80 0     # 陆地恢复
/execute in minecraft:the_end run tp @s 741455 80 0     # 第二个虚空环带
```

对照表：

| 半径区间（格） | 表现 |
|---------------|------|
| 0 – 370,727 | 正常地形 |
| **370,728 – 524,287** | **虚空（环带 0）** |
| 524,288 – 741,454 | 陆地 |
| **741,455 – 908,093** | **虚空（环带 1）** |
| … | 依此类推，环带逐渐变窄 |

## 8. 从源码构建

工程位于 `ender-ring-restorer/`：

```
./gradlew build
```

开箱即用，受限网络下也可直接构建：

- `settings.gradle` 把 Mojang 的资源与库 CDN（`resources.download.minecraft.net`、`libraries.minecraft.net`）重定向到 BMCLAPI 镜像，在官方地址被阻断时仍可访问。
- `build.gradle` 把该镜像限制为仅提供 `com.mojang` 产物，其余第三方 Minecraft 库全部走 Maven Central（并以阿里云镜像作为回退）。这是必要的：该镜像在批量下载 jar 时不稳定，而 Loom 又把它排在 Maven Central 之前。

若要强制使用官方 Mojang CDN，可加 `-PuseOfficialCdn`：

```
./gradlew build -PuseOfficialCdn
```

本次交付的 jar 即在受限网络下用 JDK 25 的 `javac` 手动编译并打包得到（26.3 不再发布混淆 jar，因此**不需要映射与 refmap**，Mixin 直接用字面名匹配即可）。

### 目录结构

```
src/main/java/dev/enderring/restorer/
├── EnderRingRestorer.java                       # ModInitializer，仅打印加载日志
└── mixin/DistanceToPointFunctionSamplerMixin.java  # 核心：还原 int 溢出 → NaN
src/main/resources/
├── fabric.mod.json
├── ender-ring-restorer.mixins.json
└── assets/ender-ring-restorer/icon.png
```

## 9. 兼容性与注意事项

- 仅影响 `end/islands.json` 中「原点 + 欧几里得」的 `distance_to_point`；经全量扫描，26.3 原版**只有这一处**使用该函数，因此效果严格限定在末地。
- 需要 Java 25；低于 26.3 的版本本身就是坏的，无需本 mod。
- 与其他修改末地地形生成的 mod / 数据包可能冲突（取决于是否也改写 `end/islands`）。
- 属于「还原 bug」性质，会显著改变远距离末地地形，请勿用于正常存档。

## 10. 参考

- [末地星环地形成因（知乎）](https://zhuanlan.zhihu.com/p/1933391363480752876)
- [MC-159283](https://bugs.mojang.com/browse/MC/issues/MC-159283)
- [End Void Rings – MCDF Wiki](https://mcdf.wiki.gg/wiki/Java_Edition:End_Void_Rings)
- [Fabric for Minecraft 26.3](https://fabricmc.net/2026/09/15/263.html)
