package io.priorart.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.priorart.domain.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether a person may read work (sessions, transcripts, records) from a repo. Search spans every repo a person may
 * read and leaves out the rest. priorart.repo-access decides how:
 * <ul>
 *   <li>{@code team} (default): members of a team the repo belongs to.</li>
 *   <li>{@code git}: the repo's git host decides. The server asks GitHub or GitLab, with its own read-only token, whether
 *       the person's linked account can read the repo; losing access there means losing it here. Repos on hosts that
 *       aren't configured fall back to the team rule.</li>
 * </ul>
 * Answers are cached for a few minutes; a host that can't be reached means no access (fail closed), briefly cached.
 */
@Component
public class RepoAccess {
    private static final Logger log = LoggerFactory.getLogger(RepoAccess.class);
    private static final Duration TTL = Duration.ofMinutes(10), ERROR_TTL = Duration.ofMinutes(1);

    /** A git host this server can check access on, and how a person signs in to it (device flow). */
    public record Host(String host, String provider, String clientId, String deviceCodeUrl, String tokenUrl, String scope) {}
    public record LinkedAccount(String host, String username) {}

    private final boolean gitMode;
    private final Map<String, Provider> providers = new LinkedHashMap<>();
    private final TeamMemberRepository members;
    private final TeamRepoRepository teamRepos;
    private final RepoRepository repos;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    @PersistenceContext private EntityManager em;

    private record Cached(boolean allowed, long until) {}

    public RepoAccess(@Value("${priorart.repo-access:team}") String mode,
                      @Value("${priorart.git.github.host:github.com}") String githubHost,
                      @Value("${priorart.git.github.api:https://api.github.com}") String githubApi,
                      @Value("${priorart.git.github.web:https://github.com}") String githubWeb,
                      @Value("${priorart.git.github.client-id:}") String githubClientId,
                      @Value("${priorart.git.github.token:}") String githubToken,
                      @Value("${priorart.git.gitlab.url:https://gitlab.com}") String gitlabUrl,
                      @Value("${priorart.git.gitlab.host:}") String gitlabHost,   // repos' host when it differs from the URL's
                      @Value("${priorart.git.gitlab.client-id:}") String gitlabClientId,
                      @Value("${priorart.git.gitlab.token:}") String gitlabToken,
                      TeamMemberRepository members, TeamRepoRepository teamRepos, RepoRepository repos) {
        this.gitMode = "git".equalsIgnoreCase(mode);
        this.members = members;
        this.teamRepos = teamRepos;
        this.repos = repos;
        if (!githubToken.isBlank()) providers.put(githubHost.toLowerCase(Locale.ROOT),
                new GitHub(githubHost.toLowerCase(Locale.ROOT), trim(githubApi), trim(githubWeb), githubClientId, githubToken));
        if (!gitlabToken.isBlank()) {
            String url = trim(gitlabUrl);
            String host = (gitlabHost.isBlank() ? URI.create(url).getHost() : gitlabHost).toLowerCase(Locale.ROOT);
            providers.put(host, new GitLab(host, url, gitlabClientId, gitlabToken));
        }
        if (gitMode && providers.isEmpty()) log.warn("repo-access=git but no git host token is configured: every repo uses the team rule");
    }

    public String mode() { return gitMode ? "git" : "team"; }

    /** Hosts people can link an account on (only in git mode; in team mode nothing is asked of them). */
    public List<Host> hosts() {
        return gitMode ? providers.values().stream().map(Provider::host).toList() : List.of();
    }

    public boolean canRead(User me, UUID repoId) {
        if (me == null || repoId == null) return false;
        Repo repo = repos.findById(repoId).orElse(null);
        if (repo == null) return false;
        Provider p = gitMode ? providers.get(hostOf(repo.getRemoteUrl())) : null;
        if (p == null) return teamRule(me, repoId);
        String key = me.getId() + "|" + repoId;
        Cached c = cache.get(key);
        if (c != null && c.until() > System.currentTimeMillis()) return c.allowed();
        String account = accountOn(me.getId(), p.host().host());
        boolean allowed;
        Duration ttl = TTL;
        if (account == null) allowed = false;   // no linked account on this host: nothing to check against
        else try { allowed = p.canRead(pathOf(repo.getRemoteUrl()), account); }
        catch (Exception e) {
            log.warn("access check on {} failed for {}: {}", p.host().host(), repo.getRemoteUrl(), e.toString());
            allowed = false;
            ttl = ERROR_TTL;
        }
        cache.put(key, new Cached(allowed, System.currentTimeMillis() + ttl.toMillis()));
        return allowed;
    }

    /** True when a check for this repo needs the person to link an account they haven't linked yet. */
    public String missingAccountFor(User me, String remoteUrl) {
        if (!gitMode || me == null || remoteUrl == null) return null;
        Provider p = providers.get(hostOf(remoteUrl));
        return p != null && accountOn(me.getId(), p.host().host()) == null ? p.host().host() : null;
    }

    private boolean teamRule(User me, UUID repoId) {
        Set<UUID> mine = new HashSet<>(members.teamIdsOf(me.getId()));
        return teamRepos.teamIdsOf(repoId).stream().anyMatch(mine::contains);
    }

    // ---- linking an account ----------------------------------------------------------------------------------

