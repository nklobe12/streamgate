package com.klobe.streamgate.fulu;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Decides which hosts are the site, and which are ad or tracker hosts. */
final class AdBlocker {
    /** The site itself. The host and all its subdomains count as "same website". */
    private static final String[] SITE_HOSTS = {"cinejoy.pk", "cinejoy.to"};

    private static final String[] AD_WORDS = {
            "popads", "popcash", "propellerads", "adsterra", "exoclick", "juicyads",
            "trafficjunky", "hilltopads", "clickadu", "doubleclick", "googlesyndication",
            "adservice", "adnxs", "onclick", "popunder", "pushnotification"
    };

    private static final String LIST_URL =
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts";
    private static final long REFRESH_MS = 7L * 24 * 60 * 60 * 1000;
    private static final int MIN_VALID_HOSTS = 10000;

    private volatile Set<String> hosts = new HashSet<>();

    /** Loads the built-in list, then any downloaded list, then refreshes it if stale. */
    void start(Context context) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            Set<String> set = new HashSet<>();
            try (InputStream in = app.getAssets().open("adhosts.txt")) {
                read(in, set);
            } catch (IOException ignored) {
            }
            hosts = set;

            File cache = new File(app.getFilesDir(), "hosts.txt");
            if (cache.exists()) {
                Set<String> merged = new HashSet<>(set);
                try (InputStream in = new java.io.FileInputStream(cache)) {
                    read(in, merged);
                    hosts = merged;
                } catch (IOException ignored) {
                }
            }
            if (System.currentTimeMillis() - cache.lastModified() > REFRESH_MS) {
                refresh(cache, set);
            }
        }, "adblock-list").start();
    }

    private void refresh(File cache, Set<String> builtIn) {
        File tmp = new File(cache.getPath() + ".tmp");
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(LIST_URL).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            Set<String> merged = new HashSet<>(builtIn);
            try (InputStream in = new java.io.FileInputStream(tmp)) {
                read(in, merged);
            }
            // A truncated or wrong download must not replace a good list.
            if (merged.size() - builtIn.size() >= MIN_VALID_HOSTS && tmp.renameTo(cache)) {
                hosts = merged;
            }
        } catch (IOException ignored) {
            // Offline: keep the current list.
        } finally {
            if (c != null) c.disconnect();
            tmp.delete();
        }
    }

    /** Accepts plain "host" lines and hosts-file "0.0.0.0 host" lines. */
    private static void read(InputStream in, Set<String> out) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        String line;
        while ((line = r.readLine()) != null) {
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+");
            String host = parts[parts.length - 1].toLowerCase(Locale.ROOT);
            if (host.indexOf('.') > 0 && !host.equals("0.0.0.0")) out.add(host);
        }
    }

    static boolean isSite(String host) {
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        for (String site : SITE_HOSTS) {
            if (host.equals(site) || host.endsWith("." + site)) return true;
        }
        return false;
    }

    boolean isAd(String host) {
        if (host == null || isSite(host)) return false;
        host = host.toLowerCase(Locale.ROOT);
        for (String word : AD_WORDS) {
            if (host.contains(word)) return true;
        }
        Set<String> set = hosts;
        String h = host;
        while (true) {
            if (set.contains(h)) return true;
            int dot = h.indexOf('.');
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false;
            h = h.substring(dot + 1);
        }
    }
}
