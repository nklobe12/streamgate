package com.klobe.streamgate;

import java.util.Locale;
import java.util.Set;

/** Decides what may load. Has no Android dependencies so it can be tested anywhere. */
final class Policy {
    private Policy() {}

    /**
     * True for the streaming site, its subdomains, and any sister domain it
     * moves to (e.g. streamcorner.to). All navigation here is always allowed.
     */
    static boolean isSite(String host) {
        return host != null && host.toLowerCase(Locale.ROOT).matches("(.+\\.)?streamcorner\\.[a-z]{2,4}");
    }

    /** True if the host or any parent domain is listed, and it isn't the site itself. */
    static boolean isBlocked(String host, Set<String> list) {
        if (host == null || isSite(host)) return false;
        String h = host.toLowerCase(Locale.ROOT);
        while (true) {
            if (list.contains(h)) return true;
            int dot = h.indexOf('.');
            if (dot < 0) return false;
            h = h.substring(dot + 1);
        }
    }

    /** Whether a top-level page change (link, redirect, or script) may proceed. */
    static boolean allowTopLevel(String scheme, String host) {
        return isWeb(scheme) && isSite(host);
    }

    /** Whether a frame inside the page may navigate. The stream players live in these. */
    static boolean allowSubframe(String scheme, String host, Set<String> list) {
        if ("about".equals(scheme) || "blob".equals(scheme) || "data".equals(scheme)) return true;
        return isWeb(scheme) && !isBlocked(host, list);
    }

    private static boolean isWeb(String scheme) {
        return "http".equals(scheme) || "https".equals(scheme);
    }
}
