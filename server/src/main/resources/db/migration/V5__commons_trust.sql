-- Commons trust: publishers are GitHub-verified (one per GitHub account); every vote, outcome and flag carries the
-- weight its publisher had at the time; ranking and review use the weighted sums.
alter table commons_publishers
  add column github_id         bigint unique,
  add column github_login      text,
  add column github_created_at timestamptz;

create table commons_publisher_tokens (                  -- one per signed-in machine
  token_sha256 text primary key,
  publisher_id uuid not null references commons_publishers(id) on delete cascade,
  created_at   timestamptz not null default now()
);
insert into commons_publisher_tokens (token_sha256, publisher_id, created_at)
select token_sha256, id, created_at from commons_publishers;
alter table commons_publishers drop column token_sha256;

alter table commons_votes    add column weight real not null default 1;
alter table commons_outcomes add column weight real not null default 1;
alter table commons_flags    add column weight real not null default 1;

alter table commons_records
  add column vote_score    real not null default 0,      -- sum(weight * value)
  add column outcome_score real not null default 0,      -- sum(weight) worked - sum(weight) didn't
  add column flag_score    real not null default 0;      -- sum(weight); >= threshold sends the record to review
update commons_records set vote_score = upvotes - downvotes, outcome_score = worked - didnt_work, flag_score = flags;
-- status: published | under_review | removed   ('hidden' from V3 becomes 'under_review')
update commons_records set status = 'under_review' where status = 'hidden';
