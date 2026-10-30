package io.priorart.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionRepository extends JpaRepository<Session, UUID> {
    Optional<Session> findByAgentAndExternalId(String agent, String externalId);
    List<Session> findByTeamIdIsNull();
    Optional<Session> findFirstByAuthorAndCwdOrderByEndedAtDesc(String author, String cwd);
    Optional<Session> findFirstByAuthorOrderByEndedAtDesc(String author);
}
