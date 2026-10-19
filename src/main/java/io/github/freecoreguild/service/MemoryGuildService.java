package io.github.freecoreguild.service;

import io.github.freecoreguild.api.GuildProvider;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe in-memory and base guild implementation. */
public class MemoryGuildService implements GuildService {
    protected final NavigableMap<Integer, LevelConfig> levels;
    protected final long checkinProsperity;
    protected final double checkinCoins;
    protected final long leaveCooldownMillis;
    protected final Map<String, Guild> guilds = new ConcurrentHashMap<>();
    protected final Map<UUID, String> members = new ConcurrentHashMap<>();
    protected final Map<String, Set<UUID>> applications = new ConcurrentHashMap<>();
    protected final Map<UUID, Long> leaveTimestamps = new ConcurrentHashMap<>();

    public MemoryGuildService(NavigableMap<Integer, LevelConfig> levels) {
        this(levels, 1000L, 0.0, 24L * 3600_000L);
    }

    public MemoryGuildService(NavigableMap<Integer, LevelConfig> levels, long checkinProsperity, double checkinCoins, long leaveCooldownMillis) {
        this.levels = Collections.unmodifiableNavigableMap(new TreeMap<>(Objects.requireNonNull(levels)));
        this.checkinProsperity = Math.max(0L, checkinProsperity);
        this.checkinCoins = Math.max(0.0, checkinCoins);
        this.leaveCooldownMillis = Math.max(0L, leaveCooldownMillis);
    }

    @Override public void initialize() {}

    @Override public long getLeaveCooldownRemainingMillis(UUID player) {
        if (player == null) return 0L;
        Long leftTime = leaveTimestamps.get(player);
        if (leftTime == null) return 0L;
        long elapsed = System.currentTimeMillis() - leftTime;
        if (elapsed >= leaveCooldownMillis) {
            leaveTimestamps.remove(player);
            return 0L;
        }
        return leaveCooldownMillis - elapsed;
    }

    @Override public void clearLeaveCooldown(UUID player) {
        if (player != null) leaveTimestamps.remove(player);
    }

    @Override public boolean clearLeaveCooldownByName(String playerName) {
        UUID uid = resolvePlayerUuid(playerName);
        if (uid != null) {
            clearLeaveCooldown(uid);
            return true;
        }
        return false;
    }

    @Override public void resetCheckinToday(UUID player) {
        if (player == null) return;
        Guild g = guildFor(player);
        if (g != null) {
            synchronized (g) {
                g.checkins.remove(player);
            }
        }
    }

    @Override public boolean resetCheckinTodayByName(String playerName) {
        UUID uid = resolvePlayerUuid(playerName);
        if (uid != null) {
            resetCheckinToday(uid);
            return true;
        }
        return false;
    }

    @Override public UUID resolvePlayerUuid(String playerName) {
        if (playerName == null || playerName.isBlank()) return null;
        org.bukkit.entity.Player online = Bukkit.getPlayerExact(playerName);
        if (online != null) return online.getUniqueId();
        OfflinePlayer op = Bukkit.getOfflinePlayer(playerName);
        if (op.hasPlayedBefore() || op.isOnline()) return op.getUniqueId();
        return null;
    }

    @Override public synchronized boolean joinDirectly(UUID player, String guildName) {
        if (player == null || guildName == null || members.containsKey(player)) return false;
        Guild g = guilds.get(guildName.trim().toLowerCase(Locale.ROOT));
        if (g == null) return false;
        synchronized (g) {
            if (g.memberCount >= level(g.level).maxMembers()) return false;
            members.put(player, g.key);
            g.roles.put(player, Role.MEMBER);
            g.memberCount++;
            for (Set<UUID> pending : applications.values()) pending.remove(player);
            return true;
        }
    }

    @Override public int setGuildProsperity(String guildName, long newProsperity) {
        if (guildName == null) return -1;
        Guild g = guilds.get(guildName.trim().toLowerCase(Locale.ROOT));
        if (g == null) return -1;
        synchronized (g) {
            g.xp = Math.max(0L, newProsperity);
            // 重新计算等级
            int targetLevel = 1;
            for (Map.Entry<Integer, LevelConfig> entry : levels.entrySet()) {
                if (g.xp >= entry.getValue().requiredXp()) {
                    targetLevel = entry.getKey();
                }
            }
            g.level = targetLevel;
            return g.level;
        }
    }

