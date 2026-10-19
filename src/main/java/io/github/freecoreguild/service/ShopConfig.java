package io.github.freecoreguild.service;

import io.github.freecoreguild.api.GuildProvider.Role;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

/**
 * 支持从独立的 shop 文件夹（按公会等级分别划分 shop/level_1.yml, shop/level_2.yml ...）加载商品实例配置
 */
public final class ShopConfig {
    private static final Role[] ORDER = {Role.MEMBER, Role.ELDER, Role.VICE_PRESIDENT, Role.PRESIDENT};
    private final Map<Integer, Map<String, ShopItem>> itemsByLevel;
    private final Map<String, ShopItem> allItems;
    private final String resetTimezone;

    private ShopConfig(Map<Integer, Map<String, ShopItem>> itemsByLevel, Map<String, ShopItem> allItems, String resetTimezone) {
        this.itemsByLevel = itemsByLevel;
        this.allItems = Collections.unmodifiableMap(allItems);
        this.resetTimezone = resetTimezone;
    }

    public Map<String, ShopItem> items() { return allItems; }
    public Map<String, ShopItem> itemsForLevel(int level) {
        return itemsByLevel.getOrDefault(level, allItems);
    }
    public Optional<ShopItem> item(String id) { return Optional.ofNullable(allItems.get(id)); }
    public String resetTimezone() { return resetTimezone; }

    public static ShopConfig load(File shopFolder, ConfigurationSection guildSection) {
        String timezone = "Asia/Shanghai";
        Map<Integer, Map<String, ShopItem>> byLevel = new HashMap<>();
        Map<String, ShopItem> combined = new LinkedHashMap<>();

        if (shopFolder != null && shopFolder.exists() && shopFolder.isDirectory()) {
            File[] files = shopFolder.listFiles((dir, name) -> name.endsWith(".yml") || name.endsWith(".yaml"));
            if (files != null) {
                Arrays.sort(files, Comparator.comparing(File::getName));
                for (File file : files) {
                    YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
                    int lvl = yaml.getInt("level", -1);
                    if (lvl <= 0) {
                        String fName = file.getName().replaceAll("\\D+", "");
                        if (!fName.isEmpty()) {
                            try { lvl = Integer.parseInt(fName); } catch (Exception ignored) {}
                        }
                    }
                    if (yaml.contains("reset-timezone")) {
                        timezone = yaml.getString("reset-timezone", timezone);
                    }

                    ConfigurationSection itemsSec = yaml.getConfigurationSection("items");
                    if (itemsSec != null) {
                        Map<String, ShopItem> lvlItems = parseItems(itemsSec);
                        if (lvl > 0) {
                            byLevel.put(lvl, lvlItems);
                        }
                        combined.putAll(lvlItems);
                    }
                }
            }
        }

        if (combined.isEmpty() && guildSection != null) {
            ConfigurationSection shop = guildSection.getConfigurationSection("shop");
            if (shop != null) {
                timezone = shop.getString("reset-timezone", "Asia/Shanghai");
                ConfigurationSection itemsSec = shop.getConfigurationSection("items");
                if (itemsSec != null) {
                    combined.putAll(parseItems(itemsSec));
                }
            }
        }

        return new ShopConfig(byLevel, combined, timezone);
    }

    private static Map<String, ShopItem> parseItems(ConfigurationSection section) {
        Map<String, ShopItem> map = new LinkedHashMap<>();
        for (String id : section.getKeys(false)) {
            ConfigurationSection item = section.getConfigurationSection(id);
            if (item == null) continue;
            Map<Role, ShopItem.Offer> offers = new EnumMap<>(Role.class);
            ShopItem.Offer inherited = null;
            for (Role role : ORDER) {
                ConfigurationSection roleSection = item.getConfigurationSection(role.name());
                if (roleSection != null) inherited = overlay(inherited, roleSection);
                if (inherited != null) offers.put(role, inherited);
            }
            if (!offers.isEmpty()) map.put(id, new ShopItem(id, offers));
        }
        return map;
    }

    private static ShopItem.Offer overlay(ShopItem.Offer base, ConfigurationSection section) {
        String material = section.contains("material") ? section.getString("material") : base == null ? null : base.material();
        String name = section.contains("name") ? section.getString("name") : base == null ? null : base.displayName();
        double price = section.contains("price") ? section.getDouble("price") : base == null ? 0 : base.price();
        ShopItem.LimitType type = base == null ? ShopItem.LimitType.UNLIMITED : base.limitType();
        if (section.contains("limit.type")) {
            try { type = ShopItem.LimitType.valueOf(section.getString("limit.type", "UNLIMITED").toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ignored) { type = ShopItem.LimitType.UNLIMITED; }
        } else if (base == null && section.contains("limit-type")) {
            try { type = ShopItem.LimitType.valueOf(section.getString("limit-type", "UNLIMITED").toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ignored) { type = ShopItem.LimitType.UNLIMITED; }
        }
        int amount = section.contains("limit.amount") ? section.getInt("limit.amount")
                : section.contains("limit-amount") ? section.getInt("limit-amount") : base == null ? 0 : base.limitAmount();
        return new ShopItem.Offer(material, name, price, type, amount);
    }
}
