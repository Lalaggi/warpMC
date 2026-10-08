# Lithium Patch: AI Optimizations

Porting Lithium's AI brain, sensor, and task optimizations to Purpur.

## Overview

These optimizations reduce CPU waste in the mob AI system. The biggest wins are replacing Stream-based code with traditional iteration (eliminating garbage collection pressure from lambda/iterator/stream objects) and caching behavior entry condition checks.

## Files to Modify

### 1. `purpur-server/minecraft-patches/sources/net/minecraft/world/entity/ai/brain/Brain.java.patch`

**What A — Memory change tracking:** Add a `long memoryModCount` counter to `Brain`. Increment it whenever a memory value is set, cleared, expires, or when memories are registered/cleared. Does NOT call `equals()` — even false-positive increments are acceptable since the counter only ever increases.

**What B — Running task tracking:** Cache the list of possible tasks and currently running tasks. `possibleTasks` — lazily computed, invalidated when activities change or tasks are added/removed. `runningTasks` — tracks which tasks are running via visibility flags (bitset-backed), updated incrementally on task start/stop.

**Why:** Without memory change tracking, every behavior checks `hasRequiredMemories()` on every brain tick by iterating the `entryCondition` map. With dozens of behaviors per entity and hundreds of entities, this is significant waste. Task tracking avoids re-computing which tasks are active every tick.

**Lines:** ~113 (memory tracking) + ~203 (task tracking) + 149 (MaskedList utility)
**Risk:** Medium — the invalidation logic must be correct for all activity/task lifecycle events.

### 2. `purpur-server/minecraft-patches/sources/net/minecraft/world/entity/ai/behavior/Behavior.java.patch`

**What:** Cache `hasRequiredMemories()` result keyed by the brain's `memoryModCount`. On each call, compare brain's current mod count with cached value. If unchanged, return cached result immediately.

**Why:** Avoids iterating `entryCondition` map and calling `brain.checkMemory()` for every behavior on every tick.

**Lines:** ~15
**Risk:** Very Low

### 3. `purpur-server/minecraft-patches/sources/net/minecraft/world/entity/ai/behavior/declarative/memory/MemorySlot.java.patch`

**What:** When setting a null value with a non-MAX_VALUE time-to-live, correctly set the TTL to MAX_VALUE. Prevents unnecessary future tick processing.

**Lines:** ~5
**Risk:** Very Low

### 4. `purpur-server/minecraft-patches/sources/net/minecraft/world/entity/ai/behavior/GateBehavior.java.patch`

**What:** Replace Stream-based `tickOrStop()` and `doStop()` with direct iteration over `ShufflingList` via `WeightedListIterable.cast()`. Replace `tryStart()` dispatch with `RunningPolicyNoStream` implementations.

**Why:** `GateBehavior` is the primary behavior execution mechanism. Stream allocation per tick for every mob is wasteful.

**Lines:** ~90
**Risk:** Low

### 5. `purpur-server/minecraft-patches/sources/net/minecraft/world/entity/ai/behavior/GateBehavior.java.patch` (inner classes)

**What:** Replace `RunningPolicy$1` (RunOne) and `RunningPolicy$2` (TryAll) with non-stream implementations:
- RunOne: `filter().findFirst().tryStart()` → simple for-loop
- TryAll: `filter().forEach(tryStart)` → simple for-loop

**Lines:** ~47
**Risk:** Very Low

### 6. `purpur-server/minecraft-patches/sources/net/minecraft/world/entity/ai/behavior/WeightedList.java.patch` (or new utility)

**What:** Implement `WeightedListIterable` on `ShufflingList` — direct `Iterator<U>` that unwraps `WeightedEntry.getData()` without stream overhead.

**Lines:** ~44 (utility) + ~22 (mixin)
**Risk:** Very Low

### 7. `purpur-server/minecraft-patches/sources/net/minecraft/world/entity/ai/sensing/TemptingSensor.java.patch`

**What:** `@Overwrite` `doTick()` to replace `level.players().stream().filter(...).findFirst()` with a simple for-loop tracking the closest matching player.

