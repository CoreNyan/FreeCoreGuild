package io.github.freecoreguild.service;

import io.github.freecoreguild.FreeCoreGuildPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * 轻量级原生 Redis 同步网桥，支持命令执行、发布/订阅跨服消息以及离线通知持久化
 */
public final class GuildRedisBridge {
    private static final String CHANNEL = "fc:guild:cross";
    private static final String OFFLINE_NOTIFY_KEY = "fcguild:offline:notify:"; // List key: fcguild:offline:notify:<uuid>
    private static final String INVITE_KEY = "fcguild:invite:"; // Key: fcguild:invite:<targetUuid> -> guildName|inviterName|expiryTimestamp

    private final FreeCoreGuildPlugin plugin;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private volatile boolean running = false;
    private Thread subThread;
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    public GuildRedisBridge(FreeCoreGuildPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (running) return;
        running = true;
        subThread = new Thread(this::runSubscriber, "FCGuild-Redis-Subscriber");
        subThread.setDaemon(true);
        subThread.start();
    }

    public void shutdown() {
        running = false;
        if (subThread != null) {
            subThread.interrupt();
            subThread = null;
        }
        executor.shutdownNow();
    }

    public void registerListener(Consumer<String> consumer) {
        listeners.add(consumer);
    }

    private record Config(String host, int port, String password, int database) {}

    private Config config() {
        File configFile = new File(plugin.getDataFolder().getParentFile(), "HuskSync/config.yml");
        String host = "127.0.0.1";
        int port = 6379;
        String password = "";
        int database = 0;
        if (configFile.exists()) {
            try {
                YamlConfiguration cfg = YamlConfiguration.loadConfiguration(configFile);
                host = cfg.getString("database.redis.credentials.host", cfg.getString("redis.credentials.host", "127.0.0.1"));
                port = cfg.getInt("database.redis.credentials.port", cfg.getInt("redis.credentials.port", 6379));
                password = cfg.getString("database.redis.credentials.password", cfg.getString("redis.credentials.password", ""));
                database = cfg.getInt("database.redis.credentials.database", cfg.getInt("redis.credentials.database", 0));
            } catch (Exception ignored) {}
        }
        return new Config(host, port, password, database);
    }

