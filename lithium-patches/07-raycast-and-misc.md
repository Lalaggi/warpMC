# Lithium Patch: Raycast & Misc Optimizations

Porting Lithium's remaining high-value optimizations — faster raycasts, piston shape reuse, profiler optimization, and miscellaneous world utilities.

## Overview

These are standalone optimizations that don't fit neatly into the other categories. Each one targets a specific hot path with a focused optimization.

## Files to Modify

### 1. `purpur-server/minecraft-patches/sources/net/minecraft/world/level/BlockGetter.java.patch`

**What:** Rewrite `clip()` to use an inline anonymous `BiFunction` that:
- Caches chunk references locally (keyed by `chunkX`/`chunkZ`) to avoid repeated chunk manager lookups
- Directly accesses chunk sections instead of calling `getBlockState()`
- Skips fluid shape computation when `FluidHandling == NONE`
- Avoids allocating lambdas by using an anonymous class with captured local state
- Falls back to vanilla lambdas for non-ServerLevel/non-ClientLevel instances

**Why:** `clip()` is used for entity LOS checks, block selection, projectile trajectory, and more. The chunk caching alone eliminates massive overhead from the chunk manager — every ray step previously called `getChunk()` which has non-trivial overhead.

**Lines:** ~80 + accessor
**Risk:** High — `clip()` is used everywhere. Must produce identical results. The ServerLevel/ClientLevel check is critical for compatibility.

### 2. `purpur-server/minecraft-patches/sources/net/minecraft/world/level/block/piston/PistonMovingBlockEntity.java.patch`

**What:** Precompute all 18 possible merged piston base + head shapes (6 directions × 3 offsets: 0, 0.5, 1.0) into a static array. For non-extending pistons, return the precomputed shape directly. For extending pistons, cache offset + simplified shapes in a `volatile VoxelShape[]` array.

**Why:** Pistons in contraptions query collision shapes constantly. `Shapes.or()` triggers full shape merge every time. Precomputed shapes eliminate this entirely for the common case.

**Lines:** ~40 + helper
**Risk:** Medium — must handle all 18 direction/offset combinations correctly. Thread safety via volatile arrays.

### 3. `purpur-server/minecraft-patches/sources/net/minecraft/server/MinecraftServer.java.patch`

**What:** Optimize `Profiler.get()`:
- Fast-path: if `ACTIVE_PROFILER_COUNT == 0`, return `InactiveProfiler.INSTANCE` immediately (no `ThreadLocal.get()`)
- Cache up to 2 thread-profiler pairs in `AtomicReference<Thread>` fields with direct `profiler1`/`profiler2` fields
- Fall back to `ThreadLocal` only for 3+ threads

**Why:** The profiler is called extremely frequently (every profile section start/end). Most of the benefit is the fast-path when profiling is disabled (the common case in production). Reduces `ThreadLocal.get()` cost from ~2% to ~1% of server runtime.

**Lines:** ~40
**Risk:** Low — the profiler is already a no-op in production. Making it faster is safe.

### 4. `purpur-server/minecraft-patches/sources/net/minecraft/world/level/block/state/BlockBehaviour.java.patch`

**What:** Cache `flattenStates` — make `FluidState` store directly whether it is empty (boolean flag) instead of computing it every time.

**Why:** `FluidState.isEmpty()` is called frequently during fluid flow and block interactions. Caching the result avoids repeated computation.

**Lines:** ~10
**Risk:** Very Low

### 5. `purpur-server/minecraft-patches/sources/net/minecraft/world/phys/AABB.java.patch`

**What:** Replace `min(Axis)` and `max(Axis)` virtual dispatch through `Axis.choose()` with switch on `axis.ordinal()` (which JVM can optimize to a jump table).

**Why:** AABB axis access is common in collision and pathfinding.

**Lines:** ~10
**Risk:** Very Low

### 6. `purpur-server/minecraft-patches/sources/net/minecraft/core/Direction.java.patch` (additional)

**What A:** Replace `getOpposite()` modulo/abs with direct array lookup via precomputed `oppositeIndex`.

**What B:** Replace `getRandom()` to avoid array allocation.

**Why:** Direction operations are frequent. Inlining helps the JVM avoid virtual dispatch.

**Lines:** ~8
**Risk:** Very Low

### 7. `purpur-server/minecraft-patches/sources/net/minecraft/world/level/chunk/LevelChunkSection.java.patch` (additional)

**What:** Cache `FluidState` emptiness on `FluidState.flattenStates` — store a boolean directly on the fluid state instead of computing it.

**Lines:** ~5
**Risk:** Very Low

### 8. `purpur-server/minecraft-patches/sources/net/minecraft/world/level/block/Block.java.patch`

**What:** Cache `isRandomlyTicking` result on `BlockState` to avoid repeated tag/property checks.

**Lines:** ~8
**Risk:** Very Low

## Dependencies

- Raycast (#1) is standalone but high-risk — should be tested extensively
- Piston shapes (#2) depends on the shapes infrastructure (specialized shapes from patch 03)
- Profiler (#3) is standalone
- Everything else is standalone
- No external library dependencies

## Implementation Order

1. Micro-optimizations (#4-8) — trivial, very low risk
2. Profiler (#3) — standalone, low risk
3. Piston shapes (#2) — depends on shapes patch, medium risk
4. Raycast (#1) — standalone, highest risk, defer until other patches are stable

## Verification

- Build: `./gradlew build`
- Raycast test: Verify identical LOS checks, projectile trajectories, block selection
- Piston test: Build complex piston contraptions, verify collision behavior
- Profiler test: Run `/perf` and verify profiling still works
- Fluid test: Place water/lava, verify fluid flow is unchanged
- Direction test: Verify all direction-dependent behaviors (redstone, pistons, facing) still work

## Expected Impact

- **Raycast:** High — `clip()` is called thousands of times per tick. Chunk caching eliminates most overhead.
- **Piston shapes:** Medium — significant for piston-heavy contraptions, negligible otherwise.
- **Profiler:** Low-medium — saves ~1% of server runtime when profiling is disabled.
- **Micro-optimizations:** Negligible individually, collectively small improvement.

## Notes

- The raycast optimization (#1) is the most impactful but also the most invasive. It modifies a core method used by many subsystems. Consider deferring it until all other patches are stable and tested.
- The piston shape precomputation (#2) pairs well with the shapes infrastructure from patch 03 — the precomputed shapes can use `VoxelShapeSimpleCube` for maximum performance.
