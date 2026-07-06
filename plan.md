# WarpMC: Performance Patches + Rebrand Plan

Fork of Purpur (MC 26.2) that ports InspirationForPerformancePatches optimizations and rebrands to "WarpMC".

## Architecture

Purpur uses a patch-based system:
- `purpur-server/minecraft-patches/sources/` — patches applied directly to Minecraft source
- `purpur-server/paper-patches/features/` — feature patches (Rebrand, Ridables, etc.)
- `purpur-server/paper-patches/files/` — file-level patches to CraftBukkit/Paper code
- `purpur-api/paper-patches/` — API patches

Performance patches will go through the same patch system as new feature patches (or additional hunks in existing patches).

## Patch Building Workflow

1. Edit source directly in `purpur-server/src/minecraft/java/net/minecraft/` (changes go on top of existing applied patches)
2. `./gradlew :purpur-server:fixupMinecraftSourcePatches` — commits source changes to the git patch tracking branch
3. `./gradlew :purpur-server:rebuildMinecraftSourcePatches` — regenerates `.java.patch` files in `minecraft-patches/sources/` from the committed source changes
4. Verify patches exist: `ls` / `cat` the patch file(s)
5. `./gradlew :purpur-server:applyMinecraftPatches` — applies all patches from scratch to verify they compile correctly

**Do NOT** hand-write patch files — always use source edits → fixup → rebuild. Paperweight's diff format uses `_` hunk context lines that standard `git apply` rejects, and the tooling ensures correct hunk header counts.

---

## Phase 1: Rebrand — Purpur → WarpMC

### 1.1 Player-Facing Branding Strings

| File | Current (Purpur) | Target (WarpMC) |
|------|------------------|-----------------|
| `purpur-server/paper-patches/files/.../WatchdogThread.java.patch` | "DO NOT REPORT THIS TO PURPUR", "Purpur bug", "Purpur version", github.com/PurpurMC/Purpur | "DO NOT REPORT THIS TO WARPMC", "WarpMC bug", "WarpMC version", github.com/lalaggi/warpMC |
| `purpur-server/minecraft-patches/sources/net/minecraft/CrashReport.java.patch` | "DO NOT REPORT THIS TO PAPER! REPORT TO PURPUR INSTEAD!" | "DO NOT REPORT THIS TO PAPER! REPORT TO WARPMC INSTEAD!" |
| `purpur-server/paper-patches/files/.../CraftScheduler.java.patch` | "Report this to Purpur" | "Report this to WarpMC" |
| `purpur-server/paper-patches/files/.../PaperConsole.java.patch` | `.appName("Purpur")` | `.appName("WarpMC")` |
| `purpur-server/paper-patches/files/.../Metrics.java.patch` | `"Purpur"`, `"purpur_version"` | `"WarpMC"`, `"warpmc_version"` |

### 1.2 Manifest / Build Identity

| File | Field | Value |
|------|-------|-------|
| `purpur-server/build.gradle.kts.patch` | `Implementation-Title` | `"WarpMC"` |
| `purpur-server/build.gradle.kts.patch` | `Specification-Title` | `"WarpMC"` |
| `purpur-server/build.gradle.kts.patch` | `Specification-Vendor` | `"WarpMC Team"` |
| `purpur-server/build.gradle.kts.patch` | `Brand-Id` | `"warpmc:warpMC"` |
| `purpur-server/build.gradle.kts.patch` | `Brand-Name` | `"WarpMC"` |
| `purpur-api/paper-patches/features/0001-Rebrand.patch` | `BRAND_PURPUR_ID` | `Key.key("warpmc", "warpMC")` |

### 1.3 ServerBuildInfoImpl

| File | Current | Target |
|------|---------|--------|
| `purpur-server/paper-patches/files/.../ServerBuildInfoImpl.java.patch` | `BRAND_PURPUR_NAME = "Purpur"` | `BRAND_WARPMC_NAME = "WarpMC"` |
| (same file) | `BRAND_PURPUR_ID` fallback | `BRAND_WARPMC_ID` |
| (same file) | `isBrandCompatible` checks `BRAND_PAPER_ID` | Keep as-is (backward compat) |

### 1.4 Logo

