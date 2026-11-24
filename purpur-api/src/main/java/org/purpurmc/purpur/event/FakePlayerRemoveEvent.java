package org.purpurmc.purpur.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/**
 * Fired when a fake player is removed from the server (timeout, admin kick,
 * real player login, or death without revive).
 */
public class FakePlayerRemoveEvent extends Event {

    private static final HandlerList handlers = new HandlerList();

    public enum Reason {
        TIMEOUT,   // Auto-removed after timeout elapsed
        ADMIN,     // Removed by admin command or plugin
        REAL_LOGIN, // Real player with same UUID logged in
        DEATH_NO_REVIVE, // Died and resetHealthOnDeath was false
        SHUTDOWN   // Server shutdown
    }

    private final UUID fakePlayerUuid;
    private final String fakePlayerName;
    private final Reason reason;

    public FakePlayerRemoveEvent(@NotNull UUID fakePlayerUuid, @Nullable String fakePlayerName, @NotNull Reason reason) {
        this.fakePlayerUuid = fakePlayerUuid;
        this.fakePlayerName = fakePlayerName;
        this.reason = reason;
    }

    @NotNull
    public UUID getFakePlayerUuid() {
        return fakePlayerUuid;
    }

    @Nullable
    public String getFakePlayerName() {
        return fakePlayerName;
    }

    @NotNull
    public Reason getReason() {
        return reason;
    }

    @Override
    @NotNull
    public HandlerList getHandlers() {
        return handlers;
    }

    @NotNull
    public static HandlerList getHandlerList() {
        return handlers;
    }
}