    @Override public synchronized boolean create(UUID owner, String name) {
        if (owner == null || members.containsKey(owner) || name == null || name.isBlank()) return false;
        if (getLeaveCooldownRemainingMillis(owner) > 0) return false;
        String trimmed = name.trim();
        String key = trimmed.toLowerCase(Locale.ROOT);
        Guild g = new Guild(trimmed, owner);
        if (guilds.putIfAbsent(key, g) != null) return false;
        members.put(owner, key);
        applications.putIfAbsent(key, ConcurrentHashMap.newKeySet());
        return true;
    }

    @Override public synchronized boolean apply(UUID player, String guildName) {
        if (player == null || guildName == null || members.containsKey(player)) return false;
        if (getLeaveCooldownRemainingMillis(player) > 0) return false;
        Guild g = guilds.get(guildName.trim().toLowerCase(Locale.ROOT));
        if (g == null) return false;
        synchronized (g) {
            if (g.memberCount >= level(g.level).maxMembers()) return false;
            for (Set<UUID> pending : applications.values()) pending.remove(player);
            return applications.computeIfAbsent(g.key, k -> ConcurrentHashMap.newKeySet()).add(player);
        }
    }

    @Override public List<UUID> applications(UUID player) {
        Guild g = guildFor(player);
        if (g == null) return List.of();
        synchronized (g) {
            if (rank(g, player).ordinal() < Role.ELDER.ordinal()) return List.of();
            return List.copyOf(applications.getOrDefault(g.key, Set.of()));
        }
    }

    @Override public synchronized boolean approve(UUID operator, UUID applicant) {
        return reviewApplication(operator, applicant, true);
    }

    @Override public synchronized boolean reject(UUID operator, UUID applicant) {
        return reviewApplication(operator, applicant, false);
    }

    private boolean reviewApplication(UUID operator, UUID applicant, boolean accept) {
        if (operator == null || applicant == null || members.containsKey(applicant)) return false;
        if (accept && getLeaveCooldownRemainingMillis(applicant) > 0) return false;
        Guild g = guildFor(operator);
        if (g == null) return false;
        synchronized (g) {
            if (rank(g, operator).ordinal() < Role.ELDER.ordinal()) return false;
            Set<UUID> pending = applications.get(g.key);
            if (pending == null || !pending.contains(applicant)) return false;
            if (accept && g.memberCount >= level(g.level).maxMembers()) return false;
            pending.remove(applicant);
            if (accept) {
                members.put(applicant, g.key);
                g.memberCount++;
                g.roles.put(applicant, Role.MEMBER);
            }
            return true;
        }
    }

    @Override public Optional<GuildView> guildOf(UUID player) {
        if (player == null) return Optional.empty();
        String name = members.get(player);
        return name == null ? Optional.empty() : find(name);
    }

    @Override public Optional<GuildView> find(String name) {
        if (name == null) return Optional.empty();
        Guild g = guilds.get(name.toLowerCase(Locale.ROOT));
        if (g == null) return Optional.empty();
        synchronized (g) {
            LevelConfig level = level(g.level);
            return Optional.of(new GuildView(g.name, g.owner, g.level, g.xp,
                    g.memberCount, level.maxMembers()));
        }
    }

    @Override public List<GuildView> getAllGuilds() {
        List<GuildView> list = new ArrayList<>();
        for (Guild g : guilds.values()) {
            synchronized (g) {
                LevelConfig level = level(g.level);
                list.add(new GuildView(g.name, g.owner, g.level, g.xp, g.memberCount, level.maxMembers()));
            }
        }
        return list;
    }

    @Override public List<MemberInfo> getMembers(UUID player) {
        Guild g = guildFor(player);
        if (g == null) return List.of();
        List<MemberInfo> list = new ArrayList<>();
        synchronized (g) {
            OfflinePlayer ownerP = Bukkit.getOfflinePlayer(g.owner);
            String ownerName = ownerP.getName() != null ? ownerP.getName() : g.owner.toString().substring(0, 8);
            String lastCheckinOwner = g.checkins.containsKey(g.owner) ? g.checkins.get(g.owner).toString() : "无";
            list.add(new MemberInfo(g.owner, ownerName, Role.PRESIDENT, g.contributions.getOrDefault(g.owner, 0L), lastCheckinOwner));

            for (Map.Entry<UUID, Role> e : g.roles.entrySet()) {
                if (e.getKey().equals(g.owner)) continue;
                OfflinePlayer op = Bukkit.getOfflinePlayer(e.getKey());
                String pName = op.getName() != null ? op.getName() : e.getKey().toString().substring(0, 8);
                String lastCheckin = g.checkins.containsKey(e.getKey()) ? g.checkins.get(e.getKey()).toString() : "无";
                list.add(new MemberInfo(e.getKey(), pName, e.getValue(), g.contributions.getOrDefault(e.getKey(), 0L), lastCheckin));
            }
        }
        list.sort((a, b) -> Integer.compare(b.role().ordinal(), a.role().ordinal()));
        return list;
    }

