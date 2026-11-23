package rw.lag;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Trident;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Automatic lag fixer. When the server lags, it finds the heaviest chunks and
 * immediately trims the entities that cause the lag there. Runs every 2 seconds.
 * Never touches named, tamed, leashed, boss, NPC, riding entities or protected zones.
 */
final class AutoFix {
    private final RWLag pl;
    private long lastNotify;
    long totalFixed;
    int lastLevel;

    // per-chunk caps: {hostile, animal, water, ambient, villager, item, xp, vehicle, tnt, falling, arrows}
    private static final int[] SOFT = {12, 16, 8, 2, 30, 20, 10, 6, 30, 20, 10};
    private static final int[] HARD = {5, 8, 4, 0, 20, 4, 0, 3, 10, 5, 0};

    AutoFix(RWLag pl) { this.pl = pl; }

    void tick() {
        FileConfiguration c = pl.getConfig();
        if (!c.getBoolean("autofix.enabled", true)) {
            lastLevel = 0;
            return;
        }
        double mspt = Bukkit.getAverageTickTime();
        double tps = Bukkit.getTPS()[0];
        double trigger = c.getDouble("autofix.trigger-mspt", 40);
        double critical = c.getDouble("autofix.critical-mspt", 60);
        int level = (mspt >= critical || tps < 14) ? 2 : (pl.lagMode || mspt >= trigger || tps < 17) ? 1 : 0;
        lastLevel = level;
        if (level == 0) return;
        if (level == 2 && !pl.lagMode) pl.enableLag();

        int minEnt = c.getInt("autofix.min-entities-in-chunk", 25);
        int maxChunks = level == 2 ? c.getInt("autofix.max-chunks-critical", 15) : c.getInt("autofix.max-chunks", 6);
        int removed = 0;
        World bw = null;
        int bx = 0, bz = 0, best = 0;
        for (Object[] row : pl.sweeper.top(maxChunks)) {
            World w = (World) row[0];
            int cx = (int) row[1], cz = (int) row[2], total = (int) row[3];
            if (total < minEnt) break;
            if (!w.isChunkLoaded(cx, cz) || pl.inZone(w, cx, cz)) continue;
            int r = fix(w.getChunkAt(cx, cz), level == 2 ? HARD : SOFT);
            removed += r;
            if (r > best) { best = r; bw = w; bx = cx; bz = cz; }
        }
        totalFixed += removed;
        long now = System.currentTimeMillis();
        if (removed >= 10 && bw != null && c.getBoolean("autofix.notify", true) && now - lastNotify > 20_000) {
            lastNotify = now;
            pl.notifyAdminsTp("<green>AutoFix" + (level == 2 ? " <red>(CRITICAL)" : "") + " <gray>removed <white>" + removed
                    + " <gray>lag entities. Worst area: <aqua>" + bw.getName() + " " + (bx * 16) + "," + (bz * 16)
                    + " <yellow>[click = teleport]", bw, bx, bz);
        }
    }

    /** Trims one chunk down to the caps. Returns how many entities were removed. */
    private int fix(Chunk ch, int[] caps) {
        EnumMap<Group, List<Entity>> by = new EnumMap<>(Group.class);
        List<Entity> falling = new ArrayList<>();
        List<Entity> arrows = new ArrayList<>();
        for (Entity e : ch.getEntities()) {
            if (!e.isValid()) continue;
            if (e instanceof FallingBlock) { falling.add(e); continue; }
            if (e instanceof AbstractArrow && !(e instanceof Trident)) { arrows.add(e); continue; }
            Group g = Group.of(e);
            if (g == Group.OTHER) continue;
            by.computeIfAbsent(g, k -> new ArrayList<>()).add(e);
        }
        int n = 0;
        for (Map.Entry<Group, List<Entity>> en : by.entrySet()) {
            n += trim(en.getValue(), caps[en.getKey().ordinal()], true);
        }
        n += trim(falling, caps[9], false);
        n += trim(arrows, caps[10], false);
        return n;
    }

    private int trim(List<Entity> list, int cap, boolean checkProtected) {
        if (list.size() <= cap) return 0;
        List<Entity> removable = new ArrayList<>();
        int kept = 0;
        for (Entity e : list) {
            if (checkProtected && pl.sweeper.isProtected(e)) kept++;
            else removable.add(e);
        }
        int extra = list.size() - Math.max(cap, kept);
        if (extra <= 0) return 0;
        removable.sort((a, b) -> Integer.compare(b.getTicksLived(), a.getTicksLived()));
        int n = 0;
        for (int i = 0; i < extra && i < removable.size(); i++) {
            removable.get(i).remove();
            n++;
        }
        return n;
    }
            }
