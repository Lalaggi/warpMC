# Lithium Patch: Collections & Allocations

Porting Lithium's collection type replacements and allocation reduction patches to Purpur.

## Overview

These are the lowest-risk, highest-value-per-effort optimizations. Each one is a 1-3 file change that swaps a `HashMap`/`HashSet`/`ArrayList` for a fastutil equivalent, or caches a repeated computation to avoid allocation. No behavioral changes. No complex dependencies.

## Files to Modify

### 1. `purpur-server/minecraft-patches/sources/net/minecraft/world/entity/ai/attributes/AttributeMap.java.patch`

**What:** Replace `attributesToUpdate` and `attributesToSync` (`Set<AttributeInstance>`) with fastutil `ReferenceOpenHashSet`.

**Why:** `AttributeInstance` uses identity equality, so `ReferenceOpenHashSet` avoids `equals()`/`hashCode()` overhead. These sets are checked every tick for every living entity.

**Change:** At end of constructor, replace:
```java
this.attributesToUpdate = Sets.newIdentityHashSet();
this.attributesToSync = Sets.newIdentityHashSet();
```
with:
```java
this.attributesToUpdate = new it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<>();
this.attributesToSync = new it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<>();
```

**Lines:** ~4
**Risk:** Very Low

### 2. `purpur-server/minecraft-patches/sources/net/minecraft/world/level/chunk/LevelChunk.java.patch`

**What:** Replace `tickersInLevel` map with fastutil `Object2ObjectOpenHashMap`.

**Why:** Faster `containsKey()` and `get()` for block entity tick tracking.

**Change:** In constructor, replace:
```java
this.tickersInLevel = new HashMap<>();
```
with:
```java
this.tickersInLevel = new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<>();
```

**Lines:** ~2
**Risk:** Very Low

### 3. `purpur-server/minecraft-patches/sources/net/minecraft/world/entity/ai/brain/Brain.java.patch`

**What:** Replace 3 maps with fastutil equivalents:
- `memories`: `HashMap` → `Reference2ObjectOpenHashMap` (reference equality for `MemoryModuleType` enums)
- `sensors`: `HashMap` → `Reference2ReferenceLinkedOpenHashMap` (reference equality + preserves insertion order)
- `activityRequirements`: `HashMap` → `Object2ObjectOpenHashMap`

**Why:** `MemoryModuleType` and `Sensor` are enum/singleton types where reference equality is faster than `equals()`.

**Change:** At end of constructor, reassign the three fields.

**Lines:** ~6
**Risk:** Very Low

### 4. `purpur-server/minecraft-patches/sources/net/minecraft/world/level/chunk/LevelChunk.java.patch` (additional)

**What:** Replace `EntityTickList` backing map with fastutil clone.

**Why:** When splitting the tick list into active/passive during iteration, vanilla clones via iteration. fastutil `clone()` is faster.

**Change:** In `EntityTickList`, cast `allEntities` to `Int2ObjectLinkedOpenHashMap` and call `clone()`.

**Lines:** ~5
**Risk:** Very Low

### 5. `purpur-server/minecraft-patches/sources/net/minecraft/world/level/NaturalSpawner.java.patch`

**What:** Replace `MobSpawnSettings.spawners` map with `EnumMap<MobCategory, ...>`. Replace `WeightedList.items` with `HashedReferenceList` (for lists > 4 items) or `ReferenceArrayList` (for lists ≤ 4 items).

**Why:** `EnumMap` provides O(1) lookup with smaller memory footprint. `HashedReferenceList` provides O(1) `contains()` for mob spawn weight checks.

**Change:** Constructor injection to swap the map and list types.

**Lines:** ~8 + 277-line utility class
**Risk:** Low

### 6. `purpur-server/minecraft-patches/sources/net/minecraft/nbt/CompoundTag.java.patch`

**What:** Replace `HashMap<String, Tag>` with `Object2ObjectOpenHashMap<String, Tag>` in all constructors and `copy()`.

**Why:** `CompoundTag` is ubiquitous (every chunk, entity, tile entity). fastutil has lower memory overhead and faster iteration.

