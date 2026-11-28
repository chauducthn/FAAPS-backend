-- Migration: Course substitution (REPLACEMENT) scoping + substitution audit trail
-- Purpose:
--   1. Allow an admin to declare that course B replaces course A for a given
--      curriculum version (cohort). NULL scope = applies to every curriculum.
--   2. Record who declared the relation and why, so the roadmap can be audited.
--   3. Store the "satisfied by" mapping on the student record so the system
--      keeps explaining HOW a curriculum course was fulfilled.

SET NAMES utf8mb4;
USE faaps;

-- ---------------------------------------------------------------------------
-- 1. Scope a REPLACEMENT relation to a curriculum version (cohort).
--    NULL = the relation is global (applies to every curriculum version).
-- ---------------------------------------------------------------------------
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'course_prerequisites'
    AND COLUMN_NAME = 'effective_from_curriculum_version_id'
);
SET @sql_add_scope := IF(
  @col_exists = 0,
  'ALTER TABLE course_prerequisites
     ADD COLUMN effective_from_curriculum_version_id char(36) NULL
       COMMENT ''NULL = applies to all curriculum versions; otherwise the relation starts at this cohort''
     AFTER `type`,
     ADD COLUMN replaced_by_reason varchar(255) NULL
       COMMENT ''Why this substitution exists (curriculum change note)''',
  'SELECT 1'
);
PREPARE stmt FROM @sql_add_scope;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- The scope points at a curriculum version, so it needs a foreign key. The FK is
-- added separately because a column may already exist from a partial run.
SET @fk_exists := (
  SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'course_prerequisites'
    AND CONSTRAINT_NAME = 'fk_prereq_replacement_scope'
);
SET @sql_add_fk := IF(
  @fk_exists = 0,
  'ALTER TABLE course_prerequisites
     ADD CONSTRAINT fk_prereq_replacement_scope
       FOREIGN KEY (effective_from_curriculum_version_id) REFERENCES curriculum_versions (id)
       ON DELETE CASCADE',
  'SELECT 1'
);
PREPARE stmt FROM @sql_add_fk;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 2. How a curriculum course was fulfilled on the student record.
--    `satisfied_via_course_id` = the legacy / substitute course the student
--    actually took, when it is not the curriculum course itself.
-- ---------------------------------------------------------------------------
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'student_course_records'
    AND COLUMN_NAME = 'satisfied_via_course_id'
);
SET @sql_add_satisfied := IF(
  @col_exists = 0,
  'ALTER TABLE student_course_records
     ADD COLUMN satisfied_via_course_id char(36) NULL
       COMMENT ''The curriculum course this record satisfies (set when the student took a substitute course)''',
  'SELECT 1'
);
PREPARE stmt FROM @sql_add_satisfied;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @fk_exists := (
  SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'student_course_records'
    AND CONSTRAINT_NAME = 'fk_student_record_satisfied_via'
);
SET @sql_add_fk2 := IF(
  @fk_exists = 0,
  'ALTER TABLE student_course_records
     ADD CONSTRAINT fk_student_record_satisfied_via
       FOREIGN KEY (satisfied_via_course_id) REFERENCES courses (id)
       ON DELETE SET NULL',
  'SELECT 1'
);
PREPARE stmt FROM @sql_add_fk2;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 3. Fast lookup index: "which courses may replace course X in curriculum V".
-- ---------------------------------------------------------------------------
SET @idx_exists := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'course_prerequisites'
    AND INDEX_NAME = 'idx_prereq_replacement_scope'
);
SET @sql_add_idx := IF(
  @idx_exists = 0,
  'ALTER TABLE course_prerequisites
     ADD INDEX idx_prereq_replacement_scope (type, course_id, effective_from_curriculum_version_id)',
  'SELECT 1'
);
PREPARE stmt FROM @sql_add_idx;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
