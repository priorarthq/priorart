package io.priorart.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** The agent's native transcript, verbatim. 1:1 with Session; kept separate so session listings stay small. */
@Entity
@Table(name = "session_transcripts")
public class SessionTranscript {
    @Id private UUID sessionId;
    private String format = "jsonl";
    @Column(columnDefinition = "text") private String content;
    private int bytes;
    private int lines;
    private String sha256;
    private String sourcePath;
    private Instant capturedAt = Instant.now();

    public UUID getSessionId() { return sessionId; }
    public void setSessionId(UUID v) { sessionId = v; }
    public String getFormat() { return format; }
    public void setFormat(String v) { format = v; }
    public String getContent() { return content; }
    public void setContent(String v) { content = v; }
    public int getBytes() { return bytes; }
    public void setBytes(int v) { bytes = v; }
    public int getLines() { return lines; }
    public void setLines(int v) { lines = v; }
    public String getSha256() { return sha256; }
    public void setSha256(String v) { sha256 = v; }
    public String getSourcePath() { return sourcePath; }
    public void setSourcePath(String v) { sourcePath = v; }
    public Instant getCapturedAt() { return capturedAt; }
    public void setCapturedAt(Instant v) { capturedAt = v; }
}
