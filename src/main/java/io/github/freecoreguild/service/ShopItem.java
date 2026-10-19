package io.github.freecoreguild.service;

import io.github.freecoreguild.api.GuildProvider.Role;
import java.util.*;

/** Immutable shop entry with effective values resolved for each guild role. */
public record ShopItem(String id, Map<Role, Offer> offers) {
    public ShopItem {
        Objects.requireNonNull(id, "id");
        EnumMap<Role, Offer> copy = new EnumMap<>(Role.class);
        copy.putAll(offers);
        offers = Collections.unmodifiableMap(copy);
    }

    /** Null fields in a role offer inherit the previous (lower) role's value. */
    public record Offer(String material, String displayName, double price, LimitType limitType, int limitAmount) {}

    public enum LimitType { UNLIMITED, DAILY, WEEKLY, MONTHLY }
}
