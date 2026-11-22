package rw.lag;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class RWLag extends JavaPlugin implements TabExecutor {

    static final MiniMessage MM = MiniMessage.miniMessage();

    Sweeper sweeper;
    Guards guards;
    boolean lagMode;

    // ---- cached config ----
    String prefix = "";
    final int[] limits = new int[Group.values().length];
    double hardCap, lagMult;
    boolean aiEnabled, redstoneEnabled, lagEnabled, lagCancelNatural, lagEmergencyClear;
    boolean clearEnabled, clearItems, clearXp, clearArrows, clearHostile, clearAnimals;
    boolean memEmergency, memGc, mergeEnabled, hopperEnabled;
    int mergeMin, hopperMax;
    double awareDist, enableTps, disableTps, enableMspt, disableMspt, memPercent;
    int redstoneMax, redstoneBlockSec, clearInterval, minItemAge, lagView, lagSim, sweepInterval;
    long budgetNs;
    final Set<EntityType> aiExclude = new HashSet<>();
    final Set<Material> itemWhitelist = new HashSet<>();
    final Set<Integer> warnAt = new HashSet<>();
    // natural mob spawn control (player-made spawns are never blocked)
    boolean naturalSpawn = true;
    final Set<SpawnReason> blockedReasons = new HashSet<>();
    String msgWarn, msgDone;
    boolean warnTitle;
    String warnSound;

    // ---- state ----
    private final Map<UUID, int[]> origDist = new HashMap<>();
    private int clearRemaining, hot, cool;
    private long lastMemAlert;
    private long totalCleared;
    private final double[] hist = new double[60];
    private int histN;
    private final Set<UUID> monitors = new HashSet<>();
    Gui gui;
    boolean reportOnLag;
    final Map<String, Double> worldMult = new HashMap<>(), worldAware = new HashMap<>();
    final List<Zone> zones = new ArrayList<>();
    private File zonesFile;

    record Zone(String name, String world, int x1, int z1, int x2, int z2) {
        boolean has(World w, int cx, int cz) {
            return w.getName().equals(world) && cx >= x1 && cx <= x2 && cz >= z1 && cz <= z2;
        }
    }

    boolean inZone(World w, int cx, int cz) {
        for (int i = 0; i < zones.size(); i++) if (zones.get(i).has(w, cx, cz)) return true;
        return false;
    }

    double awareDist(World w) { return worldAware.getOrDefault(w.getName(), awareDist); }

    private void loadZones() {
        zones.clear();
        if (zonesFile == null) zonesFile = new File(getDataFolder(), "zones.yml");
        if (!zonesFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(zonesFile);
        for (String k : y.getKeys(false)) {
            ConfigurationSection z = y.getConfigurationSection(k);
            if (z == null) continue;
            zones.add(new Zone(k, z.getString("world", ""), z.getInt("x1"), z.getInt("z1"), z.getInt("x2"), z.getInt("z2")));
        }
    }

    private void saveZones() {
        YamlConfiguration y = new YamlConfiguration();
        for (Zone z : zones) {
            y.set(z.name() + ".world", z.world());
            y.set(z.name() + ".x1", z.x1()); y.set(z.name() + ".z1", z.z1());
            y.set(z.name() + ".x2", z.x2()); y.set(z.name() + ".z2", z.z2());
        }
        try { y.save(zonesFile); } catch (Exception ex) { getLogger().warning("Could not save zones.yml: " + ex.getMessage()); }
    }
    Discord discord;
    String discordUrl;
    int discordCooldown;
    boolean pingEnabled, pingActive;
    final TreeMap<Integer, Integer> pingTiers = new TreeMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        sweeper = new Sweeper(this);
        guards = new Guards(this);
        getServer().getPluginManager().registerEvents(guards, this);
        discord = new Discord(this);
        gui = new Gui(this);
        getServer().getPluginManager().registerEvents(gui, this);
        PluginCommand c = getCommand("rwlag");
        c.setExecutor(this);
        c.setTabCompleter(this);

        Bukkit.getScheduler().runTaskTimer(this, sweeper, 40L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, this::monitor, 100L, 100L);
        Bukkit.getScheduler().runTaskTimer(this, this::pingTick, 600L, 600L);
        Bukkit.getScheduler().runTaskTimer(this, () -> { guards.tick(); clearTick(); monitorTick(); }, 20L, 20L);
    }

    @Override
    public void onDisable() {
        if (sweeper != null) sweeper.wakeAll();
        if (lagMode) disableLag(false);
    }

    void loadSettings() {
        reloadConfig();
        FileConfiguration c = getConfig();
        prefix = c.getString("prefix", "");
        for (Group g : Group.values())
            limits[g.ordinal()] = g == Group.OTHER ? -1 : c.getInt("entity-limits." + g.name().toLowerCase(), -1);
        hardCap = c.getDouble("entity-limits.hard-cap-multiplier", 2.0);
        aiEnabled = c.getBoolean("ai-optimizer.enabled", true);
        awareDist = c.getDouble("ai-optimizer.aware-distance", 40);
        aiExclude.clear();
        for (String s : c.getStringList("ai-optimizer.exclude-types")) {
            try { aiExclude.add(EntityType.valueOf(s.toUpperCase())); } catch (Exception ignored) {}
        }
        clearEnabled = c.getBoolean("auto-clear.enabled", true);
        clearInterval = Math.max(10, c.getInt("auto-clear.interval-seconds", 300));
        clearItems = c.getBoolean("auto-clear.clear-items", true);
        clearXp = c.getBoolean("auto-clear.clear-xp-orbs", true);
        clearArrows = c.getBoolean("auto-clear.clear-arrows", true);
        clearHostile = c.getBoolean("auto-clear.clear-hostile", false);
        clearAnimals = c.getBoolean("auto-clear.clear-animals", false);
        minItemAge = c.getInt("auto-clear.min-item-age-ticks", 60);
        warnAt.clear();
        warnAt.addAll(c.getIntegerList("auto-clear.warn-at"));
        itemWhitelist.clear();
        for (String s : c.getStringList("auto-clear.item-whitelist")) {
            Material m = Material.matchMaterial(s);
            if (m != null) itemWhitelist.add(m);
        }
        clearRemaining = clearInterval;
        msgWarn = c.getString("auto-clear.message-warn", "<yellow>Ground items will be cleared in <white><time><yellow>!");
        msgDone = c.getString("auto-clear.message-done", "<green>Cleared <white><items><green> items, <white><orbs><green> orbs, <white><other><green> other entities.");
        warnTitle = c.getBoolean("auto-clear.warn-title", true);
        warnSound = c.getString("auto-clear.warn-sound", "minecraft:block.note_block.pling");
        naturalSpawn = c.getBoolean("mob-spawn.natural", true);
        blockedReasons.clear();
        for (String r : c.getStringList("mob-spawn.blocked-reasons")) {
            try { blockedReasons.add(SpawnReason.valueOf(r.toUpperCase())); } catch (Exception ignored) {}
        }
        if (blockedReasons.isEmpty()) {
            blockedReasons.addAll(List.of(SpawnReason.NATURAL, SpawnReason.CHUNK_GEN, SpawnReason.PATROL,
                    SpawnReason.VILLAGE_INVASION, SpawnReason.REINFORCEMENTS));
        }
        redstoneEnabled = c.getBoolean("redstone-limiter.enabled", true);
        redstoneMax = c.getInt("redstone-limiter.max-updates-per-chunk-per-second", 1500);
        redstoneBlockSec = c.getInt("redstone-limiter.block-seconds", 10);
        lagEnabled = c.getBoolean("lag-mode.enabled", true);
        enableMspt = c.getDouble("lag-mode.enable-mspt", 45);
        disableMspt = c.getDouble("lag-mode.disable-mspt", 35);
        enableTps = c.getDouble("lag-mode.enable-tps", 17);
        disableTps = c.getDouble("lag-mode.disable-tps", 19);
        lagCancelNatural = c.getBoolean("lag-mode.cancel-natural-spawns", true);
        lagView = c.getInt("lag-mode.view-distance", 6);
        lagSim = c.getInt("lag-mode.simulation-distance", 4);
        lagMult = c.getDouble("lag-mode.limit-multiplier", 0.5);
        lagEmergencyClear = c.getBoolean("lag-mode.emergency-clear", true);
        memPercent = c.getDouble("memory.alert-percent", 90);
        memEmergency = c.getBoolean("memory.emergency-clear", true);
        memGc = c.getBoolean("memory.run-gc", false);
        worldMult.clear();
        worldAware.clear();
        ConfigurationSection ws = c.getConfigurationSection("worlds");
        if (ws != null) for (String k : ws.getKeys(false)) {
            if (ws.isSet(k + ".limit-multiplier")) worldMult.put(k, ws.getDouble(k + ".limit-multiplier"));
            if (ws.isSet(k + ".aware-distance")) worldAware.put(k, ws.getDouble(k + ".aware-distance"));
        }
        reportOnLag = c.getBoolean("report-on-lag-mode", true);
        loadZones();
        discordUrl = c.getString("discord.webhook-url", "");
        discordCooldown = c.getInt("discord.cooldown-seconds", 30);
        pingEnabled = c.getBoolean("ping-view.enabled", true);
        pingTiers.clear();
        for (String t : c.getStringList("ping-view.tiers")) {
            String[] p = t.split(":");
            try { pingTiers.put(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim())); } catch (Exception ignored) {}
        }
        mergeEnabled = c.getBoolean("item-merge.enabled", true);
        mergeMin = c.getInt("item-merge.min-items-in-chunk", 6);
        hopperEnabled = c.getBoolean("hopper-limiter.enabled", true);
        hopperMax = c.getInt("hopper-limiter.max-per-chunk", 40);
        sweepInterval = Math.max(1, c.getInt("sweeper.interval-ticks", 60));
        budgetNs = (long) (c.getDouble("sweeper.max-ms-per-tick", 1.5) * 1_000_000L);
    }

    int limit(Group g, World w) {
        int l = limits[g.ordinal()];
        if (l < 0) return -1;
        double m = lagMode ? lagMult : 1.0;
        if (w != null) m *= worldMult.getOrDefault(w.getName(), 1.0);
        return Math.max(1, (int) Math.round(l * m));
    }

    boolean protectedItem(Item it) {
        ItemStack s = it.getItemStack();
        if (itemWhitelist.contains(s.getType())) return true;
        return s.hasItemMeta() && s.getItemMeta().hasDisplayName();
                    }
