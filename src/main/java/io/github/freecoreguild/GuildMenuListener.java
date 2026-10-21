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
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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

    private static final long CONFIRM_TIMEOUT_NANOS = 60_000_000_000L;
    private final Map<UUID, ConfirmationHolder> confirmations = new ConcurrentHashMap<>();
    private final Map<UUID, ConfirmationHolder> pendingGuildNameInput = new ConcurrentHashMap<>();
    private final Map<UUID, Object> inputSessions = new ConcurrentHashMap<>();

    private final Set<UUID> pendingDonateInput = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingApplyInput = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingCreateInput = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingTransferInput = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingInviteInput = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingIconInput = ConcurrentHashMap.newKeySet();
    private final Set<UUID> inputInFlight = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> capturedInputs = new ConcurrentHashMap<>();
    private final Set<Object> privateChatEvents = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

    public boolean isChatInputPending(UUID player) {
        return player != null && (hasPendingInput(player) || inputInFlight.contains(player));
    }

    public boolean isChatInputCaptured(UUID player) {
        if (player == null) return false;
        Long until = capturedInputs.get(player);
        if (until == null) return false;
        if (System.nanoTime() <= until) return true;
        capturedInputs.remove(player, until);
        return false;
    }

    public void clearChatInputs() {
        confirmations.clear();
        pendingGuildNameInput.clear();
        inputSessions.clear();
        pendingDonateInput.clear();
        pendingApplyInput.clear();
        pendingCreateInput.clear();
        pendingTransferInput.clear();
        pendingInviteInput.clear();
        pendingIconInput.clear();
        inputInFlight.clear();
        capturedInputs.clear();
        privateChatEvents.clear();
    }

    private boolean captureInput(Player player, Object event) {
        UUID uuid = player.getUniqueId();
        if (privateChatEvents.contains(event)) return true;
        boolean pending = isChatInputPending(uuid);
        if (!pending && !isChatInputCaptured(uuid)) return false;
        privateChatEvents.add(event);
        if (pending) capturedInputs.compute(uuid, (ignored, until) ->
                until == null || System.nanoTime() > until ? System.nanoTime() + 1_000_000_000L : until);
        return true;
    }

    private void queueInput(Player player, String message) {
        UUID uuid = player.getUniqueId();
        if (!hasPendingInput(uuid) || !inputInFlight.add(uuid)) return;
        // Paper can bridge one message through both legacy and Adventure events.
        // Keep the pending state until a single main-thread consumer handles it.
        Object session = inputSessions.computeIfAbsent(uuid, ignored -> new Object());
        Bukkit.getScheduler().runTask(FreeCoreGuildPlugin.getInstance(), () -> {
            try {
                if (player.isOnline() && inputSessions.get(uuid) == session) handleChatInput(player, message);
            } finally {
                if (inputSessions.get(uuid) == session) {
                    inputInFlight.remove(uuid);
                    if (!hasPendingInput(uuid)) inputSessions.remove(uuid, session);
                }
            }
        });
    }

    @EventHandler
    public void onInputPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        clearPlayerInputs(uuid);
        capturedInputs.remove(uuid);
    }

    @EventHandler
    public void onConfirmationClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof ConfirmationHolder holder)) return;
        if (!event.getPlayer().getUniqueId().equals(holder.actor)) return;
        if (confirmations.remove(holder.actor, holder)) inputSessions.remove(holder.actor);
    }

    private void clearPlayerInputs(UUID uuid) {
        confirmations.remove(uuid);
        pendingGuildNameInput.remove(uuid);
        inputSessions.remove(uuid);
        pendingDonateInput.remove(uuid);
        pendingApplyInput.remove(uuid);
        pendingCreateInput.remove(uuid);
        pendingTransferInput.remove(uuid);
        pendingInviteInput.remove(uuid);
        pendingIconInput.remove(uuid);
        inputInFlight.remove(uuid);
        // Keep the short privacy grace window when a captured chat message opens a new request.
    }

    public GuildMenuListener(GuildService service, ShopService shopService, EconomyGateway economy,
                             GuildBaseTransferService baseTransferService) {
        this.service = service;
        this.shopService = shopService;
        this.economy = economy;
        this.baseTransferService = baseTransferService;
    }

    public void requestKickConfirmation(Player player, UUID target, String targetName) {
        requestConfirmation(player, DestructiveAction.KICK, target, targetName);
    }

    public void requestLeaveConfirmation(Player player) {
        requestConfirmation(player, DestructiveAction.LEAVE, null, null);
    }

    public void requestDisbandConfirmation(Player player) {
        requestConfirmation(player, DestructiveAction.DISBAND, null, null);
    }

    public void requestTransferConfirmation(Player player, UUID target, String targetName) {
        requestConfirmation(player, DestructiveAction.TRANSFER, target, targetName);
    }

    private void requestConfirmation(Player player, DestructiveAction action, UUID target, String targetName) {
        UUID actor = player.getUniqueId();
        clearPlayerInputs(actor);
        player.closeInventory();
        try {
            GuildView guild = service.guildOf(actor).orElse(null);
            if (guild == null) {
                confirmationFailure(player);
                return;
            }
            ConfirmationHolder holder = new ConfirmationHolder(action, actor, guild.owner(), guild.name(),
                    guild.createdAt(), service.guildIdentity(actor), System.nanoTime(), target,
                    targetName == null ? String.valueOf(target) : targetName);
            if (!validateConfirmation(player, holder)) return;
            String title = switch (action) {
                case KICK -> Lang.text("menus.confirm.kick-title");
                case LEAVE -> Lang.text("menus.confirm.leave-title");
                case DISBAND -> Lang.text("menus.confirm.disband-title");
                case TRANSFER -> Lang.text("menus.confirm.transfer-title");
            };
            Inventory inventory = Bukkit.createInventory(holder, 27, title);
            fill(inventory);
            put(inventory, 4, Material.PAPER, Lang.text("menus.confirm.guild", "name", guild.name()),
                    List.of(Lang.text("menus.confirm.target", "target", target == null ? guild.name() : holder.targetName),
                            Lang.text("menus.confirm.timeout")));
            put(inventory, 11, Material.BARRIER, Lang.text("menus.confirm.cancel"), List.of());
            put(inventory, 15, Material.RED_CONCRETE, Lang.text("menus.confirm.confirm"),
                    List.of(action == DestructiveAction.KICK
                            ? Lang.text("menus.confirm.kick-warning")
                            : Lang.text("menus.confirm.name-warning")));
            confirmations.put(actor, holder);
            inputSessions.put(actor, new Object());
            player.openInventory(inventory);
            Bukkit.getScheduler().runTaskLater(FreeCoreGuildPlugin.getInstance(), () -> {
                // Release abandoned state, but do not touch any newer request/input session.
                boolean removed = confirmations.remove(actor, holder);
                removed |= pendingGuildNameInput.remove(actor, holder);
                if (removed) {
                    inputSessions.remove(actor);
                    inputInFlight.remove(actor);
                    if (player.isOnline()) player.sendMessage(Lang.getPrefix()
                            + Lang.text("menus.confirm.expired"));
                }
            }, 1200L);
        } catch (RuntimeException ex) {
            confirmationFailure(player);
        }
    }

    private void confirmationFailure(Player player) {
        playFail(player);
        player.sendMessage(Lang.getPrefix() + Lang.text("menus.confirm.invalid"));
    }

    private boolean validateConfirmation(Player player, ConfirmationHolder holder) {
        UUID actor = player.getUniqueId();
        GuildView current = service.guildOf(actor).orElse(null);
        boolean valid = player.hasPermission("freecoreguild.use")
                && actor.equals(holder.actor) && System.nanoTime() - holder.createdAt < CONFIRM_TIMEOUT_NANOS
                && current != null && holder.guildName.equals(current.name())
                && Objects.equals(holder.owner, current.owner()) && holder.guildCreatedAt == current.createdAt()
                && holder.guildIdentity != 0L && holder.guildIdentity == service.guildIdentity(actor);
        Role role = service.roleOf(actor).orElse(null);
        if (valid) {
            valid = switch (holder.action) {
                case LEAVE -> role != null && role != Role.PRESIDENT && !actor.equals(current.owner());
                case DISBAND, TRANSFER -> role == Role.PRESIDENT && actor.equals(current.owner());
                case KICK -> role != null;
            };
        }
        if (valid && (holder.action == DestructiveAction.KICK || holder.action == DestructiveAction.TRANSFER)) {
            GuildView targetGuild = holder.target == null ? null : service.guildOf(holder.target).orElse(null);
            MemberInfo target = service.getMembers(actor).stream()
                    .filter(member -> member.uuid().equals(holder.target)).findFirst().orElse(null);
            valid = !actor.equals(holder.target) && targetGuild != null && target != null
                    && holder.guildName.equals(targetGuild.name()) && Objects.equals(holder.owner, targetGuild.owner())
                    && holder.targetName.equals(target.name());
            if (valid && holder.action == DestructiveAction.KICK) {
                valid = !holder.target.equals(holder.owner) && role.ordinal() > target.role().ordinal();
            }
        }
        if (!valid) confirmationFailure(player);
        return valid;
    }

    private void handleConfirmationClick(Player player, int slot, ConfirmationHolder holder) {
        if (!player.getUniqueId().equals(holder.actor)) return;
        if (slot != 11 && slot != 15) return;
        // Identity removal consumes this exact menu once, including a queued double click.
        if (!confirmations.remove(holder.actor, holder)) return;
        player.closeInventory();
        if (slot == 11) {
            inputSessions.remove(holder.actor);
            player.sendMessage(Lang.getPrefix() + Lang.text("menus.confirm.cancelled"));
            return;
        }
        inputSessions.remove(holder.actor);
        try {
            if (!validateConfirmation(player, holder)) return;
            if (holder.action == DestructiveAction.KICK) {
                executeConfirmedAction(player, holder);
            } else {
                ConfirmationHolder nameInput = holder.withCreatedAt(System.nanoTime());
                pendingGuildNameInput.put(holder.actor, nameInput);
                Object session = new Object();
                inputSessions.put(holder.actor, session);
                player.sendMessage(Lang.getPrefix() + Lang.text("menus.confirm.name-prompt", "name", holder.guildName));
                Bukkit.getScheduler().runTaskLater(FreeCoreGuildPlugin.getInstance(), () -> {
                    if (pendingGuildNameInput.remove(holder.actor, nameInput)) {
                        boolean sessionRemoved = inputSessions.remove(holder.actor, session);
                        if (sessionRemoved) {
                            inputInFlight.remove(holder.actor);
                            if (player.isOnline()) player.sendMessage(Lang.getPrefix()
                                    + Lang.text("menus.confirm.expired"));
                        }
                    }
                }, 1200L);
            }
        } catch (RuntimeException ex) {
            confirmationFailure(player);
        }
    }

    private void executeConfirmedAction(Player player, ConfirmationHolder holder) {
        if (!validateConfirmation(player, holder)) return;
        FreeCoreGuildPlugin plugin = FreeCoreGuildPlugin.getInstance();
        boolean success;
        switch (holder.action) {
            case KICK -> {
                success = service.kick(holder.actor, holder.target);
                if (success) {
                    player.sendMessage(Lang.msg("kicked", "player", holder.targetName));
                    plugin.broadcastGuildCross(holder.guildName, Lang.text("broadcast.kick", "player", holder.targetName));
                } else player.sendMessage(Lang.msg("kick-failed"));
            }
            case LEAVE -> {
                success = service.leave(holder.actor);
                if (success) {
                    player.sendMessage(Lang.msg("left"));
                    plugin.broadcastGuildCross(holder.guildName, Lang.text("broadcast.leave", "player", player.getName()));
                } else player.sendMessage(Lang.msg("leave-failed"));
            }
            case DISBAND -> {
                Set<UUID> recipients = new HashSet<>();
                for (MemberInfo member : service.getMembers(holder.actor)) recipients.add(member.uuid());
                success = service.disband(holder.actor, holder.guildIdentity);
                if (success) {
                    player.sendMessage(Lang.msg("disbanded"));
                    String notice = Lang.text("broadcast.disband");
                    recipients.remove(holder.actor);
                    // Membership has already been deleted; address the captured members directly.
                    for (UUID recipient : recipients) {
                        Player online = Bukkit.getPlayer(recipient);
                        if (online != null && online.isOnline()) online.sendMessage(notice);
                        else if (plugin.getRedisBridge() != null) {
                            plugin.getRedisBridge().publish("NOTIFY_PLAYER|" + recipient + "||" + notice);
                        }
                    }
                } else player.sendMessage(Lang.msg("disband-save-failed"));
            }
            case TRANSFER -> {
                TransferResult result = service.transferWithReason(holder.actor, holder.target);
                success = result.success();
                if (success) {
                    player.sendMessage(Lang.msg("transfer-success", "player", holder.targetName));
                    plugin.broadcastGuildCross(holder.guildName, Lang.text("broadcast.transfer", "player", holder.targetName));
                } else player.sendMessage(Lang.msg("transfer-failed", "reason", result.reason()));
            }
            default -> throw new IllegalStateException("Unknown confirmation action");
        }
        if (success) {
            playSuccess(player);
            plugin.refreshGuildMenus();
            GuildRedisBridge bridge = plugin.getRedisBridge();
            if (bridge != null) bridge.publish("SYNC_ALL|||");
            if (holder.action == DestructiveAction.KICK) openMembersMenu(player);
            else if (holder.action == DestructiveAction.TRANSFER) openMain(player);
        } else playFail(player);
    }

    /** Rebuild only currently visible membership/invite menus after committed changes. */
    public void refreshOpenMenus(UUID inviteTarget) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (inviteTarget != null && !inviteTarget.equals(online.getUniqueId())) continue;
            if (!(online.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder holder)) continue;
            switch (holder.type) {
                case MY_INVITES -> openInvitesMenu(online);
                case ALL_GUILDS -> openAllGuildsList(online);
                case MEMBERS, MEMBER_DETAIL -> {
                    if (inviteTarget == null) openMembersMenu(online);
                }
                case MAIN -> { if (inviteTarget == null) openMain(online); }
                case DONATE -> { if (inviteTarget == null) openDonateMenu(online); }
                case MANAGE -> { if (inviteTarget == null) openManageMenu(online); }
                default -> { }
            }
        }
    }

    public void openMain(Player player) {
        if (!player.hasPermission("freecoreguild.use")) {
            player.sendMessage(Lang.getPrefix() + Lang.text("menus.errors.no-use-permission"));
            return;
        }
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
        // 已完全移入 MySQL 实时拉取邀请与信息，不再利用跨服离线通知列表重复刷屏提醒
    }

    /* ══════════════════════════════════════════════════════════════ */
    /*                   界面 1: 无公会展示菜单                     */
    /* ══════════════════════════════════════════════════════════════ */
    public void openNoGuildMenu(Player player) {
        playClick(player);
        Inventory inv = Bukkit.createInventory(new MenuHolder(MenuType.NO_GUILD), 27,
                Lang.text("menus.no-guild.title"));
        fill(inv);

        long cd = service.getLeaveCooldownRemainingMillis(player.getUniqueId());
        long hours = (cd / 3600_000L) + 1;
        String cdText = cd > 0
                ? Lang.text("menus.no-guild.cooldown-tip", "hours", String.valueOf(hours))
                : Lang.text("menus.no-guild.normal-tip");

        put(inv, 11, Material.COMPASS,
                Lang.text("menus.no-guild.join-title"),
                Lang.lines("menus.no-guild.join-lore"));

        double cost = economy != null ? economy.createCost() : 10000.0;
        put(inv, 15, Material.ANVIL,
                Lang.text("menus.no-guild.create-title"),
                Lang.lines("menus.no-guild.create-lore", "cost", String.valueOf((long)cost), "cooldown", cdText));

        put(inv, 22, Material.BARRIER,
                Lang.text("menus.common.close"),
                Lang.lines("menus.common.close-lore"));
        player.openInventory(inv);
    }

    /* ══════════════════════════════════════════════════════════════ */
    /*                   界面 2: 有公会主面板                       */
    /* ══════════════════════════════════════════════════════════════ */
    public void openGuildMainMenu(Player player, GuildView g) {
        playClick(player);
        Inventory inv = Bukkit.createInventory(new MenuHolder(MenuType.MAIN), 36,
                Lang.text("menus.main.title", "name", g.name()));
        fill(inv);

        Role role = service.roleOf(player.getUniqueId()).orElse(Role.MEMBER);

        ItemStack infoItem = new ItemStack(Material.BEACON);
        ItemMeta meta = infoItem.getItemMeta();
        meta.setDisplayName(Lang.text("menus.main.info-title", "name", g.name(), "level", String.valueOf(g.level())));
        long nextProsperity = FreeCoreGuildPlugin.getInstance().getNextLevelProsperity(g.level());
        String nextProsperityText = nextProsperity < 0 ? Lang.text("menus.common.max-level") : FreeCoreGuildPlugin.getInstance().formatProsperity(nextProsperity);
        meta.setLore(Lang.lines("menus.main.info-lore", "name", g.name(), "level", String.valueOf(g.level()), "xp", FreeCoreGuildPlugin.getInstance().formatProsperity(g.xp()), "next_xp", nextProsperityText, "members", String.valueOf(g.members()), "max", String.valueOf(g.maxMembers()), "role", role.getMenuDisplay(), "fake_current", String.valueOf(service.currentFakePlayers(player.getUniqueId())), "fake_max", String.valueOf(service.fakePlayerQuota(player.getUniqueId()))));
        infoItem.setItemMeta(meta);
        inv.setItem(4, infoItem);

        boolean checkedIn = service.hasCheckedInToday(player.getUniqueId());
        String checkinStatus = checkedIn
                ? Lang.text("menus.main.checkin-status-done")
                : Lang.text("menus.main.checkin-status-ready");
        Material checkinMat = checkedIn ? Material.MINECART : Material.GOLD_INGOT;

        put(inv, 10, checkinMat,
                Lang.text("menus.main.checkin-title"),
                Lang.lines("menus.main.checkin-lore", "checkin_status", checkinStatus));

        put(inv, 12, Material.SUNFLOWER,
                Lang.text("menus.main.donate-title"),
                Lang.lines("menus.main.donate-lore"));

        put(inv, 14, Material.CHEST,
                Lang.text("menus.main.shop-title"),
                Lang.lines("menus.main.shop-lore"));

        String actionTip = role.canOperateFakePlayers()
                ? Lang.text("menus.main.base-lore-admin")
                : Lang.text("menus.main.base-lore-member");

        put(inv, 16, Material.LODESTONE,
                Lang.text("menus.main.base-title"),
                Lang.lines("menus.main.base-lore-desc", "action", actionTip));

        // 修复3: 菜单【成员管理】头颅显示为会长头颅(多个会长随机一个)
        ItemStack memberHead = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta msm = (SkullMeta) memberHead.getItemMeta();
        UUID presUuid = getRandomPresidentUuid(player, g);
        applyPlayerSkull(msm, presUuid, null);
        msm.setDisplayName(Lang.text("menus.main.members-title"));
        msm.setLore(Lang.lines("menus.main.members-lore"));
        memberHead.setItemMeta(msm);
        inv.setItem(20, memberHead);

        // 需求5: 在已加入公会的公会菜单新增按钮：全服公会列表（居中放置在 22 号槽位）
        put(inv, 22, Material.BOOK,
                Lang.text("menus.main.list-title"),
                Lang.lines("menus.main.list-lore"));

        put(inv, 24, Material.NETHER_STAR,
                Lang.text("menus.main.manage-title"),
                Lang.lines("menus.main.manage-lore"));

        put(inv, 31, Material.BARRIER,
                Lang.text("menus.common.close"),
                Lang.lines("menus.common.close-lore"));
        player.openInventory(inv);
    }

    /* ══════════════════════════════════════════════════════════════ */
    /*                   界面 3: 公会基地二级管理菜单               */
    /* ══════════════════════════════════════════════════════════════ */
    public void openBaseManageMenu(Player player) {
        playClick(player);
        Inventory inv = Bukkit.createInventory(new MenuHolder(MenuType.BASE_MANAGE), 27,
                Lang.text("menus.base-manage.title"));
        fill(inv);

        var opt = service.getBaseLocation(player.getUniqueId());
        String status = opt.map(b -> Lang.text("menus.base-manage.status-set", "server", b.server(), "x", String.valueOf((int)b.x()), "y", String.valueOf((int)b.y()), "z", String.valueOf((int)b.z())))
                .orElse(Lang.text("menus.base-manage.status-unset"));

        put(inv, 4, Material.COMPASS,
                Lang.text("menus.base-manage.status-title"),
                Lang.lines("menus.base-manage.status-lore", "status", status));

        put(inv, 11, Material.ENDER_PEARL,
                Lang.text("menus.base-manage.teleport-title"),
                Lang.lines("menus.base-manage.teleport-lore"));

        put(inv, 15, Material.LODESTONE,
                Lang.text("menus.base-manage.set-title"),
                Lang.lines("menus.base-manage.set-lore"));

        put(inv, 22, Material.ARROW,
                Lang.text("menus.common.back-main"),
                Lang.lines("menus.common.back-main-lore"));
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
                Lang.text("menus.donate.title", "name", g.name()));
        fill(inv);

        double balance = economy != null ? economy.getBalance(player) : 0.0;

        put(inv, 4, Material.SUNFLOWER,
                Lang.text("menus.donate.info-title"),
                Lang.lines("menus.donate.info-lore", "xp", String.valueOf(g.xp()), "balance", String.valueOf((long)balance)));

        int[] amounts = {1000, 5000, 10000, 50000};
        Material[] mats = {Material.IRON_INGOT, Material.GOLD_INGOT, Material.DIAMOND, Material.EMERALD};
        int[] slots = {10, 12, 14, 16};

        for (int i = 0; i < 4; i++) {
            int amt = amounts[i];
            put(inv, slots[i], mats[i],
                    Lang.text("menus.donate.donate-btn", "amount", String.format(Lang.text("menus.donate.amount-format"), amt)),
                    Lang.lines("menus.donate.donate-btn-lore", "amount", String.format(Lang.text("menus.donate.amount-format"), amt)));
        }

        put(inv, 22, Material.NAME_TAG,
                Lang.text("menus.donate.custom-title"),
                Lang.lines("menus.donate.custom-lore"));

        put(inv, 18, Material.ARROW,
                Lang.text("menus.common.back-main"),
                Lang.lines("menus.common.back-main-lore"));

        put(inv, 26, Material.BARRIER,
                Lang.text("menus.common.close"),
                Lang.lines("menus.common.close-lore"));
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
                Lang.text("menus.members.title", "name", g.name()));
        fill(inv);

        Role myRole = service.roleOf(player.getUniqueId()).orElse(Role.MEMBER);
        List<MemberInfo> members = service.getMembers(player.getUniqueId());

        if (myRole.ordinal() >= Role.ELDER.ordinal()) {
            int appCount = service.applications(player.getUniqueId()).size();
            put(inv, 4, Material.WRITABLE_BOOK,
                    Lang.text("menus.members.apps-btn"),
                    Lang.lines("menus.members.apps-btn-lore", "count", String.valueOf(appCount)));

            // 需求8: 邀请成员按钮
            put(inv, 6, Material.PAPER,
                    Lang.text("menus.members.invite-title"),
                    Lang.lines("menus.members.invite-lore"));
        }

        int slot = 9;
        for (MemberInfo m : members) {
            if (slot >= 45) break;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta sm = (SkullMeta) head.getItemMeta();
            // 修复4: 修复成员列表头颅皮肤，应显示各个成员自己的头颅皮肤
            applyPlayerSkull(sm, m.uuid(), m.name());
            sm.setDisplayName(Lang.text("menus.members.head-title", "role", m.role().getMenuDisplay(), "name", m.name()));

            List<String> lore = new ArrayList<>(Lang.lines("menus.members.head-lore", "role", m.role().getMenuDisplay(), "contribution", String.valueOf(m.contribution()), "last_checkin", m.lastCheckin()));

            if (!m.uuid().equals(player.getUniqueId())) {
                if (myRole == Role.PRESIDENT) {
                    lore.add(Lang.text("menus.members.action-promote"));
                    lore.add(Lang.text("menus.members.action-demote"));
                    lore.add(Lang.text("menus.members.action-kick"));
                } else if (myRole == Role.VICE_PRESIDENT) {
                    if (m.role() == Role.MEMBER) {
                        lore.add(Lang.text("menus.members.action-promote"));
                        lore.add(Lang.text("menus.members.action-kick"));
                    } else if (m.role() == Role.ELDER) {
                        lore.add(Lang.text("menus.members.action-promote"));
                        lore.add(Lang.text("menus.members.action-demote"));
                        lore.add(Lang.text("menus.members.action-kick"));
                    }
                }
            }
            sm.setLore(lore);
            head.setItemMeta(sm);
            inv.setItem(slot++, head);
        }

        put(inv, 49, Material.ARROW,
                Lang.text("menus.common.back-main"),
                Lang.lines("menus.common.back-main-lore"));
        player.openInventory(inv);
    }

    /* ══════════════════════════════════════════════════════════════ */
    /*                   界面 5.5: 成员独立管理二级菜单              */
    /* ══════════════════════════════════════════════════════════════ */
    public void openMemberDetailMenu(Player player, MemberInfo target) {
        Optional<GuildView> opt = service.guildOf(player.getUniqueId());
        if (opt.isEmpty()) { openNoGuildMenu(player); return; }
        GuildView g = opt.get();
        playClick(player);

        Role myRole = service.roleOf(player.getUniqueId()).orElse(Role.MEMBER);
        Role targetRole = target.role();

        Inventory inv = Bukkit.createInventory(new MemberDetailHolder(target), 27,
                Lang.text("menus.member-detail.title", "name", target.name()));
        fill(inv);

        // 槽位 4: 成员信息展示头颅
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta sm = (SkullMeta) head.getItemMeta();
        applyPlayerSkull(sm, target.uuid(), target.name());
        sm.setDisplayName(Lang.text("menus.member-detail.info-title", "role", targetRole.getMenuDisplay(), "name", target.name()));
        sm.setLore(Lang.lines("menus.member-detail.info-lore", "role", targetRole.getMenuDisplay(), "contribution", String.valueOf(target.contribution()), "last_checkin", target.lastCheckin()));
        head.setItemMeta(sm);
        inv.setItem(4, head);

        // 判断是否可以升职
        boolean canPromote = false;
        Role nextRole = null;
        if (myRole == Role.PRESIDENT) {
            if (targetRole == Role.MEMBER) { canPromote = true; nextRole = Role.ELDER; }
            else if (targetRole == Role.ELDER) { canPromote = true; nextRole = Role.VICE_PRESIDENT; }
            else if (targetRole == Role.VICE_PRESIDENT) { canPromote = true; nextRole = Role.PRESIDENT; }
        } else if (myRole == Role.VICE_PRESIDENT) {
            if (targetRole == Role.MEMBER) { canPromote = true; nextRole = Role.ELDER; }
            else if (targetRole == Role.ELDER) { canPromote = true; nextRole = Role.VICE_PRESIDENT; }
        }

        if (canPromote && nextRole != null) {
            put(inv, 11, Material.EMERALD,
                    Lang.text("menus.member-detail.promote-title"),
                    Lang.lines("menus.member-detail.promote-lore", "next_role", nextRole.getMenuDisplay()));
        }

        // 判断是否可以降职
        boolean canDemote = false;
        Role prevRole = null;
        if (myRole == Role.PRESIDENT) {
            if (targetRole == Role.VICE_PRESIDENT) { canDemote = true; prevRole = Role.ELDER; }
            else if (targetRole == Role.ELDER) { canDemote = true; prevRole = Role.MEMBER; }
        } else if (myRole == Role.VICE_PRESIDENT) {
            if (targetRole == Role.ELDER) { canDemote = true; prevRole = Role.MEMBER; }
        }

        if (canDemote && prevRole != null) {
            put(inv, 13, Material.GOLD_INGOT,
                    Lang.text("menus.member-detail.demote-title"),
                    Lang.lines("menus.member-detail.demote-lore", "prev_role", prevRole.getMenuDisplay()));
        }

        // 判断是否可以踢出
        boolean canKick = false;
        if (myRole == Role.PRESIDENT && targetRole != Role.PRESIDENT) {
            canKick = true;
        } else if (myRole == Role.VICE_PRESIDENT && (targetRole == Role.MEMBER || targetRole == Role.ELDER)) {
            canKick = true;
        }

        if (canKick) {
            put(inv, 15, Material.BARRIER,
                    Lang.text("menus.member-detail.kick-title"),
                    Lang.lines("menus.member-detail.kick-lore"));
        }

        // 返回按钮
        put(inv, 22, Material.ARROW,
                Lang.text("menus.common.back-members"),
                Lang.lines("menus.common.back-members-lore"));

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

        List<MemberInfo> candidates = service.getMembers(player.getUniqueId()).stream()
                .filter(member -> !member.uuid().equals(player.getUniqueId())).limit(16).toList();
        Inventory inv = Bukkit.createInventory(new TransferMenuHolder(player.getUniqueId(), g, candidates), 36,
                Lang.text("menus.transfer.title", "name", g.name()));
        fill(inv);

        List<MemberInfo> members = service.getMembers(player.getUniqueId());

        int slot = 10;
        for (MemberInfo m : candidates) {
            // 修复5: 必须正确过滤掉自己，显示其它成员
            if (m.uuid().equals(player.getUniqueId())) continue;
            if (slot > 25) break;

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta sm = (SkullMeta) head.getItemMeta();
            // 头颅显示该候选成员本身的皮肤
            applyPlayerSkull(sm, m.uuid(), m.name());
            sm.setDisplayName(Lang.text("menus.transfer.item-title", "name", m.name(), "role", m.role().getMenuDisplay()));
            sm.setLore(Lang.lines("menus.transfer.item-lore", "role", m.role().getMenuDisplay(), "contribution", String.valueOf(m.contribution())));
            head.setItemMeta(sm);
            inv.setItem(slot++, head);
        }

        put(inv, 31, Material.NAME_TAG,
                Lang.text("menus.transfer.chat-title"),
                Lang.lines("menus.transfer.chat-lore"));

        put(inv, 27, Material.ARROW,
                Lang.text("menus.common.back-manage"),
                Lang.lines("menus.common.back-manage-lore"));
        player.openInventory(inv);
    }

    /* ══════════════════════════════════════════════════════════════ */
    /*                   界面 7: 公会商城独立 GUI                   */
    /* ══════════════════════════════════════════════════════════════ */
    public void openShopMenu(Player player) {
        playClick(player);
        Role role = service.roleOf(player.getUniqueId()).orElse(Role.MEMBER);

        Inventory inv = Bukkit.createInventory(new MenuHolder(MenuType.SHOP), 36,
                Lang.text("shop-menu.title", "role", role.getMenuDisplay()));
        fill(inv);

        Collection<ShopItem> allItems = shopService.items();
        int slot = 10;
        for (ShopItem item : allItems) {
            if (slot > 25) break;
            Optional<ShopItem.Offer> offerOpt = item.offers().containsKey(role)
                    ? Optional.of(item.offers().get(role))
                    : Optional.empty();

            Material mat = Material.CHEST;
            String itemName = Lang.text("shop-menu.default-item-name", "id", item.id());
            double price = 0;
            String limitDesc = Lang.text("shop-menu.limit-unlimited");
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
                    limitDesc = Lang.text("shop-menu.limit-format", "type", Lang.text("shop-menu.limit-types." + offer.limitType().name().toLowerCase(Locale.ROOT)), "remaining", String.valueOf(decision.remaining()), "limit", String.valueOf(offer.limitAmount()), "bought", String.valueOf(decision.purchased()));
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

            String actionText = canBuyRole
                    ? Lang.text("shop-menu.action-can-buy")
                    : Lang.text("shop-menu.action-no-perm");

            List<String> lore = Lang.lines("shop-menu.item-lore", "price", String.valueOf((long)price), "limit", limitDesc, "action", actionText);

            im.setLore(lore);
            is.setItemMeta(im);
            inv.setItem(slot++, is);
        }

        put(inv, 31, Material.ARROW,
                Lang.text("menus.common.back-main"),
                Lang.lines("menus.common.back-main-lore"));
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
                Lang.text("menus.manage.title", "name", g.name()));
        fill(inv);

        if (role == Role.PRESIDENT) {
            boolean claimed = service.isRewardClaimed(player.getUniqueId(), g.level());
            Material mat = claimed ? Material.MINECART : Material.CHEST_MINECART;
            String status = claimed
                    ? Lang.text("menus.manage.reward-claimed")
                    : Lang.text("menus.manage.reward-can-claim");

            // 槽位 10: 领取升级奖励
            put(inv, 10, mat,
                    Lang.text("menus.manage.reward-title"),
                    Lang.lines("menus.manage.reward-lore", "level", String.valueOf(g.level()), "status", status));

            // 槽位 12: 转让公会
            put(inv, 12, Material.ENCHANTED_BOOK,
                    Lang.text("menus.manage.transfer-title"),
                    Lang.lines("menus.manage.transfer-lore"));

            // 槽位 14: 修改公会图标
            put(inv, 14, Material.ITEM_FRAME,
                    Lang.text("menus.manage.icon-title"),
                    Lang.lines("menus.manage.icon-lore", "icon", g.icon() != null && !g.icon().isBlank() ? g.icon() : "WHITE_BANNER"));

            // 槽位 16: 解散公会
            put(inv, 16, Material.TNT,
                    Lang.text("menus.manage.disband-title"),
                    Lang.lines("menus.manage.disband-lore"));
        } else {
            put(inv, 13, Material.PAPER,
                    Lang.text("menus.manage.non-owner-title"),
                    Lang.lines("menus.manage.non-owner-lore", "role", role.getMenuDisplay()));
        }

        put(inv, 30, Material.SPRUCE_DOOR,
                Lang.text("menus.manage.leave-title"),
                Lang.lines("menus.manage.leave-lore"));

        put(inv, 32, Material.ARROW,
                Lang.text("menus.common.back-main"),
                Lang.lines("menus.common.back-main-lore"));
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
                Lang.text("menus.applications.title", "count", String.valueOf(apps.size())));
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
            sm.setDisplayName(Lang.text("menus.applications.item-title", "name", name));
            sm.setLore(Lang.lines("menus.applications.item-lore", "uuid", uid.toString()));
            head.setItemMeta(sm);
            inv.setItem(slot++, head);
        }

        put(inv, 31, Material.ARROW,
                Lang.text("menus.common.back-members"),
                Lang.lines("menus.common.back-members-lore"));
        player.openInventory(inv);
    }

    public enum SortMode { NEWEST, PROSPERITY, NAME }
    private final Map<UUID, SortMode> playerSortMode = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> playerSortAscending = new ConcurrentHashMap<>();

    private List<GuildView> getSortedGuilds(UUID playerUuid) {
        List<GuildView> list = new ArrayList<>(service.getAllGuilds());
        SortMode mode = playerSortMode.getOrDefault(playerUuid, SortMode.NEWEST);
        boolean asc = playerSortAscending.getOrDefault(playerUuid, false);

        Comparator<GuildView> comp = switch (mode) {
            case NEWEST -> Comparator.comparingLong(GuildView::createdAt);
            case PROSPERITY -> Comparator.comparingLong(GuildView::xp);
            case NAME -> Comparator.comparing(g -> g.name().toLowerCase(Locale.ROOT));
        };

        if (!asc) {
            comp = comp.reversed();
        }
        list.sort(comp);
        return list;
    }

    /* ══════════════════════════════════════════════════════════════ */
    /*                   界面 10: 全服公会浏览申请                  */
    /* ══════════════════════════════════════════════════════════════ */
    public void openAllGuildsList(Player player) {
        playClick(player);
        List<GuildView> list = getSortedGuilds(player.getUniqueId());
        Inventory inv = Bukkit.createInventory(new MenuHolder(MenuType.ALL_GUILDS), 54,
                Lang.text("menus.all-guilds.title", "count", String.valueOf(list.size())));
        fill(inv);

        int slot = 0;
        for (GuildView g : list) {
            if (slot >= 45) break;
            Material mat = Material.WHITE_BANNER;
            if (g.icon() != null && !g.icon().isBlank()) {
                Material m = Material.matchMaterial(g.icon());
                if (m != null && m.isItem()) {
                    mat = m;
                }
            }
            ItemStack banner = new ItemStack(mat);
            ItemMeta meta = banner.getItemMeta();
            meta.setDisplayName(Lang.text("menus.all-guilds.item-title", "name", g.name(), "level", String.valueOf(g.level())));
            String presStr = g.presidents() != null && !g.presidents().isEmpty()
                    ? String.join(Lang.text("menus.common.president-separator"), g.presidents())
                    : Lang.text("menus.common.none");
            long nextProsperity = FreeCoreGuildPlugin.getInstance().getNextLevelProsperity(g.level());
            String nextText = nextProsperity < 0 ? Lang.text("menus.common.max-level") : FreeCoreGuildPlugin.getInstance().formatProsperity(nextProsperity);
            meta.setLore(Lang.lines("menus.all-guilds.item-lore", "presidents", presStr, "level", String.valueOf(g.level()), "xp", FreeCoreGuildPlugin.getInstance().formatProsperity(g.xp()), "next_xp", nextText, "members", String.valueOf(g.members()), "max", String.valueOf(g.maxMembers())));
            banner.setItemMeta(meta);
            inv.setItem(slot++, banner);
        }

        // 排序/筛选按钮 (45 号槽位)
        SortMode mode = playerSortMode.getOrDefault(player.getUniqueId(), SortMode.NEWEST);
        boolean asc = playerSortAscending.getOrDefault(player.getUniqueId(), false);
        String modeName = switch (mode) {
            case NEWEST -> Lang.text("menus.all-guilds.sort-mode-newest");
            case PROSPERITY -> Lang.text("menus.all-guilds.sort-mode-prosperity");
            case NAME -> Lang.text("menus.all-guilds.sort-mode-name");
        };
        String orderName = asc ? Lang.text("menus.all-guilds.sort-order-ascending") : Lang.text("menus.all-guilds.sort-order-descending");

        put(inv, 45, Material.HOPPER,
                Lang.text("menus.all-guilds.sort-btn"),
                Lang.lines("menus.all-guilds.sort-lore", "mode", modeName, "order", orderName));

        put(inv, 49, Material.NAME_TAG,
                Lang.text("menus.all-guilds.chat-title"),
                Lang.lines("menus.all-guilds.chat-lore"));

        // 需求10: 搜寻列表新增二级菜单入口：我的公会邀请列表 (放置在 53 号槽位)
        List<GuildService.GuildInvite> pendingInvites = loadInvites(player);
        int inviteCount = pendingInvites == null ? 0 : pendingInvites.size();
        put(inv, 53, Material.WRITABLE_BOOK,
                Lang.text("menus.all-guilds.my-invites-btn"),
                Lang.lines("menus.all-guilds.my-invites-lore", "count", String.valueOf(inviteCount)));
        player.openInventory(inv);
    }

    /* ══════════════════════════════════════════════════════════════ */
    /*             界面 11: 全服公会独立纯预览列表（点击无操作）         */
    /* ══════════════════════════════════════════════════════════════ */
    private List<GuildService.GuildInvite> loadInvites(Player player) {
        try {
            return service.getInvitesFor(player.getUniqueId());
        } catch (IllegalStateException ex) {
            player.sendMessage(Lang.getPrefix() + Lang.text("menus.my-invites.load-failed"));
            return null;
        }
    }

    public void openInvitesMenu(Player player) {
        List<GuildService.GuildInvite> invites = loadInvites(player);
        if (invites == null) return;
        playClick(player);
        Inventory inv = Bukkit.createInventory(new InviteMenuHolder(invites), 36,
                Lang.text("menus.my-invites.title", "count", String.valueOf(invites.size())));
        fill(inv);

        if (invites.isEmpty()) {
            put(inv, 13, Material.BARRIER,
                    Lang.text("menus.my-invites.empty-title"),
                    Lang.lines("menus.my-invites.empty-lore"));
        } else {
            int slot = 10;
            long now = System.currentTimeMillis();
            for (GuildService.GuildInvite invite : invites) {
                if (slot > 25) break;
                long remainSec = Math.max(0L, (invite.expireAt() - now) / 1000L);
                put(inv, slot++, Material.ENCHANTED_BOOK,
                        Lang.text("menus.my-invites.item-title", "guild", invite.guildName()),
                        Lang.lines("menus.my-invites.item-lore", "guild", invite.guildName(), "inviter", invite.inviterName(), "remain", String.valueOf(remainSec)));
            }
        }

        put(inv, 31, Material.ARROW,
                Lang.text("menus.common.back-main"),
                Lang.lines("menus.my-invites.back-lore"));
        player.openInventory(inv);
    }
    /* ══════════════════════════════════════════════════════════════ */
    public void openAllGuildsPreviewList(Player player) {
        playClick(player);
        List<GuildView> list = getSortedGuilds(player.getUniqueId());
        Inventory inv = Bukkit.createInventory(new MenuHolder(MenuType.ALL_GUILDS_PREVIEW), 54,
                Lang.text("menus.all-guilds-preview.title", "count", String.valueOf(list.size())));
        fill(inv);

        int slot = 0;
        for (GuildView g : list) {
            if (slot >= 45) break;
            Material mat = Material.WHITE_BANNER;
            if (g.icon() != null && !g.icon().isBlank()) {
                Material m = Material.matchMaterial(g.icon());
                if (m != null && m.isItem()) {
                    mat = m;
                }
            }
            ItemStack banner = new ItemStack(mat);
            ItemMeta meta = banner.getItemMeta();
            meta.setDisplayName(Lang.text("menus.all-guilds-preview.item-title", "name", g.name(), "level", String.valueOf(g.level())));
            String presStr = g.presidents() != null && !g.presidents().isEmpty()
                    ? String.join(Lang.text("menus.common.president-separator"), g.presidents())
                    : Lang.text("menus.common.none");
            long nextProsperity = FreeCoreGuildPlugin.getInstance().getNextLevelProsperity(g.level());
            String nextText = nextProsperity < 0 ? Lang.text("menus.common.max-level") : FreeCoreGuildPlugin.getInstance().formatProsperity(nextProsperity);
            meta.setLore(Lang.lines("menus.all-guilds-preview.item-lore", "presidents", presStr, "level", String.valueOf(g.level()), "xp", FreeCoreGuildPlugin.getInstance().formatProsperity(g.xp()), "next_xp", nextText, "members", String.valueOf(g.members()), "max", String.valueOf(g.maxMembers())));
            banner.setItemMeta(meta);
            inv.setItem(slot++, banner);
        }

        // 排序/筛选按钮 (45 号槽位)
        SortMode mode = playerSortMode.getOrDefault(player.getUniqueId(), SortMode.NEWEST);
        boolean asc = playerSortAscending.getOrDefault(player.getUniqueId(), false);
        String modeName = switch (mode) {
            case NEWEST -> Lang.text("menus.all-guilds.sort-mode-newest");
            case PROSPERITY -> Lang.text("menus.all-guilds.sort-mode-prosperity");
            case NAME -> Lang.text("menus.all-guilds.sort-mode-name");
        };
        String orderName = asc ? Lang.text("menus.all-guilds.sort-order-ascending") : Lang.text("menus.all-guilds.sort-order-descending");

        put(inv, 45, Material.HOPPER,
                Lang.text("menus.all-guilds-preview.sort-btn"),
                Lang.lines("menus.all-guilds-preview.sort-lore", "mode", modeName, "order", orderName));

        put(inv, 49, Material.ARROW,
                Lang.text("menus.common.back-main"),
                Lang.lines("menus.common.back-main-lore"));
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

        if (holder instanceof ConfirmationHolder confirmation) {
            if (event.getClick() != org.bukkit.event.inventory.ClickType.LEFT
                    && event.getClick() != org.bukkit.event.inventory.ClickType.RIGHT) return;
            handleConfirmationClick(player, slot, confirmation);
            return;
        }

        if (holder instanceof MemberDetailHolder detailHolder) {
            handleMemberDetailClick(player, slot, detailHolder.target);
            return;
        }

        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || clickedItem.getType() == Material.AIR || clickedItem.getType() == Material.GRAY_STAINED_GLASS_PANE) {
            // 点击的是菜单背景或空气，直接返回，不触发任何逻辑与任何提示（彻底杜绝隐形按钮）
            return;
        }

        switch (holder.type) {
            case NO_GUILD -> handleNoGuildClick(player, slot);
            case MAIN -> handleMainClick(player, slot);
            case BASE_MANAGE -> handleBaseManageClick(player, slot);
            case DONATE -> handleDonateClick(player, slot);
            case MEMBERS -> handleMembersClick(player, slot);
            case TRANSFER -> handleTransferClick(player, slot, holder);
            case SHOP -> handleShopClick(player, slot);
            case APPLICATIONS -> handleApplicationsClick(player, slot, event.isLeftClick(), event.isRightClick());
            case MANAGE -> handleManageClick(player, slot);
            case ALL_GUILDS -> handleAllGuildsClick(player, slot, event.isLeftClick(), event.isRightClick());
            case ALL_GUILDS_PREVIEW -> handleAllGuildsPreviewClick(player, slot, event.isLeftClick(), event.isRightClick());
            case MY_INVITES -> handleMyInvitesClick(player, slot, event.isLeftClick(), event.isRightClick(), holder);
        }
    }

    private void handleAllGuildsPreviewClick(Player player, int slot, boolean left, boolean right) {
        if (slot == 45) {
            toggleSort(player, left, right);
            openAllGuildsPreviewList(player);
            return;
        }
        if (slot == 49) {
            openMain(player);
        }
    }

    private void toggleSort(Player player, boolean left, boolean right) {
        UUID uid = player.getUniqueId();
        if (left) {
            SortMode cur = playerSortMode.getOrDefault(uid, SortMode.NEWEST);
            SortMode next = switch (cur) {
                case NEWEST -> SortMode.PROSPERITY;
                case PROSPERITY -> SortMode.NAME;
                case NAME -> SortMode.NEWEST;
            };
            playerSortMode.put(uid, next);
            playClick(player);
        } else if (right) {
            boolean curAsc = playerSortAscending.getOrDefault(uid, false);
            playerSortAscending.put(uid, !curAsc);
            playClick(player);
        }
    }

    private void handleMyInvitesClick(Player player, int slot, boolean left, boolean right, MenuHolder holder) {
        if (slot == 31) {
            openAllGuildsList(player);
            return;
        }

        List<GuildService.GuildInvite> invites = holder instanceof InviteMenuHolder snapshot
                ? snapshot.invites : List.of();
        int idx = slot - 10;
        if (slot <= 25 && idx >= 0 && idx < invites.size()) {
            GuildService.GuildInvite inv = invites.get(idx);
            try {
                if (!service.hasInvite(player.getUniqueId(), inv.guildName())) {
                    player.sendMessage(Lang.msg("invite-expired"));
                    openInvitesMenu(player);
                    return;
                }
            } catch (IllegalStateException ex) {
                player.sendMessage(Lang.getPrefix() + Lang.text("menus.my-invites.load-failed"));
                return;
            }
            if (left) {
                // 接受邀请
                long cd = service.getLeaveCooldownRemainingMillis(player.getUniqueId());
                if (cd > 0) {
                    long hours = (cd / 3600_000L) + 1;
                    playFail(player);
                    player.sendMessage(Lang.msg("cooldown-active", "hours", String.valueOf(hours)));
                    return;
                }
                if (service.acceptInvite(player.getUniqueId(), inv.guildName())) {
                    playSuccess(player);
                    player.sendMessage(Lang.msg("invite-accepted", "guild", inv.guildName()));
                    FreeCoreGuildPlugin.getInstance().broadcastGuildCross(inv.guildName(),
                            Lang.text("broadcast.join", "player", player.getName()));
                    FreeCoreGuildPlugin.getInstance().refreshGuildMenus();
                    GuildRedisBridge bridge = FreeCoreGuildPlugin.getInstance().getRedisBridge();
                    if (bridge != null) bridge.publish("SYNC_ALL|||");
                    openMain(player);
                } else {
                    playFail(player);
                    player.sendMessage(Lang.msg("app-approved-failed"));
                    openInvitesMenu(player);
                }
            } else if (right) {
                // Only acknowledge rejection after the shared invite was removed.
                try {
                    service.removeInvite(player.getUniqueId(), inv.guildName());
                } catch (IllegalStateException ex) {
                    playFail(player);
                    player.sendMessage(Lang.getPrefix() + Lang.text("menus.my-invites.process-failed"));
                    return;
                }
                playSuccess(player);
                player.sendMessage(Lang.msg("invite-rejected", "guild", inv.guildName()));
                openInvitesMenu(player);
            }
        }
    }

    private void handleAllGuildsPreviewClick(Player player, int slot) {
        if (slot == 49) {
            openMain(player);
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

    private final Set<UUID> checkinClickCooldown = ConcurrentHashMap.newKeySet();

    private void handleMainClick(Player player, int slot) {
        if (slot == 10) {
            if (!checkinClickCooldown.add(player.getUniqueId())) {
                return;
            }
            try {
                int beforeLevel = service.guildOf(player.getUniqueId()).map(GuildView::level).orElse(0);
                CheckinResult res = service.checkinPlayer(player.getUniqueId());
                if (res.success()) {
                    if (res.goldAmount() > 0) {
                        java.time.DayOfWeek dow = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).getDayOfWeek();
                        boolean isWeekend = (dow == java.time.DayOfWeek.SATURDAY || dow == java.time.DayOfWeek.SUNDAY);
                        Material rewardMat = FreeCoreGuildPlugin.getInstance().getCheckinRewardMaterial(isWeekend);
                        player.getInventory().addItem(new ItemStack(rewardMat, res.goldAmount()));
                    }
                    playSuccess(player);
                    player.sendMessage(Lang.msg("checked-in", "gold", String.valueOf(res.goldAmount()), "prosperity", String.valueOf(res.prosperity())));
                    // gc公会频道广播每日签到
                    service.guildOf(player.getUniqueId()).ifPresent(g -> {
                        FreeCoreGuildPlugin.getInstance().broadcastGuildCross(g.name(),
                                Lang.text("broadcast.checkin", "player", player.getName(), "prosperity", String.valueOf(res.prosperity())));
                        if (g.level() > beforeLevel) FreeCoreGuildPlugin.getInstance().broadcastGuildCross(g.name(),
                                Lang.text("broadcast.upgrade", "level", String.valueOf(g.level())));
                    });
                    FreeCoreGuildPlugin.getInstance().refreshGuildMenus();
                    GuildRedisBridge bridge = FreeCoreGuildPlugin.getInstance().getRedisBridge();
                    if (bridge != null) bridge.publish("SYNC_ALL|||");
                } else {
                    playFail(player);
                    player.sendMessage(Lang.msg("checkin-failed", "message", res.message()));
                }
                openMain(player);
            } finally {
                Bukkit.getScheduler().runTaskLater(FreeCoreGuildPlugin.getInstance(), () -> {
                    checkinClickCooldown.remove(player.getUniqueId());
                }, 20L); // 1秒点击防抖
            }
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
        } else if (slot == 22) {
            // 点击查看全服公会独立纯预览列表（点击无申请操作）
            openAllGuildsPreviewList(player);
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
                service.guildOf(player.getUniqueId()).ifPresent(g -> FreeCoreGuildPlugin.getInstance().broadcastGuildCross(g.name(),
                        Lang.text("broadcast.base", "player", player.getName())));
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
            player.sendMessage(Lang.msg("donated", "amount", String.valueOf((long) amount),
                    "prosperity", String.valueOf(economy.prosperityFor(amount))));
        } else {
            playFail(player);
            player.sendMessage(Lang.msg("donate-failed"));
        }
    }

    private void handleMembersClick(Player player, int slot) {
        if (slot == 49) { openMain(player); return; }
        Role myRole = service.roleOf(player.getUniqueId()).orElse(Role.MEMBER);
        if (slot == 4) {
            if (myRole.ordinal() < Role.ELDER.ordinal()) {
                playFail(player);
                player.sendMessage(Lang.getPrefix() + Lang.text("menus.applications.no-permission"));
                return;
            }
            openApplicationsMenu(player);
            return;
        }
        if (slot == 6) {
            // 需求8: 邀请成员菜单点击
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

            // 需求12: 所有成员均可点击打开查看该成员的信息（管理按钮由权限判断是否可见）
            openMemberDetailMenu(player, target);
        }
    }

    private void handleMemberDetailClick(Player player, int slot, MemberInfo target) {
        if (slot == 4) {
            // 需求2: 成员管理最上方的成员头像点击执行 /profile（未来好友/个人资料功能）
            player.closeInventory();
            player.performCommand("profile " + target.name());
            return;
        }

        if (slot == 22) {
            openMembersMenu(player);
            return;
        }

        // 重新获取最新身份
        Role myRole = service.roleOf(player.getUniqueId()).orElse(Role.MEMBER);
        Optional<GuildView> opt = service.guildOf(player.getUniqueId());
        if (opt.isEmpty()) return;

        // 槽位 11: 升职
        if (slot == 11) {
            Role targetRole = service.roleOf(target.uuid()).orElse(Role.MEMBER);
            boolean canPromote = (myRole == Role.PRESIDENT && targetRole.ordinal() < Role.VICE_PRESIDENT.ordinal())
                    || (myRole == Role.VICE_PRESIDENT && targetRole == Role.MEMBER);
            if (!canPromote) return;

            Role nextRole = Role.values()[targetRole.ordinal() + 1];
            var res = service.promoteWithReason(player.getUniqueId(), target.uuid(), nextRole);
            if (res.success()) {
                playSuccess(player);
                Role newRole = res.newRole() != null ? res.newRole() : service.roleOf(target.uuid()).orElse(Role.MEMBER);
                player.sendMessage(Lang.text("broadcast.promote", "player", target.name(), "role", newRole.getMenuDisplay()));
                service.guildOf(player.getUniqueId()).ifPresent(g -> {
                    FreeCoreGuildPlugin.getInstance().broadcastGuildCross(g.name(),
                            Lang.text("broadcast.promote", "player", target.name(), "role", newRole.getMenuDisplay()));
                });
            } else {
                playFail(player);
                player.sendMessage(Lang.getPrefix() + Lang.text("menus.member-detail.promote-failed", "reason", res.reason()));
            }
            openMembersMenu(player);
            return;
        }

        // 槽位 13: 降职
        if (slot == 13) {
            Role targetRole = service.roleOf(target.uuid()).orElse(Role.MEMBER);
            boolean canDemote = (myRole == Role.PRESIDENT && targetRole.ordinal() > Role.MEMBER.ordinal() && targetRole.ordinal() < Role.PRESIDENT.ordinal())
                    || (myRole == Role.VICE_PRESIDENT && targetRole == Role.ELDER);
            if (!canDemote) return;

            if (service.demote(player.getUniqueId(), target.uuid())) {
                playSuccess(player);
                Role newRole = service.roleOf(target.uuid()).orElse(Role.MEMBER);
                player.sendMessage(Lang.getPrefix() + Lang.text("menus.member-detail.demote-success", "player", target.name(), "role", newRole.getMenuDisplay()));
                service.guildOf(player.getUniqueId()).ifPresent(g -> {
                    FreeCoreGuildPlugin.getInstance().broadcastGuildCross(g.name(),
                            Lang.text("broadcast.demote", "player", target.name(), "role", newRole.getMenuDisplay()));
                });
            } else {
                playFail(player);
                player.sendMessage(Lang.msg("demote-failed"));
            }
            openMembersMenu(player);
            return;
        }

        // 槽位 15: 踢出必须先经过独立确认界面。
        if (slot == 15) requestKickConfirmation(player, target.uuid(), target.name());
    }

    private void handleTransferClick(Player player, int slot, MenuHolder holder) {
        if (!(holder instanceof TransferMenuHolder snapshot)
                || !snapshot.actor.equals(player.getUniqueId())) return;
        GuildView guild = service.guildOf(player.getUniqueId()).orElse(null);
        if (guild == null || !guild.name().equals(snapshot.guildName)
                || !Objects.equals(guild.owner(), snapshot.owner)) {
            confirmationFailure(player);
            return;
        }
        if (slot == 27) { openManageMenu(player); return; }
        if (slot == 31) {
            UUID actor = player.getUniqueId();
            clearPlayerInputs(actor);
            player.closeInventory();
            pendingTransferInput.add(actor);
            Object session = new Object();
            inputSessions.put(actor, session);
            player.sendMessage(Lang.msg("transfer-prompt"));
            Bukkit.getScheduler().runTaskLater(FreeCoreGuildPlugin.getInstance(), () -> {
                if (pendingTransferInput.remove(actor) && inputSessions.remove(actor, session)
                        && player.isOnline()) {
                    player.sendMessage(Lang.getPrefix() + Lang.text("menus.confirm.expired"));
                }
            }, 1200L);
            return;
        }
        int idx = slot - 10;
        if (slot <= 25 && idx >= 0 && idx < snapshot.candidates.size()) {
            MemberInfo target = snapshot.candidates.get(idx);
            requestTransferConfirmation(player, target.uuid(), target.name());
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
                            Lang.text("broadcast.join", "player", name));
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
        if (slot == 32) { openMain(player); return; }

        Role role = service.roleOf(player.getUniqueId()).orElse(Role.MEMBER);
        if (role == Role.PRESIDENT) {
            if (slot == 10) {
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
                    FreeCoreGuildPlugin.getInstance().broadcastGuildCross(opt.get().name(),
                            Lang.text("broadcast.reward", "player", player.getName(), "level", String.valueOf(level)));
                } else {
                    playFail(player);
                    player.sendMessage(Lang.msg("reward-already-claimed"));
                }
                openManageMenu(player);
                return;
            } else if (slot == 12) {
                openTransferMenu(player);
                return;
            } else if (slot == 14) {
                // 修改公会图标输入
                player.closeInventory();
                pendingIconInput.add(player.getUniqueId());
                player.sendMessage(Lang.getPrefix() + Lang.text("menus.manage.icon-prompt"));
                return;
            } else if (slot == 16) {
                requestDisbandConfirmation(player);
                return;
            }
        }

        if (slot == 30) requestLeaveConfirmation(player);
    }

    private void handleAllGuildsClick(Player player, int slot, boolean left, boolean right) {
        long cd = service.getLeaveCooldownRemainingMillis(player.getUniqueId());
        if (cd > 0) {
            long hours = (cd / 3600_000L) + 1;
            playFail(player);
            player.sendMessage(Lang.msg("cooldown-active", "hours", String.valueOf(hours)));
            return;
        }

        if (slot == 45) {
            // 切换排序方式
            toggleSort(player, left, right);
            openAllGuildsList(player);
            return;
        }

        if (slot == 49) {
            player.closeInventory();
            pendingApplyInput.add(player.getUniqueId());
            player.sendMessage(Lang.msg("apply-prompt"));
            return;
        }

        if (slot == 53) {
            // 打开我的公会邀请列表
            openInvitesMenu(player);
            return;
        }

        List<GuildView> list = getSortedGuilds(player.getUniqueId());
        if (slot >= 0 && slot < list.size()) {
            GuildView g = list.get(slot);
            var res = service.applyWithReason(player.getUniqueId(), g.name());
            if (res.success()) {
                playSuccess(player);
                player.sendMessage(Lang.msg("app-submitted", "name", g.name()));
                String appNotify = Lang.msg("app-received-notify", "player", player.getName());
                FreeCoreGuildPlugin.getInstance().broadcastGuildCross(g.name(), appNotify);
            } else {
                playFail(player);
                player.sendMessage(Lang.getPrefix() + Lang.text("menus.all-guilds.apply-failed", "reason", res.reason()));
            }
            openAllGuildsList(player);
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
    public void onPaperChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (!captureInput(player, event)) return;
        event.setCancelled(true);
        event.viewers().clear();
        event.message(Component.empty());
        event.renderer((source, sourceDisplayName, msg, viewer) -> Component.empty());
        queueInput(player, message);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();
        if (!captureInput(player, event)) return;
        event.setCancelled(true);
        event.getRecipients().clear();
        event.setMessage("");
        queueInput(player, message);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPaperChatHighest(AsyncChatEvent event) {
        if (!captureInput(event.getPlayer(), event)) return;
        event.setCancelled(true);
        event.viewers().clear();
        event.message(Component.empty());
        event.renderer((source, sourceDisplayName, msg, viewer) -> Component.empty());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChatHighest(AsyncPlayerChatEvent event) {
        if (!captureInput(event.getPlayer(), event)) return;
        event.setCancelled(true);
        event.getRecipients().clear();
        event.setMessage("");
    }

    private boolean hasPendingInput(UUID uuid) {
        return pendingGuildNameInput.containsKey(uuid)
                || pendingInviteInput.contains(uuid)
                || pendingCreateInput.contains(uuid)
                || pendingDonateInput.contains(uuid)
                || pendingApplyInput.contains(uuid)
                || pendingTransferInput.contains(uuid)
                || pendingIconInput.contains(uuid);
    }

    private void handleChatInput(Player player, String message) {
        UUID uuid = player.getUniqueId();

        ConfirmationHolder confirmation = pendingGuildNameInput.remove(uuid);
        if (confirmation != null) {
            message = message.trim();
            if (message.equals("取消") || message.equalsIgnoreCase("cancel")) {
                player.sendMessage(Lang.getPrefix() + Lang.text("menus.confirm.cancelled"));
                return;
            }
            if (!message.equals(confirmation.guildName)) {
                playFail(player);
                player.sendMessage(Lang.getPrefix() + Lang.text("menus.confirm.wrong-name"));
                return;
            }
            try {
                executeConfirmedAction(player, confirmation);
            } catch (RuntimeException ex) {
                confirmationFailure(player);
            }
            return;
        }
        // Existing non-confirmation inputs retain their whitespace normalization.
        handleOrdinaryChatInput(player, message.trim());
    }

    private void handleOrdinaryChatInput(Player player, String message) {
        UUID uuid = player.getUniqueId();
        if (pendingInviteInput.remove(uuid)) {
            Bukkit.getScheduler().runTask(FreeCoreGuildPlugin.getInstance(), () -> {
                player.performCommand("guild invite " + message);
            });
            return;
        }

        if (pendingCreateInput.remove(uuid)) {
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
            inputSessions.remove(uuid);
            if (message.equals("取消") || message.equalsIgnoreCase("cancel")) {
                player.sendMessage(Lang.getPrefix() + Lang.text("menus.confirm.cancelled"));
                return;
            }
            // queueInput already moved this onto the main thread; do not defer past a newer request.
            {
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

                requestTransferConfirmation(player, found.uuid(), found.name());
            }
            return;
        }

        if (pendingIconInput.remove(uuid)) {
            Bukkit.getScheduler().runTask(FreeCoreGuildPlugin.getInstance(), () -> {
                Material mat = Material.matchMaterial(message.toUpperCase(Locale.ROOT));
                if (mat == null || !mat.isItem()) {
                    playFail(player);
                    player.sendMessage(Lang.getPrefix() + Lang.text("menus.manage.icon-invalid", "input", message));
                    return;
                }
                if (service.setGuildIcon(player.getUniqueId(), mat.name())) {
                    playSuccess(player);
                    player.sendMessage(Lang.getPrefix() + Lang.text("menus.manage.icon-success", "icon", mat.name()));
                    service.guildOf(player.getUniqueId()).ifPresent(g -> {
                        FreeCoreGuildPlugin.getInstance().broadcastGuildCross(g.name(),
                                Lang.text("broadcast.icon", "icon", mat.name()));
                    });
                    GuildRedisBridge bridge = FreeCoreGuildPlugin.getInstance().getRedisBridge();
                    if (bridge != null) {
                        bridge.publish("SYNC_ALL|||");
                    }
                } else {
                    playFail(player);
                    player.sendMessage(Lang.getPrefix() + Lang.text("menus.manage.icon-failed"));
                }
            });
        }
    }

    private static void fill(Inventory inv) {
        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, Lang.text("menus.common.background-name"), null);
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

    public enum MenuType { NO_GUILD, MAIN, BASE_MANAGE, DONATE, MEMBERS, MEMBER_DETAIL, APPLICATIONS, TRANSFER, SHOP, MANAGE, ALL_GUILDS, ALL_GUILDS_PREVIEW, MY_INVITES, CONFIRM }
    public static class MenuHolder implements InventoryHolder {
        public final MenuType type;
        public MenuHolder(MenuType type) { this.type = type; }
        @Override public Inventory getInventory() { return null; }
    }
    private enum DestructiveAction { KICK, LEAVE, DISBAND, TRANSFER }

    /** Immutable request snapshot; consumption is tracked outside the holder by identity. */
    private static final class ConfirmationHolder extends MenuHolder {
        private final DestructiveAction action;
        private final UUID actor, owner, target;
        private final String guildName, targetName;
        private final long guildCreatedAt, guildIdentity, createdAt;
        private ConfirmationHolder(DestructiveAction action, UUID actor, UUID owner, String guildName,
                                   long guildCreatedAt, long guildIdentity, long createdAt, UUID target, String targetName) {
            super(MenuType.CONFIRM);
            this.action = action;
            this.actor = actor;
            this.owner = owner;
            this.guildName = guildName;
            this.guildCreatedAt = guildCreatedAt;
            this.guildIdentity = guildIdentity;
            this.createdAt = createdAt;
            this.target = target;
            this.targetName = targetName;
        }

        private ConfirmationHolder withCreatedAt(long createdAt) {
            return new ConfirmationHolder(action, actor, owner, guildName, guildCreatedAt, guildIdentity,
                    createdAt, target, targetName);
        }
    }

    private static final class TransferMenuHolder extends MenuHolder {
        private final UUID actor, owner;
        private final String guildName;
        private final List<MemberInfo> candidates;
        private TransferMenuHolder(UUID actor, GuildView guild, List<MemberInfo> candidates) {
            super(MenuType.TRANSFER);
            this.actor = actor;
            this.owner = guild.owner();
            this.guildName = guild.name();
            this.candidates = List.copyOf(candidates);
        }
    }

    public static final class InviteMenuHolder extends MenuHolder {
        private final List<GuildService.GuildInvite> invites;
        public InviteMenuHolder(List<GuildService.GuildInvite> invites) {
            super(MenuType.MY_INVITES);
            this.invites = List.copyOf(invites);
        }
    }
    public static final class MemberDetailHolder extends MenuHolder {
        public final MemberInfo target;
        public MemberDetailHolder(MemberInfo target) {
            super(MenuType.MEMBER_DETAIL);
            this.target = target;
        }
    }
}
