package rw.lag;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

final class Gui implements Listener {
    private final RWLag pl;

    private static final class Holder implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    Gui(RWLag pl) { this.pl = pl; }

    void open(Player p) {
        Holder h = new Holder();
        h.inv = Bukkit.createInventory(h, 27, RWLag.MM.deserialize("<gradient:#ff5555:#ffaa00><bold>RWLag Control"));
        fill(h.inv);
        p.openInventory(h.inv);
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

    private ItemStack toggle(String name, String path, String desc) {
        boolean on = pl.getConfig().getBoolean(path);
        return item(on ? Material.LIME_DYE : Material.GRAY_DYE, (on ? "<green>" : "<red>") + name + ": " + (on ? "ON" : "OFF"),
                "<gray>" + desc, "<yellow>Click to toggle");
    }

    private void fill(Inventory inv) {
        ItemStack bg = item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, bg);
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) >> 20, max = rt.maxMemory() >> 20;
        inv.setItem(4, item(Material.CLOCK, "<gold>Server status",
                "<gray>TPS: <white>" + String.format("%.1f", Math.min(20, Bukkit.getTPS()[0])),
                "<gray>MSPT: <white>" + String.format("%.1f", Bukkit.getAverageTickTime()),
                "<gray>RAM: <white>" + used + "/" + max + " MB",
                "<gray>Lag mode: " + (pl.lagMode ? "<red>ON" : "<green>OFF"), "<yellow>Click to refresh"));
        inv.setItem(10, toggle("AI Optimizer", "ai-optimizer.enabled", "Sleep mobs far from players"));
        inv.setItem(11, toggle("Auto Clear", "auto-clear.enabled", "Periodic ground clear"));
        inv.setItem(12, toggle("Redstone Limiter", "redstone-limiter.enabled", "Freeze lag machines"));
        inv.setItem(13, toggle("Auto Lag Mode", "lag-mode.enabled", "Emergency mode on low TPS"));
        inv.setItem(14, toggle("Item Merge", "item-merge.enabled", "Merge dropped items"));
        inv.setItem(15, toggle("Hopper Limiter", "hopper-limiter.enabled", "Max hoppers per chunk"));
        inv.setItem(16, toggle("Ping View Distance", "ping-view.enabled", "Lower view dist for high ping"));
        inv.setItem(19, toggle("Natural Mob Spawn", "mob-spawn.natural", "OFF = no auto spawns (eggs/spawners still work)"));
        inv.setItem(22, item(Material.LAVA_BUCKET, "<red>Clear now", "<gray>Clear items, orbs, arrows"));
        inv.setItem(26, item(Material.BARRIER, "<gray>Close"));
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || !p.hasPermission("rwlag.admin")) return;
        if (e.getClickedInventory() != e.getInventory()) return;
        int s = e.getSlot();
        String path = switch (s) {
            case 10 -> "ai-optimizer.enabled";
            case 11 -> "auto-clear.enabled";
            case 12 -> "redstone-limiter.enabled";
            case 13 -> "lag-mode.enabled";
            case 14 -> "item-merge.enabled";
            case 15 -> "hopper-limiter.enabled";
            case 16 -> "ping-view.enabled";
            case 19 -> "mob-spawn.natural";
            default -> null;
        };
        if (path != null) {
            pl.getConfig().set(path, !pl.getConfig().getBoolean(path));
            pl.saveConfig();
            pl.loadSettings();
            fill(h.inv);
        } else if (s == 22) {
            int[] r = pl.doClear();
            pl.send(p, "<green>Cleared <white>" + r[0] + "<green> items, <white>" + r[1] + "<green> orbs, <white>" + r[2] + "<green> other.");
        } else if (s == 26) p.closeInventory();
        else if (s == 4) fill(h.inv);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }
          }
