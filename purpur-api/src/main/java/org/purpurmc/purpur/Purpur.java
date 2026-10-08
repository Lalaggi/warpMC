package org.purpurmc.purpur;

import org.jetbrains.annotations.NotNull;
import org.purpurmc.purpur.player.FakePlayerFactory;

/**
 * Static accessor for WarpMC's server-side utilities.
 * <p>
 * The factory instance is set by the server on startup and is only available
 * after the server has fully initialized.
 */
public final class Purpur {
    private static volatile FakePlayerFactory factory;

    private Purpur() {}

    /**
     * Sets the global {@link FakePlayerFactory} instance.
     * Called internally by the server on startup.
     *
     * @param factory the factory instance
     */
    public static void setFakePlayerFactory(@NotNull FakePlayerFactory factory) {
        Purpur.factory = factory;
    }

    /**
     * Gets the global {@link FakePlayerFactory} instance.
     *
     * @return the factory, or null if the server hasn't initialized yet
     */
    @org.jetbrains.annotations.Nullable
    public static FakePlayerFactory getFakePlayerFactory() {
        return factory;
    }
}
