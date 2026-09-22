package dev.tggamesyt.szakmai;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.*;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * Szakmai — Agario-style Paper plugin for Minecraft 26.2.
 *
 * Key 26.2 considerations applied:
 *  - Adventure 5 API: no deprecated ClickEvent/HoverEvent constructors used.
 *  - MagmaCube no longer extends Slime; they share AbstractCubeMob. We always
 *    spawn/check Slime.class explicitly to avoid any MagmaCube confusion.
 *  - World#setSpawnFlags / getAllowAnimals are deprecated; use DO_MOB_SPAWNING
 *    gamerule only.
 *  - Snake_case gamerule enum (from 26.1, still applies in 26.2):
 *    GameRule.DO_MOB_SPAWNING, DO_DAYLIGHT_CYCLE, DO_WEATHER_CYCLE, etc.
 *  - api-version: '26.2' in plugin.yml.
 *  - Java 25 target.
 *
 * Mechanics:
 *  - /doworld confirm (twice) → writes a flag file, shuts the server down.
 *    On next start the flag is found, the world folders are deleted, and a
 *    fresh superflat world is created with gamerules applied.
 *  - Players are locked to a fixed Y (adventure flying, invisible).
 *    They cannot place/break blocks, take damage, or change Y.
 *  - Each player controls a NOAI Slime on the ground below them.
 *  - Slimes grow by absorbing fully-contained smaller slimes.
 *  - Food slimes (static NOAI) and bot slimes (chase/flee AI we drive) fill
 *    the arena. Bots chase players smaller than themselves; flee from bigger ones.
 *  - Movement speed scales gently with size for both players and bots.
 *  - A sidebar scoreboard shows rank · name · size.
 */
public final class Szakmai extends JavaPlugin implements Listener {

    // ── Tuning constants ──────────────────────────────────────────────────────

    /** Y of the top of the superflat ground layer (grass block). */
    private static final int GROUND_Y = 4;

    /**
     * Base height (blocks above ground) for a size-1 blob.
     * As the blob grows the camera rises so the slime stays fully in frame.
     * Formula: playerY = GROUND_Y + CAMERA_BASE_HEIGHT + blob.getSize() * CAMERA_HEIGHT_PER_SIZE
     *
     * A size-2 start blob → Y = 4 + 6 + 2*1.2 = ~12.4
     * A size-20 giant      → Y = 4 + 6 + 20*1.2 = 34  (still looks down at the slime)
     */
    private static final double CAMERA_BASE_HEIGHT    = 6.0;
    private static final double CAMERA_HEIGHT_PER_SIZE = 1.2;

    // Slime sizes (Bukkit int: 1=tiny, 2=small, 4=big, etc.)
    private static final int PLAYER_START_SIZE  = 2;
    private static final int BOT_START_SIZE     = 2;
    private static final int FOOD_SIZE_MIN      = 1;
    private static final int FOOD_SIZE_MAX      = 2;

    private static final int FOOD_TARGET_COUNT  = 60;
    private static final int BOT_TARGET_COUNT   = 8;

    /**
     * Half-width of the playable arena in blocks.
     * The world border diameter is set to 2 * ARENA_HALF_SIZE + 40 (20-block
     * warning buffer on each side), so players hit the border warning before
     * they reach the hard edge.  Spawns are kept within ARENA_HALF_SIZE so
     * food/bots never start outside the border.
     */
    private static final int ARENA_HALF_SIZE    = 80;
    /** World-border radius (centre 0,0). Border = 2*(ARENA_HALF_SIZE + BORDER_BUFFER). */
    private static final int BORDER_BUFFER      = 20;

    // Speed: player walk/fly speed (Bukkit, max 1.0) scaled by blob size.
    private static final float PLAYER_BASE_SPEED   = 0.06f;
    private static final float PLAYER_SPEED_PER_SZ = 0.003f;
    private static final float PLAYER_MAX_SPEED    = 0.4f;

    // Bot movement distance per tick.
    private static final double BOT_MOVE_PER_TICK     = 0.23;
    private static final double BOT_MOVE_PER_SIZE     = 0.006;
    private static final double BOT_SIGHT_RANGE       = 35.0;