    @Override public List<UUID> getPresidents(UUID player) {
        Guild g = guildFor(player);
        if (g == null) return List.of();
        List<UUID> presidents = new ArrayList<>();
        synchronized (g) {
            if (g.owner != null) presidents.add(g.owner);
            for (Map.Entry<UUID, Role> e : g.roles.entrySet()) {
                if (e.getValue() == Role.PRESIDENT && !presidents.contains(e.getKey())) {
                    presidents.add(e.getKey());
                }
            }
        }
        return presidents;
    }

    @Override public boolean setBaseLocation(UUID operator, Location location) {
        if (operator == null || location == null || location.getWorld() == null) return false;
        Guild g = guildFor(operator);
        if (g == null) return false;
        synchronized (g) {
            Role role = rank(g, operator);
            if (role != Role.PRESIDENT && role != Role.VICE_PRESIDENT) return false;
            String serverName = Bukkit.getPort() == 25570 ? "technical" : "survival";
            g.baseLocation = new GuildBaseLocation(
                    serverName,
                    location.getWorld().getName(),
                    location.getX(),
                    location.getY(),
                    location.getZ(),
                    location.getYaw(),
                    location.getPitch()
            );
            return true;
        }
    }

    @Override public Optional<GuildBaseLocation> getBaseLocation(UUID player) {
        Guild g = guildFor(player);
        if (g == null) return Optional.empty();
        reloadBaseLocationFromDb(g.key);
        synchronized (g) {
            return Optional.ofNullable(g.baseLocation);
        }
    }

    @Override public void reloadBaseLocationFromDb(String guildKey) {}

    @Override public int fakePlayerQuota(UUID player) {
        String name = player == null ? null : members.get(player);
        Guild g = name == null ? null : guilds.get(name);
        if (g == null) return 0;
        synchronized (g) { return level(g.level).fakePlayerQuota(); }
    }

    @Override public int currentFakePlayers(UUID player) {
        Guild g = guildFor(player);
        if (g == null) return 0;
        synchronized (g) { return g.fakePlayers.size(); }
    }

    @Override public boolean canOperateFakePlayers(UUID player) {
        return roleOf(player).map(Role::canOperateFakePlayers).orElse(false);
    }

    @Override public Optional<Role> roleOf(UUID player) {
        if (player == null) return Optional.empty();
        String name = members.get(player);
        Guild g = name == null ? null : guilds.get(name);
        if (g == null) return Optional.empty();
        synchronized (g) { return Optional.of(g.owner.equals(player) ? Role.PRESIDENT : g.roles.getOrDefault(player, Role.MEMBER)); }
    }

    @Override public boolean reserveFakePlayer(UUID operator, String identifier) {
        if (identifier == null || identifier.isBlank() || !canOperateFakePlayers(operator)) return false;
        Guild g = guildFor(operator);
        if (g == null) return false;
        synchronized (g) {
            if (g.fakePlayers.contains(identifier) || g.fakePlayers.size() >= level(g.level).fakePlayerQuota()) return false;
            return g.fakePlayers.add(identifier);
        }
    }

    @Override public boolean releaseFakePlayer(UUID operator, String identifier) {
        if (identifier == null || !canOperateFakePlayers(operator)) return false;
        Guild g = guildFor(operator);
        if (g == null) return false;
        synchronized (g) { return g.fakePlayers.remove(identifier); }
    }

    @Override public CheckinResult checkinPlayer(UUID player) {
        Guild g = guildFor(player);
        if (g == null) return new CheckinResult(false, "你不属于任何公会", 0, 0, 0);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        synchronized (g) {
            if (today.equals(g.checkins.get(player))) {
                return new CheckinResult(false, "你今日已签到", 0, 0, 0);
            }
            g.checkins.put(player, today);

            DayOfWeek dow = today.getDayOfWeek();
            boolean isWeekend = (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY);
            long prosperity = isWeekend ? 3000L : 1000L;
            int goldAmount = isWeekend ? 30 : 10;

            g.xp += prosperity;
            g.contributions.merge(player, prosperity, Long::sum);

            checkAutoUpgrade(g);
            return new CheckinResult(true, "签到成功", 0.0, prosperity, goldAmount);
        }
    }

