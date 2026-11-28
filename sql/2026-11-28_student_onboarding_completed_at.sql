-- Migration: track the first-login setup of a student account (MySQL 8.4)
--
-- A new student cannot use FAAPS before they choose their Major & Curriculum and
-- their Specialization & Electives. The student portal shows a blocking setup
-- modal until both are saved; this column records that the setup was finished.
--
-- The migration is idempotent: it only adds the column when it is missing, and
-- it backfills the students that already have a curriculum so returning students
-- are not asked to set up again.

USE faaps;

-- 1. Add the column when it does not exist yet.
SET @has_column := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'students' AND COLUMN_NAME = 'onboarding_completed_at');

SET @add_column_sql := 'ALTER TABLE `students` ADD COLUMN `onboarding_completed_at` timestamp NULL COMMENT ''Set when the student finishes the first-login setup (major, specialization)''';

SET @noop_sql := 'SELECT 1';

SET @stmt := IF(@has_column = 0, @add_column_sql, @noop_sql);
PREPARE s FROM @stmt;
EXECUTE s;
DEALLOCATE PREPARE s;

-- 2. Backfill: a student that already has a curriculum is considered set up.
UPDATE `students` SET `onboarding_completed_at` = COALESCE(`onboarding_completed_at`, CURRENT_TIMESTAMP) WHERE `curriculum_version_id` IS NOT NULL AND `onboarding_completed_at` IS NULL;

-- 3. Result
SELECT COUNT(*) AS students_total, SUM(`onboarding_completed_at` IS NOT NULL) AS onboarding_completed FROM `students`;
