-- =============================================================================
-- Migration: 2026-11-19 Attendance Import/Config & Seed Phone Cleanup
-- Safe to run multiple times (idempotent).
-- =============================================================================

-- 1. Normalize any existing user phone numbers containing spaces (S4)
UPDATE `users`
SET `phone` = REPLACE(`phone`, ' ', '')
WHERE `phone` IS NOT NULL AND `phone` LIKE '% %';

-- 2. Attendance configuration & import tables (Admin B7 / Section 5.7)
CREATE TABLE IF NOT EXISTS `attendance_default_config` (
  `singleton_id` int PRIMARY KEY,
  `total_sessions` int NOT NULL DEFAULT 20,
  `min_attendance_percent` int NOT NULL DEFAULT 80,
  `warn_absent_sessions` int NOT NULL DEFAULT 3,
  `updated_at` timestamp DEFAULT CURRENT_TIMESTAMP
);

INSERT IGNORE INTO `attendance_default_config`
  (`singleton_id`, `total_sessions`, `min_attendance_percent`, `warn_absent_sessions`, `updated_at`)
VALUES (1, 20, 80, 3, CURRENT_TIMESTAMP);

CREATE TABLE IF NOT EXISTS `course_attendance_configs` (
  `course_id` char(36) PRIMARY KEY,
  `total_sessions` int NOT NULL,
  `min_attendance_percent` int NOT NULL,
  `warn_absent_sessions` int NOT NULL,
  `updated_at` timestamp DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS `attendance_imports` (
  `id` char(36) PRIMARY KEY,
  `file_name` varchar(255) NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'PREVIEW',
  `row_count` int NOT NULL DEFAULT 0,
  `valid_rows` int NOT NULL DEFAULT 0,
  `error_rows` int NOT NULL DEFAULT 0,
  `warning_count` int NOT NULL DEFAULT 0,
  `fail_count` int NOT NULL DEFAULT 0,
  `created_at` timestamp DEFAULT CURRENT_TIMESTAMP,
  `confirmed_at` timestamp NULL
);

CREATE TABLE IF NOT EXISTS `attendance_import_rows` (
  `id` char(36) PRIMARY KEY,
  `import_id` char(36) NOT NULL,
  `line_number` int NOT NULL,
  `student_code` varchar(50),
  `semester_code` varchar(50),
  `course_code` varchar(50),
  `total_sessions` int,
  `absent_sessions` int,
  `validation_error` text,
  `result` varchar(20)
);

CREATE TABLE IF NOT EXISTS `student_course_attendance` (
  `id` char(36) PRIMARY KEY,
  `student_id` char(36) NOT NULL,
  `course_id` char(36) NOT NULL,
  `semester_id` char(36) NOT NULL,
  `total_sessions` int NOT NULL,
  `absent_sessions` int NOT NULL,
  `absent_rate` decimal(5,2) NOT NULL COMMENT 'Percentage absent over total course sessions (0.00 - 100.00)',
  `result` varchar(20) NOT NULL COMMENT 'OK, WARNING, FAIL',
  `updated_at` timestamp DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uq_student_course_semester_attendance` (`student_id`, `course_id`, `semester_id`)
);
