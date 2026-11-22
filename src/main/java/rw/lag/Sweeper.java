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
                      }    private void processChunk(Chunk c) {
        Entity[] ents = c.getEntities();
        UUID wid = c.getWorld().getUID();
        int[] cnt = new int[Group.values().length];
        EnumMap<Group, List<Entity>> rem = null;
        World cw = c.getWorld();
        boolean zone = pl.inZone(cw, c.getX(), c.getZ());
        int merged = (pl.mergeEnabled && !zone) ? mergeItems(ents) : 0;
        for (Entity e : ents) {
            if (!e.isValid()) continue;
            Group g = Group.of(e);
            if (g == Group.OTHER) continue;
            cnt[g.ordinal()]++;
            boolean prot = zone || isProtected(e);

            if (g.isMob() && e instanceof Mob m) {
                PersistentDataContainer pdc = e.getPersistentDataContainer();
                boolean off = pdc.has(aiKey, PersistentDataType.BYTE);
                boolean sleep = pl.aiEnabled && !prot && !pl.aiExclude.contains(e.getType())
                        && !near(wid, e.getLocation().getX(), e.getLocation().getZ(), pl.awareDist(cw));
                if (sleep) {
                    if (off) sleepBuilding++;
                    else if (m.isAware()) { m.setAware(false); pdc.set(aiKey, PersistentDataType.BYTE, (byte) 1); sleepBuilding++; }
                } else if (off) {
                    m.setAware(true);
                    pdc.remove(aiKey);
                }
            }

            if (!prot && pl.limit(g, cw) >= 0 && ents.length > pl.limit(g, cw)) {
                if (rem == null) rem = new EnumMap<>(Group.class);
                rem.computeIfAbsent(g, k -> new ArrayList<>()).add(e);
            }
        }
        if (rem != null) {
            for (Map.Entry<Group, List<Entity>> en : rem.entrySet()) {
                Group g = en.getKey();
                int lim = pl.limit(g, cw);
                int extra = cnt[g.ordinal()] - lim;
                if (extra <= 0) continue;
                List<Entity> l = en.getValue();
                l.sort((a, b) -> Integer.compare(b.getTicksLived(), a.getTicksLived()));
                for (int i = 0; i < extra && i < l.size(); i++) {
                    l.get(i).remove();
                    cnt[g.ordinal()]--;
                    removed++;
                }
            }
        }
        double weighted = cnt[Group.HOSTILE.ordinal()] + cnt[Group.ANIMAL.ordinal()] + cnt[Group.WATER.ordinal()] * 0.5
                + cnt[Group.AMBIENT.ordinal()] * 0.2 + cnt[Group.VILLAGER.ordinal()] * 2 + cnt[Group.ITEM.ordinal()] * 0.5
                + cnt[Group.XP.ordinal()] * 0.3 + cnt[Group.VEHICLE.ordinal()] * 1.5 + cnt[Group.TNT.ordinal()] * 2;
        if (weighted >= 10) {
            String who = nearestName(wid, c.getX() * 16 + 8, c.getZ() * 16 + 8, 96);
            if (who != null) {
                Culprit cu = scoreBuilding.computeIfAbsent(who, Culprit::new);
                cu.score += weighted;
                if (weighted > cu.best) { cu.best = weighted; cu.w = c.getWorld(); cu.cx = c.getX(); cu.cz = c.getZ(); }
            }
        }
        building.computeIfAbsent(wid, k -> new HashMap<>()).put(key(c.getX(), c.getZ()), cnt);
    }

    /** Merge identical item entities of one chunk into full stacks. Returns removed count. */
    private int mergeItems(Entity[] ents) {
        List<Item> items = new ArrayList<>();
        for (Entity e : ents) if (e instanceof Item it && it.isValid() && it.getTicksLived() > 10) items.add(it);
        if (items.size() < pl.mergeMin) return 0;
        int gone = 0;
        for (int i = 0; i < items.size(); i++) {
            Item a = items.get(i);
            if (!a.isValid()) continue;
            ItemStack sa = a.getItemStack();
            int max = sa.getMaxStackSize();
            if (sa.getAmount() >= max) continue;
            boolean changed = false;
            for (int j = i + 1; j < items.size() && sa.getAmount() < max; j++) {
                Item b = items.get(j);
                if (!b.isValid()) continue;
                ItemStack sb = b.getItemStack();
                if (!sa.isSimilar(sb)) continue;
                int move = Math.min(max - sa.getAmount(), sb.getAmount());
                sa.setAmount(sa.getAmount() + move);
                changed = true;
                if (move >= sb.getAmount()) { b.remove(); gone++; }
                else { sb.setAmount(sb.getAmount() - move); b.setItemStack(sb); }
            }
            if (changed) a.setItemStack(sa);
        }
        removed += 0;
        return gone;
    }

    /** Wake every mob we put to sleep (used on disable). */
    void wakeAll() {
        for (World w : Bukkit.getWorlds())
            for (Entity e : w.getEntities())
                if (e instanceof Mob m && e.getPersistentDataContainer().has(aiKey, PersistentDataType.BYTE)) {
                    m.setAware(true);
                    e.getPersistentDataContainer().remove(aiKey);
                }
    }

    /** Heaviest chunks: {World, cx, cz, total, int[] counts} */
    List<Object[]> top(int n) {
        List<Object[]> all = new ArrayList<>();
        for (Map.Entry<UUID, Map<Long, int[]>> we : counts.entrySet()) {
            World w = Bukkit.getWorld(we.getKey());
            if (w == null) continue;
            for (Map.Entry<Long, int[]> ce : we.getValue().entrySet()) {
                int t = 0;
                for (int v : ce.getValue()) t += v;
                long k = ce.getKey();
                all.add(new Object[]{w, (int) k, (int) (k >> 32), t, ce.getValue()});
            }
        }
        all.sort((a, b) -> Integer.compare((int) b[3], (int) a[3]));
        return all.subList(0, Math.min(n, all.size()));
    }
            }
