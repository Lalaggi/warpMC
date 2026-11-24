package org.purpurmc.purpur.player;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.FakeServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.FakeConnection;
import net.minecraft.server.network.FakePacketListener;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.Level;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.purpurmc.purpur.Purpur;
import org.purpurmc.purpur.event.FakePlayerRemoveEvent;
import org.purpurmc.purpur.event.FakePlayerRespawnEvent;
import org.purpurmc.purpur.event.FakePlayerSpawnEvent;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Server-side manager for {@link FakeServerPlayer} instances.
 * <p>
 * Tracks all active fake players, provides the factory for creating them,
 * handles ticking (timeout expiration), persistence, and lifecycle events.
 * <p>
 * All public methods are thread-safe; spawn/remove operations are atomic and
 * safe to call from any thread (they are dispatched to the server thread
 * internally when needed).
 */
public class FakeServerPlayerManager implements FakePlayerFactory {

    private static final FakeServerPlayerManager INSTANCE = new FakeServerPlayerManager();
    public static FakeServerPlayerManager getInstance() { return INSTANCE; }

    private final Map<UUID, FakeServerPlayer> activeFakePlayers = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<FakeServerPlayer>> inFlightSpawns = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;

    // --- Metrics ---
    private final AtomicLong totalSpawned = new AtomicLong(0);
    private final AtomicLong totalExpired = new AtomicLong(0);
    private final AtomicLong totalRemovedByAdmin = new AtomicLong(0);
    private final AtomicLong totalRespawns = new AtomicLong(0);
    private final AtomicLong totalRealLoginEvictions = new AtomicLong(0);
    private final AtomicLong totalSpawnTimeMillis = new AtomicLong(0);

    // --- Persistence ---
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, FakeDataSerializer> serializers = new HashMap<>();

    private FakeServerPlayerManager() {}

    @Override
    public boolean isEnabled() {
        return this.enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    // --- Profile Cache (lazy, backed by server's profile resolver) ---

    @Nullable
    @Override
    public ProfileCache getProfileCache() {
        return new ProfileCache() {
            private final Map<UUID, Object> cache = new ConcurrentHashMap<>();

            @Override
            @Nullable
            public Object get(@NotNull UUID uuid) {
                return cache.get(uuid);
            }

            @Override
            public void put(@NotNull UUID uuid, @NotNull Object profile) {
                cache.put(uuid, profile);
            }

            @Override
            public void remove(@NotNull UUID uuid) {
                cache.remove(uuid);
            }

            @Override
            public void clear() {
                cache.clear();
            }
        };
    }

    // --- Factory API ---

    @Override
    public @NotNull FakePlayerBuilder createBuilder(@NotNull org.bukkit.entity.Player player) {
        // Capture GameProfile with textures to preserve skin (fixes Steve skin bug)
        GameProfile profile = null;
        try {
            if (player instanceof org.bukkit.craftbukkit.entity.CraftPlayer cp) {
                profile = cp.getHandle().getGameProfile();
            }
        } catch (Exception ignored) {}
        Builder b = new Builder(player.getUniqueId(), player.getName());
        if (profile != null) {
            b.profile(profile);
        }
        return b.world(player.getWorld()).location(player.getLocation());
    }

    @Override
    public @NotNull FakePlayerBuilder createBuilder(@NotNull UUID uuid, @NotNull String name) {
        Builder b = new Builder(uuid, name);
        // Try to enrich profile with cached skin for offline spawns
        try {
            MinecraftServer server = MinecraftServer.getServer();
            if (server != null) {
                var resolver = server.services().profileResolver();
                if (resolver != null) {
                    var opt = resolver.fetchById(uuid);
                    if (opt.isEmpty()) opt = resolver.fetchByName(name);
                    opt.ifPresent(b::profile);
                }
            }
        } catch (Exception ignored) {}
        return b;
    }

    @Override
    public @Nullable FakePlayer getFakePlayer(@NotNull UUID uuid) {
        FakeServerPlayer nms = this.activeFakePlayers.get(uuid);
        if (nms != null) {
            return (FakePlayer) nms.getBukkitEntity();
        }
        return null;
    }

    @Override
    public boolean isFakePlayer(@NotNull UUID uuid) {
        return this.activeFakePlayers.containsKey(uuid);
    }

    @Override
    public @NotNull Collection<FakePlayer> getActiveFakePlayers() {
        List<FakePlayer> result = new ArrayList<>();
        for (FakeServerPlayer nms : this.activeFakePlayers.values()) {
            result.add((FakePlayer) nms.getBukkitEntity());
        }
        return Collections.unmodifiableCollection(result);
    }

    @Override
    public boolean removeFakePlayer(@NotNull UUID uuid) {
        return removeFakePlayerInternal(uuid, FakePlayerRemoveEvent.Reason.ADMIN);
    }

    @Override
    public void saveAll(@NotNull File file) throws IOException {
        List<SavedFakeData> list = new ArrayList<>();
        for (FakeServerPlayer fp : this.activeFakePlayers.values()) {
            list.add(saveFakeToData(fp));
        }
        if (!file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }
        // Atomic write: temp file then rename
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileWriter w = new FileWriter(temp)) {
            gson.toJson(list, w);
        }
        if (!temp.renameTo(file)) {
            throw new IOException("Failed to rename temp persistence file");
        }
    }

