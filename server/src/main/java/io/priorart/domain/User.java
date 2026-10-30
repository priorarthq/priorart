package io.priorart.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private String handle;
    private String displayName;
    private String email;
    private Instant createdAt = Instant.now();
    private String tokenSha256;
    private Instant claimedAt;
    private boolean serverAdmin;   // may create applications (and teams anywhere); see V7

    public UUID getId() { return id; }
    public String getHandle() { return handle; }
    public void setHandle(String v) { handle = v; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String v) { displayName = v; }
    public String getEmail() { return email; }
    public void setEmail(String v) { email = v; }
    public Instant getCreatedAt() { return createdAt; }
    @com.fasterxml.jackson.annotation.JsonIgnore public String getTokenSha256() { return tokenSha256; }
    public void setTokenSha256(String v) { tokenSha256 = v; }
    public Instant getClaimedAt() { return claimedAt; }
    public void setClaimedAt(Instant v) { claimedAt = v; }
    public boolean isServerAdmin() { return serverAdmin; }
    public void setServerAdmin(boolean v) { serverAdmin = v; }
}
