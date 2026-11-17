-- V054__date_precision_metadata.sql
--
-- Preserves year-only date precision in database persistence for work experiences
-- and certifications. When a resume or candidate specifies year-only dates (e.g. "2021"),
-- PostgreSQL DATE columns require a full date (e.g. 2021-01-01). The precision metadata
-- columns record whether the date was given as 'YEAR' or 'MONTH' so that profile retrieval
-- and resume/document generation can render "2021" without inventing "Jan 2021".

ALTER TABLE work_experiences
  ADD COLUMN IF NOT EXISTS start_date_precision text NOT NULL DEFAULT 'MONTH',
  ADD COLUMN IF NOT EXISTS end_date_precision text;

UPDATE work_experiences
  SET end_date_precision = 'MONTH'
  WHERE end_month IS NOT NULL AND end_date_precision IS NULL;

ALTER TABLE certifications
  ADD COLUMN IF NOT EXISTS issued_on_precision text;

UPDATE certifications
  SET issued_on_precision = 'MONTH'
  WHERE issued_on IS NOT NULL AND issued_on_precision IS NULL;
