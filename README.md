# Ender Ring Restorer

**[English](README.md)** | [简体中文](README.zh-CN.md) | [繁體中文](README.zh-TW.md)

A Fabric mod that restores The End's "ring / circular void" terrain bug ([MC-159283](https://bugs.mojang.com/browse/MC/issues/MC-159283)) on Minecraft **26.3 (Wilderness Bound)**.

- Target: Minecraft 26.3
- Loader: Fabric Loader 0.19.5+
- Java: 25+
- Artifact: `ender-ring-restorer/build/libs/ender-ring-restorer-0.1.0+mc26.3.jar`

---

## 1. Symptom

Since 1.14.4 / 19w36a, The End has shown **concentric circular void bands** beyond roughly **370,720 blocks** from the origin: alternating rings of land and void, with the gap between rings shrinking as the radius grows. In 26.3 this bug was **accidentally fixed** (see section 3), and the fix was never reverted as a "feature". This mod brings it back using the **original code error**.

## 2. Cause

In the old `EndIslandFunction.getHeightValue`, the main island density was computed like this (`x`, `z` are chunk coordinates, i.e. `blockX / 8`):

```java
float errorWithNaN = 100 - Mth.sqrt(x * x + z * z) * 8;   // ← the problem
errorWithNaN = Mth.clamp(errorWithNaN, -100, 80);
```

`x * x + z * z` is **32-bit int arithmetic**. When `x² + z² > 2³¹ - 1`, it **overflows** and wraps to a negative value:

```
s_actual = (x² + z²) - 2³²   →   negative
Mth.sqrt(negative) = NaN
```

NaN propagates through `sloped_cheese → lerp → squeeze → final_density`, turning the whole density column into NaN, which is classified as air → **void**.

### Why rings instead of one big void

The overflow has a period of `2³²`. Let `s = x² + z²`:

- `s mod 2³² ∈ [0, 2³¹)` → signed int is positive → **land**
- `s mod 2³² ∈ [2³¹, 2³²)` → signed int is negative → **void**

Because `s` depends only on the distance from the origin, each interval corresponds to a **ring band**, producing concentric rings. The radius range of the k-th void ring (in blocks, multiplied by 8):

```
r_start(k) = 8 · √(k·2³² + 2³¹)
r_end(k)   = 8 · √((k+1)·2³²)
```

Key values (verified one by one in `RingVerify2`):

| k | Void ring start (blocks) | Void ring end (blocks) |
|---|--------------------------|------------------------|
| 0 | 370,727.6 | 524,288 |
| 1 | 642,119.0 | 741,455.2 |
| 2 | 828,972.1 | 908,093.5 |
| 3 | 980,853.0 | 1,048,576 |

Each ring's theoretical area is exactly `π · 2³⁷ ≈ 4.32 × 10¹¹ blocks²`, and the ring spacing is `ΔR_k ≈ 524,288 / √k` (so rings get denser outward, but converge very slowly — no convergence point is visible within the 30,000,000 world border).

## 3. Why 26.3 "accidentally fixed" it

26.3 moved the main island density out of Java code and into a **data-driven density function**. `end/islands.json` is now:

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

The main island term is now computed by `distance_to_point`. Internally it uses **float** (`Mth.length`) rather than int, so `sqrt` can never overflow and the ring void disappears. The original `EndIslandFunction` was renamed to `end_outer_islands` and now only handles the outer small islands.

> Mathematically the two are equivalent: `√((x/8)²+(z/8)²)·8 = √(x²+z²)`, so the fixed version matches the old version outside the overflow range — **the only difference is that it no longer overflows**.

## 4. Why a mod instead of a data pack

| Approach | Reproduces? | Reason |
|----------|-------------|--------|
| **Data pack** | ✗ | Data-pack density functions only have float/double arithmetic and **cannot express int overflow**. Faking rings with `distance_to_point` + modulo only "simulates the appearance", not "the original code error", which defeats the purpose. |
| **Fabric mod** | ✓ | It can re-execute that int expression directly inside `distance_to_point`'s sampler, **precisely reproducing** the NaN condition. |

So a Fabric mod was chosen, injecting into the **main island term** (`distance_to_point`) rather than the outer islands.

## 5. How it works (Mixin details)

`DistanceToPointFunctionSamplerMixin` injects into
`net.minecraft.world.level.levelgen.densityfunction.generator.DistanceToPointFunction$Sampler#sampleValue`:

```java
if (metric != EUCLIDEAN || x != 0 || y != 0 || z != 0) return;  // only the main island term in end/islands
int sectionX = blockX / 8;
int sectionZ = blockZ / 8;
if (sectionX * sectionX + sectionZ * sectionZ < 0) {           // reproduce the int overflow
    cir.setReturnValue(Float.NaN);
}
```

