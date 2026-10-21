package io.github.freecoreguild;

import io.github.freecoreguild.api.GuildProvider;
import io.github.freecoreguild.lang.Lang;
import io.github.freecoreguild.service.*;
import org.bukkit.Material;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.NavigableMap;

public final class FreeCoreGuildPlugin extends JavaPlugin {
    private static FreeCoreGuildPlugin instance;
    private GuildService service;
    private ShopService shopService;
    private EconomyGateway economy;
    private GuildMenuListener menus;
    private GuildBaseTransferService baseTransferService;
    private NavigableMap<Integer, LevelConfig> levels;
    private GuildRedisBridge redisBridge;
    private GuildPlaceholderExpansion placeholderExpansion;

    /** Shared privacy gate for chat bridges and perception listeners. */
    public boolean isChatInputPending(java.util.UUID player) {
        return menus != null && menus.isChatInputPending(player);
    }

    public boolean isChatInputCaptured(java.util.UUID player) {
        return menus != null && menus.isChatInputCaptured(player);
    }

    public static FreeCoreGuildPlugin getInstance() {
        return instance;
    }

    public GuildRedisBridge getRedisBridge() {
        return redisBridge;
    }

    public ShopService getShopService() {
        return shopService;
    }

    public void refreshGuildMenus() {
        refreshInviteMenus(null);
    }

    public void refreshInviteMenus(java.util.UUID target) {
        if (isEnabled()) getServer().getScheduler().runTask(this, () -> {
            if (menus != null) menus.refreshOpenMenus(target);
        });
    }

    public void broadcastGuildCross(String guildName, String message) {
        // 本地广播给该公会玩家
        if (service != null && guildName != null) {
            for (org.bukkit.entity.Player online : org.bukkit.Bukkit.getOnlinePlayers()) {
                var g = service.guildOf(online.getUniqueId());
                if (g.isPresent() && g.get().name().equalsIgnoreCase(guildName)) {
                    online.sendMessage(message);
                }
            }
        }
        // 跨服发布
        if (redisBridge != null && guildName != null) {
            String serverId = org.bukkit.Bukkit.getPort() == 25570 ? "technical" : "survival";
            redisBridge.publish("CHAT|" + guildName + "|" + serverId + "|" + message);
        }
    }

    public List<String> getUpgradeRewardsForLevel(int level) {
        if (levels != null && levels.containsKey(level)) {
            return levels.get(level).upgradeRewards();
        }
        return List.of();
    }

    /** Returns the next cumulative prosperity threshold, or -1 when max level. */
    public long getNextLevelProsperity(int currentLevel) {
        if (levels == null) return -1L;
        var next = levels.higherEntry(currentLevel);
        return next == null ? -1L : next.getValue().requiredXp();
    }

    public String formatProsperity(long prosperity) {
        return java.text.NumberFormat.getIntegerInstance(java.util.Locale.CHINA).format(prosperity);
    }

    public void reloadPluginConfig() {
        reloadConfig();
        Lang.init(this);
        applyCommandLanguage();

        File levelsFile = new File(getDataFolder(), "levels.yml");
        this.levels = LevelConfig.load(levelsFile, getConfig().getConfigurationSection("guild.levels"));

        File shopFolder = new File(getDataFolder(), "shop");
        ShopConfig loadedShopConfig = ShopConfig.load(shopFolder, getConfig().getConfigurationSection("guild"));
        if (shopService != null) {
            shopService.reload(loadedShopConfig);
        }
        getLogger().info("FreeCoreGuild configurations, levels, and shop items reloaded successfully.");
    }

