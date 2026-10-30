-- A session's team must be justified by its repo being linked to that team. Sessions assigned by the removed
-- "author's only team" fallback (personal side projects pulled into a team) go back to unassigned = private.
update sessions s set team_id = null
where s.team_id is not null
  and not exists (select 1 from team_repos tr where tr.team_id = s.team_id and tr.repo_id = s.repo_id);
update records r set team_id = s.team_id from sessions s where r.session_id = s.id and r.team_id is distinct from s.team_id;

-- Handles are case-insensitive: "Lena" and "lena" are the same name. Where two users differ only by case, the older
-- one keeps the name (lowercased) and the newer one, a lookalike claimed later, gets a suffix; its sessions and
-- records follow the rename.
with ranked as (
  select id, handle, lower(handle) as base,
         row_number() over (partition by lower(handle) order by created_at, id) as n
  from users
), renamed as (
  select id, handle as old, case when n = 1 then base else base || '-' || left(id::text, 6) end as new
  from ranked where handle <> (case when n = 1 then base else base || '-' || left(id::text, 6) end)
), s as (
  update sessions set author = r.new from renamed r where sessions.author = r.old returning 1
), rec as (
  update records set author = r.new from renamed r where records.author = r.old returning 1
)
update users u set handle = r.new from renamed r where u.id = r.id;
create unique index users_handle_lower_idx on users (lower(handle));
