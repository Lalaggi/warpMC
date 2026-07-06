# FerriteCore Port to Purpur

Porting FerriteCore memory optimizations as Purpur patches. Three of four server-relevant optimizations (ThreadingDetector is excluded due to high risk).

## Overview

FerriteCore replaces Minecraft's per-state data structures with compact alternatives, saving ~1 GB of heap memory. Paper has **zero** overlap with any of these optimizations.

| Optimization | Memory Saved | Files Changed |
|---|---|---|
| FastMap neighbor lookup + property storage | ~770 MB | ~12 |
| BlockState cache deduplication | ~200 MB | ~15 |
| PatchedDataComponentMap empty map | Small | 1 |

## Optimization 1: FastMap + Property Storage (~770 MB)

### What It Does

Vanilla stores block state neighbors as a `Table<Property<?>, Comparable<?>, S>` per `StateHolder` (per blockstate). With many properties and values, this table costs ~600 MB in modded and ~100 MB in vanilla. FerriteCore replaces this with a single `FastMap<S>` per block (not per blockstate) — a flat `ArrayList` indexed by integer math. Neighbor states are found via bitmask operations instead of hash table lookups.

Additionally, each `StateHolder` stores a `Reference2ObjectArrayMap<Property<?>, Comparable<?>>` (`values` field) with ~170 MB overhead. When FastMap is enabled, this is nulled out and values are reconstructed from the FastMap index.

### Files to Create

All new files go in `purpur-server/src/main/java/malte0811/ferritecore/`:

#### 1. `fastmap/FastMapKey.java` — Indexing strategy interface

```java
public interface FastMapKey {
    int replaceIn(int mapIndex, int valueIndex);
    int toPartialMapIndex(int valueIndex);
    int getFactorToNext();
    int getIndexIn(int mapIndex);
}
```

**Lines:** 28

#### 2. `fastmap/BinaryFastMapKey.java` — Bitmask-based indexing (default)

A record implementing `FastMapKey`. Uses bitmask operations for O(1) neighbor lookup. Each property's value count is rounded up to the next power of two, wasting some space in the value matrix but eliminating integer division.

**Key method:**
```java
public int replaceIn(int mapIndex, int valueIndex) {
    if (valueIndex >= numValues) return -1;
    final int keepMask = ~lowestNBits(firstBitAfterValue) | lowestNBits(firstBitInValue);
    return (keepMask & mapIndex) | toPartialMapIndex(valueIndex);
}
```

**Lines:** 57

#### 3. `fastmap/CompactFastMapKey.java` — Modulo-based indexing (opt-in)

A record implementing `FastMapKey`. Uses integer division/modulo. Fully packed, no wasted space, but slower.

**Lines:** 32

#### 4. `fastmap/FastMap.java` — Core data structure

Stores `Property<?>[] properties`, `List<FastMapKey> keys`, and `List<Value> valueMatrix`. The `with(oldIndex, propertyIndex, valueIndex)` method computes the new flat index via `keys.get(propertyIndex).replaceIn(oldIndex, valueIndex)` and returns `valueMatrix.get(newIndex)`.

**Lines:** 97

#### 5. `ducks/FastMapStateHolder.java` — Duck interface for StateHolder

```java
public interface FastMapStateHolder<S> {
    void ferritecore_setStateMap(FastMap<S> stateMap, int tableIndex);
}
```

**Lines:** 7

#### 6. `impl/FastMapStateHolderImpl.java` — Initialization logic

`initializeFastMap(Collection<S> states)`: Creates a `FastMap` from the first state's properties, inserts all states, and calls `ferritecore_setStateMap()` on each.

`getPropertyValue(vanillaValues, fastMap, fastMapIndex, propertyIndex)`: If `vanillaValues != null` (during init), returns from vanilla array. Otherwise reconstructs from FastMap index.

**Lines:** 40

### Files to Patch

#### 7. `StateHolder.java.patch`

Target: `net.minecraft.world.level.block.state.StateHolder`

**Changes:**
- Add `@Unique` fields: `int ferritecore_globalTableIndex`, `FastMap<S> ferritecore_globalTable`
- Implement `FastMapStateHolder<S>` interface
- `@Overwrite` `setValueInternal(Property<T>, int, V)` — use `ferritecore_globalTable.with(index, propertyIndex, valueIndex)` instead of array lookup
- `@Overwrite` `initializeNeighbors(S[][])` — no-op (throws for non-singleton states)
- `@Redirect` on `propertyValues` field access in `getNullableValue` and `lambda$getValues$0` — delegate to `FastMapStateHolderImpl.getPropertyValue()`
- `ferritecore_setStateMap()`: Store the FastMap and index. If property storage optimization enabled, null out `propertyValues`.

