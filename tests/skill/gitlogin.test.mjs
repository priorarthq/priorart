// node --test 'tests/skill/*.test.mjs'   — git login: link a GitLab/GitHub account with the device flow.
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { execFile } from "node:child_process";
import { mkdtempSync, writeFileSync, mkdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const CLI = fileURLToPath(new URL("../../plugins/priorart/skills/priorart/scripts/priorart.mjs", import.meta.url));
let server, base, approved = false, linked = null;

// One stub plays the team server (/api/...) and the GitLab instance (/oauth/...).
before(async () => {
  server = createServer((req, res) => {
    let body = "";
    req.on("data", (c) => (body += c));
    req.on("end", () => {
      const send = (status, data) => { res.writeHead(status, { "content-type": "application/json" }); res.end(JSON.stringify(data)); };
      const path = req.url.split("?")[0];
      if (path === "/api/git/hosts") return send(200, { mode: "git", linked: [], hosts: [{ host: "gitlab.example", provider: "gitlab", clientId: "gl-app",
        deviceCodeUrl: `${base}/oauth/authorize_device`, tokenUrl: `${base}/oauth/token`, scope: "read_user" }] });
      if (path === "/oauth/authorize_device") {
        assert.match(body, /client_id=gl-app/); assert.match(body, /scope=read_user/);
        return send(200, { device_code: "dc-1", user_code: "ABCD-1234", verification_uri: `${base}/oauth/device`, interval: 0, expires_in: 600 });
      }
      if (path === "/oauth/token") return send(200, approved ? { access_token: "gl-user-token", token_type: "Bearer" } : { error: "authorization_pending" });
      if (path === "/api/git/identities") {
        assert.equal(req.headers.authorization, "Bearer pt_test", "the personal token goes with it");
        linked = JSON.parse(body);
        return send(200, { host: linked.host, username: "jordan" });
      }
      send(404, { error: `no route ${req.method} ${path}` });
    });
  });
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  base = `http://127.0.0.1:${server.address().port}`;
});
after(() => server.close());

test("git login links the user's account through the host's device flow", async () => {
  const home = mkdtempSync(join(tmpdir(), "priorart-gitlogin-"));
  mkdirSync(join(home, ".priorart"));
  writeFileSync(join(home, ".priorart", "config.json"), JSON.stringify({ serverUrl: base, author: "jordan", token: "pt_test" }));
  const env = { ...process.env, HOME: home };
  for (const k of ["PRIORART_URL", "PRIORART_AUTHOR", "PRIORART_TOKEN"]) delete env[k];
  const cli = (...args) => new Promise((resolve) => execFile(process.execPath, [CLI, ...args], { cwd: home, env },
    (err, stdout, stderr) => resolve({ code: err?.code ?? 0, out: stdout + stderr })));

  let r = await cli("git", "login");
  assert.equal(r.code, 0, r.out);
  assert.match(r.out, /GITLAB\.EXAMPLE SIGN-IN/);
  assert.match(r.out, /Enter the code: ABCD-1234/);

  approved = true;   // the user enters the code on GitLab
  r = await cli("git", "login", "--finish");
  assert.equal(r.code, 0, r.out);
  assert.match(r.out, /linked: jordan on gitlab\.example/);
  assert.deepEqual(linked, { host: "gitlab.example", token: "gl-user-token" });
});