**Change:** Replace map constructors. Also remove wasted intermediate `HashMap` allocation in default constructor.

**Lines:** ~6
**Risk:** Very Low

### 7. `purpur-server/minecraft-patches/sources/net/minecraft/server/level/ServerLevel.java.patch`

**What:** Replace `getBlockRandomPos()` with inlined LCG random + cached `MutableBlockPos`.

**Why:** Vanilla allocates a new `BlockPos` every random tick probe. With hundreds of chunks and randomTickSpeed of 3, that's thousands of allocations per tick.

**Change:** Add a `randomPosInChunkCachedPos` field. Rewrite `getBlockRandomPos()` to write into it and return `pos.immutable()`. Inline the LCG: `randValue * 3 + 1013904223`.

**Lines:** ~15
**Risk:** Very Low

### 8. `purpur-server/minecraft-patches/sources/net/minecraft/world/level/chunk/LevelChunkSection.java.patch`

**What:** Bypass filtering iterator in entity section iteration.

**Why:** `ClassInstanceMultiMap.iterator()` creates a filtering `Iterator`. Direct access to `allInstances` list bypasses this.

**Change:** In `getEntities()` methods, redirect to iterate `allInstances` directly when the type matches.

**Lines:** ~6
**Risk:** Very Low

### 9. `purpur-server/minecraft-patches/sources/net/minecraft/core/Direction.java.patch`

**What:** Cache `Direction.values()` as a static final `Direction[]` constant (`ALL`). Redirect all `Direction.values()` calls in redstone wire, piston structure resolver, and piston base to the cached array.

**Why:** `Enum.values()` creates a defensive copy array on every call.

**Lines:** ~8
**Risk:** Very Low

### 10. `purpur-server/minecraft-patches/sources/net/minecraft/world/level/material/FlowingFluid.java.patch` (or explosion-related)

**What:** Inline `getBlockExplosionResistance` to remove lambda + Optional allocation per block in explosion radius.

**Why:** Explosions iterate thousands of blocks. Each block check avoids one lambda + one Optional allocation.

**Lines:** ~10
**Risk:** Low

### 11. `purpur-server/minecraft-patches/sources/net/minecraft/server/level/ChunkMap.java.patch`

**What:** Replace `Sets.newIdentityHashSet()` with `ReferenceOpenHashSet` for entity tracker player sets.

**Why:** fastutil uses less memory and caches its iterator.

**Lines:** ~2
**Risk:** Very Low

### 12. `purpur-server/minecraft-patches/sources/net/minecraft/world/entity/animal/comparator/ComposterBlock.java.patch`

**What:** Return static `EMPTY`/`ZERO` arrays instead of allocating new `int[]` on every `getSlotsForFace()` call.

**Why:** Reduces GC pressure in farms with many composters.

**Lines:** ~6
**Risk:** Very Low

### 13. `purpur-server/minecraft-patches/sources/net/minecraft/world/entity/Entity.java.patch`

**What:** Replace `getIndirectPassengers()` Stream-based implementation with recursive `addPassengersDeep(ArrayList)`.

**Why:** Vanilla uses `Stream.flatMap()` chains creating multiple intermediate objects. Direct ArrayList collection avoids all Stream allocation.

**Lines:** ~15
**Risk:** Low-Medium

## Dependencies

- **fastutil** is already a transitive dependency of Minecraft and Paper. No new libraries needed.
- No inter-dependencies between these patches. Each is standalone.

## Implementation Order

1. Collections (#1-6) — mechanical swaps
2. Allocation reductions (#7-12) — per-file rewrites
3. Entity passengers (#13) — requires testing with nested minecarts/boats

## Verification

- Build: `./gradlew build`
- Load test: Start server with ~100 entities, run for 10 minutes
- No gameplay changes expected — all optimizations are transparent

## Expected Impact

- Measurable GC pressure reduction across the server
- Small but measurable MSPT improvement on entity-heavy servers
- Improved cache locality from fastutil's compact storage
