package org.purpurmc.purpur.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.purpurmc.purpur.player.FakePlayer;

/**
 * Fired when a fake player is spawned and added to the server.
 * <p>
 * This is fired after {@link org.bukkit.event.player.PlayerJoinEvent} and the
 * fake player is fully registered with the server.
 */
public class FakePlayerSpawnEvent extends Event {

    private static final HandlerList handlers = new HandlerList();

    private final FakePlayer fakePlayer;

    public FakePlayerSpawnEvent(@NotNull FakePlayer fakePlayer) {
        this.fakePlayer = fakePlayer;
    }

    @NotNull
    public FakePlayer getFakePlayer() {
        return fakePlayer;
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