    @Override
    public int loadAll(@NotNull File file, @Nullable Consumer<FakePlayer> postSpawn) throws IOException {
        if (!file.exists()) return 0;
        try (FileReader r = new FileReader(file)) {
            Type type = new TypeToken<List<SavedFakeData>>(){}.getType();
            List<SavedFakeData> list = gson.fromJson(r, type);
            if (list == null) return 0;
            int restored = 0;
            for (SavedFakeData data : list) {
                try {
                    UUID uuid = UUID.fromString(data.uuid);
                    // Skip if already active
                    if (this.activeFakePlayers.containsKey(uuid)) continue;
                    // Skip if real player is online
                    MinecraftServer server = MinecraftServer.getServer();
                    if (server != null && server.getPlayerList().getPlayer(uuid) != null) continue;

                    World world = data.world != null ? org.bukkit.Bukkit.getWorld(data.world) : null;
                    if (world == null) world = org.bukkit.Bukkit.getWorlds().isEmpty() ? null : org.bukkit.Bukkit.getWorlds().get(0);
                    if (world == null) continue;

                    Location loc = new Location(world, data.x, data.y, data.z, data.yaw, data.pitch);

                    long remaining = data.timeoutMinutes < 0 ? -1 : data.timeoutMinutes;
                    Builder builder = new Builder(uuid, data.name);
                    builder.world(world);
                    builder.location(loc);
                    builder.timeoutMinutes(remaining);
                    builder.reason(data.reason);
                    builder.damageDisabled(data.damageDisabled);
                    builder.hidden(data.hidden);
                    builder.hiddenFromNonOps(data.hiddenFromOps);
                    builder.resetHealthOnDeath(data.resetHealthOnDeath);
                    builder.suppressJoinLeaveMessages(data.suppressJoinLeaveMessages);
                    builder.displayDuration(data.displayDuration);
                    if (data.profile != null) {
                        builder.profile(data.profile);
                    }
                    builder.sessionId(data.sessionId != null ? UUID.fromString(data.sessionId) : null);

                    FakePlayer spawned = builder.spawn();
                    if (postSpawn != null) postSpawn.accept(spawned);
                    restored++;
                } catch (Exception e) {
                    // Log but continue with other entries
                }
            }
            return restored;
        }
    }

    @Override
    public void registerSerializer(@NotNull String key, @NotNull FakeDataSerializer serializer) {
        this.serializers.put(key, serializer);
    }

