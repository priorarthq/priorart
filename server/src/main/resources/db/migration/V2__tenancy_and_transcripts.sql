-- Tenancy: application -> teams / repos, users <-> teams, teams <-> repos.
-- Transcripts move out of sessions into their own 1:1 table.

create table users (
  id           uuid primary key default gen_random_uuid(),
  handle       text not null unique,
  display_name text,
  email        text unique,
  created_at   timestamptz not null default now()
);

create table applications (
  id         uuid primary key default gen_random_uuid(),
  slug       text not null unique,
  name       text not null,
  created_at timestamptz not null default now()
);

create table teams (
  id             uuid primary key default gen_random_uuid(),
  application_id uuid not null references applications(id) on delete cascade,
  slug           text not null,
  name           text not null,
  created_at     timestamptz not null default now(),
  unique (application_id, slug)
);

create table team_members (
  team_id    uuid not null references teams(id) on delete cascade,
  user_id    uuid not null references users(id) on delete cascade,
  role       text not null default 'member' check (role in ('member', 'admin')),
  created_at timestamptz not null default now(),
  primary key (team_id, user_id)
);
create index team_members_user_idx on team_members (user_id);

create table repos (
  id             uuid primary key default gen_random_uuid(),
  application_id uuid not null references applications(id) on delete cascade,
  remote_url     text not null unique,            -- normalized: host/org/repo, lowercase, no scheme/creds/.git
  name           text not null,
  created_at     timestamptz not null default now()
);

create table team_repos (
  team_id    uuid not null references teams(id) on delete cascade,
  repo_id    uuid not null references repos(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (team_id, repo_id)
);
create index team_repos_repo_idx on team_repos (repo_id);

-- Transcripts: the agent's native file, verbatim. Postgres TOAST compresses large values.
create table session_transcripts (
  session_id  uuid primary key references sessions(id) on delete cascade,
  format      text not null default 'jsonl',
  content     text not null,
  bytes       int  not null,
  lines       int  not null,
  sha256      text not null,
  source_path text,
  captured_at timestamptz not null default now()
);

insert into session_transcripts (session_id, content, bytes, lines, sha256, source_path, captured_at)
select id,
       transcript_raw,
       octet_length(transcript_raw),
       (select count(*) from regexp_split_to_table(transcript_raw, E'\n') l where l <> ''),
       encode(sha256(convert_to(transcript_raw, 'UTF8')), 'hex'),
       transcript_path,
       ended_at
from sessions;

alter table sessions drop column transcript_raw, drop column transcript_path;

-- Sessions: who / which team / which repo. Nullable: capture never fails on missing setup.
alter table sessions
  add column user_id uuid references users(id) on delete set null,
  add column team_id uuid references teams(id) on delete set null,
  add column repo_id uuid references repos(id) on delete set null;
create index sessions_user_idx on sessions (user_id);
create index sessions_team_idx on sessions (team_id);
create index sessions_repo_idx on sessions (repo_id);

-- sessions.repo now holds the normalized remote (same rules as RemoteUrls.normalize in Java).
update sessions set repo = lower(
  regexp_replace(
    regexp_replace(
      regexp_replace(
        regexp_replace(repo, '^(https?|ssh|git)://([^@/]+@)?', '', 'i'),
        '^[^@/]+@([^:/]+):(?!\d+/)', '\1/'),
      '^([^/:]+):\d+/', '\1/'),
    '(\.git)?/*$', ''))
where repo is not null and repo <> '';

insert into users (handle)
select distinct author from sessions where author is not null and author <> ''
union
select distinct promoted_by from records where promoted_by is not null and promoted_by <> ''
on conflict (handle) do nothing;

update sessions s set user_id = u.id from users u where u.handle = s.author;

-- Records: team scope, promoter as a real user, and the transcript version they were extracted from.
alter table records
  add column team_id uuid references teams(id) on delete set null,
  add column promoted_by_id uuid references users(id) on delete set null,
  add column transcript_sha256 text;
update records r set promoted_by_id = u.id from users u where u.handle = r.promoted_by;
alter table records drop column promoted_by;
alter table records rename column promoted_by_id to promoted_by;
update records r set transcript_sha256 = t.sha256 from session_transcripts t where t.session_id = r.session_id;
create index records_team_idx on records (team_id);
