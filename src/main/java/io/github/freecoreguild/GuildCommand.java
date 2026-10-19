package io.github.freecoreguild;

import io.github.freecoreguild.api.GuildProvider.Role;
import io.github.freecoreguild.lang.Lang;
import io.github.freecoreguild.service.GuildRedisBridge;
import io.github.freecoreguild.service.GuildService;
import io.github.freecoreguild.service.MySqlGuildService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.stream.Collectors;

public final class GuildCommand implements CommandExecutor, TabCompleter {
    private final GuildService service;
    private final EconomyGateway economy;
    private final GuildMenuListener menu;
    private final GuildRedisBridge redisBridge;

    private static final List<String> SUB_COMMANDS = List.of(
            "help", "menu", "info", "checkin", "donate", "create", "apply",
            "members", "transfer", "promote", "demote", "kick", "leave", "disband",
            "reward", "invite", "accept", "deny", "reload", "admin"
    );

    private static final List<String> ADMIN_SUB_COMMANDS = List.of(
            "reload", "resetcheckin", "clearcooldown", "setxp", "refreshshop"
    );

    public GuildCommand(GuildService service, EconomyGateway economy, GuildMenuListener menu, GuildRedisBridge redisBridge) {
        this.service = service;
        this.economy = economy;
        this.menu = menu;
        this.redisBridge = redisBridge;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (a.length > 0 && ("admin".equalsIgnoreCase(a[0]) || "debug".equalsIgnoreCase(a[0]))) {
            handleAdmin(sender, a);
            return true;
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.msg("player-only"));
            return true;
        }

        if (a.length == 0) {
            menu.openMain(p);
            return true;
        }

