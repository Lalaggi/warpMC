package org.purpurmc.purpur.player;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Metrics for the fake player system, useful for monitoring and debugging.
 */
public record FakePlayerMetrics(
    long totalSpawned,
    long currentlyActive,
    long totalExpired,
    long totalRemovedByAdmin,
    long totalRespawns,
    long totalRealLoginEvictions,
    double averageLifetimeMinutes
) {}
