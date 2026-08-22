# WarpMC

> High-performance fork of **Purpur** for Minecraft **26.1.2** (Tiny Takeover) — rebranded, optimized, and extended with server-side Fake Players.

WarpMC is a drop-in replacement for Purpur/Paper focused on **worldgen speed**, **memory efficiency**, and **AFK/bot gameplay**. It keeps full Purpur plugin compatibility (same `org.purpurmc.purpur` package, same `purpur.yml`) while adding targeted ports from the **InspirationForPerformancePatches** collection and a native FakePlayer API.

**Current branch:** `warp/26.1.2` (MC 26.1.2, Paper commit `70eaed6`, Java 25) — see [Releases](https://gitlab.com/Lalaggi/warpMC/-/releases) for ready-to-run jars.

---

## Why WarpMC over plain Purpur?

| Area | Purpur 26.1.2 | WarpMC 26.1.2 |
|------|---------------|---------------|
| **Branding** | `Purpur` / `purpurmc:purpur` | `WarpMC` / `warpmc:warpmc` (adventure key `warpmc:warpmc`) |
| **Worldgen** | vanilla `PalettedContainer.set()` | **noisium** — direct palette writes in `NoiseBasedChunkGenerator.doFill()`, 4 access transformers, cached counters |
| **Memory** | vanilla `BlockState` tables | **FerriteCore** — FastMap neighbor lookup (~770 MB saved), cache dedup (~200 MB), `PatchedDataComponentMap` empty-map fix |
| **Allocations** | HashMap-heavy | **Lithium** Collections — `Brain`, `LevelChunk.tickersInLevel`, `CompoundTag`, `ComposterBlock` → fastutil `Object2ObjectOpenHashMap` |
| **Bots / AFK** | not built-in | **FakePlayer API** — full `Player` instances with no client, timeout, damage toggle, hidden flag |

Internals intentionally stay on `org.purpurmc.purpur` / `org.purpurmc.purpur` group and `purpur.yml` so existing plugins keep working.

---

## Features

### Performance Patches (from `InspirationForPerformancePatches/`)

- **noisium (forked)** — `purpur.at` exposes `LevelChunkSection.states`, `nonEmptyBlockCount`, `tickingFluidCount`, `tickingBlockCount`; `NoiseBasedChunkGenerator` bypasses lock overhead with `getAndSetUnchecked()` + manual counter increments. Safe under Paper's `acquire()`/`release()` chunk section bracketing.
- **FerriteCore** — `FastMap` replaces `Table<Property,Comparable,BlockState>` per-state hash tables with a flat `int[]` index; per-state `values` map nulled and reconstructed; `BlockState` cache dedup for collision shapes + `faceSturdy`; `PatchedDataComponentMap` avoids copying for common empty cases.
- **Lithium** (segment 01 + partial) — `LevelChunk`, `Brain` memories/sensors, `CompoundTag.copy()`, `ComposterBlock` empty slots, `GolemRandomStroll`, `LlamaFollowCaravan`, `AttributeMap`.

Excluded for now (documented in `plan.md`): Alternate Current (already in Paper), VMP (Moonrise covers it, lock removal unsafe), hopper/block-entity sleeping (too invasive).

### FakePlayer API (`FAKE_PLAYER_API.md`)

Server-side bots that are real `Player` objects — no client, no `EmbeddedChannel` traffic, fully ticked by the server.

```java
FakePlayerFactory factory = Purpur.getFakePlayerFactory();
if (factory == null) return;

FakePlayer bot = factory.createBuilder(player)
    .world(player.getWorld())
    .location(player.getLocation())
    .timeoutMinutes(30)
    .reason("AFK")
    .damageDisabled(true)
    .hidden(false)
    .resetHealthOnDeath(true)
    .spawn();

// later
factory.removeFakePlayer(player.getUniqueId());
Collection<FakePlayer> all = factory.getActiveFakePlayers();
```

Highlights:
- `FakeServerPlayer` extends `ServerPlayer`, `FakeConnection`/`FakePacketListener` are no-ops, `CraftFakePlayer` bridges to Bukkit.
- Fires `PlayerJoinEvent`/`PlayerQuitEvent`/`PlayerDeathEvent` normally, appears in tab list (unless `hidden`), can trigger redstone/pressure plates, be targeted by mobs, chunk-loaded, teleported.
- Timeout checked each `MinecraftServer.tickServer()` tick; real login with same UUID evicts the fake first (`ServerLoginPacketListenerImpl`); death respawns a new `FakeServerPlayer` preserving config; `PistonMovingBlockEntity` allows slime bounce.

See `FAKE_PLAYER_API.md` for full builder options, `FakePlayer` interface, and guard/AFK examples.

### Rebrand

- All player-facing strings: `CrashReport`, `WatchdogThread`, `CraftScheduler`, `PaperConsole`, `Metrics`, `ServerBuildInfoImpl` → WarpMC.
- Manifest `Implementation-Title`/`Specification-*` → `WarpMC`; `Brand-Id` `warpmc:warpmc`, `Brand-Name` `WarpMC`.
- Error footers point to `https://github.com/lalaggi/warpMC` (and now `https://gitlab.com/Lalaggi/warpMC`).
- Logo patch (`purpur-server/paper-patches/features/0001-Rebrand.patch`) still Purpur artwork — placeholder for WarpMC logo.

---

## Project Layout

```
purpur-api/paper-patches/          # API rebrand + FakePlayer interfaces
purpur-api/src/main/java/org/purpurmc/purpur/player/  # FakePlayer, FakePlayerBuilder, FakePlayerFactory
purpur-server/paper-patches/features/0001-Rebrand.patch  # main rebrand
purpur-server/paper-patches/features/0006-...CraftEntity  # FakePlayer craft bridge
purpur-server/minecraft-patches/sources/  # NMS patches (FakeServerPlayer, FakeConnection, Packet stuff, noisium, etc.)
purpur-server/src/main/java/org/purpurmc/purpur/player/FakeServerPlayerManager.java
build-data/purpur.at               # access transformers (LevelChunkSection fields)
InspirationForPerformancePatches/  # upstream reference copies (FerriteCore, lithium, noisium-forked)
plan.md / ferritecore-patch.md / noisium-patch.md / lithium-patches/
```

Patch workflow (paperweight):
```bash
./gradlew applyAllPatches          # apply all patches from scratch (root project)
# edit source under purpur-server/src/minecraft/java/net/minecraft/...
./gradlew :purpur-server:fixupMinecraftSourcePatches
./gradlew :purpur-server:rebuildMinecraftSourcePatches
./gradlew :purpur-server:applyMinecraftPatches  # verify
```

---

## Building

**Requirements:** Java 25, Git (clone, don't download zip).

```bash
git clone https://gitlab.com/Lalaggi/warpMC.git
cd warpMC
git checkout warp/26.1.2
./gradlew applyAllPatches          # first time: decompile + apply

# standard jars (not runnable)
./gradlew build
# -> purpur-api/build/libs/purpur-api-*.jar
# -> purpur-server/build/libs/purpur-server-*.jar

# runnable server jar (Mojmap bundler / paperclip)
./gradlew createMojmapBundlerJar
# -> purpur-server/build/libs/purpur-bundler-*.jar  (~95 MB)
# -> purpur-server/build/libs/purpur-paperclip-*.jar (~58 MB)

# install to local maven
./gradlew publishToMavenLocal
```

The jars under `purpur-server/build/libs/` **are** the server — run with `java -jar purpur-bundler-*.jar` or `purpur-paperclip-*.jar`.

---

## Downloads

Prebuilt jars are attached to **GitLab Releases** (this repo → Releases). The `latest` tag tracks the tip of `warp/26.1.2`:

- `purpur-bundler-26.1.2.local-SNAPSHOT.jar` — recommended server jar
- `purpur-paperclip-26.1.2.local-SNAPSHOT.jar`
- `purpur-server-26.1.2.local-SNAPSHOT.jar` + `purpur-api-*.jar`

If you previously used `purpurmc.org/downloads`, switch to the GitLab Releases page — WarpMC does not publish to the Purpur API.

---

## API / Plugin Usage

`purpur-api` is the same Maven coordinates as Purpur (compatibility):

```xml
<repository>
  <id>purpur</id>
  <url>https://repo.purpurmc.org/snapshots</url>
</repository>
<dependency>
  <groupId>org.purpurmc.purpur</groupId>
  <artifactId>purpur-api</artifactId>
  <version>[26.1.2.build,)</version>
  <scope>provided</scope>
</dependency>
```

For local WarpMC builds after `publishToMavenLocal`, resolve from `mavenLocal()`.

FakePlayer detection in listeners:

```java
@EventHandler
public void onDamage(EntityDamageEvent e) {
    if (e.getEntity() instanceof FakePlayer fp && fp.isDamageDisabled())
        e.setCancelled(true);
}
```

---

## WarpAFK — Unplugged AFK for WarpMC

Standalone port of [sakura-ryoko/unplugged-afk](https://github.com/sakura-ryoko/unplugged-afk) (Fabric, LGPL-3.0) as a Paper plugin in [`warp-afk/`](./warp-afk) (gitignored, standalone `main` repo).

- `/unplug [minutes] [reason]` / `/afk` — spawn a same-UUID bot via `FakePlayerFactory`, kick yourself, bot stays ticked, auto-expires, hides from tab if configured
- `/unplugged-admin spawn|kick|info|list|save|reload|purge|set` — parity with upstream admin commands
- Persists to `plugins/WarpAFK/unplugged.json`, restores on restart (remaining-time adjusted for downtime)
- Honors `unplugged.disable-damage`, `hide-player`, `reset-health-on-death` (FakePlayer respawns with full health/food instead of staying dead)

Build after `publishToMavenLocal` from the server:

```bash
./gradlew publishToMavenLocal
cd warp-afk && ./gradlew build  # -> build/libs/warp-afk-0.1.0-SNAPSHOT.jar
```

The plugin has its own git history (`git -C warp-afk log`) — push it to a separate GitLab project if you want CI for the plugin alone.

## CI

- **GitLab (primary, local runner):** [`.gitlab-ci.yml`](.gitlab-ci.yml) targets the `local-podman-warpMC` runner (`tags: warpmc`, `privileged`, podman `unix:///run/user/1000/podman/podman.sock`). It runs `applyAllPatches → build → createMojmapBundlerJar` on every push to `warp/*` and stores `purpur-*.jar` as pipeline artifacts (1 week). See [`../flatpak-repo/.gitlab-ci.yml`](../flatpak-repo/.gitlab-ci.yml) for the original podman-runner pattern.
- **GitHub Actions (mirror):** [`.github/workflows/build.yml`](.github/workflows/build.yml) (Java 25, Gradle 9) runs on `warp/*` + `ver/*`, uploads jars as artifacts.

Registered runner (already running via `systemctl --user status gitlab-runner`):

```bash
gitlab-runner verify  # local-podman-warpMC online, tags warpmc,warp,local-podman
# re-register if needed:
gitlab-runner register --url https://gitlab.com --registration-token GR1348941... \
  --description "local-podman-warpMC" --tag-list "warpmc,warp,local-podman" --executor docker
# then edit ~/.gitlab-runner/config.toml: host = "unix:///run/user/1000/podman/podman.sock", privileged = true
systemctl --user restart gitlab-runner
```

## Roadmap

- [x] Rebrand Purpur → WarpMC (brand id, manifest, messages)
- [x] noisium core + optional (loop order, cached fields)
- [x] FerriteCore cache dedup + data-component fix
- [x] Lithium collections/allocations (01) + partial entity
- [x] FakePlayer system (API + NMS + CraftBukkit bridge) — fixed: proper death revive, hidden handling, FakePacketListener overrides, timeout tick
- [x] GitLab CI on local podman runner (`.gitlab-ci.yml` + `local-podman-warpMC`)
- [x] WarpAFK plugin port of unplugged-afk (`warp-afk/` standalone repo)
- [ ] Lithium 02/05/06/04/07/03 in order (world, entity, AI, explosion, raycast, shapes)
- [ ] FerriteCore FastMap remaining (property storage)
- [ ] WarpMC logo + docs polish
- [ ] GitLab Packages Maven repo

See `plan.md` for the full 3-phase plan and excluded patches table.

---

## Credits

- **PurpurMC/Purpur** — upstream fork this project is built on (MIT)
- **PaperMC/Paper** & **Paperweight** — base server + patch toolchain
- **FerriteCore** / **Lithium** / **noisium** — performance patch sources under `InspirationForPerformancePatches/` (see their licenses)
- **YourKit** & **JetBrains** — profiling/IDE sponsors of Purpur (see original README)

---

## License

MIT — all WarpMC patches are MIT unless noted in patch headers. See `LICENSE`. Paper/Paperweight material retains its original license (`PaperMC/Paper`, `PaperMC/paperweight`).

---

## Status

Experimental / WIP — the `warp/26.1.2` branch is under active porting, `codebase is kinda cooked so Releases is my last hope` is the vibe but the `95 MB` bundler jar in Releases does boot. Issues/PRs welcome at `https://gitlab.com/Lalaggi/warpMC`.
