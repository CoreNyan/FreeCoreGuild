package io.github.freecoreguild.api;

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
        MEMBER("§0成员", "§7成员"),
        ELDER("§a长老", "§a长老"),
        VICE_PRESIDENT("§9副会长", "§b副会长"),
        PRESIDENT("§c会长", "§c会长");

        private final String chatDisplay;
        private final String menuDisplay;

        Role(String chatDisplay, String menuDisplay) {
            this.chatDisplay = chatDisplay;
            this.menuDisplay = menuDisplay;
        }

        public String getChatDisplay() {
            return chatDisplay;
        }

        public String getMenuDisplay() {
            return menuDisplay;
        }

        public boolean canOperateFakePlayers() {
            return this == VICE_PRESIDENT || this == PRESIDENT;
        }
    }

    record GuildView(String name, UUID owner, int level, long xp, int members, int maxMembers) {}
}
