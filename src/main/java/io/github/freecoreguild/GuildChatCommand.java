package io.github.freecoreguild;

import io.github.freecoreguild.service.GuildRedisBridge;
import io.github.freecoreguild.service.GuildService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

public final class GuildChatCommand implements CommandExecutor, TabCompleter {
    private final GuildService service;
    private final GuildRedisBridge redisBridge;

    public GuildChatCommand(GuildService service, GuildRedisBridge redisBridge) {
        this.service = service;
        this.redisBridge = redisBridge;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家可以使用公会聊天频道！");
            return true;
        }

        var opt = service.guildOf(player.getUniqueId());
        if (opt.isEmpty()) {
            player.sendMessage("§c你当前未加入任何公会，无法在公会频道发言！");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage("§6用法：§e/gc <消息内容>");
            return true;
        }

        String msg = String.join(" ", args).trim();
        if (msg.isEmpty()) return true;

        String guildName = opt.get().name();
        var role = service.roleOf(player.getUniqueId()).orElse(io.github.freecoreguild.api.GuildProvider.Role.MEMBER);

        // 格式化公会聊天内容
        String formatted = "§8[§b公会聊天§8] §7[" + role.getMenuDisplay() + "§7] §f" + player.getName() + "§7: §f" + msg;

        // 本地广播给公会在线成员
        broadcastLocal(guildName, formatted);

        // 跨服发布
        if (redisBridge != null) {
            String serverId = Bukkit.getPort() == 25570 ? "technical" : "survival";
            redisBridge.publish("CHAT|" + guildName + "|" + serverId + "|" + formatted);
        }

        return true;
    }

    private void broadcastLocal(String guildName, String message) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            var g = service.guildOf(online.getUniqueId());
            if (g.isPresent() && g.get().name().equalsIgnoreCase(guildName)) {
                online.sendMessage(message);
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }
}
