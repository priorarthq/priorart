-- Who may create applications and teams. Before this, anyone with a token could create a team, become its admin and
-- so count as "in a team", which is what unlocks reading other teams' work: the approval step protected nothing.
-- Now applications are created by server admins (the first person to create one on an empty server becomes one) and
-- teams by server admins or admins of a team in that application. The admins of the server's first team become
-- server admins (not every team admin: on an upgraded server some teams were created through that very hole).
-- PRIORART_SERVER_ADMINS can name more; server-admin <handle> adds one.
alter table users add column server_admin boolean not null default false;
update users set server_admin = true where id in (
  select m.user_id from team_members m where m.role = 'admin' and m.team_id = (select id from teams order by created_at, id limit 1));

-- A session belongs to a team only if its author is a member of that team. Before, a repo owned by one team pulled in
-- anyone's session from it, so a non-member could plant work in a team's memory (and its teammates' prompt hints).
update sessions s set team_id = null
where s.team_id is not null
  and not exists (select 1 from team_members m where m.team_id = s.team_id and m.user_id = s.user_id);
update records r set team_id = s.team_id from sessions s where r.session_id = s.id and r.team_id is distinct from s.team_id;

-- A person's sessions in a repo go to the team only once they've onboarded that repo themselves. Otherwise a teammate
-- could link someone's private side project to the team and pull their sessions in it into team memory. Sessions
-- already in a team keep their place.
create table repo_members (
  repo_id    uuid not null references repos(id) on delete cascade,
  user_id    uuid not null references users(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (repo_id, user_id)
);
insert into repo_members (repo_id, user_id)
select distinct repo_id, user_id from sessions where team_id is not null and repo_id is not null and user_id is not null;

-- Public names are case-insensitive: "Spa-Tinkerer" must not pass for "spa-tinkerer". Where two differ only by case,
-- the older keeps its name and the newer gets a suffix.
with ranked as (
  select id, handle, row_number() over (partition by lower(handle) order by created_at, id) as n from commons_publishers
)
update commons_publishers p set handle = r.handle || '-' || left(p.id::text, 6) from ranked r where p.id = r.id and r.n > 1;
create unique index commons_publishers_handle_lower_idx on commons_publishers (lower(handle));

-- status: published | under_review | removed | withdrawn (taken down by its own publisher)
