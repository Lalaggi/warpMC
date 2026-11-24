package org.purpurmc.purpur.player;

import java.time.Instant;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Represents a fake player that acts exactly like a real player on the server,
 * but without an actual network connection. This allows players to "unplug" —
 * disconnecting their client while a server-side bot continues to play on their behalf.
 * <p>
 * Fake players are full {@link Player} instances: they appear in tab list,
 * receive ticks, can interact with the world, and respond to all game mechanics
 * identically to real players.
 * <p>
 * Fake players are registered with the {@link FakePlayerFactory} and fire
 * lifecycle events ({@link org.purpurmc.purpur.event.FakePlayerSpawnEvent},
 * {@link org.purpurmc.purpur.event.FakePlayerRemoveEvent},
 * {@link org.purpurmc.purpur.event.FakePlayerRespawnEvent}).
 */
public interface FakePlayer extends Player {

    /**
     * Returns whether this player is a fake (server-side) player.
     *
     * @return true if this is a fake player, false if it is a real player
     */
    boolean isFake();

    /**
     * Gets the UUID of the real player who owns this fake player.
     *
     * @return the owner's UUID
     */
    @NotNull UUID getOwnerUuid();

    /**
     * Sets the UUID of the real player who owns this fake player.
     *
     * @param ownerUuid the new owner's UUID
     */
    void setOwnerUuid(@NotNull UUID ownerUuid);

    /**
     * Gets the duration in minutes that this fake player is configured to remain.
     *
     * @return the configured timeout in minutes, or -1 if no timeout
     */
    long getTimeoutMinutes();

    /**
     * Sets the duration in minutes that this fake player should remain active.
     *
     * @param minutes the timeout in minutes, or -1 for no timeout
     */
    void setTimeoutMinutes(long minutes);

    /**
     * Gets the reason this fake player was spawned, if any.
     *
     * @return the reason string, or null if none set
     */
    @Nullable String getReason();

    /**
     * Sets the reason this fake player was spawned.
     *
     * @param reason the reason string
     */
    void setReason(@Nullable String reason);

    /**
     * Gets the time this fake player was spawned.
     *
     * @return the spawn time, or null if not yet set
     */
    @Nullable Instant getSpawnTime();

    /**
     * Returns whether this fake player has expired (timeout reached).
     *
     * @return true if expired
     */
    boolean isExpired();

    /**
     * Returns whether damage is disabled for this fake player.
     *
     * @return true if damage is disabled
     */
    boolean isDamageDisabled();

    /**
     * Sets whether damage is disabled for this fake player.
     *
     * @param disabled true to disable damage
     */
    void setDamageDisabled(boolean disabled);

    /**
     * Returns whether this fake player is hidden from tab list and other players.
     *
     * @return true if hidden
     */
    boolean isHidden();

    /**
     * Sets whether this fake player should be hidden from tab list and other players.
     *
     * @param hidden true to hide
     */
    void setHidden(boolean hidden);

    /**
     * Returns whether this fake player respawns with full health after death.
     *
     * @return true if health is reset on death
     */
    boolean isResetHealthOnDeath();

    /**
     * Sets whether this fake player respawns with full health after death.
     *
     * @param reset true to reset health on death
     */
    void setResetHealthOnDeath(boolean reset);

    /**
     * Removes this fake player from the server, as if they disconnected.
     */
    void remove();

    /**
     * Gets a persistent session identifier for this fake player. Used by the
     * built-in persistence system to match saved data across restarts.
     *
     * @return the session UUID, or null if not persisted
     */
    @Nullable UUID getSessionId();

    /**
     * Sets the persistent session identifier for this fake player.
     *
     * @param sessionId the session UUID
     */
    void setSessionId(@Nullable UUID sessionId);

    /**
     * Returns whether this fake player should be hidden from operators as well
     * when {@link #isHidden()} is true.
     *
     * @return true if hidden from ops too
     */
    boolean isHiddenFromOps();

    /**
     * Sets whether this fake player should be hidden from operators as well
     * when {@link #isHidden()} is true.
     *
     * @param hiddenFromOps true to hide from operators
     */
    void setHiddenFromOps(boolean hiddenFromOps);

    /**
     * Returns whether this fake player's join/leave messages are suppressed.
     *
     * @return true if join/leave messages are suppressed
     */
    boolean isSuppressJoinLeaveMessages();

    /**
     * Sets whether this fake player's join/leave messages are suppressed.
     *
     * @param suppress true to suppress messages
     */
    void setSuppressJoinLeaveMessages(boolean suppress);

    /**
     * Returns the display duration setting — when true, broadcast messages
     * include the remaining timeout duration.
     *
     * @return true if duration should be displayed in messages
     */
    boolean isDisplayDuration();

    /**
     * Sets whether broadcast messages should include the remaining timeout duration.
     *
     * @param displayDuration true to display duration
     */
    void setDisplayDuration(boolean displayDuration);
}
