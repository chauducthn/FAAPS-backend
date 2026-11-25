-- Migration: Core 5-flow enhancements (idempotent for MySQL 8.4)
-- 1. Link attendance_imports to semesters for semester-level reporting
-- 2. Support EMAIL advising sessions cleanly

SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'attendance_imports'
    AND COLUMN_NAME = 'semester_id'
);
SET @sql_add_sem := IF(
  @col_exists = 0,
  'ALTER TABLE attendance_imports ADD COLUMN semester_id CHAR(36) NULL, ADD INDEX idx_attendance_imports_semester (semester_id)',
  'SELECT 1'
);
PREPARE stmt FROM @sql_add_sem;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
