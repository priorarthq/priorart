package io.priorart.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "teams")
public class Team {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID applicationId;
    private String slug;
    private String name;
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getApplicationId() { return applicationId; }
    public void setApplicationId(UUID v) { applicationId = v; }
    public String getSlug() { return slug; }
    public void setSlug(String v) { slug = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public Instant getCreatedAt() { return createdAt; }
}
