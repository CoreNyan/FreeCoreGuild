package io.github.freecoreguild.service;

import io.github.freecoreguild.lang.Lang;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.freecoreguild.FreeCoreGuildPlugin;
import io.github.freecoreguild.api.GuildProvider.Role;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Location;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 具备全生命周期 MySQL 持久化与跨服实时同步的 GuildService 实现
 */
public final class MySqlGuildService extends MemoryGuildService {
    private final HikariDataSource dataSource;
    private final MySqlFakePlayerStore fakePlayerStore;

    public MySqlGuildService(NavigableMap<Integer, LevelConfig> levels, String jdbc, String user, String password, int poolSize, long leaveCooldownMillis) {
        super(levels, 1000L, 0.0, leaveCooldownMillis);
        HikariConfig c = new HikariConfig();
        String sanitizedJdbc = jdbc;
        if (sanitizedJdbc.contains("characterEncoding=utf8mb4")) {
            sanitizedJdbc = sanitizedJdbc.replace("characterEncoding=utf8mb4", "characterEncoding=UTF-8");
        } else if (!sanitizedJdbc.contains("characterEncoding=")) {
            sanitizedJdbc += (sanitizedJdbc.contains("?") ? "&" : "?") + "characterEncoding=UTF-8";
        }
        c.setJdbcUrl(sanitizedJdbc);
        c.setUsername(user);
        c.setPassword(password);
        c.setMaximumPoolSize(poolSize);
        c.setMinimumIdle(1);
        c.setConnectionTimeout(3000);
        c.setMaxLifetime(1800000);
        this.dataSource = new HikariDataSource(c);
        this.fakePlayerStore = new MySqlFakePlayerStore(dataSource, level -> {
            LevelConfig cfg = levels.floorEntry(level) == null ? null : levels.floorEntry(level).getValue();
            return cfg == null ? 0 : cfg.fakePlayerQuota();
        });
    }

