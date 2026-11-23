package rw.lag;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Network / crowd load control. Every 5 seconds each player gets the smallest of:
 * world distance, high-ping tier, online-players tier, crowd tier.
 * Less chunk data sent = less server work = lower ping for everyone.
 */
final class Network {
    private final RWLag pl;
    private final Set<UUID> touched = new HashSet<>();

    Network(RWLag pl) { this.pl = pl; }

    private static int[] parse(String t) {
        try {
            String[] p = t.split(":");
            return new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim())};
        } catch (Exception ex) {
            return null;
        }
    }

    void tick() {
        FileConfiguration c = pl.getConfig();
        boolean ping = pl.pingEnabled;
        boolean crowd = c.getBoolean("crowd.enabled", true);
        boolean scale = c.getBoolean("online-scaling.enabled", true);
        boolean active = ping || crowd || scale;
        touched.removeIf(id -> Bukkit.getPlayer(id) == null);

        Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        int n = online.size();
        int onlineCap = Integer.MAX_VALUE;
        if (scale) {
            List<String> tiers = c.getStringList("online-scaling.tiers");
            if (tiers.isEmpty()) tiers = List.of("60:7", "100:6", "150:5");
            for (String t : tiers) {
                int[] p = parse(t);
                if (p != null && n >= p[0]) onlineCap = Math.min(onlineCap, p[1]);
            }
        }
        int minPlayers = Math.max(2, c.getInt("crowd.min-players", 12));
        double radius = c.getDouble("crowd.radius", 48);
        double r2 = radius * radius;
        int cView = c.getInt("crowd.view-distance", 5);
        int cSim = c.getInt("crowd.simulation-distance", 3);

        List<Player> list = new ArrayList<>(online);
        double[] xs = new double[list.size()], zs = new double[list.size()];
        World[] ws = new World[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Player p = list.get(i);
            xs[i] = p.getLocation().getX();
            zs[i] = p.getLocation().getZ();
            ws[i] = p.getWorld();
        }

        for (int i = 0; i < list.size(); i++) {
            Player p = list.get(i);
            try {
                World w = ws[i];
                int view = w.getViewDistance();
                int sim = w.getSimulationDistance();
                if (active) {
                    if (ping) {
                        int pg = p.getPing();
                        for (Map.Entry<Integer, Integer> en : pl.pingTiers.entrySet())
                            if (pg >= en.getKey()) view = Math.min(view, en.getValue());
                    }
                    if (scale && onlineCap != Integer.MAX_VALUE) view = Math.min(view, onlineCap);
                    if (crowd) {
                        int near = 0;
                        for (int j = 0; j < list.size(); j++) {
                            if (ws[j] != w) continue;
                            double dx = xs[j] - xs[i], dz = zs[j] - zs[i];
                            if (dx * dx + dz * dz <= r2) near++;
                        }
                        if (near >= minPlayers) {
                            view = Math.min(view, cView);
                            sim = Math.min(sim, cSim);
                        }
                    }
                    view = Math.max(3, view);
                    sim = Math.max(2, Math.min(sim, view));
                } else if (!touched.contains(p.getUniqueId())) {
                    continue;
                }
                boolean changed = false;
                if (p.getViewDistance() != view) { p.setViewDistance(view); changed = true; }
                if (p.getSimulationDistance() != sim) { p.setSimulationDistance(sim); changed = true; }
                if (changed) touched.add(p.getUniqueId());
                if (!active) touched.remove(p.getUniqueId());
            } catch (Throwable ignored) {
                // method missing on this server version: skip this player
            }
        }
    }
                }