    @Override
    public @NotNull CompletableFuture<FakePlayer> spawnAsync(@NotNull FakePlayerBuilder builder) {
        CompletableFuture<FakePlayer> result = new CompletableFuture<>();
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            result.completeExceptionally(new IllegalStateException("Server not running"));
            return result;
        }
        server.submit(() -> {
            try {
                FakePlayer fp = spawnBuilderInternal(builder);
                result.complete(fp);
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    @Override
    public @NotNull CompletableFuture<Boolean> removeAsync(@NotNull UUID uuid) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            result.complete(false);
            return result;
        }
        server.submit(() -> {
            result.complete(removeFakePlayerInternal(uuid, FakePlayerRemoveEvent.Reason.ADMIN));
        });
        return result;
    }

    // --- Internal methods ---

    /**
     * Register a fake player with the manager (called during spawn).
     */
    public void register(@NotNull FakeServerPlayer player) {
        this.activeFakePlayers.put(player.getUUID(), player);
        totalSpawned.incrementAndGet();
        totalSpawnTimeMillis.addAndGet(System.currentTimeMillis());
    }

    /**
     * Unregister a fake player from the manager (called during removal).
     */
    public void unregister(@NotNull UUID uuid) {
        this.activeFakePlayers.remove(uuid);
    }

    @Nullable
    public FakeServerPlayer getNmsFakePlayer(@NotNull UUID uuid) {
        return this.activeFakePlayers.get(uuid);
    }

    /**
     * Called from the server tick to check timeout expiration on all fake players.
     */
    public void tick() {
        if (!this.enabled) return;

        List<UUID> expired = new ArrayList<>();
        List<UUID> deadNoRevive = new ArrayList<>();
        for (Map.Entry<UUID, FakeServerPlayer> entry : this.activeFakePlayers.entrySet()) {
            FakeServerPlayer player = entry.getValue();
            if (player.isExpired()) {
                expired.add(entry.getKey());
            } else if (!player.isResetHealthOnDeath() && !player.isAlive()) {
                deadNoRevive.add(entry.getKey());
            }
        }

        for (UUID uuid : expired) {
            removeFakePlayerInternal(uuid, FakePlayerRemoveEvent.Reason.TIMEOUT);
            totalExpired.incrementAndGet();
        }
        for (UUID uuid : deadNoRevive) {
            removeFakePlayerInternal(uuid, FakePlayerRemoveEvent.Reason.DEATH_NO_REVIVE);
        }
    }

    /**
     * Evict a fake player when a real player with the same UUID logs in.
     */
    public void evictForRealLogin(@NotNull UUID uuid) {
        removeFakePlayerInternal(uuid, FakePlayerRemoveEvent.Reason.REAL_LOGIN);
        totalRealLoginEvictions.incrementAndGet();
    }

    /**
     * Handle respawn of a fake player — preserves entity ID and full state.
     * Called from PlayerList patch during respawn.
     */
    public FakeServerPlayer onRespawn(@NotNull FakeServerPlayer oldFake, @NotNull FakeServerPlayer newFake) {
        // Transfer all state atomically
        this.activeFakePlayers.remove(oldFake.getUUID());
        this.activeFakePlayers.put(newFake.getUUID(), newFake);
        totalRespawns.incrementAndGet();

        // Fire respawn event
        PluginManager pm = org.bukkit.Bukkit.getPluginManager();
        if (pm != null) {
            FakePlayer oldBukkit = (FakePlayer) oldFake.getBukkitEntity();
            FakePlayer newBukkit = (FakePlayer) newFake.getBukkitEntity();
            pm.callEvent(new FakePlayerRespawnEvent(oldBukkit, newBukkit));
        }

        return newFake;
    }

    private boolean removeFakePlayerInternal(@NotNull UUID uuid, @NotNull FakePlayerRemoveEvent.Reason reason) {
        FakeServerPlayer player = this.activeFakePlayers.remove(uuid);
        if (player == null) {
            // Also clear any in-flight spawn
            this.inFlightSpawns.remove(uuid);
            return false;
        }

        // Capture name BEFORE removal so listeners can use it
        String fakePlayerName = player.getName() != null ? player.getName().toString() : null;

        // Remove from PlayerList (fires PlayerQuitEvent)
        MinecraftServer server = MinecraftServer.getServer();
        if (server != null) {
            try {
                server.getPlayerList().remove(player);
            } catch (Exception ignored) {}
        }

        // Fire remove event with captured name
        PluginManager pm = org.bukkit.Bukkit.getPluginManager();
        if (pm != null) {
            pm.callEvent(new FakePlayerRemoveEvent(uuid, fakePlayerName, reason));
        }

        if (reason == FakePlayerRemoveEvent.Reason.ADMIN) {
            totalRemovedByAdmin.incrementAndGet();
        }

        return true;
    }

    /**
     * Internal spawn logic — called from Builder.spawn() and spawnAsync.
     * Must be called from the server thread.
     */
    private FakePlayer spawnBuilderInternal(@NotNull FakePlayerBuilder builder) {
        if (!(builder instanceof Builder)) {
            throw new IllegalArgumentException("Builder must be created by this factory");
        }
        Builder b = (Builder) builder;

        if (!this.enabled) {
            throw new IllegalStateException("FakePlayer system is disabled");
        }

        MinecraftServer server = MinecraftServer.getServer();
        PlayerList playerList = server.getPlayerList();

        // Atomic: evict any existing fake with same UUID to prevent duplicates
        FakeServerPlayer existing = this.activeFakePlayers.get(b.uuid);
        if (existing != null) {
            this.activeFakePlayers.remove(b.uuid);
            try { server.getPlayerList().remove(existing); } catch (Exception ignored) {}
        }
        // Also clear in-flight
        this.inFlightSpawns.remove(b.uuid);

        // Determine world and level
        ServerLevel level;
        if (b.world != null) {
            level = ((CraftWorld) b.world).getHandle();
        } else {
            level = server.overworld();
        }

        // Determine position
        double x = 0, y = 64, z = 0;
        float yaw = 0, pitch = 0;
        if (b.location != null) {
            x = b.location.getX();
            y = b.location.getY();
            z = b.location.getZ();
            yaw = b.location.getYaw();
            pitch = b.location.getPitch();
            if (b.location.getWorld() != null) {
                level = ((CraftWorld) b.location.getWorld()).getHandle();
            }
        }

        // Create game profile
        GameProfile profile = b.profile != null ? b.profile : new GameProfile(b.uuid, b.name);
        if (profile.id() == null || !profile.id().equals(b.uuid)) {
            GameProfile corrected = new GameProfile(b.uuid, b.name);
            corrected.properties().putAll(profile.properties());
            profile = corrected;
        } else if (!profile.name().equals(b.name)) {
            GameProfile corrected = new GameProfile(profile.id(), b.name);
            corrected.properties().putAll(profile.properties());
            profile = corrected;
        }

        // Create the fake player
        net.minecraft.server.level.ClientInformation ci = net.minecraft.server.level.ClientInformation.createDefault();
        UUID sessionId = b.sessionId != null ? b.sessionId : UUID.randomUUID();
        FakeServerPlayer fakePlayer = new FakeServerPlayer(server, level, profile, ci, b.uuid, sessionId);
        FakeConnection connection = new FakeConnection();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);

        // Configure before placeNewPlayer so hidden/damage flags are visible to join logic
        fakePlayer.setTimeoutMinutes(b.timeoutMinutes);
        fakePlayer.setReason(b.reason);
        fakePlayer.setDamageDisabled(b.damageDisabled);
        fakePlayer.setHidden(b.hidden);
        fakePlayer.setHiddenFromOps(b.hiddenFromNonOps);
        fakePlayer.setResetHealthOnDeath(b.resetHealthOnDeath);
        fakePlayer.setSuppressJoinLeaveMessages(b.suppressJoinLeaveMessages);
        fakePlayer.setDisplayDuration(b.displayDuration);

        // Set position BEFORE placeNewPlayer to avoid initial 0,0,0 chunk ticket
        fakePlayer.snapTo(x, y, z, yaw, pitch);

        // Register early so PlayerList patches can query isFake / hidden state
        this.register(fakePlayer);

        // Place into the world — fires PlayerJoinEvent and adds to player lists
        try {
            playerList.placeNewPlayer(connection, fakePlayer, cookie);
        } catch (RuntimeException e) {
            this.unregister(fakePlayer.getUUID());
            throw e;
        }

        // Ensure chunk loader is at correct position
        try {
            ca.spottedleaf.moonrise.common.PlatformHooks.get().updateMaps(fakePlayer.level(), fakePlayer);
        } catch (Exception ignored) {}

        // Apply tab-list visibility after join
        applyTabVisibility(fakePlayer);

        // Fire spawn event
        try {
            PluginManager pm = org.bukkit.Bukkit.getPluginManager();
            if (pm != null) {
                pm.callEvent(new FakePlayerSpawnEvent((FakePlayer) fakePlayer.getBukkitEntity()));
            }
        } catch (Exception ignored) {}

        return (FakePlayer) fakePlayer.getBukkitEntity();
    }

