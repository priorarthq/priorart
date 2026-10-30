package io.priorart.api;

import io.priorart.directory.ScopingService;
import io.priorart.directory.ScopingService.Scope;
import io.priorart.auth.Identity;
import io.priorart.search.SearchService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.Locale;

@RestController
@RequestMapping("/api/search")
public class SearchController {
    private final SearchService search;
    private final ScopingService scoping;
    private final Identity identity;

    public SearchController(SearchService search, ScopingService scoping, Identity identity) {
        this.search = search;
        this.scoping = scoping;
        this.identity = identity;
    }

    /**
     * @param user  handle of the person (or agent's owner) searching; enables team scoping
     * @param scope          team | app (default) | all
     * @param excludeSession the agent's own session id when searching from inside a session
     */
    @GetMapping
    public SearchService.Results search(@RequestParam String q,
                                        @RequestParam(defaultValue = "10") int limit,
                                        @RequestParam(required = false) String user,
                                        @RequestParam(defaultValue = "app") String scope,
                                        @RequestParam(required = false) String excludeSession,
                                        HttpServletRequest req) {
        user = identity.handle(req, user); // with auth, ?user= is ignored: you search as yourself
        if (q.length() > 1000) throw new IllegalArgumentException("query too long (max 1000 characters)");
        Scope s;
        try { s = Scope.valueOf(scope.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("scope must be team, app or all"); }
        ScopingService.Resolved resolved = identity.isInsider(req) ? scoping.forUser(user, s)
                : new ScopingService.Resolved(Scope.TEAM, java.util.Set.of()); // not in a team yet: only your own work
        return search.search(q, Math.max(1, Math.min(limit, 50)), resolved, user, excludeSession, identity.readableRepos(req));
    }
}
