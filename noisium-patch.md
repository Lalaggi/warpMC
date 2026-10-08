# Noisium Port to Purpur

Porting [noisium-forked](InspirationForPerformancePatches/noisium-forked/) world generation optimizations as Purpur source patches.

## Overview

Noisium optimizes `NoiseBasedChunkGenerator` by bypassing `LevelChunkSection.setBlockState()` during the `doFill` phase of chunk generation. Instead of going through the full `setBlockState` pipeline (lock acquisition, counter recomputation, heightmap updates), it writes directly to palette storage and manually increments counters. This yields measurable worldgen speedup with full 1:1 vanilla parity.

Paper has **no existing patches** to `NoiseBasedChunkGenerator`.

## Thread Safety

Safe with Paper's async worldgen:
- `lambda$fillFromNoise$0` calls `chunkSection.acquire()` on ALL sections before `doFill()`, then `release()` after
- Each chunk has its own `LevelChunkSection[]` -- adjacent chunks write to different section objects
- `getAndSetUnchecked()` does not acquire/release locks itself
- Counter increments happen within the locked region -- no races

## Files to Modify

### 1. `build-data/purpur.at` -- Access Transformers

Add at end:
```
public net.minecraft.world.level.chunk.LevelChunkSection states
public net.minecraft.world.level.chunk.LevelChunkSection nonEmptyBlockCount
public net.minecraft.world.level.chunk.LevelChunkSection tickingFluidCount
public net.minecraft.world.level.chunk.LevelChunkSection tickingBlockCount
```

These expose the 4 private fields on `LevelChunkSection` that the patch needs direct access to. The AT format uses `public` (or `public-f` for final fields). These fields are `short` primitives and a `PalettedContainer` -- no inheritance concerns.

### 2. `purpur-server/minecraft-patches/sources/net/minecraft/world/level/levelgen/NoiseBasedChunkGenerator.java.patch` -- Core Patch

This is the main patch. It targets two methods in `NoiseBasedChunkGenerator`:

#### Patch A: Redirect `setBlockState` call inside `doFill()`

The `doFill()` method contains a loop that calls `LevelChunkSection.setBlockState(x, y, z, state, true)` for every block position during noise generation. We replace this call with:

1. Increment `nonEmptyBlockCount` by 1 (always -- doFill only places non-air blocks)
2. If the block has a non-empty fluid state, increment `tickingFluidCount` by 1
3. If the block is randomly tickable, increment `tickingBlockCount` by 1
4. Call `chunkSection.getStates().getAndSetUnchecked(x, y, z, blockState)` to write directly to palette storage
5. Return the blockState (method signature requires `BlockState` return)

**Source-level approach:** Since Purpur patches are source patches (not mixins), we need to find the `setBlockState` call inside `doFill()` and replace it inline. The `doFill` method calls `chunkSection.setBlockState(localX, localY, localZ, blockState, true)`. We replace that single line with the 5-step direct write above.

**Equivalent Java:**
```java
// Before (vanilla):
chunkSection.setBlockState(localX, localY, localZ, blockState, true);

// After (noisium):
// Manually update counters -- these increment only during doFill where only non-air blocks are placed
short nonEmpty = chunkSection.nonEmptyBlockCount;
chunkSection.nonEmptyBlockCount = (short) (nonEmpty + 1);
if (!blockState.getFluidState().isEmpty()) {
    chunkSection.tickingFluidCount = (short) (chunkSection.tickingFluidCount + 1);
}
if (blockState.isRandomlyTicking()) {
    chunkSection.tickingBlockCount = (short) (chunkSection.tickingBlockCount + 1);
}
chunkSection.getStates().getAndSetUnchecked(localX, localY, localZ, blockState);
```

Note: With ATs, we access the fields directly instead of via accessor methods. This is simpler than the mixin accessor approach used in the Fabric mod.

#### Patch B: Optimize `lambda$fillFromNoise$0` chunk section locking

The lambda that wraps `doFill()` acquires/releases chunk section locks. Noisium optimizes it by:
1. Getting the chunk sections array directly via `chunk.getSections()` instead of building it manually
2. Using `fori` loops instead of `foreach`/enhanced for loops
3. Reusing the same array reference for both acquire and release phases

