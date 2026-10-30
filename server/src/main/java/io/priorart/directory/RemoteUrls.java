package io.priorart.directory;

import java.util.Locale;

/**
 * Canonical form of a git remote so the same repo matches however it was cloned:
 * git@github.com:Org/Repo.git, https://user:tok@github.com/org/repo, ssh://git@github.com:22/org/repo/ → github.com/org/repo.
 * Keep in sync with the SQL in V2__tenancy_and_transcripts.sql.
 */
public final class RemoteUrls {
    private RemoteUrls() {}

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        s = s.replaceFirst("(?i)^(https?|ssh|git)://([^@/]+@)?", "");   // scheme + credentials
        s = s.replaceFirst("^[^@/]+@([^:/]+):(?!\\d+/)", "$1/");        // scp-like user@host:path
        s = s.replaceFirst("^([^/:]+):\\d+/", "$1/");                   // host:port/
        s = s.replaceFirst("(\\.git)?/*$", "");                         // .git suffix, trailing slashes
        return s.toLowerCase(Locale.ROOT);
    }

    /** Last path segment, e.g. github.com/org/payments-svc → payments-svc. */
    public static String defaultName(String normalized) {
        int i = normalized.lastIndexOf('/');
        return i >= 0 ? normalized.substring(i + 1) : normalized;
    }
}
