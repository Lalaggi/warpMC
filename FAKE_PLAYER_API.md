# FakePlayer API

Server-side fake player system for WarpMC. Allows plugins to create full
`Player` instances that exist entirely on the server — no client connection
required.

## Quick Start

```java
import org.purpurmc.purpur.Purpur;
import org.purpurmc.purpur.player.FakePlayerFactory;
import org.purpurmc.purpur.player.FakePlayer;

FakePlayerFactory factory = Purpur.getFakePlayerFactory();
if (factory == null) return; // server not ready

// Spawn a fake player mimicking an existing player
FakePlayer bot = factory.createBuilder(player)
    .world(player.getWorld())
    .location(player.getLocation())
    .timeoutMinutes(30)
    .reason("AFK")
    .damageDisabled(true)
    .hidden(false)
    .resetHealthOnDeath(true)
    .spawn();
```

## API Overview

### Accessing the Factory

```java
FakePlayerFactory factory = Purpur.getFakePlayerFactory();
```

Returns `null` if the server hasn't initialized yet. The factory is registered
during `PlayerList` construction.

### Creating a Builder

Two factory methods:

```java
// Mimic an existing player (copies UUID, name, skin)
FakePlayerBuilder builder = factory.createBuilder(player);

// Custom identity
FakePlayerBuilder builder = factory.createBuilder(uuid, "BotName");
```

### Builder Options

| Method | Default | Description |
|--------|---------|-------------|
| `world(World)` | overworld | World to spawn in |
| `location(Location)` | world origin | Spawn position (overrides world if set) |
| `timeoutMinutes(long)` | -1 (never) | Auto-remove after N minutes |
| `reason(String)` | null | Descriptive tag (e.g. "AFK", "Guard") |
| `damageDisabled(boolean)` | false | Immune to all damage |
| `hidden(boolean)` | false | Hidden from tab list / PlayerInfo packets (per-viewer in WarpAFK: honours `hide-player` + `hide-from-ops`; see WarpAFK for Pl3xMap/tab visibility) |
| `resetHealthOnDeath(boolean)` | true | Auto-respawn with full health |
| `spawn()` | — | Spawns the fake player, returns `FakePlayer` |

### The FakePlayer Interface

Extends `org.bukkit.entity.Player`. All standard Bukkit player methods work.
Additional methods:

```java
boolean isFake();                   // always true
UUID getOwnerUuid();                // UUID of the real player (or custom UUID)
long getTimeoutMinutes();           // configured timeout
String getReason();                 // reason tag
Instant getSpawnTime();             // when it was spawned
boolean isExpired();                // true if timeout has elapsed
boolean isDamageDisabled();         // damage immunity
boolean isHidden();                 // tab list visibility
boolean isResetHealthOnDeath();     // auto-heal on death
void remove();                      // kick the fake player
```

### Managing Fake Players

```java
// Look up by UUID
FakePlayer fp = factory.getFakePlayer(uuid);

// Check if a UUID is a fake player
boolean isFake = factory.isFakePlayer(uuid);

// Get all active fakes
Collection<FakePlayer> all = factory.getActiveFakePlayers();

// Remove (kick) a fake player
boolean removed = factory.removeFakePlayer(uuid);
```

### Detecting Fake Players in Events

```java
@EventHandler
public void onDamage(EntityDamageEvent event) {
    if (event.getEntity() instanceof FakePlayer fake) {
        if (fake.isDamageDisabled()) {
            event.setCancelled(true);
        }
    }
}
```

## Architecture

```
API Layer (purpur-api)
  FakePlayer          - Bukkit Player interface with fake-player methods
  FakePlayerBuilder   - Builder pattern for configuring and spawning
  FakePlayerFactory   - Create, lookup, list, and remove fake players
  Purpur              - Static accessor for the factory

Server Layer (purpur-server)
  FakeServerPlayerManager  - Implements FakePlayerFactory, manages lifecycle
  FakeServerPlayer (NMS)   - Extends ServerPlayer, overrides damage/death/tick
  FakeConnection (NMS)     - No-op Connection using EmbeddedChannel
  FakePacketListener (NMS) - No-op ServerGamePacketListenerImpl

CraftBukkit Layer (paper-server)
  CraftFakePlayer     - Extends CraftPlayer, implements FakePlayer
```