    /** Ticks between main game loop runs. */
    private static final long LOOP_INTERVAL_TICKS   = 2L;
    /** Ticks between replenishment checks. */
    private static final long REPLEN_INTERVAL_TICKS = 40L;

    private static final String FLAG_FILENAME = "RESET_WORLD_ON_START.flag";

    // ── State ─────────────────────────────────────────────────────────────────

    /** player UUID → their controlled Slime blob */
    private final Map<UUID, Slime> playerBlobs = new HashMap<>();

    /** UUIDs of spawned food Slimes */
    private final Set<UUID> foodSlimeIds = new HashSet<>();

    /** UUID → bot Slime (NOAI but moved by us) */
    private final Map<UUID, Slime> botBlobs = new HashMap<>();

    /** Non-null when a /doworld confirm is awaiting second confirmation. */
    private String pendingDoWorldSender = null;

    private Scoreboard scoreboard;
    private Objective sidebarObj;

    private World arena;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        // ── Reset flag check (left by /doworld confirm) ───────────────────────
        File flag = new File(getDataFolder(), FLAG_FILENAME);
        if (flag.exists()) {
            flag.delete();
            getLogger().info("[Szakmai] Reset flag found – regenerating superflat world.");
            rebuildWorld();
        }

        // ── Grab game world ───────────────────────────────────────────────────
        arena = Bukkit.getWorld("world");
        if (arena == null) {
            getLogger().severe("[Szakmai] Could not find world 'world'. Disabling.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        applyGamerules(arena);

        // ── Events & scoreboard ───────────────────────────────────────────────
        Bukkit.getPluginManager().registerEvents(this, this);
        setupScoreboard();

        // ── Main loop ─────────────────────────────────────────────────────────
        new BukkitRunnable() {
            @Override public void run() { tickGame(); }
        }.runTaskTimer(this, 20L, LOOP_INTERVAL_TICKS);

        // ── Initial population — run 1 tick after enable so the world is loaded
        // and the map is already full when the first player joins.
        new BukkitRunnable() {
            @Override public void run() { replenishFood(); replenishBots(); }
        }.runTaskLater(this, 1L);

        // ── Replenishment loop — keeps counts topped up as entities are eaten ─
        new BukkitRunnable() {
            @Override public void run() { replenishFood(); replenishBots(); }
        }.runTaskTimer(this, REPLEN_INTERVAL_TICKS, REPLEN_INTERVAL_TICKS);

        getLogger().info("[Szakmai] Ready – agario on Minecraft 26.2!");
    }

    @Override
    public void onDisable() {
        playerBlobs.values().forEach(s -> { if (!s.isDead()) s.remove(); });
        botBlobs.values().forEach(s -> { if (!s.isDead()) s.remove(); });
        foodSlimeIds.forEach(id -> {
            Entity e = Bukkit.getEntity(id);
            if (e != null && !e.isDead()) e.remove();
        });
        playerBlobs.clear();
        botBlobs.clear();
        foodSlimeIds.clear();
    }

    // ── /doworld command ──────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("doworld")) return false;

        if (args.length == 1 && args[0].equalsIgnoreCase("confirm")) {
            if (!sender.hasPermission("szakmai.doworld")) {
                sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
                return true;
            }

            if (pendingDoWorldSender == null) {
                pendingDoWorldSender = sender.getName();
                sender.sendMessage(Component.text(
                    "⚠  This will STOP the server and DELETE the world on next start. " +
                    "Run /doworld confirm again within 30 s to proceed.",
                    NamedTextColor.GOLD).decorate(TextDecoration.BOLD));

                // Auto-expire after 30 s.
                new BukkitRunnable() {
                    @Override public void run() {
                        if (pendingDoWorldSender != null) {
                            pendingDoWorldSender = null;
                            sender.sendMessage(Component.text("Confirmation expired.", NamedTextColor.GRAY));
                        }
                    }
                }.runTaskLater(this, 600L);
                return true;
            }

            // Second confirm – write flag and halt.
            pendingDoWorldSender = null;
            Bukkit.broadcast(Component.text(
                "[Szakmai] Server shutting down – world will reset on next start!",
                NamedTextColor.RED));

            getDataFolder().mkdirs();
            try (FileWriter fw = new FileWriter(new File(getDataFolder(), FLAG_FILENAME))) {
                fw.write("reset");
            } catch (IOException ex) {
                getLogger().severe("[Szakmai] Could not write reset flag: " + ex.getMessage());
            }

            new BukkitRunnable() {
                @Override public void run() { Bukkit.shutdown(); }
            }.runTaskLater(this, 60L);
            return true;
        }

        sender.sendMessage(Component.text("Usage: /doworld confirm", NamedTextColor.YELLOW));
        return true;
    }

