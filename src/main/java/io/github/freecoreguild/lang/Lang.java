package io.github.freecoreguild.lang;

import org.bukkit.ChatColor;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** All player-visible templates live in the external UTF-8 language file. */
public final class Lang {
    private static YamlConfiguration config;
    private static String prefix = "";
    private static JavaPlugin owner;
    private static final Set<String> missingKeys = ConcurrentHashMap.newKeySet();

    public static void init(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "zh_CN.yml");
        YamlConfiguration loaded = new YamlConfiguration();
        YamlConfiguration defaults = new YamlConfiguration();
        try (InputStream resource = plugin.getResource("zh_CN.yml")) {
            if (resource == null) throw new IllegalStateException("Missing bundled language resource: zh_CN.yml");
            defaults.load(new InputStreamReader(resource, StandardCharsets.UTF_8));
            if (file.exists()) loaded.load(file);
            else plugin.saveResource("zh_CN.yml", false);
            int added = mergeMissing(loaded, defaults);
            if (added > 0) {
                loaded.save(file);
                plugin.getLogger().info("Added " + added + " missing language entries to zh_CN.yml; existing custom values preserved.");
            }
        } catch (IOException | InvalidConfigurationException ex) {
            // Never overwrite an unreadable/malformed user language file with defaults.
            throw new IllegalStateException("Failed to load or merge zh_CN.yml; original file preserved", ex);
        }
        owner = plugin;
        config = loaded;
        missingKeys.clear();
        prefix = text("prefix");
    }

    /** Adds only absent leaves. Lists, empty values and custom strings are retained verbatim. */
    public static int mergeMissing(YamlConfiguration target, YamlConfiguration defaults) {
        int added = 0;
        for (String path : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(path) || target.contains(path)) continue;
            // Legacy releases accidentally nested these sections below menus.
            String legacy = (path.startsWith("broadcast.") || path.startsWith("admin.")
                    || path.startsWith("shop-menu.")) ? "menus." + path : null;
            Object value = legacy != null && target.contains(legacy) ? target.get(legacy) : defaults.get(path);
            target.set(path, value instanceof List<?> list ? new ArrayList<>(list) : value);
            added++;
        }
        return added;
    }

    public static String getPrefix() { return prefix; }

    public static String msg(String key, String... placeholders) {
        return prefix + text("messages." + key, placeholders);
    }

    public static String text(String path, String... placeholders) {
        if (config == null || !config.isString(path)) return missing(path);
        return color(replace(config.getString(path, ""), placeholders));
    }

    public static List<String> lines(String path, String... placeholders) {
        if (config == null || !config.isList(path)) return List.of(missing(path));
        List<String> result = new ArrayList<>();
        for (String line : config.getStringList(path)) result.add(color(replace(line, placeholders)));
        return result;
    }

    private static String replace(String value, String... placeholders) {
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            value = value.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
        }
        return value;
    }

    private static String missing(String path) {
        if (missingKeys.add(path) && owner != null) owner.getLogger().warning("Missing or invalid language key: " + path);
        return "[missing-lang:" + path + "]";
    }

    /** Binary compatibility for older integrations; templates are no longer sourced from Java defaults. */
    @Deprecated
    public static String raw(String path, String ignoredDefault, String... placeholders) {
        return text(path, placeholders);
    }

    @Deprecated
    public static List<String> list(String path, List<String> ignoredDefault, String... placeholders) {
        return lines(path, placeholders);
    }

    public static String color(String value) {
        return value == null ? "" : ChatColor.translateAlternateColorCodes('&', value);
    }
}
