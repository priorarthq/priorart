package io.priorart.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end flows over HTTP against a real Postgres: capture, onboarding, rescoping, scoped search, extraction.
 * Each test uses its own handles/slugs/repos so tests don't depend on each other's data.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "priorart.extract.provider=none",
        "priorart.team-auth=off",                    // flows in the legacy open mode; TeamAuthIT covers the default
        "priorart.extract.sweep-ms=3600000",          // tests trigger extraction explicitly
        "priorart.extract.sweep-initial-ms=3600000",
})
class PriorArtFlowsIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};

    private Map<String, Object> get(String url) {
        ResponseEntity<Map<String, Object>> r = http.exchange(url, HttpMethod.GET, null, MAP);
        assertThat(r.getStatusCode().is2xxSuccessful()).as("GET %s -> %s %s", url, r.getStatusCode(), r.getBody()).isTrue();
        return r.getBody();
    }

    private ResponseEntity<Map<String, Object>> post(String url, Object body) {
        return http.exchange(url, HttpMethod.POST, new HttpEntity<>(body), MAP);
    }

    private Map<String, Object> ok(ResponseEntity<Map<String, Object>> r) {
        assertThat(r.getStatusCode().is2xxSuccessful()).as("%s %s", r.getStatusCode(), r.getBody()).isTrue();
        return r.getBody();
    }

    /** A Claude Code JSONL transcript: one user prompt per entry, each followed by an assistant reply. */
    private static String transcript(String... prompts) {
        StringBuilder sb = new StringBuilder();
        for (String p : prompts) {
            sb.append("{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"").append(p).append("\"}}\n");
            sb.append("{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":\"looking into it\"}}\n");
        }
        return sb.toString();
    }

    private Map<String, Object> capture(String sessionId, String author, String remote, boolean hadDiff, String transcript) {
        Map<String, Object> b = new HashMap<>();
        b.put("agent", "claude-code");
        b.put("sessionId", sessionId);
        b.put("author", author);
        b.put("cwd", "/work/" + sessionId);
        b.put("repo", remote);
        b.put("headSha", "abc1234def");
        b.put("hadDiff", hadDiff);
        b.put("transcript", transcript);
        return ok(post("/api/sessions", b));
    }

    private Map<String, Object> onboard(String handle, String remote, String app, String team, boolean create) {
        Map<String, Object> b = new HashMap<>();
        b.put("handle", handle);
        b.put("remoteUrl", remote);
        b.put("application", app);
        b.put("team", team);
        b.put("create", create);
        return ok(post("/api/onboard", b));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> hits(Map<String, Object> results, String tier) {
        return (List<Map<String, Object>>) results.get(tier);
    }

    private Set<String> authors(Map<String, Object> results, String tier) {
        return hits(results, tier).stream().map(h -> (String) h.get("author")).collect(Collectors.toSet());
    }

    // ---- tests --------------------------------------------------------------------------------------------------

    @Test
    void captureIsAnIdempotentUpsertPerSession() {
        Map<String, Object> first = capture("idem-1", "ina", null, false, transcript("why is the cache cold"));
        assertThat(first.get("transcriptChanged")).isEqualTo(true);

        Map<String, Object> grown = capture("idem-1", "ina", null, false, transcript("why is the cache cold", "and after restart?"));
        assertThat(grown.get("id")).isEqualTo(first.get("id"));
        assertThat(grown.get("transcriptChanged")).isEqualTo(true);
        assertThat(grown.get("turns")).isEqualTo(2);

        Map<String, Object> same = capture("idem-1", "ina", null, false, transcript("why is the cache cold", "and after restart?"));
        assertThat(same.get("transcriptChanged")).isEqualTo(false);

        Map<String, Object> session = get("/api/sessions/" + first.get("id"));
        @SuppressWarnings("unchecked") Map<String, Object> t = (Map<String, Object>) session.get("transcript");
        assertThat(t.get("format")).isEqualTo("jsonl");
        assertThat(t.get("lines")).isEqualTo(4);
        assertThat(http.getForObject("/api/sessions/" + first.get("id") + "/transcript", String.class))
                .contains("and after restart?");
    }

    @Test
    void onboardingRefusesUnknownSlugsWithoutCreateAndIsIdempotent() {
        onboard("olga", "git@github.com:acme/olga-svc.git", "onb-app", "onb-team", true);

        ResponseEntity<Map<String, Object>> typo = post("/api/onboard",
                Map.of("handle", "olga", "application", "onb-ap", "team", "onb-team"));
        assertThat(typo.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) typo.getBody().get("error")).contains("does not exist").contains("onb-app");

        Map<String, Object> again = onboard("olga", "https://github.com/ACME/olga-svc", "onb-app", "onb-team", false);
        assertThat(again.get("actions")).asList().containsExactly("nothing to do — already onboarded");

        ResponseEntity<Map<String, Object>> otherApp = post("/api/onboard", Map.of("handle", "olga",
                "remoteUrl", "git@github.com:acme/olga-svc.git", "application", "other-app", "create", true));
        assertThat(otherApp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) otherApp.getBody().get("error")).contains("already registered under application 'onb-app'");
    }

    @Test
    void workCapturedBeforeOnboardingIsAttachedToTheTeamAfterwards() {
        String remote = "git@github.com:acme/late-svc.git";
        Map<String, Object> s = capture("late-1", "lena", remote, false, transcript("late svc leaks memory overnight"));
        assertThat(s.get("teamId")).isNull();
        String recordId = (String) ok(post("/api/records/extract/" + s.get("id"), null)).get("id");

        onboard("lena", "https://github.com/acme/late-svc", "late-app", "late-team", true);

        assertThat(get("/api/sessions/" + s.get("id")).get("team")).isEqualTo("late-team");
        assertThat(get("/api/records/" + recordId).get("teamId")).isNotNull();
    }

    @Test
    void searchIsScopedByApplicationAndUnassignedWorkIsPrivateToItsAuthor() {
        onboard("sam", "git@github.com:acme/scope-billing.git", "scope-billing", "payments", true);
        onboard("tia", "git@github.com:acme/scope-coaching.git", "scope-coaching", "platform", true);
        capture("scope-s", "sam", "git@github.com:acme/scope-billing.git", false, transcript("zebrafish ledger mismatch in billing"));
        capture("scope-t", "tia", "git@github.com:acme/scope-coaching.git", false, transcript("zebrafish ledger mismatch in coaching"));
        capture("scope-u", "uma", null, false, transcript("zebrafish ledger mismatch somewhere"));

        Map<String, Object> asSam = get("/api/search?q=zebrafish ledger&user=sam&scope=app");
        assertThat(asSam.get("scope")).isEqualTo("app");
        assertThat(authors(asSam, "sessions")).as("uma's work is in no team: private").containsExactly("sam");

        Map<String, Object> everyone = get("/api/search?q=zebrafish ledger&user=sam&scope=all");
        assertThat(authors(everyone, "sessions")).as("all = every team, still not others' unassigned work").containsExactlyInAnyOrder("sam", "tia");

        Map<String, Object> asUma = get("/api/search?q=zebrafish ledger&user=uma&scope=all");
        assertThat(authors(asUma, "sessions")).as("authors still find their own unassigned work").contains("uma");

        Map<String, Object> stranger = get("/api/search?q=zebrafish ledger&user=nobody-here&scope=team");
        assertThat(stranger.get("scope")).as("unknown users fall back to everyone").isEqualTo("all");
    }

    @Test
    void searchNeverReturnsTheCallersOwnSessionAndReportsTermOverlap() {
        String remote = "git@github.com:acme/quokka-scheduler.git";
        onboard("vic", remote, "self-app", "self-team", true);
        onboard("wes", remote, "self-app", "self-team", false);
        capture("self-1", "vic", remote, false, transcript("quokka scheduler drops jobs after daylight saving"));
        capture("self-2", "wes", remote, false, transcript("quokka scheduler drops jobs after daylight saving change"));

        Map<String, Object> r = get("/api/search?q=quokka scheduler drops jobs&user=vic&scope=app&excludeSession=self-1");
        assertThat(authors(r, "sessions")).containsExactly("wes");
        assertThat((Integer) r.get("queryTerms")).isGreaterThanOrEqualTo(3);
        assertThat(hits(r, "sessions").get(0).get("matchedTerms")).isEqualTo(r.get("queryTerms"));

        Map<String, Object> noise = get("/api/search?q=please check this claude session&scope=all");
        assertThat(noise.get("queryTerms")).as("generic agent words carry no meaning").isEqualTo(0);
    }

    @Test
    void extractionDraftsWithoutAModelAndPromotionRecordsThePromoter() {
        Map<String, Object> s = capture("ext-1", "xena", null, false, transcript("why do pdf exports render blank fonts"));
        Map<String, Object> draft = ok(post("/api/records/extract/" + s.get("id"), null));
        assertThat(draft.get("status")).isEqualTo("draft");
        assertThat(draft.get("title")).isEqualTo("why do pdf exports render blank fonts");
        assertThat(draft.get("commitSha")).isEqualTo("abc1234def");

        Map<String, Object> promoted = ok(post("/api/records/" + draft.get("id") + "/promote",
                Map.of("by", "yuri", "decision", "bake fonts into the image")));
        assertThat(promoted.get("status")).isEqualTo("promoted");
        assertThat(promoted.get("promotedBy")).isNotNull();
        assertThat(promoted.get("decision")).isEqualTo("bake fonts into the image");

        Map<String, Object> r = get("/api/search?q=pdf blank fonts&user=xena&scope=all");
        assertThat(hits(r, "records").get(0).get("status")).isEqualTo("promoted");
        assertThat(hits(r, "sessions")).as("the record's own session isn't listed again").isEmpty();
    }

    @Test
    void aTeamServerDoesNotServeTheCommons() {
        assertThat(http.getForEntity("/api/commons/search?q=anything", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post("/api/commons/publishers", Map.of("handle", "someone")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aTeamRecordRemembersWhereItWasPublished() {
        Map<String, Object> s = capture("pubmark-1", "pia", null, false, transcript("why do webhook retries duplicate orders"));
        String id = (String) ok(post("/api/records/extract/" + s.get("id"), null)).get("id");
        Map<String, Object> r = ok(post("/api/records/" + id + "/published", Map.of("url", "https://priorart.dev/api/commons/records/abc")));
        assertThat(r.get("publishedUrl")).isEqualTo("https://priorart.dev/api/commons/records/abc");
        assertThat(r.get("publishedAt")).isNotNull();
    }

    @Test
    void draftsWithoutAModelComeFromTheConversation_andPromotionDropsDraftMarkers() {
        String t = "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"Why do webhook retries create duplicate orders?\\nThey started last week.\"}}\n"
                + "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"Looking at the handler.\"}]}}\n"
                + "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"Root cause: the handler is not idempotent, so a retried delivery inserts a second order. It is not the queue, which delivers at-least-once as documented.\"}]}}\n";
        Map<String, Object> s = capture("draft-1", "dora", null, false, t);
        Map<String, Object> d = ok(post("/api/records/extract/" + s.get("id"), null));
        assertThat(d.get("title")).isEqualTo("Why do webhook retries create duplicate orders?");
        assertThat(d.get("findings")).asList().anySatisfy(f -> assertThat((String) f).contains("not idempotent"));
        assertThat((String) d.get("decision")).startsWith("Root cause: the handler is not idempotent");
        assertThat(d.get("tags")).asList().containsExactly("auto-draft");

        Map<String, Object> p = ok(post("/api/records/" + d.get("id") + "/promote",
                Map.of("by", "dora", "tags", List.of("Webhooks", "idempotency"), "findings", List.of("Handler lacks an idempotency key"))));
        assertThat(p.get("tags")).asList().containsExactly("webhooks", "idempotency");
        assertThat(p.get("findings")).asList().containsExactly("Handler lacks an idempotency key");
    }

    @Test
    void onboardingTyposGetADidYouMean() {
        onboard("tom", "git@github.com:acme/typo-svc.git", "typo-app", "payments", true);
        ResponseEntity<Map<String, Object>> r = post("/api/onboard", Map.of("handle", "tom", "application", "typo-app", "team", "paymnets"));
        assertThat((String) r.getBody().get("error")).contains("did you mean 'payments'").contains("--create");
    }

    @Test
    void oversizedBodiesAndQueriesAreRejectedEarly() {
        assertThat(http.getForEntity("/api/search?q=" + "a".repeat(1200), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void lookupTellsTheSkillWhereTheUserStands() {
        onboard("zoe", "git@github.com:acme/look-svc.git", "look-app", "look-team", true);
        Map<String, Object> l = get("/api/lookup?handle=zoe&remoteUrl=https://github.com/acme/look-svc.git");
        assertThat(l.get("knownUser")).isEqualTo(true);
        @SuppressWarnings("unchecked") Map<String, Object> repo = (Map<String, Object>) l.get("repo");
        assertThat(repo.get("registered")).isEqualTo(true);
        assertThat(repo.get("application")).isEqualTo("look-app");
        assertThat(repo.get("teams")).asList().containsExactly("look-team");

        Map<String, Object> unknown = get("/api/lookup?handle=newbie&remoteUrl=git@github.com:acme/brand-new.git");
        assertThat(unknown.get("knownUser")).isEqualTo(false);
        @SuppressWarnings("unchecked") Map<String, Object> r2 = (Map<String, Object>) unknown.get("repo");
        assertThat(r2.get("registered")).isEqualTo(false);
        assertThat(r2.get("remoteUrl")).isEqualTo("github.com/acme/brand-new");
    }
}