    public String execute(String... cmd) {
        try {
            Config cfg = config();
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(cfg.host, cfg.port), 2000);
                s.setSoTimeout(2000);
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                if (cfg.password != null && !cfg.password.isEmpty()) {
                    if (send(out, in, "AUTH", cfg.password) == null) return null;
                }
                if (cfg.database != 0) {
                    if (send(out, in, "SELECT", String.valueOf(cfg.database)) == null) return null;
                }
                return send(out, in, cmd);
            }
        } catch (Exception ex) {
            return null;
        }
    }

    public void publish(String payload) {
        executor.execute(() -> execute("PUBLISH", CHANNEL, payload));
    }

    /** 保存离线通知 */
    public void pushOfflineNotification(UUID targetPlayer, String message) {
        if (targetPlayer == null || message == null || message.isBlank()) return;
        executor.execute(() -> {
            execute("RPUSH", OFFLINE_NOTIFY_KEY + targetPlayer, message);
            execute("EXPIRE", OFFLINE_NOTIFY_KEY + targetPlayer, "604800"); // 7天过期
        });
    }

    /** 读取并清空指定玩家的离线通知 */
    public List<String> pollOfflineNotifications(UUID targetPlayer) {
        if (targetPlayer == null) return List.of();
        List<String> result = new ArrayList<>();
        try {
            Config cfg = config();
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(cfg.host, cfg.port), 2000);
                s.setSoTimeout(2000);
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                if (cfg.password != null && !cfg.password.isEmpty()) {
                    send(out, in, "AUTH", cfg.password);
                }
                if (cfg.database != 0) {
                    send(out, in, "SELECT", String.valueOf(cfg.database));
                }
                // LRANGE key 0 -1
                write(out, "LRANGE", OFFLINE_NOTIFY_KEY + targetPlayer, "0", "-1");
                String[] items = array(in);
                if (items != null) {
                    result.addAll(Arrays.asList(items));
                }
                // DEL key
                send(out, in, "DEL", OFFLINE_NOTIFY_KEY + targetPlayer);
            }
        } catch (Exception ignored) {}
        return result;
    }

    /** 跨服设置邀请信息 (有效期300秒) */
    public void setInvite(UUID targetPlayer, String guildName, String inviterName) {
        long expireAt = System.currentTimeMillis() + 300_000L;
        String val = guildName + "|" + inviterName + "|" + expireAt;
        executor.execute(() -> execute("SET", INVITE_KEY + targetPlayer, val, "EX", "300"));
    }

    /** 获取邀请信息: 返回 [guildName, inviterName] 或 null */
    public String[] getInvite(UUID targetPlayer) {
        String val = execute("GET", INVITE_KEY + targetPlayer);
        if (val == null || val.isBlank()) return null;
        String[] parts = val.split("\\|", 3);
        if (parts.length >= 2) {
            return new String[]{parts[0], parts[1]};
        }
        return null;
    }

    public void removeInvite(UUID targetPlayer) {
        executor.execute(() -> execute("DEL", INVITE_KEY + targetPlayer));
    }

    private void runSubscriber() {
        while (running) {
            try {
                Config cfg = config();
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(cfg.host, cfg.port), 3000);
                    socket.setSoTimeout(0); // 订阅长连接不超时
                    InputStream in = socket.getInputStream();
                    OutputStream out = socket.getOutputStream();
                    if (cfg.password != null && !cfg.password.isEmpty()) {
                        if (send(out, in, "AUTH", cfg.password) == null) {
                            Thread.sleep(2000L);
                            continue;
                        }
                    }
                    if (cfg.database != 0) {
                        send(out, in, "SELECT", String.valueOf(cfg.database));
                    }
                    write(out, "SUBSCRIBE", CHANNEL);
                    String[] subscribed = array(in);
                    if (subscribed == null || subscribed.length != 3 || !"subscribe".equals(subscribed[0])) {
                        throw new IOException("Redis subscribe failed");
                    }
                    while (running) {
                        String[] packet = array(in);
                        if (packet != null && packet.length == 3 && "message".equals(packet[0])) {
                            String message = packet[2];
                            for (Consumer<String> l : listeners) {
                                try { l.accept(message); } catch (Throwable t) {
                                    plugin.getLogger().log(Level.WARNING, "Error dispatching redis message", t);
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                if (!running) break;
                try { Thread.sleep(2000L); } catch (InterruptedException ignored) { break; }
            }
        }
    }

    private static String send(OutputStream out, InputStream in, String... values) throws IOException {
        write(out, values);
        int type = in.read();
        if (type < 0 || type == '-') { line(in); return null; }
        String header = line(in);
        if (type == '$') {
            int length = Integer.parseInt(header);
            if (length < 0) return null;
            byte[] body = in.readNBytes(length);
            in.read(); in.read();
            return new String(body, StandardCharsets.UTF_8);
        }
        return header;
    }

    private static void write(OutputStream out, String... values) throws IOException {
        StringBuilder q = new StringBuilder("*").append(values.length).append("\r\n");
        for (String v : values) {
            byte[] b = v.getBytes(StandardCharsets.UTF_8);
            q.append("$").append(b.length).append("\r\n").append(v).append("\r\n");
        }
        out.write(q.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int n;
        while ((n = in.read()) != -1 && n != '\r') b.write(n);
        if (n < 0 || in.read() != '\n') throw new IOException("redis line malformed");
        return b.toString(StandardCharsets.UTF_8);
    }

    private static String[] array(InputStream in) throws IOException {
        int first = in.read();
        if (first != '*') {
            if (first < 0) throw new EOFException();
            line(in);
            return null;
        }
        int count = Integer.parseInt(line(in));
        if (count < 0) return null;
        String[] values = new String[count];
        for (int i = 0; i < count; i++) {
            int type = in.read();
            if (type == ':') {
                values[i] = line(in);
                continue;
            }
            if (type != '$') {
                line(in);
                values[i] = null;
                continue;
            }
            int length = Integer.parseInt(line(in));
            if (length < 0) {
                values[i] = null;
            } else {
                byte[] body = in.readNBytes(length);
                in.read(); in.read();
                values[i] = new String(body, StandardCharsets.UTF_8);
            }
        }
        return values;
    }
}
