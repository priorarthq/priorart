package io.priorart.directory;

import io.priorart.auth.Forbidden;
import io.priorart.auth.Identity;
import io.priorart.domain.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.UUID;

/**
 * Who may create what. Being in a team is what lets you read other teams' work, so creating a team must not be a way
 * around a team admin's approval. Applications: server admins (on an empty server, whoever creates the first one becomes
 * a server admin). Teams: server admins, or admins of a team in that application. Repos: members of a team in that
 * application. With team-auth off (single-user mode) everything is allowed.
 */
@Component
public class CreationRights {
    private final Identity identity;
    private final ApplicationRepository apps;
    private final TeamRepository teams;
    private final TeamMemberRepository members;
    private final UserRepository users;
    private final java.util.Set<String> configured;   // PRIORART_SERVER_ADMINS: handles that are server admins by configuration

    public CreationRights(Identity identity, ApplicationRepository apps, TeamRepository teams, TeamMemberRepository members, UserRepository users,
                          @Value("${priorart.server-admins:}") String serverAdmins) {
        this.configured = java.util.Arrays.stream(serverAdmins.split(",")).map(String::trim).map(h -> h.toLowerCase(java.util.Locale.ROOT))
                .filter(h -> !h.isEmpty()).collect(java.util.stream.Collectors.toSet());
        this.identity = identity;
        this.apps = apps;
        this.teams = teams;
        this.members = members;
        this.users = users;
    }

    public boolean isServerAdmin(User me) {
        return me.isServerAdmin() || configured.contains(me.getHandle());
    }

    /** Checks the caller may create an application; on an empty server makes them its first server admin (returns true then). */
    public boolean application(User me) {
        if (!identity.required() || isServerAdmin(me)) return false;
        if (apps.count() == 0 && configured.isEmpty()) {
            me.setServerAdmin(true);
            users.save(me);
            return true;
        }
        throw new Forbidden("only a server admin can create an application (server admins: " + serverAdmins()
                + "). Ask one to create it for you, or to make you a server admin: priorart.mjs server-admin <handle>");
    }

    public void team(User me, Application app) {
        if (!identity.required() || isServerAdmin(me) || role(me, app.getId(), true)) return;
        throw new Forbidden("only an admin of a team in '" + app.getSlug() + "' or a server admin can create a team there (server admins: "
                + serverAdmins() + ")");
    }

    public boolean mayRegisterRepo(User me, UUID appId) {
        return !identity.required() || isServerAdmin(me) || role(me, appId, false);
    }

    public void serverAdmin(User me) {
        if (identity.required() && !isServerAdmin(me)) throw new Forbidden("only a server admin can do that (server admins: " + serverAdmins() + ")");
    }

    /** Whether the user is in (adminOnly: administers) a team of this application. */
    private boolean role(User me, UUID appId, boolean adminOnly) {
        for (UUID tid : members.teamIdsOf(me.getId())) {
            Team t = teams.findById(tid).orElse(null);
            if (t == null || !t.getApplicationId().equals(appId)) continue;
            if (!adminOnly) return true;
            if (members.findById(new TeamMember.Key(tid, me.getId())).map(m -> "admin".equals(m.getRole())).orElse(false)) return true;
        }
        return false;
    }

    public java.util.List<String> serverAdminHandles() {
        java.util.Set<String> all = new java.util.TreeSet<>(configured);
        users.findAll().stream().filter(User::isServerAdmin).map(User::getHandle).forEach(all::add);
        return java.util.List.copyOf(all);
    }

    private String serverAdmins() {
        String s = String.join(", ", serverAdminHandles());
        return s.isEmpty() ? "none" : s;
    }
}