    public void clearAllShopLimits() {
        // Finish the shared write before SHOP_REFRESH invalidates caches on other nodes.
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM fc_guild_shop_limits");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clear guild shop limits", e);
        }
    }

    public MySqlFakePlayerStore fakePlayerStore() { return fakePlayerStore; }
    public HikariDataSource dataSource() { return dataSource; }

    @Override
    public void initialize() {
        super.initialize();
        // 1. 初始化数据表结构
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS fc_guilds (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "name VARCHAR(32) UNIQUE NOT NULL," +
                    "owner_uuid CHAR(36) NOT NULL," +
                    "level INT NOT NULL DEFAULT 1," +
                    "prosperity BIGINT NOT NULL DEFAULT 0," +
                    "claimed_rewards TEXT NULL," +
                    "base_server VARCHAR(32) NULL," +
                    "base_world VARCHAR(64) NULL," +
                    "base_x DOUBLE NULL," +
                    "base_y DOUBLE NULL," +
                    "base_z DOUBLE NULL," +
                    "base_yaw FLOAT NULL," +
                    "base_pitch FLOAT NULL," +
                    "icon VARCHAR(64) DEFAULT 'WHITE_BANNER'," +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)");

            // 检查并兼容老表增加 icon 列
            try {
                s.executeUpdate("ALTER TABLE fc_guilds ADD COLUMN icon VARCHAR(64) DEFAULT 'WHITE_BANNER'");
            } catch (SQLException ignored) {}

            s.executeUpdate("CREATE TABLE IF NOT EXISTS fc_guild_members (" +
                    "guild_id BIGINT NOT NULL," +
                    "player_uuid CHAR(36) NOT NULL," +
                    "role VARCHAR(16) NOT NULL DEFAULT 'MEMBER'," +
                    "contribution BIGINT NOT NULL DEFAULT 0," +
                    "last_checkin DATE NULL," +
                    "PRIMARY KEY(guild_id, player_uuid)," +
                    "INDEX idx_player(player_uuid))");

            s.executeUpdate("CREATE TABLE IF NOT EXISTS fc_guild_applications (" +
                    "guild_id BIGINT NOT NULL," +
                    "player_uuid CHAR(36) NOT NULL," +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "PRIMARY KEY(guild_id, player_uuid))");

            s.executeUpdate("CREATE TABLE IF NOT EXISTS fc_guild_cooldowns (" +
                    "player_uuid CHAR(36) PRIMARY KEY," +
                    "left_at BIGINT NOT NULL)");

            s.executeUpdate("CREATE TABLE IF NOT EXISTS fc_guild_invites (" +
                    "target_uuid CHAR(36) NOT NULL," +
                    "guild_name VARCHAR(32) NOT NULL," +
                    "inviter_name VARCHAR(32) NOT NULL," +
                    "expire_at BIGINT NOT NULL," +
                    "PRIMARY KEY(target_uuid, guild_name))");

            s.executeUpdate("CREATE TABLE IF NOT EXISTS fc_guild_shop_limits (" +
                    "guild_id BIGINT NOT NULL," +
                    "player_uuid CHAR(36) NOT NULL," +
                    "item_id VARCHAR(64) NOT NULL," +
                    "purchased INT NOT NULL DEFAULT 0," +
                    "reset_at TIMESTAMP NULL," +
                    "PRIMARY KEY(guild_id, player_uuid, item_id))");

            s.executeUpdate("CREATE TABLE IF NOT EXISTS fc_guild_fakeplayers (" +
                    "guild_id BIGINT NOT NULL," +
                    "slot INT NOT NULL," +
                    "identifier VARCHAR(64) NOT NULL," +
                    "active TINYINT(1) NOT NULL DEFAULT 1," +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                    "PRIMARY KEY(guild_id, slot))");
        } catch (SQLException e) {
            throw new IllegalStateException("Guild database schema initialization failed", e);
        }

        // 2. 启动时全量加载已存在的数据
        loadAllFromDatabase();
    }

    /** Publish a complete database snapshot, including removals, only after every read succeeds. */
    public synchronized void loadAllFromDatabase() {
        Map<Long, Guild> idToGuild = new HashMap<>();
        Map<String, Guild> loadedGuilds = new HashMap<>();
        Map<UUID, String> loadedMembers = new HashMap<>();
        Map<String, Set<UUID>> loadedApplications = new HashMap<>();
        Map<UUID, Long> loadedCooldowns = new HashMap<>();
        boolean committed = false;
        try (Connection c = dataSource.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setAutoCommit(false);
            try {

            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT id, name, owner_uuid, level, prosperity, claimed_rewards, base_server, base_world, base_x, base_y, base_z, base_yaw, base_pitch, icon, created_at FROM fc_guilds")) {
                while (rs.next()) {
                    long gid = rs.getLong("id");
                    String name = rs.getString("name");
                    UUID owner = UUID.fromString(rs.getString("owner_uuid"));
                    int lvl = rs.getInt("level");
                    long xp = rs.getLong("prosperity");
                    String claimed = rs.getString("claimed_rewards");
                    String icon = rs.getString("icon");
                    java.sql.Timestamp cat = rs.getTimestamp("created_at");

                    Guild g = new Guild(name, owner);
                    g.databaseId = gid;
                    g.level = lvl;
                    g.xp = xp;
                    if (cat != null) {
                        g.createdAt = cat.getTime();
                    }
                    if (icon != null && !icon.isBlank()) {
                        g.icon = icon;
                    }
                    g.claimedUpgradeRewards.clear();
                    if (claimed != null && !claimed.isBlank()) {
                        for (String part : claimed.split(",")) {
                            try { g.claimedUpgradeRewards.add(Integer.parseInt(part.trim())); } catch (Exception ignored) {}
                        }
                    }

                    String bServer = rs.getString("base_server");
                    if (bServer != null && !bServer.isBlank()) {
                        g.baseLocation = new GuildBaseLocation(
                                bServer,
                                rs.getString("base_world"),
                                rs.getDouble("base_x"),
                                rs.getDouble("base_y"),
                                rs.getDouble("base_z"),
                                rs.getFloat("base_yaw"),
                                rs.getFloat("base_pitch")
                        );
                    } else {
                        g.baseLocation = null;
                    }

                    idToGuild.put(gid, g);
                    loadedGuilds.put(g.key, g);
                }
            }

            // 加载成员
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT guild_id, player_uuid, role, contribution, last_checkin FROM fc_guild_members")) {
                while (rs.next()) {
                    long gid = rs.getLong("guild_id");
                    Guild g = idToGuild.get(gid);
                    if (g == null) continue;
                    UUID pUid = UUID.fromString(rs.getString("player_uuid"));
                    String rStr = rs.getString("role");
                    Role r = Role.MEMBER;
                    try { r = Role.valueOf(rStr.toUpperCase(Locale.ROOT)); } catch (Exception ignored) {}
                    long contrib = rs.getLong("contribution");
                    java.sql.Date checkinDate = rs.getDate("last_checkin");

                    g.roles.put(pUid, r);
                    g.contributions.put(pUid, contrib);
                    if (checkinDate != null) g.checkins.put(pUid, checkinDate.toLocalDate());
                    loadedMembers.put(pUid, g.key);
                }
            }

            for (Guild g : idToGuild.values()) {
                if (!g.roles.containsKey(g.owner)) {
                    g.roles.put(g.owner, Role.PRESIDENT);
                }
                loadedMembers.put(g.owner, g.key);
                g.memberCount = g.roles.size();
            }

            // 加载申请
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT guild_id, player_uuid FROM fc_guild_applications")) {
                while (rs.next()) {
                    long gid = rs.getLong("guild_id");
                    Guild g = idToGuild.get(gid);
                    if (g == null) continue;
                    UUID applicant = UUID.fromString(rs.getString("player_uuid"));
                    loadedApplications.computeIfAbsent(g.key, k -> ConcurrentHashMap.newKeySet()).add(applicant);
                }
            }

            // 加载退会冷却
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT player_uuid, left_at FROM fc_guild_cooldowns")) {
                while (rs.next()) {
                    loadedCooldowns.put(UUID.fromString(rs.getString("player_uuid")), rs.getLong("left_at"));
                }
            }
                c.commit();
                committed = true;
            } catch (SQLException | RuntimeException e) {
                try { c.rollback(); } catch (SQLException rollbackError) { e.addSuppressed(rollbackError); }
                throw e;
            }
        } catch (SQLException | RuntimeException e) {
            Bukkit.getLogger().severe("[FreeCoreGuild] " + (committed ? "Guild snapshot read; closing connection failed: " : "Failed to load guilds from MySQL: ") + e.getMessage());
            if (!committed) return;
        }
        // Keep process-local fake-player reservations only for the same database identity.
        for (Guild loaded : loadedGuilds.values()) {
            Guild previous = guilds.get(loaded.key);
            if (previous != null && previous.databaseId == loaded.databaseId) {
                synchronized (previous) { loaded.fakePlayers.addAll(previous.fakePlayers); }
            }
        }
        guilds.clear();
        guilds.putAll(loadedGuilds);
        members.clear();
        members.putAll(loadedMembers);
        applications.clear();
        applications.putAll(loadedApplications);
        leaveTimestamps.clear();
        leaveTimestamps.putAll(loadedCooldowns);
    }

    @Override
    public synchronized List<GuildView> getAllGuilds() {
        // Opening the list also repairs a missed cross-server invalidation.
        loadAllFromDatabase();
        return super.getAllGuilds();
    }

    @Override
    public synchronized Optional<GuildView> find(String name) {
        return super.find(name);
    }

    /** 跨服穿透检查：实时对齐玩家最新的公会归属和职位状态 */
    private synchronized void refreshPlayerStatus(UUID player) {
        if (player == null) return;
        boolean reload = false;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT g.name, g.id, g.owner_uuid, g.created_at, g.level, g.prosperity, g.icon, m.role, m.contribution, m.last_checkin FROM fc_guild_members m JOIN fc_guilds g ON m.guild_id = g.id WHERE m.player_uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String actualGuildName = rs.getString("name").toLowerCase(Locale.ROOT);
                    String currentGuild = members.get(player);
                    Guild cachedGuild = currentGuild == null ? null : guilds.get(currentGuild.toLowerCase(Locale.ROOT));
                    long actualGuildId = rs.getLong("id");
                    String ownerStr = rs.getString("owner_uuid");
                    UUID actualOwner = ownerStr == null || ownerStr.isBlank() ? null : UUID.fromString(ownerStr.trim());
                    java.sql.Timestamp actualCreatedAt = rs.getTimestamp("created_at");
                    boolean identityChanged = cachedGuild == null
                            || cachedGuild.databaseId != actualGuildId
                            || !Objects.equals(cachedGuild.owner, actualOwner)
                            || (actualCreatedAt != null && cachedGuild.createdAt != actualCreatedAt.getTime());
                    String roleStr = rs.getString("role");
                    Role dbRole = Role.MEMBER;
                    try { dbRole = Role.valueOf(roleStr.toUpperCase(Locale.ROOT)); } catch (Exception ignored) {}

                    Guild g = guilds.get(actualGuildName);
                    if (g != null && !identityChanged) {
                        synchronized (g) {
                            if (actualOwner != null) g.owner = actualOwner;
                            if (actualCreatedAt != null) g.createdAt = actualCreatedAt.getTime();
                            g.xp = rs.getLong("prosperity");
                            g.level = rs.getInt("level");
                            String icon = rs.getString("icon");
                            if (icon != null && !icon.isBlank()) {
                                g.icon = icon;
                            }
                        }
                    }

                    if (identityChanged || currentGuild == null || !currentGuild.equalsIgnoreCase(actualGuildName) || g == null) {
                        reload = true;
                    } else {
                        // 快速对齐该玩家内存中的职位与贡献
                        synchronized (g) {
                            g.roles.put(player, dbRole);
                            g.contributions.put(player, rs.getLong("contribution"));
                            java.sql.Date lc = rs.getDate("last_checkin");
                            if (lc != null) {
                                g.checkins.put(player, lc.toLocalDate());
                            } else {
                                // 签到已同步落库，NULL 是管理员重置后的权威状态。
                                g.checkins.remove(player);
                            }
                        }
                    }
                } else {
                    // 数据库中已经没有该玩家的公会记录（玩家已在另一服务器退会或被踢）
                    if (members.containsKey(player)) reload = true;
                }
            }
        } catch (SQLException ignored) {}
        // Return the connection before borrowing for the snapshot (also works with a one-connection pool).
        if (reload) loadAllFromDatabase();
    }

    @Override
    public void reloadBaseLocationFromDb(String guildKey) {
        if (guildKey == null) return;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT base_server, base_world, base_x, base_y, base_z, base_yaw, base_pitch FROM fc_guilds WHERE LOWER(name) = ?")) {
            ps.setString(1, guildKey.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String bServer = rs.getString("base_server");
                    Guild g = guilds.get(guildKey.toLowerCase(Locale.ROOT));
                    if (g != null) {
                        synchronized (g) {
                            if (bServer != null && !bServer.isBlank()) {
                                g.baseLocation = new GuildBaseLocation(
                                        bServer,
                                        rs.getString("base_world"),
                                        rs.getDouble("base_x"),
                                        rs.getDouble("base_y"),
                                        rs.getDouble("base_z"),
                                        rs.getFloat("base_yaw"),
                                        rs.getFloat("base_pitch")
                                );
                            } else {
                                g.baseLocation = null;
                            }
                        }
                    }
                }
            }
        } catch (SQLException ignored) {}
    }

    @Override
    public synchronized Optional<GuildView> guildOf(UUID player) {
        if (player == null) return Optional.empty();
        refreshPlayerStatus(player);
        return super.guildOf(player);
    }

    @Override
    public synchronized Optional<Role> roleOf(UUID player) {
        if (player == null) return Optional.empty();
        refreshPlayerStatus(player);
        return super.roleOf(player);
    }

    @Override
    public synchronized List<MemberInfo> getMembers(UUID player) {
        if (player != null) {
            refreshPlayerStatus(player);
            Guild g = guildFor(player);
            if (g != null) {
                // 实时从数据库加载该公会所有成员最新信息，保证跨服升降职、改名、贡献、签到百分百同步
                try (Connection c = dataSource.getConnection()) {
                    long gid = getGuildId(c, g);
                    if (gid > 0) {
                        try (PreparedStatement ps = c.prepareStatement(
                                "SELECT player_uuid, role, contribution, last_checkin FROM fc_guild_members WHERE guild_id = ?")) {
                            ps.setLong(1, gid);
                            try (ResultSet rs = ps.executeQuery()) {
                                synchronized (g) {
                                    g.roles.clear();
                                    g.contributions.clear();
                                    g.checkins.clear();
                                    while (rs.next()) {
                                        UUID u = UUID.fromString(rs.getString("player_uuid"));
                                        String rStr = rs.getString("role");
                                        Role r = Role.MEMBER;
                                        try { r = Role.valueOf(rStr.toUpperCase(Locale.ROOT)); } catch (Exception ignored) {}
                                        g.roles.put(u, r);
                                        g.contributions.put(u, rs.getLong("contribution"));
                                        java.sql.Date lc = rs.getDate("last_checkin");
                                        if (lc != null) g.checkins.put(u, lc.toLocalDate());
                                        members.put(u, g.key);
                                    }
                                    if (!g.roles.containsKey(g.owner)) {
                                        g.roles.put(g.owner, Role.PRESIDENT);
                                    }
                                    members.put(g.owner, g.key);
                                    g.memberCount = g.roles.size();
                                }
                            }
                        }
                    }
                } catch (SQLException ignored) {}
            }
        }
        return super.getMembers(player);
    }

    @Override
    public synchronized Optional<GuildBaseLocation> getBaseLocation(UUID player) {
        if (player != null) {
            refreshPlayerStatus(player);
        }
        return super.getBaseLocation(player);
    }

    @Override
    public synchronized List<UUID> getPresidents(UUID player) {
        if (player != null) {
            refreshPlayerStatus(player);
        }
        return super.getPresidents(player);
    }

    @Override
    public long getLeaveCooldownRemainingMillis(UUID player) {
        if (player == null) return 0L;
        // Read shared state even when cached: another node may have cleared the cooldown.
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT left_at FROM fc_guild_cooldowns WHERE player_uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long leftAt = rs.getLong("left_at");
                    leaveTimestamps.put(player, leftAt);
                    long elapsed = System.currentTimeMillis() - leftAt;
                    if (elapsed < leaveCooldownMillis) {
                        return leaveCooldownMillis - elapsed;
                    } else {
                        try (PreparedStatement delete = c.prepareStatement("DELETE FROM fc_guild_cooldowns WHERE player_uuid = ?")) {
                            delete.setString(1, player.toString());
                            delete.executeUpdate();
                        }
                        super.clearLeaveCooldown(player);
                    }
                } else {
                    super.clearLeaveCooldown(player);
                }
            }
        } catch (SQLException e) {
            return super.getLeaveCooldownRemainingMillis(player);
        }
        return 0L;
    }

    @Override
    public void clearLeaveCooldown(UUID player) {
        if (player == null) return;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM fc_guild_cooldowns WHERE player_uuid = ?")) {
            ps.setString(1, player.toString());
            ps.executeUpdate();
            super.clearLeaveCooldown(player);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clear guild cooldown", e);
        }
    }

    @Override
    public void resetCheckinToday(UUID player) {
        if (player == null) return;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE fc_guild_members SET last_checkin = NULL WHERE player_uuid = ?")) {
            ps.setString(1, player.toString());
            ps.executeUpdate();
            super.resetCheckinToday(player);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to reset guild checkin", e);
        }
    }

    @Override
    public UUID resolvePlayerUuid(String playerName) {
        if (playerName == null || playerName.isBlank()) return null;
        playerName = playerName.trim();
        UUID local = super.resolvePlayerUuid(playerName);
        if (local != null) return local;

        // 跨服在 Redis 查找玩家 UUID (FreeCoreEssentials / BungeeCord 缓存)
        try {
            GuildRedisBridge bridge = FreeCoreGuildPlugin.getInstance().getRedisBridge();
            if (bridge != null) {
                String redisUuid = bridge.execute("GET", "fce:online:name:" + playerName.toLowerCase(Locale.ROOT) + ":uuid");
                if (redisUuid != null && !redisUuid.isBlank()) {
                    return UUID.fromString(redisUuid.trim());
                }
                redisUuid = bridge.execute("GET", "fce:name2uuid:" + playerName.toLowerCase(Locale.ROOT));
                if (redisUuid != null && !redisUuid.isBlank()) {
                    return UUID.fromString(redisUuid.trim());
                }
            }
        } catch (Exception ignored) {}

        // 1. 从当前库的 husksync_users 表中精确匹配 (不区分大小写)
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT uuid FROM husksync_users WHERE LOWER(username) = ? LIMIT 1")) {
            ps.setString(1, playerName.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return UUID.fromString(rs.getString("uuid"));
            }
        } catch (Exception ignored) {}

        // 2. 从跨库 freecore_permissions.luckperms_players 中查找 (不区分大小写)
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT uuid FROM freecore_permissions.luckperms_players WHERE LOWER(username) = ? LIMIT 1")) {
            ps.setString(1, playerName.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return UUID.fromString(rs.getString("uuid"));
            }
        } catch (Exception ignored) {}

        // 3. 从已有的公会成员名单缓存中查找 (不区分大小写)
        for (Guild g : guilds.values()) {
            synchronized (g) {
                for (UUID mUuid : g.roles.keySet()) {
                    OfflinePlayer op = Bukkit.getOfflinePlayer(mUuid);
                    if (op != null && op.getName() != null && op.getName().equalsIgnoreCase(playerName)) {
                        return mUuid;
                    }
                }
            }
        }

        // 4. 从 fce_players 中查找
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT uuid FROM fce_players WHERE LOWER(username) = ? LIMIT 1")) {
            ps.setString(1, playerName.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return UUID.fromString(rs.getString("uuid"));
            }
        } catch (Exception ignored) {}

        // Do not use getOfflinePlayer(name): it fabricates IDs for unknown names.
        return null;
    }

    @Override
    public boolean resetCheckinTodayByName(String playerName) {
        UUID uid = resolvePlayerUuid(playerName);
        if (uid == null) return false;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT player_uuid FROM fc_guild_members WHERE player_uuid = ?")) {
            ps.setString(1, uid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
            }
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[FreeCoreGuild] Reset checkin lookup failed: " + e.getMessage());
            return false;
        }
        try {
            resetCheckinToday(uid);
            return true;
        } catch (IllegalStateException e) {
            Bukkit.getLogger().warning("[FreeCoreGuild] Reset checkin failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean clearLeaveCooldownByName(String playerName) {
        UUID uid = resolvePlayerUuid(playerName);
        if (uid == null) return false;
        try {
            clearLeaveCooldown(uid);
            return true;
        } catch (IllegalStateException e) {
            Bukkit.getLogger().warning("[FreeCoreGuild] Clear cooldown failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public synchronized boolean addInvite(UUID targetPlayer, String guildName, String inviterName) {
        if (targetPlayer == null || guildName == null || guildName.isBlank() || inviterName == null) return false;
        Guild g = guilds.get(guildName.trim().toLowerCase(Locale.ROOT));
        if (g == null) return false;
        boolean committed = false;
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                if (getGuildId(c, g) <= 0L) { c.rollback(); return false; }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO fc_guild_invites (target_uuid, guild_name, inviter_name, expire_at) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE inviter_name = VALUES(inviter_name), expire_at = VALUES(expire_at)")) {
                    ps.setString(1, targetPlayer.toString());
                    ps.setString(2, g.name);
                    ps.setString(3, inviterName);
                    ps.setLong(4, System.currentTimeMillis() + INVITE_TTL_MILLIS);
                    ps.executeUpdate();
                }
                c.commit();
                committed = true;
            } catch (SQLException e) {
                try { c.rollback(); } catch (SQLException rollbackError) { e.addSuppressed(rollbackError); }
                throw e;
            }
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[FreeCoreGuild] " + (committed ? "Invite committed; closing connection failed: " : "Save invite failed: ") + e.getMessage());
        }
        return committed;
    }

    @Override
    public List<GuildInvite> getInvitesFor(UUID targetPlayer) {
        if (targetPlayer == null) return List.of();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT guild_name, inviter_name, expire_at FROM fc_guild_invites WHERE target_uuid = ? AND expire_at > ? ORDER BY expire_at DESC, guild_name ASC")) {
            ps.setString(1, targetPlayer.toString());
            ps.setLong(2, System.currentTimeMillis());
            List<GuildInvite> invites = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) invites.add(new GuildInvite(rs.getString(1), rs.getString(2), rs.getLong(3)));
            }
            return invites;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read guild invitations", e);
        }
    }

    @Override
    public boolean hasInvite(UUID targetPlayer, String guildName) {
        return guildName != null && getInvitesFor(targetPlayer).stream()
                .anyMatch(inv -> inv.guildName().equalsIgnoreCase(guildName.trim()));
    }

    @Override
    public void removeInvite(UUID targetPlayer, String guildName) {
        if (targetPlayer == null || guildName == null) return;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM fc_guild_invites WHERE target_uuid = ? AND LOWER(guild_name) = ?")) {
            ps.setString(1, targetPlayer.toString());
            ps.setString(2, guildName.trim().toLowerCase(Locale.ROOT));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to remove guild invitation", e);
        }
    }

    @Override
    public void clearAllInvitesFor(UUID targetPlayer) {
        if (targetPlayer == null) return;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM fc_guild_invites WHERE target_uuid = ?")) {
            ps.setString(1, targetPlayer.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clear guild invitations", e);
        }
    }

    @Override
    public synchronized boolean joinDirectly(UUID player, String guildName) {
        return joinPersisted(player, guildName, false);
    }

    @Override
    public synchronized boolean acceptInvite(UUID player, String guildName) {
        return joinPersisted(player, guildName, true);
    }

    private boolean joinPersisted(UUID player, String guildName, boolean requireInvite) {
        if (player == null || guildName == null || guildName.isBlank()) return false;
        String key = guildName.trim().toLowerCase(Locale.ROOT);
        // The database is authoritative; a stale local cache must not block a valid join.
        boolean committed = false;
        String guildDisplayName = null;
        UUID guildOwner = null;
        int guildLevel = 1;
        long prosperity = 0L;
        String icon = null;
        int memberCountAfter = 0;
        long persistedGuildId = 0L;
        long guildCreatedAt = 0L;
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                long gid;
                try (PreparedStatement ps = c.prepareStatement("SELECT id, name, owner_uuid, level, prosperity, icon, created_at FROM fc_guilds WHERE LOWER(name) = ? FOR UPDATE")) {
                    ps.setString(1, key);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) { c.rollback(); return false; }
                        gid = rs.getLong("id");
                        persistedGuildId = gid;
                        java.sql.Timestamp timestamp = rs.getTimestamp("created_at");
                        if (timestamp != null) guildCreatedAt = timestamp.getTime();
                        guildDisplayName = rs.getString("name");
                        guildOwner = UUID.fromString(rs.getString("owner_uuid"));
                        guildLevel = rs.getInt("level");
                        prosperity = rs.getLong("prosperity");
                        icon = rs.getString("icon");
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("SELECT guild_id FROM fc_guild_members WHERE player_uuid = ? FOR UPDATE")) {
                    ps.setString(1, player.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) { c.rollback(); return false; }
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("SELECT left_at FROM fc_guild_cooldowns WHERE player_uuid = ? FOR UPDATE")) {
                    ps.setString(1, player.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next() && System.currentTimeMillis() - rs.getLong(1) < leaveCooldownMillis) {
                            c.rollback(); return false;
                        }
                    }
                }
                if (requireInvite) {
                    try (PreparedStatement ps = c.prepareStatement("SELECT expire_at FROM fc_guild_invites WHERE target_uuid = ? AND LOWER(guild_name) = ? FOR UPDATE")) {
                        ps.setString(1, player.toString());
                        ps.setString(2, key);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next() || rs.getLong(1) <= System.currentTimeMillis()) { c.rollback(); return false; }
                        }
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM fc_guild_members WHERE guild_id = ?")) {
                    ps.setLong(1, gid);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        int count = rs.getInt(1);
                        if (count >= level(guildLevel).maxMembers()) { c.rollback(); return false; }
                        memberCountAfter = count + 1;
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO fc_guild_members (guild_id, player_uuid, role, contribution) VALUES (?, ?, 'MEMBER', 0)")) {
                    ps.setLong(1, gid);
                    ps.setString(2, player.toString());
                    if (ps.executeUpdate() != 1) throw new SQLException("Member was not inserted");
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM fc_guild_applications WHERE player_uuid = ?")) {
                    ps.setString(1, player.toString());
                    ps.executeUpdate();
                }
                // Consume all invitations in the same transaction as the successful membership write.
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM fc_guild_invites WHERE target_uuid = ?")) {
                    ps.setString(1, player.toString());
                    ps.executeUpdate();
                }
                c.commit();
                committed = true;
            } catch (SQLException e) {
                try { c.rollback(); } catch (SQLException rollbackError) { e.addSuppressed(rollbackError); }
                throw e;
            }
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[FreeCoreGuild] " + (committed ? "Join committed; closing connection failed: " : "Join guild failed: ") + e.getMessage());
            if (!committed) return false;
        }
        if (!committed) return false;
        try {
            Guild g = guilds.get(key);
            if (g == null) {
                g = new Guild(guildDisplayName, guildOwner);
                guilds.put(key, g);
            }
            synchronized (g) {
                g.databaseId = persistedGuildId;
                if (guildCreatedAt != 0L) g.createdAt = guildCreatedAt;
                g.owner = guildOwner;
                g.level = guildLevel;
                g.xp = prosperity;
                if (icon != null && !icon.isBlank()) g.icon = icon;
                members.put(player, g.key);
                g.roles.put(player, Role.MEMBER);
                g.memberCount = memberCountAfter;
            }
        } catch (RuntimeException cacheError) {
            Bukkit.getLogger().warning("[FreeCoreGuild] Join committed but local cache refresh failed: " + cacheError.getMessage());
            try { loadAllFromDatabase(); } catch (RuntimeException reloadError) { cacheError.addSuppressed(reloadError); }
        }
        for (Set<UUID> pending : applications.values()) pending.remove(player);
        return true;
    }

    @Override
    public boolean setGuildIcon(UUID operator, String iconMaterial) {
        boolean ok = super.setGuildIcon(operator, iconMaterial);
        if (!ok) return false;
        Guild g = guildFor(operator);
        if (g == null) return true;
        // 同步直接更新数据库，避免异步延迟导致紧接着读取旧数据
        try (Connection c = dataSource.getConnection()) {
            long gid = getGuildId(c, g);
            if (gid > 0) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE fc_guilds SET icon = ? WHERE id = ?")) {
                    ps.setString(1, iconMaterial.toUpperCase(Locale.ROOT));
                    ps.setLong(2, gid);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException ignored) {}
        return true;
    }

    @Override
    public synchronized int setGuildProsperity(String guildName, long newProsperity) {
        if (guildName == null || newProsperity < 0) return -1;
        String key = guildName.trim().toLowerCase(Locale.ROOT);
        Guild g = guilds.get(key);
        if (g == null) {
            loadAllFromDatabase();
            g = guilds.get(key);
        }
        if (g == null) return -1;
        synchronized (g) {
            long previousXp = g.xp;
            int previousLevel = g.level;
            int newLevel = super.setGuildProsperity(key, newProsperity);
            try (Connection c = dataSource.getConnection()) {
                long gid = getGuildId(c, g);
                if (gid <= 0) throw new SQLException("Guild no longer exists");
                try (PreparedStatement ps = c.prepareStatement("UPDATE fc_guilds SET prosperity = ?, level = ? WHERE id = ?")) {
                    ps.setLong(1, g.xp);
                    ps.setInt(2, newLevel);
                    ps.setLong(3, gid);
                    ps.executeUpdate();
                }
                return newLevel;
            } catch (SQLException e) {
                g.xp = previousXp;
                g.level = previousLevel;
                Bukkit.getLogger().warning("[FreeCoreGuild] Set prosperity failed: " + e.getMessage());
                return -1;
            }
        }
    }

    private long getGuildId(Connection c, Guild guild) throws SQLException {
        if (guild.databaseId <= 0L) return -1L;
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM fc_guilds WHERE id = ? FOR UPDATE")) {
            ps.setLong(1, guild.databaseId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong("id");
            }
        }
        return -1;
    }

    @Override
    public synchronized boolean create(UUID owner, String name) {
        // Refresh first so a missed deletion cannot reserve a name forever in local cache.
        loadAllFromDatabase();
        boolean ok = super.create(owner, name);
        if (!ok) return false;
        Guild g = guildFor(owner);
        long gid = 0L;
        long createdAt = g.createdAt;
        boolean committed = false;
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO fc_guilds (name, owner_uuid, level, prosperity) VALUES (?, ?, 1, 0)", Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, name.trim());
                    ps.setString(2, owner.toString());
                    if (ps.executeUpdate() != 1) throw new SQLException("Guild was not created");
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (!rs.next()) throw new SQLException("Guild identity was not returned");
                        gid = rs.getLong(1);
                    }
                }
                try (PreparedStatement mps = c.prepareStatement("INSERT INTO fc_guild_members (guild_id, player_uuid, role, contribution) VALUES (?, ?, 'PRESIDENT', 0)")) {
                    mps.setLong(1, gid);
                    mps.setString(2, owner.toString());
                    if (mps.executeUpdate() != 1) throw new SQLException("Guild owner was not saved");
                }
                try (PreparedStatement ps = c.prepareStatement("SELECT created_at FROM fc_guilds WHERE id = ?")) {
                    ps.setLong(1, gid);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next() && rs.getTimestamp(1) != null) createdAt = rs.getTimestamp(1).getTime();
                    }
                }
                c.commit();
                committed = true;
            } catch (SQLException e) {
                try { c.rollback(); } catch (SQLException rollbackError) { e.addSuppressed(rollbackError); }
                throw e;
            }
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[FreeCoreGuild] " + (committed ? "Create committed; closing connection failed: " : "MySQL create guild failed: ") + e.getMessage());
            if (!committed) {
                guilds.remove(g.key, g);
                members.remove(owner, g.key);
                applications.remove(g.key);
                return false;
            }
        }
        g.databaseId = gid;
        g.createdAt = createdAt;
        return true;
    }

    @Override
    public synchronized List<UUID> applications(UUID player) {
        if (player == null) return List.of();
        Guild g = guildFor(player);
        if (g == null) return List.of();
        // 实时从 MySQL 加载最新的申请列表，保证跨服数据一致
        try (Connection c = dataSource.getConnection()) {
            long gid = getGuildId(c, g);
            if (gid > 0) {
                try (PreparedStatement ps = c.prepareStatement("SELECT player_uuid FROM fc_guild_applications WHERE guild_id = ? ORDER BY created_at ASC")) {
                    ps.setLong(1, gid);
                    try (ResultSet rs = ps.executeQuery()) {
                        Set<UUID> set = new HashSet<>();
                        while (rs.next()) {
                            set.add(UUID.fromString(rs.getString("player_uuid")));
                        }
                        synchronized (this) {
                            applications.put(g.key, set);
                        }
                    }
                }
            }
        } catch (SQLException ignored) {}
        return super.applications(player);
    }

    @Override
    public synchronized boolean approve(UUID operator, UUID applicant) {
        boolean ok = super.approve(operator, applicant);
        if (!ok) return false;
        Guild g = guildFor(operator);
        if (g == null) return true;
        Bukkit.getScheduler().runTaskAsynchronously(FreeCoreGuildPlugin.getInstance(), () -> {
            try (Connection c = dataSource.getConnection()) {
                c.setAutoCommit(false);
                long gid = getGuildId(c, g);
                if (gid > 0) {
                    try (PreparedStatement ps = c.prepareStatement("INSERT INTO fc_guild_members (guild_id, player_uuid, role, contribution) VALUES (?, ?, 'MEMBER', 0) ON DUPLICATE KEY UPDATE role='MEMBER'")) {
                        ps.setLong(1, gid);
                        ps.setString(2, applicant.toString());
                        ps.executeUpdate();
                    }
                    try (PreparedStatement dps = c.prepareStatement("DELETE FROM fc_guild_applications WHERE guild_id = ? AND player_uuid = ?")) {
                        dps.setLong(1, gid);
                        dps.setString(2, applicant.toString());
                        dps.executeUpdate();
                    }
                }
                c.commit();
            } catch (SQLException e) {
                Bukkit.getLogger().warning("[FreeCoreGuild] MySQL sync approve failed: " + e.getMessage());
            }
        });
        return true;
    }

    @Override
    public synchronized boolean reject(UUID operator, UUID applicant) {
        boolean ok = super.reject(operator, applicant);
        Guild g = guildFor(operator);
        if (g == null) return ok;
        Bukkit.getScheduler().runTaskAsynchronously(FreeCoreGuildPlugin.getInstance(), () -> {
            try (Connection c = dataSource.getConnection()) {
                c.setAutoCommit(false);
                long gid = getGuildId(c, g);
                if (gid > 0) {
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM fc_guild_applications WHERE guild_id = ? AND player_uuid = ?")) {
                        ps.setLong(1, gid);
                        ps.setString(2, applicant.toString());
                        ps.executeUpdate();
                    }
                }
                c.commit();
            } catch (SQLException e) { Bukkit.getLogger().warning("[FreeCoreGuild] Deferred guild write failed: " + e.getMessage()); }
        });
        return ok;
    }

    @Override
    public synchronized ApplyResult applyWithReason(UUID player, String guildName) {
        ApplyResult res = super.applyWithReason(player, guildName);
        if (!res.success()) return res;
        Guild g = guilds.get(guildName.trim().toLowerCase(Locale.ROOT));
        if (g == null) return res;
        Bukkit.getScheduler().runTaskAsynchronously(FreeCoreGuildPlugin.getInstance(), () -> {
            try (Connection c = dataSource.getConnection()) {
                c.setAutoCommit(false);
                long gid = getGuildId(c, g);
                if (gid > 0) {
                    try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO fc_guild_applications (guild_id, player_uuid) VALUES (?, ?)")) {
                        ps.setLong(1, gid);
                        ps.setString(2, player.toString());
                        ps.executeUpdate();
                    }
                }
                c.commit();
            } catch (SQLException e) { Bukkit.getLogger().warning("[FreeCoreGuild] Deferred guild write failed: " + e.getMessage()); }
        });
        return res;
    }

    @Override
    public synchronized boolean hasCheckedInToday(UUID player) {
        if (player == null) return false;
        refreshPlayerStatus(player);
        return super.hasCheckedInToday(player);
    }

    @Override
    public synchronized CheckinResult checkinPlayer(UUID player) {
        if (player == null) return new CheckinResult(false, Lang.text("results.invalid-player"), 0, 0, 0);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        boolean weekend = today.getDayOfWeek() == java.time.DayOfWeek.SATURDAY || today.getDayOfWeek() == java.time.DayOfWeek.SUNDAY;
        long reward = weekend ? 3000L : 1000L;
        int goldAmount = weekend ? 30 : 10;
        boolean committed = false;
        long newXp = 0L, newContribution = 0L;
        int newLevel = 1;
        String guildName = null;
        UUID owner = null;
        long persistedGuildId = 0L;
        long guildCreatedAt = 0L;
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                long gid;
                try (PreparedStatement ps = c.prepareStatement("SELECT guild_id FROM fc_guild_members WHERE player_uuid = ?")) {
                    ps.setString(1, player.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) { c.rollback(); return new CheckinResult(false, Lang.text("results.checkin.no-guild"), 0, 0, 0); }
                        gid = rs.getLong(1);
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("SELECT name, owner_uuid, prosperity, level, created_at FROM fc_guilds WHERE id = ? FOR UPDATE")) {
                    ps.setLong(1, gid);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) { c.rollback(); return new CheckinResult(false, Lang.text("results.checkin.guild-missing"), 0, 0, 0); }
                        persistedGuildId = gid;
                        java.sql.Timestamp timestamp = rs.getTimestamp("created_at");
                        if (timestamp != null) guildCreatedAt = timestamp.getTime();
                        guildName = rs.getString("name");
                        owner = UUID.fromString(rs.getString("owner_uuid"));
                        newXp = Math.addExact(rs.getLong("prosperity"), reward);
                        newLevel = rs.getInt("level");
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("SELECT contribution, last_checkin FROM fc_guild_members WHERE guild_id = ? AND player_uuid = ? FOR UPDATE")) {
                    ps.setLong(1, gid);
                    ps.setString(2, player.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) { c.rollback(); return new CheckinResult(false, Lang.text("results.checkin.no-guild"), 0, 0, 0); }
                        java.sql.Date checked = rs.getDate("last_checkin");
                        if (checked != null && today.equals(checked.toLocalDate())) { c.rollback(); return new CheckinResult(false, Lang.text("results.checkin.already"), 0, 0, 0); }
                        newContribution = Math.addExact(rs.getLong("contribution"), reward);
                    }
                }
                while (true) {
                    Map.Entry<Integer, LevelConfig> next = levels.higherEntry(newLevel);
                    if (next == null || newXp < next.getValue().requiredXp()) break;
                    newLevel = next.getKey();
                }
                try (PreparedStatement ps = c.prepareStatement("UPDATE fc_guilds SET prosperity = ?, level = ? WHERE id = ?")) {
                    ps.setLong(1, newXp); ps.setInt(2, newLevel); ps.setLong(3, gid);
                    if (ps.executeUpdate() != 1) throw new SQLException("Guild checkin reward was not saved");
                }
                try (PreparedStatement ps = c.prepareStatement("UPDATE fc_guild_members SET contribution = ?, last_checkin = ? WHERE guild_id = ? AND player_uuid = ?")) {
                    ps.setLong(1, newContribution); ps.setDate(2, java.sql.Date.valueOf(today));
                    ps.setLong(3, gid); ps.setString(4, player.toString());
                    if (ps.executeUpdate() != 1) throw new SQLException("Member checkin was not saved");
                }
                c.commit();
                committed = true;
            } catch (SQLException | ArithmeticException e) {
                try { c.rollback(); } catch (SQLException rollbackError) { e.addSuppressed(rollbackError); }
                throw e;
            }
        } catch (SQLException | ArithmeticException e) {
            Bukkit.getLogger().warning("[FreeCoreGuild] " + (committed ? "Checkin committed; closing connection failed: " : "Checkin failed: ") + e.getMessage());
            if (!committed) return new CheckinResult(false, Lang.text("results.checkin.save-failed"), 0, 0, 0);
        }
        try {
            String key = guildName.toLowerCase(Locale.ROOT);
            Guild g = guilds.get(key);
            if (g == null) { g = new Guild(guildName, owner); guilds.put(key, g); }
            synchronized (g) {
                g.databaseId = persistedGuildId;
                if (guildCreatedAt != 0L) g.createdAt = guildCreatedAt;
                g.xp = newXp; g.level = newLevel;
                g.contributions.put(player, newContribution);
                g.checkins.put(player, today);
                members.put(player, key);
            }
        } catch (RuntimeException cacheError) {
            Bukkit.getLogger().warning("[FreeCoreGuild] Checkin committed but cache refresh failed: " + cacheError.getMessage());
            try { loadAllFromDatabase(); } catch (RuntimeException reloadError) { cacheError.addSuppressed(reloadError); }
        }
        return new CheckinResult(true, Lang.text("results.checkin.success"), 0.0, reward, goldAmount);
    }

    @Override
    public synchronized int donateAndCheckAutoUpgrade(UUID player, long prosperityToAdd) {
        if (player == null || prosperityToAdd <= 0) return -1;
        boolean committed = false;
        int upgradedTo = 0;
        int newLevel = 1;
        long newXp = 0L;
        long newContribution = 0L;
        String guildName = null;
        UUID owner = null;
        long persistedGuildId = 0L;
        long guildCreatedAt = 0L;
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                long gid;
                // Resolve without locking the member first: all guild mutations lock the guild before members.
                try (PreparedStatement ps = c.prepareStatement("SELECT guild_id FROM fc_guild_members WHERE player_uuid = ?")) {
                    ps.setString(1, player.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) { c.rollback(); return -1; }
                        gid = rs.getLong(1);
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("SELECT name, owner_uuid, prosperity, level, created_at FROM fc_guilds WHERE id = ? FOR UPDATE")) {
                    ps.setLong(1, gid);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) { c.rollback(); return -1; }
                        persistedGuildId = gid;
                        java.sql.Timestamp timestamp = rs.getTimestamp("created_at");
                        if (timestamp != null) guildCreatedAt = timestamp.getTime();
                        guildName = rs.getString("name");
                        owner = UUID.fromString(rs.getString("owner_uuid"));
                        newXp = Math.addExact(rs.getLong("prosperity"), prosperityToAdd);
                        newLevel = rs.getInt("level");
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("SELECT contribution FROM fc_guild_members WHERE guild_id = ? AND player_uuid = ? FOR UPDATE")) {
                    ps.setLong(1, gid);
                    ps.setString(2, player.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) { c.rollback(); return -1; }
                        newContribution = Math.addExact(rs.getLong(1), prosperityToAdd);
                    }
                }
                while (true) {
                    Map.Entry<Integer, LevelConfig> next = levels.higherEntry(newLevel);
                    if (next == null || newXp < next.getValue().requiredXp()) break;
                    newLevel = next.getKey();
                    upgradedTo = newLevel;
                }
                try (PreparedStatement ps = c.prepareStatement("UPDATE fc_guilds SET prosperity = ?, level = ? WHERE id = ?")) {
                    ps.setLong(1, newXp);
                    ps.setInt(2, newLevel);
                    ps.setLong(3, gid);
                    if (ps.executeUpdate() != 1) throw new SQLException("Guild donation was not saved");
                }
                try (PreparedStatement ps = c.prepareStatement("UPDATE fc_guild_members SET contribution = ? WHERE guild_id = ? AND player_uuid = ?")) {
                    ps.setLong(1, newContribution);
                    ps.setLong(2, gid);
                    ps.setString(3, player.toString());
                    if (ps.executeUpdate() != 1) throw new SQLException("Member contribution was not saved");
                }
                c.commit();
                committed = true;
            } catch (SQLException | ArithmeticException e) {
                try { c.rollback(); } catch (SQLException rollbackError) { e.addSuppressed(rollbackError); }
                throw e;
            }
        } catch (SQLException | ArithmeticException e) {
            Bukkit.getLogger().warning("[FreeCoreGuild] " + (committed ? "Donation committed; closing connection failed: " : "Donation failed: ") + e.getMessage());
            if (!committed) return -1;
        }
        try {
            String key = guildName.toLowerCase(Locale.ROOT);
            Guild g = guilds.get(key);
            if (g == null) {
                g = new Guild(guildName, owner);
                guilds.put(key, g);
            }
            synchronized (g) {
                g.databaseId = persistedGuildId;
                if (guildCreatedAt != 0L) g.createdAt = guildCreatedAt;
                g.xp = newXp;
                g.level = newLevel;
                g.contributions.put(player, newContribution);
                members.put(player, key);
            }
        } catch (RuntimeException cacheError) {
            Bukkit.getLogger().warning("[FreeCoreGuild] Donation committed but cache refresh failed: " + cacheError.getMessage());
            try { loadAllFromDatabase(); } catch (RuntimeException reloadError) { cacheError.addSuppressed(reloadError); }
        }
        return upgradedTo;
    }

    @Override
    public PromoteResult promoteWithReason(UUID operator, UUID target, Role requested) {
        PromoteResult res = super.promoteWithReason(operator, target, requested);
        if (!res.success()) return res;
        syncRoleToDb(target, res.newRole());
        return res;
    }

    @Override
    public boolean promote(UUID operator, UUID target, Role requested) {
        return promoteWithReason(operator, target, requested).success();
    }

    @Override
    public boolean demote(UUID operator, UUID target) {
        boolean ok = super.demote(operator, target);
        if (!ok) return false;
        roleOf(target).ifPresent(r -> syncRoleToDb(target, r));
        return true;
    }

    private void syncRoleToDb(UUID player, Role role) {
        Guild g = guildFor(player);
        if (g == null) return;
        try (Connection c = dataSource.getConnection()) {
            long gid = getGuildId(c, g);
            if (gid > 0) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE fc_guild_members SET role = ? WHERE guild_id = ? AND player_uuid = ?")) {
                    ps.setString(1, role.name());
                    ps.setLong(2, gid);
                    ps.setString(3, player.toString());
                    ps.executeUpdate();
                }
            }
        } catch (SQLException ignored) {}
    }

    @Override
    public TransferResult transferWithReason(UUID operator, UUID target) {
        TransferResult res = super.transferWithReason(operator, target);
        if (!res.success()) return res;
        Guild g = guildFor(operator);
        if (g == null) return res;
        Bukkit.getScheduler().runTaskAsynchronously(FreeCoreGuildPlugin.getInstance(), () -> {
            try (Connection c = dataSource.getConnection()) {
                c.setAutoCommit(false);
                long gid = getGuildId(c, g);
                if (gid > 0) {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE fc_guilds SET owner_uuid = ? WHERE id = ?")) {
                        ps.setString(1, target.toString());
                        ps.setLong(2, gid);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = c.prepareStatement("UPDATE fc_guild_members SET role = 'PRESIDENT' WHERE guild_id = ? AND player_uuid = ?")) {
                        ps.setLong(1, gid);
                        ps.setString(2, target.toString());
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = c.prepareStatement("UPDATE fc_guild_members SET role = 'VICE_PRESIDENT' WHERE guild_id = ? AND player_uuid = ?")) {
                        ps.setLong(1, gid);
                        ps.setString(2, operator.toString());
                        ps.executeUpdate();
                    }
                }
                c.commit();
            } catch (SQLException e) { Bukkit.getLogger().warning("[FreeCoreGuild] Deferred guild write failed: " + e.getMessage()); }
        });
        return res;
    }

    @Override
    public synchronized boolean kick(UUID operator, UUID target) {
        if (operator == null || target == null || operator.equals(target)) return false;
        refreshPlayerStatus(operator);
        refreshPlayerStatus(target);
        Guild g = guildFor(operator);
        if (g == null || g != guildFor(target)) return false;
        long leftAt = System.currentTimeMillis();
        boolean committed = false;
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                long gid;
                String owner;
                try (PreparedStatement ps = c.prepareStatement("SELECT id, owner_uuid FROM fc_guilds WHERE LOWER(name) = ? FOR UPDATE")) {
                    ps.setString(1, g.key);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) { c.rollback(); return false; }
                        gid = rs.getLong(1);
                        owner = rs.getString(2);
                    }
                }
                if (target.toString().equalsIgnoreCase(owner)) { c.rollback(); return false; }
                Map<UUID, Role> roles = new HashMap<>();
                try (PreparedStatement ps = c.prepareStatement("SELECT player_uuid, role FROM fc_guild_members WHERE guild_id = ? AND player_uuid IN (?, ?) FOR UPDATE")) {
                    ps.setLong(1, gid);
                    ps.setString(2, operator.toString());
                    ps.setString(3, target.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) roles.put(UUID.fromString(rs.getString(1)), Role.valueOf(rs.getString(2)));
                    }
                }
                Role actor = roles.get(operator), victim = roles.get(target);
                if (operator.toString().equalsIgnoreCase(owner)) actor = Role.PRESIDENT;
                if (actor == null || victim == null || actor.ordinal() <= victim.ordinal()) { c.rollback(); return false; }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM fc_guild_members WHERE guild_id = ? AND player_uuid = ?")) {
                    ps.setLong(1, gid);
                    ps.setString(2, target.toString());
                    if (ps.executeUpdate() != 1) throw new SQLException("Member was not removed");
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO fc_guild_cooldowns (player_uuid, left_at) VALUES (?, ?) ON DUPLICATE KEY UPDATE left_at = VALUES(left_at)")) {
                    ps.setString(1, target.toString());
                    ps.setLong(2, leftAt);
                    ps.executeUpdate();
                }
                c.commit();
                committed = true;
            } catch (SQLException e) {
                try { c.rollback(); } catch (SQLException rollbackError) { e.addSuppressed(rollbackError); }
                throw e;
            }
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[FreeCoreGuild] " + (committed ? "Kick committed; closing connection failed: " : "Kick member failed: ") + e.getMessage());
            if (!committed) return false;
        }
        synchronized (g) {
            members.remove(target, g.key);
            g.roles.remove(target);
            g.checkins.remove(target);
            g.contributions.remove(target);
            g.memberCount = g.roles.size();
            leaveTimestamps.put(target, leftAt);
        }
        return true;
    }

    @Override
    public boolean leave(UUID player) {
        Guild g = guildFor(player);
        boolean ok = super.leave(player);
        if (!ok || g == null) return false;
        Bukkit.getScheduler().runTaskAsynchronously(FreeCoreGuildPlugin.getInstance(), () -> {
            try (Connection c = dataSource.getConnection()) {
                c.setAutoCommit(false);
                long gid = getGuildId(c, g);
                if (gid > 0) {
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM fc_guild_members WHERE guild_id = ? AND player_uuid = ?")) {
                        ps.setLong(1, gid);
                        ps.setString(2, player.toString());
                        ps.executeUpdate();
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO fc_guild_cooldowns (player_uuid, left_at) VALUES (?, ?) ON DUPLICATE KEY UPDATE left_at = ?")) {
                    ps.setString(1, player.toString());
                    ps.setLong(2, System.currentTimeMillis());
                    ps.setLong(3, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) { Bukkit.getLogger().warning("[FreeCoreGuild] Deferred guild write failed: " + e.getMessage()); }
        });
        return true;
    }

    @Override
    public synchronized long guildIdentity(UUID player) {
        Guild g = guildFor(player);
        return g == null ? 0L : g.databaseId;
    }

    @Override
    public synchronized boolean disband(UUID player) {
        return disband(player, guildIdentity(player));
    }

    @Override
    public synchronized boolean disband(UUID player, long expectedIdentity) {
        if (player == null || expectedIdentity <= 0L) return false;
        Guild g = guildFor(player);
        if (g == null || g.databaseId != expectedIdentity || !player.equals(g.owner)) return false;
        boolean committed = false;
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                // Serialize with joins/checkins/donations and validate the original guild, not its name.
                try (PreparedStatement ps = c.prepareStatement("SELECT owner_uuid FROM fc_guilds WHERE id = ? FOR UPDATE")) {
                    ps.setLong(1, expectedIdentity);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next() || !player.toString().equalsIgnoreCase(rs.getString("owner_uuid"))) {
                            c.rollback();
                            return false;
                        }
                    }
                }
                for (String table : List.of("fc_guild_members", "fc_guild_applications", "fc_guild_fakeplayers")) {
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE guild_id = ?")) {
                        ps.setLong(1, expectedIdentity);
                        ps.executeUpdate();
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM fc_guild_invites WHERE LOWER(guild_name) = ?")) {
                    ps.setString(1, g.key);
                    ps.executeUpdate();
                }
                // Shop rows are a player purchase ledger across guilds; disband must not reset limits.
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM fc_guilds WHERE id = ?")) {
                    ps.setLong(1, expectedIdentity);
                    if (ps.executeUpdate() != 1) throw new SQLException("Guild was not removed");
                }
                c.commit();
                committed = true;
            } catch (SQLException e) {
                try { c.rollback(); } catch (SQLException rollbackError) { e.addSuppressed(rollbackError); }
                throw e;
            }
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[FreeCoreGuild] " + (committed ? "Disband committed; closing connection failed: " : "Disband guild failed: ") + e.getMessage());
            if (!committed) return false;
        }
        // Only committed deletion may evict caches and allow callers to announce success/SYNC_ALL.
        guilds.remove(g.key, g);
        members.entrySet().removeIf(e -> e.getValue().equals(g.key));
        applications.remove(g.key);
        removeGuildInvites(g.key);
        return true;
    }

    @Override
    public boolean claimUpgradeReward(UUID player, int level) {
        boolean ok = super.claimUpgradeReward(player, level);
        if (!ok) return false;
        Guild g = guildFor(player);
        if (g == null) return true;
        Bukkit.getScheduler().runTaskAsynchronously(FreeCoreGuildPlugin.getInstance(), () -> {
            try (Connection c = dataSource.getConnection()) {
                c.setAutoCommit(false);
                long gid = getGuildId(c, g);
                if (gid > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int l : g.claimedUpgradeRewards) {
                        if (!sb.isEmpty()) sb.append(",");
                        sb.append(l);
                    }
                    try (PreparedStatement ps = c.prepareStatement("UPDATE fc_guilds SET claimed_rewards = ? WHERE id = ?")) {
                        ps.setString(1, sb.toString());
                        ps.setLong(2, gid);
                        ps.executeUpdate();
                    }
                }
                c.commit();
            } catch (SQLException e) { Bukkit.getLogger().warning("[FreeCoreGuild] Deferred guild write failed: " + e.getMessage()); }
        });
        return true;
    }

    @Override
    public boolean setBaseLocation(UUID operator, Location location) {
        boolean ok = super.setBaseLocation(operator, location);
        if (!ok) return false;
        Guild g = guildFor(operator);
        if (g == null || g.baseLocation == null) return true;
        GuildBaseLocation bl = g.baseLocation;
        // 关键修复：同步立即写入数据库，确保跨服立即查得到
        try (Connection c = dataSource.getConnection()) {
            long gid = getGuildId(c, g);
            if (gid > 0) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE fc_guilds SET base_server = ?, base_world = ?, base_x = ?, base_y = ?, base_z = ?, base_yaw = ?, base_pitch = ? WHERE id = ?")) {
                    ps.setString(1, bl.server());
                    ps.setString(2, bl.world());
                    ps.setDouble(3, bl.x());
                    ps.setDouble(4, bl.y());
                    ps.setDouble(5, bl.z());
                    ps.setFloat(6, bl.yaw());
                    ps.setFloat(7, bl.pitch());
                    ps.setLong(8, gid);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            Bukkit.getLogger().severe("[FreeCoreGuild] Failed to save base location to MySQL: " + e.getMessage());
        }
        return true;
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