When there is no overflow it returns 26.3's real distance unchanged, so **non-ring regions behave exactly like 26.3**.

### Why inject the main island term instead of `end_outer_islands`?

26.3's structure is `max(main island term, end_outer_islands)`, and `MaxSampler`'s two sampling paths handle NaN differently:

- **Scalar path** `sampleValue`: uses `Math.max`, so NaN propagates;
- **Bulk path** `sampleVolume` (the path world generation actually uses, together with `interpolated`): it writes the **left** side into the output buffer first, then uses `if (right > left)` to decide whether to overwrite. A `NaN` comparison is always `false`, so:

  - left (main island term) is NaN → the buffer stays NaN → **void holds** ✅
  - right (outer islands) is NaN → not overwritten, the NaN is discarded ❌

So the **main island term** (the left side of `max`) must become NaN. If you injected `end_outer_islands`, the NaN would be swallowed by the bulk path and no ring would appear.

After that, NaN passes through `sub / clamp / slice / mul / max / add / lerp / blend_density / squeeze`, all of which preserve NaN (each verified against 26.3 decompiled bytecode), ending in `final_density = NaN`, classified as air.

## 6. Installation

1. Install **Fabric Loader 0.19.5+** (Minecraft 26.3) and **Java 25**.
2. Put `ender-ring-restorer-0.1.0+mc26.3.jar` into `.minecraft/mods/`.
3. Launch the game. Success is indicated by `Ender Ring Restorer loaded ...` in the log.

## 7. Usage & verification

The rings only affect **newly generated** chunks, not already-generated ones. Fly far out in the End and observe:

```
# switch to spectator/creative, then teleport near the rings
/execute in minecraft:the_end run tp @s 400000 80 0     # should land inside a void ring
/execute in minecraft:the_end run tp @s 524288 80 0     # land restored
/execute in minecraft:the_end run tp @s 741455 80 0     # second void ring
```

Reference table:

| Radius range (blocks) | Appearance |
|-----------------------|------------|
| 0 – 370,727 | Normal terrain |
| **370,728 – 524,287** | **Void (ring 0)** |
| 524,288 – 741,454 | Land |
| **741,455 – 908,093** | **Void (ring 1)** |
| … | And so on; rings gradually narrow |

## 8. Building from source

The project lives in `ender-ring-restorer/`:

```
./gradlew build
```

The build works out of the box, including on restricted networks:

- `settings.gradle` redirects Mojang's asset and library CDNs (`resources.download.minecraft.net`, `libraries.minecraft.net`) to the BMCLAPI mirror, which stays reachable where the official hosts are blocked.
- `build.gradle` restricts that mirror to `com.mojang` artifacts and resolves every third-party Minecraft library from Maven Central (with the Aliyun mirror as a fallback). This is required because the mirror is unreliable for bulk jar downloads and Loom places it ahead of Maven Central.

To force the official Mojang CDNs instead, add `-PuseOfficialCdn`:

```
./gradlew build -PuseOfficialCdn
```

The jar shipped here was compiled and packaged manually with JDK 25's `javac` (26.3 no longer ships obfuscated jars, so **no mappings or refmap are needed**; Mixin matches by literal names).

### Directory structure

```
src/main/java/dev/enderring/restorer/
├── EnderRingRestorer.java                          # ModInitializer, only prints a load log
└── mixin/DistanceToPointFunctionSamplerMixin.java  # core: reproduce int overflow → NaN
src/main/resources/
├── fabric.mod.json
├── ender-ring-restorer.mixins.json
└── assets/ender-ring-restorer/icon.png
```

## 9. Compatibility & notes

- Only affects the `distance_to_point` with "origin + euclidean" in `end/islands.json`; a full scan shows 26.3 vanilla uses that function in **exactly one place**, so the effect is strictly limited to the End.
- Requires Java 25; versions below 26.3 are already broken, so this mod isn't needed there.
- May conflict with other mods / data packs that modify End terrain generation (depending on whether they also rewrite `end/islands`).
- This is a "bug restoration": it significantly alters distant End terrain, so do not use it on normal saves.

## 10. References

- [Cause of the End ring terrain (Zhihu)](https://zhuanlan.zhihu.com/p/1933391363480752876)
- [MC-159283](https://bugs.mojang.com/browse/MC/issues/MC-159283)
- [End Void Rings – MCDF Wiki](https://mcdf.wiki.gg/wiki/Java_Edition:End_Void_Rings)
- [Fabric for Minecraft 26.3](https://fabricmc.net/2026/09/15/263.html)
