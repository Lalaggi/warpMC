package org.purpurmc.purpur.player;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Builder for creating a {@link FakePlayer}.
 * <p>
 * Use {@link FakePlayerFactory#createBuilder(Player)} to obtain an instance,
 * then configure it and call {@link #spawn()} to create the fake player.
 */
public interface FakePlayerBuilder {

    /**
     * Sets the world for the fake player.
     *
     * @param world the world to spawn in
     * @return this builder
     */
    @NotNull FakePlayerBuilder world(@NotNull World world);

    /**
     * Sets the spawn location for the fake player.
     *
     * @param location the spawn location
     * @return this builder
     */
    @NotNull FakePlayerBuilder location(@NotNull Location location);

    /**
     * Sets the duration in minutes the fake player should remain active.
     *
     * @param minutes the timeout in minutes, or -1 for no timeout
     * @return this builder
     */
    @NotNull FakePlayerBuilder timeoutMinutes(long minutes);

    /**
     * Sets a reason for this fake player's session.
     *
     * @param reason the reason string, or null for none
     * @return this builder
     */
    @NotNull FakePlayerBuilder reason(@Nullable String reason);

    /**
     * Sets whether damage should be disabled for this fake player.
     *
     * @param disabled true to disable damage
     * @return this builder
     */
    @NotNull FakePlayerBuilder damageDisabled(boolean disabled);

    /**
     * Sets whether this fake player should be hidden from tab list and other players.
     *
     * @param hidden true to hide
     * @return this builder
     */
    @NotNull FakePlayerBuilder hidden(boolean hidden);

    /**
     * Sets whether this fake player should reset health upon death.
     *
     * @param reset true to reset health on death
     * @return this builder
     */
    @NotNull FakePlayerBuilder resetHealthOnDeath(boolean reset);

    /**
     * Spawns the fake player and returns it.
     *
     * @return the spawned fake player
     * @throws IllegalStateException if the builder is in an invalid state
     */
    @NotNull FakePlayer spawn();
}
