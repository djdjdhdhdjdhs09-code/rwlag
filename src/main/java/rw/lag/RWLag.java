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
    AutoFix autoFix;
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
                Network net = new Network(this);
        Bukkit.getScheduler().runTaskTimer(this, net::tick, 600L, 100L);
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
                    }    // ---------- messaging ----------
    void send(CommandSender s, String mini) { s.sendMessage(MM.deserialize(prefix + mini)); }

    void notifyAdmins(String mini) {
        Component c = MM.deserialize(prefix + mini);
        for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("rwlag.notify")) p.sendMessage(c);
        Bukkit.getConsoleSender().sendMessage(c);
    }

    void broadcast(String mini) { Bukkit.broadcast(MM.deserialize(prefix + mini)); }

    // ---------- auto clear ----------
    private void clearTick() {
        if (!clearEnabled) return;
        clearRemaining--;
        if (warnAt.contains(clearRemaining)) {
            broadcast(msgWarn.replace("<time>", fmtTime(clearRemaining)));
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (warnSound != null && !warnSound.isEmpty()) p.playSound(p.getLocation(), warnSound, 0.6f, 1.2f);
                 if (false && clearRemaining <= 10)
                    p.showTitle(Title.title(MM.deserialize("<red><bold>" + clearRemaining),
                            MM.deserialize("<yellow>Items will be cleared!"),
                            Title.Times.times(java.time.Duration.ZERO, java.time.Duration.ofMillis(900), java.time.Duration.ZERO)));
            }
        }
        if (clearRemaining <= 0) {
            int[] r = doClear();
            broadcast(msgDone.replace("<items>", "" + r[0]).replace("<orbs>", "" + r[1]).replace("<other>", "" + r[2]));
            clearRemaining = clearInterval;
        }
    }

    /** 600 -> "10m", 90 -> "1m 30s", 5 -> "5s" */
    static String fmtTime(int sec) {
        if (sec < 60) return sec + "s";
        int m = sec / 60, r = sec % 60;
        return r == 0 ? m + "m" : m + "m " + r + "s";
    }

    /** accepts 600, 600s, 10m, 1h */
    static int parseTime(String t) {
        t = t.trim().toLowerCase();
        int mult = 1;
        if (t.endsWith("h")) { mult = 3600; t = t.substring(0, t.length() - 1); }
        else if (t.endsWith("m")) { mult = 60; t = t.substring(0, t.length() - 1); }
        else if (t.endsWith("s")) { t = t.substring(0, t.length() - 1); }
        return Integer.parseInt(t) * mult;
    }

    // ---------- click-to-teleport helpers ----------
    Component tpLine(String mini, World w, int cx, int cz) {
        return MM.deserialize(mini)
                .clickEvent(ClickEvent.runCommand("/rwlag tp " + w.getName() + " " + cx + " " + cz))
                .hoverEvent(HoverEvent.showText(MM.deserialize("<yellow>Click to teleport to the problem area")));
    }

    void notifyAdminsTp(String mini, World w, int cx, int cz) {
        Component c = Component.text().append(MM.deserialize(prefix)).append(tpLine(mini, w, cx, cz)).build();
        for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("rwlag.notify")) p.sendMessage(c);
        Bukkit.getConsoleSender().sendMessage(c);
    }

    private Location safeSpot(World w, int cx, int cz) {
        int x = cx * 16 + 8, z = cz * 16 + 8;
        if (w.getEnvironment() == World.Environment.NETHER) {
            for (int y = 120; y > 32; y--) {
                if (w.getBlockAt(x, y, z).getType().isSolid() && w.getBlockAt(x, y + 1, z).isPassable()
                        && w.getBlockAt(x, y + 2, z).isPassable())
                    return new Location(w, x + 0.5, y + 1, z + 0.5);
            }
            return new Location(w, x + 0.5, 100, z + 0.5);
        }
        return new Location(w, x + 0.5, w.getHighestBlockYAt(x, z) + 1, z + 0.5);
    }

    /** returns {items, xp, other} */
    int[] doClear() {
        int items = 0, xp = 0, other = 0;
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (!zones.isEmpty() && inZone(w, e.getLocation().getBlockX() >> 4, e.getLocation().getBlockZ() >> 4)) continue;
                if (e instanceof Item it) {
                    if (!clearItems || it.getTicksLived() < minItemAge || protectedItem(it)) continue;
                    it.remove(); items++;
                } else if (e instanceof ExperienceOrb) {
                    if (clearXp) { e.remove(); xp++; }
                } else if (e instanceof AbstractArrow && !(e instanceof Trident)) {
                    if (clearArrows) { e.remove(); other++; }
                } else {
                    Group g = Group.of(e);
                    if (((g == Group.HOSTILE && clearHostile) || (g == Group.ANIMAL && clearAnimals)) && !sweeper.isProtected(e)) {
                        e.remove(); other++;
                    }
                }
            }
        }
        totalCleared += items + xp + other;
        return new int[]{items, xp, other};
    }

    // ---------- monitor: lag mode + memory ----------
    private void monitor() {
        double mspt = Bukkit.getAverageTickTime();
        double tps = Bukkit.getTPS()[0];
        hist[histN++ % hist.length] = mspt;
        if (lagEnabled) {
            boolean bad = mspt >= enableMspt || tps < enableTps;
            boolean good = mspt < disableMspt && tps >= disableTps;
            if (!lagMode) {
                if (bad) { if (++hot >= 2) { enableLag(); hot = 0; } } else hot = 0;
            } else {
                if (good) { if (++cool >= 6) { disableLag(true); cool = 0; } } else cool = 0;
            }
        }
        Runtime rt = Runtime.getRuntime();
        double pct = (rt.totalMemory() - rt.freeMemory()) * 100.0 / rt.maxMemory();
        long now = System.currentTimeMillis();
        if (pct >= memPercent && now - lastMemAlert > 60_000) {
            lastMemAlert = now;
            notifyAdmins("<red>Memory at <white>" + (int) pct + "%<red>!" + (memEmergency ? " Running emergency clear." : ""));
            discord.send("Memory at " + (int) pct + "% of max heap.");
            if (memEmergency) doClear();
            if (memGc) System.gc();
        }
    }

    void enableLag() {
        if (lagMode) return;
        lagMode = true;
        for (World w : Bukkit.getWorlds()) {
            try {
                origDist.putIfAbsent(w.getUID(), new int[]{w.getViewDistance(), w.getSimulationDistance()});
                w.setViewDistance(Math.min(w.getViewDistance(), lagView));
                w.setSimulationDistance(Math.min(w.getSimulationDistance(), lagSim));
            } catch (Throwable ignored) {}
        }
        if (lagEmergencyClear) doClear();
        notifyAdmins("<red>Lag mode ON <gray>(spawns reduced, limits x" + lagMult + ", view distance lowered)");
        if (reportOnLag) writeReport(null, false);
        String top = culpritText(3);
        for (Sweeper.Culprit c : sweeper.culprits(3))
            if (c.w != null) notifyAdminsTp("<gray>Heavy area near <aqua>" + c.name + " <white>(score " + (int) c.score
                    + ") <yellow>[click = teleport]", c.w, c.cx, c.cz);
        discord.send("Lag mode ON | MSPT " + String.format("%.1f", Bukkit.getAverageTickTime()) + " | TPS "
                + String.format("%.1f", Math.min(20, Bukkit.getTPS()[0])) + (top.isEmpty() ? "" : " | Heaviest areas near: " + top));
    }

    void disableLag(boolean announce) {
        if (!lagMode) return;
        lagMode = false;
        for (World w : Bukkit.getWorlds()) {
            int[] o = origDist.remove(w.getUID());
            if (o == null) continue;
            try { w.setViewDistance(o[0]); w.setSimulationDistance(o[1]); } catch (Throwable ignored) {}
        }
        if (announce) { notifyAdmins("<green>Lag mode OFF <gray>(server is stable again)"); discord.send("Lag mode OFF - server stable again."); }
        }    // ---------- commands ----------
    private static String tpsColor(double v) { return v >= 19 ? "<green>" : v >= 15 ? "<yellow>" : "<red>"; }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("rwlag.admin")) { send(s, "<red>No permission."); return true; }
        String sub = a.length == 0 ? "status" : a[0].toLowerCase();
        switch (sub) {
            case "status", "tps" -> status(s);
            case "clear" -> {
                int[] r = doClear();
                send(s, "<green>Cleared <white>" + r[0] + "<green> items, <white>" + r[1] + "<green> orbs, <white>" + r[2] + "<green> other.");
            }
            case "reload" -> { loadSettings(); send(s, "<green>Config reloaded."); }
            case "lagmode" -> {
                if (a.length < 2) { send(s, "<yellow>/rwlag lagmode <on|off>"); return true; }
                if (a[1].equalsIgnoreCase("on")) enableLag(); else disableLag(true);
            }
            case "chunks" -> chunks(s);
            case "gui" -> { if (s instanceof Player p) gui.open(p); else send(s, "<red>Players only."); }
            case "graph" -> graph(s);
            case "culprits" -> culprits(s);
            case "report" -> writeReport(s, true);
            case "protect" -> protect(s, a);
            case "discord" -> {
                if (!discord.configured()) send(s, "<red>Set discord.webhook-url in config.yml first.");
                else { discord.send("RWLag test message.", true); send(s, "<green>Test message sent."); }
            }
            case "entities" -> entities(s);
            case "monitor" -> {
                if (!(s instanceof Player p)) return true;
                if (!monitors.remove(p.getUniqueId())) { monitors.add(p.getUniqueId()); send(s, "<green>Live monitor ON (action bar)."); }
                else send(s, "<yellow>Live monitor OFF.");
            }
            case "cleartime" -> {
                if (a.length < 2) { send(s, "<yellow>/rwlag cleartime <seconds|10m>  <gray>(now: " + fmtTime(clearInterval) + ")"); return true; }
                try {
                    int sec = Math.max(10, parseTime(a[1]));
                    getConfig().set("auto-clear.interval-seconds", sec);
                    saveConfig();
                    loadSettings();
                    send(s, "<green>Item clear every <white>" + fmtTime(sec) + "<green>. Players are warned at: <white>"
                            + new TreeSet<>(warnAt).descendingSet());
                } catch (NumberFormatException ex) { send(s, "<red>Use a number like 600, 90s, 10m or 1h."); }
            }
            case "mobspawn" -> {
                if (a.length < 2) {
                    send(s, "<yellow>/rwlag mobspawn <on|off>  <gray>(natural spawning is now " + (naturalSpawn ? "<green>ON" : "<red>OFF") + "<gray>)");
                    return true;
                }
                boolean on = a[1].equalsIgnoreCase("on");
                getConfig().set("mob-spawn.natural", on);
                saveConfig();
                loadSettings();
                send(s, on ? "<green>Natural mob spawning ON."
                        : "<red>Natural mob spawning OFF. <gray>Spawn eggs, spawners, breeding, commands still work.");
            }
            case "tp" -> {
                if (!(s instanceof Player p) || a.length < 4) return true;
                World w = Bukkit.getWorld(a[1]);
                if (w == null) return true;
                try {
                    p.teleport(safeSpot(w, Integer.parseInt(a[2]), Integer.parseInt(a[3])));
                } catch (NumberFormatException ignored) {}
            }
            default -> send(s, "<yellow>/rwlag <gui|status|chunks|culprits|entities|graph|monitor|report|protect|discord|clear|cleartime|mobspawn|lagmode|reload>");
        }
        return true;
    }

    private void status(CommandSender s) {
        double[] t = Bukkit.getTPS();
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) >> 20, max = rt.maxMemory() >> 20;
        send(s, "<gold>TPS <gray>(1m/5m/15m): " + tpsColor(t[0]) + String.format("%.1f", Math.min(20, t[0]))
                + "<gray>/" + tpsColor(t[1]) + String.format("%.1f", Math.min(20, t[1]))
                + "<gray>/" + tpsColor(t[2]) + String.format("%.1f", Math.min(20, t[2]))
                + " <gold>MSPT: <white>" + String.format("%.1f", Bukkit.getAverageTickTime()));
        send(s, "<gold>RAM: <white>" + used + "/" + max + " MB <gray>(" + (used * 100 / max) + "%) <gold>Players: <white>"
                + Bukkit.getOnlinePlayers().size() + "/" + Bukkit.getMaxPlayers());
        send(s, "<gold>Lag mode: " + (lagMode ? "<red>ON" : "<green>OFF")
                + " <gold>Next clear: <white>" + (clearEnabled ? fmtTime(clearRemaining) + " <gray>(every " + fmtTime(clearInterval) + ")" : "off"));
        for (World w : Bukkit.getWorlds())
            send(s, "<gray>" + w.getName() + ": <white>" + w.getChunkCount() + "<gray> chunks, <white>" + w.getEntityCount() + "<gray> entities");
        send(s, "<gray>Totals: removed by limiter <white>" + sweeper.removed + "<gray>, cleared <white>" + totalCleared
                + "<gray>, mobs with AI asleep <white>" + sweeper.sleeping + "<gray>, redstone blocked <white>" + guards.redstoneBlocked);
    }

    private void protect(CommandSender s, String[] a) {
        String act = a.length < 2 ? "list" : a[1].toLowerCase();
        switch (act) {
            case "add" -> {
                if (!(s instanceof Player p) || a.length < 3) { send(s, "<yellow>/rwlag protect add <name> [radius-in-chunks]"); return; }
                int r = 0;
                if (a.length > 3) try { r = Math.max(0, Math.min(20, Integer.parseInt(a[3]))); } catch (NumberFormatException ignored) {}
                int cx = p.getLocation().getBlockX() >> 4, cz = p.getLocation().getBlockZ() >> 4;
                zones.removeIf(z -> z.name().equalsIgnoreCase(a[2]));
                zones.add(new Zone(a[2], p.getWorld().getName(), cx - r, cz - r, cx + r, cz + r));
                saveZones();
                send(s, "<green>Zone <white>" + a[2] + "<green> protected: " + (2 * r + 1) + "x" + (2 * r + 1)
                        + " chunks around you. No clear, limits, AI sleep, item merge or redstone freeze there.");
            }
            case "remove" -> {
                if (a.length < 3) { send(s, "<yellow>/rwlag protect remove <name>"); return; }
                boolean ok = zones.removeIf(z -> z.name().equalsIgnoreCase(a[2]));
                if (ok) saveZones();
                send(s, ok ? "<green>Zone removed." : "<red>No such zone.");
            }
            default -> {
                if (zones.isEmpty()) { send(s, "<gray>No protected zones."); return; }
                for (Zone z : zones)
                    send(s, "<aqua>" + z.name() + " <gray>" + z.world() + " chunks " + z.x1() + "," + z.z1() + " to " + z.x2() + "," + z.z2());
            }
        }
    }

    private void writeReport(CommandSender s, boolean full) {
        Runtime rt = Runtime.getRuntime();
        double[] t = Bukkit.getTPS();
        StringBuilder sb = new StringBuilder();
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        sb.append("RWLag report ").append(ts).append("\n");
        sb.append("Server: ").append(Bukkit.getVersion()).append("\n");
        sb.append(String.format("TPS 1m/5m/15m: %.2f / %.2f / %.2f   MSPT: %.2f%n", t[0], t[1], t[2], Bukkit.getAverageTickTime()));
        sb.append("RAM: ").append((rt.totalMemory() - rt.freeMemory()) >> 20).append("/").append(rt.maxMemory() >> 20).append(" MB\n");
        sb.append("Players: ").append(Bukkit.getOnlinePlayers().size()).append("/").append(Bukkit.getMaxPlayers());
        sb.append("   Lag mode: ").append(lagMode).append("   Plugins: ").append(Bukkit.getPluginManager().getPlugins().length).append("\n\n");
        sb.append("== Worlds ==\n");
        for (World w : Bukkit.getWorlds())
            sb.append(w.getName()).append(": chunks ").append(w.getChunkCount()).append(", entities ").append(w.getEntityCount())
              .append(", players ").append(w.getPlayerCount()).append(", view ").append(w.getViewDistance())
              .append(", sim ").append(w.getSimulationDistance()).append("\n");
        sb.append("\n== Heaviest chunks ==\n");
        for (Object[] o : sweeper.top(10))
            sb.append(((World) o[0]).getName()).append(" chunk ").append(o[1]).append(",").append(o[2])
              .append(" (block ").append((int) o[1] * 16).append(" ").append((int) o[2] * 16).append(") entities=").append(o[3]).append("\n");
        sb.append("\n== Players near heavy areas ==\n");
        for (Sweeper.Culprit c : sweeper.culprits(10)) sb.append(c.name).append(" score ").append((int) c.score).append("\n");
        sb.append("\n== MSPT history (5s samples, oldest first) ==\n");
        int n = Math.min(histN, hist.length);
        for (int i = 0; i < n; i++) sb.append(String.format("%.1f ", hist[(histN - n + i) % hist.length]));
        sb.append("\n");
        if (full) {
            Map<EntityType, Integer> m = new EnumMap<>(EntityType.class);
            for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) m.merge(e.getType(), 1, Integer::sum);
            List<Map.Entry<EntityType, Integer>> l = new ArrayList<>(m.entrySet());
            l.sort((x, y) -> y.getValue() - x.getValue());
            sb.append("\n== Top entity types ==\n");
            for (int i = 0; i < Math.min(15, l.size()); i++) sb.append(l.get(i).getKey().name()).append(": ").append(l.get(i).getValue()).append("\n");
        }
        sb.append("\nTotals: limiter removed ").append(sweeper.removed).append(", cleared ").append(totalCleared)
          .append(", AI asleep ").append(sweeper.sleeping).append(", redstone blocked ").append(guards.redstoneBlocked).append("\n");
        String name = "report-" + ts + ".txt";
        File dir = new File(getDataFolder(), "reports");
        String text = sb.toString();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                dir.mkdirs();
                Files.writeString(new File(dir, name).toPath(), text);
            } catch (Exception ex) { getLogger().warning("Could not write report: " + ex.getMessage()); }
        });
        if (s != null) send(s, "<green>Report saved: <white>plugins/RWLag/reports/" + name);
    }

    String culpritText(int n) {
        StringBuilder sb = new StringBuilder();
        for (Sweeper.Culprit c : sweeper.culprits(n)) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(c.name).append(" (").append((int) c.score).append(")");
        }
        return sb.toString();
        }    private void culprits(CommandSender s) {
        List<Sweeper.Culprit> l = sweeper.culprits(5);
        if (l.isEmpty()) { send(s, "<gray>No heavy areas found (or sweep not finished yet)."); return; }
        send(s, "<gold>Players with the heaviest surroundings <gray>(entities near them, click = teleport):");
        for (Sweeper.Culprit c : l) {
            Component line = MM.deserialize("<aqua> " + c.name + " <white>score " + (int) c.score + " <gray>(worst chunk "
                    + c.w.getName() + " " + c.cx + "," + c.cz + ")")
                    .clickEvent(ClickEvent.runCommand("/rwlag tp " + c.w.getName() + " " + c.cx + " " + c.cz));
            s.sendMessage(line);
        }
    }

    private void pingTick() {
        if (!pingEnabled) {
            if (pingActive) {
                pingActive = false;
                for (Player p : Bukkit.getOnlinePlayers()) {
                    try { p.setViewDistance(p.getWorld().getViewDistance()); } catch (Throwable ignored) {}
                }
            }
            return;
        }
        pingActive = true;
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                int ping = p.getPing();
                int target = p.getWorld().getViewDistance();
                for (Map.Entry<Integer, Integer> en : pingTiers.entrySet())
                    if (ping >= en.getKey()) target = Math.min(target, en.getValue());
                target = Math.max(3, target);
                if (p.getViewDistance() != target) p.setViewDistance(target);
            } catch (Throwable ignored) {}
        }
    }

    private void monitorTick() {
        if (monitors.isEmpty()) return;
        double t = Math.min(20, Bukkit.getTPS()[0]);
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) >> 20, max = rt.maxMemory() >> 20;
        Component bar = MM.deserialize("<gold>TPS " + tpsColor(t) + String.format("%.1f", t) + " <gold>MSPT <white>"
                + String.format("%.1f", Bukkit.getAverageTickTime()) + " <gold>RAM <white>" + used + "/" + max + "MB "
                + (lagMode ? "<red>[LAG MODE]" : "<green>[OK]"));
        monitors.removeIf(id -> Bukkit.getPlayer(id) == null);
        for (UUID id : monitors) { Player p = Bukkit.getPlayer(id); if (p != null) p.sendActionBar(bar); }
    }

    private void graph(CommandSender s) {
        int n = Math.min(histN, hist.length);
        if (n < 2) { send(s, "<gray>Not enough data yet (samples every 5s)."); return; }
        String chars = "▁▂▃▄▅▆▇█";
        StringBuilder sb = new StringBuilder();
        double min = 1e9, max = 0, sum = 0;
        for (int i = 0; i < n; i++) {
            double v = hist[(histN - n + i) % hist.length];
            min = Math.min(min, v); max = Math.max(max, v); sum += v;
            int idx = (int) Math.min(7, v / 60.0 * 8);
            sb.append(v < 35 ? "<green>" : v < 50 ? "<yellow>" : "<red>").append(chars.charAt(idx));
        }
        send(s, "<gold>MSPT last " + (n * 5 / 60.0 >= 1 ? String.format("%.1f min", n * 5 / 60.0) : n * 5 + "s") + " <gray>(50 = lag limit)");
        s.sendMessage(MM.deserialize(sb.toString()));
        send(s, "<gray>min <white>" + String.format("%.1f", min) + " <gray>avg <white>" + String.format("%.1f", sum / n) + " <gray>max <white>" + String.format("%.1f", max));
    }

    private void entities(CommandSender s) {
        Map<EntityType, Integer> m = new EnumMap<>(EntityType.class);
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) m.merge(e.getType(), 1, Integer::sum);
        List<Map.Entry<EntityType, Integer>> l = new ArrayList<>(m.entrySet());
        l.sort((x, y) -> y.getValue() - x.getValue());
        send(s, "<gold>Top entity types on the server:");
        for (int i = 0; i < Math.min(8, l.size()); i++)
            send(s, "<gray> " + l.get(i).getKey().name() + ": <white>" + l.get(i).getValue());
    }

    private void chunks(CommandSender s) {
        List<Object[]> top = sweeper.top(5);
        if (top.isEmpty()) { send(s, "<gray>No data yet, wait a few seconds."); return; }
        send(s, "<gold>Heaviest chunks <gray>(click to teleport):");
        for (Object[] o : top) {
            World w = (World) o[0];
            int cx = (int) o[1], cz = (int) o[2];
            int[] c = (int[]) o[4];
            Component line = MM.deserialize("<aqua> " + w.getName() + " " + cx + "," + cz + " <white>" + o[3] + " <gray>(hostile "
                    + c[Group.HOSTILE.ordinal()] + ", animal " + c[Group.ANIMAL.ordinal()] + ", item " + c[Group.ITEM.ordinal()] + ")")
                    .clickEvent(ClickEvent.runCommand("/rwlag tp " + w.getName() + " " + cx + " " + cz));
            s.sendMessage(line);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 1) return List.of("gui", "status", "chunks", "culprits", "entities", "graph", "monitor", "report", "protect", "discord", "clear", "cleartime", "mobspawn", "lagmode", "reload");
        if (a.length == 2 && (a[0].equalsIgnoreCase("lagmode") || a[0].equalsIgnoreCase("mobspawn"))) return List.of("on", "off");
        if (a.length == 2 && a[0].equalsIgnoreCase("cleartime")) return List.of("60", "5m", "10m", "30m");
        if (a.length == 2 && a[0].equalsIgnoreCase("protect")) return List.of("add", "remove", "list");
        if (a.length == 3 && a[0].equalsIgnoreCase("protect") && a[1].equalsIgnoreCase("remove"))
            return zones.stream().map(Zone::name).toList();
        return List.of();
    }
                }
