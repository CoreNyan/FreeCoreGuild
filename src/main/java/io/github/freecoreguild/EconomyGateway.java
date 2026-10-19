package io.github.freecoreguild;

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
        if (coins <= 0 || !validCost(coins) || !validCost(prosperityPerCoin)) return false;
        if (!withdraw(player, coins)) return false;

        long prosperity;
        try {
            prosperity = Math.round(coins * prosperityPerCoin);
        } catch (RuntimeException ex) {
            refund(player, coins);
            return false;
        }

        if (prosperity <= 0) {
            refund(player, coins);
            return false;
        }

        int upgraded = guilds.donateAndCheckAutoUpgrade(player.getUniqueId(), prosperity);
        if (upgraded < 0) {
            refund(player, coins);
            return false;
        }
        return true;
    }

    public boolean withdraw(Player player, double amount) {
        try {
            return economy.has(player, amount) && economy.withdrawPlayer(player, amount).transactionSuccess();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public void refund(Player player, double amount) {
        try { economy.depositPlayer(player, amount); } catch (RuntimeException ignored) { }
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