    // ── World rebuild ─────────────────────────────────────────────────────────

    /**
     * Deletes the old 'world' (and its nether/end) and generates a fresh
     * superflat world with the correct gamerules applied.
     */
    private void rebuildWorld() {
        World old = Bukkit.getWorld("world");
        if (old != null) {
            old.getPlayers().forEach(p -> p.kickPlayer("World is resetting. Reconnect shortly."));
            Bukkit.unloadWorld(old, false);
        }

        deleteDir(new File(Bukkit.getWorldContainer(), "world"));
        deleteDir(new File(Bukkit.getWorldContainer(), "world_nether"));
        deleteDir(new File(Bukkit.getWorldContainer(), "world_the_end"));

        // Flat preset: bedrock + 2 dirt + 1 grass, no structures/features.
        WorldCreator wc = new WorldCreator("world");
        wc.type(WorldType.FLAT);
        wc.generateStructures(false);
        wc.generatorSettings(
            "{\"biome\":\"minecraft:plains\",\"lakes\":false,\"features\":false," +
            "\"layers\":[{\"block\":\"minecraft:bedrock\",\"height\":1}," +
            "{\"block\":\"minecraft:dirt\",\"height\":2}," +
            "{\"block\":\"minecraft:grass_block\",\"height\":1}]," +
            "\"structures\":{\"structures\":{}}}"
        );

        arena = wc.createWorld();
        if (arena == null) {
            getLogger().severe("[Szakmai] World creation failed!");
            return;
        }
        applyGamerules(arena);
        getLogger().info("[Szakmai] Fresh superflat world created.");
    }

