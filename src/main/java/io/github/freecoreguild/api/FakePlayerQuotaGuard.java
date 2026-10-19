package io.github.freecoreguild.api;

import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.UUID;

/**
 * Small integration helper for fake-player plugins. It only talks to the
 * read/write GuildProvider service and does not depend on FreeCoreGuild's implementation.
 */
public final class FakePlayerQuotaGuard {
    private FakePlayerQuotaGuard() {}

    public static GuildProvider findProvider() {
        RegisteredServiceProvider<GuildProvider> registration =
                Bukkit.getServicesManager().getRegistration(GuildProvider.class);
        return registration == null ? null : registration.getProvider();
    }

    public static boolean canOperate(UUID operator) {
        GuildProvider provider = findProvider();
        return provider != null && operator != null && provider.canOperateFakePlayers(operator);
    }

    public static boolean reserve(UUID operator, String stableIdentifier) {
        GuildProvider provider = findProvider();
        return provider != null
                && operator != null
                && stableIdentifier != null
                && !stableIdentifier.isBlank()
                && provider.reserveFakePlayer(operator, stableIdentifier);
    }

    public static boolean release(UUID operator, String stableIdentifier) {
        GuildProvider provider = findProvider();
        return provider != null
                && operator != null
                && stableIdentifier != null
                && provider.releaseFakePlayer(operator, stableIdentifier);
    }

    public static int available(UUID player) {
        GuildProvider provider = findProvider();
        if (provider == null || player == null) return 0;
        return Math.max(0, provider.fakePlayerQuota(player) - provider.currentFakePlayers(player));
    }
}
