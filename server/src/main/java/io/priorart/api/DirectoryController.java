package io.priorart.api;

import io.priorart.auth.Identity;
import io.priorart.directory.CreationRights;
import io.priorart.directory.DirectoryService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.NoSuchElementException;
import io.priorart.directory.ScopingService;
import io.priorart.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Onboarding API: applications → teams / repos, team members, team ↔ repo links. */
@RestController
@RequestMapping("/api")
public class DirectoryController {
    private final DirectoryService directory;
    private final ScopingService scoping;
    private final UserRepository users;
    private final TeamMemberRepository members;
    private final TeamJoinRequestRepository requests;
    private final TeamRepository teams;
    private final ApplicationRepository apps;
    private final Identity identity;
    private final CreationRights rights;

    public DirectoryController(DirectoryService directory, ScopingService scoping, UserRepository users,
                               TeamMemberRepository members, TeamJoinRequestRepository requests, TeamRepository teams,
                               ApplicationRepository apps, Identity identity, CreationRights rights) {
        this.rights = rights;
        this.directory = directory;
        this.scoping = scoping;
        this.users = users;
        this.members = members;
        this.requests = requests;
        this.teams = teams;
        this.apps = apps;
        this.identity = identity;
    }

    /** Team changes need an admin of that team (or, for a team with no admins yet, any member-to-be). No-op in off mode. */
    private void requireAdmin(HttpServletRequest req, UUID teamId) {
        if (!identity.required()) return;
        User me = identity.user(req, null);
        boolean hasAdmins = members.findByTeamId(teamId).stream().anyMatch(m -> "admin".equals(m.getRole()));
        boolean isAdmin = members.findById(new TeamMember.Key(teamId, me.getId())).map(m -> "admin".equals(m.getRole())).orElse(false);
        if (hasAdmins && !isAdmin) throw new Forbidden("only an admin of this team can do that");
    }

    public static class Forbidden extends RuntimeException {
        public Forbidden(String m) { super(m); }
    }

    public record NewApplication(String slug, String name) {}
    public record NewTeam(String slug, String name) {}
    public record NewRepo(String remoteUrl, String name) {}
    public record NewMember(String handle, String role) {}
    public record LinkRepo(UUID repoId) {}

    /** Members, roles and repo remotes only for people in a team; someone not yet approved sees just the names to pick from. */
    @GetMapping("/applications")
    public List<DirectoryService.AppView> tree(HttpServletRequest req) {
        List<DirectoryService.AppView> all = directory.tree();
        if (identity.isInsider(req)) return all;
        return all.stream().map(a -> new DirectoryService.AppView(a.id(), a.slug(), a.name(),
                a.teams().stream().map(t -> new DirectoryService.TeamView(t.id(), t.slug(), t.name(), List.of(), List.of())).toList(),
                List.of())).toList();
    }

    @PostMapping("/applications") @ResponseStatus(HttpStatus.CREATED)
    public Application createApplication(@RequestBody NewApplication b, HttpServletRequest req) {
        if (identity.required()) rights.application(identity.user(req, null));
        return directory.createApplication(b.slug(), b.name());
    }

    @PostMapping("/applications/{appId}/teams") @ResponseStatus(HttpStatus.CREATED)
    public Team createTeam(@PathVariable UUID appId, @RequestBody NewTeam b, HttpServletRequest req) {
        if (identity.required()) rights.team(identity.user(req, null), apps.findById(appId).orElseThrow(() -> new NoSuchElementException("application " + appId)));
        Team t = directory.createTeam(appId, b.slug(), b.name());
        if (identity.required()) directory.addMember(t.getId(), identity.handle(req, null), "admin"); // creator administers it
        return t;
    }

    @PostMapping("/applications/{appId}/repos") @ResponseStatus(HttpStatus.CREATED)
    public Repo createRepo(@PathVariable UUID appId, @RequestBody NewRepo b, HttpServletRequest req) {
        if (identity.required() && !rights.mayRegisterRepo(identity.user(req, null), appId))
            throw new io.priorart.auth.Forbidden("only members of a team in this application can register repos in it");
        return directory.createRepo(appId, b.remoteUrl(), b.name());
    }

    @PostMapping("/teams/{teamId}/members") @ResponseStatus(HttpStatus.CREATED)
    public TeamMember addMember(@PathVariable UUID teamId, @RequestBody NewMember b, HttpServletRequest req) {
        requireAdmin(req, teamId);
        return directory.addMember(teamId, b.handle(), b.role());
    }

    @DeleteMapping("/teams/{teamId}/members/{userId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable UUID teamId, @PathVariable UUID userId, HttpServletRequest req) {
        if (!(identity.required() && identity.user(req, null).getId().equals(userId))) requireAdmin(req, teamId); // leaving is always allowed
        directory.removeMember(teamId, userId);
    }

