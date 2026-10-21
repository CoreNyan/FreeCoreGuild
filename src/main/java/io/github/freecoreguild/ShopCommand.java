package io.github.freecoreguild;

import io.github.freecoreguild.lang.Lang;
import io.github.freecoreguild.api.GuildProvider;
import io.github.freecoreguild.service.ShopService;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

public final class ShopCommand implements CommandExecutor {
    private final ShopService shop;
    public ShopCommand(ShopService shop) { this.shop = shop; }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage(Lang.text("commands.shop-player-only")); return true; }
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            sender.sendMessage(Lang.text("commands.shop-list", "items", String.join(Lang.text("commands.shop-item-separator"), shop.items().stream().map(i -> i.id()).toList()))); return true;
        }
        if (args[0].equalsIgnoreCase("check") || args[0].equalsIgnoreCase("buy")) {
            if (args.length < 2) { sender.sendMessage(Lang.text("commands.shop-usage-action", "action", args[0])); return true; }
            int amount = 1;
            if (args.length > 2) try { amount = Integer.parseInt(args[2]); } catch (NumberFormatException ignored) { amount = -1; }
            GuildProvider.Role role = getRole(player);
            if (args[0].equalsIgnoreCase("check")) {
                var d = shop.checkLimit(player.getUniqueId(), args[1], role, amount);
                sender.sendMessage(Lang.text(d.allowed() ? "commands.shop-success" : "commands.shop-failure", "message", d.reason()));
            } else {
                var result = shop.purchase(player, args[1], role, amount);
                sender.sendMessage(Lang.text(result.success() ? "commands.shop-success" : "commands.shop-failure", "message", result.message()));
            }
            return true;
        }
        sender.sendMessage(Lang.text("commands.shop-usage")); return true;
    }
    private GuildProvider.Role getRole(Player player) {
        var registration = player.getServer().getServicesManager().getRegistration(GuildProvider.class);
        if (registration == null) return GuildProvider.Role.MEMBER;
        return registration.getProvider().roleOf(player.getUniqueId()).orElse(GuildProvider.Role.MEMBER);
    }
}
