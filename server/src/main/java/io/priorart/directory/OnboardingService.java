package io.priorart.directory;

import io.priorart.auth.Identity;
import io.priorart.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/**
 * Onboarding as the skill does it: one "where am I" lookup and one idempotent "make it so" call, both keyed on the
 * caller's handle and the git remote of the directory the agent is running in.
 */
@Service
public class OnboardingService {
    private final ApplicationRepository apps;
    private final TeamRepository teams;
    private final RepoRepository repos;
    private final TeamMemberRepository members;
    private final TeamRepoRepository teamRepos;
    private final UserRepository users;
    private final UserService userService;
    private final DirectoryService directory;
    private final ScopingService scoping;
    private final TeamJoinRequestRepository requests;
    private final Identity identity;
    private final CreationRights rights;
    @org.springframework.beans.factory.annotation.Autowired private io.priorart.auth.RepoAccess access;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;

    public OnboardingService(ApplicationRepository apps, TeamRepository teams, RepoRepository repos,
                             TeamMemberRepository members, TeamRepoRepository teamRepos, UserRepository users,
                             UserService userService, DirectoryService directory, ScopingService scoping,
                             TeamJoinRequestRepository requests, Identity identity, CreationRights rights) {
        this.rights = rights;
        this.apps = apps;
        this.teams = teams;
        this.repos = repos;
        this.members = members;
        this.teamRepos = teamRepos;
        this.users = users;
        this.userService = userService;
        this.directory = directory;
        this.scoping = scoping;
        this.requests = requests;
        this.identity = identity;
    }

    private List<String> adminsOf(UUID teamId) {
        Map<UUID, String> handles = new HashMap<>();
        users.findAll().forEach(u -> handles.put(u.getId(), u.getHandle()));
        return members.findByTeamId(teamId).stream().filter(m -> "admin".equals(m.getRole()))
                .map(m -> handles.get(m.getUserId())).filter(Objects::nonNull).sorted().toList();
    }

    public record TeamRef(String application, String slug, String name, String role) {}
    /** mine: the user has onboarded this repo, so their sessions in it go to its team (when they're a member). */
    public record RepoInfo(String remoteUrl, boolean registered, String name, String application, List<String> teams, boolean mine) {}
    public record AppSummary(String slug, String name, List<String> teams) {}
    /** pending: teams the user asked to join, awaiting an admin. approvalsWaiting: join requests the user can approve. */
    public record Lookup(String handle, boolean knownUser, List<TeamRef> myTeams, RepoInfo repo, List<AppSummary> applications,
                         List<TeamRef> pending, int approvalsWaiting, boolean serverAdmin, List<String> serverAdmins,
                         String repoAccess, String linkGitAccount) {}

    @Transactional(readOnly = true)
    public Lookup lookup(String handle, String remoteUrl) {
        Map<UUID, Application> appById = new HashMap<>();
        apps.findAll().forEach(a -> appById.put(a.getId(), a));
        Map<UUID, Team> teamById = new HashMap<>();
        teams.findAll().forEach(t -> teamById.put(t.getId(), t));

        User u = handle == null || handle.isBlank() ? null : users.findByHandle(handle.trim()).orElse(null);
        List<TeamRef> mine = new ArrayList<>();
        if (u != null) {
            for (UUID tid : members.teamIdsOf(u.getId())) {
                Team t = teamById.get(tid);
                String role = members.findById(new TeamMember.Key(tid, u.getId())).map(TeamMember::getRole).orElse("member");
                mine.add(new TeamRef(appById.get(t.getApplicationId()).getSlug(), t.getSlug(), t.getName(), role));
            }
            mine.sort(Comparator.comparing(TeamRef::application).thenComparing(TeamRef::slug));
        }

        RepoInfo repo = null;
        String norm = RemoteUrls.normalize(remoteUrl);
        if (norm != null) {
            repo = repos.findByRemoteUrl(norm).map(r -> new RepoInfo(norm, true, r.getName(),
                    appById.get(r.getApplicationId()).getSlug(),
                    teamRepos.teamIdsOf(r.getId()).stream().map(id -> teamById.get(id).getSlug()).sorted().toList(),
                    u != null && optedIn(u.getId(), r.getId())))
                    .orElse(new RepoInfo(norm, false, RemoteUrls.defaultName(norm), null, List.of(), false));
        }

        List<AppSummary> all = apps.findAllByOrderByName().stream().map(a -> new AppSummary(a.getSlug(), a.getName(),
                teams.findByApplicationIdOrderByName(a.getId()).stream().map(Team::getSlug).toList())).toList();
        List<TeamRef> pending = new ArrayList<>();
        int approvals = 0;
        if (u != null) {
            for (TeamJoinRequest jr : requests.findByUserId(u.getId())) {
                Team t = teamById.get(jr.getTeamId());
                if (t != null) pending.add(new TeamRef(appById.get(t.getApplicationId()).getSlug(), t.getSlug(), t.getName(), "pending"));
            }
            Set<UUID> adminOf = new HashSet<>();
            for (UUID tid : members.teamIdsOf(u.getId()))
                if (members.findById(new TeamMember.Key(tid, u.getId())).map(m -> "admin".equals(m.getRole())).orElse(false)) adminOf.add(tid);
            approvals = adminOf.isEmpty() ? 0 : requests.findByTeamIdIn(adminOf).size();
        }
        return new Lookup(u == null ? handle : u.getHandle(), u != null, mine, repo, all, pending, approvals,
                u != null && rights.isServerAdmin(u), rights.serverAdminHandles(),
                access.mode(), access.missingAccountFor(u, norm));   // the git host whose account this user still needs to link
    }

