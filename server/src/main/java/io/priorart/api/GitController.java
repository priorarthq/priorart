package io.priorart.api;

import io.priorart.auth.Identity;
import io.priorart.auth.RepoAccess;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/**
 * Linking a person's GitHub / GitLab account, so the server can check their access to a repo with that host (see
 * {@link RepoAccess}). The skill runs the host's device flow on the person's machine and sends the resulting token
 * here once; the server reads who it belongs to and forgets it.
 */
@RestController
@RequestMapping("/api/git")
public class GitController {
    private final RepoAccess access;
    private final Identity identity;

    public GitController(RepoAccess access, Identity identity) {
        this.access = access;
        this.identity = identity;
    }

    public record Hosts(String mode, List<RepoAccess.Host> hosts, List<RepoAccess.LinkedAccount> linked) {}
    public record Link(String host, String token) {}

    @GetMapping("/hosts")
    public Hosts hosts(HttpServletRequest req) {
        return new Hosts(access.mode(), access.hosts(), identity.required() ? access.linked(identity.user(req, null)) : List.of());
    }

    @PostMapping("/identities")
    public Map<String, Object> link(@RequestBody Link b, HttpServletRequest req) {
        RepoAccess.LinkedAccount a = access.link(identity.user(req, null), b.host(), b.token());
        return Map.of("host", a.host(), "username", a.username());
    }
}
