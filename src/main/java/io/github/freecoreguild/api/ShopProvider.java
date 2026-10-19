package io.github.freecoreguild.api;

import io.github.freecoreguild.service.ShopItem;
import java.util.*;

/** Read-only public integration API for guild shop catalog and role-specific offers. */
public interface ShopProvider {
    Collection<ShopItem> items();
    Optional<ShopItem.Offer> offer(String itemId, GuildProvider.Role role);
}
