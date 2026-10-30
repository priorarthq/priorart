package io.priorart.directory;

import io.priorart.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Onboarding: applications, teams, repos, members. Every mutation rescopes existing work. */
@Service
public class DirectoryService {
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");

    private final ApplicationRepository apps;
    private final TeamRepository teams;
    private final RepoRepository repos;
    private final TeamMemberRepository members;
    private final TeamRepoRepository teamRepos;
    private final UserRepository users;
    private final UserService userService;
    private final ScopingService scoping;

    public DirectoryService(ApplicationRepository apps, TeamRepository teams, RepoRepository repos,
                            TeamMemberRepository members, TeamRepoRepository teamRepos, UserRepository users,
                            UserService userService, ScopingService scoping) {
        this.apps = apps;
        this.teams = teams;
        this.repos = repos;
        this.members = members;
        this.teamRepos = teamRepos;
        this.users = users;
        this.userService = userService;
        this.scoping = scoping;
    }

    // ---- read model -------------------------------------------------------------------------------------------

    public record MemberView(UUID userId, String handle, String role) {}
    public record RepoRef(UUID id, String remoteUrl, String name) {}
    public record TeamView(UUID id, String slug, String name, List<MemberView> members, List<RepoRef> repos) {}
    public record RepoView(UUID id, String remoteUrl, String name, List<String> teams) {}
    public record AppView(UUID id, String slug, String name, List<TeamView> teams, List<RepoView> repos) {}

    @Transactional(readOnly = true)
    public List<AppView> tree() {
        Map<UUID, User> userById = users.findAll().stream().collect(Collectors.toMap(User::getId, Function.identity()));
        List<AppView> out = new ArrayList<>();
        for (Application a : apps.findAllByOrderByName()) {
            List<Team> appTeams = teams.findByApplicationIdOrderByName(a.getId());
            List<Repo> appRepos = repos.findByApplicationIdOrderByName(a.getId());
            Map<UUID, Repo> repoById = appRepos.stream().collect(Collectors.toMap(Repo::getId, Function.identity()));
            Map<UUID, String> teamSlug = appTeams.stream().collect(Collectors.toMap(Team::getId, Team::getSlug));
            List<TeamView> tv = new ArrayList<>();
            for (Team t : appTeams) {
                List<MemberView> mv = members.findByTeamId(t.getId()).stream()
                        .map(m -> new MemberView(m.getUserId(), userById.get(m.getUserId()).getHandle(), m.getRole()))
                        .sorted(Comparator.comparing(MemberView::handle)).toList();
                List<RepoRef> rr = teamRepos.findByTeamId(t.getId()).stream()
                        .map(tr -> repoById.get(tr.getRepoId())).filter(Objects::nonNull)
                        .map(r -> new RepoRef(r.getId(), r.getRemoteUrl(), r.getName())).toList();
                tv.add(new TeamView(t.getId(), t.getSlug(), t.getName(), mv, rr));
            }
            List<RepoView> rv = appRepos.stream().map(r -> new RepoView(r.getId(), r.getRemoteUrl(), r.getName(),
                    teamRepos.findByRepoId(r.getId()).stream().map(tr -> teamSlug.get(tr.getTeamId()))
                            .filter(Objects::nonNull).sorted().toList())).toList();
            out.add(new AppView(a.getId(), a.getSlug(), a.getName(), tv, rv));
        }
        return out;
    }

    // ---- mutations --------------------------------------------------------------------------------------------

    @Transactional
    public Application createApplication(String slug, String name) {
        Application a = new Application();
        a.setSlug(slug(slug));
        a.setName(required(name, "name"));
        return apps.save(a);
    }

    @Transactional
    public Team createTeam(UUID appId, String slug, String name) {
        apps.findById(appId).orElseThrow(() -> new NoSuchElementException("application " + appId));
        Team t = new Team();
        t.setApplicationId(appId);
        t.setSlug(slug(slug));
        t.setName(required(name, "name"));
        t = teams.save(t);
        return t;
    }

    @Transactional
    public Repo createRepo(UUID appId, String remoteUrl, String name) {
        apps.findById(appId).orElseThrow(() -> new NoSuchElementException("application " + appId));
        String norm = RemoteUrls.normalize(required(remoteUrl, "remoteUrl"));
        Repo r = new Repo();
        r.setApplicationId(appId);
        r.setRemoteUrl(norm);
        r.setName(name == null || name.isBlank() ? RemoteUrls.defaultName(norm) : name.trim());
        r = repos.saveAndFlush(r);
        scoping.rescope();
        return r;
    }

    @Transactional
    public TeamMember addMember(UUID teamId, String handle, String role) {
        teams.findById(teamId).orElseThrow(() -> new NoSuchElementException("team " + teamId));
        String r = role == null || role.isBlank() ? "member" : role.trim();
        if (!r.equals("member") && !r.equals("admin")) throw new IllegalArgumentException("role must be member or admin");
        User u = userService.resolve(required(handle, "handle"));
        TeamMember m = members.findById(new TeamMember.Key(teamId, u.getId())).orElseGet(TeamMember::new);
        m.setTeamId(teamId);
        m.setUserId(u.getId());
        m.setRole(r);
        m = members.saveAndFlush(m);
        scoping.rescope();
        return m;
    }

    @Transactional
    public void removeMember(UUID teamId, UUID userId) {
        members.deleteById(new TeamMember.Key(teamId, userId));
    }

    @Transactional
    public TeamRepo linkRepo(UUID teamId, UUID repoId) {
        Team t = teams.findById(teamId).orElseThrow(() -> new NoSuchElementException("team " + teamId));
        Repo r = repos.findById(repoId).orElseThrow(() -> new NoSuchElementException("repo " + repoId));
        if (!t.getApplicationId().equals(r.getApplicationId()))
            throw new IllegalArgumentException("team and repo belong to different applications");
        TeamRepo tr = new TeamRepo();
        tr.setTeamId(teamId);
        tr.setRepoId(repoId);
        tr = teamRepos.saveAndFlush(tr);
        scoping.rescope();
        return tr;
    }

    @Transactional
    public void unlinkRepo(UUID teamId, UUID repoId) {
        teamRepos.deleteById(new TeamRepo.Key(teamId, repoId));
    }

    private static String slug(String s) {
        String v = required(s, "slug").toLowerCase(Locale.ROOT);
        if (!SLUG.matcher(v).matches())
            throw new IllegalArgumentException("slug must be lowercase letters, digits and dashes, starting with a letter or digit");
        return v;
    }

    private static String required(String s, String field) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException(field + " is required");
        return s.trim();
    }
}
