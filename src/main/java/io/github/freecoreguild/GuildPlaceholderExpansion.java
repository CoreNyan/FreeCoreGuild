package io.github.freecoreguild;

import io.github.freecoreguild.lang.Lang;
import io.github.freecoreguild.api.GuildProvider;
import java.util.Locale;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/** Player guild display for chat, scoreboards and other PlaceholderAPI consumers. */
public final class GuildPlaceholderExpansion extends PlaceholderExpansion {
    private final FreeCoreGuildPlugin plugin;
    private final GuildProvider guilds;

    public GuildPlaceholderExpansion(FreeCoreGuildPlugin plugin, GuildProvider guilds) {
        this.plugin = plugin;
        this.guilds = guilds;
    }

    @Override
    public String getIdentifier() {
        return "freecoreguild";
    }

    @Override
    public String getAuthor() {
        return "FreeCore";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String parameters) {
        if (parameters == null) return null;
        return switch (parameters.toLowerCase(Locale.ROOT)) {
            case "name", "guild_name" -> player == null ? Lang.text("placeholders.no-guild")
                    : guilds.guildOf(player.getUniqueId()).map(GuildProvider.GuildView::name)
                            .filter(name -> !name.isBlank()).orElse(Lang.text("placeholders.no-guild"));
            default -> null;
        };
    }
}
