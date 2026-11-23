package rw.lag;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Premium settings menu: categories, ON/OFF switches, +/- number steppers. */
final class Gui implements Listener {
    private final RWLag pl;
    private final Set<UUID> awaiting = new HashSet<>();

    private enum Page { MAIN, PERF, MOBS, ITEMS, NET, TOOLS, TP, TIME }

    /** One setting. toggle = ON/OFF switch, otherwise a number with min/max/step. */
    private record S(String name, String path, boolean toggle, double def, double min, double max,
                     double step, Material icon, String desc) {}

    private static S t(String name, String path, boolean def, Material icon, String desc) {
        return new S(name, path, true, def ? 1 : 0, 0, 1, 0, icon, desc);
    }

    private static S n(String name, String path, double def, double min, double max, double step,
                       Material icon, String desc) {
        return new S(name, path, false, def, min, max, step, icon, desc);
    }

    private static final Map<Page, List<S>> PAGES = new EnumMap<>(Page.class);
    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};

    static {
        PAGES.put(Page.PERF, List.of(
                t("AI Optimizer", "ai-optimizer.enabled", true, Material.REDSTONE_TORCH, "Sleep mobs far from players"),
                n("AI Wake Distance", "ai-optimizer.aware-distance", 40, 16, 128, 4, Material.SPYGLASS, "Mobs farther than this sleep (blocks)"),
                t("Redstone Limiter", "redstone-limiter.enabled", true, Material.REDSTONE, "Freeze redstone lag machines"),
                n("Redstone Max / sec", "redstone-limiter.max-updates-per-chunk-per-second", 1500, 200, 10000, 100, Material.COMPARATOR, "Updates per chunk per second"),
                t("Hopper Limiter", "hopper-limiter.enabled", true, Material.HOPPER, "Max hoppers per chunk"),
                n("Hoppers / Chunk", "hopper-limiter.max-per-chunk", 40, 5, 200, 5, Material.HOPPER, "Limit when placing hoppers"),
                t("Item Merge", "item-merge.enabled", true, Material.CHEST, "Merge nearby dropped items"),
                t("Auto Lag Mode", "lag-mode.enabled", true, Material.BEACON, "Emergency mode when TPS drops"),
                n("Lag Mode at MSPT", "lag-mode.enable-mspt", 45, 25, 50, 1, Material.CLOCK, "Turn on emergency mode above this"),
                n("Lag Mode View Dist", "lag-mode.view-distance", 6, 2, 12, 1, Material.ENDER_EYE, "View distance in emergency mode"),
                n("Sweep CPU Budget (ms)", "sweeper.max-ms-per-tick", 1.5, 0.5, 5, 0.5, Material.DAYLIGHT_DETECTOR, "CPU time per tick for RWLag")));
        PAGES.put(Page.MOBS, List.of(
                t("Natural Mob Spawn", "mob-spawn.natural", true, Material.ZOMBIE_SPAWN_EGG, "OFF = no auto spawns (eggs/spawners work)"),
                t("Block Spawns in Lag Mode", "lag-mode.cancel-natural-spawns", true, Material.IRON_BARS, "No natural spawns during lag mode"),
                n("Hostile / Chunk", "entity-limits.hostile", 25, 1, 200, 5, Material.ZOMBIE_HEAD, "Max hostile mobs per chunk"),
                n("Animals / Chunk", "entity-limits.animal", 30, 1, 200, 5, Material.COW_SPAWN_EGG, "Max animals per chunk"),
                n("Water Mobs / Chunk", "entity-limits.water", 15, 1, 200, 5, Material.COD_BUCKET, "Max water mobs per chunk"),
                n("Ambient / Chunk", "entity-limits.ambient", 5, 1, 100, 1, Material.BAT_SPAWN_EGG, "Max bats etc per chunk"),
                n("Villagers / Chunk", "entity-limits.villager", 40, 1, 200, 5, Material.EMERALD, "Max villagers per chunk"),
                n("Hard Cap x", "entity-limits.hard-cap-multiplier", 2, 0, 5, 0.5, Material.BARRIER, "Block new spawns above limit x this")));
        PAGES.put(Page.ITEMS, List.of(
                t("Auto Clear", "auto-clear.enabled", true, Material.LAVA_BUCKET, "Clear ground items on a timer"),
                t("Clear Items", "auto-clear.clear-items", true, Material.DIAMOND, "Include dropped items"),
                t("Clear XP Orbs", "auto-clear.clear-xp-orbs", true, Material.EXPERIENCE_BOTTLE, "Include XP orbs"),
                t("Clear Arrows", "auto-clear.clear-arrows", true, Material.ARROW, "Include stuck arrows"),
                t("Clear Hostile Mobs", "auto-clear.clear-hostile", false, Material.ROTTEN_FLESH, "Also remove hostile mobs"),
                t("Clear Animals", "auto-clear.clear-animals", false, Material.WHEAT, "Also remove animals"),
                t("Countdown Title", "auto-clear.warn-title", true, Material.OAK_SIGN, "Big number for last 10 seconds"),
                n("Items / Chunk", "entity-limits.item", 100, 10, 500, 10, Material.CHEST_MINECART, "Max dropped items per chunk")));
        PAGES.put(Page.NET, List.of(
                t("Ping View Distance", "ping-view.enabled", true, Material.COMPASS, "Lower view distance for high ping"),
                t("Crowd Mode", "crowd.enabled", true, Material.PLAYER_HEAD, "Reduce load where many players gather"),
                n("Crowd Min Players", "crowd.min-players", 12, 4, 100, 2, Material.NAME_TAG, "Players nearby to count as crowd"),
                n("Crowd Radius", "crowd.radius", 48, 16, 128, 8, Material.SPYGLASS, "Blocks around a player"),
                n("Crowd View Dist", "crowd.view-distance", 5, 3, 10, 1, Material.ENDER_EYE, "View distance inside a crowd"),
                n("Crowd Sim Dist", "crowd.simulation-distance", 3, 2, 8, 1, Material.CLOCK, "Simulation distance inside a crowd"),
                t("Online Scaling", "online-scaling.enabled", true, Material.GOLD_INGOT, "Lower view distance as players join")));
    }

    private static final class Holder implements InventoryHolder {
        Inventory inv;
        Page page = Page.MAIN;
        int value;
        final Map<Integer, String> cmds = new HashMap<>();
        final Map<Integer, S> settings = new HashMap<>();
        @Override public Inventory getInventory() { return inv; }
    }

    Gui(RWLag pl) { this.pl = pl; }

    // ---------- helpers ----------

    private Holder make(Page p, String title) {
        Holder h = new Holder();
        h.page = p;
        h.inv = Bukkit.createInventory(h, 54, RWLag.MM.deserialize(title));
        frame(h.inv);
        return h;
    }

    private ItemStack item(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(RWLag.MM.deserialize("<!italic>" + name));
        List<net.kyori.adventure.text.Component> l = new ArrayList<>();
        for (String s : lore) l.add(RWLag.MM.deserialize("<!italic>" + s));
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    private void frame(Inventory inv) {
        ItemStack border = item(Material.BLACK_STAINED_GLASS_PANE, " ");
        ItemStack inner = item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) {
            int r = i / 9, c = i % 9;
            inv.setItem(i, (r == 0 || r == 5 || c == 0 || c == 8) ? border : inner);
        }
    }

    private static String fmt(double v) {
        if (v == Math.rint(v)) return String.valueOf((long) v);
        return String.valueOf(Math.round(v * 100) / 100.0);
    }

    private static String bar(double pct) {
        int filled = (int) Math.max(0, Math.min(20, Math.round(pct / 5)));
        String color = pct < 70 ? "<green>" : pct < 88 ? "<yellow>" : "<red>";
        return color + "|".repeat(filled) + "<dark_gray>" + "|".repeat(20 - filled);
    }

    private ItemStack settingItem(S s) {
        FileConfiguration c = pl.getConfig();
        if (s.toggle()) {
            boolean on = c.getBoolean(s.path(), s.def() == 1);
            return item(s.icon(), (on ? "<green>[ON]  " : "<red>[OFF] ") + "<white>" + s.name(),
                    "<gray>" + s.desc(), " ",
                    "<gray>State: " + (on ? "<green>ON" : "<red>OFF"), "<yellow>Click to switch");
        }
        double v = c.getDouble(s.path(), s.def());
        return item(s.icon(), "<gold>" + s.name() + ": <white>" + fmt(v),
                "<gray>" + s.desc(), " ",
                "<yellow>Left click <gray>+" + fmt(s.step()) + "   <yellow>Right click <gray>-" + fmt(s.step()),
                "<yellow>Shift <gray>= x10", "<dark_gray>Range " + fmt(s.min()) + " - " + fmt(s.max()));
    }

    private void saveAndReload() {
        pl.saveConfig();
        pl.loadSettings();
    }
