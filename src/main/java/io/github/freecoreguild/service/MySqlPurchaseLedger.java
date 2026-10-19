package io.github.freecoreguild.service;

import java.sql.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** MySQL-backed purchase counter implementing the shop read ledger. */
public final class MySqlPurchaseLedger implements ShopService.PurchaseLedger, PurchaseRecorder {
    private final DataSource dataSource;

    public MySqlPurchaseLedger(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** Convenience constructor for a MySqlGuildService data source. */
    public MySqlPurchaseLedger(MySqlGuildService service) {
        this(service.dataSource());
    }

    /** Returns units purchased in [fromInclusive, toInclusive], scoped to player/item. */
    @Override
    public int purchased(UUID player, String itemId, Instant fromInclusive, Instant toInclusive) {
        if (player == null || itemId == null || itemId.isBlank() || fromInclusive == null || toInclusive == null
                || toInclusive.isBefore(fromInclusive)) return 0;
        String sql = "SELECT COALESCE(SUM(purchased),0) FROM fc_guild_shop_limits "
                + "WHERE player_uuid=? AND item_id=? AND reset_at>=? AND reset_at<=?";
        try (Connection c = dataSource.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, player.toString());
            p.setString(2, itemId);
            p.setTimestamp(3, Timestamp.from(fromInclusive));
            p.setTimestamp(4, Timestamp.from(toInclusive));
            try (ResultSet r = p.executeQuery()) { return r.next() ? Math.max(0, r.getInt(1)) : 0; }
        } catch (SQLException e) {
            throw new IllegalStateException("Shop purchase lookup failed", e);
        }
    }

    /** Resolves the player's guild database id for purchase recording. */
    public long guildId(UUID player) {
        if (player == null) return 0;
        try (Connection c = dataSource.getConnection(); PreparedStatement p = c.prepareStatement("SELECT guild_id FROM fc_guild_members WHERE player_uuid=? LIMIT 1")) {
            p.setString(1, player.toString());
            try (ResultSet r = p.executeQuery()) { return r.next() ? r.getLong(1) : 0; }
        } catch (SQLException e) { throw new IllegalStateException("Guild lookup failed", e); }
    }

    /** Records units atomically, replacing a stale period and incrementing the current one. */
    @Override
    public boolean recordPurchase(long guildId, UUID player, String itemId, int amount, Instant windowStart) {
        if (guildId <= 0 || player == null || itemId == null || itemId.isBlank() || amount <= 0 || windowStart == null)
            return false;
        String sql = "INSERT INTO fc_guild_shop_limits "
                + "(guild_id,player_uuid,item_id,purchased,reset_at) VALUES (?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE purchased=IF(reset_at=VALUES(reset_at),purchased+VALUES(purchased),VALUES(purchased)), "
                + "reset_at=VALUES(reset_at)";
        try (Connection c = dataSource.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setLong(1, guildId); p.setString(2, player.toString()); p.setString(3, itemId);
            p.setInt(4, amount); p.setTimestamp(5, Timestamp.from(windowStart));
            return p.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Shop purchase record failed", e);
        }
    }
}
