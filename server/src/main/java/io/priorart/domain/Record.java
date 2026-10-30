package io.priorart.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "records")
public class Record {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID sessionId;
    private UUID teamId;
    private String status = "draft";
    private String kind = "investigation";
    private String title;
    @Column(columnDefinition = "text") private String question = "";
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") private List<String> findings = new ArrayList<>();
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") private List<Map<String, Object>> evidence = new ArrayList<>();
    @Column(columnDefinition = "text") private String decision = "";
    private String outcome = "unknown";
    @Column(columnDefinition = "text[]") private String[] tags = new String[0];
    private String commitSha;
    @Column(columnDefinition = "text[]") private String[] touchedFiles = new String[0];
    private String author;
    private Instant createdAt = Instant.now();
    private Instant promotedAt;
    private UUID promotedBy;
    private String transcriptSha256;
    private String publishedUrl;
    private Instant publishedAt;
    private int hits;

    public UUID getId() { return id; }
    public UUID getSessionId() { return sessionId; }
    public void setSessionId(UUID v) { sessionId = v; }
    public UUID getTeamId() { return teamId; }
    public void setTeamId(UUID v) { teamId = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public String getKind() { return kind; }
    public void setKind(String v) { kind = v; }
    public String getTitle() { return title; }
    /** Risky suggestions in the record (same checks as public records), so whoever reads it is warned. */
    @Transient
    public java.util.List<String> getWarnings() {
        java.util.List<String> f = new java.util.ArrayList<>(java.util.Arrays.asList(title, question, decision));
        if (findings != null) f.addAll(findings);
        return io.priorart.safety.SecretScanner.dangers(f);
    }
    public void setTitle(String v) { title = v; }
    public String getQuestion() { return question; }
    public void setQuestion(String v) { question = v; }
    public List<String> getFindings() { return findings; }
    public void setFindings(List<String> v) { findings = v; }
    public List<Map<String, Object>> getEvidence() { return evidence; }
    public void setEvidence(List<Map<String, Object>> v) { evidence = v; }
    public String getDecision() { return decision; }
    public void setDecision(String v) { decision = v; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String v) { outcome = v; }
    public String[] getTags() { return tags; }
    public void setTags(String[] v) { tags = v; }
    public String getCommitSha() { return commitSha; }
    public void setCommitSha(String v) { commitSha = v; }
    public String[] getTouchedFiles() { return touchedFiles; }
    public void setTouchedFiles(String[] v) { touchedFiles = v; }
    public String getAuthor() { return author; }
    public void setAuthor(String v) { author = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPromotedAt() { return promotedAt; }
    public void setPromotedAt(Instant v) { promotedAt = v; }
    public UUID getPromotedBy() { return promotedBy; }
    public void setPromotedBy(UUID v) { promotedBy = v; }
    public String getPublishedUrl() { return publishedUrl; }
    public void setPublishedUrl(String v) { publishedUrl = v; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant v) { publishedAt = v; }
    public String getTranscriptSha256() { return transcriptSha256; }
    public void setTranscriptSha256(String v) { transcriptSha256 = v; }
    public int getHits() { return hits; }
    public void setHits(int v) { hits = v; }
}
