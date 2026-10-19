package io.github.freecoreguild.service;

import io.github.freecoreguild.api.GuildProvider;
import org.bukkit.Location;
import java.util.*;

public interface GuildService extends GuildProvider, AutoCloseable {
    void initialize();
    boolean create(UUID owner, String name);
    boolean apply(UUID player, String guildName);
    List<UUID> applications(UUID player);
    boolean approve(UUID operator, UUID applicant);
    boolean reject(UUID operator, UUID applicant);

    /** 玩家签到 */
    CheckinResult checkinPlayer(UUID player);
    default boolean checkin(UUID player) {
        return checkinPlayer(player).success();
    }

    /** 成员升职 */
    boolean promote(UUID operator, UUID target, io.github.freecoreguild.api.GuildProvider.Role role);
    default boolean promote(UUID operator, UUID target) {
        Optional<io.github.freecoreguild.api.GuildProvider.Role> current = roleOf(target);
        if (current.isEmpty()) return false;
        io.github.freecoreguild.api.GuildProvider.Role[] roles = io.github.freecoreguild.api.GuildProvider.Role.values();
        int next = current.get().ordinal() + 1;
        return next < roles.length && promote(operator, target, roles[next]);
    }

    /** 成员降职 */
    boolean demote(UUID operator, UUID target);

    /** 转让公会 */
    boolean transfer(UUID operator, UUID target);
    TransferResult transferWithReason(UUID operator, UUID target);

    /** 会长领取升级奖励 */
    boolean claimUpgradeReward(UUID player, int level);
    boolean isRewardClaimed(UUID player, int level);

    /** 移除成员 */
    boolean kick(UUID operator, UUID target);

    /** 捐献增加繁荣度并检查自动升级 */
    int donateAndCheckAutoUpgrade(UUID player, long prosperityToAdd);
    default boolean donate(UUID player, long amount) {
        return donateAndCheckAutoUpgrade(player, amount) >= 0;
    }

    boolean leave(UUID player);
    boolean disband(UUID player);

    /** 退出公会冷却检查 (返回剩余冷却毫秒数，0为无冷却) */
    long getLeaveCooldownRemainingMillis(UUID player);
    /** 清除玩家的退会冷却 */
    void clearLeaveCooldown(UUID player);
    /** 重置玩家今日签到记录 */
    void resetCheckinToday(UUID player);
    boolean resetCheckinTodayByName(String playerName);
    boolean clearLeaveCooldownByName(String playerName);
    UUID resolvePlayerUuid(String playerName);
    boolean joinDirectly(UUID player, String guildName);
    /** 设置公会繁荣度（支持升降级） */
    int setGuildProsperity(String guildName, long newProsperity);

    /** 获取公会所有成员信息 */
    List<MemberInfo> getMembers(UUID player);

    /** 获取所有公会列表 */
    List<GuildView> getAllGuilds();

    /** 获取公会中所有会长职位的成员 UUID 列表 */
    List<UUID> getPresidents(UUID player);

    /** 公会基地 (Home/Warp) 相关 */
    boolean setBaseLocation(UUID operator, Location location);
    Optional<GuildBaseLocation> getBaseLocation(UUID player);
    void reloadBaseLocationFromDb(String guildKey);

    boolean reserveFakePlayer(UUID operator, String identifier);
    boolean releaseFakePlayer(UUID operator, String identifier);
    @Override void close();

    record CheckinResult(boolean success, String message, double coins, long prosperity, int goldAmount) {}
    record TransferResult(boolean success, String reason) {}
    record MemberInfo(UUID uuid, String name, io.github.freecoreguild.api.GuildProvider.Role role, long contribution, String lastCheckin) {}
    record GuildBaseLocation(String server, String world, double x, double y, double z, float yaw, float pitch) {}
}
