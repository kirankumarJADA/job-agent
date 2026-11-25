do $$
declare
    v_schema text := current_schema();
    v_table_oid oid;
    v_constraints text[];
begin
    select c.oid
      into v_table_oid
      from pg_class c
      join pg_namespace n on n.oid = c.relnamespace
     where n.nspname = v_schema
       and c.relname = 'candidate_sandbox_registrations'
       and c.relkind in ('r', 'p');

    if v_table_oid is null then
        raise exception 'Expected table %.candidate_sandbox_registrations does not exist', v_schema;
    end if;

    select coalesce(array_agg(con.conname order by con.conname), array[]::text[])
      into v_constraints
      from pg_constraint con
     where con.conrelid = v_table_oid
       and con.contype = 'u'
       and (
           select array_agg(att.attname::text order by cols.ord)
             from unnest(con.conkey) with ordinality as cols(attnum, ord)
             join pg_attribute att
               on att.attrelid = con.conrelid
              and att.attnum = cols.attnum
              and not att.attisdropped
       ) = array['profile_id', 'ats_platform', 'posting_url']::text[];

    if coalesce(array_length(v_constraints, 1), 0) <> 1 then
        raise exception 'Expected exactly one UNIQUE (profile_id, ats_platform, posting_url) constraint on %.candidate_sandbox_registrations, found % (%)',
            v_schema,
            coalesce(array_length(v_constraints, 1), 0),
            array_to_string(v_constraints, ', ');
    end if;

    execute format(
        'alter table %I.%I drop constraint %I',
        v_schema,
        'candidate_sandbox_registrations',
        v_constraints[1]
    );
end $$;

create unique index candidate_sandbox_registrations_active_uidx
    on candidate_sandbox_registrations (profile_id, ats_platform, posting_url)
    where revoked_at is null;

create index submission_receipts_sandbox_registration_idx
    on submission_receipts (sandbox_registration_id);
