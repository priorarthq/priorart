package io.priorart.search;

import io.priorart.directory.ScopingService.Resolved;
import io.priorart.directory.ScopingService.Scope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Two-tier search. Tier 1: records (ranked, promoted first). Tier 2 "zero-record" hits: raw user prompts of recent
 * sessions, so a teammate's session from this morning is findable before anyone extracts anything.
 * Scoped by team: rows in scope, plus unassigned rows (team_id null), which every scope includes.
 */
@Service
public class SearchService {
    @PersistenceContext private EntityManager em;

    /**
     * matchedTerms: how many of the query's meaningful terms (see {@link #terms}) the hit contains. warnings: risky
     * suggestions in it (same checks as public records), so a hint injected into a teammate's prompt can say so.
     */
    public record RecordHit(UUID id, String title, String status, String author, String team, String decision,
                            Instant createdAt, String commitSha, UUID sessionId, double rank, int matchedTerms, List<String> warnings) {}
    public record SessionHit(UUID id, String agent, String author, String team, String cwd, Instant endedAt,
                             boolean hadDiff, String snippet, double rank, int matchedTerms, List<String> warnings) {}
    /** queryTerms: number of meaningful terms in the query, so callers can judge overlap (matchedTerms / queryTerms). */
    public record Results(String query, String scope, int queryTerms, List<RecordHit> records, List<SessionHit> sessions) {}

    /**
     * Words that appear in almost every agent session and say nothing about the task. They still take part in matching
     * but are not counted as overlap, so "claude session work" doesn't make two unrelated sessions look related.
     * Stemmed forms, as produced by the english text-search config.
     */
    private static final Set<String> NOISE = Set.of(
            "claud", "copilot", "codex", "cursor", "agent", "session", "ai", "llm", "pleas", "help", "want", "need",
            "make", "use", "let", "okay", "ok", "yes", "thing", "like", "work", "look", "tri", "check", "right", "well",
            "also", "get", "go", "know", "think", "mine", "now", "one", "would", "could", "can", "see", "way");
    private static final java.util.regex.Pattern SAFE_TERM = java.util.regex.Pattern.compile("^[a-z0-9][a-z0-9._-]{0,62}$");

    private static final String STRICT = "websearch_to_tsquery('english', :q)";
    private static final String RELAXED = "replace(plainto_tsquery('english', :q)::text, '&', '|')::tsquery";

    /**
     * Per tier: strict first (all words must match), then relaxed (any word) if strict found nothing. Done per tier so
     * a strict session hit can't hide a record that only shares some words with the query.
     */
    /**
     * @param excludeSession the caller's own session (the agent's session id), so a search made from inside a session
     *                       never returns that session or its record — otherwise the current task always matches itself.
     */
    /**
     * @param viewer the searcher's handle. Work with no team (sessions from repos nobody onboarded, often personal
     *               projects) is private: only its own author ever sees it, in any scope.
     */
    @Transactional(readOnly = true)
    public Results search(String q, int limit, Resolved scope, String viewer, String excludeSession,
                          java.util.function.Predicate<UUID> readableRepo) {
        // A scope with an empty team set means "no teams": only the viewer's own work (never "unscoped").
        Filter f = new Filter(scope.scope() != Scope.ALL && scope.teamIds() != null, scope,
                viewer == null || viewer.isBlank() ? null : viewer.trim(),
                excludeSession == null || excludeSession.isBlank() ? null : excludeSession.trim(), terms(q), readableRepo);
        // Records always use the relaxed tier, ordered by overlap: a draft that repeats the question word for word must
        // not hide the promoted answer that shares most (not all) of its words.
        List<RecordHit> rh = records(q, limit, RELAXED, f);
        List<SessionHit> sh = sessions(q, limit, STRICT, f);
        if (sh.isEmpty()) sh = sessions(q, limit, RELAXED, f);
        // A session whose record is already in the results is the same knowledge twice; show the record only.
        Set<UUID> covered = new HashSet<>();
        for (RecordHit r : rh) covered.add(r.sessionId());
        sh = sh.stream().filter(x -> !covered.contains(x.id())).toList();
        return new Results(q, scope.scope().name().toLowerCase(), f.terms().size(), rh, sh);
    }

    /** The query's distinct stemmed lexemes minus {@link #NOISE}; only shapes that are safe to bind back are kept. */
    @SuppressWarnings("unchecked")
    private List<String> terms(String q) {
        Object arr = em.createNativeQuery("select tsvector_to_array(to_tsvector('english', :q))")
                .setParameter("q", q).getSingleResult();
        String[] lex = arr instanceof String[] a ? a : new String[0];
        List<String> out = new ArrayList<>();
        for (String l : lex) if (!NOISE.contains(l) && SAFE_TERM.matcher(l).matches()) out.add(l);
        return out;
    }

    /** SQL for "how many query terms does this row's search_vec contain". Terms are pre-stemmed, so match them unstemmed. */
    private static String matchedSql(Filter f) {
        return f.terms().isEmpty() ? "0"
                : "(select count(*) from unnest(cast(:terms as text[])) t(lex) where x.search_vec @@ plainto_tsquery('simple', t.lex))";
    }

