package dev.tggamesyt.szakmai;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.*;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Szakmai extends JavaPlugin implements Listener {

    private final Map<Material, Integer> itemValues = new HashMap<>();
    private File configFile;
    private FileConfiguration config;
    private Scoreboard scoreboard;
    private Objective objective;

    @Override
    public void onEnable() {
        createAndLoadConfig();
        setupScoreboard();
        getServer().getPluginManager().registerEvents(this, this);

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                updatePlayerScore(player);
            }
        }, 20L, 20L);
    }

    private boolean isSurvivalAccessible(Material material) {
        if (material.isAir() || !material.isItem()) return false;

        String name = material.name();

        if (name.contains("SPAWN_EGG") ||
                name.contains("COMMAND_BLOCK") ||
                name.equals("DEBUG_STICK") ||
                name.equals("BARRIER") ||
                name.equals("STRUCTURE_BLOCK") ||
                name.equals("STRUCTURE_VOID") ||
                name.equals("LIGHT") ||
                name.equals("BEDROCK") ||
                name.equals("JIGSAW") ||
                name.equals("KNOWLEDGE_BOOK")) {
            return false;
        }
        return true;
    }
    public boolean isGear(String name) {
        return (name.contains("SWORD") ||
                name.contains("AXE") ||
                name.contains("PICKAXE") ||
                name.contains("HOE") ||
                name.contains("SHOVEL") ||
                name.contains("HELMET") ||
                name.contains("CHESTPLATE") ||
                name.contains("LEGGINGS") ||
                name.contains("BOOTS"));
    }

    private void createAndLoadConfig() {
        configFile = new File(getDataFolder(), "values.yml");
        if (!configFile.exists()) {
            configFile.getParentFile().mkdirs();
            config = new YamlConfiguration();

            // Alapértelmezett tagek létrehozása és pontozása
            config.set("tags.WOODENPLANK", 10);
            config.set("tags.BOATS", 20);
            config.set("tags.POTTERY_SHERDS", 200);
            config.set("tags.ARMOR_TRIMS", 500);
            config.set("tags.DIAMOND_GEAR", 5000);
            config.set("tags.IRON_GEAR", 10000);
            config.set("tags.COPPER_GEAR", 3000);
            config.set("tags.LEATHER_GEAR", 10000);

            for (Material material : Material.values()) {
                if (isSurvivalAccessible(material)) {
                    String name = material.name();

                    // Automatikus csoportosítás a tagek alá az első generáláskor
                    if (name.contains("PLANKS")) {
                        config.set("items." + name, "WOODENPLANK");
                    } else if (name.contains("BOAT") || name.contains("RAFT")) {
                        config.set("items." + name, "BOATS");
                    } else if (name.contains("POTTERY_SHERD")) {
                        config.set("items." + name, "POTTERY_SHERDS");
                    } else if (name.contains("SMITHING_TEMPLATE")) {
                        config.set("items." + name, "ARMOR_TRIMS");
                    } else if (name.startsWith("DIAMOND") && isGear(name)) {
                        config.set("items." + name, "DIAMOND_GEAR");
                    } else if (name.startsWith("IRON") && isGear(name)) {
                        config.set("items." + name, "IRON_GEAR");
                    } else if (name.startsWith("COPPER") && isGear(name)) {
                        config.set("items." + name, "COPPER_GEAR");
                    } else if (name.startsWith("LEATHER") && isGear(name)) {
                        config.set("items." + name, "LEATHER_GEAR");
                    } else {
                        config.set("items." + name, 10);
                    }
                }
            }
            try {
                config.save(configFile);
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            config = YamlConfiguration.loadConfiguration(configFile);
        }

        // 1. Tagek beolvasása
        Map<String, Integer> tags = new HashMap<>();
        if (config.getConfigurationSection("tags") != null) {
            for (String tagKey : config.getConfigurationSection("tags").getKeys(false)) {
                tags.put(tagKey.toUpperCase(), config.getInt("tags." + tagKey, 0));
            }
        }

        // 2. Tárgyak beolvasása és tagek feloldása számértékké
        if (config.getConfigurationSection("items") != null) {
            for (String key : config.getConfigurationSection("items").getKeys(false)) {
                try {
                    Material mat = Material.valueOf(key);
                    if (!isSurvivalAccessible(mat)) continue;

                    Object valueObject = config.get("items." + key);
                    int finalValue = 0;

                    if (valueObject instanceof Number) {
                        finalValue = ((Number) valueObject).intValue();
                    } else if (valueObject instanceof String) {
                        String tagRef = ((String) valueObject).toUpperCase();
                        finalValue = tags.getOrDefault(tagRef, 0);
                    }

                    itemValues.put(mat, finalValue);
                } catch (IllegalArgumentException ignored) {}
            }
        }
    }

    private void setupScoreboard() {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        scoreboard = manager.getNewScoreboard();

        objective = scoreboard.registerNewObjective("item_score", Criteria.DUMMY, Component.text("Item Score", NamedTextColor.GOLD));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
    }

    private void updatePlayerScore(Player player) {
        if (player.getScoreboard() != scoreboard) {
            player.setScoreboard(scoreboard);
        }

        int totalScore = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && isSurvivalAccessible(item.getType())) {
                int valuePerItem = itemValues.getOrDefault(item.getType(), 0);
                totalScore += (valuePerItem * item.getAmount());
            }
        }

        Score score = objective.getScore(player.getName());
        score.setScore(totalScore);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        applyTooltip(event.getCurrentItem());
        applyTooltip(event.getCursor());
        if (event.getWhoClicked() instanceof Player) {
            updatePlayerScore((Player) event.getWhoClicked());
        }
    }

    @EventHandler
    public void onInventoryCreative(InventoryCreativeEvent event) {
        applyTooltip(event.getCurrentItem());
        applyTooltip(event.getCursor());
        if (event.getWhoClicked() instanceof Player) {
            updatePlayerScore((Player) event.getWhoClicked());
        }
    }

    @EventHandler
    public void onPlayerPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            applyTooltip(event.getItem().getItemStack());
            Bukkit.getScheduler().runTaskLater(this, () -> updatePlayerScore(player), 1L);
        }
    }

    private void applyTooltip(ItemStack item) {
        if (item == null || !isSurvivalAccessible(item.getType())) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        List<Component> lore = meta.hasLore() ? meta.lore() : new ArrayList<>();
        if (lore == null) lore = new ArrayList<>();

        int value = itemValues.getOrDefault(item.getType(), 0);
        Component customLine = Component.text(value + " score", NamedTextColor.AQUA);

        lore.removeIf(line -> line.toString().contains("score"));

        if (!lore.contains(customLine)) {
            lore.add(customLine);
            meta.lore(lore);
            item.setItemMeta(meta);
        }
    }

    @Override
    public void onDisable() {
    }
}
