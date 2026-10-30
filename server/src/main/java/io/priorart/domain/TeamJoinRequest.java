package io.priorart.domain;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "team_join_requests")
@IdClass(TeamJoinRequest.Key.class)
public class TeamJoinRequest {
    @Id private UUID teamId;
    @Id private UUID userId;
    private Instant requestedAt = Instant.now();

    public static class Key implements Serializable {
        private UUID teamId;
        private UUID userId;
        public Key() {}
        public Key(UUID teamId, UUID userId) { this.teamId = teamId; this.userId = userId; }
        @Override public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(teamId, k.teamId) && Objects.equals(userId, k.userId);
        }
        @Override public int hashCode() { return Objects.hash(teamId, userId); }
    }

    public UUID getTeamId() { return teamId; }
    public void setTeamId(UUID v) { teamId = v; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID v) { userId = v; }
    public Instant getRequestedAt() { return requestedAt; }
}
