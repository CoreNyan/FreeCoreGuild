package io.github.freecoreguild.service;

import io.github.freecoreguild.FreeCoreGuildPlugin;
import io.github.freecoreguild.lang.Lang;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 公会基地跨服传送管理器
 * 完美解决跨服传送“只换服但不传送到坐标点”的通病：
 * 1. 同服直传：立即执行 player.teleport(loc)
 * 2. 跨服传送：通过 Redis 写入 120 秒有效期的票据 (fcguild:base:ticket:<uuid>)，并通过 BungeeCord 消息切换子服
 * 3. 目标服落地拦截：在 PlayerJoinEvent (LOWEST) 优先检测并执行坐标纠正传送，确保必定到达设定点
 */
public final class GuildBaseTransferService implements Listener {
    private final FreeCoreGuildPlugin plugin;
    private final GuildService service;
    private final String localServerId;

    public GuildBaseTransferService(FreeCoreGuildPlugin plugin, GuildService service) {
        this.plugin = plugin;
        this.service = service;
        this.localServerId = Bukkit.getPort() == 25570 ? "technical" : "survival";
    }

    public void teleportToBase(Player player) {
        var opt = service.getBaseLocation(player.getUniqueId());
        if (opt.isEmpty()) {
            player.sendMessage(Lang.msg("base-not-set"));
            return;
        }

        GuildService.GuildBaseLocation base = opt.get();
        if (base.server().equalsIgnoreCase(localServerId)) {
            // 同服传送
            World world = Bukkit.getWorld(base.world());
            if (world == null) {
                player.sendMessage(Lang.getPrefix() + Lang.text("messages.base-world-unloaded"));
                return;
            }
            Location targetLoc = new Location(world, base.x(), base.y(), base.z(), base.yaw(), base.pitch());
            player.teleport(targetLoc);
            player.sendMessage(Lang.msg("base-teleporting"));
        } else {
            // 跨服传送：先写 Redis 票据，再切服
            player.sendMessage(Lang.msg("base-teleporting"));
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                String payload = base.server() + "|" + base.world() + "|" + base.x() + "|" + base.y() + "|" + base.z() + "|" + base.yaw() + "|" + base.pitch();
                boolean ok = writeTicket(player.getUniqueId().toString(), payload);
                if (!ok) {
                    player.sendMessage(Lang.msg("base-cross-failed"));
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> connectServer(player, base.server()));
            });
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> pollAndTeleport(player, 0));
    }

    private void pollAndTeleport(Player player, int attempt) {
        if (!player.isOnline()) return;
        String ticket = readTicket(player.getUniqueId().toString());
        if (ticket == null || ticket.isBlank()) {
            if (attempt < 15) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> pollAndTeleport(player, attempt + 1), 3L);
            }
            return;
        }

        String[] parts = ticket.split("\\|");
        if (parts.length >= 7) {
            String targetServer = parts[0];
            if (targetServer.equalsIgnoreCase(localServerId)) {
                deleteTicket(player.getUniqueId().toString());
                String worldName = parts[1];
                double x = Double.parseDouble(parts[2]);
                double y = Double.parseDouble(parts[3]);
                double z = Double.parseDouble(parts[4]);
                float yaw = Float.parseFloat(parts[5]);
                float pitch = Float.parseFloat(parts[6]);

                Bukkit.getScheduler().runTask(plugin, () -> {
                    World w = Bukkit.getWorld(worldName);
                    if (w != null && player.isOnline()) {
                        player.teleport(new Location(w, x, y, z, yaw, pitch));
                    }
                });
            }
        }
    }

    private void connectServer(Player player, String server) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF("Connect");
            out.writeUTF(server);
            player.sendPluginMessage(plugin, "BungeeCord", bytes.toByteArray());
        } catch (IOException e) {
            player.sendMessage(Lang.msg("base-cross-failed"));
        }
    }

    private boolean writeTicket(String uuid, String payload) {
        return "OK".equalsIgnoreCase(redis("SET", "fcguild:base:ticket:" + uuid, payload, "EX", "120"));
    }

    private String readTicket(String uuid) {
        return redis("GET", "fcguild:base:ticket:" + uuid);
    }

    private void deleteTicket(String uuid) {
        redis("DEL", "fcguild:base:ticket:" + uuid);
    }

    private String redis(String... command) {
        File configFile = new File(plugin.getDataFolder().getParentFile(), "HuskSync/config.yml");
        String host = "127.0.0.1";
        int port = 6379;
        String password = "";
        int database = 0;
        if (configFile.exists()) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(configFile);
            host = cfg.getString("database.redis.credentials.host", cfg.getString("redis.credentials.host", "127.0.0.1"));
            port = cfg.getInt("database.redis.credentials.port", cfg.getInt("redis.credentials.port", 6379));
            password = cfg.getString("database.redis.credentials.password", cfg.getString("redis.credentials.password", ""));
            database = cfg.getInt("database.redis.credentials.database", cfg.getInt("redis.credentials.database", 0));
        }

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2000);
            socket.setSoTimeout(2000);
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            if (password != null && !password.isEmpty()) {
                if (sendRedis(output, input, "AUTH", password) == null) return null;
            }
            if (database != 0) {
                if (sendRedis(output, input, "SELECT", String.valueOf(database)) == null) return null;
            }
            return sendRedis(output, input, command);
        } catch (Exception e) {
            return null;
        }
    }

    private String sendRedis(OutputStream output, InputStream input, String... values) throws IOException {
        StringBuilder request = new StringBuilder("*").append(values.length).append("\r\n");
        for (String value : values) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            request.append("$").append(bytes.length).append("\r\n").append(value).append("\r\n");
        }
        output.write(request.toString().getBytes(StandardCharsets.UTF_8));
        output.flush();

        int type = input.read();
        if (type == -1 || type == '-') { readRedisLine(input); return null; }
        String line = readRedisLine(input);
        if (type == '$') {
            int length = Integer.parseInt(line);
            if (length < 0) return null;
            byte[] data = input.readNBytes(length);
            input.read(); input.read();
            return new String(data, StandardCharsets.UTF_8);
        }
        return line;
    }

    private String readRedisLine(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int next;
        while ((next = input.read()) != -1 && next != '\r') bytes.write(next);
        if (next == -1 || input.read() != '\n') throw new IOException("Malformed Redis response");
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
