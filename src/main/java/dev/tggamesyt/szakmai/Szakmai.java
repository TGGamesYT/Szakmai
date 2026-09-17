package dev.tggamesyt.szakmai;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class Szakmai extends JavaPlugin implements Listener, CommandExecutor {

    private final Map<Material, Integer> itemValues = new HashMap<>();
    private File configFile;
    private FileConfiguration config;
    private Scoreboard scoreboard;
    private Objective objective;

    private boolean timerActive = true;
    private boolean timerPaused = false;
    private BukkitTask scoreboardUpdaterTask;
    private BukkitTask lobbyGameModeTask;
    private BukkitRunnable timerTask;
    private BossBar bossBar;

    private int totalSeconds = 15 * 60;
    private int secondsLeft = 15 * 60;

    @Override
    public void onEnable() {
        createAndLoadConfig();
        setupScoreboard();
        getServer().getPluginManager().registerEvents(this, this);

        if (this.getCommand("starttimer") != null) {
            this.getCommand("starttimer").setExecutor(this);
        }
        if (this.getCommand("settimer") != null) {
            this.getCommand("settimer").setExecutor(this);
        }
        if (this.getCommand("pausetimer") != null) {
            this.getCommand("pausetimer").setExecutor(this);
        }

        scoreboardUpdaterTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!timerActive) return;
            updateAllScores();
        }, 20L, 20L);

        startLobbyTask();
    }


    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("settimer")) {
            if (!timerActive) {
                sender.sendMessage(Component.text("Game has already ended.", NamedTextColor.RED));
                return true;
            }
            if (args.length == 0) {
                sender.sendMessage(Component.text("Usage: /settimer <time><m/s>", NamedTextColor.RED));
                return true;
            }

            String input = args[0].toLowerCase();
            try {
                if (input.endsWith("m")) {
                    int minutes = Integer.parseInt(input.replace("m", ""));
                    secondsLeft = minutes * 60;
                } else if (input.endsWith("s")) {
                    secondsLeft = Integer.parseInt(input.replace("s", ""));
                } else {
                    secondsLeft = Integer.parseInt(input);
                }

                if (secondsLeft > totalSeconds) {
                    totalSeconds = secondsLeft;
                }

                int min = secondsLeft / 60;
                int sec = secondsLeft % 60;
                if (bossBar != null) {
                    bossBar.name(Component.text(String.format("%02d:%02d", min, sec), NamedTextColor.GOLD));
                    bossBar.progress(Math.max(0.0f, Math.min(1.0f, (float) secondsLeft / totalSeconds)));
                }
                sender.sendMessage(Component.text("Time adjusted.", NamedTextColor.GREEN));
            } catch (NumberFormatException e) {
                sender.sendMessage(Component.text("Invalid time format.", NamedTextColor.RED));
            }
            return true;
        }

        if (command.getName().equalsIgnoreCase("pausetimer")) {
            if (timerTask == null || !timerActive) {
                sender.sendMessage(Component.text("No active timer running.", NamedTextColor.RED));
                return true;
            }
            timerPaused = !timerPaused;
            if (timerPaused) {
                sender.sendMessage(Component.text("Timer paused.", NamedTextColor.YELLOW));
            } else {
                sender.sendMessage(Component.text("Timer resumed.", NamedTextColor.GREEN));
            }
            return true;
        }

        if (command.getName().equalsIgnoreCase("starttimer")) {
            if (timerTask != null) {
                sender.sendMessage(Component.text("Timer is already running.", NamedTextColor.RED));
                return true;
            }

            if (lobbyGameModeTask != null) {
                lobbyGameModeTask.cancel();
                lobbyGameModeTask = null;
            }

            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getGameMode() == GameMode.ADVENTURE) {
                    player.setGameMode(GameMode.SURVIVAL);
                }
            }

            secondsLeft = totalSeconds;
            int startMin = secondsLeft / 60;
            int startSec = secondsLeft % 60;

            bossBar = BossBar.bossBar(
                    Component.text(String.format("%02d:%02d", startMin, startSec), NamedTextColor.GOLD),
                    1.0f,
                    BossBar.Color.GREEN,
                    BossBar.Overlay.PROGRESS
            );

            for (Player player : Bukkit.getOnlinePlayers()) {
                player.showBossBar(bossBar);
            }

            timerTask = new BukkitRunnable() {
                @Override
                public void run() {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        player.showBossBar(bossBar);
                        if (player.getGameMode() == GameMode.ADVENTURE) {
                            player.setGameMode(GameMode.SURVIVAL);
                        }
                    }

                    if (timerPaused) return;

                    secondsLeft--;

                    if (secondsLeft <= 0) {
                        stopTimerAndLock();
                        cancel();
                        return;
                    }

                    int minutes = secondsLeft / 60;
                    int seconds = secondsLeft % 60;
                    bossBar.name(Component.text(String.format("%02d:%02d", minutes, seconds), NamedTextColor.GOLD));

                    float progress = Math.max(0.0f, Math.min(1.0f, (float) secondsLeft / totalSeconds));
                    bossBar.progress(progress);

                    if (progress > 0.75f) {
                        bossBar.color(BossBar.Color.GREEN);
                    } else if (progress > 0.50f) {
                        bossBar.color(BossBar.Color.YELLOW);
                    } else if (progress > 0.25f) {
                        bossBar.color(BossBar.Color.WHITE);
                    } else {
                        bossBar.color(BossBar.Color.RED);
                    }
                }
            };

            timerTask.runTaskTimer(this, 0L, 20L);
            sender.sendMessage(Component.text("Timer started.", NamedTextColor.GREEN));
            return true;
        }
        return false;
    }

    private void startLobbyTask() {
        if (lobbyGameModeTask != null) return;
        lobbyGameModeTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getGameMode() == GameMode.SURVIVAL) {
                    player.setGameMode(GameMode.ADVENTURE);
                }
            }
        }, 0L, 20L);
    }


    public void stopTimerAndLock() {
        this.timerActive = false;

        if (scoreboardUpdaterTask != null) {
            scoreboardUpdaterTask.cancel();
            scoreboardUpdaterTask = null;
        }
        if (timerTask != null) {
            timerTask = null;
        }

        if (bossBar != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.hideBossBar(bossBar);
            }
            bossBar = null;
        }

        updateAllScores();
        objective.displayName(Component.text("Final Score", NamedTextColor.RED));
        Bukkit.broadcast(Component.text("Time is up. Scores locked.", NamedTextColor.RED));
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (bossBar != null) {
            event.getPlayer().showBossBar(bossBar);
        }
    }
    private void updateAllScores() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getScoreboard() != scoreboard) {
                player.setScoreboard(scoreboard);
            }
        }

        if (!timerActive) return;

        Map<String, Integer> playerScores = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            int totalScore = 0;
            for (ItemStack item : player.getInventory().getContents()) {
                if (item != null && isSurvivalAccessible(item.getType())) {
                    int valuePerItem = itemValues.getOrDefault(item.getType(), 0);
                    totalScore += (valuePerItem * item.getAmount());
                }
            }
            playerScores.put(player.getName(), totalScore);
        }

        for (String entry : scoreboard.getEntries()) {
            scoreboard.resetScores(entry);
        }

        List<Map.Entry<String, Integer>> sortedPlayers = new ArrayList<>(playerScores.entrySet());
        sortedPlayers.sort((b, a) -> a.getValue().compareTo(b.getValue()));

        int place = 1;
        for (Map.Entry<String, Integer> entry : sortedPlayers) {
            String entryName = place + ". " + entry.getKey();
            Score score = objective.getScore(entryName);
            score.setScore(entry.getValue());
            place++;
        }
    }

    private boolean isSurvivalAccessible(Material material) {
        if (material.isAir() || !material.isItem()) return false;
        String name = material.name();
        return !name.contains("SPAWN_EGG") && !name.contains("COMMAND_BLOCK") && !name.equals("DEBUG_STICK") &&
                !name.equals("BARRIER") && !name.equals("STRUCTURE_BLOCK") && !name.equals("STRUCTURE_VOID") &&
                !name.equals("LIGHT") && !name.equals("BEDROCK") && !name.equals("JIGSAW") && !name.equals("KNOWLEDGE_BOOK");
    }

    public boolean isGear(String name) {
        return (name.contains("SWORD") || name.contains("AXE") || name.contains("PICKAXE") || name.contains("HOE") ||
                name.contains("SHOVEL") || name.contains("HELMET") || name.contains("CHESTPLATE") || name.contains("LEGGINGS") || name.contains("BOOTS"));
    }

    private void createAndLoadConfig() {
        configFile = new File(getDataFolder(), "values.yml");
        if (!configFile.exists()) {
            configFile.getParentFile().mkdirs();
            config = new YamlConfiguration();
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
                    if (name.contains("PLANKS")) config.set("items." + name, "WOODENPLANK");
                    else if (name.contains("BOAT") || name.contains("RAFT")) config.set("items." + name, "BOATS");
                    else if (name.contains("POTTERY_SHERD")) config.set("items." + name, "POTTERY_SHERDS");
                    else if (name.contains("SMITHING_TEMPLATE")) config.set("items." + name, "ARMOR_TRIMS");
                    else if (name.startsWith("DIAMOND") && isGear(name)) config.set("items." + name, "DIAMOND_GEAR");
                    else if (name.startsWith("IRON") && isGear(name)) config.set("items." + name, "IRON_GEAR");
                    else if (name.startsWith("COPPER") && isGear(name)) config.set("items." + name, "COPPER_GEAR");
                    else if (name.startsWith("LEATHER") && isGear(name)) config.set("items." + name, "LEATHER_GEAR");
                    else config.set("items." + name, 10);
                }
            }
            try { config.save(configFile); } catch (IOException e) { e.printStackTrace(); }
        } else {
            config = YamlConfiguration.loadConfiguration(configFile);
        }

        Map<String, Integer> tags = new HashMap<>();
        if (config.getConfigurationSection("tags") != null) {
            for (String tagKey : config.getConfigurationSection("tags").getKeys(false)) {
                tags.put(tagKey.toUpperCase(), config.getInt("tags." + tagKey, 0));
            }
        }

        if (config.getConfigurationSection("items") != null) {
            for (String key : config.getConfigurationSection("items").getKeys(false)) {
                try {
                    Material mat = Material.valueOf(key);
                    if (!isSurvivalAccessible(mat)) continue;
                    Object valueObject = config.get("items." + key);
                    int finalValue = 0;
                    if (valueObject instanceof Number) finalValue = ((Number) valueObject).intValue();
                    else if (valueObject instanceof String) finalValue = tags.getOrDefault(((String) valueObject).toUpperCase(), 0);
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



    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!timerActive) return;
        applyTooltip(event.getCurrentItem());
        applyTooltip(event.getCursor());
        updateAllScores();
    }

    @EventHandler
    public void onInventoryCreative(InventoryCreativeEvent event) {
        if (!timerActive) return;
        applyTooltip(event.getCurrentItem());
        applyTooltip(event.getCursor());
        updateAllScores();
    }

    @EventHandler
    public void onPlayerPickup(EntityPickupItemEvent event) {
        if (!timerActive) return;
        if (event.getEntity() instanceof Player) updateAllScores();
    }
}
