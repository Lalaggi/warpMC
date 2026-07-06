# Lithium Patch: Entity Optimizations

Porting Lithium's entity-related optimizations to Purpur — inactive navigations, entity type predicates, sprinting particles, and entity iteration.

## Overview

These optimizations reduce unnecessary work in entity processing. The biggest win is `inactive_navigations` — block updates currently notify ALL mobs to recompute paths, even those not pathfinding. With thousands of mobs, this is a massive waste.

## Files to Modify

### 1. `purpur-server/minecraft-patches/sources/net/minecraft/server/level/ServerLevel.java.patch`

**What A — Inactive navigations:** Replace `navigatingMobs` (a list of ALL mobs) with a `ReferenceOpenHashSet<PathNavigation>` of only *active* navigations (those with a current path). When `sendBlockUpdated()` fires, only check active navigations via `shouldRecomputePath(pos)`.

**What B — Registration tracking:** When a mob starts pathfinding, register its `PathNavigation` in the set. When the path is cleared, unregister.

**Why:** With thousands of mobs (mob farms, animal pens), vanilla iterates all of them on every block update. Most mobs are not pathfinding at any given moment. This transforms O(n) block update notifications into O(activePathfinders).

**Lines:** ~40 + 6 supporting files (MobMixin, PathNavigationMixin, LivingEntityMixin, etc.)
**Risk:** Medium — must ensure pathfinding mobs always get recompute notifications. Edge case: mob starts pathfinding between registration check and block update.

### 2. `purpur-server/minecraft-patches/sources/net/minecraft/world/entity/Mob.java.patch`

**What:** Track which navigation is registered. On `startNavigation()` / `stopNavigation()`, update the set in `ServerLevel`.

**Lines:** ~15
**Risk:** Low

### 3. `purpur-server/minecraft-patches/sources/net/minecraft/world/level/pathfinder/PathNavigation.java.patch`

**What:** On path cleared / path found, update registration in the `ServerLevel` set.

**Lines:** ~10
**Risk:** Low

### 4. `purpur-server/minecraft-patches/sources/net/minecraft/world/entity/LivingEntity.java.patch`

**What:** On vehicle mount/dismount, update navigation registration (ridden mobs may change pathfinding state).

**Lines:** ~8
**Risk:** Low

### 5. `purpur-server/minecraft-patches/sources/net/minecraft/world/entity/Entity.java.patch`

**What A — Replace entity type predicates:** Replace `world.getEntities(Entity, AABB, Predicate)` calls with `world.getEntitiesOfClass(Class, AABB, Predicate)` where the predicate is a simple type check. Applied to minecart pushing, llama caravan following, golem village wander, armor stand pushing.

**What B — Skip sprinting particles:** Skip creating sprinting particles for all entities on the server side (only the client needs these).

**Why:** `getEntities()` returns ALL entities then filters with a predicate. `getEntitiesOfClass()` uses type-based spatial indexing to skip non-matching entities. Sprinting particles are purely visual.

**Lines:** ~20 across 4 files
**Risk:** Very Low

### 6. `purpur-server/minecraft-patches/sources/net/minecraft/world/entity/item/ItemEntity.java.patch`

**What:** Skip sprinting particles for item entities.

**Lines:** ~3
**Risk:** Very Low

### 7. `purpur-server/minecraft-patches/sources/net/minecraft/world/entity/decoration/ArmorStand.java.patch`

**What:** Use `getEntitiesOfClass(ArmorStand.class, ...)` instead of `getEntities(ArmorStand::isPushable, ...)`.

**Lines:** ~3
**Risk:** Very Low

### 8. `purpur-server/minecraft-patches/sources/net/minecraft/world/entity/animal/llama/Llama.java.patch`

**What:** Use `getEntitiesOfClass(Llama.class, ...)` for caravan following.

**Lines:** ~3
**Risk:** Very Low

### 9. `purpur-server/minecraft-patches/sources/net/minecraft/world/entity/npc/Golem.java.patch`

**What:** Use `getEntitiesOfClass` for village wander entity queries.

**Lines:** ~3
**Risk:** Very Low

## Dependencies

- `inactive_navigations` requires `ServerLevel`, `Mob`, `PathNavigation`, and `LivingEntity` patches together as a unit
- Entity type predicate replacements (#5-9) are all standalone
- No external library dependencies

## Implementation Order

1. Entity type predicate replacements (#5-9) — trivial, low-risk
2. Inactive navigations (#1-4) — requires all 4 files to be patched together

## Verification

- Build: `./gradlew build`
- Mob farm test: Place 500+ mobs in a farm, place/break blocks nearby, measure MSPT
- Pathfinding test: Ensure mobs still path correctly after block updates
- Llama caravan test: Verify caravans still follow correctly
- Minecart test: Verify minecart pushing still works
- Armor stand test: Verify armor stands can still be pushed

## Expected Impact

- `inactive_navigations`: **Very high** for mob-heavy servers. With 1000 mobs and only 10 pathfinding, this eliminates 99% of block update notifications.
- Entity type predicates: **Low-medium** — eliminates unnecessary entity iteration in specific AI paths.
- Sprinting particles: **Negligible** — pure micro-optimization.
