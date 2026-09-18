-- V008__audit_immutability_trigger.sql
--
-- V001 revokes UPDATE/DELETE from the optional backend_role, but local
-- docker-compose uses the table-owning jobagent role. Ownership means a
-- privilege-only check cannot make audit_logs immutable in that environment.
-- This trigger closes that gap: application UPDATE/DELETE attempts fail at
-- the database boundary, while INSERT (the only required operation) remains
-- available.

create or replace function reject_audit_log_mutation()
returns trigger
language plpgsql
as $$
begin
  raise exception 'audit_logs is append-only: % is forbidden', tg_op
    using errcode = '55000';
end;
$$;

drop trigger if exists audit_logs_immutable on audit_logs;

create trigger audit_logs_immutable
before update or delete on audit_logs
for each row execute function reject_audit_log_mutation();
