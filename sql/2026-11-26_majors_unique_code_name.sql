-- Migration: Allow multiple major names under the same major code (idempotent for MySQL 8.4)
--
-- FLM lists more than one programme under a major code, so the uniqueness has to
-- be on the pair (code, name). Before this migration the table had a single
-- UNIQUE index on `code`, which made the structure import fail with
-- "Duplicate entry 'LLB' for key 'majors.code'" whenever a sheet carried two
-- majors that share a code.
--
-- The migration is idempotent: it only drops the old single-column index when
-- it still exists, and only creates the composite one when it is missing.

USE faaps;

-- 1. Drop the legacy single-column unique index on `code`, if it is still there.
SET @has_code_uq := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'majors'
    AND INDEX_NAME = 'code'
    AND NON_UNIQUE = 0
    AND SEQ_IN_INDEX = 1
    AND (SELECT COUNT(*) FROM information_schema.STATISTICS s2
         WHERE s2.TABLE_SCHEMA = DATABASE()
           AND s2.TABLE_NAME = 'majors'
           AND s2.INDEX_NAME = 'code') = 1
);
SET @sql_drop_code_uq := IF(
  @has_code_uq = 1,
  'ALTER TABLE `majors` DROP INDEX `code`',
  'SELECT 1'
);
PREPARE stmt FROM @sql_drop_code_uq;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. Create the composite unique index and the lookup index, if missing.
SET @has_code_name_uq := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'majors'
    AND INDEX_NAME = 'uq_majors_code_name'
);
SET @sql_add_code_name_uq := IF(
  @has_code_name_uq = 0,
  'ALTER TABLE `majors` ADD UNIQUE KEY `uq_majors_code_name` (`code`, `name`)',
  'SELECT 1'
);
PREPARE stmt FROM @sql_add_code_name_uq;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_code_idx := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'majors'
    AND INDEX_NAME = 'idx_majors_code'
);
SET @sql_add_code_idx := IF(
  @has_code_idx = 0,
  'ALTER TABLE `majors` ADD INDEX `idx_majors_code` (`code`)',
  'SELECT 1'
);
PREPARE stmt FROM @sql_add_code_idx;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3. Result
SHOW INDEX FROM `majors`;
