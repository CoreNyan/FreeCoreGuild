package io.github.freecoreguild.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.IntUnaryOperator;
import javax.sql.DataSource;

public final class MySqlFakePlayerStore {
    private final DataSource dataSource;
    private final IntUnaryOperator quotaFunction;

    public MySqlFakePlayerStore(DataSource dataSource, IntUnaryOperator quotaFunction) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.quotaFunction = quotaFunction != null ? quotaFunction : (lvl -> 0);
    }

    public MySqlFakePlayerStore(DataSource dataSource) {
        this(dataSource, lvl -> 0);
    }

    public MySqlFakePlayerStore(MySqlGuildService service) {
        this(service.dataSource(), lvl -> 0);
    }

    public boolean upsert(long guildId, int slot, String identifier, boolean active) {
        if (guildId <= 0 || slot < 0 || identifier == null || identifier.isBlank()) return false;
        String sql = "INSERT INTO fc_guild_fakeplayers (guild_id, slot, identifier, active) VALUES (?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE identifier=VALUES(identifier), active=VALUES(active)";
        try (Connection c = dataSource.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setLong(1, guildId);
            p.setInt(2, slot);
            p.setString(3, identifier);
            p.setInt(4, active ? 1 : 0);
            return p.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("FakePlayer record failed", e);
        }
    }

    public boolean delete(long guildId, int slot) {
        if (guildId <= 0 || slot < 0) return false;
        String sql = "DELETE FROM fc_guild_fakeplayers WHERE guild_id=? AND slot=?";
        try (Connection c = dataSource.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setLong(1, guildId);
            p.setInt(2, slot);
            return p.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("FakePlayer delete failed", e);
        }
    }

    public int countActive(long guildId) {
        if (guildId <= 0) return 0;
        String sql = "SELECT COUNT(*) FROM fc_guild_fakeplayers WHERE guild_id=? AND active=1";
        try (Connection c = dataSource.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setLong(1, guildId);
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? r.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("FakePlayer count failed", e);
        }
    }

    public long guildId(UUID player) {
        if (player == null) return 0;
        try (Connection c = dataSource.getConnection();
             PreparedStatement p = c.prepareStatement(
                "SELECT guild_id FROM fc_guild_members WHERE player_uuid=? LIMIT 1")) {
            p.setString(1, player.toString());
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? r.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Guild lookup failed", e);
        }
    }
}
