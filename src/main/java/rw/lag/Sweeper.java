package rw.lag;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/** Time-sliced chunk sweeper: entity limits + far-mob AI sleep + per-chunk counts. */
final class Sweeper implements Runnable {
    private final RWLag pl;
    private final NamespacedKey aiKey;
    private ArrayDeque<Chunk> queue;
    private int wait = 40;
    private Map<UUID, Map<Long, int[]>> building = new HashMap<>(), counts = new HashMap<>();
    private final Map<UUID, double[]> players = new HashMap<>();
    long removed;
    int sleeping, sleepBuilding;

    static final class Culprit {
        final String name; double score, best; World w; int cx, cz;
        Culprit(String n) { name = n; }
    }
    private final Map<UUID, String[]> names = new HashMap<>();
    private Map<String, Culprit> scoreBuilding = new HashMap<>(), scores = new HashMap<>();

    Sweeper(RWLag pl) { this.pl = pl; this.aiKey = new NamespacedKey(pl, "ai_off"); }

    static long key(int cx, int cz) { return (cx & 0xffffffffL) | ((long) cz << 32); }

    int[] countFor(Location l) {
        World w = l.getWorld();
        if (w == null) return null;
        Map<Long, int[]> m = counts.get(w.getUID());
        if (m == null) return null;
        return m.computeIfAbsent(key(l.getBlockX() >> 4, l.getBlockZ() >> 4), k -> new int[Group.values().length]);
    }

    @Override
    public void run() {
        if (queue == null) {
            if (--wait > 0) return;
            begin();
            return;
        }
        long end = System.nanoTime() + pl.budgetNs;
        while (!queue.isEmpty()) {
            Chunk c = queue.poll();
            if (c.isLoaded()) processChunk(c);
            if (System.nanoTime() > end) break;
        }
        if (queue.isEmpty()) {
            counts = building;
            scores = scoreBuilding;
            sleeping = sleepBuilding;
            queue = null;
            wait = pl.lagMode ? Math.max(1, pl.sweepInterval / 2) : pl.sweepInterval;
        }
    }

    private void begin() {
        building = new HashMap<>();
        scoreBuilding = new HashMap<>();
        sleepBuilding = 0;
        players.clear();
        names.clear();
        queue = new ArrayDeque<>();
        for (World w : Bukkit.getWorlds()) {
            List<Player> ps = w.getPlayers();
            double[] a = new double[ps.size() * 2];
            for (int i = 0; i < ps.size(); i++) {
                Location l = ps.get(i).getLocation();
                a[i * 2] = l.getX();
                a[i * 2 + 1] = l.getZ();
            }
            players.put(w.getUID(), a);
            String[] nm = new String[ps.size()];
            for (int i = 0; i < nm.length; i++) nm[i] = ps.get(i).getName();
            names.put(w.getUID(), nm);
            Collections.addAll(queue, w.getLoadedChunks());
        }
    }

    private String nearestName(UUID w, double x, double z, double d) {
        double[] a = players.get(w);
        String[] nm = names.get(w);
        if (a == null || nm == null) return null;
        double best = d * d;
        String res = null;
        for (int i = 0; i < nm.length; i++) {
            double dx = a[i * 2] - x, dz = a[i * 2 + 1] - z, d2 = dx * dx + dz * dz;
            if (d2 <= best) { best = d2; res = nm[i]; }
        }
        return res;
    }

    List<Culprit> culprits(int n) {
        List<Culprit> l = new ArrayList<>(scores.values());
        l.sort((a, b) -> Double.compare(b.score, a.score));
        return l.subList(0, Math.min(n, l.size()));
    }

    private boolean near(UUID w, double x, double z, double d) {
        double[] a = players.get(w);
        if (a == null) return false;
        double d2 = d * d;
        for (int i = 0; i < a.length; i += 2) {
            double dx = a[i] - x, dz = a[i + 1] - z;
            if (dx * dx + dz * dz <= d2) return true;
        }
        return false;
    }

    boolean isProtected(Entity e) {
        if (e instanceof Player || e instanceof Boss) return true;
        if (e.customName() != null) return true;
        if (e instanceof Tameable t && t.isTamed()) return true;
        if (e instanceof LivingEntity le && le.isLeashed()) return true;
        if (e.hasMetadata("NPC")) return true;
        if (e.isInsideVehicle() || !e.getPassengers().isEmpty()) return true;
        if (e instanceof Item it) return pl.protectedItem(it);
        return false;
                      }
