package io.github.freecoreguild.service;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

/**
 * 等级与升级奖励一体化配置 [Instance]
 */
public record LevelConfig(int level, long requiredProsperity, int maxMembers,
                          int maxPresidents, int maxVicePresidents, int maxElders,
                          int fakePlayerQuota, List<String> upgradeRewards) {

    public long requiredXp() { return requiredProsperity; }

    public static NavigableMap<Integer, LevelConfig> load(File levelsFile, ConfigurationSection fallbackSection) {
        NavigableMap<Integer, LevelConfig> result = new TreeMap<>();
        if (levelsFile != null && levelsFile.exists()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(levelsFile);
            ConfigurationSection root = yaml.getConfigurationSection("levels");
            if (root != null) {
                for (String key : root.getKeys(false)) {
                    ConfigurationSection sec = root.getConfigurationSection(key);
                    if (sec == null) continue;
                    try {
                        int lvl = sec.getInt("level", Integer.parseInt(key.replace("level_", "").replace("level", "")));
                        long prosperity = sec.getLong("required-prosperity", 0L);
                        int maxMembers = sec.getInt("max-members", 10);
                        int maxPresidents = sec.getInt("max-presidents", 1);
                        int maxVicePresidents = sec.getInt("max-vice-presidents", 1);
                        int maxElders = sec.getInt("max-elders", 1);
                        int fakeQuota = sec.getInt("fake-player-limit", 0);
                        List<String> rewards = sec.getStringList("upgrade-rewards");
                        result.put(lvl, new LevelConfig(lvl, prosperity, maxMembers, maxPresidents, maxVicePresidents, maxElders, fakeQuota, rewards));
                    } catch (Exception ignored) {}
                }
            }
        }

        if (result.isEmpty() && fallbackSection != null) {
            for (String key : fallbackSection.getKeys(false)) {
                try {
                    int level = Integer.parseInt(key);
                    String path = key + ".";
                    long prosperity = fallbackSection.contains(path + "required-prosperity")
                            ? fallbackSection.getLong(path + "required-prosperity")
                            : fallbackSection.getLong(path + "required-xp");
                    int quota = fallbackSection.contains(path + "fake-player-limit")
                            ? fallbackSection.getInt(path + "fake-player-limit")
                            : fallbackSection.getInt(path + "fake-player-quota", 0);
                    result.put(level, new LevelConfig(level, prosperity,
                            fallbackSection.getInt(path + "max-members", 10),
                            fallbackSection.getInt(path + "max-presidents", 1),
                            fallbackSection.getInt(path + "max-vice-presidents", 1),
                            fallbackSection.getInt(path + "max-elders", 1), quota, List.of()));
                } catch (NumberFormatException ignored) {}
            }
        }
        return result;
    }
}
