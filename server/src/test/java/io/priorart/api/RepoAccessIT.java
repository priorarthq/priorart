package io.priorart.api;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * repo-access=git: people search across every repo they can read, and only those. A fake GitLab and a fake GitHub
 * decide who can read what; two teammates in the same team see different things because their git access differs.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "priorart.repo-access=git",
        "priorart.git.gitlab.host=gitlab.example",
        "priorart.git.gitlab.token=server-gl",
        "priorart.git.gitlab.client-id=gl-app",
        "priorart.git.github.token=server-gh",
        "priorart.git.github.client-id=gh-app",
        "priorart.extract.sweep-ms=3600000",
        "priorart.extract.sweep-initial-ms=3600000",
})
class RepoAccessIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static HttpServer git;

    /** One stub for both hosts: GitLab under /api/v4, GitHub's API at the root. */
    @DynamicPropertySource
    static void gitStub(DynamicPropertyRegistry r) throws IOException {
        git = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        git.createContext("/", ex -> {
            String path = ex.getRequestURI().getRawPath();
            String bearer = Optional.ofNullable(ex.getRequestHeaders().getFirst("Authorization")).orElse("").replace("Bearer ", "");
            String serverGl = Optional.ofNullable(ex.getRequestHeaders().getFirst("PRIVATE-TOKEN")).orElse("");
            String body = null;
            switch (path) {
                case "/api/v4/user" -> body = switch (bearer) {
                    case "gl-alice" -> "{\"id\":11,\"username\":\"alice\"}";
                    case "gl-bob" -> "{\"id\":12,\"username\":\"bob\"}";
                    default -> null;
                };
                case "/api/v4/projects/acme%2Fprivate-api" -> body = serverGl.equals("server-gl") ? "{\"visibility\":\"private\"}" : null;
                case "/api/v4/projects/acme%2Fpublic-lib" -> body = serverGl.equals("server-gl") ? "{\"visibility\":\"public\"}" : null;
                case "/api/v4/projects/acme%2Fprivate-api/members/all/11" -> body = "{\"access_level\":30,\"state\":\"active\"}";   // alice: developer
                case "/user" -> body = switch (bearer) {
                    case "gh-alice" -> "{\"id\":21,\"login\":\"alice-gh\"}";
                    case "gh-bob" -> "{\"id\":22,\"login\":\"bob-gh\"}";
                    default -> null;
                };
                case "/repos/acme/web" -> body = bearer.equals("server-gh") ? "{\"private\":true}" : null;
                case "/repos/acme/web/collaborators/bob-gh/permission" -> body = "{\"permission\":\"write\"}";   // bob only
                default -> {}
            }
            byte[] out = (body == null ? "{\"message\":\"Not Found\"}" : body).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(body == null ? (path.equals("/user") || path.equals("/api/v4/user") ? 401 : 404) : 200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        git.start();
        String base = "http://127.0.0.1:" + git.getAddress().getPort();
        r.add("priorart.git.gitlab.url", () -> base);
        r.add("priorart.git.github.api", () -> base);
    }

    @AfterAll
    static void stop() { git.stop(0); }

    @Autowired TestRestTemplate http;
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {};
    private static final String PRIVATE = "https://gitlab.example/acme/private-api.git", PUBLIC = "https://gitlab.example/acme/public-lib.git",
            WEB = "https://github.com/acme/web.git";

    private <T> ResponseEntity<T> call(HttpMethod m, String url, String token, Object body, ParameterizedTypeReference<T> type) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) h.setBearerAuth(token);
        return http.exchange(url, m, new HttpEntity<>(body, h), type);
    }
    private ResponseEntity<Map<String, Object>> call(HttpMethod m, String url, String token, Object body) { return call(m, url, token, body, MAP); }

    private String claim(String handle) {
        return (String) call(HttpMethod.POST, "/api/users/claim", null, Map.of("handle", handle)).getBody().get("token");
    }

    private String capture(String token, String id, String remote, String prompt) {
        Map<String, Object> b = new HashMap<>(Map.of("agent", "claude-code", "sessionId", id, "author", "x", "cwd", "/w/" + id, "hadDiff", false, "repo", remote,
                "transcript", "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"" + prompt + "\"}}\n"
                        + "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":\"Root cause found.\"}}\n"));
        ResponseEntity<Map<String, Object>> r = call(HttpMethod.POST, "/api/sessions", token, b);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return (String) r.getBody().get("id");
    }

    @SuppressWarnings("unchecked")
    private Set<String> searchIds(String token, String q) {
        Map<String, Object> r = call(HttpMethod.GET, "/api/search?q=" + q + "&scope=all", token, null).getBody();
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> h : (List<Map<String, Object>>) r.get("sessions")) ids.add((String) h.get("id"));
        return ids;
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyoneSearchesAcrossTheReposTheyCanReadAndOnlyThose() {
        // the lead sets up one team over three repos and works in each
        String lead = claim("lead");
        call(HttpMethod.POST, "/api/onboard", lead, Map.of("application", "acme", "team", "core", "remoteUrl", PRIVATE, "create", true));
        call(HttpMethod.POST, "/api/onboard", lead, Map.of("application", "acme", "team", "core", "remoteUrl", PUBLIC));
        call(HttpMethod.POST, "/api/onboard", lead, Map.of("application", "acme", "team", "core", "remoteUrl", WEB));
        String sPrivate = capture(lead, "s-private", PRIVATE, "zebra cache invalidation in the private api");
        String sPublic = capture(lead, "s-public", PUBLIC, "zebra cache helper in the public lib");
        String sWeb = capture(lead, "s-web", WEB, "zebra cache banner on the web app");

        // alice and bob join the same team
        String alice = claim("alice"), bob = claim("bob");
        String teamId = null;
        for (Map<String, Object> app : call(HttpMethod.GET, "/api/applications", lead, null, LIST).getBody())
            for (Map<String, Object> t : (List<Map<String, Object>>) app.get("teams")) teamId = (String) t.get("id");
        for (String[] who : List.of(new String[] { alice, "alice" }, new String[] { bob, "bob" })) {
            call(HttpMethod.POST, "/api/onboard", who[0], Map.of("application", "acme", "team", "core"));
            String uid = null;
            for (Map<String, Object> u : call(HttpMethod.GET, "/api/users", lead, null, LIST).getBody())
                if (who[1].equals(u.get("handle"))) uid = (String) u.get("id");
            assertThat(call(HttpMethod.POST, "/api/teams/" + teamId + "/requests/" + uid + "/approve", lead, Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        // before linking a GitLab/GitHub account, nothing on those hosts is readable
        assertThat(searchIds(alice, "zebra cache")).isEmpty();
        Map<String, Object> hosts = call(HttpMethod.GET, "/api/git/hosts", alice, null).getBody();
        assertThat(hosts.get("mode")).isEqualTo("git");
        assertThat((List<?>) hosts.get("hosts")).hasSize(2);

        // linking verifies the sign-in token with the host and keeps only who it belongs to
        assertThat(call(HttpMethod.POST, "/api/git/identities", alice, Map.of("host", "gitlab.example", "token", "forged")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.POST, "/api/git/identities", alice, Map.of("host", "gitlab.example", "token", "gl-alice")).getBody().get("username")).isEqualTo("alice");
        call(HttpMethod.POST, "/api/git/identities", alice, Map.of("host", "github.com", "token", "gh-alice"));
        call(HttpMethod.POST, "/api/git/identities", bob, Map.of("host", "gitlab.example", "token", "gl-bob"));
        call(HttpMethod.POST, "/api/git/identities", bob, Map.of("host", "github.com", "token", "gh-bob"));
        assertThat(call(HttpMethod.POST, "/api/git/identities", bob, Map.of("host", "gitlab.example", "token", "gl-alice")).getStatusCode())
                .as("one Prior Art user per git account").isEqualTo(HttpStatus.CONFLICT);

        // alice: GitLab developer on private-api, no access to the GitHub web repo
        assertThat(searchIds(alice, "zebra cache")).containsExactlyInAnyOrder(sPrivate, sPublic);
        assertThat(call(HttpMethod.GET, "/api/sessions/" + sWeb, alice, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // bob: GitHub collaborator on web, not a member of private-api
        assertThat(searchIds(bob, "zebra cache")).containsExactlyInAnyOrder(sPublic, sWeb);
        assertThat(call(HttpMethod.GET, "/api/sessions/" + sPrivate, bob, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.exchange("/api/sessions/" + sPrivate + "/transcript", HttpMethod.GET, new HttpEntity<>(bearer(bob)), String.class).getStatusCode())
                .as("the raw JSONL too").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET, "/api/sessions/" + sWeb, bob, null).getStatusCode()).isEqualTo(HttpStatus.OK);

        // records follow their session's repo
        String record = (String) call(HttpMethod.POST, "/api/records/extract/" + sPrivate, lead, null).getBody().get("id");
        assertThat(call(HttpMethod.GET, "/api/records/" + record, alice, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, "/api/records/" + record, bob, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET, "/api/records", bob, null, LIST).getBody()).extracting(r -> r.get("id")).doesNotContain(record);
    }

    private HttpHeaders bearer(String token) { HttpHeaders h = new HttpHeaders(); h.setBearerAuth(token); return h; }
}
