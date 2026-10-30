package io.priorart.api;

import io.priorart.auth.Identity;
import io.priorart.directory.UserService;
import io.priorart.domain.SessionRepository;
import jakarta.servlet.http.HttpServletRequest;
import io.priorart.domain.Record;
import io.priorart.domain.RecordRepository;
import io.priorart.extract.ExtractionService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/records")
public class RecordController {
    private final RecordRepository records;
    private final ExtractionService extraction;
    private final UserService users;
    private final SessionRepository sessions;
    private final Identity identity;

    public RecordController(RecordRepository records, ExtractionService extraction, UserService users,
                            SessionRepository sessions, Identity identity) {
        this.records = records;
        this.extraction = extraction;
        this.users = users;
        this.sessions = sessions;
        this.identity = identity;
    }

    private boolean visible(HttpServletRequest req, Record r) {
        UUID repo = r.getSessionId() == null ? null : sessions.findById(r.getSessionId()).map(io.priorart.domain.Session::getRepoId).orElse(null);
        return identity.canSee(req, null, r.getAuthor(), r.getTeamId(), repo);
    }

    /** Changing a record needs its author or a member of its team; otherwise 403 (it's visible, just not yours). */
    private Record changeable(HttpServletRequest req, UUID id) {
        Record r = find(req, id);
        if (!identity.canChange(req, r.getAuthor(), r.getTeamId()))
            throw new io.priorart.api.DirectoryController.Forbidden("only the author or a member of its team can change this record");
        return r;
    }

    /** Need-to-know: 404 for records the caller may not see. */
    private Record find(HttpServletRequest req, UUID id) {
        Record r = records.findById(id).orElseThrow(() -> new NoSuchElementException("record " + id));
        if (!visible(req, r)) throw new NoSuchElementException("record " + id);
        return r;
    }

    @GetMapping
    public List<Record> list(@RequestParam(required = false) String status, HttpServletRequest req) {
        return (status == null ? records.findAllByOrderByCreatedAtDesc() : records.findByStatusOrderByCreatedAtDesc(status))
                .stream().filter(r -> visible(req, r)).toList();
    }

    @GetMapping("/{id}")
    public Record get(@PathVariable UUID id, HttpServletRequest req) {
        Record r = find(req, id);
        r.setHits(r.getHits() + 1);
        return records.save(r);
    }

    /** The whole "approval gate": one call promotes a draft. Optional edits ride along; "by" is the promoter's handle. */
    @PostMapping("/{id}/promote")
    @Transactional
    public Record promote(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
        Record r = changeable(req, id);
        if (body != null) {
            if (body.get("title") instanceof String t && !t.isBlank()) r.setTitle(t.trim());
            if (body.get("decision") instanceof String d) r.setDecision(d);
            if (body.get("outcome") instanceof String o) r.setOutcome(o);
            if (body.get("findings") instanceof List<?> f && !f.isEmpty())
                r.setFindings(f.stream().map(String::valueOf).map(String::trim).filter(x -> !x.isEmpty()).toList());
            if (body.get("tags") instanceof List<?> tg && !tg.isEmpty())
                r.setTags(tg.stream().map(String::valueOf).map(x -> x.trim().toLowerCase(java.util.Locale.ROOT)).filter(x -> !x.isEmpty()).toArray(String[]::new));
        }
        // A promoted record is confirmed knowledge: drop draft markers and placeholders.
        r.setFindings(r.getFindings().stream().filter(x -> !x.startsWith("(auto-draft") && !x.startsWith("(no extraction model")).toList());
        r.setTags(java.util.Arrays.stream(r.getTags()).filter(x -> !x.equals("auto-draft") && !x.equals("unextracted")).toArray(String[]::new));
        String claimed = body != null && body.get("by") instanceof String b && !b.isBlank() ? b : r.getAuthor();
        String by = identity.required() ? identity.handle(req, null) : claimed; // with auth, the promoter is the caller
        r.setStatus("promoted");
        r.setPromotedAt(Instant.now());
        r.setPromotedBy(users.resolve(by).getId());
        return records.save(r);
    }

    @PostMapping("/{id}/reject")
    public Record reject(@PathVariable UUID id, HttpServletRequest req) {
        Record r = changeable(req, id);
        r.setStatus("rejected");
        return records.save(r);
    }

    @PostMapping("/{id}/stale")
    public Record stale(@PathVariable UUID id, HttpServletRequest req) {
        Record r = changeable(req, id);
        r.setStatus("stale");
        return records.save(r);
    }

    /** Remembers that a team record was published to a public commons (set by the skill after publishing). */
    @PostMapping("/{id}/published")
    public Record published(@PathVariable UUID id, @RequestBody Map<String, String> body, HttpServletRequest req) {
        Record r = changeable(req, id);
        String url = body.get("url");
        if (url == null || !url.matches("https?://\\S+")) throw new IllegalArgumentException("url of the public record is required");
        r.setPublishedUrl(url);
        r.setPublishedAt(Instant.now());
        return records.save(r);
    }

    /**
     * Builds (or refreshes) a session's record now. A promoted record is someone's curated text and is returned as it is,
     * unless force=true (e.g. re-extracting after configuring a model).
     */
    @PostMapping("/extract/{sessionId}")
    public Record extract(@PathVariable UUID sessionId, @RequestParam(defaultValue = "false") boolean force, HttpServletRequest req) {
        var s = sessions.findById(sessionId).orElseThrow(() -> new NoSuchElementException("session " + sessionId));
        if (!identity.canSee(req, s.getUserId(), s.getAuthor(), s.getTeamId(), s.getRepoId())) throw new NoSuchElementException("session " + sessionId);
        if (!identity.canChange(req, s.getAuthor(), s.getTeamId()))
            throw new io.priorart.api.DirectoryController.Forbidden("only the author or a member of its team can re-extract this session");
        if (force && identity.required() && !identity.handle(req, null).equals(s.getAuthor())
                && records.findBySessionId(sessionId).stream().anyMatch(r -> "promoted".equals(r.getStatus())))
            throw new io.priorart.auth.Forbidden("this session's record is promoted; only its author can re-extract over it");
        return extraction.extract(sessionId, force);
    }
}