    /**
     * For sessions: the overlap of the single best-matching prompt. A long session mentions almost every word somewhere,
     * so counting across all its prompts made unrelated marathon sessions look like strong matches.
     */
    private static String sessionMatchedSql(Filter f) {
        return f.terms().isEmpty() ? "0" : """
                (select coalesce(max(c), 0) from (
                   select (select count(*) from unnest(cast(:terms as text[])) t(lex) where v.vec @@ plainto_tsquery('simple', t.lex)) as c
                   from regexp_split_to_table(x.prompts, E'\\n\\n') p, lateral (select to_tsvector('english', p) as vec) v
                 ) z)""";
    }

    private record Filter(boolean scoped, Resolved scope, String viewer, String excludeSession, List<String> terms,
                          java.util.function.Predicate<UUID> readableRepo) {
        /** The viewer's own work, or work from a repo they may read (checked after the query: see RepoAccess). */
        boolean readable(String author, UUID repoId) {
            return (viewer != null && viewer.equals(author)) || (repoId != null && readableRepo.test(repoId));
        }

        /** Team rows in scope, plus the viewer's own unassigned rows. Nobody else's unassigned rows, ever. */
        String visibility() {
            String own = viewer != null ? " or (x.team_id is null and x.author = :viewer)" : "";
            return scoped ? " and (x.team_id = any(cast(:ids as uuid[]))" + own + ")"
                          : " and (x.team_id is not null" + own + ")";
        }
        String recordSql() {
            return visibility() + (excludeSession != null ? " and x.session_id not in (select id from sessions where external_id = :ex)" : "");
        }
        String sessionSql() {
            return visibility() + (excludeSession != null ? " and x.external_id <> :ex" : "");
        }
    }

    private List<RecordHit> records(String q, int limit, String tsq, Filter f) {
        List<RecordHit> rh = new ArrayList<>();
        Query rq = em.createNativeQuery("""
                select x.id, x.title, x.status, x.author, t.slug, x.decision, x.created_at, x.commit_sha, x.session_id,
                       ts_rank(x.search_vec, %1$s) * (case x.status when 'promoted' then 2 else 1 end) as rank,
                       %3$s as matched, sx.repo_id
                from records x left join teams t on t.id = x.team_id left join sessions sx on sx.id = x.session_id
                where x.status not in ('rejected') and x.search_vec @@ %1$s %2$s
                order by matched desc, rank desc, x.created_at desc limit :lim""".formatted(tsq, f.recordSql(), matchedSql(f)));
        bind(rq, q, overfetch(limit), f);
        for (Object row : rq.getResultList()) {
            Object[] r = (Object[]) row;
            if (!f.readable((String) r[3], (UUID) r[11])) continue;
            if (rh.size() >= limit) break;
            rh.add(new RecordHit((UUID) r[0], (String) r[1], (String) r[2], (String) r[3], (String) r[4], (String) r[5],
                    toInstant(r[6]), (String) r[7], (UUID) r[8], ((Number) r[9]).doubleValue(), ((Number) r[10]).intValue(),
                    io.priorart.safety.SecretScanner.dangers(java.util.Arrays.asList((String) r[1], (String) r[5]))));
        }
        return rh;
    }

    private List<SessionHit> sessions(String q, int limit, String tsq, Filter f) {
        List<SessionHit> sh = new ArrayList<>();
        Query sq = em.createNativeQuery("""
                select x.id, x.agent, x.author, t.slug, x.cwd, x.ended_at, x.had_diff,
                       ts_headline('english', x.prompts, %1$s, 'MaxWords=40, MinWords=15'),
                       ts_rank(x.search_vec, %1$s) as rank,
                       %3$s as matched, left(x.prompts, 20000), x.repo_id
                from sessions x left join teams t on t.id = x.team_id
                where x.search_vec @@ %1$s %2$s
                order by matched desc, rank desc, x.ended_at desc limit :lim""".formatted(tsq, f.sessionSql(), sessionMatchedSql(f)));
        bind(sq, q, overfetch(limit), f);
        for (Object row : sq.getResultList()) {
            Object[] r = (Object[]) row;
            if (!f.readable((String) r[2], (UUID) r[11])) continue;
            if (sh.size() >= limit) break;
            sh.add(new SessionHit((UUID) r[0], (String) r[1], (String) r[2], (String) r[3], (String) r[4],
                    toInstant(r[5]), (Boolean) r[6], (String) r[7], ((Number) r[8]).doubleValue(), ((Number) r[9]).intValue(),
                    io.priorart.safety.SecretScanner.dangers(java.util.Arrays.asList((String) r[10]))));
        }
        return sh;
    }

    /** Rows from repos the viewer can't read are dropped after the query, so ask for more than will be shown. */
    private static int overfetch(int limit) { return Math.min(limit * 5, 250); }

    private void bind(Query query, String q, int limit, Filter f) {
        query.setParameter("q", q).setParameter("lim", limit);
        if (f.scoped()) query.setParameter("ids",
                f.scope().teamIds().stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}")));
        if (f.excludeSession() != null) query.setParameter("ex", f.excludeSession());
        if (f.viewer() != null) query.setParameter("viewer", f.viewer());
        if (!f.terms().isEmpty()) query.setParameter("terms", String.join(",", f.terms()).transform(t -> "{" + t + "}"));
    }

    private Instant toInstant(Object o) {
        if (o instanceof Instant i) return i;
        if (o instanceof java.sql.Timestamp t) return t.toInstant();
        if (o instanceof java.time.OffsetDateTime d) return d.toInstant();
        return null;
    }
}
