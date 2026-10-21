package io.github.freecoreguild.api;

import io.github.freecoreguild.lang.Lang;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Public integration API for other FreeCore modules. */
public interface GuildProvider {
    Optional<GuildView> guildOf(UUID player);
    Optional<GuildView> find(String name);
    int fakePlayerQuota(UUID player);
    int currentFakePlayers(UUID player);
    boolean canOperateFakePlayers(UUID player);
    Optional<Role> roleOf(UUID player);
    boolean reserveFakePlayer(UUID operator, String identifier);
    boolean releaseFakePlayer(UUID operator, String identifier);

    enum Role {
        MEMBER,
        ELDER,
        VICE_PRESIDENT,
        PRESIDENT;

        public String getChatDisplay() {
            return Lang.text("roles." + name().toLowerCase(java.util.Locale.ROOT) + ".chat");
        }

        public String getMenuDisplay() {
            return Lang.text("roles." + name().toLowerCase(java.util.Locale.ROOT) + ".menu");
        }

        public boolean canOperateFakePlayers() {
            return this == VICE_PRESIDENT || this == PRESIDENT;
        }
    }

    record GuildView(
            String name,
            UUID owner,
            int level,
            long xp,
            int members,
            int maxMembers,
            String icon,
            List<String> presidents,
            long createdAt
    ) {
        public GuildView(String name, UUID owner, int level, long xp, int members, int maxMembers, String icon) {
            this(name, owner, level, xp, members, maxMembers, icon, List.of(), 0L);
        }

        public GuildView(String name, UUID owner, int level, long xp, int members, int maxMembers) {
            this(name, owner, level, xp, members, maxMembers, "WHITE_BANNER", List.of(), 0L);
        }
    }
}
