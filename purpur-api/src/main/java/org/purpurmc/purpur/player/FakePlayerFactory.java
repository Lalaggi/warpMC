package org.purpurmc.purpur.player;

import java.util.Collection;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Factory for creating and managing {@link FakePlayer} instances.
 * <p>
 * Obtain an instance via {@code Purpur.getFakeServerPlayerFactory()}
 * or from the WarpMC server bootstrap.
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
}