    private void applyCommandLanguage() {
        for (String name : java.util.List.of("guild", "gc", "gshop")) {
            PluginCommand command = getCommand(name);
            if (command != null) {
                command.setDescription(Lang.text("commands.metadata." + name + ".description"));
                command.setUsage(Lang.text("commands.metadata." + name + ".usage"));
            }
        }
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        saveResource("zh_CN.yml", false);
        saveResource("roles.yml", false);
        saveResource("checkin.yml", false);
        saveResource("levels.yml", false);

        // 初始化 shop 文件夹与按等级划分的商店实例模板
        File shopFolder = new File(getDataFolder(), "shop");
        if (!shopFolder.exists()) {
            shopFolder.mkdirs();
        }
        for (int i = 1; i <= 10; i++) {
            File shopFile = new File(shopFolder, "level_" + i + ".yml");
            if (!shopFile.exists()) {
                try (InputStream in = getResource("shop/level_" + i + ".yml")) {
                    if (in != null) {
                        Files.copy(in, shopFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (Exception ignored) {}
            }
        }

        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");

        Lang.init(this);
        applyCommandLanguage();

        File levelsFile = new File(getDataFolder(), "levels.yml");
        levels = LevelConfig.load(levelsFile, getConfig().getConfigurationSection("guild.levels"));

        long checkinProsperity = getConfig().getLong("guild.checkin.base-prosperity", 1000L);
        double checkinCoins = getConfig().getDouble("guild.checkin.reward-coins", 0.0);
        long leaveCooldownHours = getConfig().getLong("guild.leave-cooldown-hours", 24L);
        long leaveCooldownMillis = Math.max(0L, leaveCooldownHours) * 3600_000L;

        if ("mysql".equalsIgnoreCase(getConfig().getString("storage.type", "memory"))) {
            var m = getConfig().getConfigurationSection("storage.mysql");
            service = new MySqlGuildService(levels, m.getString("jdbc"), m.getString("username"), m.getString("password"), m.getInt("pool-size", 5), leaveCooldownMillis);
        } else {
            service = new MemoryGuildService(levels, checkinProsperity, checkinCoins, leaveCooldownMillis);
        }
        service.initialize();
        getServer().getServicesManager().register(GuildProvider.class, service, this, ServicePriority.Normal);
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            placeholderExpansion = new GuildPlaceholderExpansion(this, service);
            if (!placeholderExpansion.register()) {
                getLogger().warning("Failed to register %freecoreguild_name%.");
            }
        }

        economy = EconomyGateway.find(getServer(), service, getConfig().getDouble("guild.create-cost", 10000.0), getConfig().getDouble("guild.prosperity-per-coin", 1.0));
        if (economy == null) {
            getLogger().warning("Vault economy unavailable; guild creation and donations are disabled.");
        }

        ShopService.PurchaseLedger ledger = (player, itemId, from, to) -> {
            if (service instanceof MySqlGuildService mysql) {
                return new MySqlPurchaseLedger(mysql).purchased(player, itemId, from, to);
            }
            return 0;
        };
        PurchaseRecorder recorder = null;
        java.util.function.ToLongFunction<java.util.UUID> guildId = p -> 0L;
        if (service instanceof MySqlGuildService mysql) {
            MySqlPurchaseLedger purchases = new MySqlPurchaseLedger(mysql);
            recorder = purchases;
            guildId = purchases::guildId;
        }

        ShopConfig loadedShopConfig = ShopConfig.load(shopFolder, getConfig().getConfigurationSection("guild"));
        shopService = new ShopService(loadedShopConfig, ledger, recorder, economy, guildId);
        getServer().getServicesManager().register(io.github.freecoreguild.api.ShopProvider.class, shopService, this, ServicePriority.Normal);

        PluginCommand shopCommand = getCommand("gshop");
        if (shopCommand != null) {
            shopCommand.setExecutor(new ShopCommand(shopService));
        }

        baseTransferService = new GuildBaseTransferService(this, service);
        getServer().getPluginManager().registerEvents(baseTransferService, this);

        menus = new GuildMenuListener(service, shopService, economy, baseTransferService);
        getServer().getPluginManager().registerEvents(menus, this);

        redisBridge = new GuildRedisBridge(this);
        redisBridge.registerListener(this::handleCrossServerMessage);
        redisBridge.start();

        PluginCommand c = getCommand("guild");
        if (c != null) {
            GuildCommand gc = new GuildCommand(service, economy, menus, redisBridge);
            c.setExecutor(gc);
            c.setTabCompleter(gc);
        }

        PluginCommand gcCmd = getCommand("gc");
        if (gcCmd != null) {
            GuildChatCommand gcc = new GuildChatCommand(service, redisBridge);
            gcCmd.setExecutor(gcc);
            gcCmd.setTabCompleter(gcc);
        }
    }

    private void handleCrossServerMessage(String message) {
        if (message == null || message.isBlank()) return;
        String[] parts = message.split("\\|", 4);
        if (parts.length < 2) return;
        String action = parts[0];
        String currentServerId = getServer().getPort() == 25570 ? "technical" : "survival";

        switch (action) {
            case "CHAT" -> {
                if (parts.length >= 4) {
                    String guildName = parts[1];
                    String fromServer = parts[2];
                    String chatText = parts[3];
                    if (fromServer.equalsIgnoreCase(currentServerId)) return; // 忽略自己发出的
                    // 本地广播给公会成员
                    if (service != null) {
                        for (org.bukkit.entity.Player online : org.bukkit.Bukkit.getOnlinePlayers()) {
                            var g = service.guildOf(online.getUniqueId());
                            if (g.isPresent() && g.get().name().equalsIgnoreCase(guildName)) {
                                online.sendMessage(chatText);
                            }
                        }
                    }
                }
            }
            case "SYNC_ALL" -> {
                if (service instanceof MySqlGuildService mysql) {
                    mysql.loadAllFromDatabase();
                }
                refreshGuildMenus();
            }
            case "SHOP_REFRESH" -> {
                if (shopService != null) {
                    shopService.clearAllLimits();
                }
            }
            case "NOTIFY_PLAYER" -> {
                // NOTIFY_PLAYER|targetUuid|fromServer|message
                if (parts.length >= 4) {
                    try {
                        java.util.UUID targetUuid = java.util.UUID.fromString(parts[1]);
                        String notifyMsg = parts[3];
                        org.bukkit.entity.Player online = org.bukkit.Bukkit.getPlayer(targetUuid);
                        if (online != null && online.isOnline()) {
                            online.sendMessage(notifyMsg);
                            refreshInviteMenus(targetUuid);
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    public Material getCheckinRewardMaterial(boolean isWeekend) {
        String path = isWeekend ? "guild.checkin.weekend-reward-item.material" : "guild.checkin.base-reward-item.material";
        String matName = getConfig().getString(path, "RAW_GOLD");
        Material mat = Material.matchMaterial(matName);
        return (mat != null && mat.isItem()) ? mat : Material.GOLD_ORE;
    }

    @Override
    public void onDisable() {
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
            placeholderExpansion = null;
        }
        if (menus != null) menus.clearChatInputs();
        if (redisBridge != null) {
            redisBridge.shutdown();
            redisBridge = null;
        }
        getServer().getServicesManager().unregisterAll(this);
        if (service != null) {
            service.close();
            service = null;
        }
        shopService = null;
        economy = null;
        menus = null;
        baseTransferService = null;
        instance = null;
    }
}
