package io.github.freecoreguild;

import io.github.freecoreguild.api.GuildProvider.Role;
import io.github.freecoreguild.lang.Lang;
import io.github.freecoreguild.service.GuildRedisBridge;
import io.github.freecoreguild.service.GuildService;
import io.github.freecoreguild.service.GuildService.MemberInfo;
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
            "help", "menu", "info", "checkin", "donate", "create", "apply", "join",
            "members", "transfer", "promote", "demote", "kick", "leave", "disband",
            "reward", "invite", "accept", "deny", "reload", "admin"
    );

    private static final List<String> ADMIN_SUB_COMMANDS = List.of(
            "reload", "resetcheckin", "clearcooldown", "setxp", "refreshshop", "checkin", "cooldown", "prosperity"
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
            try {
                handleAdmin(sender, a);
            } catch (IllegalStateException e) {
                Bukkit.getLogger().warning("[FreeCoreGuild] Admin operation failed: " + e.getMessage());
                sender.sendMessage(Lang.getPrefix() + Lang.text("commands.admin-operation-failed"));
            }
            return true;
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage(Lang.msg("player-only"));
            return true;
        }

        if (!p.hasPermission("freecoreguild.use")) {
            p.sendMessage(Lang.getPrefix() + Lang.text("commands.no-permission"));
            return true;
        }

        if (a.length == 0) {
            menu.openMain(p);
            return true;
        }

        String op = a[0].toLowerCase(Locale.ROOT);
        try {
        switch (op) {
            case "help", "帮助", "?" -> {
                sendHelp(p);
                return true;
            }
            case "reload", "重载" -> {
                if (!p.isOp() && !p.hasPermission("freecoreguild.admin")) {
                    p.sendMessage(Lang.getPrefix() + Lang.text("commands.reload-no-permission"));
                    return true;
                }
                FreeCoreGuildPlugin.getInstance().reloadPluginConfig();
                p.sendMessage(Lang.getPrefix() + Lang.text("commands.reloaded"));
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
                    p.sendMessage(Lang.msg("donated", "amount", String.valueOf(amount), "prosperity", String.valueOf(economy.prosperityFor(amount))));

                } else {
                    p.sendMessage(Lang.msg("donate-failed"));
                }
            }
            case "create", "创建" -> {
                if (a.length < 2) {
                    p.sendMessage(Lang.getPrefix() + Lang.text("commands.usage-create"));
                    return true;
                }
                long cd = service.getLeaveCooldownRemainingMillis(p.getUniqueId());
                if (cd > 0) {
                    long hours = (cd / 3600_000L) + 1;
                    p.sendMessage(Lang.getPrefix() + Lang.text("commands.create-cooldown", "hours", String.valueOf(hours)));
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
            case "info", "信息" -> service.guildOf(p.getUniqueId()).ifPresentOrElse(g -> {
                        long nextProsperity = FreeCoreGuildPlugin.getInstance().getNextLevelProsperity(g.level());
                        String nextText = nextProsperity < 0 ? Lang.text("messages.max-level")
                                : FreeCoreGuildPlugin.getInstance().formatProsperity(nextProsperity);
                        p.sendMessage(Lang.msg("info", "name", g.name(), "level", String.valueOf(g.level()),
                                "xp", FreeCoreGuildPlugin.getInstance().formatProsperity(g.xp()),
                                "next_xp", nextText, "members", String.valueOf(g.members()), "max", String.valueOf(g.maxMembers())));
                    }, () -> p.sendMessage(Lang.msg("no-guild"))
            );
            case "apply", "join", "申请", "加入" -> {
                if (a.length < 2) {
                    menu.openAllGuildsList(p);
                    return true;
                }
                long cd = service.getLeaveCooldownRemainingMillis(p.getUniqueId());
                if (cd > 0) {
                    long hours = (cd / 3600_000L) + 1;
                    p.sendMessage(Lang.getPrefix() + Lang.text("commands.apply-cooldown", "hours", String.valueOf(hours)));
                    return true;
                }
                if (service.apply(p.getUniqueId(), a[1])) {
                    p.sendMessage(Lang.msg("app-submitted", "name", a[1]));
                    String appNotify = Lang.msg("app-received-notify", "player", p.getName());
                    FreeCoreGuildPlugin.getInstance().broadcastGuildCross(a[1], appNotify);
                    syncAllNodes();
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
                var guild = service.guildOf(p.getUniqueId()).orElse(null);
                var targetGuild = targetId == null ? null : service.guildOf(targetId).orElse(null);
                if (guild == null || targetGuild == null || !guild.name().equalsIgnoreCase(targetGuild.name())) {
                    p.sendMessage(Lang.msg("transfer-not-found", "name", a[1]));
                    return true;
                }
                String targetName = service.getMembers(p.getUniqueId()).stream()
                        .filter(member -> member.uuid().equals(targetId))
                        .map(MemberInfo::name).findFirst().orElse(null);
                if (targetName == null) {
                    p.sendMessage(Lang.msg("transfer-not-found", "name", a[1]));
                    return true;
                }
                menu.requestTransferConfirmation(p, targetId, targetName);
            }
            case "checkin", "签到" -> {
                int beforeLevel = service.guildOf(p.getUniqueId()).map(g -> g.level()).orElse(0);
                var res = service.checkinPlayer(p.getUniqueId());
                if (res.success()) {
                    if (res.goldAmount() > 0) {
                        java.time.DayOfWeek dow = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).getDayOfWeek();
                        boolean isWeekend = (dow == java.time.DayOfWeek.SATURDAY || dow == java.time.DayOfWeek.SUNDAY);
                        Material rewardMat = FreeCoreGuildPlugin.getInstance().getCheckinRewardMaterial(isWeekend);
                        p.getInventory().addItem(new ItemStack(rewardMat, res.goldAmount()));
                    }
                    p.sendMessage(Lang.getPrefix() + Lang.text("commands.checkin-success", "prosperity", String.valueOf(res.prosperity()), "amount", String.valueOf(res.goldAmount())));
                    service.guildOf(p.getUniqueId()).ifPresent(g -> {
                        FreeCoreGuildPlugin.getInstance().broadcastGuildCross(g.name(),
                                Lang.text("broadcast.checkin", "player", String.valueOf(p.getName()), "prosperity", String.valueOf(res.prosperity())));
                        if (g.level() > beforeLevel) {
                            FreeCoreGuildPlugin.getInstance().broadcastGuildCross(g.name(),
                                    Lang.text("broadcast.upgrade", "level", String.valueOf(g.level())));
                        }
                    });
                    syncAllNodes();
                    FreeCoreGuildPlugin.getInstance().refreshGuildMenus();
                } else {
                    p.sendMessage(Lang.msg("checkin-failed", "message", res.message()));
                }
            }
            case "reward", "奖励" -> claim(p, a.length > 1 ? parseInt(a[1]) : -1);
            case "promote", "升职" -> {
                if (a.length < 2) { p.sendMessage(Lang.getPrefix() + Lang.text("commands.usage-promote")); return true; }
                UUID targetId = target(a[1]);
                if (targetId != null && service.promote(p.getUniqueId(), targetId)) {
                    Role newRole = service.roleOf(targetId).orElse(Role.MEMBER);
                    p.sendMessage(Lang.getPrefix() + Lang.text("commands.promoted", "player", String.valueOf(a[1]), "role", String.valueOf(newRole.getMenuDisplay())));
                    service.guildOf(p.getUniqueId()).ifPresent(g -> {
                        FreeCoreGuildPlugin.getInstance().broadcastGuildCross(g.name(),
                                Lang.text("broadcast.promote", "player", String.valueOf(a[1]), "role", String.valueOf(newRole.getMenuDisplay())));
                    });
                    syncAllNodes();
                } else {
                    p.sendMessage(Lang.msg("promote-failed"));
                }
            }
            case "demote", "降职" -> {
                if (a.length < 2) { p.sendMessage(Lang.getPrefix() + Lang.text("commands.usage-demote")); return true; }
                UUID targetId = target(a[1]);
                if (targetId != null && service.demote(p.getUniqueId(), targetId)) {
                    Role newRole = service.roleOf(targetId).orElse(Role.MEMBER);
                    p.sendMessage(Lang.getPrefix() + Lang.text("commands.demoted", "player", String.valueOf(a[1]), "role", String.valueOf(newRole.getMenuDisplay())));
                    service.guildOf(p.getUniqueId()).ifPresent(g -> {
                        FreeCoreGuildPlugin.getInstance().broadcastGuildCross(g.name(),
                                Lang.text("broadcast.demote", "player", String.valueOf(a[1]), "role", String.valueOf(newRole.getMenuDisplay())));
                    });
                    syncAllNodes();
                } else {
                    p.sendMessage(Lang.msg("demote-failed"));
                }
            }
            case "kick", "踢出" -> {
                if (a.length < 2) { p.sendMessage(Lang.getPrefix() + Lang.text("commands.usage-kick")); return true; }
                UUID targetId = target(a[1]);
                if (targetId != null) menu.requestKickConfirmation(p, targetId, a[1]);
                else p.sendMessage(Lang.msg("kick-failed"));
            }
            case "leave", "退出" -> menu.requestLeaveConfirmation(p);
            case "disband", "解散" -> menu.requestDisbandConfirmation(p);
            // 需求8: 邀请成员 /guild invite <player>
            case "invite", "邀请" -> {
                if (a.length < 2) {
                    p.sendMessage(Lang.getPrefix() + Lang.text("commands.usage-invite"));
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
                    p.sendMessage(Lang.getPrefix() + Lang.text("commands.player-not-found", "player", String.valueOf(targetName)));
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
                if (!service.addInvite(targetId, guildName, p.getName())) {
                    p.sendMessage(Lang.getPrefix() + Lang.text("commands.invite-save-failed"));
                    return true;
                }
                p.sendMessage(Lang.msg("invite-sent", "player", targetName));
                FreeCoreGuildPlugin.getInstance().refreshInviteMenus(targetId);

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
                List<GuildService.GuildInvite> invites = service.getInvitesFor(p.getUniqueId());
                if (invites.isEmpty()) {
                    p.sendMessage(Lang.msg("invite-none"));
                    return true;
                }
                GuildService.GuildInvite invite = invites.get(0);
                String gName = invite.guildName();
                if (service.acceptInvite(p.getUniqueId(), gName)) {
                    p.sendMessage(Lang.msg("invite-accepted", "guild", gName));
                    FreeCoreGuildPlugin.getInstance().broadcastGuildCross(gName,
                            Lang.text("broadcast.join", "player", p.getName()));
                    syncAllNodes();
                    FreeCoreGuildPlugin.getInstance().refreshGuildMenus();
                    menu.openMain(p);
                } else {
                    p.sendMessage(Lang.msg("app-approved-failed"));
                }
            }
            // 需求8: 拒绝邀请 /guild deny
            case "deny", "拒绝" -> {
                List<GuildService.GuildInvite> invites = service.getInvitesFor(p.getUniqueId());
                if (invites.isEmpty()) {
                    p.sendMessage(Lang.msg("invite-none"));
                    return true;
                }
                GuildService.GuildInvite invite = invites.get(0);
                String gName = invite.guildName();
                String inviterName = invite.inviterName();
                service.removeInvite(p.getUniqueId(), gName);
                FreeCoreGuildPlugin.getInstance().refreshInviteMenus(p.getUniqueId());

                p.sendMessage(Lang.msg("invite-rejected", "guild", gName));
                UUID inviterUuid = service.resolvePlayerUuid(inviterName);
                if (inviterUuid != null) {
                    String rejectMsg = Lang.msg("invite-rejected-notify", "player", p.getName());
                    Player onlineInviter = Bukkit.getPlayer(inviterUuid);
                    if (onlineInviter != null && onlineInviter.isOnline()) {
                        onlineInviter.sendMessage(rejectMsg);
                    } else if (redisBridge != null) {
                        String serverId = Bukkit.getPort() == 25570 ? "technical" : "survival";
                        redisBridge.publish("NOTIFY_PLAYER|" + inviterUuid + "|" + serverId + "|" + rejectMsg);
                    }
                }
            }
            default -> sendHelp(p);
        }
        } catch (IllegalStateException e) {
            Bukkit.getLogger().warning("[FreeCoreGuild] Guild operation failed: " + e.getMessage());
            p.sendMessage(Lang.getPrefix() + Lang.text("commands.operation-failed"));
        }
        return true;
    }

    private void syncAllNodes() {
        if (redisBridge != null) {
            redisBridge.publish("SYNC_ALL|||");
        }
    }

    private void sendHelp(Player p) {
        p.sendMessage(Lang.getPrefix() + Lang.text("commands.help-title"));
        Lang.lines("commands.help").forEach(p::sendMessage);
        if (p.isOp() || p.hasPermission("freecoreguild.admin")) {
            Lang.lines("commands.help-admin").forEach(p::sendMessage);
        }
    }

    private void handleAdmin(CommandSender sender, String[] a) {
        if (!sender.isOp() && !sender.hasPermission("freecoreguild.admin")) {
            sender.sendMessage(Lang.getPrefix() + Lang.text("commands.admin-no-permission"));
            return;
        }

        if (a.length < 2) {
            sender.sendMessage(Lang.getPrefix() + Lang.text("commands.admin-help-title"));
            Lang.lines("commands.admin-help").forEach(sender::sendMessage);
            return;
        }

        String sub = a[1].toLowerCase(Locale.ROOT);
        // Accept descriptive forms without changing the original compact command syntax.
        if (List.of("checkin", "cooldown").contains(sub)) {
            String action = "checkin".equals(sub) ? "reset" : "clear";
            if (a.length < 4 || !action.equalsIgnoreCase(a[2])) {
                sender.sendMessage(Lang.getPrefix() + Lang.text("commands.admin-usage-action", "sub", String.valueOf(sub), "action", String.valueOf(action)));
                return;
            }
            a = new String[]{a[0], "checkin".equals(sub) ? "resetcheckin" : "clearcooldown", a[3]};
            sub = a[1];
        } else if (List.of("prosperity", "propserity").contains(sub)) {
            if (a.length > 2 && "set".equalsIgnoreCase(a[2])) {
                a = a.length >= 5 ? new String[]{a[0], "setxp", a[3], a[4]} : new String[]{a[0], "setxp"};
            }
            sub = "setxp";
        }
        switch (sub) {
            case "reload" -> {
                FreeCoreGuildPlugin.getInstance().reloadPluginConfig();
                sender.sendMessage(Lang.getPrefix() + Lang.text("commands.reloaded"));
            }
            case "resetcheckin" -> {
                if (a.length < 3) {
                    sender.sendMessage(Lang.getPrefix() + Lang.text("commands.admin-usage-resetcheckin"));
                    return;
                }
                String targetPlayer = a[2];
                // 修复2: 必须跨服准确重置签到，不能无论输谁都说成功
                boolean ok = service.resetCheckinTodayByName(targetPlayer);
                if (ok) {
                    sender.sendMessage(Lang.getPrefix() + Lang.text("commands.admin-checkin-reset", "player", String.valueOf(targetPlayer)));
                    syncAllNodes();
                } else {
                    sender.sendMessage(Lang.getPrefix() + Lang.text("commands.admin-checkin-reset-failed", "player", String.valueOf(targetPlayer)));
                }
            }
            case "clearcooldown" -> {
                if (a.length < 3) {
                    sender.sendMessage(Lang.getPrefix() + Lang.text("commands.admin-usage-clearcooldown"));
                    return;
                }
                String targetPlayer = a[2];
                // 修复2: 跨服准确清空冷却
                boolean ok = service.clearLeaveCooldownByName(targetPlayer);
                if (ok) {
                    sender.sendMessage(Lang.getPrefix() + Lang.text("commands.admin-cooldown-cleared", "player", String.valueOf(targetPlayer)));
                    syncAllNodes();
                } else {
                    sender.sendMessage(Lang.getPrefix() + Lang.text("commands.admin-cooldown-clear-failed", "player", String.valueOf(targetPlayer)));
                }
            }
            case "setxp" -> {
                if (a.length < 4) {
                    sender.sendMessage(Lang.getPrefix() + Lang.text("commands.admin-usage-setxp"));
                    return;
                }
                String guildName = a[2];
                long xp = parse(a[3]);
                if (xp < 0) {
                    sender.sendMessage(Lang.getPrefix() + Lang.text("commands.admin-prosperity-invalid"));
                    return;
                }
                int newLvl = service.setGuildProsperity(guildName, xp);
                if (newLvl > 0) {
                    sender.sendMessage(Lang.getPrefix() + Lang.text("commands.admin-prosperity-set", "guild", String.valueOf(guildName), "xp", String.valueOf(xp), "newLvl", String.valueOf(newLvl)));
                    syncAllNodes();
                } else {
                    sender.sendMessage(Lang.getPrefix() + Lang.text("commands.admin-prosperity-set-failed", "guild", String.valueOf(guildName)));
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
                sender.sendMessage(Lang.msg("admin-shop-refreshed"));
            }
            default -> sender.sendMessage(Lang.getPrefix() + Lang.text("commands.admin-unknown"));
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
        service.guildOf(p.getUniqueId()).ifPresent(g -> FreeCoreGuildPlugin.getInstance().broadcastGuildCross(g.name(),
                Lang.text("broadcast.reward",
                        "player", p.getName(), "level", String.valueOf(level))));
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
        boolean adminCommand = args.length > 0 && ("admin".equalsIgnoreCase(args[0]) || "debug".equalsIgnoreCase(args[0]));
        if (adminCommand && !sender.isOp() && !sender.hasPermission("freecoreguild.admin")) return Collections.emptyList();
        if (adminCommand && args.length >= 3) {
            String sub = args[1].toLowerCase(Locale.ROOT);
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            if (List.of("checkin", "cooldown").contains(sub) && args.length == 3) {
                String action = "checkin".equals(sub) ? "reset" : "clear";
                return action.startsWith(prefix) ? List.of(action) : List.of();
            }
            boolean playerTarget = args.length == 3 && List.of("resetcheckin", "clearcooldown").contains(sub)
                    || args.length == 4 && ("checkin".equals(sub) && "reset".equalsIgnoreCase(args[2])
                    || "cooldown".equals(sub) && "clear".equalsIgnoreCase(args[2]));
            if (playerTarget) return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix)).collect(Collectors.toList());
            boolean guildTarget = args.length == 3 && List.of("setxp", "prosperity", "propserity").contains(sub)
                    || args.length == 4 && List.of("prosperity", "propserity").contains(sub) && "set".equalsIgnoreCase(args[2]);
            if (guildTarget) return service.getAllGuilds().stream().map(g -> g.name())
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix)).collect(Collectors.toList());
            return Collections.emptyList();
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUB_COMMANDS.stream()
                    .filter(s -> s.startsWith(prefix))
                    .collect(Collectors.toList());
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (adminCommand) {
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

            if ("apply".equals(sub) || "join".equals(sub)) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                return service.getAllGuilds().stream()
                        .map(g -> g.name())
                        .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .collect(Collectors.toList());
            }
        }

        return Collections.emptyList();
    }
}