    @Override public boolean promote(UUID operator, UUID target, Role requested) {
        if (requested == null || operator == null || target == null || operator.equals(target)) return false;
        Guild g = guildFor(operator);
        if (g == null || g != guildFor(target)) return false;
        synchronized (g) {
            Role actor = rank(g, operator), current = rank(g, target);
            if (actor == Role.VICE_PRESIDENT) {
                if (current == Role.MEMBER && requested == Role.ELDER) {
                    if (countRole(g, Role.ELDER) >= quota(g, Role.ELDER)) return false;
                    g.roles.put(target, Role.ELDER);
                    return true;
                }
                if (current == Role.ELDER && requested == Role.VICE_PRESIDENT) {
                    if (countRole(g, Role.VICE_PRESIDENT) >= quota(g, Role.VICE_PRESIDENT)) return false;
                    g.roles.put(target, Role.VICE_PRESIDENT);
                    return true;
                }
                return false;
            }
            if (actor == Role.PRESIDENT) {
                if (requested.ordinal() <= current.ordinal()) return false;
                if (requested == Role.PRESIDENT && current != Role.VICE_PRESIDENT) return false;
                if (countRole(g, requested) >= quota(g, requested)) return false;
                g.roles.put(target, requested);
                return true;
            }
            return false;
        }
    }

    @Override public boolean demote(UUID operator, UUID target) {
        if (operator == null || target == null || operator.equals(target)) return false;
        Guild g = guildFor(operator);
        if (g == null || g != guildFor(target)) return false;
        synchronized (g) {
            Role actor = rank(g, operator), current = rank(g, target);
            if (current == Role.MEMBER) return false;
            if (actor == Role.VICE_PRESIDENT) {
                if (current == Role.ELDER) {
                    g.roles.put(target, Role.MEMBER);
                    return true;
                }
                return false;
            }
            if (actor == Role.PRESIDENT) {
                if (current == Role.PRESIDENT) return false;
                Role lower = Role.values()[current.ordinal() - 1];
                g.roles.put(target, lower);
                return true;
            }
            return false;
        }
    }

    @Override public boolean transfer(UUID operator, UUID target) {
        return transferWithReason(operator, target).success();
    }

    @Override public TransferResult transferWithReason(UUID operator, UUID target) {
        if (operator == null || target == null) return new TransferResult(false, "目标玩家无效！");
        if (operator.equals(target)) return new TransferResult(false, "无法转让给自己！");
        synchronized (this) {
            Guild g = guildFor(operator);
            if (g == null) return new TransferResult(false, "你当前不属于任何公会！");
            if (rank(g, operator) != Role.PRESIDENT && !g.owner.equals(operator)) {
                return new TransferResult(false, "只有公会会长才能发起转让操作！");
            }
            if (g != guildFor(target)) {
                return new TransferResult(false, "目标玩家不属于本公会！");
            }
            synchronized (g) {
                g.roles.remove(target);
                UUID previousOwner = g.owner;
                g.owner = target;
                g.roles.put(previousOwner, Role.VICE_PRESIDENT);
                return new TransferResult(true, "转让成功");
            }
        }
    }

    @Override public boolean claimUpgradeReward(UUID player, int level) {
        if (player == null || level < 1) return false;
        Guild g = guildFor(player);
        if (g == null) return false;
        synchronized (g) {
            if (!g.owner.equals(player) && rank(g, player) != Role.PRESIDENT) return false;
            if (g.level < level || !levels.containsKey(level)) return false;
            return g.claimedUpgradeRewards.add(level);
        }
    }

    @Override public boolean isRewardClaimed(UUID player, int level) {
        Guild g = guildFor(player);
        if (g == null) return true;
        synchronized (g) {
            return g.claimedUpgradeRewards.contains(level);
        }
    }

    @Override public boolean kick(UUID operator, UUID target) {
        if (operator == null || target == null || operator.equals(target)) return false;
        synchronized (this) {
            Guild g = guildFor(operator);
            if (g == null || g != guildFor(target)) return false;
            synchronized (g) {
                Role actor = rank(g, operator);
                Role victim = rank(g, target);
                if (actor.ordinal() <= victim.ordinal()) return false;
                if (g.owner.equals(target)) return false;

                String key = members.remove(target);
                if (key == null) return false;
                g.roles.remove(target);
                g.checkins.remove(target);
                g.memberCount--;
                leaveTimestamps.put(target, System.currentTimeMillis());
                return true;
            }
        }
    }

