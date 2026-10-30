package io.priorart.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sessions")
public class Session {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private String agent;
    private String externalId;
    private String author;
    private UUID userId;
    private UUID teamId;
    private UUID repoId;
    private String cwd;
    private String repo;          // normalized remote, kept even when the repo isn't onboarded
    private String gitBranch;
    private String headSha;
    private boolean hadDiff;
    private Instant startedAt;
    private Instant endedAt = Instant.now();
    @Column(columnDefinition = "text") private String prompts = "";
    private int turns;

    public UUID getId() { return id; }
    public String getAgent() { return agent; }
    public void setAgent(String v) { agent = v; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String v) { externalId = v; }
    public String getAuthor() { return author; }
    public void setAuthor(String v) { author = v; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID v) { userId = v; }
    public UUID getTeamId() { return teamId; }
    public void setTeamId(UUID v) { teamId = v; }
    public UUID getRepoId() { return repoId; }
    public void setRepoId(UUID v) { repoId = v; }
    public String getCwd() { return cwd; }
    public void setCwd(String v) { cwd = v; }
    public String getRepo() { return repo; }
    public void setRepo(String v) { repo = v; }
    public String getGitBranch() { return gitBranch; }
    public void setGitBranch(String v) { gitBranch = v; }
    public String getHeadSha() { return headSha; }
    public void setHeadSha(String v) { headSha = v; }
    public boolean isHadDiff() { return hadDiff; }
    public void setHadDiff(boolean v) { hadDiff = v; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant v) { startedAt = v; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant v) { endedAt = v; }
    public String getPrompts() { return prompts; }
    public void setPrompts(String v) { prompts = v; }
    public int getTurns() { return turns; }
    public void setTurns(int v) { turns = v; }
}
