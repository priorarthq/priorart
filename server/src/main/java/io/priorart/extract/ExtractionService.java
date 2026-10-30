package io.priorart.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.priorart.domain.Record;
import io.priorart.domain.RecordRepository;
import io.priorart.domain.Session;
import io.priorart.domain.SessionRepository;
import io.priorart.domain.SessionTranscript;
import io.priorart.domain.SessionTranscriptRepository;
import io.priorart.ingest.TranscriptAdapter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Turns a no-diff session into a draft record. Drafts are never gated: they exist as soon as the session goes quiet;
 * a human promotes them. A draft is re-extracted when its transcript grows; a promoted record only on explicit request.
 */
@Service
public class ExtractionService {
    private static final Logger log = LoggerFactory.getLogger(ExtractionService.class);
    private static final String SYSTEM = """
        You extract a structured investigation record from an AI coding-agent session transcript.
        Return ONLY a JSON object with keys:
          title (<= 90 chars, specific, names the system/symptom),
          kind ("investigation" | "spike" | "review" | "implementation"),
          question (what the user was trying to find out or do),
          findings (array of short factual sentences, each something the session established),
          evidence (array of {claim, turn, tool, detail}; turn = the [turn N] number where it was established; tool = tool name or null),
          decision (what was concluded or done, and why; "" if none),
          outcome ("resolved" | "partial" | "abandoned" | "unknown"),
          tags (array of lowercase keywords: services, components, error types),
          touched_files (array of file paths that were read or edited).
        Prefer negative results ("X is not the cause because Y") — they are the most valuable. Be concrete. No prose outside the JSON.
        """;

    @PersistenceContext private EntityManager em;
    private final SessionRepository sessions;
    private final SessionTranscriptRepository transcripts;
    private final RecordRepository records;
    private final LlmClient llm;
    private final ExtractProperties props;
    private final Map<String, TranscriptAdapter> adapters;
    private final ObjectMapper mapper = new ObjectMapper();
    /** Sessions whose extraction failed, with when to try next: exponential backoff, so a down model endpoint isn't hammered. */
    private final Map<UUID, long[]> backoff = new java.util.concurrent.ConcurrentHashMap<>(); // {failures, nextAttemptMillis}

    public ExtractionService(SessionRepository sessions, SessionTranscriptRepository transcripts, RecordRepository records,
                             LlmClient llm, ExtractProperties props, List<TranscriptAdapter> adapterList) {
        this.sessions = sessions;
        this.transcripts = transcripts;
        this.records = records;
        this.llm = llm;
        this.props = props;
        this.adapters = adapterList.stream().collect(Collectors.toMap(TranscriptAdapter::agent, Function.identity()));
    }

    /** Sessions that ended with no code change, have been quiet long enough, and have no up-to-date draft. */
    @Scheduled(fixedDelayString = "${priorart.extract.sweep-ms:60000}", initialDelayString = "${priorart.extract.sweep-initial-ms:15000}")
    public void sweep() {
        @SuppressWarnings("unchecked")
        List<UUID> due = em.createNativeQuery("""
                select s.id from sessions s
                join session_transcripts t on t.session_id = s.id
                left join records r on r.session_id = s.id
                where not s.had_diff and s.turns > 0
                  and t.captured_at < now() - make_interval(secs => :quiet)
                  and (r.id is null or (r.status = 'draft' and r.transcript_sha256 is distinct from t.sha256))
                order by t.captured_at limit 20""")
                .setParameter("quiet", props.quietSeconds()).getResultList();
        long now = System.currentTimeMillis();
        for (UUID id : due) {
            long[] b = backoff.get(id);
            if (b != null && now < b[1]) continue;
            try {
                extract(id, false);
                backoff.remove(id);
            } catch (Exception e) {
                long failures = b == null ? 1 : b[0] + 1;
                long delay = Math.min(3_600_000L, 30_000L << Math.min(failures - 1, 7)); // 30s, 1m, 2m … capped at 1h
                backoff.put(id, new long[]{failures, now + delay});
                log.warn("extraction failed for session {} (attempt {}, next in {}s): {}", id, failures, delay / 1000, e.toString());
            }
        }
    }

    /** @param force re-extract even if the record is promoted or the transcript is unchanged (the UI's "Re-extract"). */
    public Record extract(UUID sessionId, boolean force) {
        Session s = sessions.findById(sessionId).orElseThrow(() -> new NoSuchElementException("session " + sessionId));
        SessionTranscript t = transcripts.findById(sessionId).orElseThrow(() -> new NoSuchElementException("transcript " + sessionId));
        Record existing = records.findBySessionId(sessionId).stream().findFirst().orElse(null);
        if (existing != null && !force
                && (!"draft".equals(existing.getStatus()) || t.getSha256().equals(existing.getTranscriptSha256()))) {
            return existing;
        }
        String rendered = adapters.get(s.getAgent()).renderForExtraction(t.getContent(), props.maxTranscriptChars());
        TranscriptAdapter adapter = adapters.get(s.getAgent());
        Record fresh = llm.enabled() ? fromLlm(s, rendered, adapter, t.getContent()) : heuristic(s, adapter, t.getContent());
        Record r = existing == null ? fresh : copyContent(fresh, existing);
        r.setSessionId(s.getId());
        r.setTeamId(s.getTeamId());
        r.setAuthor(s.getAuthor());
        r.setCommitSha(s.getHeadSha());
        r.setTranscriptSha256(t.getSha256());
        r = records.save(r);
        log.info("{} record {} for session {} via {}", existing == null ? "drafted" : "re-extracted", r.getId(), s.getId(),
                llm.enabled() ? "llm" : "heuristic");
        return r;
    }