**Source-level approach:** The lambda `lambda$fillFromNoise$0` is a synthetic method. In Paper/Purpur source, this may appear as an anonymous inner class or as a direct method. We need to patch it to:
- Replace any manual section list construction with `chunk.getSections()` direct access
- Ensure the locking/unlocking loops use `fori` with the sections array

**Equivalent Java:**
```java
// The optimized locking loop:
final LevelChunkSection[] chunkSections = chunk.getSections();
final int startIdx = chunk.getSectionIndex(heightFloor * cellHeight - 1 + minimumY);
final int endIdx = chunk.getSectionIndex(minimumY);

for (int i = startIdx; i >= endIdx; --i) {
    chunkSections[i].acquire();
}
try {
    return this.doFill(blender, structureAccessor, noiseConfig, chunk, minimumYFloorDiv, heightFloor);
} finally {
    for (int i = startIdx; i >= endIdx; --i) {
        chunkSections[i].release();
    }
}
```

### 3. (Optional) `purpur-server/minecraft-patches/sources/net/minecraft/world/level/chunk/LevelChunkSection.java.patch` -- Biome Axis Order

Optimize `fillBiomesFromNoise` to iterate in Y-Z-X order instead of X-Y-Z. This is a micro-optimization that improves cache locality because `PalettedContainer` stores data in Y-major order.

**Change:** Replace the nested loop iteration order:
```java
// Before (vanilla): X -> Y -> Z
for (int x = 0; x < 4; x++)
    for (int y = 0; y < 4; y++)
        for (int z = 0; z < 4; z++)

// After (noisium): Y -> Z -> X
for (int y = 0; y < 4; y++)
    for (int z = 0; z < 4; z++)
        for (int x = 0; x < 4; x++)
```

### 4. (Optional) `purpur-server/minecraft-patches/sources/net/minecraft/world/level/levelgen/NoiseSettings.java.patch` -- Cell Dimension Caching

Cache `cellWidth` and `cellHeight` values that are computed from `QuartPos.toBlock()` on every call. These values never change after construction.

**Change:** Add two `int` fields, set them in the constructor via `@Inject` equivalent, and short-circuit `getCellWidth()` / `getCellHeight()` to return the cached values.

### 5. (Optional) `purpur-server/minecraft-patches/sources/net/minecraft/world/level/levelgen/material/MaterialRuleList.java.patch` -- fori Loop

Replace `foreach` over `materialRuleList` with `fori` loop to avoid iterator allocation.

**Change:**
```java
// Before:
for (NoiseChunk.BlockStateFiller rule : materialRuleList) { ... }

// After:
for (int i = 0; i < materialRuleList.length; i++) {
    NoiseChunk.BlockStateFiller rule = materialRuleList[i];
    ...
}
```

## Patch File Format

Purpur source patches use unified diff format. Example structure for the core patch:

```diff
--- a/net/minecraft/world/level/levelgen/NoiseBasedChunkGenerator.java
+++ b/net/minecraft/world/level/levelgen/NoiseBasedChunkGenerator.java
@@ -XXX,Y +_,Z @@
```

The `+_` in the hunk header is a Purpur convention (Paper uses it too) to indicate the new line number is computed by the patch tool.

## Implementation Steps

1. Run `./gradlew applyAllPatches` to generate decompiled NMS sources
2. Locate `NoiseBasedChunkGenerator.java` in `purpur-server/src/`
3. Find the `setBlockState` call inside `doFill()` and apply Patch A
4. Find the lambda/synthetic `lambda$fillFromNoise$0` method and apply Patch B
5. Add ATs to `build-data/purpur.at`
6. Build and test: `./gradlew build`
7. Generate world and verify chunk generation produces identical results (noisium has 1:1 vanilla parity)

## Risk Assessment

- **Low risk.** The optimization is small (~15 lines of code change in the core patch), self-contained to a single class, and has full vanilla parity
- Thread safety confirmed safe with Paper's async worldgen
- No conflicts with existing Purpur or Paper patches (no existing `NoiseBasedChunkGenerator` patches)
- The `getAndSetUnchecked` call is the same method that `setBlockState` calls internally -- we just skip the surrounding overhead

## Expected Impact

- Measurable worldgen speedup (noisium benchmarks show seconds to dozens of seconds faster depending on chunk count)
- No gameplay changes -- block placement, lighting, and all game mechanics are identical
- Most noticeable during initial world generation, exploration at high speed, and pregen tools
