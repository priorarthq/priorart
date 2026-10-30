package io.priorart.ingest;

import io.priorart.directory.RemoteUrls;
import io.priorart.directory.TeamResolver;
import io.priorart.directory.UserService;
import io.priorart.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Receives a transcript from a capture hook. Copilot's agentStop fires at the end of every turn, so the same session
 * arrives many times with a growing transcript: ingest is an idempotent upsert, and extraction is left to the
 * debounced sweep in ExtractionService rather than triggered here.
 */
@Service
public class IngestService {
    private static final Logger log = LoggerFactory.getLogger(IngestService.class);
    private final SessionRepository sessions;
    private final SessionTranscriptRepository transcripts;
    private final RepoRepository repos;
    private final UserService users;
    private final TeamResolver teams;
    private final Map<String, TranscriptAdapter> adapters;

    public IngestService(SessionRepository sessions, SessionTranscriptRepository transcripts, RepoRepository repos,
                         UserService users, TeamResolver teams, List<TranscriptAdapter> adapterList) {
        this.sessions = sessions;
        this.transcripts = transcripts;
        this.repos = repos;
        this.users = users;
        this.teams = teams;
        this.adapters = adapterList.stream().collect(Collectors.toMap(TranscriptAdapter::agent, Function.identity()));
    }

    public TranscriptAdapter adapterFor(String agent) {
        TranscriptAdapter a = adapters.get(agent);
        if (a == null) throw new IllegalArgumentException("unknown agent: " + agent + " (known: " + adapters.keySet() + ")");
        return a;
    }

    public record Result(Session session, boolean transcriptChanged) {}

    @Transactional
    /** @param author the authenticated caller's handle (never the upload's own author field when auth is on) */
    public Result ingest(SessionUpload up, String author) {
        TranscriptAdapter adapter = adapterFor(up.agent());
        User user = users.resolve(author);
        String remote = RemoteUrls.normalize(up.repo());
        UUID repoId = remote == null ? null : repos.findByRemoteUrl(remote).map(Repo::getId).orElse(null);

        Session s = sessions.findByAgentAndExternalId(up.agent(), up.sessionId()).orElseGet(Session::new);
        s.setAgent(up.agent());
        s.setExternalId(up.sessionId());
        s.setAuthor(user.getHandle());
        s.setUserId(user.getId());
        s.setRepo(remote);
        s.setRepoId(repoId);
        if (s.getTeamId() == null) s.setTeamId(teams.resolve(user.getId(), repoId));
        s.setCwd(up.cwd());
        s.setGitBranch(up.gitBranch());
        s.setHeadSha(up.headSha());
        s.setHadDiff(Boolean.TRUE.equals(up.hadDiff()));
        if (s.getStartedAt() == null) s.setStartedAt(up.startedAt());
        s.setEndedAt(up.endedAt() != null ? up.endedAt() : Instant.now());
        List<String> prompts = adapter.userPrompts(up.transcript());
        s.setPrompts(String.join("\n\n", prompts));
        s.setTurns(adapter.turns(up.transcript()));
        s = sessions.saveAndFlush(s);

        String sha = sha256(up.transcript());
        SessionTranscript t = transcripts.findById(s.getId()).orElse(null);
        boolean changed = t == null || !sha.equals(t.getSha256());
        if (changed) {
            if (t == null) { t = new SessionTranscript(); t.setSessionId(s.getId()); }
            t.setContent(up.transcript());
            t.setBytes(up.transcript().getBytes(StandardCharsets.UTF_8).length);
            t.setLines((int) up.transcript().lines().filter(l -> !l.isBlank()).count());
            t.setSha256(sha);
            t.setSourcePath(up.transcriptPath());
            t.setCapturedAt(Instant.now());
            transcripts.save(t);
        }
        log.info("ingested session {} agent={} user={} team={} repo={} turns={} hadDiff={} transcriptChanged={}",
                s.getId(), s.getAgent(), s.getAuthor(), s.getTeamId(), remote, s.getTurns(), s.isHadDiff(), changed);
        return new Result(s, changed);
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
