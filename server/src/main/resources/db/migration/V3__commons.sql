-- Public commons ("Stack Overflow for agents"). Any Prior Art server can serve it (PRIORART_COMMONS_ENABLED=true);
-- a team server only uses the two columns added to records at the bottom.

-- array_to_string() is only STABLE, which generated columns don't allow; joining text with a space is immutable.
create function priorart_join_tags(text[]) returns text language sql immutable parallel safe
  as $$ select array_to_string($1, ' ') $$;

create table commons_publishers (
  id           uuid primary key default gen_random_uuid(),
  handle       text not null unique,                 -- public name; not necessarily the corp handle
  token_sha256 text not null unique,                 -- publisher token, stored hashed
  created_at   timestamptz not null default now()
);

create table commons_records (
  id           uuid primary key default gen_random_uuid(),
  publisher_id uuid not null references commons_publishers(id) on delete cascade,
  title        text not null,
  problem      text not null,
  environment  jsonb not null default '{}',          -- {"language": "...", "framework": "...", "versions": "..."}
  tags         text[] not null default '{}',
  tried        jsonb not null default '[]',          -- what was tried and did NOT work: the negative results
  findings     jsonb not null default '[]',
  solution     text not null default '',
  evidence     text not null default '',
  source_agent text,
  license      text not null default 'CC-BY-4.0',    -- accepted explicitly at publish time; includes dataset/training use
  status       text not null default 'published',    -- published | hidden (flagged)
  upvotes      int not null default 0,
  downvotes    int not null default 0,
  worked       int not null default 0,               -- "this worked for me": the verified-outcome signal
  didnt_work   int not null default 0,
  flags        int not null default 0,
  created_at   timestamptz not null default now(),
  search_vec   tsvector generated always as (
                 setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
                 setweight(to_tsvector('english', priorart_join_tags(tags)), 'A') ||
                 setweight(to_tsvector('english', coalesce(problem, '')), 'B') ||
                 setweight(to_tsvector('english', coalesce(solution, '')), 'B') ||
                 setweight(to_tsvector('english', coalesce(findings::text, '') || ' ' || coalesce(tried::text, '')), 'C')
               ) stored
);
create index commons_records_search_idx on commons_records using gin (search_vec);
create index commons_records_status_idx on commons_records (status);

create table commons_votes (
  record_id    uuid not null references commons_records(id) on delete cascade,
  publisher_id uuid not null references commons_publishers(id) on delete cascade,
  value        smallint not null check (value in (-1, 1)),
  created_at   timestamptz not null default now(),
  primary key (record_id, publisher_id)
);

create table commons_outcomes (
  record_id    uuid not null references commons_records(id) on delete cascade,
  publisher_id uuid not null references commons_publishers(id) on delete cascade,
  worked       boolean not null,
  note         text not null default '',
  created_at   timestamptz not null default now(),
  primary key (record_id, publisher_id)
);

create table commons_flags (
  record_id    uuid not null references commons_records(id) on delete cascade,
  publisher_id uuid not null references commons_publishers(id) on delete cascade,
  reason       text not null,
  created_at   timestamptz not null default now(),
  primary key (record_id, publisher_id)
);

-- Team side: remember where a team record was published.
alter table records
  add column published_url text,
  add column published_at  timestamptz;