    private Record copyContent(Record from, Record to) {
        to.setTitle(from.getTitle());
        to.setKind(from.getKind());
        to.setQuestion(from.getQuestion());
        to.setFindings(from.getFindings());
        to.setEvidence(from.getEvidence());
        to.setDecision(from.getDecision());
        to.setOutcome(from.getOutcome());
        to.setTags(from.getTags());
        to.setTouchedFiles(from.getTouchedFiles());
        return to;
    }

    private Record fromLlm(Session s, String rendered, TranscriptAdapter adapter, String raw) {
        String out = llm.complete(SYSTEM, "Session by " + s.getAuthor() + " in " + s.getCwd() + ":\n\n" + rendered);
        String json = out.trim();
        int a = json.indexOf('{'), b = json.lastIndexOf('}');
        if (a >= 0 && b > a) json = json.substring(a, b + 1);
        try {
            JsonNode n = mapper.readTree(json);
            Record r = new Record();
            r.setTitle(n.path("title").asText("Untitled investigation"));
            r.setKind(n.path("kind").asText("investigation"));
            r.setQuestion(n.path("question").asText(""));
            r.setFindings(strings(n.path("findings")));
            List<Map<String, Object>> ev = new ArrayList<>();
            for (JsonNode e : n.path("evidence")) ev.add(mapper.convertValue(e, Map.class));
            r.setEvidence(ev);
            r.setDecision(n.path("decision").asText(""));
            r.setOutcome(n.path("outcome").asText("unknown"));
            r.setTags(strings(n.path("tags")).toArray(String[]::new));
            r.setTouchedFiles(strings(n.path("touched_files")).toArray(String[]::new));
            return r;
        } catch (Exception e) {
            log.warn("LLM returned non-JSON; falling back to heuristic. output={}", out);
            return heuristic(s, adapter, raw);
        }
    }

    private static final java.util.regex.Pattern CUE = java.util.regex.Pattern.compile(
            "(?i)\\b(root cause|because|caused by|is not|isn't|not the|ruled out|the fix|fix:|fixed|so the|which means|turns out|the problem)\\b");

    /**
     * No model configured: a plain draft from the conversation itself. Title = the first prompt's first line; findings =
     * the sentences of the final replies that state a cause or rule one out; decision = the last reply's opening. Marked
     * "auto-draft" so people know to check it before promoting.
     */
    private static final java.util.regex.Pattern FILLER = java.util.regex.Pattern.compile(
            "(?i)^(understood|sure|got it|okay|ok|great|thanks|thank you|no changes (were )?made|here'?s (a|the) summary|summary)\\b[^.]{0,40}[.!:]?$");
    private static final java.util.regex.Pattern BULLET = java.util.regex.Pattern.compile("^\\s*(?:[-*•]|\\d+[.)])\\s+");

    /** Sentences of a reply, one per line item, without list markers or markdown emphasis. */
    static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\\n+"))
            for (String sentence : BULLET.matcher(line).replaceFirst("").replace("**", "").split("(?<=[.!?])\\s+")) {
                String t = BULLET.matcher(sentence).replaceFirst("").trim();
                if (!t.isEmpty()) out.add(t);
            }
        return out;
    }

    static Record heuristic(Session s, TranscriptAdapter adapter, String raw) {
        Record r = new Record();
        String first = s.getPrompts().split("\n\n")[0].trim();
        String firstLine = first.lines().findFirst().orElse("").trim();
        r.setTitle(firstLine.isEmpty() ? "Untitled session" : firstLine.length() > 90 ? firstLine.substring(0, 87) + "…" : firstLine);
        r.setQuestion(first.length() > 2000 ? first.substring(0, 2000) + "…" : first);
        List<String> replies = adapter == null || raw == null ? List.of() : adapter.assistantTexts(raw);
        List<String> findings = new ArrayList<>();
        for (int i = Math.max(0, replies.size() - 3); i < replies.size(); i++)
            for (String sentence : sentences(replies.get(i)))
                if (sentence.length() >= 25 && sentence.length() <= 400 && CUE.matcher(sentence).find() && !findings.contains(sentence))
                    findings.add(sentence);
        if (findings.size() > 6) findings = findings.subList(findings.size() - 6, findings.size());
        r.setFindings(findings.isEmpty() ? List.of("(auto-draft: no findings detected; see the session)") : findings);
        // decision: the last reply's conclusion (sentences that state a cause, a fix or what was ruled out), not its
        // opening pleasantries; falls back to the reply itself without filler
        String last = replies.isEmpty() ? "" : replies.get(replies.size() - 1);
        List<String> conclusion = sentences(last).stream().filter(x -> CUE.matcher(x).find()).limit(3).toList();
        String decision = conclusion.isEmpty() ? String.join(" ", sentences(last).stream().filter(x -> !FILLER.matcher(x).find()).toList())
                                               : String.join(" ", conclusion);
        r.setDecision(decision.length() > 600 ? decision.substring(0, 597) + "…" : decision);
        r.setTags(new String[]{"auto-draft"});
        return r;
    }

    private List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) out.add(n.asText());
        return out;
    }
}