**Why:** Stream allocation per tick for every tempting sensor is wasteful.

**Lines:** ~60
**Risk:** Very Low

### 8. `purpur-server/minecraft-patches/sources/net/minecraft/world/entity/ai/sensing/SecondaryPoiSensor.java.patch`

**What:** Skip secondary POI search for non-farmer villagers. Only farmers need secondary POI (composter) lookups.

**Lines:** ~10
**Risk:** Very Low

### 9. `purpur-server/minecraft-patches/sources/net/minecraft/world/entity/ai/village/golem/GolemRandomStrollInVillageGoal.java.patch`

**What:** Use `getEntitiesOfClass` instead of `getEntities` with predicate for golem wander.

**Lines:** ~3
**Risk:** Very Low

### 10. `purpur-server/minecraft-patches/sources/net/minecraft/world/entity/npc/Villager.java.patch`

**What A — Nitwit job search:** Replace useless `AcquirePoi` behavior for nitwit villagers with a sentinel `EMPTY_BEHAVIOR_SENTINEL` that always returns STOPPED.

**What B — Brain mixin:** When `addActivity()` iterates the behavior list, skip `EMPTY_BEHAVIOR_SENTINEL` entries entirely — don't add them to the brain's internal structures.

**Why:** Nitwit villagers have no profession and no job site, but still run `AcquirePoi` every tick, which always fails.

**Lines:** ~44 + ~43 + ~44 (sentinel class)
**Risk:** Very Low

### 11. `purpur-server/minecraft-patches/sources/net/minecraft/world/entity/animal/goat/Goat.java.patch`

**What:** Disable the `NEAREST_ITEMS` sensor for goats — goats never pick up items but their brain registers this sensor. Set `timeToTick` to `Long.MAX_VALUE - (Long.MAX_VALUE % senseInterval)`.

**Lines:** ~10
**Risk:** Very Low

### 12. `purpur-server/minecraft-patches/sources/net/minecraft/world/entity/animal/AgeableMob.java.patch`

**What:** Disable `NEAREST_ADULT` sensor for adult mobs (only needed for babies seeking parents). For villagers, also manage `VILLAGER_BABIES` sensor.

**Lines:** ~20
**Risk:** Very Low

### 13. `purpur-server/minecraft-patches/sources/net/minecraft/world/entity/raid/Raider.java.patch`

**What:** Avoid unnecessary raid bar updates when the raid hasn't changed. Skip expensive leader banner operations when the raider is not the raid leader.

**Lines:** ~15
**Risk:** Low

## Dependencies

- Memory change tracking (#1-3) is a cohesive unit — Brain, Behavior, MemorySlot must be patched together
- Task stream replacement (#4-6) is a cohesive unit — GateBehavior and WeightedList
- Sensor replacements (#7-12) are all standalone
- Raid optimization (#13) is standalone
- No external library dependencies

## Implementation Order

1. Sensor replacements (#7-12) — trivial, standalone
2. Nitwit/unused behavior (#10) — standalone
3. Raid optimization (#13) — standalone
4. Stream replacement (#4-6) — standalone unit
5. Memory change tracking (#1-3) — requires careful testing of behavior lifecycle

## Verification

- Build: `./gradlew build`
- Villager test: Place 50+ villagers in a trading hall, measure MSPT
- Nitwit test: Spawn nitwit villagers, verify they don't waste CPU on job search
- Goat test: Spawn goats, verify they still behave correctly
- Raid test: Start a raid, verify raid bar updates correctly
- Behavior test: Verify all mob behaviors (panic, follow, wander) still function
- Stream test: Use Spark profiler to verify reduced GC pressure from AI

## Expected Impact

- **Medium-high.** The stream replacements eliminate garbage collection pressure from every AI tick on every mob.
- Memory change tracking avoids redundant behavior entry condition checks.
- Nitwit/useless sensor disabling eliminates small but constant waste.
- Combined effect: Measurable MSPT improvement on villager-heavy servers (trading halls).
