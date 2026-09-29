# Ender Ring Restorer

[English](README.md) | [简体中文](README.zh-CN.md) | **[繁體中文](README.zh-TW.md)**

在 Minecraft **26.3（Wilderness Bound）** 上重現終界「星環 / 環形虛空」地形 bug（[MC-159283](https://bugs.mojang.com/browse/MC/issues/MC-159283)）的 Fabric mod。

- 目標版本：Minecraft 26.3
- 載入器：Fabric Loader 0.19.5+
- Java：25+
- 產物：`ender-ring-restorer/build/libs/ender-ring-restorer-0.1.0+mc26.3.jar`

---

## 1. 現象

自 1.14.4 / 19w36a 起，終界（The End）在距離原點約 **370,720 格**以外的地方開始出現**同心圓環狀的虛空帶**：一圈陸地、一圈虛空交替出現，環間距隨半徑增大而逐漸縮小。26.3 版本該 bug 被**意外修復**（修復方式見第 3 節），且官方並未把它當成「特性」恢復。本 mod 用**原本的程式錯誤**把它重新帶回來。

## 2. 成因

舊版 `EndIslandFunction.getHeightValue` 裡，主島密度是這樣算的（`x`、`z` 為區塊座標，即 `blockX / 8`）：

```java
float errorWithNaN = 100 - Mth.sqrt(x * x + z * z) * 8;   // ← 問題所在
errorWithNaN = Mth.clamp(errorWithNaN, -100, 80);
```

`x * x + z * z` 是 **32 位元 int 運算**。當 `x² + z² > 2³¹ - 1` 時發生**整數溢位**，回繞成負數：

```
s_actual = (x² + z²) - 2³²   →   負數
Mth.sqrt(負數) = NaN
```

NaN 順著 `sloped_cheese → lerp → squeeze → final_density` 一路傳播，整根密度柱變成 NaN，判定為空氣 → **虛空**。

### 為什麼是「同心圓環」而不是一整塊虛空

溢位以 `2³²` 為週期。記 `s = x² + z²`，則：

- `s mod 2³² ∈ [0, 2³¹)` → 有號 int 為正 → **陸地**
- `s mod 2³² ∈ [2³¹, 2³²)` → 有號 int 為負 → **虛空**

由於 `s` 只與到原點的距離有關，每一段區間都對應一個**圓環帶**，因此形成同心圓環。第 k 個虛空環的半徑範圍（換算成方塊，乘以 8）：

```
r_start(k) = 8 · √(k·2³² + 2³¹)
r_end(k)   = 8 · √((k+1)·2³²)
```

關鍵數值（已在 `RingVerify2` 中逐一核對）：

| k | 虛空環起點（格） | 虛空環終點（格） |
|---|-----------------|-----------------|
| 0 | 370,727.6 | 524,288 |
| 1 | 642,119.0 | 741,455.2 |
| 2 | 828,972.1 | 908,093.5 |
| 3 | 980,853.0 | 1,048,576 |

每個環的理論面積恆為 `π · 2³⁷ ≈ 4.32 × 10¹¹ 方塊²`，環間距 `ΔR_k ≈ 524,288 / √k`（因此越往外環越密，但收斂極慢，在 30,000,000 的世界邊界內看不到收斂點）。

## 3. 26.3 為什麼被「意外修復」

26.3 把主島密度從 Java 程式碼搬到了**資料驅動的密度函數**。`end/islands.json` 現在是：

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

主島項改由 `distance_to_point` 計算。它在內部用 **float**（`Mth.length`）而非 int，`sqrt` 永遠不會溢位，於是環形虛空消失。原本的 `EndIslandFunction` 被改名為 `end_outer_islands`，只負責外圍小島。

> 數學上二者等價：`√((x/8)²+(z/8)²)·8 = √(x²+z²)`，所以修復版與非溢位區間的舊版結果一致——**唯一的差別就是不再溢位**。

## 4. 為什麼做 Mod 而不是資料包

| 方案 | 能否重現 | 原因 |
|------|---------|------|
| **資料包** | ✗ | 資料包密度函數只有 float/double 運算，**無法表達 int 溢位**。用 `distance_to_point` + 取模去硬湊環帶只是「模擬外觀」，不是「原本的程式錯誤」，違背需求。 |
| **Fabric Mod** | ✓ | 可以直接在 `distance_to_point` 的取樣點裡重新執行那句 int 表示式，**精確還原** NaN 的產生條件。 |

因此選擇 Fabric mod，注入點選在**主島項**（`distance_to_point`）而非外圍小島。

## 5. 工作原理（Mixin 細節）

`DistanceToPointFunctionSamplerMixin` 注入
`net.minecraft.world.level.levelgen.densityfunction.generator.DistanceToPointFunction$Sampler#sampleValue`：

```java
if (metric != EUCLIDEAN || x != 0 || y != 0 || z != 0) return;  // 僅命中 end/islands 的主島項
int sectionX = blockX / 8;
int sectionZ = blockZ / 8;
if (sectionX * sectionX + sectionZ * sectionZ < 0) {           // 還原 int 溢位
    cir.setReturnValue(Float.NaN);
}
```

不溢位時原樣返回 26.3 的真實距離，因此**非環帶區域與 26.3 表現完全一致**。

### 為什麼注入主島項，而不是 `end_outer_islands`？

26.3 的結構是 `max(主島項, end_outer_islands)`，而 `MaxSampler` 的兩條取樣路徑對 NaN 的處理不同：

- **純量路徑** `sampleValue`：用 `Math.max`，NaN 會傳播；
- **批次路徑** `sampleVolume`（世界生成實際走的路徑，配合 `interpolated` 使用）：先把**左側**寫入輸出緩衝，再用 `if (right > left)` 決定是否覆蓋。`NaN` 參與比較恆為 `false`，所以：

  - 左側（主島項）是 NaN → 緩衝保持 NaN → **虛空成立** ✅
  - 右側（外圍小島）是 NaN → 不覆蓋，NaN 被丟棄 ❌

所以必須讓**主島項**（`max` 的左側）變 NaN。若注入 `end_outer_islands`，NaN 會在批次路徑裡被吃掉，環帶根本不會出現。

NaN 之後會依次穿過 `sub / clamp / slice / mul / max / add / lerp / blend_density / squeeze` 等運算子，這些運算子全部保持 NaN（已逐類核對 26.3 反編譯位元碼），最終 `final_density = NaN`，判定為空氣。

## 6. 安裝

1. 安裝 **Fabric Loader 0.19.5+**（Minecraft 26.3）與 **Java 25**。
2. 把 `ender-ring-restorer-0.1.0+mc26.3.jar` 放進 `.minecraft/mods/`。
3. 啟動遊戲。日誌出現 `Ender Ring Restorer loaded ...` 即成功。

## 7. 使用與驗證

環帶只影響**新生成**的區塊，對已生成的區塊無效。進入終界後飛到遠處觀察：

```
# 切旁觀/創造，直接傳送到環帶附近
/execute in minecraft:the_end run tp @s 400000 80 0     # 應落在虛空環帶內
/execute in minecraft:the_end run tp @s 524288 80 0     # 陸地恢復
/execute in minecraft:the_end run tp @s 741455 80 0     # 第二個虛空環帶
```

對照表：

| 半徑區間（格） | 表現 |
|---------------|------|
| 0 – 370,727 | 正常地形 |
| **370,728 – 524,287** | **虛空（環帶 0）** |
| 524,288 – 741,454 | 陸地 |
| **741,455 – 908,093** | **虛空（環帶 1）** |
| … | 依此類推，環帶逐漸變窄 |

## 8. 從原始碼建置

專案位於 `ender-ring-restorer/`：

```
./gradlew build
```

開箱即用，受限網路下也可直接建置：

- `settings.gradle` 把 Mojang 的資源與函式庫 CDN（`resources.download.minecraft.net`、`libraries.minecraft.net`）重定向到 BMCLAPI 映像站，在官方位址被封鎖時仍可存取。
- `build.gradle` 把該映像站限制為僅提供 `com.mojang` 產物，其餘第三方 Minecraft 函式庫全部走 Maven Central（並以阿里雲映像站作為回退）。這是必要的：該映像站在批次下載 jar 時不穩定，而 Loom 又把它排在 Maven Central 之前。

若要強制使用官方 Mojang CDN，可加 `-PuseOfficialCdn`：

```
./gradlew build -PuseOfficialCdn
```

本次交付的 jar 即在受限網路下用 JDK 25 的 `javac` 手動編譯並封裝得到（26.3 不再發布混淆 jar，因此**不需要對應與 refmap**，Mixin 直接用字面名比對即可）。

### 目錄結構

```
src/main/java/dev/enderring/restorer/
├── EnderRingRestorer.java                       # ModInitializer，僅列印載入日誌
└── mixin/DistanceToPointFunctionSamplerMixin.java  # 核心：還原 int 溢位 → NaN
src/main/resources/
├── fabric.mod.json
├── ender-ring-restorer.mixins.json
└── assets/ender-ring-restorer/icon.png
```

## 9. 相容性與注意事項

- 僅影響 `end/islands.json` 中「原點 + 歐幾里得」的 `distance_to_point`；經全量掃描，26.3 原版**只有這一處**使用該函數，因此效果嚴格限定在終界。
- 需要 Java 25；低於 26.3 的版本本身就是壞的，無需本 mod。
- 與其他修改終界地形生成的 mod / 資料包可能衝突（取決於是否也改寫 `end/islands`）。
- 屬於「還原 bug」性質，會顯著改變遠距離終界地形，請勿用於正常存檔。

## 10. 參考

- [終界星環地形成因（知乎）](https://zhuanlan.zhihu.com/p/1933391363480752876)
- [MC-159283](https://bugs.mojang.com/browse/MC/issues/MC-159283)
- [End Void Rings – MCDF Wiki](https://mcdf.wiki.gg/wiki/Java_Edition:End_Void_Rings)
- [Fabric for Minecraft 26.3](https://fabricmc.net/2026/09/15/263.html)
