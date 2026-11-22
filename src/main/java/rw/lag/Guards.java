package rw.lag;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.Hopper;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.entity.ItemSpawnEvent;

import java.util.*;

final class Guards implements Listener {
    private record Key(UUID w, long c) {}

    private final RWLag pl;
    private final Map<Key, int[]> redstone = new HashMap<>();
    private final Map<Key, Long> blocked = new HashMap<>();
    long redstoneBlocked;

    Guards(RWLag pl) { this.pl = pl; }

    /** called every second */
    void tick() {
        redstone.clear();
        long now = System.currentTimeMillis();
        blocked.values().removeIf(t -> t < now);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        SpawnReason r = e.getSpawnReason();
        if (!pl.naturalSpawn && pl.blockedReasons.contains(r)) {
            e.setCancelled(true);
            return;
        }
        if (pl.lagMode && pl.lagCancelNatural && (r == SpawnReason.NATURAL || r == SpawnReason.PATROL
                || r == SpawnReason.VILLAGE_INVASION || r == SpawnReason.REINFORCEMENTS)) {
            e.setCancelled(true);
            return;
        }
        if (r != SpawnReason.NATURAL && r != SpawnReason.SPAWNER && r != SpawnReason.BREEDING && r != SpawnReason.EGG) return;
        Group g = Group.of(e.getEntity());
        World sw = e.getLocation().getWorld();
        if (sw != null && pl.inZone(sw, e.getLocation().getBlockX() >> 4, e.getLocation().getBlockZ() >> 4)) return;
        int lim = pl.limit(g, sw);
        if (lim < 0) return;
        int[] c = pl.sweeper.countFor(e.getLocation());
        if (c == null) return;
        if (c[g.ordinal()] >= lim) e.setCancelled(true);
        else c[g.ordinal()]++;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItem(ItemSpawnEvent e) {
        if (pl.hardCap <= 0) return;
        World iw = e.getLocation().getWorld();
        if (iw != null && pl.inZone(iw, e.getLocation().getBlockX() >> 4, e.getLocation().getBlockZ() >> 4)) return;
        int lim = pl.limit(Group.ITEM, iw);
        if (lim < 0) return;
        int[] c = pl.sweeper.countFor(e.getLocation());
        if (c == null) return;
        if (c[Group.ITEM.ordinal()] >= lim * pl.hardCap) e.setCancelled(true);
        else c[Group.ITEM.ordinal()]++;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!pl.hopperEnabled || e.getBlockPlaced().getType() != Material.HOPPER) return;
        if (e.getPlayer().hasPermission("rwlag.bypass")) return;
        if (pl.inZone(e.getBlockPlaced().getWorld(), e.getBlockPlaced().getX() >> 4, e.getBlockPlaced().getZ() >> 4)) return;
        int n = 0;
        for (BlockState bs : e.getBlockPlaced().getChunk().getTileEntities()) if (bs instanceof Hopper) n++;
        if (n >= pl.hopperMax) {
            e.setCancelled(true);
            pl.send(e.getPlayer(), "<red>Hopper limit reached for this chunk (<white>" + pl.hopperMax + "<red>).");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRedstone(BlockRedstoneEvent e) {
        if (!pl.redstoneEnabled) return;
        Block b = e.getBlock();
        Key k = new Key(b.getWorld().getUID(), ((b.getX() >> 4) & 0xffffffffL) | ((long) (b.getZ() >> 4) << 32));
        long now = System.currentTimeMillis();
        Long until = blocked.get(k);
        if (until != null && until > now) {
            e.setNewCurrent(e.getOldCurrent());
            redstoneBlocked++;
            return;
        }
        int limit = pl.lagMode ? Math.max(50, (int) (pl.redstoneMax * pl.lagMult)) : pl.redstoneMax;
        int n = ++redstone.computeIfAbsent(k, x -> new int[1])[0];
        if (n > limit && !pl.inZone(b.getWorld(), b.getX() >> 4, b.getZ() >> 4)) {
            blocked.put(k, now + pl.redstoneBlockSec * 1000L);
            e.setNewCurrent(e.getOldCurrent());
            redstoneBlocked++;
            Location l = b.getLocation();
            pl.notifyAdminsTp("<yellow>Redstone lag machine frozen for " + pl.redstoneBlockSec + "s at <white>"
                    + b.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ()
                    + " <aqua>[click = teleport]", b.getWorld(), b.getX() >> 4, b.getZ() >> 4);
        }
    }
            }
