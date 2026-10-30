package io.priorart.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TeamRepository extends JpaRepository<Team, UUID> {
    List<Team> findByApplicationIdOrderByName(UUID applicationId);
    List<Team> findByApplicationIdIn(Collection<UUID> applicationIds);
    Optional<Team> findByApplicationIdAndSlug(UUID applicationId, String slug);
}