## What Fake Players Can and Cannot Do

### Can Do
- Appear in the player list (tab list)
- Be targeted by mobs, take pathfinding decisions
- Interact with the world (redstone, pressure plates, etc.)
- Receive ticks and process game logic
- Die, respawn, and trigger `PlayerDeathEvent`
- Be teleported, moved, and tracked by chunk loading
- Trigger `PlayerJoinEvent` / `PlayerQuitEvent`
- Hold items, use items, wear armor

### Cannot Do
- Receive network packets (all `send()` calls are no-ops)
- Be kicked by the server (no-op `disconnect()`)
- Have a chat session (overridden to `null`)
- Be damage-ticked by the client (position is server-authoritative)

## Server-Side Behavior

### Spawning Flow
1. `FakePlayerBuilder.spawn()` creates a `GameProfile` (with skin textures if available) and `FakeServerPlayer`
2. A `FakeConnection` + `FakePacketListener` are created (no real socket)
3. `PlayerList.placeNewPlayer()` is called — fires `PlayerJoinEvent`, adds to
   the player list, sends `PlayerInfo` packets (listed per-viewer; hidden respected)
4. The fake player is teleported to the target position and `RegionizedPlayerChunkLoader` is engaged (fakes now load chunks like real players for farm ticking + Pl3xMap visibility)
5. Registered in `FakeServerPlayerManager` for tick tracking

### Timeout Expiration
- Checked every server tick in `MinecraftServer.tickServer()`
- `FakeServerPlayer.tick()` also checks expiration
- When expired, the player is removed via `PlayerList.remove()`
- Fires `PlayerQuitEvent`

### Real Player Login
- `ServerLoginPacketListenerImpl` checks for an active fake player with the
  same UUID
- If found, the fake player is removed before the real player joins
- Prevents duplicate UUID conflicts

### Respawn
- `PlayerList.respawn()` detects `FakeServerPlayer` instances
- Creates a new `FakeServerPlayer` with the same configuration
- Transfers `damageDisabled`, `hidden`, `resetHealthOnDeath`, `timeoutMinutes`,
  `reason`, and `ownerUuid` to the new instance
- Re-registers with the manager

### Damage & Knockback
- `FakeServerPlayer.hurtServer()` returns `false` when `damageDisabled` is true
- `Player.causeExtraKnockback()` skips velocity events for fake players
- `PistonMovingBlockEntity` allows slime block bounce for fake players

### Entity Identity
- `FakeServerPlayer.isClientAuthoritative()` returns `false` — the server
  controls movement, preventing desync from client-side physics
- `CraftEntity.getEntity()` dispatches `FakeServerPlayer` to `CraftFakePlayer`
  before the standard entity type resolution

## Usage Example: AFK Bot

```java
public void goAfk(Player player, long minutes) {
    FakePlayerFactory factory = Purpur.getFakePlayerFactory();
    if (factory == null) return;

    // Remove existing fake if already AFK
    factory.removeFakePlayer(player.getUniqueId());

    FakePlayer bot = factory.createBuilder(player)
        .world(player.getWorld())
        .location(player.getLocation())
        .timeoutMinutes(minutes)
        .reason("AFK")
        .damageDisabled(true)
        .hidden(false)
        .resetHealthOnDeath(true)
        .spawn();

    player.kick(Component.text("You are now AFK. Bot is active for " + minutes + " minutes."));
}
```

## Usage Example: Guard NPC

```java
public void spawnGuard(Location loc) {
    FakePlayerFactory factory = Purpur.getFakePlayerFactory();
    if (factory == null) return;

    UUID guardUuid = UUID.randomUUID();
    factory.createBuilder(guardUuid, "Guard")
        .world(loc.getWorld())
        .location(loc)
        .timeoutMinutes(-1)  // no timeout
        .reason("Guard")
        .damageDisabled(false) // can fight
        .hidden(false)
        .resetHealthOnDeath(true)
        .spawn();
}
```
