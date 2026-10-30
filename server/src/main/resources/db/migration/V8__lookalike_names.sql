-- Public names that differ only in '-', '_' or '.' ("spa_tinkerer", "spa.tinkerer", "spatinkerer" next to
-- "spa-tinkerer") read as the same person on the site, so they count as the same name. The older keeps it; a newer
-- look-alike gets a suffix.
with ranked as (
  select id, handle, row_number() over (partition by lower(regexp_replace(handle, '[-_.]', '', 'g')) order by created_at, id) as n
  from commons_publishers
)
update commons_publishers p set handle = r.handle || '-' || left(p.id::text, 6) from ranked r where p.id = r.id and r.n > 1;
create unique index commons_publishers_handle_shape_idx on commons_publishers (lower(regexp_replace(handle, '[-_.]', '', 'g')));
