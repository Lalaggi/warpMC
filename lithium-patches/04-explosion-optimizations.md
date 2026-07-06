# Lithium Patch: Explosion Optimizations

Porting Lithium's explosion raycast and entity exposure optimizations to Purpur.

## Overview

Explosions are one of the most CPU-intensive operations on servers with TNT, creepers, withers, or end crystals. A single explosion fires thousands of rays, each probing blocks along the path. Lithium optimizes both the block raycast (which blocks are destroyed) and the entity raycast (how much each entity is exposed to the explosion).

## Files to Modify

### 1. `purpur-server/minecraft-patches/sources/net/minecraft/world/level/ServerExplosion.java.patch`

**What A — Block raycast cache:** Add a direct-mapped cache of 512 entries (9-bit hash) storing `BlockState` + blast resistance per block position. Uses `Long.MIN_VALUE` as sentinel for empty slots. ThreadLocal storage avoids allocation per explosion.

**What B — Chunk caching:** Keep `prevChunk`/`prevChunkX`/`prevChunkZ` so adjacent ray steps in the same chunk skip `getChunk()` entirely.

**What C — BlockPos encoding:** Store block positions as `long` packed values via `BlockPos.asLong()` in a `LongOpenHashSet` instead of allocating `BlockPos` objects for every ray step. Only allocate `BlockPos` objects at the end to build the result list.

**What D — Direct section access:** Read `LevelChunkSection` directly with manual index math, avoiding `Chunk.getBlockState()` overhead.

**What E — Surface-only ray origin:** Skip 2744 interior rays by only firing from the surface of the 14x14x14 origin volume using `zIncrement = xIncrement || yIncrement ? 1 : 15`.

**What F — Ray over-sampling skip:** If `prevX/Y/Z == blockX/Y/Z` on the current step, reuse the previous resistance without re-querying.

**Why:** Each optimization targets a specific waste in vanilla's explosion algorithm. The direct-mapped cache alone achieves ~85% hit rate in TNT testing, eliminating most chunk lookups. Surface-only rays eliminate ~70% of rays that always hit the same block as an adjacent ray.

**Lines:** ~430 (main mixin) + 10 (cache class) + 10 (interface)
**Risk:** Medium — must produce identical destruction results. The cache must be invalidated correctly when explosions happen in the same chunk simultaneously (ThreadLocal handles this).

### 2. `purpur-server/minecraft-patches/sources/net/minecraft/world/level/ServerExplosion.java.patch` (additional)

**What A — Skip air blocks:** When the explosion does not create fire (`this.fire == false`), set a flag so air blocks encountered during ray-casting are not added to the `touched` set and not processed in `interactWithBlocks()`. Avoids allocating `BlockPos` for air blocks, calling `shouldBlockExplode()` on air, and adding air to the destruction list.

**What B — End dimension safety:** In the End dimension near the origin (where the end portal / dragon respawn crystal lives), disable air skipping to prevent the end portal from being destroyed by nearby explosions.

**Why:** The majority of blocks hit by explosion rays are air. Skipping them eliminates the largest source of unnecessary work.

**Lines:** ~60
**Risk:** Low-Medium — the End dimension safety check is critical. Non-vanilla behavior: explosions do not destroy blocks placed by the same explosion (e.g. wither roses).

### 3. `purpur-server/minecraft-patches/sources/net/minecraft/server/level/ServerLevel.java.patch`

**What:** Delayed explosion without air counting — when air skipping is active, skip counting exploded air blocks (used for particle spawning) when no player is nearby. Defer the second `explode()` call.

**Why:** In vanilla, `ServerLevel.explode()` calls `ServerExplosion.explode()` twice — once to calculate positions (getting the block count) and once to actually destroy blocks. The block count is used for particle spawning. With air skipping, the count would undercount, so skip it entirely when no player is near.

**Lines:** ~60
**Risk:** Low — only affects particle calculations, and only when no player is nearby to see them anyway.

### 4. `purpur-server/minecraft-patches/sources/net/minecraft/world/level/ServerExplosion.java.patch` (entity raycast section)

**What A — ClipContext reuse:** Instead of allocating a new `ClipContext` for each entity, reuse a single one by making `from` mutable. Per-thread storage via `@Share`.

**What B — Direct-mapped AABB cache:** A 64-entry (6-bit) cache mapping `BlockPos -> AABB[]` (collision shape as array of AABBs). Avoids re-computing `BlockState.getCollisionShape()` for blocks hit by multiple entity rays.

**What C — Specialized hit factory:** Replace `Level.clip()` with a custom `BiFunction<ClipContext, BlockPos, BlockHitResult>` that:
- Reads block state directly from chunk sections
- Caches chunk reference across ray steps
- Returns `null` instead of allocating `BlockHitResult` objects
- Skips fluid handling entirely (not needed for exposure calculation)
- Skips hit direction computation
- Uses pre-allocated `MISS` sentinel

**What D — Pure math intersection:** `ExplosionEntityRays.doesRayHitOffsetAABBVolumes()` — ray-AABB intersection test operating on pre-extracted AABB arrays, avoiding `VoxelShape` allocation.

**Why:** `getSeenPercent()` is called per-entity per-explosion. With 100 entities in a TNT array, this is the dominant cost. Each optimization eliminates one source of allocation or computation.

**Lines:** ~356 across 5 files
**Risk:** Medium — the ClipContext mutability is non-obvious. Must ensure thread safety with ThreadLocal.

## Dependencies

- `skip_air` (#2) depends on `block_raycast` (#1) — it uses the `skipAirBlocks` field
- `no_air_counting` (#3) depends on both `block_raycast` and `skip_air`
- `entity_raycast` (#4) is standalone
- No external library dependencies

## Implementation Order

1. Block raycast (#1) — the core optimization
2. Skip air (#2) — simple extension of #1
3. No air counting (#3) — simple extension of #2
4. Entity raycast (#4) — independent

## Verification

- Build: `./gradlew build`
- TNT test: Place 100 TNT in a row, ignite, verify identical block destruction pattern
- Creeper test: Spawn creepers near structures, verify identical damage
- Wither test: Spawn wither in enclosed space, verify identical destruction
- Entity exposure test: Place entities at various distances from TNT, verify identical damage distribution
- End test: Place TNT near end portal, verify portal is NOT destroyed (air skip safety check)
- Performance test: Compare MSPT before/after with 50+ simultaneous TNT explosions

## Expected Impact

- **High.** Explosions are one of the most CPU-intensive operations.
- Block raycast cache eliminates ~85% of chunk lookups during explosions.
- Air skipping eliminates processing for ~70% of ray steps.
- Entity raycast eliminates per-entity `Level.clip()` overhead and VoxelShape allocation.
- Combined effect: 2-5x faster explosions depending on scenario.
