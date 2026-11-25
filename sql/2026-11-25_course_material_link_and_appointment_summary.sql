-- =============================================================================
-- Migration: 2026-11-25 Course Material Link & Appointment Consultation Summary
-- Safe to run multiple times (idempotent on MySQL 8.0 / 8.4).
-- =============================================================================

USE faaps;

-- 1. Add courses.material_link VARCHAR(500) NULL
SET @col_exists := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'courses' AND COLUMN_NAME = 'material_link'
);
SET @sql_stmt := IF(@col_exists = 0,
  'ALTER TABLE `courses` ADD COLUMN `material_link` VARCHAR(500) NULL AFTER `learning_outcomes`',
  'SELECT 1'
);
PREPARE stmt FROM @sql_stmt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Migrate any URL previously placed into learning_outcomes or objectives into material_link
UPDATE `courses`
SET `material_link` = TRIM(`learning_outcomes`),
    `learning_outcomes` = NULL
WHERE `material_link` IS NULL
  AND (`learning_outcomes` LIKE 'http://%' OR `learning_outcomes` LIKE 'https://%');

UPDATE `courses`
SET `material_link` = TRIM(`objectives`),
    `objectives` = NULL
WHERE `material_link` IS NULL
  AND (`objectives` LIKE 'http://%' OR `objectives` LIKE 'https://%');

-- 2. Add advising_sessions.consultation_summary TEXT NULL
SET @cs_exists := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'advising_sessions' AND COLUMN_NAME = 'consultation_summary'
);
SET @cs_stmt := IF(@cs_exists = 0,
  'ALTER TABLE `advising_sessions` ADD COLUMN `consultation_summary` TEXT NULL AFTER `summary`',
  'SELECT 1'
);
PREPARE stmt2 FROM @cs_stmt;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;

-- Backfill consultation_summary for already COMPLETED appointments
UPDATE `advising_sessions`
SET `consultation_summary` = `summary`
WHERE `status` = 'COMPLETED'
  AND `consultation_summary` IS NULL
  AND `summary` IS NOT NULL;
