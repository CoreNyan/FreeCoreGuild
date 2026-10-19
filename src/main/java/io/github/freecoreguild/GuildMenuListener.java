package io.github.freecoreguild;

import io.github.freecoreguild.api.GuildProvider.GuildView;
import io.github.freecoreguild.api.GuildProvider.Role;
import io.github.freecoreguild.lang.Lang;
import io.github.freecoreguild.service.GuildBaseTransferService;
import io.github.freecoreguild.service.GuildRedisBridge;
import io.github.freecoreguild.service.GuildService;
import io.github.freecoreguild.service.GuildService.CheckinResult;
import io.github.freecoreguild.service.GuildService.MemberInfo;
import io.github.freecoreguild.service.GuildService.TransferResult;
import io.github.freecoreguild.service.ShopItem;
import io.github.freecoreguild.service.ShopService;
import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 全面完善的公会 GUI 与聊天栏输入监听器（文案已全量抽离至 zh_CN.yml）
 */
public final class GuildMenuListener implements Listener {
    private final GuildService service;
    private final ShopService shopService;
    private final EconomyGateway economy;
    private final GuildBaseTransferService baseTransferService;

    private final Set<UUID> pendingDonateInput = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingApplyInput = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingCreateInput = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingTransferInput = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingInviteInput = ConcurrentHashMap.newKeySet();

    public GuildMenuListener(GuildService service, ShopService shopService, EconomyGateway economy,
                             GuildBaseTransferService baseTransferService) {
        this.service = service;
        this.shopService = shopService;
        this.economy = economy;
        this.baseTransferService = baseTransferService;
    }

    public void openMain(Player player) {
        Optional<GuildView> opt = service.guildOf(player.getUniqueId());
        if (opt.isEmpty()) {
            openNoGuildMenu(player);
        } else {
            openGuildMainMenu(player, opt.get());
        }
    }

