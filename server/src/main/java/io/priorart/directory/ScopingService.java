package io.priorart.directory;

import io.priorart.domain.Session;
import io.priorart.domain.SessionRepository;
import io.priorart.domain.Team;
import io.priorart.domain.TeamMemberRepository;
import io.priorart.domain.TeamRepository;
import io.priorart.domain.User;
import io.priorart.domain.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ScopingService {
    private static final Logger log = LoggerFactory.getLogger(ScopingService.class);
    @PersistenceContext private EntityManager em;
    private final SessionRepository sessions;
    private final TeamResolver resolver;
    private final UserRepository users;
    private final TeamMemberRepository members;
    private final TeamRepository teams;

    public ScopingService(SessionRepository sessions, TeamResolver resolver, UserRepository users,
                          TeamMemberRepository members, TeamRepository teams) {
        this.sessions = sessions;
        this.resolver = resolver;
        this.users = users;
        this.members = members;
        this.teams = teams;
    }

    /**
     * Re-attach work captured before its repo/team was onboarded. Called after every directory change, so onboarding
     * a repo today makes last week's sessions from it show up in that team's search.
     */
    @Transactional
    public int rescope() {
        int repos = em.createNativeQuery("""
                update sessions s set repo_id = r.id from repos r
                where s.repo_id is null and s.repo = r.remote_url""").executeUpdate();
        // The update above bypasses the persistence context: sessions loaded earlier in this transaction (e.g. by a
        // previous rescope during the same onboarding) would still show repo_id = null. Flush and reload.
        em.flush();
        em.clear();
        int assigned = 0;
        for (Session s : sessions.findByTeamIdIsNull()) {
            UUID t = resolver.resolve(s.getUserId(), s.getRepoId());
            if (t != null) { s.setTeamId(t); assigned++; }
        }
        sessions.flush();
        int recs = em.createNativeQuery("""
                update records r set team_id = s.team_id from sessions s
                where r.session_id = s.id and r.team_id is distinct from s.team_id""").executeUpdate();
        if (repos + assigned + recs > 0) log.info("rescope: {} sessions got a repo, {} got a team, {} records moved", repos, assigned, recs);
        return repos + assigned + recs;
    }

    public enum Scope { TEAM, APP, ALL }

    public record Resolved(Scope scope, Set<UUID> teamIds) {
        public static final Resolved ALL = new Resolved(Scope.ALL, null);
    }

    /**
     * Which teams a search should cover for this user. Unassigned work (team_id null) is always included: in a
     * single-corp deployment it is visible to everyone, and early on most work is unassigned.
     * Falls back to ALL when the user is unknown or in no team, so search still works before onboarding.
     */
    @Transactional(readOnly = true)
    public Resolved forUser(String handle, Scope requested) {
        if (requested == Scope.ALL || handle == null || handle.isBlank()) return Resolved.ALL;
        User u = users.findByHandle(handle.trim()).orElse(null);
        if (u == null) return Resolved.ALL;
        Set<UUID> mine = new HashSet<>(members.teamIdsOf(u.getId()));
        if (mine.isEmpty()) return Resolved.ALL;
        if (requested == Scope.TEAM) return new Resolved(Scope.TEAM, mine);
        Set<UUID> apps = teams.findAllById(mine).stream().map(Team::getApplicationId).collect(Collectors.toSet());
        Set<UUID> appTeams = teams.findByApplicationIdIn(apps).stream().map(Team::getId).collect(Collectors.toSet());
        return new Resolved(Scope.APP, appTeams);
    }
}
