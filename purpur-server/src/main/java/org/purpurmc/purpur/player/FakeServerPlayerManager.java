package org.purpurmc.purpur.player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.FakeServerPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.FakeConnection;
import net.minecraft.server.players.PlayerList;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Server-side manager for {@link FakeServerPlayer} instances.
 * <p>
 * Tracks all active fake players, provides the factory for creating them,
 * and handles ticking (timeout expiration).
 */
public class FakeServerPlayerManager implements FakePlayerFactory {

    private static final FakeServerPlayerManager INSTANCE = new FakeServerPlayerManager();
    public static FakeServerPlayerManager getInstance() { return INSTANCE; }

    private final Map<UUID, FakeServerPlayer> activeFakePlayers = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;

    private FakeServerPlayerManager() {}

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    // --- Factory API ---

    @Override
    public @NotNull FakePlayerBuilder createBuilder(@NotNull org.bukkit.entity.Player player) {
        return new Builder(player.getUniqueId(), player.getName())
            .world(player.getWorld())
            .location(player.getLocation());
    }

    @Override
    public @NotNull FakePlayerBuilder createBuilder(@NotNull UUID uuid, @NotNull String name) {
        return new Builder(uuid, name);
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
        FakeServerPlayer player = this.activeFakePlayers.remove(uuid);
        if (player != null) {
            MinecraftServer server = MinecraftServer.getServer();
            server.getPlayerList().remove(player);
            return true;
        }
        return false;
    }

    // --- Internal methods ---

    public void register(@NotNull FakeServerPlayer player) {
        this.activeFakePlayers.put(player.getUUID(), player);
    }

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
        for (Map.Entry<UUID, FakeServerPlayer> entry : this.activeFakePlayers.entrySet()) {
            FakeServerPlayer player = entry.getValue();
            if (player.isExpired()) {
                expired.add(entry.getKey());
            }
        }

        for (UUID uuid : expired) {
            FakeServerPlayer player = this.activeFakePlayers.remove(uuid);
            if (player != null && player.isAlive()) {
                MinecraftServer server = MinecraftServer.getServer();
                server.getPlayerList().remove(player);
            }
        }
    }

    // --- Builder implementation ---

    private class Builder implements FakePlayerBuilder {
        private final UUID uuid;
        private final String name;
        private @Nullable World world = null;
        private @Nullable Location location = null;
        private long timeoutMinutes = -1;
        private @Nullable String reason = null;
        private boolean damageDisabled = false;
        private boolean hidden = false;
        private boolean resetHealthOnDeath = true;

        Builder(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
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
        public @NotNull FakePlayerBuilder resetHealthOnDeath(boolean reset) {
            this.resetHealthOnDeath = reset;
            return this;
        }

        @Override
        public @NotNull FakePlayer spawn() {
            if (!FakeServerPlayerManager.this.enabled) {
                throw new IllegalStateException("FakePlayer system is disabled");
            }

            MinecraftServer server = MinecraftServer.getServer();
            PlayerList playerList = server.getPlayerList();

            // Determine world and level
            net.minecraft.server.level.ServerLevel level;
            if (this.world != null) {
                level = ((CraftWorld) this.world).getHandle();
            } else {
                level = server.overworld();
            }

            // Determine position
            double x = 0, y = 64, z = 0;
            float yaw = 0, pitch = 0;
            if (this.location != null) {
                x = this.location.getX();
                y = this.location.getY();
                z = this.location.getZ();
                yaw = this.location.getYaw();
                pitch = this.location.getPitch();
                if (this.location.getWorld() != null) {
                    level = ((CraftWorld) this.location.getWorld()).getHandle();
                }
            }

            // Create game profile
            GameProfile profile = new GameProfile(this.uuid, this.name);

            // Create the fake player
            net.minecraft.server.level.ClientInformation ci = net.minecraft.server.level.ClientInformation.createDefault();
            FakeServerPlayer fakePlayer = new FakeServerPlayer(server, level, profile, ci, this.uuid);
            FakeConnection connection = new FakeConnection();
            CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);

            // Configure
            fakePlayer.setTimeoutMinutes(this.timeoutMinutes);
            fakePlayer.setReason(this.reason);
            fakePlayer.setDamageDisabled(this.damageDisabled);
            fakePlayer.setHidden(this.hidden);
            fakePlayer.setResetHealthOnDeath(this.resetHealthOnDeath);

            // Place into the world — this fires PlayerJoinEvent and adds to player lists
            playerList.placeNewPlayer(connection, fakePlayer, cookie);

            // Position
            fakePlayer.connection.teleport(x, y, z, yaw, pitch);

            // Register
            FakeServerPlayerManager.this.register(fakePlayer);

            // Return the Bukkit API handle
            return (FakePlayer) fakePlayer.getBukkitEntity();
        }
    }
}