    /**
     * Applies all desired gamerules.
     * 26.1+ uses snake_case GameRule enum values; the Java enum names remain
     * the same (DO_MOB_SPAWNING etc.) – only the in-game string changed.
     */
    private void applyGamerules(World w) {
        w.setGameRule(GameRule.DO_MOB_SPAWNING,      false);  // no natural mob spawns
        w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE,    false);  // freeze time
        w.setGameRule(GameRule.DO_WEATHER_CYCLE,     false);  // no weather
        w.setGameRule(GameRule.DO_FIRE_TICK,         false);
        w.setGameRule(GameRule.MOB_GRIEFING,         false);
        w.setGameRule(GameRule.DO_MOB_LOOT,          false);
        w.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
        w.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);
        w.setGameRule(GameRule.KEEP_INVENTORY,       true);
        w.setGameRule(GameRule.SHOW_DEATH_MESSAGES,  false);
        w.setGameRule(GameRule.FALL_DAMAGE,          false);
        w.setTime(6000L); // noon

        // ── World border ──────────────────────────────────────────────────────
        WorldBorder border = w.getWorldBorder();
        border.setCenter(0, 0);
        // diameter = 2 * (arena + buffer) on each axis
        border.setSize((ARENA_HALF_SIZE + BORDER_BUFFER) * 2.0);
        border.setDamageBuffer(5.0);   // 5 blocks grace before damage
        border.setDamageAmount(0.5);   // 0.5 HP per second outside buffer
        border.setWarningDistance(BORDER_BUFFER); // yellow fog starts at buffer edge
        border.setWarningTime(0);      // no time-based warning, only distance
    }

    // ── Player join / quit ────────────────────────────────────────────────────

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // Short delay so the player fully loads before we manipulate them.
        new BukkitRunnable() {
            @Override public void run() { initPlayer(e.getPlayer()); }
        }.runTaskLater(this, 5L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Slime blob = playerBlobs.remove(e.getPlayer().getUniqueId());
        if (blob != null && !blob.isDead()) blob.remove();
        updateScoreboard();
    }

    // ── Player initialisation ─────────────────────────────────────────────────

    private void initPlayer(Player player) {
        if (arena == null) return;

        // Pick a spawn point that isn't too close to any existing blob so
        // a fresh (small) player doesn't immediately land inside a giant.
        double[] spawn = safeSpawnXZ(PLAYER_START_SIZE);
        double x = spawn[0], z = spawn[1];

        // Teleport player to their starting camera Y, looking straight down.
        double startY = cameraY(PLAYER_START_SIZE);
        Location playerLoc = new Location(arena, x, startY, z, 0f, 90f);
        player.teleport(playerLoc);

        // Adventure mode blocks building/breaking without restricting movement.
        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setInvulnerable(true);
        player.setCollidable(false);
        player.setSaturation(20f);

        // Permanent invisibility (no particles, no ambient, no beacon override).
        player.addPotionEffect(new PotionEffect(
            PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, false, false, false));

        // Spawn the player's NOAI blob slime on the ground.
        Location blobLoc = new Location(arena, x, GROUND_Y, z, player.getYaw(), 0f);
        Slime blob = spawnNoAiSlime(blobLoc, PLAYER_START_SIZE);
        blob.setCustomName(player.getName());
        blob.setCustomNameVisible(true);

        playerBlobs.put(player.getUniqueId(), blob);
        applySpeed(player, blob.getSize());
        updateScoreboard();
    }

    // ── Slime spawning helpers ────────────────────────────────────────────────

    /**
     * Spawns a NOAI Slime (not MagmaCube – those are a separate type in 26.2).
     * setAI(false) on a Slime disables movement/pathfinding; we move it manually.
     */
    private Slime spawnNoAiSlime(Location loc, int size) {
        return arena.spawn(loc, Slime.class, s -> {
            s.setSize(Math.max(1, size));
            s.setAI(false);
            s.setSilent(true);
            s.setRemoveWhenFarAway(false);
            s.setInvulnerable(false);
            s.setPersistent(true);
            s.setGravity(false); // keep on ground without physics jitter
        });
    }

    // ── Food slimes ───────────────────────────────────────────────────────────

    private void replenishFood() {
        foodSlimeIds.removeIf(id -> {
            Entity e = Bukkit.getEntity(id);
            return e == null || e.isDead();
        });

        int need = FOOD_TARGET_COUNT - foodSlimeIds.size();
        for (int i = 0; i < need; i++) {
            int size = FOOD_SIZE_MIN + (int) (Math.random() * (FOOD_SIZE_MAX - FOOD_SIZE_MIN + 1));
            Location loc = groundLoc(randCoord(), randCoord());
            Slime food = spawnNoAiSlime(loc, size);
            food.setCustomNameVisible(false);
            foodSlimeIds.add(food.getUniqueId());
        }
    }

    // ── Bot slimes ────────────────────────────────────────────────────────────

    private void replenishBots() {
        botBlobs.entrySet().removeIf(e -> e.getValue().isDead());

        int need = BOT_TARGET_COUNT - botBlobs.size();
        for (int i = 0; i < need; i++) {
            Location loc = groundLoc(randCoord(), randCoord());
            Slime bot = spawnNoAiSlime(loc, BOT_START_SIZE);
            bot.setCustomNameVisible(false);
            botBlobs.put(bot.getUniqueId(), bot);
        }
    }

    // ── Game tick ─────────────────────────────────────────────────────────────

    private void tickGame() {
        if (arena == null) return;

        // 1. Follow player position (XZ only); lock their Y.
        for (Map.Entry<UUID, Slime> entry : new HashMap<>(playerBlobs).entrySet()) {
            Player p = Bukkit.getPlayer(entry.getKey());
            Slime blob = entry.getValue();
            if (p == null || !p.isOnline() || blob.isDead()) continue;

            // Keep player at the camera Y matching their current blob size.
            // We teleport them whenever they drift more than 0.3 blocks from target.
            Location pLoc = p.getLocation();
            double targetY = cameraY(blob.getSize());
            if (Math.abs(pLoc.getY() - targetY) > 0.3) {
                pLoc.setY(targetY);
                p.teleport(pLoc);
            }

            // Mirror XZ to blob on the ground.
            blob.teleport(groundLoc(pLoc.getX(), pLoc.getZ()));
        }

        // 2. Bot AI.
        tickBots();

        // 3. Eat checks for player blobs.
        tickEating();

        // 4. Scoreboard refresh.
        updateScoreboard();
    }

    // ── Bot AI ────────────────────────────────────────────────────────────────

    private void tickBots() {
        for (Map.Entry<UUID, Slime> botEntry : new HashMap<>(botBlobs).entrySet()) {
            Slime bot = botEntry.getValue();
            if (bot.isDead()) continue;

            Location botLoc = bot.getLocation();
            int botSize = bot.getSize();

            // Find nearest player blob within sight range.
            Slime target = null;
            double nearestDist = Double.MAX_VALUE;

            for (Map.Entry<UUID, Slime> pEntry : playerBlobs.entrySet()) {
                Slime pBlob = pEntry.getValue();
                if (pBlob.isDead()) continue;
                double d = flatDist(botLoc, pBlob.getLocation());
                if (d < BOT_SIGHT_RANGE && d < nearestDist) {
                    nearestDist = d;
                    target = pBlob;
                }
            }

            if (target != null) {
                Location targetLoc = target.getLocation();
                double dx = targetLoc.getX() - botLoc.getX();
                double dz = targetLoc.getZ() - botLoc.getZ();
                double len = Math.sqrt(dx * dx + dz * dz);
                if (len > 0.01) {
                    double speed = BOT_MOVE_PER_TICK + botSize * BOT_MOVE_PER_SIZE;
                    double nx = dx / len, nz = dz / len;
                    // Chase if bigger, flee if smaller.
                    double dir = (botSize > target.getSize()) ? 1.0 : -1.0;
                    Location newLoc = groundLoc(
                        botLoc.getX() + nx * speed * dir,
                        botLoc.getZ() + nz * speed * dir
                    );
                    bot.teleport(newLoc);
                    botLoc = newLoc; // updated for eat check below
                }

                // Bot eats a smaller player blob it's fully covering.
                if (botSize > target.getSize()
                        && flatDist(botLoc, target.getLocation()) < radius(bot) - radius(target)) {
                    UUID eaten = null;
                    for (Map.Entry<UUID, Slime> pe : playerBlobs.entrySet()) {
                        if (pe.getValue() == target) { eaten = pe.getKey(); break; }
                    }
                    absorb(target, bot);
                    if (eaten != null) {
                        UUID finalEaten = eaten;
                        playerBlobs.remove(eaten);
                        Player ep = Bukkit.getPlayer(finalEaten);
                        if (ep != null) {
                            ep.sendMessage(Component.text("You were eaten by a bot! Respawning…", NamedTextColor.RED));
                            new BukkitRunnable() {
                                @Override public void run() { if (ep.isOnline()) initPlayer(ep); }
                            }.runTaskLater(this, 80L);
                        }
                    }
                }
            }

            // Bots also eat food slimes.
            eatFood(bot);
        }
    }

    // ── Player eating logic ───────────────────────────────────────────────────

    private void tickEating() {
        for (Map.Entry<UUID, Slime> entry : new HashMap<>(playerBlobs).entrySet()) {
            UUID pid = entry.getKey();
            Slime blob = entry.getValue();
            if (blob.isDead()) continue;

            // Eat food.
            eatFood(blob);

            // Eat bot slimes.
            for (Map.Entry<UUID, Slime> botEntry : new HashMap<>(botBlobs).entrySet()) {
                Slime bot = botEntry.getValue();
                if (bot.isDead()) continue;
                if (blob.getSize() > bot.getSize()
                        && flatDist(blob.getLocation(), bot.getLocation()) < radius(blob) - radius(bot)) {
                    absorb(bot, blob);
                    botBlobs.remove(botEntry.getKey());
                    applySpeed(Bukkit.getPlayer(pid), blob.getSize());
                }
            }

            // Eat other players.
            for (Map.Entry<UUID, Slime> other : new HashMap<>(playerBlobs).entrySet()) {
                if (other.getKey().equals(pid)) continue;
                Slime otherBlob = other.getValue();
                if (otherBlob.isDead()) continue;
                if (blob.getSize() > otherBlob.getSize()
                        && flatDist(blob.getLocation(), otherBlob.getLocation()) < radius(blob) - radius(otherBlob)) {
                    absorb(otherBlob, blob);
                    playerBlobs.remove(other.getKey());
                    applySpeed(Bukkit.getPlayer(pid), blob.getSize());

                    Player eatenPlayer = Bukkit.getPlayer(other.getKey());
                    if (eatenPlayer != null && eatenPlayer.isOnline()) {
                        eatenPlayer.sendMessage(Component.text("You were eaten! Respawning…", NamedTextColor.RED));
                        new BukkitRunnable() {
                            @Override public void run() { if (eatenPlayer.isOnline()) initPlayer(eatenPlayer); }
                        }.runTaskLater(this, 80L);
                    }
                }
            }
        }
    }

    /** Makes {@code eater} consume any food slime it fully covers. */
    private void eatFood(Slime eater) {
        Iterator<UUID> it = foodSlimeIds.iterator();
        while (it.hasNext()) {
            Entity fe = Bukkit.getEntity(it.next());
            if (!(fe instanceof Slime food) || food.isDead()) { it.remove(); continue; }
            if (eater.getSize() > food.getSize()
                    && flatDist(eater.getLocation(), food.getLocation()) < radius(eater) - radius(food)) {
                absorb(food, eater);
                it.remove();
                // Update speed if eater is a player blob.
                for (Map.Entry<UUID, Slime> pe : playerBlobs.entrySet()) {
                    if (pe.getValue() == eater) {
                        applySpeed(Bukkit.getPlayer(pe.getKey()), eater.getSize());
                        break;
                    }
                }
            }
        }
    }

    /**
     * Removes {@code prey}, grows {@code eater} by prey's size.
     */
    private void absorb(Slime prey, Slime eater) {
        eater.setSize(eater.getSize() + Math.max(1, prey.getSize()));
        prey.remove();
    }

    // ── Speed ─────────────────────────────────────────────────────────────────

    /**
     * Adjusts a player's walk/fly speed based on their current blob size,
     * and smoothly raises their camera Y so the larger slime stays on screen.
     * Bigger = slightly faster and slightly higher.
     */
    private void applySpeed(Player player, int blobSize) {
        if (player == null) return;
        float speed = Math.min(PLAYER_BASE_SPEED + blobSize * PLAYER_SPEED_PER_SZ, PLAYER_MAX_SPEED);
        player.setWalkSpeed(speed);
        player.setFlySpeed(speed);

        // Raise camera to match new size.
        Location loc = player.getLocation();
        double newY = cameraY(blobSize);
        if (Math.abs(loc.getY() - newY) > 0.5) {
            loc.setY(newY);
            player.teleport(loc);
        }
    }

    // ── Scoreboard ────────────────────────────────────────────────────────────

    private void setupScoreboard() {
        scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        sidebarObj = scoreboard.registerNewObjective(
            "agario",
            Criteria.DUMMY,
            Component.text("▶ AgarIO ◀", NamedTextColor.GOLD).decorate(TextDecoration.BOLD)
        );
        sidebarObj.setDisplaySlot(DisplaySlot.SIDEBAR);
    }

    private void updateScoreboard() {
        if (scoreboard == null || sidebarObj == null) return;

        // Collect and sort players by blob size, descending.
        List<Map.Entry<String, Integer>> ranked = new ArrayList<>();
        for (Map.Entry<UUID, Slime> e : playerBlobs.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || e.getValue().isDead()) continue;
            ranked.add(Map.entry(p.getName(), e.getValue().getSize()));
        }
        ranked.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        // Clear old entries.
        for (String entry : new HashSet<>(scoreboard.getEntries())) {
            scoreboard.resetScores(entry);
        }

        int total = ranked.size();
        for (int i = 0; i < ranked.size(); i++) {
            String name = ranked.get(i).getKey();
            int size    = ranked.get(i).getValue();
            int rank    = i + 1;
            // The entry string is what appears on screen.
            // §-codes still work in 26.2 scoreboard team prefixes / entries.
            String line = "§e" + rank + ". §f" + name + " §8[§a" + size + "§8]";
            sidebarObj.getScore(line).setScore(total - i);
        }

        // Push to all players.
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.setScoreboard(scoreboard);
        }
    }

    // ── Block / interaction prevention ────────────────────────────────────────

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (playerBlobs.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (playerBlobs.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (playerBlobs.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (playerBlobs.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && playerBlobs.containsKey(p.getUniqueId()))
            e.setCancelled(true);
    }

    @EventHandler
    public void onHunger(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && playerBlobs.containsKey(p.getUniqueId()))
            e.setCancelled(true);
    }

    /**
     * Prevent the player from toggling flight off (they must stay in the air).
     */
    @EventHandler
    public void onFlightToggle(PlayerToggleFlightEvent e) {
        if (playerBlobs.containsKey(e.getPlayer().getUniqueId()) && !e.isFlying())
            e.setCancelled(true);
    }

    /**
     * Y-axis lock: if the player moves away from their fixed Y, snap them back.
     * This replicates the spectator freedom restriction (no Y change unless we set it).
     */
    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        UUID uid = e.getPlayer().getUniqueId();
        if (!playerBlobs.containsKey(uid)) return;
        Location to = e.getTo();
        if (to == null) return;
        Slime blob = playerBlobs.get(uid);
        double targetY = (blob != null && !blob.isDead()) ? cameraY(blob.getSize()) : cameraY(PLAYER_START_SIZE);
        if (Math.abs(to.getY() - targetY) > 0.3) {
            Location fixed = to.clone();
            fixed.setY(targetY);
            e.setTo(fixed);
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    /**
     * Returns the Y coordinate the player camera should sit at for a given blob size.
     * The camera rises linearly with size so the slime always fits on screen when
     * the player looks straight down (pitch 90°).
     *
     * At size 2  (start): GROUND_Y + 6 + 2*1.2  ≈ Y 12.4
     * At size 10 (medium): GROUND_Y + 6 + 10*1.2 = Y 22
     * At size 25 (giant):  GROUND_Y + 6 + 25*1.2 = Y 40
     */
    private double cameraY(int blobSize) {
        return GROUND_Y + CAMERA_BASE_HEIGHT + blobSize * CAMERA_HEIGHT_PER_SIZE;
    }

    /** Vanilla slime radius approximation: 0.51 + 0.255 * size. */
    private double radius(Slime s) {
        return 0.51 + 0.255 * s.getSize();
    }

    /** XZ-only distance (Y is irrelevant in the 2-D arena). */
    private double flatDist(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Builds a Location on the arena ground. */
    private Location groundLoc(double x, double z) {
        return new Location(arena, x, GROUND_Y, z);
    }

    /** Random coordinate within the arena half-size. */
    private double randCoord() {
        return (Math.random() * 2.0 - 1.0) * ARENA_HALF_SIZE;
    }

    /**
     * Tries up to 20 times to find an XZ position that keeps the spawning
     * entity (of the given size) at least 15 blocks away from every existing
     * player blob and bot blob on the ground.  Falls back to a plain random
     * position if no safe spot is found within the attempt limit.
     *
     * @param spawnSize the Slime size of the entity about to be spawned
     * @return double[]{x, z}
     */
    private double[] safeSpawnXZ(int spawnSize) {
        // Collect all current threat positions (player blobs + bots).
        List<Location> threats = new ArrayList<>();
        for (Slime s : playerBlobs.values()) { if (!s.isDead()) threats.add(s.getLocation()); }
        for (Slime s : botBlobs.values())    { if (!s.isDead()) threats.add(s.getLocation()); }

        // Minimum safe distance: enough that a max-size blob can't immediately eat us.
        // We use a generous flat 15 blocks regardless of threat size to keep it simple.
        double minDist = 15.0;

        for (int attempt = 0; attempt < 20; attempt++) {
            double x = randCoord();
            double z = randCoord();
            boolean safe = true;
            for (Location t : threats) {
                double dx = t.getX() - x, dz = t.getZ() - z;
                if (Math.sqrt(dx * dx + dz * dz) < minDist) { safe = false; break; }
            }
            if (safe) return new double[]{x, z};
        }
        // Fallback: just pick a random spot.
        return new double[]{randCoord(), randCoord()};
    }

    /** Recursively deletes a directory. */
    private void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) { if (f.isDirectory()) deleteDir(f); else f.delete(); }
        dir.delete();
    }
}
