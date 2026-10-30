package io.priorart.directory;

import io.priorart.domain.TeamMemberRepository;
import io.priorart.domain.TeamRepoRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Decides which team a session belongs to from who ran it and which repo it ran in. Returns null when ambiguous. */
@Component
public class TeamResolver {
    private final TeamMemberRepository members;
    private final TeamRepoRepository teamRepos;
    private final boolean membersOnly;
    @PersistenceContext private EntityManager em;

    public TeamResolver(TeamMemberRepository members, TeamRepoRepository teamRepos, @Value("${priorart.team-auth:required}") String auth) {
        this.members = members;
        this.teamRepos = teamRepos;
        this.membersOnly = !"off".equalsIgnoreCase(auth);
    }

    public UUID resolve(UUID userId, UUID repoId) {
        Set<UUID> userTeams = userId == null ? Set.of() : new HashSet<>(members.teamIdsOf(userId));
        Set<UUID> repoTeams = repoId == null ? Set.of() : new HashSet<>(teamRepos.teamIdsOf(repoId));
        // With team auth on, a person's work in a repo goes to a team only after they onboarded that repo themselves:
        // a teammate linking someone's private repo must not pull their sessions in.
        if (membersOnly && userId != null && repoId != null && !optedIn(userId, repoId)) return null;
        return pick(userTeams, repoTeams, membersOnly);
    }

    private boolean optedIn(UUID userId, UUID repoId) {
        return !em.createNativeQuery("select 1 from repo_members where repo_id = :r and user_id = :u")
                .setParameter("r", repoId).setParameter("u", userId).getResultList().isEmpty();
    }

    /**
     * Only the repo decides. Work in a repo that no team has onboarded stays unassigned, and unassigned work is private to
     * its author (see SearchService): capture hooks are global, so they also see personal side projects, which must not
     * land in a team just because their author happens to be in one.
     * 1. exactly one team owns the repo AND has the user → that team
     * 2. the user isn't in a team that owns the repo → unassigned (private to its author) until an admin approves them;
     *    approval rescopes it. Otherwise anyone could write into a team's memory by claiming its repo's remote.
     *    (team-auth off, single-user mode: exactly one owning team → that team.)
     * 3. repo shared by several of the user's teams → unassigned
     * 4. repo not linked to any team → unassigned
     */
    static UUID pick(Set<UUID> userTeams, Set<UUID> repoTeams, boolean membersOnly) {
        if (repoTeams.isEmpty()) return null;
        Set<UUID> both = new HashSet<>(repoTeams);
        both.retainAll(userTeams);
        if (both.size() == 1) return both.iterator().next();
        if (!membersOnly && repoTeams.size() == 1) return repoTeams.iterator().next();
        return null;
    }
}
