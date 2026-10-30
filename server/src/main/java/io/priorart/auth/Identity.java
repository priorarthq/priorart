package io.priorart.auth;

import io.priorart.directory.UserService;
import io.priorart.domain.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

/**
 * Who is calling, and what they may see. With team-auth=required the caller is the user whose token came with the
 * request (set by {@link TeamAuthFilter}); names in request bodies are ignored. With team-auth=off (single-user local
 * setups) the caller is whoever the request says, as before.
 */
@Component
public class Identity {
    public static final String ATTR = "priorart.user";

    private final boolean required;
    private final UserRepository users;
    private final UserService userService;
    private final TeamMemberRepository members;
    private final SecureRandom random = new SecureRandom();

    private final String claimCode;
    private final RepoAccess access;

    public Identity(@Value("${priorart.team-auth:required}") String mode, @Value("${priorart.claim-code:}") String claimCode,
                    UserRepository users, UserService userService, TeamMemberRepository members, RepoAccess access) {
        this.access = access;
        this.required = !"off".equalsIgnoreCase(mode);
        this.claimCode = claimCode == null ? "" : claimCode.trim();
        this.users = users;
        this.userService = userService;
        this.members = members;
    }

    public boolean required() { return required; }

    /** The caller's handle: from the token when auth is required, otherwise the claimed name (may be null). */
    public String handle(HttpServletRequest req, String claimed) {
        if (required) return ((User) req.getAttribute(ATTR)).getHandle();
        return claimed == null || claimed.isBlank() ? null : claimed.trim();
    }

    /** The caller as a user (auto-provisioned in off mode); null only in off mode without a name. */
    public User user(HttpServletRequest req, String claimed) {
        if (required) return (User) req.getAttribute(ATTR);
        String h = handle(req, claimed);
        return h == null ? null : userService.resolve(h);
    }

    public Set<UUID> teamsOf(User u) {
        return u == null ? Set.of() : new HashSet<>(members.teamIdsOf(u.getId()));
    }

    /**
     * Same rule as search. Your own work: always. Someone else's: only if they shared it (it's filed under a team) and
     * you may read its repo (see {@link RepoAccess}: a team the repo belongs to, or the git host's own permissions), and
     * you're approved into a team. Work outside any repo, or not shared: only its author.
     */
    public boolean canSee(HttpServletRequest req, UUID ownerUserId, String ownerHandle, UUID teamId, UUID repoId) {
        if (!required) return true;
        User me = (User) req.getAttribute(ATTR);
        if (me.getId().equals(ownerUserId) || me.getHandle().equals(ownerHandle)) return true;
        return teamId != null && repoId != null && !teamsOf(me).isEmpty() && access.canRead(me, repoId);
    }

    /** For search: may the caller read work from this repo? (Own work is let through separately.) */
    public java.util.function.Predicate<UUID> readableRepos(HttpServletRequest req) {
        if (!required) return r -> true;
        User me = (User) req.getAttribute(ATTR);
        if (teamsOf(me).isEmpty()) return r -> false;
        Map<UUID, Boolean> seen = new HashMap<>();
        return repoId -> repoId != null && seen.computeIfAbsent(repoId, r -> access.canRead(me, r));
    }

    /** Curating knowledge (promote, reject, mark stale, re-extract): its author or a member of its team. */
    public boolean canChange(HttpServletRequest req, String ownerHandle, UUID teamId) {
        if (!required) return true;
        User me = (User) req.getAttribute(ATTR);
        return me.getHandle().equals(ownerHandle) || (teamId != null && teamsOf(me).contains(teamId));
    }

    /** True when the caller is approved into at least one team (in off mode: always). */
    public boolean isInsider(HttpServletRequest req) {
        return !required || !teamsOf((User) req.getAttribute(ATTR)).isEmpty();
    }

    public record Claim(String handle, String token) {}

    /** Claims a handle (case-insensitive) and returns its token once. A claimed handle can't be claimed again. */
    public Claim claim(String handle, String code) {
        if (!claimCode.isEmpty() && (code == null || !java.security.MessageDigest.isEqual(
                claimCode.getBytes(StandardCharsets.UTF_8), code.trim().getBytes(StandardCharsets.UTF_8))))
            throw new ClaimCodeRequired();
        String h = handle == null ? "" : handle.trim().toLowerCase(java.util.Locale.ROOT);
        if (!h.matches("^[A-Za-z0-9][A-Za-z0-9._-]{1,62}$"))
            throw new IllegalArgumentException("handle must be 2-63 letters, digits, '.', '_' or '-'");
        User u = userService.resolve(h);
        if (u.getTokenSha256() != null) throw new Conflict("handle '" + h + "' is already claimed. If it's yours, copy your token from your other machine (priorart.mjs config --token …); otherwise pick another handle");
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = "pt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        u.setTokenSha256(sha256(token));
        u.setClaimedAt(Instant.now());
        users.save(u);
        return new Claim(u.getHandle(), token);
    }

    public Optional<User> byToken(String token) {
        return token == null || token.isBlank() ? Optional.empty() : users.findByTokenSha256(sha256(token.trim()));
    }

    public static class ClaimCodeRequired extends RuntimeException {
        public ClaimCodeRequired() { super("this server needs a claim code to sign up (ask your team; the skill reads it from .priorart.json \"claimCode\" or config --claim-code)"); }
    }

    public static class Conflict extends RuntimeException {
        public Conflict(String m) { super(m); }
    }

    static String sha256(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