    @PostMapping("/teams/{teamId}/repos") @ResponseStatus(HttpStatus.CREATED)
    public TeamRepo linkRepo(@PathVariable UUID teamId, @RequestBody LinkRepo b, HttpServletRequest req) {
        requireAdmin(req, teamId);
        return directory.linkRepo(teamId, b.repoId());
    }

    @DeleteMapping("/teams/{teamId}/repos/{repoId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlinkRepo(@PathVariable UUID teamId, @PathVariable UUID repoId, HttpServletRequest req) {
        requireAdmin(req, teamId);
        directory.unlinkRepo(teamId, repoId);
    }

    // ---- identity and join requests ---------------------------------------------------------------------------

    public record Claim(String handle, String code) {}
    public record JoinRequestView(UUID teamId, String team, UUID userId, String handle, java.time.Instant requestedAt, boolean mine) {}
    public record Approve(String role) {}

    /** Claims a handle; the personal token is returned once. Open (no token needed). */
    @PostMapping("/users/claim") @ResponseStatus(HttpStatus.CREATED)
    public Identity.Claim claim(@RequestBody Claim b) { return identity.claim(b.handle(), b.code()); }

    /** Join requests the caller can approve (teams they administer), plus their own pending ones. */
    @GetMapping("/requests")
    public List<JoinRequestView> requests(HttpServletRequest req) {
        User me = identity.user(req, null);
        if (me == null) return List.of();
        java.util.Set<UUID> adminOf = new java.util.HashSet<>();
        for (UUID tid : members.teamIdsOf(me.getId()))
            if (members.findById(new TeamMember.Key(tid, me.getId())).map(m -> "admin".equals(m.getRole())).orElse(false)) adminOf.add(tid);
        java.util.List<TeamJoinRequest> rows = new java.util.ArrayList<>(adminOf.isEmpty() ? List.of() : requests.findByTeamIdIn(adminOf));
        rows.addAll(requests.findByUserId(me.getId()).stream().filter(r -> !adminOf.contains(r.getTeamId())).toList());
        return rows.stream().map(r -> {
            Team t = teams.findById(r.getTeamId()).orElseThrow();
            String app = apps.findById(t.getApplicationId()).map(Application::getSlug).orElse("?");
            String h = users.findById(r.getUserId()).map(User::getHandle).orElse("?");
            return new JoinRequestView(t.getId(), app + "/" + t.getSlug(), r.getUserId(), h, r.getRequestedAt(), r.getUserId().equals(me.getId()));
        }).toList();
    }

    @PostMapping("/teams/{teamId}/requests/{userId}/approve")
    public TeamMember approve(@PathVariable UUID teamId, @PathVariable UUID userId, @RequestBody(required = false) Approve b, HttpServletRequest req) {
        requireAdmin(req, teamId);
        TeamJoinRequest.Key key = new TeamJoinRequest.Key(teamId, userId);
        if (!requests.existsById(key)) throw new NoSuchElementException("no pending request from that user for this team");
        String handle = users.findById(userId).orElseThrow().getHandle();
        TeamMember m = directory.addMember(teamId, handle, b == null || b.role() == null ? "member" : b.role());
        requests.deleteById(key);
        return m;
    }

    @PostMapping("/teams/{teamId}/requests/{userId}/deny") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deny(@PathVariable UUID teamId, @PathVariable UUID userId, HttpServletRequest req) {
        requireAdmin(req, teamId);
        requests.deleteById(new TeamJoinRequest.Key(teamId, userId));
    }

    @GetMapping("/users")
    public List<User> users(HttpServletRequest req) {
        if (!identity.isInsider(req)) throw new io.priorart.auth.Forbidden("the user list is for people in a team");
        return users.findAll();
    }

    public record NewServerAdmin(String handle) {}

    /** Server admins create applications; one can make another person a server admin. */
    @PostMapping("/admins")
    public Map<String, Object> makeServerAdmin(@RequestBody NewServerAdmin b, HttpServletRequest req) {
        rights.serverAdmin(identity.user(req, null));
        String h = b.handle() == null ? "" : b.handle().trim().toLowerCase(java.util.Locale.ROOT);
        User u = users.findByHandle(h).filter(x -> x.getClaimedAt() != null || !identity.required())
                .orElseThrow(() -> new NoSuchElementException("no claimed handle '" + h + "'"));
        u.setServerAdmin(true);
        users.save(u);
        return Map.of("handle", u.getHandle(), "serverAdmin", true);
    }

    /** Re-attach captured work to repos/teams; normally automatic after onboarding changes. */
    @PostMapping("/admin/rescope")
    public Map<String, Integer> rescope(HttpServletRequest req) {
        if (identity.required()) rights.serverAdmin(identity.user(req, null));
        return Map.of("updated", scoping.rescope());
    }
}
