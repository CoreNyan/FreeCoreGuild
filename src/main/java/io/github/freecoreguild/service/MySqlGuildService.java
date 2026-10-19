package io.github.freecoreguild.service;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.freecoreguild.FreeCoreGuildPlugin;
import io.github.freecoreguild.api.GuildProvider.Role;
import org.bukkit.Bukkit;
import org.bukkit.Location;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
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
        Bukkit.getScheduler().runTaskAsynchronously(FreeCoreGuildPlugin.getInstance(), () -> {
            try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
                s.executeUpdate("TRUNCATE TABLE fc_guild_shop_limits");
            } catch (SQLException ignored) {}
        });
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
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)");

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

    /** 从数据库全量拉取公会数据并更新到内存映射中 */
    public synchronized void loadAllFromDatabase() {
        try (Connection c = dataSource.getConnection()) {
            Map<Long, Guild> idToGuild = new HashMap<>();

            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT id, name, owner_uuid, level, prosperity, claimed_rewards, base_server, base_world, base_x, base_y, base_z, base_yaw, base_pitch FROM fc_guilds")) {
                while (rs.next()) {
                    long gid = rs.getLong("id");
                    String name = rs.getString("name");
                    UUID owner = UUID.fromString(rs.getString("owner_uuid"));
                    int lvl = rs.getInt("level");
                    long xp = rs.getLong("prosperity");
                    String claimed = rs.getString("claimed_rewards");

                    Guild g = guilds.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new Guild(name, owner));
                    g.owner = owner;
                    g.level = lvl;
                    g.xp = xp;
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
                }
            }

            members.clear();
            for (Guild g : idToGuild.values()) {
                g.roles.clear();
                g.contributions.clear();
                g.checkins.clear();
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
                    members.put(pUid, g.key);
                }
            }

            for (Guild g : idToGuild.values()) {
                if (!g.roles.containsKey(g.owner)) {
                    g.roles.put(g.owner, Role.PRESIDENT);
                }
                members.put(g.owner, g.key);
                g.memberCount = g.roles.size();
            }

            // 加载申请
            applications.clear();
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT guild_id, player_uuid FROM fc_guild_applications")) {
                while (rs.next()) {
                    long gid = rs.getLong("guild_id");
                    Guild g = idToGuild.get(gid);
                    if (g == null) continue;
                    UUID applicant = UUID.fromString(rs.getString("player_uuid"));
                    applications.computeIfAbsent(g.key, k -> ConcurrentHashMap.newKeySet()).add(applicant);
                }
            }

            // 加载退会冷却
            leaveTimestamps.clear();
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT player_uuid, left_at FROM fc_guild_cooldowns")) {
                while (rs.next()) {
                    leaveTimestamps.put(UUID.fromString(rs.getString("player_uuid")), rs.getLong("left_at"));
                }
            }
        } catch (SQLException e) {
            Bukkit.getLogger().severe("[FreeCoreGuild] Failed to load guilds from MySQL: " + e.getMessage());
        }
    }

    /** 跨服穿透检查：如果玩家在内存中没有公会，实时查询一次数据库 */
    private void refreshPlayerIfMissing(UUID player) {
        if (player == null || members.containsKey(player)) return;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT g.name, g.id FROM fc_guild_members m JOIN fc_guilds g ON m.guild_id = g.id WHERE m.player_uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    loadAllFromDatabase();
                }
            }
        } catch (SQLException ignored) {}
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
    public Optional<GuildView> guildOf(UUID player) {
        if (player == null) return Optional.empty();
        if (!members.containsKey(player)) {
            refreshPlayerIfMissing(player);
        }
        return super.guildOf(player);
    }

    @Override
    public Optional<Role> roleOf(UUID player) {
        if (player == null) return Optional.empty();
        if (!members.containsKey(player)) {
            refreshPlayerIfMissing(player);
        }
        return super.roleOf(player);
    }

    @Override
    public List<MemberInfo> getMembers(UUID player) {
        if (player != null && !members.containsKey(player)) {
            refreshPlayerIfMissing(player);
        }
        return super.getMembers(player);
    }

    @Override
    public Optional<GuildBaseLocation> getBaseLocation(UUID player) {
        if (player != null && !members.containsKey(player)) {
            refreshPlayerIfMissing(player);
        }
        return super.getBaseLocation(player);
    }

    @Override
    public List<UUID> getPresidents(UUID player) {
        if (player != null && !members.containsKey(player)) {
            refreshPlayerIfMissing(player);
        }
        return super.getPresidents(player);
    }

    @Override
    public long getLeaveCooldownRemainingMillis(UUID player) {
        if (player == null) return 0L;
        // 先查本地缓存
        long rem = super.getLeaveCooldownRemainingMillis(player);
        if (rem > 0) return rem;
        // 跨服穿透数据库查询
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
                        clearLeaveCooldown(player);
                    }
                }
            }
        } catch (SQLException ignored) {}
        return 0L;
    }

    @Override
    public void clearLeaveCooldown(UUID player) {
        super.clearLeaveCooldown(player);
        if (player == null) return;
        Bukkit.getScheduler().runTaskAsynchronously(FreeCoreGuildPlugin.getInstance(), () -> {
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement("DELETE FROM fc_guild_cooldowns WHERE player_uuid = ?")) {
                ps.setString(1, player.toString());
                ps.executeUpdate();
            } catch (SQLException ignored) {}
        });
    }

    @Override
    public void resetCheckinToday(UUID player) {
        super.resetCheckinToday(player);
        if (player == null) return;
        Bukkit.getScheduler().runTaskAsynchronously(FreeCoreGuildPlugin.getInstance(), () -> {
            try (Connection c = dataSource.getConnection()) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE fc_guild_members SET last_checkin = NULL WHERE player_uuid = ?")) {
                    ps.setString(1, player.toString());
                    ps.executeUpdate();
                }
            } catch (SQLException ignored) {}
        });
    }

    @Override
    public UUID resolvePlayerUuid(String playerName) {
        if (playerName == null || playerName.isBlank()) return null;
        UUID local = super.resolvePlayerUuid(playerName);
        if (local != null) return local;

        // 跨服在数据库及 Redis 查找玩家 UUID
        try {
            String redisUuid = FreeCoreGuildPlugin.getInstance().getRedisBridge().execute("GET", "fce:online:name:" + playerName.toLowerCase(Locale.ROOT) + ":uuid");
            if (redisUuid != null && !redisUuid.isBlank()) {
                return UUID.fromString(redisUuid.trim());
            }
            redisUuid = FreeCoreGuildPlugin.getInstance().getRedisBridge().execute("GET", "fce:name2uuid:" + playerName.toLowerCase(Locale.ROOT));
            if (redisUuid != null && !redisUuid.isBlank()) {
                return UUID.fromString(redisUuid.trim());
            }
        } catch (Exception ignored) {}

        // 从 MySQL 数据库历史表中查找
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT uuid FROM fce_players WHERE LOWER(username)=? LIMIT 1")) {
            ps.setString(1, playerName.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return UUID.fromString(rs.getString("uuid"));
            }
        } catch (Exception ignored) {}

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT player_uuid FROM fc_guild_members m WHERE m.player_uuid IN (SELECT player_uuid FROM fc_guild_members) LIMIT 1")) {
            // fallback
        } catch (Exception ignored) {}

        return null;
    }

    @Override
    public boolean resetCheckinTodayByName(String playerName) {
        UUID uid = resolvePlayerUuid(playerName);
        if (uid == null) return false;
        resetCheckinToday(uid);
        return true;
    }

    @Override
    public boolean clearLeaveCooldownByName(String playerName) {
        UUID uid = resolvePlayerUuid(playerName);
        if (uid == null) return false;
        clearLeaveCooldown(uid);
        return true;
    }

    @Override
    public synchronized boolean joinDirectly(UUID player, String guildName) {
        boolean ok = super.joinDirectly(player, guildName);
        if (!ok) return false;
        Guild g = guilds.get(guildName.trim().toLowerCase(Locale.ROOT));
        if (g == null) return true;
        Bukkit.getScheduler().runTaskAsynchronously(FreeCoreGuildPlugin.getInstance(), () -> {
            try (Connection c = dataSource.getConnection()) {
                long gid = getGuildId(c, g.name);
                if (gid > 0) {
                    try (PreparedStatement ps = c.prepareStatement("INSERT INTO fc_guild_members (guild_id, player_uuid, role, contribution) VALUES (?, ?, 'MEMBER', 0) ON DUPLICATE KEY UPDATE role='MEMBER'")) {
                        ps.setLong(1, gid);
                        ps.setString(2, player.toString());
                        ps.executeUpdate();
                    }
                    try (PreparedStatement dps = c.prepareStatement("DELETE FROM fc_guild_applications WHERE guild_id = ? AND player_uuid = ?")) {
                        dps.setLong(1, gid);
                        dps.setString(2, player.toString());
                        dps.executeUpdate();
                    }
                }
            } catch (SQLException ignored) {}
        });
        return true;
    }

    @Override
    public int setGuildProsperity(String guildName, long newProsperity) {
        int newLvl = super.setGuildProsperity(guildName, newProsperity);
        if (newLvl < 0) return -1;
        Bukkit.getScheduler().runTaskAsynchronously(FreeCoreGuildPlugin.getInstance(), () -> {
            try (Connection c = dataSource.getConnection()) {
                long gid = getGuildId(c, guildName);
                if (gid > 0) {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE fc_guilds SET prosperity = ?, level = ? WHERE id = ?")) {
                        ps.setLong(1, Math.max(0L, newProsperity));
                        ps.setInt(2, newLvl);
                        ps.setLong(3, gid);
                        ps.executeUpdate();
                    }
                }
            } catch (SQLException ignored) {}
        });
        return newLvl;
    }

    private long getGuildId(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM fc_guilds WHERE LOWER(name) = ?")) {
            ps.setString(1, name.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong("id");
            }
        }
        return -1;
    }

    @Override
    public synchronized boolean create(UUID owner, String name) {
        boolean ok = super.create(owner, name);
        if (!ok) return false;
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO fc_guilds (name, owner_uuid, level, prosperity) VALUES (?, ?, 1, 0)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name.trim());
                ps.setString(2, owner.toString());
                ps.executeUpdate();
                long gid = -1;
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) gid = rs.getLong(1);
                }
                if (gid > 0) {
                    try (PreparedStatement mps = c.prepareStatement("INSERT INTO fc_guild_members (guild_id, player_uuid, role, contribution) VALUES (?, ?, 'PRESIDENT', 0)")) {
                        mps.setLong(1, gid);
                        mps.setString(2, owner.toString());
                        mps.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            Bukkit.getLogger().severe("[FreeCoreGuild] MySQL create guild failed: " + e.getMessage());
        }
        return true;
    }

    @Override
    public synchronized boolean approve(UUID operator, UUID applicant) {
        boolean ok = super.approve(operator, applicant);
        if (!ok) return false;
        Guild g = guildFor(operator);
        if (g == null) return true;
        Bukkit.getScheduler().runTaskAsynchronously(FreeCoreGuildPlugin.getInstance(), () -> {
            try (Connection c = dataSource.getConnection()) {
                long gid = getGuildId(c, g.name);
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
                long gid = getGuildId(c, g.name);
                if (gid > 0) {
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM fc_guild_applications WHERE guild_id = ? AND player_uuid = ?")) {
                        ps.setLong(1, gid);
                        ps.setString(2, applicant.toString());
                        ps.executeUpdate();
                    }
                }
            } catch (SQLException ignored) {}
        });
        return ok;
    }

    @Override
    public synchronized boolean apply(UUID player, String guildName) {
        boolean ok = super.apply(player, guildName);
        if (!ok) return false;
        Guild g = guilds.get(guildName.trim().toLowerCase(Locale.ROOT));
        if (g == null) return true;
        Bukkit.getScheduler().runTaskAsynchronously(FreeCoreGuildPlugin.getInstance(), () -> {
            try (Connection c = dataSource.getConnection()) {
                long gid = getGuildId(c, g.name);
                if (gid > 0) {
                    try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO fc_guild_applications (guild_id, player_uuid) VALUES (?, ?)")) {
                        ps.setLong(1, gid);
                        ps.setString(2, player.toString());
                        ps.executeUpdate();
                    }
                }
            } catch (SQLException ignored) {}
        });
        return true;
    }

    @Override
    public CheckinResult checkinPlayer(UUID player) {
        CheckinResult res = super.checkinPlayer(player);
        if (!res.success()) return res;
        Guild g = guildFor(player);
        if (g == null) return res;
        Bukkit.getScheduler().runTaskAsynchronously(FreeCoreGuildPlugin.getInstance(), () -> {
            try (Connection c = dataSource.getConnection()) {
                long gid = getGuildId(c, g.name);
                if (gid > 0) {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE fc_guilds SET prosperity = ?, level = ? WHERE id = ?")) {
                        ps.setLong(1, g.xp);
                        ps.setInt(2, g.level);
                        ps.setLong(3, gid);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement mps = c.prepareStatement("UPDATE fc_guild_members SET contribution = ?, last_checkin = ? WHERE guild_id = ? AND player_uuid = ?")) {
                        mps.setLong(1, g.contributions.getOrDefault(player, 0L));
                        mps.setDate(2, java.sql.Date.valueOf(LocalDate.now()));
                        mps.setLong(3, gid);
                        mps.setString(4, player.toString());
                        mps.executeUpdate();
                    }
                }
            } catch (SQLException ignored) {}
        });
        return res;
    }

    @Override
    public int donateAndCheckAutoUpgrade(UUID player, long prosperityToAdd) {
        int res = super.donateAndCheckAutoUpgrade(player, prosperityToAdd);
        Guild g = guildFor(player);
        if (g == null) return res;
        Bukkit.getScheduler().runTaskAsynchronously(FreeCoreGuildPlugin.getInstance(), () -> {
            try (Connection c = dataSource.getConnection()) {
                long gid = getGuildId(c, g.name);
                if (gid > 0) {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE fc_guilds SET prosperity = ?, level = ? WHERE id = ?")) {
                        ps.setLong(1, g.xp);
                        ps.setInt(2, g.level);
                        ps.setLong(3, gid);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement mps = c.prepareStatement("UPDATE fc_guild_members SET contribution = ? WHERE guild_id = ? AND player_uuid = ?")) {
                        mps.setLong(1, g.contributions.getOrDefault(player, 0L));
                        mps.setLong(2, gid);
                        mps.setString(3, player.toString());
                        mps.executeUpdate();
                    }
                }
            } catch (SQLException ignored) {}
        });
        return res;
    }

    @Override
    public boolean promote(UUID operator, UUID target, Role requested) {
        boolean ok = super.promote(operator, target, requested);
        if (!ok) return false;
        syncRoleToDb(target, requested);
        return true;
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
        Bukkit.getScheduler().runTaskAsynchronously(FreeCoreGuildPlugin.getInstance(), () -> {
            try (Connection c = dataSource.getConnection()) {
                long gid = getGuildId(c, g.name);
                if (gid > 0) {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE fc_guild_members SET role = ? WHERE guild_id = ? AND player_uuid = ?")) {
                        ps.setString(1, role.name());
                        ps.setLong(2, gid);
                        ps.setString(3, player.toString());
                        ps.executeUpdate();
                    }
                }
            } catch (SQLException ignored) {}
        });
    }

    @Override
    public TransferResult transferWithReason(UUID operator, UUID target) {
        TransferResult res = super.transferWithReason(operator, target);
        if (!res.success()) return res;
        Guild g = guildFor(operator);
        if (g == null) return res;
        Bukkit.getScheduler().runTaskAsynchronously(FreeCoreGuildPlugin.getInstance(), () -> {
            try (Connection c = dataSource.getConnection()) {
                long gid = getGuildId(c, g.name);
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
            } catch (SQLException ignored) {}
        });
        return res;
    }

    @Override
    public boolean kick(UUID operator, UUID target) {
        Guild g = guildFor(operator);
        boolean ok = super.kick(operator, target);
        if (!ok || g == null) return false;
        Bukkit.getScheduler().runTaskAsynchronously(FreeCoreGuildPlugin.getInstance(), () -> {
            try (Connection c = dataSource.getConnection()) {
                long gid = getGuildId(c, g.name);
                if (gid > 0) {
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM fc_guild_members WHERE guild_id = ? AND player_uuid = ?")) {
                        ps.setLong(1, gid);
                        ps.setString(2, target.toString());
                        ps.executeUpdate();
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO fc_guild_cooldowns (player_uuid, left_at) VALUES (?, ?) ON DUPLICATE KEY UPDATE left_at = ?")) {
                    ps.setString(1, target.toString());
                    ps.setLong(2, System.currentTimeMillis());
                    ps.setLong(3, System.currentTimeMillis());
                    ps.executeUpdate();
                }
            } catch (SQLException ignored) {}
        });
        return true;
    }

    @Override
    public boolean leave(UUID player) {
        Guild g = guildFor(player);
        boolean ok = super.leave(player);
        if (!ok || g == null) return false;
        Bukkit.getScheduler().runTaskAsynchronously(FreeCoreGuildPlugin.getInstance(), () -> {
            try (Connection c = dataSource.getConnection()) {
                long gid = getGuildId(c, g.name);
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
            } catch (SQLException ignored) {}
        });
        return true;
    }

    @Override
    public boolean disband(UUID player) {
        Guild g = guildFor(player);
        boolean ok = super.disband(player);
        if (!ok || g == null) return false;
        Bukkit.getScheduler().runTaskAsynchronously(FreeCoreGuildPlugin.getInstance(), () -> {
            try (Connection c = dataSource.getConnection()) {
                long gid = getGuildId(c, g.name);
                if (gid > 0) {
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM fc_guilds WHERE id = ?")) { ps.setLong(1, gid); ps.executeUpdate(); }
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM fc_guild_members WHERE guild_id = ?")) { ps.setLong(1, gid); ps.executeUpdate(); }
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM fc_guild_applications WHERE guild_id = ?")) { ps.setLong(1, gid); ps.executeUpdate(); }
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM fc_guild_fakeplayers WHERE guild_id = ?")) { ps.setLong(1, gid); ps.executeUpdate(); }
                }
            } catch (SQLException ignored) {}
        });
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
                long gid = getGuildId(c, g.name);
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
            } catch (SQLException ignored) {}
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
            long gid = getGuildId(c, g.name);
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
