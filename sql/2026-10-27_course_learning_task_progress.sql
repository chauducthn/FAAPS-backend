-- MySQL 8.4 does not support ADD COLUMN IF NOT EXISTS. These statements are safe to rerun.
SET @add_completed = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'course_learning_tasks' AND column_name = 'completed') = 0,
    'ALTER TABLE course_learning_tasks ADD COLUMN completed BOOLEAN NOT NULL DEFAULT FALSE AFTER sort_order',
    'SELECT 1');
PREPARE statement FROM @add_completed;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @add_completed_at = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'course_learning_tasks' AND column_name = 'completed_at') = 0,
    'ALTER TABLE course_learning_tasks ADD COLUMN completed_at TIMESTAMP NULL AFTER completed',
    'SELECT 1');
PREPARE statement FROM @add_completed_at;
EXECUTE statement;
DEALLOCATE PREPARE statement;
