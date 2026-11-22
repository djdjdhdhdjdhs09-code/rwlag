package rw.lag;

import org.bukkit.Bukkit;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

final class Discord {
    private final RWLag pl;
    private final HttpClient http = HttpClient.newHttpClient();
    private long last;

    Discord(RWLag pl) { this.pl = pl; }

    boolean configured() { return pl.discordUrl != null && pl.discordUrl.startsWith("https://"); }

    void send(String text) { send(text, false); }

    void send(String text, boolean force) {
        if (!configured()) return;
        long now = System.currentTimeMillis();
        if (!force && now - last < pl.discordCooldown * 1000L) return;
        last = now;
        String json = "{\"username\":\"RWLag\",\"content\":\"" + esc(RWLag.MM.stripTags(text)) + "\"}";
        String url = pl.discordUrl;
        Bukkit.getScheduler().runTaskAsynchronously(pl, () -> {
            try {
                HttpRequest r = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json)).build();
                http.send(r, HttpResponse.BodyHandlers.discarding());
            } catch (Exception ex) {
                pl.getLogger().warning("Discord webhook failed: " + ex.getMessage());
            }
        });
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '"' -> b.append("\\\"");
                case '\n' -> b.append("\\n");
                case '\r' -> {}
                case '\t' -> b.append(' ');
                default -> { if (c < 0x20) b.append(' '); else b.append(c); }
            }
        }
        return b.toString();
    }
                                 }