        String op = a[0].toLowerCase(Locale.ROOT);
        switch (op) {
            case "help", "帮助", "?" -> {
                sendHelp(p);
                return true;
            }
            case "reload", "重载" -> {
                if (!p.isOp() && !p.hasPermission("freecoreguild.admin")) {
                    p.sendMessage(Lang.getPrefix() + "§c你没有权限执行重载指令！");
                    return true;
                }
                FreeCoreGuildPlugin.getInstance().reloadPluginConfig();
                p.sendMessage(Lang.getPrefix() + "§a公会配置文件、等级与商城配置已成功重载！");
                return true;
            }
            case "menu", "gui", "菜单" -> menu.openMain(p);
            case "donate", "捐献" -> {
                if (a.length < 2) {
                    menu.openDonateMenu(p);
                    return true;
                }
                long amount = parse(a[1]);
                if (amount <= 0) {
                    p.sendMessage(Lang.msg("donate-invalid"));
                    return true;
                }
                if (economy != null && economy.donate(p, amount)) {
                    p.sendMessage(Lang.msg("donated", "amount", String.valueOf(amount), "prosperity", String.valueOf(amount)));
                    service.guildOf(p.getUniqueId()).ifPresent(g -> {
                        FreeCoreGuildPlugin.getInstance().broadcastGuildCross(g.name(),
                                "§8[§b公会§8] §e" + p.getName() + " §a向公会捐献了 §e" + amount + " H币§a，繁荣度增加 §e" + amount + "§a！");
                    });
                } else {
                    p.sendMessage(Lang.msg("donate-failed"));
                }
            }
            case "create", "创建" -> {
                if (a.length < 2) {
                    p.sendMessage(Lang.getPrefix() + "§6用法：§e/guild create <公会名称>");
                    return true;
                }
                long cd = service.getLeaveCooldownRemainingMillis(p.getUniqueId());
                if (cd > 0) {
                    long hours = (cd / 3600_000L) + 1;
                    p.sendMessage(Lang.getPrefix() + "§c你刚退出公会不久，尚在冷却期内（剩余约 §e" + hours + " §c小时），暂时无法创建公会！");
                    return true;
                }
                if (economy != null && economy.create(p, a[1])) {
                    p.sendMessage(Lang.msg("created", "name", a[1]));
                    menu.openMain(p);
                    syncAllNodes();
                } else {
                    double cost = economy != null ? economy.createCost() : 10000.0;
                    p.sendMessage(Lang.msg("create-failed", "cost", String.valueOf((long)cost)));
                }
            }
            case "info", "信息" -> service.guildOf(p.getUniqueId()).ifPresentOrElse(
                    g -> p.sendMessage(Lang.msg("info", "name", g.name(), "level", String.valueOf(g.level()), "xp", String.valueOf(g.xp()), "members", String.valueOf(g.members()), "max", String.valueOf(g.maxMembers()))),
                    () -> p.sendMessage(Lang.msg("no-guild"))
            );
            case "apply", "申请" -> {
                if (a.length < 2) {
                    menu.openAllGuildsList(p);
                    return true;
                }
                long cd = service.getLeaveCooldownRemainingMillis(p.getUniqueId());
                if (cd > 0) {
                    long hours = (cd / 3600_000L) + 1;
                    p.sendMessage(Lang.getPrefix() + "§c你刚退出公会不久，尚在冷却期内（剩余约 §e" + hours + " §c小时），暂时无法申请加入公会！");
                    return true;
                }
                if (service.apply(p.getUniqueId(), a[1])) {
                    p.sendMessage(Lang.msg("app-submitted", "name", a[1]));
                    String appNotify = Lang.msg("app-received-notify", "player", p.getName());
                    FreeCoreGuildPlugin.getInstance().broadcastGuildCross(a[1], appNotify);
                } else {
                    p.sendMessage(Lang.msg("app-failed"));
                }
            }
            case "applications", "申请列表" -> menu.openApplicationsMenu(p);
            case "members", "成员" -> menu.openMembersMenu(p);
            case "transfer", "转让" -> {
                if (a.length < 2) {
                    menu.openTransferMenu(p);
                    return true;
                }
                UUID targetId = target(a[1]);
                if (targetId == null) {
                    p.sendMessage(Lang.msg("transfer-not-found", "name", a[1]));
                    return true;
                }
                var res = service.transferWithReason(p.getUniqueId(), targetId);
                if (res.success()) {
                    p.sendMessage(Lang.msg("transfer-success", "player", a[1]));
                    syncAllNodes();
                } else {
                    p.sendMessage(Lang.msg("transfer-failed", "reason", res.reason()));
                }
            }
            case "checkin", "签到" -> {
                var res = service.checkinPlayer(p.getUniqueId());
                if (res.success()) {
                    if (res.goldAmount() > 0) {
                        p.getInventory().addItem(new ItemStack(Material.RAW_GOLD, res.goldAmount()));
                    }
                    p.sendMessage(Lang.getPrefix() + "§a今日公会签到成功！公会增加 §e" + res.prosperity() + " §a点繁荣度，获得奖励 §e" + res.goldAmount() + " 个粗金矿石§a！");
                    service.guildOf(p.getUniqueId()).ifPresent(g -> {
                        FreeCoreGuildPlugin.getInstance().broadcastGuildCross(g.name(),
                                "§8[§b公会§8] §e" + p.getName() + " §a完成了今日公会签到，公会增加 §e" + res.prosperity() + " §a点繁荣度！");
                    });
                } else {
                    p.sendMessage(Lang.msg("checkin-failed", "message", res.message()));
                }
            }
            case "reward", "奖励" -> claim(p, a.length > 1 ? parseInt(a[1]) : -1);
            case "promote", "升职" -> {
                if (a.length < 2) { p.sendMessage(Lang.getPrefix() + "§6用法：§e/guild promote <玩家名>"); return true; }
                UUID targetId = target(a[1]);
                if (targetId != null && service.promote(p.getUniqueId(), targetId)) {
                    p.sendMessage(Lang.msg("promoted", "player", a[1]));
                    syncAllNodes();
                } else {
                    p.sendMessage(Lang.msg("promote-failed"));
                }
            }
            case "demote", "降职" -> {
                if (a.length < 2) { p.sendMessage(Lang.getPrefix() + "§6用法：§e/guild demote <玩家名>"); return true; }
                UUID targetId = target(a[1]);
                if (targetId != null && service.demote(p.getUniqueId(), targetId)) {
                    p.sendMessage(Lang.msg("demoted", "player", a[1]));
                    syncAllNodes();
                } else {
                    p.sendMessage(Lang.msg("demote-failed"));
                }
            }
            case "kick", "踢出" -> {
                if (a.length < 2) { p.sendMessage(Lang.getPrefix() + "§6用法：§e/guild kick <玩家名>"); return true; }
                UUID targetId = target(a[1]);
                if (targetId != null && service.kick(p.getUniqueId(), targetId)) {
                    p.sendMessage(Lang.msg("kicked", "player", a[1]));
                    service.guildOf(p.getUniqueId()).ifPresent(g -> {
                        FreeCoreGuildPlugin.getInstance().broadcastGuildCross(g.name(),
                                "§8[§b公会§8] §c成员 §e" + a[1] + " §c已被移出公会！");
                    });
                    syncAllNodes();
                } else {
                    p.sendMessage(Lang.msg("kick-failed"));
                }
            }
            case "leave", "退出" -> {
                var role = service.roleOf(p.getUniqueId()).orElse(Role.MEMBER);
                if (role == Role.PRESIDENT) {
                    p.sendMessage(Lang.msg("leave-failed-owner"));
                    return true;
                }
                String guildName = service.guildOf(p.getUniqueId()).map(g -> g.name()).orElse("");
                if (service.leave(p.getUniqueId())) {
                    p.sendMessage(Lang.msg("left"));
                    if (!guildName.isEmpty()) {
                        FreeCoreGuildPlugin.getInstance().broadcastGuildCross(guildName,
                                "§8[§b公会§8] §e" + p.getName() + " §7退出了公会。");
                    }
                    syncAllNodes();
                } else {
                    p.sendMessage(Lang.msg("leave-failed"));
                }
            }
            case "disband", "解散" -> {
                String guildName = service.guildOf(p.getUniqueId()).map(g -> g.name()).orElse("");
                if (service.disband(p.getUniqueId())) {
                    p.sendMessage(Lang.msg("disbanded"));
                    if (!guildName.isEmpty()) {
                        FreeCoreGuildPlugin.getInstance().broadcastGuildCross(guildName,
                                "§8[§b公会§8] §c公会已被会长解散！");
                    }
                    syncAllNodes();
                } else {
                    p.sendMessage(Lang.msg("disband-failed"));
                }
            }
            // 需求8: 邀请成员 /guild invite <player>
            case "invite", "邀请" -> {
                if (a.length < 2) {
                    p.sendMessage(Lang.getPrefix() + "§6用法：§e/guild invite <玩家名>");
                    return true;
                }
                var optGuild = service.guildOf(p.getUniqueId());
                if (optGuild.isEmpty()) {
                    p.sendMessage(Lang.msg("no-guild"));
                    return true;
                }
                var role = service.roleOf(p.getUniqueId()).orElse(Role.MEMBER);
                if (role.ordinal() < Role.ELDER.ordinal()) {
                    p.sendMessage(Lang.msg("invite-no-permission"));
                    return true;
                }
                String targetName = a[1];
                UUID targetId = service.resolvePlayerUuid(targetName);
                if (targetId == null) {
                    p.sendMessage(Lang.getPrefix() + "§c未找到玩家“" + targetName + "”！");
                    return true;
                }
                if (service.guildOf(targetId).isPresent()) {
                    p.sendMessage(Lang.msg("invite-target-in-guild", "player", targetName));
                    return true;
                }
                if (service.getLeaveCooldownRemainingMillis(targetId) > 0) {
                    p.sendMessage(Lang.msg("invite-target-cooldown", "player", targetName));
                    return true;
                }

                String guildName = optGuild.get().name();
                if (redisBridge != null) {
                    redisBridge.setInvite(targetId, guildName, p.getName());
                }
                p.sendMessage(Lang.msg("invite-sent", "player", targetName));

                // 跨服通知被邀请玩家
                String inviteMsg = Lang.msg("invite-received", "inviter", p.getName(), "guild", guildName);
                Player onlineTarget = Bukkit.getPlayer(targetId);
                if (onlineTarget != null && onlineTarget.isOnline()) {
                    onlineTarget.sendMessage(inviteMsg);
                } else if (redisBridge != null) {
                    String serverId = Bukkit.getPort() == 25570 ? "technical" : "survival";
                    redisBridge.publish("NOTIFY_PLAYER|" + targetId + "|" + serverId + "|" + inviteMsg);
                    redisBridge.pushOfflineNotification(targetId, inviteMsg);
                }
            }
            // 需求8: 接受邀请 /guild accept
            case "accept", "接受" -> {
                if (service.guildOf(p.getUniqueId()).isPresent()) {
                    p.sendMessage(Lang.msg("already-in-guild"));
                    return true;
                }
                if (service.getLeaveCooldownRemainingMillis(p.getUniqueId()) > 0) {
                    long cd = service.getLeaveCooldownRemainingMillis(p.getUniqueId());
                    long hours = (cd / 3600_000L) + 1;
                    p.sendMessage(Lang.msg("cooldown-active", "hours", String.valueOf(hours)));
                    return true;
                }
                if (redisBridge == null) {
                    p.sendMessage(Lang.msg("invite-none"));
                    return true;
                }
                String[] inv = redisBridge.getInvite(p.getUniqueId());
                if (inv == null) {
                    p.sendMessage(Lang.msg("invite-none"));
                    return true;
                }
                String gName = inv[0];
                redisBridge.removeInvite(p.getUniqueId());

                if (service.joinDirectly(p.getUniqueId(), gName)) {
                    p.sendMessage(Lang.msg("invite-accepted", "guild", gName));
                    FreeCoreGuildPlugin.getInstance().broadcastGuildCross(gName,
                            Lang.msg("invite-accepted-broadcast", "player", p.getName()));
                    syncAllNodes();
                    menu.openMain(p);
                } else {
                    p.sendMessage(Lang.msg("app-approved-failed"));
                }
            }
            // 需求8: 拒绝邀请 /guild deny
            case "deny", "拒绝" -> {
                if (redisBridge == null) {
                    p.sendMessage(Lang.msg("invite-none"));
                    return true;
                }
                String[] inv = redisBridge.getInvite(p.getUniqueId());
                if (inv == null) {
                    p.sendMessage(Lang.msg("invite-none"));
                    return true;
                }
                String gName = inv[0];
                String inviterName = inv[1];
                redisBridge.removeInvite(p.getUniqueId());

                p.sendMessage(Lang.msg("invite-rejected", "guild", gName));
                UUID inviterUuid = service.resolvePlayerUuid(inviterName);
                if (inviterUuid != null) {
                    String rejectMsg = Lang.msg("invite-rejected-notify", "player", p.getName());
                    Player onlineInviter = Bukkit.getPlayer(inviterUuid);
                    if (onlineInviter != null && onlineInviter.isOnline()) {
                        onlineInviter.sendMessage(rejectMsg);
                    } else {
                        String serverId = Bukkit.getPort() == 25570 ? "technical" : "survival";
                        redisBridge.publish("NOTIFY_PLAYER|" + inviterUuid + "|" + serverId + "|" + rejectMsg);
                    }
                }
            }
            default -> sendHelp(p);
        }
        return true;
    }

    private void syncAllNodes() {
        if (redisBridge != null) {
            redisBridge.publish("SYNC_ALL|||");
        }
    }

    private void sendHelp(Player p) {
        p.sendMessage(Lang.getPrefix() + "§6===== FreeCore 公会系统帮助 =====");
        p.sendMessage("§e/guild §7- 打开公会主面板 GUI");
        p.sendMessage("§e/guild info §7- 查看当前公会信息");
        p.sendMessage("§e/guild checkin §7- 每日公会签到");
        p.sendMessage("§e/guild donate [数量] §7- 为公会捐献 H币 (1H币=1繁荣度)");
        p.sendMessage("§e/guild create <名称> §7- 创建全新公会");
        p.sendMessage("§e/guild apply [名称] §7- 申请加入公会 / 浏览公会列表");
        p.sendMessage("§e/guild members §7- 查看公会成员列表");
        p.sendMessage("§e/guild invite <玩家名> §7- 邀请玩家加入公会 (长老及以上)");
        p.sendMessage("§e/guild accept §7- 接受公会入会邀请");
        p.sendMessage("§e/guild deny §7- 拒绝公会入会邀请");
        p.sendMessage("§e/gc <消息> §7- 公会跨服聊天频道");
        p.sendMessage("§e/guild transfer [玩家名] §7- 会长转让公会");
        p.sendMessage("§e/guild promote <玩家名> §7- 晋升公会成员职位");
        p.sendMessage("§e/guild demote <玩家名> §7- 降低公会成员职位");
        p.sendMessage("§e/guild kick <玩家名> §7- 将成员踢出公会");
        p.sendMessage("§e/guild leave §7- 退出当前公会");
        p.sendMessage("§e/guild disband §7- 会长解散公会");
        if (p.isOp() || p.hasPermission("freecoreguild.admin")) {
            p.sendMessage("§b/guild reload §7- 重载公会所有配置文件");
            p.sendMessage("§b/guild admin §7- 查看 OP/Debug 管理员指令");
        }
    }

    private void handleAdmin(CommandSender sender, String[] a) {
        if (!sender.isOp() && !sender.hasPermission("freecoreguild.admin")) {
            sender.sendMessage(Lang.getPrefix() + "§c你没有权限执行该公会管理指令！");
            return;
        }

        if (a.length < 2) {
            sender.sendMessage(Lang.getPrefix() + "§6=== 公会 OP/Debug 管理指令 ===");
            sender.sendMessage("§e/guild admin reload §7- 重载公会配置与商城");
            sender.sendMessage("§e/guild admin resetcheckin <玩家名> §7- 跨服重置该玩家今日签到");
            sender.sendMessage("§e/guild admin clearcooldown <玩家名> §7- 跨服清空该玩家的退会冷却时间");
            sender.sendMessage("§e/guild admin setxp <公会名> <繁荣度> §7- 设置公会繁荣度 (自动触发升降级)");
            sender.sendMessage("§e/guild admin refreshshop §7- 跨服刷新/重置所有公会商店限购");
            return;
        }

        String sub = a[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                FreeCoreGuildPlugin.getInstance().reloadPluginConfig();
                sender.sendMessage(Lang.getPrefix() + "§a公会配置文件、等级与商城配置已成功重载！");
            }
            case "resetcheckin" -> {
                if (a.length < 3) {
                    sender.sendMessage(Lang.getPrefix() + "§6用法：§e/guild admin resetcheckin <玩家名>");
                    return;
                }
                String targetPlayer = a[2];
                // 修复2: 必须跨服准确重置签到，不能无论输谁都说成功
                boolean ok = service.resetCheckinTodayByName(targetPlayer);
                if (ok) {
                    sender.sendMessage(Lang.getPrefix() + "§a已成功重置玩家 §e" + targetPlayer + " §a的今日公会签到状态！");
                    syncAllNodes();
                } else {
                    sender.sendMessage(Lang.getPrefix() + "§c重置失败！未找到玩家“§e" + targetPlayer + "§c”的信息。");
                }
            }
            case "clearcooldown" -> {
                if (a.length < 3) {
                    sender.sendMessage(Lang.getPrefix() + "§6用法：§e/guild admin clearcooldown <玩家名>");
                    return;
                }
                String targetPlayer = a[2];
                // 修复2: 跨服准确清空冷却
                boolean ok = service.clearLeaveCooldownByName(targetPlayer);
                if (ok) {
                    sender.sendMessage(Lang.getPrefix() + "§a已成功清空玩家 §e" + targetPlayer + " §a的退出公会冷却时间！");
                    syncAllNodes();
                } else {
                    sender.sendMessage(Lang.getPrefix() + "§c清除失败！未找到玩家“§e" + targetPlayer + "§c”的信息。");
                }
            }
            case "setxp" -> {
                if (a.length < 4) {
                    sender.sendMessage(Lang.getPrefix() + "§6用法：§e/guild admin setxp <公会名> <繁荣度>");
                    return;
                }
                String guildName = a[2];
                long xp = parse(a[3]);
                if (xp < 0) {
                    sender.sendMessage(Lang.getPrefix() + "§c繁荣度数值必须大于等于0！");
                    return;
                }
                int newLvl = service.setGuildProsperity(guildName, xp);
                if (newLvl > 0) {
                    sender.sendMessage(Lang.getPrefix() + "§a成功将公会 §e" + guildName + " §a的繁荣度设为 §e" + xp + "§a，当前公会等级自动调整为 §eLv." + newLvl + "§a！");
                    syncAllNodes();
                } else {
                    sender.sendMessage(Lang.getPrefix() + "§c未找到名称为 §e" + guildName + " §c的公会！");
                }
            }
            case "refreshshop" -> {
                // 需求9: admin 商店限购刷新
                if (service instanceof MySqlGuildService mysql) {
                    mysql.clearAllShopLimits();
                }
                if (FreeCoreGuildPlugin.getInstance().getShopService() != null) {
                    FreeCoreGuildPlugin.getInstance().getShopService().clearAllLimits();
                }
                if (redisBridge != null) {
                    redisBridge.publish("SHOP_REFRESH|||");
                }
                sender.sendMessage(Lang.getPrefix() + Lang.msg("admin-shop-refreshed"));
            }
            default -> sender.sendMessage(Lang.getPrefix() + "§c未知管理指令，请输入 §e/guild admin §c查看帮助。");
        }
    }

    private void claim(Player p, int level) {
        List<String> configuredRewards = FreeCoreGuildPlugin.getInstance().getUpgradeRewardsForLevel(level);
        if (configuredRewards.isEmpty()) {
            p.sendMessage(Lang.msg("reward-not-unlocked"));
            return;
        }
        if (!service.claimUpgradeReward(p.getUniqueId(), level)) {
            p.sendMessage(Lang.msg("reward-already-claimed"));
            return;
        }
        for (String configured : configuredRewards) {
            String text = configured.replace("{player}", p.getName());
            if (text.regionMatches(true, 0, "console:", 0, 8)) {
                org.bukkit.Bukkit.dispatchCommand(org.bukkit.Bukkit.getConsoleSender(), text.substring(8).trim());
            } else if (text.regionMatches(true, 0, "op:", 0, 3)) {
                boolean wasOp = p.isOp();
                try {
                    p.setOp(true);
                    p.performCommand(text.substring(3).trim());
                } finally {
                    p.setOp(wasOp);
                }
            } else if (text.regionMatches(true, 0, "item:", 0, 5)) {
                String[] x = text.substring(5).trim().split("\\s+");
                try {
                    Material m = Material.matchMaterial(x[0]);
                    int n = x.length > 1 ? Integer.parseInt(x[1]) : 1;
                    if (m != null && n > 0) p.getInventory().addItem(new ItemStack(m, n));
                } catch (Exception ignored) {}
            }
        }
        p.sendMessage(Lang.msg("reward-claimed", "level", String.valueOf(level)));
        syncAllNodes();
    }

    private UUID target(String name) {
        UUID uid = service.resolvePlayerUuid(name);
        return uid;
    }

    private long parse(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return -1; }
    }

    private int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return -1; }
    }

    // 修复1: 完善指令 Tab 补全
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUB_COMMANDS.stream()
                    .filter(s -> s.startsWith(prefix))
                    .collect(Collectors.toList());
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if ("admin".equals(sub)) {
                if (!sender.isOp() && !sender.hasPermission("freecoreguild.admin")) {
                    return Collections.emptyList();
                }
                String prefix = args[1].toLowerCase(Locale.ROOT);
                return ADMIN_SUB_COMMANDS.stream()
                        .filter(s -> s.startsWith(prefix))
                        .collect(Collectors.toList());
            }

            if (List.of("promote", "demote", "kick", "transfer", "invite").contains(sub)) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                List<String> playerNames = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        playerNames.add(p.getName());
                    }
                }
                return playerNames;
            }

            if ("apply".equals(sub)) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                return service.getAllGuilds().stream()
                        .map(g -> g.name())
                        .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .collect(Collectors.toList());
            }
        }

        if (args.length == 3 && "admin".equalsIgnoreCase(args[0])) {
            String adminSub = args[1].toLowerCase(Locale.ROOT);
            if (List.of("resetcheckin", "clearcooldown").contains(adminSub)) {
                String prefix = args[2].toLowerCase(Locale.ROOT);
                List<String> playerNames = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        playerNames.add(p.getName());
                    }
                }
                return playerNames;
            }
            if ("setxp".equals(adminSub)) {
                String prefix = args[2].toLowerCase(Locale.ROOT);
                return service.getAllGuilds().stream()
                        .map(g -> g.name())
                        .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .collect(Collectors.toList());
            }
        }

        return Collections.emptyList();
    }
}
