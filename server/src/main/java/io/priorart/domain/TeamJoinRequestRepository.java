package io.priorart.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TeamJoinRequestRepository extends JpaRepository<TeamJoinRequest, TeamJoinRequest.Key> {
    List<TeamJoinRequest> findByUserId(UUID userId);
    List<TeamJoinRequest> findByTeamIdIn(Collection<UUID> teamIds);
}
