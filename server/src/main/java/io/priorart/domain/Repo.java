package io.priorart.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "repos")
public class Repo {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID applicationId;
    private String remoteUrl;
    private String name;
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getApplicationId() { return applicationId; }
    public void setApplicationId(UUID v) { applicationId = v; }
    public String getRemoteUrl() { return remoteUrl; }
    public void setRemoteUrl(String v) { remoteUrl = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public Instant getCreatedAt() { return createdAt; }
}