**Lines:** ~80

#### 8. `StateDefinition.java.patch`

Target: `net.minecraft.world.level.block.state.StateDefinition`

**Changes:**
- `@Redirect` on `Map.forEach()` in `createMultiPropertyStates` — call `FastMapStateHolderImpl.initializeFastMap(states.values())` instead
- `@Redirect` on `StateHolder.initializeNeighbors()` in `createSinglePropertyStates` — no-op
- `@WrapOperation` on `ImmutableList.Builder.build()` in `createSinglePropertyStates` — call `initializeFastMap()` after building the list

**Lines:** ~46

### Access Transformers Needed

```
public net.minecraft.world.level.block.state.StateHolder propertyKeys
```

(The `propertyKeys` field is accessed via mixin accessor `StateHolderAccess` in FerriteCore, but for Purpur source patches we can use an AT instead.)

### Dependencies

- MixinExtras `@WrapOperation` (Paper already has this)
- No external libraries beyond what Paper ships

---

## Optimization 2: BlockState Cache Deduplication (~200 MB)

### What It Does

When `BlockStateBase.initCache()` runs, it computes collision shapes and `faceSturdy` boolean arrays for each blockstate. Many blockstates produce identical shapes (e.g., all stair states share the same few shapes). FerriteCore deduplicates these globally — finding canonical instances and replacing the internals of duplicates to share backing arrays.

The `replaceInternals()` trick is critical: it copies the internal `DoubleList` points and `DiscreteVoxelShape` from the canonical instance into the duplicate, so even code holding a reference to the old object now sees the shared data.

### Files to Create

All new files in `purpur-server/src/main/java/malte0811/ferritecore/`:

#### 9. `ducks/BlockStateCacheAccess.java` — Duck interface for Cache inner class

```java
public interface BlockStateCacheAccess {
    VoxelShape getCollisionShape();
    void setCollisionShape(VoxelShape newShape);
    boolean[] getFaceSturdy();
    void setFaceSturdy(boolean[] newFaceSturdyArray);
}
```

**Lines:** 17

#### 10. `hash/VoxelShapeHash.java` — Top-level shape equality

Dispatches to `ArrayVoxelShapeHash`, `SliceShapeHash`, or `CubeVoxelShape` based on type. Handles `equals()` and `hashCode()` for `VoxelShape` instances.

**Lines:** 56

#### 11. `hash/ArrayVoxelShapeHash.java` — ArrayVoxelShape equality

Compares `xs`, `ys`, `zs` `DoubleList` points and the `DiscreteVoxelShape` via `DiscreteVSHash`.

**Lines:** 29

#### 12. `hash/DiscreteVSHash.java` — DiscreteVoxelShape equality

Handles `BitSetDiscreteVoxelShape` (compares `storage` BitSet + min/max bounds) and `SubShape` (compares parent recursively + start/end bounds).

**Lines:** 84

#### 13. `hash/SliceShapeHash.java` — SliceShape equality

Compares `axis`, `delegate` (via `VoxelShapeHash`), and `shape` (via `DiscreteVSHash`).

**Lines:** 30

#### 14. `impl/BlockStateCacheImpl.java` — Core dedup logic

Two global dedup maps:
```java
public static final Map<ArrayVSAccess, ArrayVSAccess> CACHE_COLLIDE = new Object2ObjectOpenCustomHashMap<>(ArrayVoxelShapeHash.INSTANCE);
public static final Map<boolean[], boolean[]> CACHE_FACE_STURDY = new Object2ObjectOpenCustomHashMap<>(BooleanArrays.HASH_STRATEGY);
```

Uses `MethodHandles` to access the private `cache` field on `BlockStateBase`.

`deduplicateCachePre(state)`: Saves old cache to `ThreadLocal<BlockStateCacheAccess>`.
`deduplicateCachePost(state)`: After vanilla populates the cache, deduplicates collision shapes and face-sturdy arrays. For shapes: checks if old cache had equivalent shape (fast path), otherwise looks up in global map via `computeIfAbsent`. Calls `replaceInternals()` to share backing arrays.

**Lines:** 138

### Files to Patch

#### 15. `BlockBehaviour.java.patch`

Target: `net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase`

