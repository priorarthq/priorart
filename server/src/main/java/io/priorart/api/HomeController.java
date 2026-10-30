package io.priorart.api;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** What someone sees when they open the server's address in a browser: it runs, and how to use it. */
@Controller
public class HomeController {
    @GetMapping("/")
    public ResponseEntity<String> home() {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body("""
                <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Prior Art</title><style>
                body{font:16px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;max-width:640px;margin:48px auto;padding:0 16px;color:#1a1a1a;background:#fff}
                @media (prefers-color-scheme:dark){body{color:#ececec;background:#121212}code{background:#222!important}}
                h1{font-size:24px;margin:0 0 4px} .ok{color:#1e8449;font-weight:600} code{background:#f3f3f3;padding:2px 6px;border-radius:4px}
                </style></head><body>
                <h1>Prior Art</h1><p class="ok">Your team's Prior Art server is running.</p>
                <p>Prior Art is used from your coding agent (Claude Code, Codex, GitHub Copilot CLI), not from this page.</p>
                <ol><li>Install the plugin: <code>/plugin marketplace add priorarthq/priorart</code>, then <code>/plugin install priorart@priorart</code> (Claude Code)</li>
                <li>Run <code>/priorart:onboard</code> and give it this server's address when asked</li></ol>
                </body></html>
                """);
    }
}
