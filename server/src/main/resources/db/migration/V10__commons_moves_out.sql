-- The public commons now has its own service and database (priorarthq/commons); a team server keeps only where its
-- records were published (records.published_url / published_at).
drop table if exists commons_flags, commons_outcomes, commons_votes, commons_publisher_tokens, commons_records, commons_publishers;
drop function if exists priorart_join_tags(text[]);
