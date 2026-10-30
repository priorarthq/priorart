-- Team identity: a user claims their handle once and gets a personal token (stored hashed). With
-- priorart.team-auth=required (default) every team API call carries it and the server derives who you are from it.
alter table users
  add column token_sha256 text unique,
  add column claimed_at   timestamptz;

-- Joining a team that already has an admin needs that admin's approval.
create table team_join_requests (
  team_id      uuid not null references teams(id) on delete cascade,
  user_id      uuid not null references users(id) on delete cascade,
  requested_at timestamptz not null default now(),
  primary key (team_id, user_id)
);
