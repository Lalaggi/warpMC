# Lithium Patch: VoxelShape Optimizations

Porting Lithium's VoxelShape system overhaul to Purpur — specialized shape types, faster intersection testing, optimized merging, and lazy collision context.

## Overview

This is the highest-impact optimization category. VoxelShapes are used for every collision check in the game — entity-block, entity-entity, block-block (pistons), and raycasting. Lithium replaces vanilla's generic VoxelShape implementations with specialized types that use O(1) math for the common case (cuboid shapes) and faster algorithms for all cases.

## Files to Modify

### 1. `purpur-server/minecraft-patches/sources/net/minecraft/world/phys/shapes/Shapes.java.patch`

**What A:** Rewrite `joinIsNotEmpty()` to fast-path when both shapes are `VoxelShapeSimpleCube` — use direct AABB intersection/exclusion tests with epsilon handling (matching vanilla's 1e-7 boundary). Fall back to vanilla only for non-cuboid or "tiny" shapes.

**What B:** Rewrite `createIndexMerger()` to return `LithiumDoublePairList` instead of `IndirectMerger` — works against raw `double[]` arrays instead of `DoubleList` abstractions. ~50% faster merging.

**What C:** Replace the `BLOCK`, `EMPTY`, `INFINITY` constants with specialized shape types.

**Why:** `joinIsNotEmpty` is called for essentially every collision check. Most block shapes are simple cuboids, so the fast path triggers for the vast majority of calls. Shape merging is performed during every collision check.

**Lines:** ~80 + `VoxelShapeMatchesAnywhere` helper (~167 lines) + `LithiumDoublePairList` helper (~122 lines)
**Risk:** Medium — the epsilon handling in `VoxelShapeMatchesAnywhere` is intricate. Must be tested against edge cases (thin slabs, barrier blocks, fluid boundaries).

### 2. `purpur-server/minecraft-patches/sources/net/minecraft/world/phys/shapes/VoxelShape.java.patch`

**What A:** Overwrite `collideX()` with smarter iteration that delays `findIndex()` calls as long as possible using `minY/minZ/maxY/maxZ` pattern — avoids redundant binary searches.

**What B:** Overwrite `findIndex()` with inlined binary search — no lambda allocation.

**What C:** Add `intersectsJNE()` for fast AABB intersection on cuboid shapes.

**Why:** `collideX` is the core collision math. The current implementation calls `findIndex` (binary search) far more often than necessary.

**Lines:** ~50
**Risk:** Medium

### 3. `purpur-server/minecraft-patches/sources/net/minecraft/world/phys/shapes/VoxelShape.java.patch` (or new file)

**What:** Create specialized shape subclasses:

- **`VoxelShapeSimpleCube`**: Stores 6 double coordinates directly. `collideX` uses simplified math (no voxel iteration). `findIndex` uses direct comparisons (no binary search). For shapes with a single cuboid (most block shapes).
- **`VoxelShapeAlignedCuboid`**: Extends `VoxelShapeSimpleCube` for shapes aligned to power-of-two subdivisions (1/2, 1/4, 1/8 block). Handles inner wall collision for pistons, slabs.
- **`VoxelShapeEmpty`**: All operations are no-ops or return constants.

**Why:** Vanilla's generic `ArrayVoxelShape` and `BitSetVoxelShape` use O(n) voxel iteration for all shapes. Specialized types use O(1) math.

**Lines:** ~300 (new classes)
**Risk:** Medium-High — must produce bit-for-bit identical results to vanilla for all block shapes. Extensive testing required.

### 4. `purpur-server/minecraft-patches/sources/net/minecraft/world/phys/shapes/CubeVoxelShape.java.patch`

**What:** Cache `DoubleList[]` coordinate arrays at construction time. Precompute `1.0 / parts` as `double scale` in `CubePointRange` to replace division with multiplication.

**Why:** `getCoords(axis)` creates a new `CubePointRange` on every call. `CubeVoxelShape` is the most common shape type.

**Lines:** ~10
**Risk:** Very Low

### 5. `purpur-server/minecraft-patches/sources/net/minecraft/world/phys/shapes/EntityCollisionContext.java.patch`

**What:** Lazy-initialize `heldItem` and `descending` fields. Force `instanceof LivingEntity` to always return `false` during construction (avoiding inventory access). Populate fields on first access via `isHoldingItem()` or `isDescending()`.

**Why:** `EntityCollisionContext` is created for every entity collision. Most checks never call `isHoldingItem()` or `isDescending()`, so the lazy init avoids the cost of `LivingEntity.getMainHandItem()` for the majority of cases.

**Lines:** ~30
**Risk:** Low-Medium — must ensure no code path reads these fields during construction.

### 6. `purpur-server/minecraft-patches/sources/net/minecraft/world/level/block/state/BlockBehaviour.java.patch`

**What:** Add a `Object2BooleanCacheTable<VoxelShape>` (512-entry LRU cache) for `isShapeFullBlock()`. Replace the unbounded `ConcurrentHashMap` in vanilla.

**Why:** `isShapeFullBlock` is called very frequently during lighting, collision, and pathfinding. The bounded cache prevents memory bloat while maintaining a high hit rate.

**Lines:** ~20 + cache class
**Risk:** Low

## Dependencies

- Specialized shapes (#3) must be implemented before the `Shapes` rewrite (#1) can use them
- `CubeVoxelShape` caching (#4) is standalone
- `EntityCollisionContext` (#5) is standalone
- `blockstate_cache` (#6) is standalone
- The `VoxelShapeMatchesAnywhere` and `LithiumDoublePairList` helpers are self-contained

## Implementation Order

1. Specialized shapes (#3) — foundational, other patches depend on these types
2. `CubeVoxelShape` caching (#4) — trivial, standalone
3. `EntityCollisionContext` lazy init (#5) — standalone
4. `blockstate_cache` (#6) — standalone
5. `Shapes` rewrite (#1) — depends on specialized shapes existing
6. `VoxelShape` collision math (#2) — depends on specialized shapes existing

## Verification

- Build: `./gradlew build
- Collision test: Push entities into walls, pistons, fences, slabs — verify identical behavior
- Edge case test: Thin slabs (1-pixel), barrier blocks, fluid boundaries, honey blocks
- Performance test: Compare MSPT before/after with 100+ entities colliding
- Shape comparison: For every `BlockState`, compare `getCollisionShape()` output between vanilla and patched

## Expected Impact

- **Very high.** This is the single most impactful optimization category in Lithium.
- Specialized shapes + optimized matching together eliminate the majority of collision CPU cost.
- The lazy shape context avoids expensive inventory lookups for most entity movement.
- The full-cube cache speeds up lighting and pathfinding.