    @Override public int donateAndCheckAutoUpgrade(UUID player, long prosperityToAdd) {
        if (prosperityToAdd <= 0) return 0;
        Guild g = guildFor(player);
        if (g == null) return 0;
        synchronized (g) {
            g.xp += prosperityToAdd;
            g.contributions.merge(player, prosperityToAdd, Long::sum);
            return checkAutoUpgrade(g);
        }
    }

    protected int checkAutoUpgrade(Guild g) {
        int upgradedTo = 0;
        while (true) {
            Map.Entry<Integer, LevelConfig> next = levels.higherEntry(g.level);
            if (next == null) break;
            long required = next.getValue().requiredXp();
            if (g.xp >= required) {
                g.xp -= required;
                g.level = next.getKey();
                upgradedTo = g.level;
                broadcastGuild(g, "§6§lFreeCore §f§l>>> §a§l公会繁荣度已达标，已自动升至 §e§lLv." + g.level + " §a§l！");
            } else {
                break;
            }
        }
        return upgradedTo;
    }

    protected void broadcastGuild(Guild g, String message) {
        for (UUID uid : g.roles.keySet()) {
            org.bukkit.entity.Player p = Bukkit.getPlayer(uid);
            if (p != null && p.isOnline()) p.sendMessage(message);
        }
        org.bukkit.entity.Player ownerP = Bukkit.getPlayer(g.owner);
        if (ownerP != null && ownerP.isOnline()) ownerP.sendMessage(message);
    }

    @Override public boolean leave(UUID player) {
        if (player == null) return false;
        synchronized (this) {
            String name = members.get(player);
            if (name == null) return false;
            Guild g = guilds.get(name);
            if (g == null) return false;
            synchronized (g) {
                if (g.owner.equals(player) || rank(g, player) == Role.PRESIDENT) {
                    return false;
                }
                members.remove(player, name);
                g.memberCount--;
                g.roles.remove(player);
                leaveTimestamps.put(player, System.currentTimeMillis());
                return true;
            }
        }
    }

    @Override public boolean disband(UUID player) {
        synchronized (this) {
            Guild g = guildFor(player);
            if (g == null || (rank(g, player) != Role.PRESIDENT && !g.owner.equals(player))) return false;
            guilds.remove(g.key, g);
            members.entrySet().removeIf(e -> e.getValue().equals(g.key));
            applications.remove(g.key);
            return true;
        }
    }

    protected Role rank(Guild g, UUID player) {
        return g.owner.equals(player) ? Role.PRESIDENT : g.roles.getOrDefault(player, Role.MEMBER);
    }
    protected int countRole(Guild g, Role role) {
        int count = role == Role.PRESIDENT && g.owner != null ? 1 : 0;
        for (Map.Entry<UUID, Role> e : g.roles.entrySet()) if (e.getValue() == role) count++;
        return count;
    }
    protected int quota(Guild g, Role role) {
        LevelConfig c = level(g.level);
        return switch (role) {
            case PRESIDENT -> c.maxPresidents();
            case VICE_PRESIDENT -> c.maxVicePresidents();
            case ELDER -> c.maxElders();
            case MEMBER -> c.maxMembers();
        };
    }
    protected Guild guildFor(UUID player) {
        String name = player == null ? null : members.get(player);
        return name == null ? null : guilds.get(name);
    }
    protected LevelConfig level(int number) {
        Map.Entry<Integer, LevelConfig> e = levels.floorEntry(number);
        return e != null ? e.getValue() : new LevelConfig(number, 0, 10, 1, 1, 1, 0, List.of());
    }
    @Override public void close() {}

    protected static final class Guild {
        final String key, name;
        UUID owner;
        int level = 1;
        long xp = 0;
        int memberCount = 1;
        final Map<UUID, Role> roles = new HashMap<>();
        final Map<UUID, Long> contributions = new HashMap<>();
        final Map<UUID, LocalDate> checkins = new HashMap<>();
        final Set<String> fakePlayers = new HashSet<>();
        final Set<Integer> claimedUpgradeRewards = new HashSet<>();
        GuildBaseLocation baseLocation = null;

        Guild(String name, UUID owner) {
            this.key = name.toLowerCase(Locale.ROOT);
            this.name = name;
            this.owner = owner;
            this.roles.put(owner, Role.PRESIDENT);
        }
    }
}
