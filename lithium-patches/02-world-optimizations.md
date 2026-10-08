# Lithium Patch: World Optimizations

Porting Lithium's world-level optimizations to Purpur — block access inlining, height caching, heightmap updates, and random tick speedup.

## Overview

These optimizations target the world/block access layer that every server tick touches thousands of times. The inlining and caching changes help the JVM JIT compiler produce better code for the most-called methods. The random tick optimization eliminates scanning air blocks in sparse chunks.

## Files to Modify

### 1. `purpur-server/minecraft-patches/sources/net/minecraft/world/level/Level.java.patch`

**What A:** Inline `getBlockState()` — bypass redundant height limit checks and directly access chunk sections via `getChunkAtIfLoaded()` → `getSections()` → section index math.

**What B:** Cache `height`, `bottomY`, `topYInclusive` from `DimensionType` as fields on `Level`. Override all `LevelHeightAccessor` methods to use cached values.

**Why:** `getBlockState()` is the single most called method in Minecraft. Every tick, thousands of calls go through this path. Helping JVM inlining multiplies the benefit. Height caching avoids repeated object traversal through `DimensionType`.

**Lines:** ~30
**Risk:** Very Low — same logic, just inlined.

### 2. `purpur-server/minecraft-patches/sources/net/minecraft/world/level/chunk/LevelChunk.java.patch`

**What A:** Inline `getBlockState()` on `LevelChunk` — same pattern as Level, direct section access.

**What B:** Inline `getFluidState()` on `LevelChunk` — avoid calling `getBlockState()` then `getFluidState()` separately.

**What C:** Cache level height methods via delegation to the Level's cached values.

**Why:** `LevelChunk.getBlockState()` is called from every block interaction, pathfinding query, and collision check.

**Lines:** ~25
**Risk:** Very Low

### 3. `purpur-server/minecraft-patches/sources/net/minecraft/server/level/ServerLevel.java.patch`

**What:** Combined heightmap update — intercept the first heightmap update call and perform all 4 heightmaps (MOTION_BLOCKING, MOTION_BLOCKING_NO_LEAVES, OCEAN_FLOOR, WORLD_SURFACE) in a single pass through the block states. Redirect subsequent 3 individual updates to no-ops.

**Why:** Vanilla updates each heightmap independently, scanning the section 4 times. Reducing to 1 pass saves 75% of heightmap computation per block change.

**Lines:** ~40 + helper class
**Risk:** Low — preserves the same end result, just computed more efficiently.

### 4. `purpur-server/minecraft-patches/sources/net/minecraft/server/level/ServerLevel.java.patch` (additional)

**What A:** Random block ticking speedup — when a chunk section has few random-tickable blocks (below ~384 out of 4096), instead of probing random positions and checking if they are tickable, directly pick from the tickable set.

**What B:** Maintain a `byte[17]` array per section tracking tickable block counts in 17 mini-sections (~256 blocks each). Find the Nth tickable block by scanning the mini-section array first, then doing a short linear search. Fall back to vanilla when section is densely populated.

**Why:** For sparse chunks (nether, caves, oceans, deepslate layers), vanilla probes ~2000 air blocks per random tick that are never tickable. This optimization eliminates that waste entirely.

**Lines:** ~60 + helper classes + section mixin
**Risk:** Medium — needs careful testing to ensure identical tick distribution. The threshold (384) must be tuned.

### 5. `purpur-server/minecraft-patches/sources/net/minecraft/world/level/chunk/LevelChunkSection.java.patch`

**What:** Support the random tick optimization by maintaining the tickable block count in mini-sections.

**Why:** The mini-section byte array must be updated when blocks are placed/removed via `setBlockState()`.

**Lines:** ~15
**Risk:** Low

### 6. `purpur-server/minecraft-patches/sources/net/minecraft/world/level/chunk/storage/ChunkSerializer.java.patch`

**What:** Save/load the tickable block count data for the random tick optimization.

**Why:** When chunks are loaded from disk, the mini-section counts must be restored.

**Lines:** ~10
**Risk:** Low

## Dependencies

- `Level.java.patch` and `LevelChunk.java.patch` are independent of each other
- `heightmap` patch requires a helper class (`CombinedHeightmapUpdate`)
- `random_block_ticking` requires section-level data (patch #5) and serialization (patch #6)
- No external library dependencies

## Implementation Order

1. Height caching + block access inlining (#1, #2) — foundational
2. Combined heightmap update (#3) — standalone
3. Random block ticking (#4, #5, #6) — requires the most testing

## Verification

- Build: `./gradlew build`
- Load test: Generate terrain, verify identical block placement
- Performance test: Compare MSPT before/after with `/perf` in a sparse-chunk-heavy world (nether)
- Heightmap test: Verify `MOTION_BLOCKING` heightmap matches vanilla output

## Expected Impact

- `getBlockState()` inlining: Small but multiplicative improvement across all subsystems
- Height caching: Marginal improvement, helps custom dimensions more
- Combined heightmap: ~75% reduction in heightmap computation on block changes
- Random tick speedup: Significant for nether/cave-heavy servers, negligible for flat overworld
