package org.purpurmc.purpur.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.purpurmc.purpur.player.FakePlayer;

/**
 * Fired when a fake player respawns after death (when {@code resetHealthOnDeath}
 * is true).
 */
public class FakePlayerRespawnEvent extends Event {

    private static final HandlerList handlers = new HandlerList();

    private final FakePlayer oldFakePlayer;
    private final FakePlayer newFakePlayer;

    public FakePlayerRespawnEvent(@NotNull FakePlayer oldFakePlayer, @NotNull FakePlayer newFakePlayer) {
        this.oldFakePlayer = oldFakePlayer;
        this.newFakePlayer = newFakePlayer;
    }

    @NotNull
    public FakePlayer getOldFakePlayer() {
        return oldFakePlayer;
    }

    @NotNull
    public FakePlayer getNewFakePlayer() {
        return newFakePlayer;
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