    /** Verifies a sign-in token from the host's device flow, remembers who it belongs to, and forgets the token. */
    @Transactional
    public LinkedAccount link(User me, String host, String token) {
        Provider p = providers.get(host == null ? "" : host.trim().toLowerCase(Locale.ROOT));
        if (!gitMode || p == null) throw new IllegalArgumentException("this server doesn't check access on " + host);
        if (token == null || token.isBlank()) throw new IllegalArgumentException("token is required");
        String[] who;
        try { who = p.whoAmI(token.trim()); }
        catch (Exception e) { throw new Unauthorized(p.host().host() + " didn't accept that sign-in"); }
        @SuppressWarnings("unchecked")
        List<UUID> taken = em.createNativeQuery("select user_id from git_identities where host = :h and external_id = :x")
                .setParameter("h", p.host().host()).setParameter("x", who[0]).getResultList();
        if (!taken.isEmpty() && !taken.get(0).equals(me.getId()))
            throw new Identity.Conflict("that " + p.host().host() + " account is already linked to another Prior Art user");
        em.createNativeQuery("""
                insert into git_identities (user_id, host, external_id, username) values (:u, :h, :x, :n)
                on conflict (user_id, host) do update set external_id = excluded.external_id, username = excluded.username, verified_at = now()""")
                .setParameter("u", me.getId()).setParameter("h", p.host().host()).setParameter("x", who[0]).setParameter("n", who[1])
                .executeUpdate();
        cache.keySet().removeIf(k -> k.startsWith(me.getId() + "|"));
        return new LinkedAccount(p.host().host(), who[1]);
    }

    @SuppressWarnings("unchecked")
    public List<LinkedAccount> linked(User me) {
        List<Object[]> rows = em.createNativeQuery("select host, username from git_identities where user_id = :u order by host")
                .setParameter("u", me.getId()).getResultList();
        return rows.stream().map(r -> new LinkedAccount((String) r[0], (String) r[1])).toList();
    }

    /** What the provider needs to check access: GitLab's numeric user id, GitHub's login. */
    private String accountOn(UUID userId, String host) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("select external_id, username from git_identities where user_id = :u and host = :h")
                .setParameter("u", userId).setParameter("h", host).getResultList();
        if (rows.isEmpty()) return null;
        return providers.get(host) instanceof GitLab ? (String) rows.get(0)[0] : (String) rows.get(0)[1];
    }

    // ---- providers -------------------------------------------------------------------------------------------

    private interface Provider {
        Host host();
        /** [external id, username] of the token's owner. */
        String[] whoAmI(String token) throws Exception;
        boolean canRead(String path, String account) throws Exception;
    }

    private final class GitHub implements Provider {
        private final Host host;
        private final String api, token;
        GitHub(String host, String api, String web, String clientId, String token) {
            this.host = new Host(host, "github", clientId, web + "/login/device/code", web + "/login/oauth/access_token", "");
            this.api = api;
            this.token = token;
        }
        public Host host() { return host; }
        public String[] whoAmI(String userToken) throws Exception {
            JsonNode u = get(api + "/user", "Bearer " + userToken, "Authorization").body();
            return new String[] { u.path("id").asText(), u.path("login").asText() };
        }
        public boolean canRead(String path, String login) throws Exception {
            Resp repo = get(api + "/repos/" + path, "Bearer " + token, "Authorization");
            if (repo.status() == 404) return false;
            if (!repo.body().path("private").asBoolean(true)) return true;   // public repo: anyone may read it
            Resp perm = get(api + "/repos/" + path + "/collaborators/" + enc(login) + "/permission", "Bearer " + token, "Authorization");
            if (perm.status() == 404) return false;
            return Set.of("admin", "maintain", "write", "triage", "read").contains(perm.body().path("permission").asText())
                    || Set.of("admin", "maintain", "write", "triage", "read").contains(perm.body().path("role_name").asText());
        }
    }

    private final class GitLab implements Provider {
        private final Host host;
        private final String url, token;
        GitLab(String host, String url, String clientId, String token) {
            this.host = new Host(host, "gitlab", clientId, url + "/oauth/authorize_device", url + "/oauth/token", "read_user");
            this.url = url;
            this.token = token;
        }
        public Host host() { return host; }
        public String[] whoAmI(String userToken) throws Exception {
            JsonNode u = get(url + "/api/v4/user", "Bearer " + userToken, "Authorization").body();
            return new String[] { u.path("id").asText(), u.path("username").asText() };
        }
        public boolean canRead(String path, String userId) throws Exception {
            String project = url + "/api/v4/projects/" + enc(path);
            Resp p = get(project, token, "PRIVATE-TOKEN");
            if (p.status() == 404) return false;
            String visibility = p.body().path("visibility").asText("private");
            if (visibility.equals("public") || visibility.equals("internal")) return true;   // internal: any signed-in user
            Resp m = get(project + "/members/all/" + enc(userId), token, "PRIVATE-TOKEN");
            return m.status() == 200 && m.body().path("access_level").asInt(0) >= 20 && !"blocked".equals(m.body().path("state").asText());
        }
    }

    private record Resp(int status, JsonNode body) {}

    /** GET as JSON. 404 is an answer (not found / no access); other failures are errors. */
    private Resp get(String url, String credential, String header) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                .header(header, credential).header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 404) return new Resp(404, mapper.createObjectNode());
        if (r.statusCode() >= 300) throw new IllegalStateException(url + " -> " + r.statusCode());
        return new Resp(r.statusCode(), mapper.readTree(r.body()));
    }

    private static String hostOf(String remote) {
        int i = remote == null ? -1 : remote.indexOf('/');
        return i < 0 ? "" : remote.substring(0, i).toLowerCase(Locale.ROOT);
    }

    private static String pathOf(String remote) { return remote.substring(remote.indexOf('/') + 1); }
    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static String trim(String u) { return u.replaceAll("/+$", ""); }
}
