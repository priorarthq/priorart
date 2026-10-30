package io.priorart.api;

import io.priorart.auth.Identity;
import io.priorart.domain.*;
import jakarta.servlet.http.HttpServletRequest;
import io.priorart.ingest.IngestService;
import io.priorart.ingest.SessionUpload;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {
    private final IngestService ingest;
    private final SessionRepository sessions;
    private final SessionTranscriptRepository transcripts;
    private final TeamRepository teams;
    private final RepoRepository repos;
    private final Identity identity;

    public SessionController(IngestService ingest, SessionRepository sessions, SessionTranscriptRepository transcripts,
                             TeamRepository teams, RepoRepository repos, Identity identity) {
        this.ingest = ingest;
        this.sessions = sessions;
        this.transcripts = transcripts;
        this.teams = teams;
        this.repos = repos;
        this.identity = identity;
    }

    /** Need-to-know: 404 (not 403) for sessions the caller may not see, so their existence isn't revealed either. */
    private Session visible(HttpServletRequest req, UUID id) {
        Session s = sessions.findById(id).orElseThrow(() -> new NoSuchElementException("session " + id));
        if (!identity.canSee(req, s.getUserId(), s.getAuthor(), s.getTeamId(), s.getRepoId())) throw new NoSuchElementException("session " + id);
        return s;
    }

    public record TranscriptInfo(String format, int bytes, int lines, String sha256, String sourcePath, Instant capturedAt) {}
    public record SessionView(UUID id, String agent, String externalId, String author, UUID userId,
                              UUID teamId, String team, UUID repoId, String repo, String repoName,
                              String cwd, String gitBranch, String headSha, boolean hadDiff,
                              Instant startedAt, Instant endedAt, int turns, String prompts, TranscriptInfo transcript,
                              List<String> warnings) {}

    private SessionView view(Session s, boolean detail) {
        String team = s.getTeamId() == null ? null : teams.findById(s.getTeamId()).map(Team::getSlug).orElse(null);
        String repoName = s.getRepoId() == null ? null : repos.findById(s.getRepoId()).map(Repo::getName).orElse(null);
        TranscriptInfo ti = !detail ? null : transcripts.findById(s.getId())
                .map(t -> new TranscriptInfo(t.getFormat(), t.getBytes(), t.getLines(), t.getSha256(), t.getSourcePath(), t.getCapturedAt()))
                .orElse(null);
        return new SessionView(s.getId(), s.getAgent(), s.getExternalId(), s.getAuthor(), s.getUserId(),
                s.getTeamId(), team, s.getRepoId(), s.getRepo(), repoName, s.getCwd(), s.getGitBranch(), s.getHeadSha(),
                s.isHadDiff(), s.getStartedAt(), s.getEndedAt(), s.getTurns(), detail ? s.getPrompts() : null, ti,
                detail ? io.priorart.safety.SecretScanner.dangers(java.util.Arrays.asList(s.getPrompts())) : List.of());
    }

    /** Called by the capture hook at agentStop / Stop — once per turn for Copilot. Idempotent on (agent, sessionId). */
    @PostMapping
    public ResponseEntity<Map<String, Object>> upload(@Valid @RequestBody SessionUpload up, HttpServletRequest req) {
        String author = identity.handle(req, up.author());
        IngestService.Result r = ingest.ingest(up, author == null ? "unknown" : author);
        Session s = r.session();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", s.getId());
        body.put("teamId", s.getTeamId());
        body.put("hadDiff", s.isHadDiff());
        body.put("turns", s.getTurns());
        body.put("transcriptChanged", r.transcriptChanged());
        body.put("willExtract", !s.isHadDiff() && s.getTurns() > 0);
        return ResponseEntity.ok(body);
    }

    @GetMapping
    public List<SessionView> recent(@RequestParam(defaultValue = "50") int limit, HttpServletRequest req) {
        return sessions.findAll(PageRequest.of(0, Math.min(limit, 200) * (identity.required() ? 10 : 1), Sort.by(Sort.Direction.DESC, "endedAt")))
                .stream().filter(s -> identity.canSee(req, s.getUserId(), s.getAuthor(), s.getTeamId(), s.getRepoId()))
                .limit(Math.min(limit, 200)).map(s -> view(s, false)).toList();
    }

    /** The caller's most recent session, preferably in the given directory. Used by the skill's "save". */
    @GetMapping("/latest")
    public SessionView latest(@RequestParam(required = false) String author, @RequestParam(required = false) String cwd, HttpServletRequest req) {
        String who = identity.handle(req, author);
        if (who == null) throw new IllegalArgumentException("author is required");
        Optional<Session> s = cwd == null || cwd.isBlank() ? Optional.empty()
                : sessions.findFirstByAuthorAndCwdOrderByEndedAtDesc(who, cwd);
        return view(s.or(() -> sessions.findFirstByAuthorOrderByEndedAtDesc(who))
                .orElseThrow(() -> new NoSuchElementException("no captured session for " + who + " yet")), true);
    }

    @GetMapping("/{id}")
    public SessionView get(@PathVariable UUID id, HttpServletRequest req) {
        return view(visible(req, id), true);
    }

    /** The agent's native transcript, verbatim. */
    @GetMapping("/{id}/transcript")
    public ResponseEntity<String> transcript(@PathVariable UUID id, HttpServletRequest req) {
        visible(req, id);
        SessionTranscript t = transcripts.findById(id).orElseThrow(() -> new NoSuchElementException("transcript " + id));
        return ResponseEntity.ok().contentType(new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8))
                .body(t.getContent());
    }
}