**Changes:**
- `@Inject` at HEAD of `initCache()` — call `BlockStateCacheImpl.deduplicateCachePre(asState())`
- `@Inject` at TAIL of `initCache()` — call `BlockStateCacheImpl.deduplicateCachePost(asState())`

**Lines:** ~26

### Access Transformers Needed

```
public net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase$Cache
```

The `Cache` inner class fields (`collisionShape`, `faceSturdy`) are private. FerriteCore uses MethodHandles to access them. For Purpur, we could alternatively add ATs:

```
public net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase cache
```

### Accessor Mixins Needed

For the hash strategies, we need access to VoxelShape internals. These are implemented as `@Mixin` accessor interfaces in FerriteCore. For Purpur, convert them to ATs or keep as mixin accessors:

| Accessor | Target | Fields |
|---|---|---|
| `ArrayVSAccess` | `ArrayVoxelShape` | `xs`, `ys`, `zs` (get+set) |
| `VoxelShapeAccess` | `VoxelShape` | `shape`, `faces` (get+set) |
| `BitSetDVSAccess` | `BitSetDiscreteVoxelShape` | `storage`, `xMin/yMin/zMin/xMax/yMax/zMax` |
| `SubShapeAccess` | `SubShape` | `parent`, `startX/Y/Z`, `endX/Y/Z` |
| `SliceShapeAccess` | `SliceShape` | `delegate`, `axis` |
| `DiscreteVSAccess` | `DiscreteVoxelShape` | `xSize`, `ySize`, `zSize` |

### Dependencies

- fastutil `Object2ObjectOpenCustomHashMap`, `BooleanArrays.HASH_STRATEGY` (Paper ships this)
- MethodHandles (standard Java)
- No external libraries beyond what Paper ships

---

## Optimization 3: PatchedDataComponentMap Empty Map (Small)

### What It Does

After `applyPatch()`, `restorePatch()`, `set()`, or `remove()` on `PatchedDataComponentMap`, if the `patch` map is empty, replaces it with a shared singleton empty map (`Reference2ObjectMaps.emptyMap()`). Sets `copyOnWrite = true` to prevent mutation of the singleton.

### Files to Create

None (just the mixin).

### Files to Patch

#### 16. `PatchedDataComponentMap.java.patch`

Target: `net.minecraft.core.component.PatchedDataComponentMap`

**Changes:**
- `@Inject` at RETURN of `applyPatch(DataComponentPatch)` and `restorePatch` — call `saveMemoryIfEmpty()`
- `@Inject` at RETURN of `set` and `remove` — call `saveMemoryIfEmpty()`
- The `saveMemoryIfEmpty()` method: if `patch.isEmpty()`, set `this.patch = Reference2ObjectMaps.emptyMap()` and `this.copyOnWrite = true`

**Lines:** ~43

### Dependencies

- fastutil `Reference2ObjectMaps` (Paper ships this)

---

## Complete File Manifest

### New Files (~22 files)

| # | File | Lines | Purpose |
|---|---|---|---|
| 1 | `fastmap/FastMapKey.java` | 28 | Indexing interface |
| 2 | `fastmap/BinaryFastMapKey.java` | 57 | Bitmask indexing |
| 3 | `fastmap/CompactFastMapKey.java` | 32 | Modulo indexing |
| 4 | `fastmap/FastMap.java` | 97 | Core data structure |
| 5 | `ducks/FastMapStateHolder.java` | 7 | Duck interface |
| 6 | `impl/FastMapStateHolderImpl.java` | 40 | Init logic |
| 7 | `ducks/BlockStateCacheAccess.java` | 17 | Cache duck interface |
| 8 | `hash/VoxelShapeHash.java` | 56 | Shape equality |
| 9 | `hash/ArrayVoxelShapeHash.java` | 29 | ArrayVoxelShape equality |
| 10 | `hash/DiscreteVSHash.java` | 84 | DiscreteVoxelShape equality |
| 11 | `hash/SliceShapeHash.java` | 30 | SliceShape equality |
| 12 | `impl/BlockStateCacheImpl.java` | 138 | Core dedup logic |
| **Total** | | **~615** | |

### Patched Files (~3 vanilla classes)

| # | File | Lines Changed | Mixin Types |
|---|---|---|---|
| 13 | `StateHolder.java.patch` | ~80 | @Overwrite, @Redirect, @Unique |
| 14 | `StateDefinition.java.patch` | ~46 | @Redirect, @WrapOperation |
| 15 | `BlockBehaviour.java.patch` | ~26 | @Inject |
| 16 | `PatchedDataComponentMap.java.patch` | ~43 | @Inject |

