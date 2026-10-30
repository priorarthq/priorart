-- A person's account on a git host (GitHub, GitLab), verified once by signing in there: the sign-in token is used to
-- read who they are and then thrown away. With repo-access=git the server asks that host whether this account can read
-- a repo before showing anything from it.
create table git_identities (
  user_id     uuid not null references users(id) on delete cascade,
  host        text not null,                 -- github.com, gitlab.com, gitlab.corp.example
  external_id text not null,                 -- the host's numeric user id
  username    text not null,
  verified_at timestamptz not null default now(),
  primary key (user_id, host),
  unique (host, external_id)                 -- one Prior Art user per git account
);
