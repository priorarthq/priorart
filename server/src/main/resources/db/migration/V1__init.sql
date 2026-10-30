create extension if not exists pgcrypto;

create table sessions (
  id              uuid primary key default gen_random_uuid(),
  agent           text not null,                 -- copilot-cli | claude-code | ...
  external_id     text not null,                 -- the agent's own session id
  author          text not null,
  cwd             text,
  repo            text,                          -- git remote origin, if any
  git_branch      text,
  head_sha        text,
  had_diff        boolean not null default false,-- working tree changed during session
  started_at      timestamptz,
  ended_at        timestamptz not null default now(),
  transcript_path text,
  transcript_raw  text not null,                 -- native transcript, untouched
  prompts         text not null default '',      -- extracted user prompts (for zero-record search)
  turns           int  not null default 0,
  search_vec      tsvector generated always as (to_tsvector('english', coalesce(prompts, ''))) stored,
  unique (agent, external_id)
);
create index sessions_search_idx on sessions using gin (search_vec);
create index sessions_ended_idx on sessions (ended_at desc);

create table records (
  id             uuid primary key default gen_random_uuid(),
  session_id     uuid not null references sessions(id) on delete cascade,
  status         text not null default 'draft',  -- draft | promoted | stale | rejected
  kind           text not null default 'investigation',
  title          text not null,
  question       text not null default '',
  findings       jsonb not null default '[]',    -- [string]
  evidence       jsonb not null default '[]',    -- [{claim, turn, tool, detail}]
  decision       text not null default '',
  outcome        text not null default 'unknown',
  tags           text[] not null default '{}',
  commit_sha     text,                           -- code state the record was true for
  touched_files  text[] not null default '{}',
  author         text not null,
  created_at     timestamptz not null default now(),
  promoted_at    timestamptz,
  promoted_by    text,
  hits           int not null default 0,
  search_vec     tsvector generated always as (
                   setweight(to_tsvector('english', coalesce(title,'')), 'A') ||
                   setweight(to_tsvector('english', coalesce(question,'')), 'B') ||
                   setweight(to_tsvector('english', coalesce(decision,'')), 'B') ||
                   setweight(to_tsvector('english', coalesce(findings::text,'')), 'C')
                 ) stored
);
create index records_search_idx on records using gin (search_vec);
create index records_session_idx on records (session_id);
create index records_status_idx on records (status);