### Access Transformers (in `purpur.at`)

```
public net.minecraft.world.level.block.state.StateHolder propertyKeys
public net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase cache
```

### Accessor Mixins (6 interfaces)

These expose private fields on VoxelShape internals for the hash strategies:

| Accessor | Target | Fields Exposed |
|---|---|---|
| `ArrayVSAccess` | `ArrayVoxelShape` | xs, ys, zs |
| `VoxelShapeAccess` | `VoxelShape` | shape, faces |
| `BitSetDVSAccess` | `BitSetDiscreteVoxelShape` | storage, xMin/yMin/zMin/xMax/yMax/zMax |
| `SubShapeAccess` | `SubShape` | parent, startX/Y/Z, endX/Y/Z |
| `SliceShapeAccess` | `SliceShape` | delegate, axis |
| `DiscreteVSAccess` | `DiscreteVoxelShape` | xSize, ySize, zSize |

**Alternative:** Convert these to ATs instead of mixin accessors for consistency with Purpur's style:
```
public net.minecraft.world.phys.shapes.ArrayVoxelShape xs
public net.minecraft.world.phys.shapes.ArrayVoxelShape ys
public net.minecraft.world.phys.shapes.ArrayVoxelShape zs
public net.minecraft.world.phys.shapes.VoxelShape shape
public net.minecraft.world.phys.shapes.VoxelShape faces
public net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape storage
public net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape xMin
public net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape yMin
public net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape zMin
public net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape xMax
public net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape yMax
public net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape zMax
public net.minecraft.world.phys.shapes.SubShape parent
public net.minecraft.world.phys.shapes.SubShape startX
public net.minecraft.world.phys.shapes.SubShape startY
public net.minecraft.world.phys.shapes.SubShape startZ
public net.minecraft.world.phys.shapes.SubShape endX
public net.minecraft.world.phys.shapes.SubShape endY
public net.minecraft.world.phys.shapes.SubShape endZ
public net.minecraft.world.phys.shapes.SliceShape delegate
public net.minecraft.world.phys.shapes.SliceShape axis
public net.minecraft.world.phys.shapes.DiscreteVoxelShape xSize
public net.minecraft.world.phys.shapes.DiscreteVoxelShape ySize
public net.minecraft.world.phys.shapes.DiscreteVoxelShape zSize
```

---

## Implementation Steps

### Phase 1: Access Transformers
1. Add all ATs to `build-data/purpur.at`
2. Run `./gradlew applyAllPatches` to regenerate sources with accessible fields

### Phase 2: FastMap (Optimization 1)
1. Create the 6 new source files (`fastmap/`, `ducks/`, `impl/`)
2. Patch `StateHolder.java` with the mixin logic
3. Patch `StateDefinition.java` with the initialization hooks
4. Build and verify: `./gradlew build`

### Phase 3: BlockState Cache Dedup (Optimization 2)
1. Create the 6 hash strategy + dedup files
2. Create the `BlockStateCacheAccess` duck interface
3. Patch `BlockBehaviour.java` with the `initCache()` hooks
4. Build and verify: `./gradlew build`

### Phase 4: PatchedDataComponentMap (Optimization 3)
1. Patch `PatchedDataComponentMap.java` with the empty map optimization
2. Build and verify: `./gradlew build`

---

## Risk Assessment

| Optimization | Risk | Mitigation |
|---|---|---|
| FastMap | Low — same data, different structure | Verify neighbor state access produces identical results for every blockstate |
| Cache dedup | Low — dedup is transparent | Verify `getCollisionShape()` returns equivalent shapes; test all blocks |
| DataComponent | Very Low — singleton empty map is safe | Copy-on-write prevents mutation; trivial change |

## Expected Impact

- **~1 GB heap memory reduction** on a typical server with all blocks loaded
- **Improved GC performance** — fewer objects to trace, less GC pause time
- **Better cache locality** — FastMap's flat array has better spatial locality than hash tables
- No gameplay changes — all optimizations are transparent memory savings

## Verification

- Build: `./gradlew build`
- Load test: Start server, generate/load chunks, compare heap usage before/after with `jstat -gc`
- Shape test: For every `BlockState`, compare `getCollisionShape()` output — must be equivalent
- Neighbor test: For every blockstate property/value pair, verify `setValue()` returns the correct neighbor state
- Mod compatibility: Test with popular mods that add blocks (if applicable)
