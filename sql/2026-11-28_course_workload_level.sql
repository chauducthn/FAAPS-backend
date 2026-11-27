-- Migration: Add manual course workload_level override column (idempotent for MySQL 8.4)
-- Valid values: NULL (auto: Layer 1 rule-based + Layer 3 historical data), LIGHT, MEDIUM, HEAVY

SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'courses'
    AND COLUMN_NAME = 'workload_level'
);
SET @sql_add_workload := IF(
  @col_exists = 0,
  'ALTER TABLE courses ADD COLUMN workload_level VARCHAR(20) NULL COMMENT ''Manual override: LIGHT, MEDIUM, HEAVY (NULL = auto)''',
  'SELECT 1'
);
PREPARE stmt FROM @sql_add_workload;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
