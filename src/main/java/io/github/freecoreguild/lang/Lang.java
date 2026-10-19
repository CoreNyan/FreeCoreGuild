package io.github.freecoreguild.lang;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class Lang {
    private static YamlConfiguration config;
    private static String prefix = "";

    public static void init(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "zh_CN.yml");
        if (!file.exists()) {
            plugin.saveResource("zh_CN.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);
        prefix = color(config.getString("prefix", "&b&l公会 &f&l>>> &f"));
    }

    public static String getPrefix() {
        return prefix;
    }

    public static String msg(String key, String... placeholders) {
        if (config == null) return key;
        String raw = config.getString("messages." + key, key);
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            raw = raw.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
        }
        return prefix + color(raw);
    }

    public static String raw(String path, String def, String... placeholders) {
        if (config == null) return color(def);
        String s = config.getString(path, def);
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            s = s.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
        }
        return color(s);
    }

    public static List<String> list(String path, List<String> def, String... placeholders) {
        if (config == null || !config.contains(path)) {
            List<String> res = new ArrayList<>();
            for (String line : def) {
                for (int i = 0; i + 1 < placeholders.length; i += 2) {
                    line = line.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
                }
                res.add(color(line));
            }
            return res;
        }
        List<String> rawList = config.getStringList(path);
        List<String> res = new ArrayList<>();
        for (String line : rawList) {
            for (int i = 0; i + 1 < placeholders.length; i += 2) {
                line = line.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
            }
            res.add(color(line));
        }
        return res;
    }

    public static String color(String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }
}