    private void playClick(Player p) {
        try { p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.2f); } catch (Exception ignored) {}
    }
    private void playSuccess(Player p) {
        try { p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.9f, 1.4f); } catch (Exception ignored) {}
    }
    private void playFail(Player p) {
        try { p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.9f, 1.0f); } catch (Exception ignored) {}
    }

    private void applyPlayerSkull(SkullMeta sm, UUID playerUuid, String fallbackName) {
        if (playerUuid == null) return;
        Player online = Bukkit.getPlayer(playerUuid);
        if (online != null && online.isOnline()) {
            sm.setPlayerProfile(online.getPlayerProfile());
        } else {
            OfflinePlayer op = Bukkit.getOfflinePlayer(playerUuid);
            sm.setOwningPlayer(op);
            try {
                String name = fallbackName != null ? fallbackName : op.getName();
                PlayerProfile profile = Bukkit.createProfile(playerUuid, name);
                if (profile.hasTextures()) {
                    sm.setPlayerProfile(profile);
                } else {
                    profile.complete(false);
                    sm.setPlayerProfile(profile);
                }
            } catch (Throwable ignored) {}
        }
    }

    private UUID getRandomPresidentUuid(Player player, GuildView g) {
        List<UUID> presidents = service.getPresidents(player.getUniqueId());
        if (presidents == null || presidents.isEmpty()) {
            return g.owner();
        }
        return presidents.get(ThreadLocalRandom.current().nextInt(presidents.size()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        GuildRedisBridge bridge = FreeCoreGuildPlugin.getInstance().getRedisBridge();
        if (bridge != null) {
            Bukkit.getScheduler().runTaskLaterAsynchronously(FreeCoreGuildPlugin.getInstance(), () -> {
                List<String> offline = bridge.pollOfflineNotifications(player.getUniqueId());
                if (!offline.isEmpty()) {
                    Bukkit.getScheduler().runTask(FreeCoreGuildPlugin.getInstance(), () -> {
                        if (player.isOnline()) {
                            for (String msg : offline) {
                                player.sendMessage(msg);
                            }
                        }
                    });
                }
            }, 20L);
        }
    }

    /* ══════════════════════════════════════════════════════════════ */
    /*                   界面 1: 无公会展示菜单                     */
    /* ══════════════════════════════════════════════════════════════ */
    public void openNoGuildMenu(Player player) {
        playClick(player);
        Inventory inv = Bukkit.createInventory(new MenuHolder(MenuType.NO_GUILD), 27,
                Lang.raw("menus.no-guild.title", "&3加入或创建公会"));
        fill(inv);

        long cd = service.getLeaveCooldownRemainingMillis(player.getUniqueId());
        long hours = (cd / 3600_000L) + 1;
        String cdText = cd > 0
                ? Lang.raw("menus.no-guild.cooldown-tip", "&c[当前退会冷却中，剩余约 {hours} 小时]", "hours", String.valueOf(hours))
                : Lang.raw("menus.no-guild.normal-tip", "&7点击后在聊天栏直接输入你要创建的公会名称");

        put(inv, 11, Material.COMPASS,
                Lang.raw("menus.no-guild.join-title", "&a&l【加入公会】"),
                Lang.list("menus.no-guild.join-lore", List.of("&7点击浏览全服所有公会列表", "&7也可在列表中点击直接提交入会申请")));

        double cost = economy != null ? economy.createCost() : 10000.0;
        put(inv, 15, Material.ANVIL,
                Lang.raw("menus.no-guild.create-title", "&e&l【创建公会】"),
                Lang.list("menus.no-guild.create-lore", List.of("&7花费 &e{cost} H币 &7创建全新的公会", "{cooldown}", "&8(输入的内容已被系统保护，不会发送至公屏)"),
                        "cost", String.valueOf((long)cost), "cooldown", cdText));

        put(inv, 22, Material.BARRIER,
                Lang.raw("menus.common.close", "&c&l关闭界面"),
                Lang.list("menus.common.close-lore", List.of("&7点击关闭菜单")));
        player.openInventory(inv);
    }

    /* ══════════════════════════════════════════════════════════════ */
    /*                   界面 2: 有公会主面板                       */
    /* ══════════════════════════════════════════════════════════════ */
    public void openGuildMainMenu(Player player, GuildView g) {
        playClick(player);
        Inventory inv = Bukkit.createInventory(new MenuHolder(MenuType.MAIN), 36,
                Lang.raw("menus.main.title", "&3公会面板 - {name}", "name", g.name()));
        fill(inv);

        Role role = service.roleOf(player.getUniqueId()).orElse(Role.MEMBER);

        ItemStack infoItem = new ItemStack(Material.BEACON);
        ItemMeta meta = infoItem.getItemMeta();
        meta.setDisplayName(Lang.raw("menus.main.info-title", "&b&l【{name}】 &eLv.{level}", "name", g.name(), "level", String.valueOf(g.level())));
        meta.setLore(Lang.list("menus.main.info-lore", List.of(
                "&7-----------------------------",
                "&f公会等级: &eLv.{level}",
                "&f当前繁荣度: &e{xp} &7(达标后由系统自动升级)",
                "&f成员数量: &e{members} &7/ &f{max}",
                "&f我的职位: {role}",
                "&f假人配额: &e{fake_current} &7/ &f{fake_max}",
                "&7-----------------------------"
        ), "name", g.name(), "level", String.valueOf(g.level()), "xp", String.valueOf(g.xp()),
                "members", String.valueOf(g.members()), "max", String.valueOf(g.maxMembers()),
                "role", role.getMenuDisplay(),
                "fake_current", String.valueOf(service.currentFakePlayers(player.getUniqueId())),
                "fake_max", String.valueOf(service.fakePlayerQuota(player.getUniqueId()))));
        infoItem.setItemMeta(meta);
        inv.setItem(4, infoItem);

        put(inv, 10, Material.GOLD_INGOT,
                Lang.raw("menus.main.checkin-title", "&a&l每日签到"),
                Lang.list("menus.main.checkin-lore", List.of(
                        "&7每日签到可领取矿石奖励",
                        "&7并为公会增加巨额繁荣度！",
                        "&8(周末签到可获得三倍繁荣度奖励)"
                )));

        put(inv, 12, Material.SUNFLOWER,
                Lang.raw("menus.main.donate-title", "&6&lH币捐献"),
                Lang.list("menus.main.donate-lore", List.of(
                        "&7为公会添砖加瓦，提升繁荣度",
                        "&a汇率: 1 H币 = 1 繁荣度",
                        "&7公会升级将解锁更多假人配额与商城！"
                )));

        put(inv, 14, Material.CHEST,
                Lang.raw("menus.main.shop-title", "&d&l公会商城"),
                Lang.list("menus.main.shop-lore", List.of(
                        "&7浏览并购买公会专属商品",
                        "&7职位越高可享受更低折扣与专属配额！"
                )));

        String actionTip = role.canOperateFakePlayers()
                ? Lang.raw("menus.main.base-lore-admin", "&e[会长/副会长] 点击打开传送或设置菜单")
                : Lang.raw("menus.main.base-lore-member", "&a[普通成员] 点击直接传送到公会基地");

        put(inv, 16, Material.LODESTONE,
                Lang.raw("menus.main.base-title", "&b&l公会基地"),
                Lang.list("menus.main.base-lore-desc", List.of(
                        "&7传送至公会设立的专属基地",
                        "{action}",
                        "&7支持全服无缝跨服传送！"
                ), "action", actionTip));

        // 修复3: 菜单【成员管理】头颅显示为会长头颅(多个会长随机一个)
        ItemStack memberHead = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta msm = (SkullMeta) memberHead.getItemMeta();
        UUID presUuid = getRandomPresidentUuid(player, g);
        applyPlayerSkull(msm, presUuid, null);
        msm.setDisplayName(Lang.raw("menus.main.members-title", "&b&l成员管理"));
        msm.setLore(Lang.list("menus.main.members-lore", List.of(
                "&7查看全体成员、职位与贡献",
                "&7管理成员晋升、踢人与入会申请审批"
        )));
        memberHead.setItemMeta(msm);
        inv.setItem(20, memberHead);

        put(inv, 24, Material.NETHER_STAR,
                Lang.raw("menus.main.manage-title", "&c&l公会管理"),
                Lang.list("menus.main.manage-lore", List.of(
                        "&7会长领取等级升级奖励",
                        "&7公会转让、退出公会或解散公会"
                )));

        put(inv, 31, Material.BARRIER,
                Lang.raw("menus.common.close", "&c&l关闭界面"),
                Lang.list("menus.common.close-lore", List.of("&7点击关闭菜单")));
        player.openInventory(inv);
    }

    /* ══════════════════════════════════════════════════════════════ */
    /*                   界面 3: 公会基地二级管理菜单               */
    /* ══════════════════════════════════════════════════════════════ */
    public void openBaseManageMenu(Player player) {
        playClick(player);
        Inventory inv = Bukkit.createInventory(new MenuHolder(MenuType.BASE_MANAGE), 27,
                Lang.raw("menus.base-manage.title", "&3公会基地管理"));
        fill(inv);

        var opt = service.getBaseLocation(player.getUniqueId());
        String status = opt.map(b -> Lang.raw("menus.base-manage.status-set", "&a已设置: {server} ({x},{y},{z})",
                "server", b.server(), "x", String.valueOf((int)b.x()), "y", String.valueOf((int)b.y()), "z", String.valueOf((int)b.z())))
                .orElse(Lang.raw("menus.base-manage.status-unset", "&c当前尚未设置公会基地"));

        put(inv, 4, Material.COMPASS,
                Lang.raw("menus.base-manage.status-title", "&b&l公会基地状态"),
                List.of("§7-----------------------------", status, "§7-----------------------------"));

        put(inv, 11, Material.ENDER_PEARL,
                Lang.raw("menus.base-manage.teleport-title", "&a&l【传送到公会基地】"),
                Lang.list("menus.base-manage.teleport-lore", List.of("&7立即传送到公会基地", "&7支持跨服自动传送定位！")));

        put(inv, 15, Material.LODESTONE,
                Lang.raw("menus.base-manage.set-title", "&e&l【设置当前位置为基地】"),
                Lang.list("menus.base-manage.set-lore", List.of("&7将你脚下的当前位置保存为公会基地", "&6所有公会成员均可跨服传送至此！")));

        put(inv, 22, Material.ARROW,
                Lang.raw("menus.common.back-main", "&7返回主菜单"),
                Lang.list("menus.common.back-main-lore", List.of("&7返回公会主面板")));
        player.openInventory(inv);
    }

    /* ══════════════════════════════════════════════════════════════ */
    /*                   界面 4: 独立捐献菜单                       */
    /* ══════════════════════════════════════════════════════════════ */
    public void openDonateMenu(Player player) {
        Optional<GuildView> opt = service.guildOf(player.getUniqueId());
        if (opt.isEmpty()) { openNoGuildMenu(player); return; }
        GuildView g = opt.get();
        playClick(player);

        Inventory inv = Bukkit.createInventory(new MenuHolder(MenuType.DONATE), 27,
                Lang.raw("menus.donate.title", "&3H币捐献 - {name}", "name", g.name()));
        fill(inv);

        double balance = economy != null ? economy.getBalance(player) : 0.0;

        put(inv, 4, Material.SUNFLOWER,
                Lang.raw("menus.donate.info-title", "&6&l公会繁荣度捐献"),
                Lang.list("menus.donate.info-lore", List.of(
                        "&7当前公会繁荣度: &e{xp}",
                        "&7你的H币余额: &e{balance} H币",
                        "&a汇率: 1 H币 = 1 繁荣度",
                        "&e繁荣度达标后将由系统自动触发公会升级！"
                ), "xp", String.valueOf(g.xp()), "balance", String.valueOf((long)balance)));

        int[] amounts = {1000, 5000, 10000, 50000};
        Material[] mats = {Material.IRON_INGOT, Material.GOLD_INGOT, Material.DIAMOND, Material.EMERALD};
        int[] slots = {10, 12, 14, 16};

        for (int i = 0; i < 4; i++) {
            int amt = amounts[i];
            put(inv, slots[i], mats[i],
                    Lang.raw("menus.donate.donate-btn", "&f捐献 &e{amount} H币", "amount", String.format("%,d", amt)),
                    Lang.list("menus.donate.donate-btn-lore", List.of("&7点击直接扣除 {amount} H币，增加 {amount} 繁荣度"), "amount", String.format("%,d", amt)));
        }

        put(inv, 22, Material.NAME_TAG,
                Lang.raw("menus.donate.custom-title", "&d&l【自定义数量捐献】"),
                Lang.list("menus.donate.custom-lore", List.of("&7点击后在聊天栏直接输入要捐献的H币数量", "&8(输入的内容已被系统拦截，不会在公屏出现)")));

        put(inv, 18, Material.ARROW,
                Lang.raw("menus.common.back-main", "&7返回主菜单"),
                Lang.list("menus.common.back-main-lore", List.of("&7返回公会主面板")));

        put(inv, 26, Material.BARRIER,
                Lang.raw("menus.common.close", "&c&l关闭界面"),
                Lang.list("menus.common.close-lore", List.of("&7点击关闭菜单")));
        player.openInventory(inv);
    }

    /* ══════════════════════════════════════════════════════════════ */
    /*                   界面 5: 成员管理菜单                       */
    /* ══════════════════════════════════════════════════════════════ */
    public void openMembersMenu(Player player) {
        Optional<GuildView> opt = service.guildOf(player.getUniqueId());
        if (opt.isEmpty()) { openNoGuildMenu(player); return; }
        GuildView g = opt.get();
        playClick(player);

        Inventory inv = Bukkit.createInventory(new MenuHolder(MenuType.MEMBERS), 54,
                Lang.raw("menus.members.title", "&3成员管理 - {name}", "name", g.name()));
        fill(inv);

        Role myRole = service.roleOf(player.getUniqueId()).orElse(Role.MEMBER);
        List<MemberInfo> members = service.getMembers(player.getUniqueId());

        if (myRole.ordinal() >= Role.ELDER.ordinal()) {
            int appCount = service.applications(player.getUniqueId()).size();
            put(inv, 4, Material.WRITABLE_BOOK,
                    Lang.raw("menus.members.apps-btn", "&e&l【入会申请审核】"),
                    Lang.list("menus.members.apps-btn-lore", List.of(
                            "&7当前待审核申请数: &e{count}",
                            "&7点击打开申请列表进行同意或拒绝"
                    ), "count", String.valueOf(appCount)));

            // 需求8: 邀请成员按钮
            put(inv, 6, Material.PAPER,
                    "§a§l【邀请新成员】",
                    List.of(
                            "§7允许长老及以上职级邀请玩家加入公会",
                            "§a点击后在聊天栏直接输入被邀请玩家名称",
                            "§7也可使用指令: §e/guild invite <玩家名>"
                    ));
        }

        int slot = 9;
        for (MemberInfo m : members) {
            if (slot >= 45) break;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta sm = (SkullMeta) head.getItemMeta();
            // 修复4: 修复成员列表头颅皮肤，应显示各个成员自己的头颅皮肤
            applyPlayerSkull(sm, m.uuid(), m.name());
            sm.setDisplayName(Lang.raw("menus.members.head-title", "{role} &f{name}", "role", m.role().getMenuDisplay(), "name", m.name()));

            List<String> lore = new ArrayList<>(Lang.list("menus.members.head-lore", List.of(
                    "&7-----------------------------",
                    "&f职位: {role}",
                    "&f累计繁荣贡献: &e{contribution}",
                    "&f最后签到: &7{last_checkin}",
                    "&7-----------------------------"
            ), "role", m.role().getMenuDisplay(), "contribution", String.valueOf(m.contribution()), "last_checkin", m.lastCheckin()));

            if (!m.uuid().equals(player.getUniqueId())) {
                if (myRole == Role.PRESIDENT) {
                    lore.add(Lang.raw("menus.members.action-promote", "&a[左键点击] 晋升职位"));
                    lore.add(Lang.raw("menus.members.action-demote", "&6[右键点击] 降低职位"));
                    lore.add(Lang.raw("menus.members.action-kick", "&c[Shift+右键] 踢出公会"));
                } else if (myRole == Role.VICE_PRESIDENT) {
                    if (m.role() == Role.MEMBER) {
                        lore.add(Lang.raw("menus.members.action-promote", "&a[左键点击] 晋升职位"));
                        lore.add(Lang.raw("menus.members.action-kick", "&c[Shift+右键] 踢出公会"));
                    } else if (m.role() == Role.ELDER) {
                        lore.add(Lang.raw("menus.members.action-promote", "&a[左键点击] 晋升职位"));
                        lore.add(Lang.raw("menus.members.action-demote", "&6[右键点击] 降低职位"));
                        lore.add(Lang.raw("menus.members.action-kick", "&c[Shift+右键] 踢出公会"));
                    }
                }
            }
            sm.setLore(lore);
            head.setItemMeta(sm);
            inv.setItem(slot++, head);
        }

        put(inv, 49, Material.ARROW,
                Lang.raw("menus.common.back-main", "&7返回主菜单"),
                Lang.list("menus.common.back-main-lore", List.of("&7返回公会主面板")));
        player.openInventory(inv);
    }

    /* ══════════════════════════════════════════════════════════════ */
    /*                   界面 6: 公会转让专属选择 GUI               */
    /* ══════════════════════════════════════════════════════════════ */
    public void openTransferMenu(Player player) {
        Optional<GuildView> opt = service.guildOf(player.getUniqueId());
        if (opt.isEmpty()) return;
        GuildView g = opt.get();
        playClick(player);

        Inventory inv = Bukkit.createInventory(new MenuHolder(MenuType.TRANSFER), 36,
                Lang.raw("menus.transfer.title", "&3选择会长转让目标 - {name}", "name", g.name()));
        fill(inv);

        List<MemberInfo> members = service.getMembers(player.getUniqueId());

        int slot = 10;
        for (MemberInfo m : members) {
            // 修复5: 必须正确过滤掉自己，显示其它成员
            if (m.uuid().equals(player.getUniqueId())) continue;
            if (slot > 25) break;

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta sm = (SkullMeta) head.getItemMeta();
            // 头颅显示该候选成员本身的皮肤
            applyPlayerSkull(sm, m.uuid(), m.name());
            sm.setDisplayName(Lang.raw("menus.transfer.item-title", "&b{name} &7({role}&7)", "name", m.name(), "role", m.role().getMenuDisplay()));
            sm.setLore(Lang.list("menus.transfer.item-lore", List.of(
                    "&7-----------------------------",
                    "&f当前职位: {role}",
                    "&f累计贡献: &e{contribution}",
                    "&7-----------------------------",
                    "&a[点击将会长职位转让给TA]",
                    "&c转让后你将自动变更为副会长！"
            ), "role", m.role().getMenuDisplay(), "contribution", String.valueOf(m.contribution())));
            head.setItemMeta(sm);
            inv.setItem(slot++, head);
        }

        put(inv, 31, Material.NAME_TAG,
                Lang.raw("menus.transfer.chat-title", "&e&l公屏输入玩家名称转让"),
                Lang.list("menus.transfer.chat-lore", List.of(
                        "&7点击后在聊天栏直接输入目标玩家名 (不区分大小写)",
                        "&8(输入内容已拦截，不会发送到公屏)"
                )));

        put(inv, 27, Material.ARROW,
                Lang.raw("menus.common.back-manage", "&7返回公会管理"),
                Lang.list("menus.common.back-manage-lore", List.of("&7返回公会管理菜单")));
        player.openInventory(inv);
    }

    /* ══════════════════════════════════════════════════════════════ */
    /*                   界面 7: 公会商城独立 GUI                   */
    /* ══════════════════════════════════════════════════════════════ */
    public void openShopMenu(Player player) {
        playClick(player);
        Role role = service.roleOf(player.getUniqueId()).orElse(Role.MEMBER);

        Inventory inv = Bukkit.createInventory(new MenuHolder(MenuType.SHOP), 36,
                "§3公会商城 - " + role.getMenuDisplay());
        fill(inv);

        Collection<ShopItem> allItems = shopService.items();
        int slot = 10;
        for (ShopItem item : allItems) {
            if (slot > 25) break;
            Optional<ShopItem.Offer> offerOpt = item.offers().containsKey(role)
                    ? Optional.of(item.offers().get(role))
                    : Optional.empty();

            Material mat = Material.CHEST;
            String itemName = "§e" + item.id();
            double price = 0;
            String limitDesc = "§a不限购";
            boolean canBuyRole = offerOpt.isPresent();

            if (canBuyRole) {
                ShopItem.Offer offer = offerOpt.get();
                if (offer.material() != null) {
                    Material matched = Material.matchMaterial(offer.material());
                    if (matched != null) mat = matched;
                }
                if (offer.displayName() != null) {
                    itemName = Lang.color(offer.displayName());
                }
                price = offer.price();

                var decision = shopService.checkLimit(player.getUniqueId(), item.id(), role, 1);
                if (offer.limitType() != ShopItem.LimitType.UNLIMITED) {
                    limitDesc = "§6" + offer.limitType() + " 限额: §e" + decision.remaining() + "/" + offer.limitAmount() + " (已购 " + decision.purchased() + ")";
                }
            } else {
                for (Role r : Role.values()) {
                    if (item.offers().containsKey(r) && item.offers().get(r).displayName() != null) {
                        itemName = Lang.color(item.offers().get(r).displayName());
                        break;
                    }
                }
            }

            ItemStack is = new ItemStack(mat);
            ItemMeta im = is.getItemMeta();
            im.setDisplayName(itemName);

            List<String> lore = new ArrayList<>();
            lore.add("§7-----------------------------");
            lore.add("§f价格: §e" + (long)price + " H币");
            lore.add("§f限购状态: " + limitDesc);
            lore.add("§7-----------------------------");
            if (canBuyRole) {
                lore.add("§a[点击直接购买 1 件]");
            } else {
                lore.add("§c[你当前的公会职位等级未解锁该商品]");
            }
            im.setLore(lore);
            is.setItemMeta(im);
            inv.setItem(slot++, is);
        }

        put(inv, 31, Material.ARROW,
                Lang.raw("menus.common.back-main", "&7返回主菜单"),
                Lang.list("menus.common.back-main-lore", List.of("&7返回公会主面板")));
        player.openInventory(inv);
    }

    /* ══════════════════════════════════════════════════════════════ */
    /*                   界面 8: 公会管理与奖励领取                 */
    /* ══════════════════════════════════════════════════════════════ */
    public void openManageMenu(Player player) {
        Optional<GuildView> opt = service.guildOf(player.getUniqueId());
        if (opt.isEmpty()) { openNoGuildMenu(player); return; }
        GuildView g = opt.get();
        Role role = service.roleOf(player.getUniqueId()).orElse(Role.MEMBER);
        playClick(player);

        Inventory inv = Bukkit.createInventory(new MenuHolder(MenuType.MANAGE), 36,
                Lang.raw("menus.manage.title", "&3公会管理 - {name}", "name", g.name()));
        fill(inv);

        if (role == Role.PRESIDENT) {
            boolean claimed = service.isRewardClaimed(player.getUniqueId(), g.level());
            Material mat = claimed ? Material.MINECART : Material.CHEST_MINECART;
            String status = claimed
                    ? Lang.raw("menus.manage.reward-claimed", "&7(当前等级奖励已领取)")
                    : Lang.raw("menus.manage.reward-can-claim", "&a(点击立即领取当前等级奖励)");

            put(inv, 11, mat,
                    Lang.raw("menus.manage.reward-title", "&6&l【领取升级奖励】"),
                    Lang.list("menus.manage.reward-lore", List.of(
                            "&7公会等级: &eLv.{level}",
                            "{status}",
                            "&7公会升级后由会长在此领取，每个等级仅限一次"
                    ), "level", String.valueOf(g.level()), "status", status));

            put(inv, 13, Material.ENCHANTED_BOOK,
                    Lang.raw("menus.manage.transfer-title", "&b&l【转让公会】"),
                    Lang.list("menus.manage.transfer-lore", List.of(
                            "&7将公会所有权转让给指定成员",
                            "&c转让完成后你将自动降职为副会长！",
                            "&a点击打开成员转让选择面板"
                    )));

            put(inv, 15, Material.TNT,
                    Lang.raw("menus.manage.disband-title", "&c&l【解散公会】"),
                    Lang.list("menus.manage.disband-lore", List.of(
                            "&4永久解散公会，不可逆！",
                            "&7清空所有公会成员与假人数据",
                            "&c点击后立即解散公会"
                    )));
        } else {
            put(inv, 13, Material.PAPER,
                    Lang.raw("menus.manage.non-owner-title", "&7公会信息"),
                    Lang.list("menus.manage.non-owner-lore", List.of("&7你的职位为: {role}&7，高级管理仅限会长。"), "role", role.getMenuDisplay()));
        }

        put(inv, 29, Material.SPRUCE_DOOR,
                Lang.raw("menus.manage.leave-title", "&c&l【退出公会】"),
                Lang.list("menus.manage.leave-lore", List.of(
                        "&7离开当前公会",
                        "&c注意：退会后将进入冷却期，冷却期内无法加会或创会！"
                )));

        put(inv, 31, Material.ARROW,
                Lang.raw("menus.common.back-main", "&7返回主菜单"),
                Lang.list("menus.common.back-main-lore", List.of("&7返回公会主面板")));
        player.openInventory(inv);
    }

    /* ══════════════════════════════════════════════════════════════ */
    /*                   界面 9: 入会申请审核列表                   */
    /* ══════════════════════════════════════════════════════════════ */
    public void openApplicationsMenu(Player player) {
        Optional<GuildView> opt = service.guildOf(player.getUniqueId());
        if (opt.isEmpty()) return;
        playClick(player);

        List<UUID> apps = service.applications(player.getUniqueId());
        Inventory inv = Bukkit.createInventory(new MenuHolder(MenuType.APPLICATIONS), 36,
                Lang.raw("menus.applications.title", "&3入会申请审核 ({count})", "count", String.valueOf(apps.size())));
        fill(inv);

        int slot = 10;
        for (UUID uid : apps) {
            if (slot > 25) break;
            OfflinePlayer op = Bukkit.getOfflinePlayer(uid);
            String name = op.getName() != null ? op.getName() : uid.toString().substring(0, 8);

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta sm = (SkullMeta) head.getItemMeta();
            // 修复4: 修复申请列表头颅皮肤，应显示各个申请人自己的头颅
            applyPlayerSkull(sm, uid, name);
            sm.setDisplayName(Lang.raw("menus.applications.item-title", "&e申请人: &f{name}", "name", name));
            sm.setLore(Lang.list("menus.applications.item-lore", List.of(
                    "&7UUID: {uuid}",
                    "&a[左键点击] 同意加入",
                    "&c[右键点击] 拒绝申请"
            ), "uuid", uid.toString()));
            head.setItemMeta(sm);
            inv.setItem(slot++, head);
        }

        put(inv, 31, Material.ARROW,
                Lang.raw("menus.common.back-members", "&7返回成员管理"),
                Lang.list("menus.common.back-members-lore", List.of("&7返回成员管理菜单")));
        player.openInventory(inv);
    }

    /* ══════════════════════════════════════════════════════════════ */
    /*                   界面 10: 全服公会浏览申请                  */
    /* ══════════════════════════════════════════════════════════════ */
    public void openAllGuildsList(Player player) {
        playClick(player);
        List<GuildView> list = service.getAllGuilds();
        Inventory inv = Bukkit.createInventory(new MenuHolder(MenuType.ALL_GUILDS), 54,
                Lang.raw("menus.all-guilds.title", "&3申请加入公会 ({count})", "count", String.valueOf(list.size())));
        fill(inv);

        int slot = 0;
        for (GuildView g : list) {
            if (slot >= 45) break;
            ItemStack banner = new ItemStack(Material.WHITE_BANNER);
            ItemMeta meta = banner.getItemMeta();
            meta.setDisplayName(Lang.raw("menus.all-guilds.item-title", "&b{name} &7(Lv.{level})", "name", g.name(), "level", String.valueOf(g.level())));
            meta.setLore(Lang.list("menus.all-guilds.item-lore", List.of(
                    "&7-----------------------------",
                    "&f公会等级: &eLv.{level}",
                    "&f繁荣度: &e{xp}",
                    "&f成员数量: &e{members} &7/ &f{max}",
                    "&7-----------------------------",
                    "&a[点击申请加入该公会]"
            ), "level", String.valueOf(g.level()), "xp", String.valueOf(g.xp()), "members", String.valueOf(g.members()), "max", String.valueOf(g.maxMembers())));
            banner.setItemMeta(meta);
            inv.setItem(slot++, banner);
        }

        put(inv, 49, Material.NAME_TAG,
                Lang.raw("menus.all-guilds.chat-title", "&e&l聊天栏输入公会名申请"),
                Lang.list("menus.all-guilds.chat-lore", List.of(
                        "&7点击后在聊天栏输入公会名称直接申请",
                        "&8(输入已受拦截保护)"
                )));
        player.openInventory(inv);
    }

    /* ══════════════════════════════════════════════════════════════ */
    /*                         点击事件总分发                         */
    /* ══════════════════════════════════════════════════════════════ */
    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !(event.getInventory().getHolder() instanceof MenuHolder holder)) return;
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getInventory().getSize()) return;

        switch (holder.type) {
            case NO_GUILD -> handleNoGuildClick(player, slot);
            case MAIN -> handleMainClick(player, slot);
            case BASE_MANAGE -> handleBaseManageClick(player, slot);
            case DONATE -> handleDonateClick(player, slot);
            case MEMBERS -> handleMembersClick(player, slot, event.isLeftClick(), event.isRightClick(), event.isShiftClick());
            case TRANSFER -> handleTransferClick(player, slot);
            case SHOP -> handleShopClick(player, slot);
            case APPLICATIONS -> handleApplicationsClick(player, slot, event.isLeftClick(), event.isRightClick());
            case MANAGE -> handleManageClick(player, slot);
            case ALL_GUILDS -> handleAllGuildsClick(player, slot);
        }
    }

    private void handleNoGuildClick(Player player, int slot) {
        if (slot == 11) {
            openAllGuildsList(player);
        } else if (slot == 15) {
            long cd = service.getLeaveCooldownRemainingMillis(player.getUniqueId());
            if (cd > 0) {
                long hours = (cd / 3600_000L) + 1;
                playFail(player);
                player.sendMessage(Lang.msg("cooldown-active", "hours", String.valueOf(hours)));
                return;
            }
            player.closeInventory();
            pendingCreateInput.add(player.getUniqueId());
            double cost = economy != null ? economy.createCost() : 10000.0;
            player.sendMessage(Lang.msg("create-prompt", "cost", String.valueOf((long)cost)));
        } else if (slot == 22) {
            player.closeInventory();
        }
    }

    private void handleMainClick(Player player, int slot) {
        if (slot == 10) {
            CheckinResult res = service.checkinPlayer(player.getUniqueId());
            if (res.success()) {
                if (res.goldAmount() > 0) {
                    player.getInventory().addItem(new ItemStack(Material.RAW_GOLD, res.goldAmount()));
                }
                playSuccess(player);
                player.sendMessage(Lang.msg("checked-in", "gold", String.valueOf(res.goldAmount()), "prosperity", String.valueOf(res.prosperity())));
                // 需求7: gc公会频道广播每日签到
                service.guildOf(player.getUniqueId()).ifPresent(g -> {
                    FreeCoreGuildPlugin.getInstance().broadcastGuildCross(g.name(),
                            "§8[§b公会§8] §e" + player.getName() + " §a完成了今日公会签到，公会增加 §e" + res.prosperity() + " §a点繁荣度！");
                });
            } else {
                playFail(player);
                player.sendMessage(Lang.msg("checkin-failed", "message", res.message()));
            }
            openMain(player);
        } else if (slot == 12) {
            openDonateMenu(player);
        } else if (slot == 14) {
            openShopMenu(player);
        } else if (slot == 16) {
            Role role = service.roleOf(player.getUniqueId()).orElse(Role.MEMBER);
            if (role.canOperateFakePlayers()) {
                openBaseManageMenu(player);
            } else {
                baseTransferService.teleportToBase(player);
            }
        } else if (slot == 20) {
            openMembersMenu(player);
        } else if (slot == 24) {
            openManageMenu(player);
        } else if (slot == 31) {
            player.closeInventory();
        }
    }

    private void handleBaseManageClick(Player player, int slot) {
        if (slot == 22) { openMain(player); return; }
        if (slot == 11) {
            baseTransferService.teleportToBase(player);
        } else if (slot == 15) {
            if (service.setBaseLocation(player.getUniqueId(), player.getLocation())) {
                playSuccess(player);
                player.sendMessage(Lang.msg("base-set-success"));
                openBaseManageMenu(player);
            } else {
                playFail(player);
                player.sendMessage(Lang.msg("base-set-failed"));
            }
        }
    }

    private void handleDonateClick(Player player, int slot) {
        if (slot == 18) { openMain(player); return; }
        if (slot == 26) { player.closeInventory(); return; }

        int[] amounts = {1000, 5000, 10000, 50000};
        int[] slots = {10, 12, 14, 16};
        for (int i = 0; i < 4; i++) {
            if (slot == slots[i]) {
                executeDonate(player, amounts[i]);
                openDonateMenu(player);
                return;
            }
        }

        if (slot == 22) {
            player.closeInventory();
            pendingDonateInput.add(player.getUniqueId());
            player.sendMessage(Lang.msg("donate-prompt"));
        }
    }

    private void executeDonate(Player player, double amount) {
        if (economy != null && economy.donate(player, amount)) {
            playSuccess(player);
            player.sendMessage(Lang.msg("donated", "amount", String.valueOf((long)amount), "prosperity", String.valueOf((long)amount)));
            // 需求7: gc公会频道广播捐献
            service.guildOf(player.getUniqueId()).ifPresent(g -> {
                FreeCoreGuildPlugin.getInstance().broadcastGuildCross(g.name(),
                        "§8[§b公会§8] §e" + player.getName() + " §a向公会捐献了 §e" + (long)amount + " H币§a，繁荣度增加 §e" + (long)amount + "§a！");
            });
        } else {
            playFail(player);
            player.sendMessage(Lang.msg("donate-failed"));
        }
    }

    private void handleMembersClick(Player player, int slot, boolean left, boolean right, boolean shift) {
        if (slot == 49) { openMain(player); return; }
        if (slot == 4) { openApplicationsMenu(player); return; }
        if (slot == 6) {
            // 需求8: 邀请成员菜单点击
            Role myRole = service.roleOf(player.getUniqueId()).orElse(Role.MEMBER);
            if (myRole.ordinal() < Role.ELDER.ordinal()) {
                playFail(player);
                player.sendMessage(Lang.msg("invite-no-permission"));
                return;
            }
            player.closeInventory();
            pendingInviteInput.add(player.getUniqueId());
            player.sendMessage(Lang.msg("invite-prompt"));
            return;
        }

        if (slot >= 9 && slot < 45) {
            List<MemberInfo> members = service.getMembers(player.getUniqueId());
            int idx = slot - 9;
            if (idx >= members.size()) return;
            MemberInfo target = members.get(idx);
            if (target.uuid().equals(player.getUniqueId())) return;

            Role myRole = service.roleOf(player.getUniqueId()).orElse(Role.MEMBER);

            if (shift && right) {
                if (service.kick(player.getUniqueId(), target.uuid())) {
                    playSuccess(player);
                    player.sendMessage(Lang.msg("kicked", "player", target.name()));
                    // 需求7: gc广播被踢出
                    service.guildOf(player.getUniqueId()).ifPresent(g -> {
                        FreeCoreGuildPlugin.getInstance().broadcastGuildCross(g.name(),
                                "§8[§b公会§8] §c成员 §e" + target.name() + " §c已被移出公会！");
                    });
                } else {
                    playFail(player);
                    player.sendMessage(Lang.msg("kick-failed"));
                }
                openMembersMenu(player);
            } else if (left) {
                if (service.promote(player.getUniqueId(), target.uuid())) {
                    playSuccess(player);
                    player.sendMessage(Lang.msg("promoted", "player", target.name()));
                } else {
                    playFail(player);
                    player.sendMessage(Lang.msg("promote-failed"));
                }
                openMembersMenu(player);
            } else if (right) {
                if (service.demote(player.getUniqueId(), target.uuid())) {
                    playSuccess(player);
                    player.sendMessage(Lang.msg("demoted", "player", target.name()));
                } else {
                    playFail(player);
                    player.sendMessage(Lang.msg("demote-failed"));
                }
                openMembersMenu(player);
            }
        }
    }

    private void handleTransferClick(Player player, int slot) {
        if (slot == 27) { openManageMenu(player); return; }
        if (slot == 31) {
            player.closeInventory();
            pendingTransferInput.add(player.getUniqueId());
            player.sendMessage(Lang.msg("transfer-prompt"));
            return;
        }

        List<MemberInfo> members = service.getMembers(player.getUniqueId());
        List<MemberInfo> candidates = new ArrayList<>();
        for (MemberInfo m : members) {
            if (!m.uuid().equals(player.getUniqueId())) candidates.add(m);
        }

        int idx = slot - 10;
        if (idx >= 0 && idx < candidates.size()) {
            MemberInfo target = candidates.get(idx);
            TransferResult res = service.transferWithReason(player.getUniqueId(), target.uuid());
            if (res.success()) {
                playSuccess(player);
                player.sendMessage(Lang.msg("transfer-success", "player", target.name()));
                openMain(player);
            } else {
                playFail(player);
                player.sendMessage(Lang.msg("transfer-failed", "reason", res.reason()));
            }
        }
    }

    private void handleShopClick(Player player, int slot) {
        if (slot == 31) { openMain(player); return; }
        List<ShopItem> allItems = new ArrayList<>(shopService.items());
        int idx = slot - 10;
        if (idx >= 0 && idx < allItems.size()) {
            ShopItem item = allItems.get(idx);
            Role role = service.roleOf(player.getUniqueId()).orElse(Role.MEMBER);
            var res = shopService.purchase(player, item.id(), role, 1);
            if (res.success()) {
                playSuccess(player);
                player.sendMessage(Lang.msg("shop-bought"));
                openShopMenu(player);
            } else {
                playFail(player);
                player.sendMessage(Lang.msg("shop-buy-failed", "message", res.message()));
            }
        }
    }

    private void handleApplicationsClick(Player player, int slot, boolean left, boolean right) {
        if (slot == 31) { openMembersMenu(player); return; }
        List<UUID> apps = service.applications(player.getUniqueId());
        int idx = slot - 10;
        if (idx >= 0 && idx < apps.size()) {
            UUID applicant = apps.get(idx);
            OfflinePlayer op = Bukkit.getOfflinePlayer(applicant);
            String name = op.getName() != null ? op.getName() : applicant.toString().substring(0, 8);
            String guildName = service.guildOf(player.getUniqueId()).map(GuildView::name).orElse("");

            if (left) {
                if (service.approve(player.getUniqueId(), applicant)) {
                    playSuccess(player);
                    player.sendMessage(Lang.msg("app-approved", "player", name));
                    // 需求6: 申请被批准跨服及离线上线提示
                    String approvedMsg = Lang.msg("app-approved-notify", "guild", guildName);
                    sendOrQueueNotify(applicant, approvedMsg);

                    // 需求7: gc广播新成员加入
                    FreeCoreGuildPlugin.getInstance().broadcastGuildCross(guildName,
                            "§8[§b公会§8] §a欢迎新成员 §e" + name + " §a加入公会！");
                } else {
                    playFail(player);
                    player.sendMessage(Lang.msg("app-approved-failed"));
                }
            } else if (right) {
                service.reject(player.getUniqueId(), applicant);
                playSuccess(player);
                player.sendMessage(Lang.msg("app-rejected", "player", name));
                // 需求6: 申请被拒绝跨服及离线上线提示
                String rejectedMsg = Lang.msg("app-rejected-notify", "guild", guildName);
                sendOrQueueNotify(applicant, rejectedMsg);
            }
            openApplicationsMenu(player);
        }
    }

    private void sendOrQueueNotify(UUID targetUuid, String message) {
        Player online = Bukkit.getPlayer(targetUuid);
        if (online != null && online.isOnline()) {
            online.sendMessage(message);
        }
        GuildRedisBridge bridge = FreeCoreGuildPlugin.getInstance().getRedisBridge();
        if (bridge != null) {
            String serverId = Bukkit.getPort() == 25570 ? "technical" : "survival";
            bridge.publish("NOTIFY_PLAYER|" + targetUuid + "|" + serverId + "|" + message);
            bridge.pushOfflineNotification(targetUuid, message);
        }
    }

    private void handleManageClick(Player player, int slot) {
        if (slot == 31) { openMain(player); return; }

        if (slot == 11) {
            Optional<GuildView> opt = service.guildOf(player.getUniqueId());
            if (opt.isEmpty()) return;
            int level = opt.get().level();
            List<String> rewards = FreeCoreGuildPlugin.getInstance().getUpgradeRewardsForLevel(level);
            if (rewards.isEmpty()) {
                playFail(player);
                player.sendMessage(Lang.msg("reward-not-unlocked"));
                return;
            }
            if (service.claimUpgradeReward(player.getUniqueId(), level)) {
                dispatchReward(player, rewards);
                playSuccess(player);
                player.sendMessage(Lang.msg("reward-claimed", "level", String.valueOf(level)));
            } else {
                playFail(player);
                player.sendMessage(Lang.msg("reward-already-claimed"));
            }
            openManageMenu(player);
        } else if (slot == 13) {
            openTransferMenu(player);
        } else if (slot == 15) {
            if (service.disband(player.getUniqueId())) {
                playSuccess(player);
                player.closeInventory();
                player.sendMessage(Lang.msg("disbanded"));
            } else {
                playFail(player);
                player.sendMessage(Lang.msg("disband-failed"));
            }
        } else if (slot == 29) {
            Role myRole = service.roleOf(player.getUniqueId()).orElse(Role.MEMBER);
            if (myRole == Role.PRESIDENT) {
                playFail(player);
                player.sendMessage(Lang.msg("leave-failed-owner"));
                return;
            }
            if (service.leave(player.getUniqueId())) {
                playSuccess(player);
                player.closeInventory();
                player.sendMessage(Lang.msg("left"));
            } else {
                playFail(player);
                player.sendMessage(Lang.msg("leave-failed"));
            }
        }
    }

    private void handleAllGuildsClick(Player player, int slot) {
        long cd = service.getLeaveCooldownRemainingMillis(player.getUniqueId());
        if (cd > 0) {
            long hours = (cd / 3600_000L) + 1;
            playFail(player);
            player.sendMessage(Lang.msg("cooldown-active", "hours", String.valueOf(hours)));
            return;
        }

        if (slot == 49) {
            player.closeInventory();
            pendingApplyInput.add(player.getUniqueId());
            player.sendMessage(Lang.msg("apply-prompt"));
            return;
        }

        List<GuildView> list = service.getAllGuilds();
        if (slot >= 0 && slot < list.size()) {
            GuildView g = list.get(slot);
            if (service.apply(player.getUniqueId(), g.name())) {
                playSuccess(player);
                player.sendMessage(Lang.msg("app-submitted", "name", g.name()));
                // 需求6 & 7: 申请公会时给公会所有成员/管理发送提醒(支持跨服)
                String appNotify = Lang.msg("app-received-notify", "player", player.getName());
                FreeCoreGuildPlugin.getInstance().broadcastGuildCross(g.name(), appNotify);
                player.closeInventory();
            } else {
                playFail(player);
                player.sendMessage(Lang.msg("app-failed"));
            }
        }
    }

    private void dispatchReward(Player p, List<String> configuredRewards) {
        for (String configured : configuredRewards) {
            String text = configured.replace("{player}", p.getName());
            if (text.regionMatches(true, 0, "console:", 0, 8)) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), text.substring(8).trim());
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
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String message = event.getMessage().trim();

        if (pendingInviteInput.remove(uuid)) {
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(FreeCoreGuildPlugin.getInstance(), () -> {
                player.performCommand("guild invite " + message);
            });
            return;
        }

        if (pendingCreateInput.remove(uuid)) {
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(FreeCoreGuildPlugin.getInstance(), () -> {
                long cd = service.getLeaveCooldownRemainingMillis(player.getUniqueId());
                if (cd > 0) {
                    long hours = (cd / 3600_000L) + 1;
                    playFail(player);
                    player.sendMessage(Lang.msg("cooldown-active", "hours", String.valueOf(hours)));
                    return;
                }
                if (economy != null && economy.create(player, message)) {
                    playSuccess(player);
                    player.sendMessage(Lang.msg("created", "name", message));
                    openMain(player);
                } else {
                    playFail(player);
                    double cost = economy != null ? economy.createCost() : 10000.0;
                    player.sendMessage(Lang.msg("create-failed", "cost", String.valueOf((long)cost)));
                }
            });
            return;
        }

        if (pendingDonateInput.remove(uuid)) {
            event.setCancelled(true);
            try {
                long amount = Long.parseLong(message);
                if (amount <= 0) throw new NumberFormatException();
                Bukkit.getScheduler().runTask(FreeCoreGuildPlugin.getInstance(), () -> executeDonate(player, amount));
            } catch (NumberFormatException e) {
                playFail(player);
                player.sendMessage(Lang.msg("donate-invalid"));
            }
            return;
        }

        if (pendingApplyInput.remove(uuid)) {
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(FreeCoreGuildPlugin.getInstance(), () -> {
                long cd = service.getLeaveCooldownRemainingMillis(player.getUniqueId());
                if (cd > 0) {
                    long hours = (cd / 3600_000L) + 1;
                    playFail(player);
                    player.sendMessage(Lang.msg("cooldown-active", "hours", String.valueOf(hours)));
                    return;
                }
                if (service.apply(player.getUniqueId(), message)) {
                    playSuccess(player);
                    player.sendMessage(Lang.msg("app-submitted", "name", message));
                    String appNotify = Lang.msg("app-received-notify", "player", player.getName());
                    FreeCoreGuildPlugin.getInstance().broadcastGuildCross(message, appNotify);
                } else {
                    playFail(player);
                    player.sendMessage(Lang.msg("app-failed"));
                }
            });
            return;
        }

        if (pendingTransferInput.remove(uuid)) {
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(FreeCoreGuildPlugin.getInstance(), () -> {
                List<MemberInfo> members = service.getMembers(player.getUniqueId());
                MemberInfo found = null;
                for (MemberInfo m : members) {
                    if (m.name().equalsIgnoreCase(message)) {
                        found = m;
                        break;
                    }
                }
                if (found == null) {
                    playFail(player);
                    player.sendMessage(Lang.msg("transfer-not-found", "name", message));
                    return;
                }

                TransferResult res = service.transferWithReason(player.getUniqueId(), found.uuid());
                if (res.success()) {
                    playSuccess(player);
                    player.sendMessage(Lang.msg("transfer-success", "player", found.name()));
                } else {
                    playFail(player);
                    player.sendMessage(Lang.msg("transfer-failed", "reason", res.reason()));
                }
            });
        }
    }

    private static void fill(Inventory inv) {
        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, " ", null);
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, pane);
    }

    private static void put(Inventory inv, int slot, Material material, String name, List<String> lore) {
        inv.setItem(slot, item(material, name, lore));
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(name);
        if (lore != null && !lore.isEmpty()) meta.setLore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    public enum MenuType { NO_GUILD, MAIN, BASE_MANAGE, DONATE, MEMBERS, APPLICATIONS, TRANSFER, SHOP, MANAGE, ALL_GUILDS }
    public record MenuHolder(MenuType type) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }
}