    private boolean optedIn(UUID userId, UUID repoId) {
        return !em.createNativeQuery("select 1 from repo_members where repo_id = :r and user_id = :u")
                .setParameter("r", repoId).setParameter("u", userId).getResultList().isEmpty();
    }

    public record OnboardRequest(String handle, String remoteUrl, String application, String applicationName,
                                 String team, String teamName, String role, Boolean create) {}
    public record OnboardResult(List<String> actions, Lookup state) {}

    /**
     * Idempotent: re-running with the same arguments changes nothing. Creating an application or team requires
     * create=true so an agent can't invent one from a typo; registering the repo and joining a team never do.
     */
    @Transactional
    public OnboardResult onboard(OnboardRequest req) {
        List<String> actions = new ArrayList<>();
        boolean create = Boolean.TRUE.equals(req.create());
        if (req.handle() == null || req.handle().isBlank()) throw new IllegalArgumentException("handle is required");
        if (req.application() == null || req.application().isBlank()) throw new IllegalArgumentException("application is required");
        User user = userService.resolve(req.handle());

        String appSlug = req.application().trim().toLowerCase(Locale.ROOT);
        Application app = apps.findBySlug(appSlug).orElse(null);
        if (app == null) {
            if (!create) throw new IllegalArgumentException(unknown("application", appSlug, "",
                    apps.findAllByOrderByName().stream().map(Application::getSlug).toList()));
            if (rights.application(user)) actions.add("you're now a server admin (the first application on this server): you create "
                    + "applications, and can let others too: priorart.mjs server-admin <handle>");
            app = directory.createApplication(appSlug, blankTo(req.applicationName(), appSlug));
            actions.add("created application " + app.getSlug());
        }

        Team team = null;
        boolean teamCreated = false;
        if (req.team() != null && !req.team().isBlank()) {
            String teamSlug = req.team().trim().toLowerCase(Locale.ROOT);
            team = teams.findByApplicationIdAndSlug(app.getId(), teamSlug).orElse(null);
            if (team == null) {
                if (!create) throw new IllegalArgumentException(unknown("team", teamSlug, " in " + app.getSlug(),
                        teams.findByApplicationIdOrderByName(app.getId()).stream().map(Team::getSlug).toList()));
                rights.team(user, app);
                team = directory.createTeam(app.getId(), teamSlug, blankTo(req.teamName(), teamSlug));
                teamCreated = true;
                actions.add("created team " + app.getSlug() + "/" + team.getSlug());
            }
        }

        // Membership. With team auth on, joining a team that already has an admin is a request the admin approves;
        // the creator of a team (or the first member of a team without admins) becomes its admin.
        boolean member = false;
        if (team != null) {
            TeamMember existing = members.findById(new TeamMember.Key(team.getId(), user.getId())).orElse(null);
            List<String> admins = adminsOf(team.getId());
            if (existing != null) {
                member = true;
                String role = blankTo(req.role(), null);
                boolean mayChangeRole = !identity.required() || "admin".equals(existing.getRole());
                if (role != null && !role.equals(existing.getRole()) && mayChangeRole) {
                    directory.addMember(team.getId(), user.getHandle(), role);
                    actions.add("changed " + user.getHandle() + "'s role in " + team.getSlug() + " to " + role);
                }
            } else if (identity.required() && !teamCreated && !admins.isEmpty()) {
                TeamJoinRequest.Key key = new TeamJoinRequest.Key(team.getId(), user.getId());
                boolean already = requests.existsById(key);
                if (!already) {
                    TeamJoinRequest jr = new TeamJoinRequest();
                    jr.setTeamId(team.getId());
                    jr.setUserId(user.getId());
                    requests.save(jr);
                }
                actions.add((already ? "still waiting" : "asked to join team " + team.getSlug() + "; waiting")
                        + " for an admin to approve (admins: " + String.join(", ", admins) + "). They'll see the request the next "
                        + "time they use Prior Art; a quick message to them speeds it up. Nothing to rerun after: it switches on by itself.");
            } else {
                String role = identity.required() ? (teamCreated || admins.isEmpty() ? "admin" : "member")
                                                  : blankTo(req.role(), teamCreated ? "admin" : "member");
                directory.addMember(team.getId(), user.getHandle(), role);
                member = true;
                actions.add("added " + user.getHandle() + " to team " + team.getSlug() + ("member".equals(role) ? "" : " as " + role));
            }
        }

        String norm = RemoteUrls.normalize(req.remoteUrl());
        if (norm != null) {
            Repo repo = repos.findByRemoteUrl(norm).orElse(null);
            if (repo != null && !repo.getApplicationId().equals(app.getId())) {
                String owner = apps.findById(repo.getApplicationId()).map(Application::getSlug).orElse("?");
                throw new IllegalArgumentException("repo " + norm + " is already registered under application '" + owner + "'");
            }
            if (repo == null && !rights.mayRegisterRepo(user, app.getId())) {
                actions.add("not registering " + norm + " until you're a member of a team in " + app.getSlug());
            } else if (repo == null) {
                repo = directory.createRepo(app.getId(), norm, null);
                actions.add("registered repo " + norm);
            }
            if (repo != null && !optedIn(user.getId(), repo.getId())) {
                // running onboard in a repo is the user's own say-so that their sessions in it are team work
                em.createNativeQuery("insert into repo_members (repo_id, user_id) values (:r, :u) on conflict do nothing")
                        .setParameter("r", repo.getId()).setParameter("u", user.getId()).executeUpdate();
                actions.add("your sessions in " + repo.getName() + " are shared with " + (team != null ? "team " + team.getSlug() : "your team")
                        + (member ? "" : " once you're approved"));
            }
            if (repo != null && team != null && !teamRepos.existsById(new TeamRepo.Key(team.getId(), repo.getId()))) {
                if (member) {
                    directory.linkRepo(team.getId(), repo.getId());
                    actions.add("linked " + repo.getName() + " to team " + team.getSlug());
                } else {
                    actions.add("not linking " + repo.getName() + " to " + team.getSlug() + " until you're a member");
                }
            }
        }

        int moved = scoping.rescope();
        if (moved > 0) actions.add("attached " + moved + " earlier session/record links to their teams");
        if (actions.isEmpty()) actions.add("nothing to do — already onboarded");
        return new OnboardResult(actions, lookup(user.getHandle(), req.remoteUrl()));
    }

    /** "team 'paymnets' does not exist in billing — did you mean 'payments'? (existing: …). To create it, re-run with --create." */
    static String unknown(String what, String slug, String where, List<String> existing) {
        String close = existing.stream().filter(e -> distance(e, slug) <= 2).findFirst().orElse(null);
        return what + " '" + slug + "' does not exist" + where + (close != null ? " — did you mean '" + close + "'?" : "")
                + " (existing: " + (existing.isEmpty() ? "none" : String.join(", ", existing)) + "). To create a new one, re-run with --create.";
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++)
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    private static String blankTo(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s.trim();
    }
}
