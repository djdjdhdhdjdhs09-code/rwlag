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
    }    // ================= MAIN =================

    void open(Player p) {
        Holder h = make(Page.MAIN, "<gradient:#ff5555:#ffaa00><bold>RWLag <dark_gray>» <white>Control Center");
        fillMain(h);
        p.openInventory(h.inv);
    }

    private void fillMain(Holder h) {
        Inventory inv = h.inv;
        double tps = Math.min(20, Bukkit.getTPS()[0]);
        double mspt = Bukkit.getAverageTickTime();
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) >> 20, max = rt.maxMemory() >> 20;
        double pct = used * 100.0 / max;
        Material dash = tps >= 19 ? Material.LIME_CONCRETE : tps >= 15 ? Material.YELLOW_CONCRETE : Material.RED_CONCRETE;
        inv.setItem(4, item(dash, "<gold><bold>Server Status",
                "<gray>TPS: " + (tps >= 19 ? "<green>" : tps >= 15 ? "<yellow>" : "<red>") + String.format("%.1f", tps),
                "<gray>MSPT: <white>" + String.format("%.1f", mspt),
                "<gray>RAM: <white>" + used + "/" + max + " MB",
                bar(pct),
                "<gray>Players: <white>" + Bukkit.getOnlinePlayers().size() + "/" + Bukkit.getMaxPlayers(),
                "<gray>Lag mode: " + (pl.lagMode ? "<red>ON" : "<green>OFF"),
                "<yellow>Click to refresh"));
        inv.setItem(20, item(Material.REDSTONE, "<red><bold>Performance", "<gray>AI, redstone, hoppers, lag mode", "<yellow>Click to open"));
        inv.setItem(22, item(Material.ZOMBIE_SPAWN_EGG, "<green><bold>Mobs", "<gray>Natural spawning and mob limits", "<yellow>Click to open"));
        inv.setItem(24, item(Material.HOPPER, "<gold><bold>Items & Clear", "<gray>Auto clear, timer and filters", "<yellow>Click to open"));
        inv.setItem(30, item(Material.ENDER_EYE, "<aqua><bold>Network & Crowds", "<gray>Ping, crowd mode, view distance", "<yellow>Click to open"));
        inv.setItem(32, item(Material.COMPASS, "<light_purple><bold>Tools", "<gray>Teleport to lag, report, lag mode", "<yellow>Click to open"));
        inv.setItem(49, item(Material.BARRIER, "<gray>Close"));
    }

    // ================= SETTINGS PAGES =================

    private void openPage(Player p, Page page) {
        String title = switch (page) {
            case PERF -> "Performance";
            case MOBS -> "Mobs";
            case ITEMS -> "Items & Clear";
            case NET -> "Network & Crowds";
            default -> "Settings";
        };
        Holder h = make(page, "<gradient:#ff5555:#ffaa00><bold>RWLag <dark_gray>» <white>" + title);
        fillPage(h);
        p.openInventory(h.inv);
    }

    private void fillPage(Holder h) {
        h.settings.clear();
        h.cmds.clear();
        List<S> list = PAGES.get(h.page);
        for (int i = 0; i < list.size() && i < SLOTS.length; i++) {
            h.settings.put(SLOTS[i], list.get(i));
            h.inv.setItem(SLOTS[i], settingItem(list.get(i)));
        }
        if (h.page == Page.ITEMS) {
            h.inv.setItem(39, item(Material.REPEATER, "<gold>Item clear timer: <white>" + RWLag.fmtTime(pl.clearInterval),
                    "<gray>Change how often items are cleared", "<yellow>Click to change"));
            h.inv.setItem(41, item(Material.TNT, "<red>Clear now", "<gray>Clear items, orbs, arrows now"));
        }
        h.inv.setItem(45, item(Material.ARROW, "<gray>Back"));
        h.inv.setItem(49, item(Material.BARRIER, "<gray>Close"));
    }

    private void clickSetting(Holder h, S s, ClickType ct) {
        FileConfiguration c = pl.getConfig();
        if (s.toggle()) {
            c.set(s.path(), !c.getBoolean(s.path(), s.def() == 1));
        } else {
            double v = c.getDouble(s.path(), s.def());
            double st = s.step() * (ct.isShiftClick() ? 10 : 1);
            if (ct.isRightClick()) v -= st; else v += st;
            v = Math.max(s.min(), Math.min(s.max(), v));
            v = Math.round(v * 100) / 100.0;
            if (s.step() % 1 == 0 && s.def() % 1 == 0) c.set(s.path(), (int) v); else c.set(s.path(), v);
        }
        saveAndReload();
        fillPage(h);
    }

    // ================= TOOLS =================

    private void openTools(Player p) {
        Holder h = make(Page.TOOLS, "<gradient:#ff5555:#ffaa00><bold>RWLag <dark_gray>» <white>Tools");
        fillTools(h);
        p.openInventory(h.inv);
    }

    private void fillTools(Holder h) {
        Inventory inv = h.inv;
        inv.setItem(20, item(Material.ENDER_PEARL, "<aqua>Teleport to lag spots", "<gray>Heaviest chunks and players", "<yellow>Click to open"));
        inv.setItem(21, item(Material.TNT, "<red>Clear now", "<gray>Clear items, orbs, arrows now"));
        inv.setItem(22, item(Material.REPEATER, "<gold>Item clear timer: <white>" + RWLag.fmtTime(pl.clearInterval), "<yellow>Click to change"));
        inv.setItem(23, item(Material.WRITABLE_BOOK, "<white>Save report", "<gray>Writes plugins/RWLag/reports/"));
        inv.setItem(24, item(Material.BEACON, "<red>Lag mode: " + (pl.lagMode ? "<red>ON" : "<green>OFF"), "<yellow>Click to switch"));
        inv.setItem(25, item(Material.WRITTEN_BOOK, "<white>Discord test", "<gray>Send a test webhook message"));
        inv.setItem(45, item(Material.ARROW, "<gray>Back"));
        inv.setItem(49, item(Material.BARRIER, "<gray>Close"));
    }

    // ================= TELEPORT =================

    private void openTp(Player p) {
        Holder h = make(Page.TP, "<gradient:#55ffff:#5555ff><bold>Lag spots <dark_gray>» <white>click = teleport");
        fillTp(h);
        p.openInventory(h.inv);
    }

    private void fillTp(Holder h) {
        Inventory inv = h.inv;
        h.cmds.clear();
        frame(inv);
        inv.setItem(4, item(Material.SUNFLOWER, "<yellow>Refresh"));
        inv.setItem(9, item(Material.ENDER_PEARL, "<aqua>Heaviest chunks", "<gray>Row below"));
        inv.setItem(18, item(Material.NAME_TAG, "<aqua>Players near heavy areas", "<gray>Row below"));
        List<Object[]> top = pl.sweeper.top(7);
        for (int i = 0; i < top.size(); i++) {
            Object[] o = top.get(i);
            World w = (World) o[0];
            int cx = (int) o[1], cz = (int) o[2];
            int[] c = (int[]) o[4];
            int slot = 10 + i;
            inv.setItem(slot, item(Material.ENDER_PEARL, "<aqua>" + w.getName() + " " + cx + "," + cz,
                    "<gray>Entities: <white>" + o[3],
                    "<gray>Hostile " + c[Group.HOSTILE.ordinal()] + ", animal " + c[Group.ANIMAL.ordinal()]
                            + ", item " + c[Group.ITEM.ordinal()],
                    "<yellow>Click to teleport"));
            h.cmds.put(slot, "rwlag tp " + w.getName() + " " + cx + " " + cz);
        }
        List<Sweeper.Culprit> cul = pl.sweeper.culprits(7);
        for (int i = 0; i < cul.size(); i++) {
            Sweeper.Culprit c = cul.get(i);
            if (c.w == null) continue;
            int slot = 19 + i;
            inv.setItem(slot, item(Material.NAME_TAG, "<aqua>" + c.name,
                    "<gray>Score: <white>" + (int) c.score,
                    "<gray>Worst chunk: <white>" + c.w.getName() + " " + c.cx + "," + c.cz,
                    "<yellow>Click to teleport"));
            h.cmds.put(slot, "rwlag tp " + c.w.getName() + " " + c.cx + " " + c.cz);
        }
        if (top.isEmpty()) inv.setItem(22, item(Material.PAPER, "<gray>No data yet. Wait a few seconds, then refresh."));
        inv.setItem(45, item(Material.ARROW, "<gray>Back"));
        inv.setItem(49, item(Material.BARRIER, "<gray>Close"));
            }    // ================= TIMER =================

    private static int clampTime(int v) { return Math.max(10, Math.min(86400, v)); }

    private void openTime(Player p, int value) {
        Holder h = make(Page.TIME, "<gradient:#ffaa00:#ffff55><bold>Item clear timer");
        h.value = clampTime(value);
        fillTime(h);
        p.openInventory(h.inv);
    }

    private void fillTime(Holder h) {
        Inventory inv = h.inv;
        inv.setItem(13, item(Material.CLOCK, "<gold>Clear every: <white>" + RWLag.fmtTime(h.value),
                "<gray>(" + h.value + " seconds)",
                "<gray>Players are warned at: <white>" + new TreeSet<>(pl.warnAt).descendingSet(),
                "<yellow>Press SAVE to apply"));
        inv.setItem(19, item(Material.RED_DYE, "<red>-10 minutes"));
        inv.setItem(20, item(Material.RED_DYE, "<red>-1 minute"));
        inv.setItem(21, item(Material.RED_DYE, "<red>-10 seconds"));
        inv.setItem(23, item(Material.LIME_DYE, "<green>+10 seconds"));
        inv.setItem(24, item(Material.LIME_DYE, "<green>+1 minute"));
        inv.setItem(25, item(Material.LIME_DYE, "<green>+10 minutes"));
        inv.setItem(28, item(Material.PAPER, "<white>1 minute"));
        inv.setItem(29, item(Material.PAPER, "<white>5 minutes"));
        inv.setItem(30, item(Material.PAPER, "<white>10 minutes"));
        inv.setItem(31, item(Material.PAPER, "<white>30 minutes"));
        inv.setItem(33, item(Material.NAME_TAG, "<aqua>Type exact time in chat",
                "<gray>Example: 600, 90s, 10m, 1h", "<yellow>Click, then write in chat"));
        inv.setItem(40, item(Material.EMERALD_BLOCK, "<green><bold>SAVE", "<gray>Save " + RWLag.fmtTime(h.value)));
        inv.setItem(49, item(Material.BARRIER, "<gray>Cancel"));
    }

    private int delta(int slot) {
        return switch (slot) {
            case 19 -> -600;
            case 20 -> -60;
            case 21 -> -10;
            case 23 -> 10;
            case 24 -> 60;
            case 25 -> 600;
            default -> 0;
        };
    }

    private int preset(int slot) {
        return switch (slot) {
            case 28 -> 60;
            case 29 -> 300;
            case 30 -> 600;
            case 31 -> 1800;
            default -> 0;
        };
    }

    // ================= CLICKS =================

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || !p.hasPermission("rwlag.admin")) return;
        if (e.getClickedInventory() != e.getInventory()) return;
        int s = e.getSlot();
        switch (h.page) {
            case MAIN -> clickMain(p, h, s);
            case TOOLS -> clickTools(p, h, s);
            case TP -> clickTp(p, h, s);
            case TIME -> clickTime(p, h, s);
            default -> clickPage(p, h, s, e.getClick());
        }
    }

    private void clickMain(Player p, Holder h, int s) {
        switch (s) {
            case 4 -> fillMain(h);
            case 20 -> openPage(p, Page.PERF);
            case 22 -> openPage(p, Page.MOBS);
            case 24 -> openPage(p, Page.ITEMS);
            case 30 -> openPage(p, Page.NET);
            case 32 -> openTools(p);
            case 49 -> p.closeInventory();
            default -> { }
        }
    }

    private void clickPage(Player p, Holder h, int s, ClickType ct) {
        if (s == 45) { open(p); return; }
        if (s == 49) { p.closeInventory(); return; }
        if (h.page == Page.ITEMS && s == 39) { openTime(p, pl.clearInterval); return; }
        if (h.page == Page.ITEMS && s == 41) {
            int[] r = pl.doClear();
            pl.send(p, "<green>Cleared <white>" + r[0] + "<green> items, <white>" + r[1] + "<green> orbs, <white>" + r[2] + "<green> other.");
            return;
        }
        S st = h.settings.get(s);
        if (st != null) clickSetting(h, st, ct);
    }

    private void clickTools(Player p, Holder h, int s) {
        switch (s) {
            case 20 -> openTp(p);
            case 21 -> {
                int[] r = pl.doClear();
                pl.send(p, "<green>Cleared <white>" + r[0] + "<green> items, <white>" + r[1] + "<green> orbs, <white>" + r[2] + "<green> other.");
            }
            case 22 -> openTime(p, pl.clearInterval);
            case 23 -> { p.closeInventory(); p.performCommand("rwlag report"); }
            case 24 -> {
                if (pl.lagMode) pl.disableLag(true); else pl.enableLag();
                fillTools(h);
            }
            case 25 -> { p.closeInventory(); p.performCommand("rwlag discord"); }
            case 45 -> open(p);
            case 49 -> p.closeInventory();
            default -> { }
        }
    }

    private void clickTp(Player p, Holder h, int s) {
        if (s == 45) { openTools(p); return; }
        if (s == 49) { p.closeInventory(); return; }
        if (s == 4) { fillTp(h); return; }
        String cmd = h.cmds.get(s);
        if (cmd != null) {
            p.closeInventory();
            p.performCommand(cmd);
        }
    }

    private void clickTime(Player p, Holder h, int s) {
        int d = delta(s);
        int pr = preset(s);
        if (d != 0) {
            h.value = clampTime(h.value + d);
            fillTime(h);
        } else if (pr != 0) {
            h.value = pr;
            fillTime(h);
        } else if (s == 33) {
            awaiting.add(p.getUniqueId());
            p.closeInventory();
            pl.send(p, "<yellow>Write the time in chat (example: <white>600<yellow>, <white>90s<yellow>, <white>10m<yellow>, <white>1h<yellow>) or write <white>cancel<yellow>.");
        } else if (s == 40) {
            pl.getConfig().set("auto-clear.interval-seconds", h.value);
            saveAndReload();
            pl.send(p, "<green>Saved! Items are cleared every <white>" + RWLag.fmtTime(h.value) + "<green>.");
            openPage(p, Page.ITEMS);
        } else if (s == 49) {
            openPage(p, Page.ITEMS);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        if (!awaiting.remove(p.getUniqueId())) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Bukkit.getScheduler().runTask(pl, () -> {
            if (text.equalsIgnoreCase("cancel")) { openTime(p, pl.clearInterval); return; }
            try {
                openTime(p, RWLag.parseTime(text));
            } catch (NumberFormatException ex) {
                pl.send(p, "<red>Use a number like 600, 90s, 10m or 1h.");
                openTime(p, pl.clearInterval);
            }
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }
    }
