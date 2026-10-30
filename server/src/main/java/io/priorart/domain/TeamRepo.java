package io.priorart.domain;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "team_repos")
@IdClass(TeamRepo.Key.class)
public class TeamRepo {
    @Id private UUID teamId;
    @Id private UUID repoId;
    private Instant createdAt = Instant.now();

    public static class Key implements Serializable {
        private UUID teamId;
        private UUID repoId;
        public Key() {}
        public Key(UUID teamId, UUID repoId) { this.teamId = teamId; this.repoId = repoId; }
        @Override public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(teamId, k.teamId) && Objects.equals(repoId, k.repoId);
        }
        @Override public int hashCode() { return Objects.hash(teamId, repoId); }
    }

    public UUID getTeamId() { return teamId; }
    public void setTeamId(UUID v) { teamId = v; }
    public UUID getRepoId() { return repoId; }
    public void setRepoId(UUID v) { repoId = v; }
    public Instant getCreatedAt() { return createdAt; }
}
