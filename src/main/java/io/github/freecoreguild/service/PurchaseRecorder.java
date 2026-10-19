package io.github.freecoreguild.service;

import java.time.Instant;
import java.util.UUID;

/** Mutation API for recording shop purchases in a purchase ledger. */
@FunctionalInterface
public interface PurchaseRecorder {
    /**
     * Atomically records units for a guild/player/item and purchase window.
     * A new window replaces the previous counter for that row; repeated calls
     * in the same window increment it.
     */
    boolean recordPurchase(long guildId, UUID player, String itemId, int amount, Instant windowStart);
}
