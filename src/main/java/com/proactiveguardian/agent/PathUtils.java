package com.proactiveguardian.agent;

/** Shared path-cleaning utilities used by analysis agents. */
final class PathUtils {

    private static final String[] GUARDIAN_PREFIXES = {"/guardian-ingest-", "/guardian-pr-"};
    private static final String[] WELL_KNOWN_MARKERS = {
            "/src/", "/.github/", "/k8s/", "/kubernetes/",
            "/helm/", "/charts/", "/manifests/", "/config/", "/deploy/"
    };

    private PathUtils() {}

    /**
     * Strip local temp-clone noise from a path, returning just the repo-relative portion.
     * e.g. {@code /var/folders/.../guardian-pr-1234/src/main/java/Foo.java} → {@code src/main/java/Foo.java}
     */
    static String repoRelative(String path) {
        if (path == null) return "?";
        String p = path.replace('\\', '/');
        for (String prefix : GUARDIAN_PREFIXES) {
            int i = p.indexOf(prefix);
            if (i >= 0) {
                // skip past the hash suffix directory, e.g. "guardian-pr-12345678/"
                int slash = p.indexOf('/', i + prefix.length());
                if (slash > 0 && slash + 1 < p.length()) return p.substring(slash + 1);
            }
        }
        for (String marker : WELL_KNOWN_MARKERS) {
            int i = p.lastIndexOf(marker);
            if (i >= 0) return p.substring(i + 1);
        }
        int slash = p.lastIndexOf('/');
        return slash >= 0 && slash + 1 < p.length() ? p.substring(slash + 1) : p;
    }
}
