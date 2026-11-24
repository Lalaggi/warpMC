package org.purpurmc.purpur.player;

import java.io.File;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Factory for creating and managing {@link FakePlayer} instances.
 * <p>
 * Obtain an instance via {@code Purpur.getFakePlayerFactory()}.
 */
public interface FakePlayerFactory {

    /**
     * Creates a new builder for spawning a fake player that mimics an existing player.
     * The fake player will inherit the game profile (name, skin, UUID) of the given player.
     *
     * @param player the real player to mimic
     * @return a new builder configured with the player's identity
     */
    @NotNull FakePlayerBuilder createBuilder(@NotNull Player player);

    /**
     * Creates a new builder for spawning a fake player with a custom UUID and name.
     *
     * @param uuid  the UUID for the fake player
     * @param name  the display name for the fake player
     * @return a new builder
     */
    @NotNull FakePlayerBuilder createBuilder(@NotNull UUID uuid, @NotNull String name);

    /**
     * Gets a fake player by its UUID.
     *
     * @param uuid the UUID
     * @return the fake player, or null if not found
     */
    @Nullable FakePlayer getFakePlayer(@NotNull UUID uuid);

    /**
     * Returns whether the given UUID belongs to an active fake player.
     *
     * @param uuid the UUID to check
     * @return true if a fake player with that UUID is active
     */
    boolean isFakePlayer(@NotNull UUID uuid);

    /**
     * Gets all currently active fake players.
     *
     * @return an unmodifiable collection of active fake players
     */
    @NotNull Collection<FakePlayer> getActiveFakePlayers();

    /**
     * Removes (kicks) a fake player by UUID.
     *
     * @param uuid the UUID of the fake player to remove
     * @return true if the fake player was found and removed
     */
    boolean removeFakePlayer(@NotNull UUID uuid);

    /**
     * Returns whether the fake player system is enabled.
     *
     * @return true if enabled
     */
    boolean isEnabled();

    /**
     * Sets whether the fake player system is enabled.
     *
     * @param enabled true to enable
     */
    void setEnabled(boolean enabled);

    /**
     * Saves all active fake players to disk for persistence across restarts.
     * The write is atomic (writes to a temp file then renames).
     *
     * @param file the file to save to
     * @throws java.io.IOException if the save fails
     */
    void saveAll(@NotNull File file) throws java.io.IOException;

    /**
     * Loads fake players from disk and spawns them. Each spawned fake player is
     * passed to {@code postSpawn} for additional configuration.
     *
     * @param file the file to load from
     * @param postSpawn callback for each spawned fake player (may be null)
     * @return the number of fake players successfully restored
     * @throws java.io.IOException if the load fails
     */
    int loadAll(@NotNull File file, @Nullable Consumer<FakePlayer> postSpawn) throws java.io.IOException;

    /**
     * Registers a custom serializer for extra fake player data that should be
     * persisted alongside the built-in fields.
     *
     * @param key     the config key for this data
     * @param serializer the serializer instance
     */
    void registerSerializer(@NotNull String key, @NotNull FakeDataSerializer serializer);

    /**
     * Gets the profile cache used for resolving skins/textures for offline spawns.
     *
     * @return the profile cache, or null if not available
     */
    @Nullable ProfileCache getProfileCache();

    // --- Async variants ---

    /**
     * Asynchronously spawns a fake player. Returns a future that completes when
     * the spawn is finished. Safe to call from any thread.
     *
     * @param builder the builder to use
     * @return a future completing with the spawned fake player
     */
    @NotNull CompletableFuture<FakePlayer> spawnAsync(@NotNull FakePlayerBuilder builder);

    /**
     * Asynchronously removes a fake player by UUID.
     *
     * @param uuid the UUID to remove
     * @return a future completing with true if removed
     */
    @NotNull CompletableFuture<Boolean> removeAsync(@NotNull UUID uuid);
}

/**
 * Serializer for custom fake player data in the persistence system.
 */
interface FakeDataSerializer {

    /**
     * Serializes extra data for a fake player into a string.
     *
     * @param fakePlayer the fake player
     * @return serialized data string
     */
    String serialize(@NotNull FakePlayer fakePlayer);

    /**
     * Deserializes extra data and applies it to a fake player.
     *
     * @param fakePlayer the fake player to apply data to
     * @param data       the serialized data string
     */
    void deserialize(@NotNull FakePlayer fakePlayer, @NotNull String data);
}

/**
 * Cache for player profiles (skins/textures) used when spawning fake players
 * for offline or custom UUIDs. Profile data is opaque to the API layer.
 */
interface ProfileCache {

    /**
     * Gets a cached profile by UUID, or null if not cached.
     *
     * @param uuid the UUID
     * @return the cached profile data, or null
     */
    @Nullable Object get(@NotNull UUID uuid);

    /**
     * Puts a profile into the cache.
     *
     * @param uuid   the UUID
     * @param profile the profile data to cache
     */
    void put(@NotNull UUID uuid, @NotNull Object profile);

    /**
     * Removes a profile from the cache.
     *
     * @param uuid the UUID
     */
    void remove(@NotNull UUID uuid);

    /**
     * Clears the entire cache.
     */
    void clear();
}
