package io.priorart.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.UUID;

public interface TeamMemberRepository extends JpaRepository<TeamMember, TeamMember.Key> {
    List<TeamMember> findByTeamId(UUID teamId);
    @Query("select m.teamId from TeamMember m where m.userId = :userId")
    List<UUID> teamIdsOf(UUID userId);
}