`purpur-server/paper-patches/features/0001-Rebrand.patch` replaces `logo.png`. For WarpMC:
- Create a new WarpMC logo (or placeholder)
- Update the binary patch, or replace the logo in a separate patch

### 1.5 Internal Naming (kept as Purpur)

- **Java package**: `org.purpurmc.purpur` — NOT renamed (massive refactor, plugins depend on it)
- **Config files**: `purpur.yml`, `purpur-settings` — NOT renamed (plugin compatibility, user muscle memory)
- **Gradle group**: `org.purpurmc.purpur` — NOT renamed (matches package)

### 1.6 GitHub URL

All error messages reference: `https://github.com/lalaggi/warpMC`

---

## Phase 2: Performance Optimizations

### 2.1 noisium (smallest, safest — do first)

**Impact**: Faster worldgen, especially noise generation
**Risk**: Low — Paper's `acquire()`/`release()` bracket protects chunk section access

Plan: `noisium-patch.md`

Core changes:
1. Direct palette writes in `NoiseBasedChunkGenerator.doFill()` — bypass `PalettedContainer.set()` lock overhead
2. Optimized section locking in `PalettedContainer` — read-path avoids write lock
3. 4 access transformers in `purpur.at`

### 2.2 FerriteCore (second — high memory savings)

**Impact**: ~770MB+ heap reduction from FastMap, ~200MB+ from cache dedup
**Risk**: Medium — data structures must match vanilla semantics exactly

Plan: `ferritecore-patch.md`

Three optimizations:
1. **FastMap** — replace `Object2IntOpenHashMap<BlockState>` with compact `int[]` + bit-packing
2. **Cache dedup** — interning `PatchedDataComponentMap` instances on chunk save
3. **PatchedDataComponentMap patching** — avoid copying full component map for common cases

Excluded: ThreadingDetector (race conditions with Moonrise)

### 2.3 Lithium (seven segments, most complex)

**Impact**: Medium per-category, cumulative significant
**Risk**: Low-Medium per segment, high if all done at once

Plans: `lithium-patches/01` through `07`

Recommended implementation order:
1. **01 - Collections & Allocations** (~15 files) — fastutil swaps, BlockPos caching
2. **02 - World Optimizations** — block access inlining, height caching, heightmap
3. **05 - Entity Optimizations** — inactive navigations, type predicates
4. **06 - AI Optimizations** — stream replacement, memory tracking
5. **04 - Explosion Optimizations** — block raycast cache, air skip
6. **07 - Raycast & Misc** — chunk-caching raycast, piston shapes
7. **03 - Shape Optimizations** — specialized shapes, intersection (highest impact but most complex)

Deferred: hopper sleeping (~60 files, 4 utility subsystems), block entity sleeping (20+ vanilla classes)

---

## Phase 3: Implementation Order

1. Create WarpMC logo placeholder
2. Apply rebrand to all player-facing strings (Phase 1)
3. Port noisium (Phase 2.1) — smallest, verifies patch workflow
4. Port FerriteCore FastMap + cache dedup (Phase 2.2)
5. Port Lithium segments in order (Phase 2.3, 01→07)
6. Test and validate each optimization independently

---

## Excluded Optimizations (with rationale)

| Project | Rationale |
|---------|-----------|
| **Alternate Current** | Already bundled in Paper (`// Paper - optimize redstone (Alternate Current)`) |
| **VMP** | Paper's Moonrise covers core value; PalettedContainer lock removal is dangerous with Moonrise |
| **Lithium hopper sleeping** | ~60 files, 3000+ lines, 4 utility subsystems — too complex for initial implementation |
| **Lithium block entity sleeping** | 20+ vanilla classes modified, feeds into hopper sleeping complexity |
| **FerriteCore ThreadingDetector** | Self-disabled by default, race conditions with Moonrise, requires self-referencing patch |

---

## Key References

- FerriteCore source: `InspirationForPerformancePatches/FerriteCore/`
- Lithium source: `InspirationForPerformancePatches/lithium/`
- noisium source: `InspirationForPerformancePatches/noisium-forked/`
- Current Purpur rebrand: `purpur-server/paper-patches/features/0001-Rebrand.patch`
- Purpur build config: `purpur-server/build.gradle.kts.patch`
- Access transformers: `build-data/purpur.at`
- Gradle properties: `gradle.properties` (paperCommit, mcVersion)
