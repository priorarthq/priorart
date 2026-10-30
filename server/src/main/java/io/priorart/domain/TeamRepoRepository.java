package io.priorart.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.UUID;

public interface TeamRepoRepository extends JpaRepository<TeamRepo, TeamRepo.Key> {
    List<TeamRepo> findByTeamId(UUID teamId);
    List<TeamRepo> findByRepoId(UUID repoId);
    @Query("select tr.teamId from TeamRepo tr where tr.repoId = :repoId")
    List<UUID> teamIdsOf(UUID repoId);
}
