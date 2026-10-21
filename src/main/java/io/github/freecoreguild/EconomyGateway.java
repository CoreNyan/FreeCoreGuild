package io.github.freecoreguild;

import io.github.freecoreguild.lang.Lang;
import io.github.freecoreguild.service.GuildService;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Bridges guild economic actions to Vault without making Vault a hard runtime dependency. */
public final class EconomyGateway {
    private final Economy economy;
    private final GuildService guilds;
    private final double createCost;
    private final double prosperityPerCoin;

    private EconomyGateway(Economy economy, GuildService guilds, double createCost, double prosperityPerCoin) {
        this.economy = economy;
        this.guilds = guilds;
        this.createCost = createCost;
        this.prosperityPerCoin = prosperityPerCoin;
    }

    public static EconomyGateway find(org.bukkit.Server server, GuildService guilds,
                                      double createCost, double prosperityPerCoin) {
        RegisteredServiceProvider<Economy> registration =
                server.getServicesManager().getRegistration(Economy.class);
        return registration == null || registration.getProvider() == null
                ? null : new EconomyGateway(registration.getProvider(), guilds, createCost, prosperityPerCoin);
    }

    public boolean create(Player player, String name) {
        if (!validCost(createCost) || !withdraw(player, createCost)) return false;
        if (guilds.create(player.getUniqueId(), name)) return true;
        refund(player, createCost);
        return false;
    }

    /**
     * 玩家使用 H 币捐献，增加繁荣度并检查自动升级
     * @return 返回是否捐献成功
     */
    public boolean donate(Player player, double coins) {
        long prosperity = prosperityFor(coins);
        if (prosperity <= 0) return false;
        var before = guilds.guildOf(player.getUniqueId());
        if (before.isEmpty() || !withdraw(player, coins)) return false;
        int upgraded;
        try {
            upgraded = guilds.donateAndCheckAutoUpgrade(player.getUniqueId(), prosperity);
        } catch (RuntimeException ex) {
            refund(player, coins);
            org.bukkit.Bukkit.getLogger().warning("[FreeCoreGuild] Donation persistence failed; refund requested.");
            return false;
        }
        if (upgraded < 0) {
            refund(player, coins);
            return false;
        }
        // Money/prosperity is already committed: notification errors must not refund it.
        FreeCoreGuildPlugin plugin = FreeCoreGuildPlugin.getInstance();
        if (plugin != null) {
            try {
                plugin.broadcastGuildCross(before.get().name(),
                        io.github.freecoreguild.lang.Lang.text("broadcast.donate",
                                "player", player.getName(), "amount", java.math.BigDecimal.valueOf(coins).stripTrailingZeros().toPlainString(),
                                "prosperity", String.valueOf(prosperity)));
                if (upgraded > 0) plugin.broadcastGuildCross(before.get().name(),
                        io.github.freecoreguild.lang.Lang.text("broadcast.upgrade",
                                "level", String.valueOf(upgraded)));
            } catch (RuntimeException ex) {
                org.bukkit.Bukkit.getLogger().warning("[FreeCoreGuild] Donation committed, but member notification failed.");
            }
            try {
                plugin.refreshGuildMenus();
                if (plugin.getRedisBridge() != null) plugin.getRedisBridge().publish("SYNC_ALL|||");
            } catch (RuntimeException ex) {
                org.bukkit.Bukkit.getLogger().warning("[FreeCoreGuild] Donation committed, but menu synchronization failed.");
            }
        }
        return true;
    }

    public long prosperityFor(double coins) {
        if (!validCost(coins) || !validCost(prosperityPerCoin)) return -1;
        double value = coins * prosperityPerCoin;
        if (!Double.isFinite(value) || value >= Long.MAX_VALUE) return -1;
        return Math.round(value);
    }

    public boolean withdraw(Player player, double amount) {
        try {
            return economy.has(player, amount) && economy.withdrawPlayer(player, amount).transactionSuccess();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public void refund(Player player, double amount) {
        try {
            var response = economy.depositPlayer(player, amount);
            if (response != null && response.transactionSuccess()) return;
        } catch (RuntimeException ignored) { }
        org.bukkit.Bukkit.getLogger().severe("[FreeCoreGuild] REFUND FAILED: player=" + player.getUniqueId() + ", amount=" + amount);
        player.sendMessage(Lang.text("messages.refund-failed"));
    }

    public void deposit(Player player, double amount) {
        try { economy.depositPlayer(player, amount); } catch (RuntimeException ignored) { }
    }

    public double getBalance(Player player) {
        try { return economy.getBalance(player); } catch (Exception ignored) { return 0.0; }
    }

    public double createCost() { return createCost; }

    private static boolean validCost(double value) { return Double.isFinite(value) && value > 0; }
}
