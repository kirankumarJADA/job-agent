-- V050__source_management_operator_role.sql
-- Role-based authorization for operational source management (Phase 18 Security Closure).
-- Adds 'role' column to users table defaulting to 'CANDIDATE'.
-- All existing user accounts remain 'CANDIDATE' (no silent elevation).
-- Shared crawler and operational source controls are restricted to OPERATOR and ADMIN roles.

alter table users add column if not exists role text not null default 'CANDIDATE';

do $$
begin
  if not exists (select 1 from pg_constraint where conname = 'users_role_check') then
    alter table users add constraint users_role_check
      check (role in ('CANDIDATE', 'OPERATOR', 'ADMIN'));
  end if;
end $$;
