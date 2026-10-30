package io.priorart.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RepoRepository extends JpaRepository<Repo, UUID> {
    Optional<Repo> findByRemoteUrl(String remoteUrl);
    List<Repo> findByApplicationIdOrderByName(UUID applicationId);
}
