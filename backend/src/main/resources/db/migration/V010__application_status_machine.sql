alter table applications drop constraint if exists applications_status_check;
alter table applications add constraint applications_status_check check (status in ('READY_TO_APPLY','APPLICATION_STARTED','OTP_PENDING','APPLICATION_SUBMITTED','CONFIRMATION_RECEIVED','RECRUITER_CONTACT','INTERVIEW','ASSESSMENT','REJECTED','OFFER','WITHDRAWN','FAILED'));
create index if not exists application_events_type_idx on application_events(application_id,type,occurred_at desc);
create index if not exists applications_status_idx on applications(status,updated_at desc);