    /**
     * Apply per-viewer tab-list visibility for a newly spawned fake player.
     */
    private void applyTabVisibility(@NotNull FakeServerPlayer fakePlayer) {
        try {
            org.bukkit.entity.Player bukkitFake = (org.bukkit.entity.Player) fakePlayer.getBukkitEntity();
            for (org.bukkit.entity.Player viewer : org.bukkit.Bukkit.getOnlinePlayers()) {
                if (viewer == bukkitFake) continue;
                if (fakePlayer.isHidden()) {
                    boolean hideFromOps = fakePlayer.isHiddenFromOps();
                    boolean shouldHide = hideFromOps || !viewer.isOp();
                    if (shouldHide) {
                        viewer.unlistPlayer(bukkitFake);
                    } else {
                        viewer.listPlayer(bukkitFake);
                    }
                } else {
                    viewer.listPlayer(bukkitFake);
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * Re-apply tab-list visibility for all active fake players (e.g. after config reload).
     */
    public void reapplyTabVisibility() {
        for (FakeServerPlayer fp : this.activeFakePlayers.values()) {
            applyTabVisibility(fp);
        }
    }

    // --- Persistence data classes ---

    private static class SavedFakeData {
        String uuid;
        String name;
        String world;
        double x, y, z;
        float yaw, pitch;
        long timeoutMinutes;
        String reason;
        boolean damageDisabled;
        boolean hidden;
        boolean hiddenFromOps;
        boolean resetHealthOnDeath;
        boolean suppressJoinLeaveMessages;
        boolean displayDuration;
        String sessionId;
        long spawnTimeMillis;
        GameProfile profile;
        Map<String, String> customData = new HashMap<>();
    }

    private SavedFakeData saveFakeToData(FakeServerPlayer fp) {
        SavedFakeData data = new SavedFakeData();
        data.uuid = fp.getUUID().toString();
        data.name = fp.getName() != null ? fp.getName().toString() : fp.getUUID().toString();
        if (fp.getBukkitEntity().getWorld() != null) {
            data.world = fp.getBukkitEntity().getWorld().getName();
        }
        data.x = fp.getX();
        data.y = fp.getY();
        data.z = fp.getZ();
        data.yaw = fp.getYRot();
        data.pitch = fp.getXRot();
        data.timeoutMinutes = fp.getTimeoutMinutes();
        data.reason = fp.getReason();
        data.damageDisabled = fp.isDamageDisabled();
        data.hidden = fp.isHidden();
        data.hiddenFromOps = fp.isHiddenFromOps();
        data.resetHealthOnDeath = fp.isResetHealthOnDeath();
        data.suppressJoinLeaveMessages = fp.isSuppressJoinLeaveMessages();
        data.displayDuration = fp.isDisplayDuration();
        data.sessionId = fp.getSessionId() != null ? fp.getSessionId().toString() : null;
        data.spawnTimeMillis = fp.getSpawnTime() != null ? fp.getSpawnTime().toEpochMilli() : System.currentTimeMillis();

        GameProfile profile = fp.getGameProfile();
        if (profile != null) {
            data.profile = profile;
        }

        // Run custom serializers
        for (Map.Entry<String, FakeDataSerializer> entry : serializers.entrySet()) {
            try {
                data.customData.put(entry.getKey(), entry.getValue().serialize((FakePlayer) fp.getBukkitEntity()));
            } catch (Exception ignored) {}
        }

        return data;
    }

    // --- Builder implementation ---

    private class Builder implements FakePlayerBuilder {
        private final UUID uuid;
        private final String name;
        private @Nullable GameProfile profile = null;
        private @Nullable World world = null;
        private @Nullable Location location = null;
        private long timeoutMinutes = -1;
        private @Nullable String reason = null;
        private boolean damageDisabled = false;
        private boolean hidden = false;
        private boolean hiddenFromNonOps = false;
        private boolean resetHealthOnDeath = true;
        private boolean suppressJoinLeaveMessages = false;
        private boolean displayDuration = false;
        private @Nullable UUID sessionId = null;

        Builder(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        // WarpMC: allow builder to carry full GameProfile (with skin) instead of bare uuid/name
        Builder profile(@NotNull GameProfile p) {
            this.profile = p;
            return this;
        }

        @Override
        public @NotNull FakePlayerBuilder world(@NotNull World world) {
            this.world = world;
            return this;
        }

        @Override
        public @NotNull FakePlayerBuilder location(@NotNull Location location) {
            this.location = location;
            return this;
        }

        @Override
        public @NotNull FakePlayerBuilder timeoutMinutes(long minutes) {
            this.timeoutMinutes = minutes;
            return this;
        }

        @Override
        public @NotNull FakePlayerBuilder reason(@Nullable String reason) {
            this.reason = reason;
            return this;
        }

        @Override
        public @NotNull FakePlayerBuilder damageDisabled(boolean disabled) {
            this.damageDisabled = disabled;
            return this;
        }

        @Override
        public @NotNull FakePlayerBuilder hidden(boolean hidden) {
            this.hidden = hidden;
            return this;
        }

        @Override
        public @NotNull FakePlayerBuilder hiddenFromNonOps(boolean hiddenFromOps) {
            this.hiddenFromNonOps = hiddenFromOps;
            return this;
        }

        @Override
        public @NotNull FakePlayerBuilder resetHealthOnDeath(boolean reset) {
            this.resetHealthOnDeath = reset;
            return this;
        }

        @Override
        public @NotNull FakePlayerBuilder suppressJoinLeaveMessages(boolean suppress) {
            this.suppressJoinLeaveMessages = suppress;
            return this;
        }

        @Override
        public @NotNull FakePlayerBuilder displayDuration(boolean displayDuration) {
            this.displayDuration = displayDuration;
            return this;
        }

        @Override
        public @NotNull FakePlayerBuilder sessionId(@Nullable UUID sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        @Override
        public @NotNull FakePlayer spawn() {
            return spawnBuilderInternal(this);
        }
    }
}
