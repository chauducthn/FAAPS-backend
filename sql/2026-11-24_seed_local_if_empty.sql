-- =============================================================================
-- FAAPS Local Database Seeder (2026-11-24, clean start)
-- 100% Idempotent: Tracks existing rows via WHERE NOT EXISTS / INSERT IGNORE
-- and only seeds missing baseline data.
--
-- It creates the roles, the Admin account, the majors, the semesters, the
-- curriculum versions, the eight course categories, the default academic rules
-- and the default AI configuration.
--
-- It deliberately creates NO sample courses, NO demo advisors and NO demo
-- students. The Admin imports the real course catalogue from FLM through
-- Admin > Courses > Import, and creates the accounts through the app.
-- =============================================================================

USE faaps;

-- -----------------------------------------------------------------------------
-- 0. Ensure incremental schema tables & columns exist
-- -----------------------------------------------------------------------------
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

CREATE TABLE IF NOT EXISTS `attendance_default_config` (
  `singleton_id` int PRIMARY KEY,
  `total_sessions` int NOT NULL DEFAULT 20,
  `min_attendance_percent` int NOT NULL DEFAULT 80,
  `warn_absent_sessions` int NOT NULL DEFAULT 3,
  `updated_at` timestamp DEFAULT CURRENT_TIMESTAMP
);

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
  `absent_rate` decimal(5,2) NOT NULL,
  `result` varchar(20) NOT NULL,
  `updated_at` timestamp DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uq_student_course_semester_attendance` (`student_id`, `course_id`, `semester_id`)
);

START TRANSACTION;

-- -----------------------------------------------------------------------------
-- 1. Roles
-- -----------------------------------------------------------------------------
INSERT INTO roles (id, code, name, description)
SELECT UUID(), 'ADMIN', 'Admin', 'System administrator role'
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE code = 'ADMIN');

INSERT INTO roles (id, code, name, description)
SELECT UUID(), 'ADVISOR', 'Advisor', 'Academic advisor role'
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE code = 'ADVISOR');

INSERT INTO roles (id, code, name, description)
SELECT UUID(), 'STUDENT', 'Student', 'FPT University student role'
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE code = 'STUDENT');

-- -----------------------------------------------------------------------------
-- 2. Default Admin Account (admin@fpt.edu.vn / Admin@123)
-- -----------------------------------------------------------------------------
INSERT INTO users (id, role_id, email, password_hash, auth_provider, full_name, phone, status)
SELECT UUID(), r.id, 'admin@fpt.edu.vn',
  '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
  'LOCAL', 'FAAPS System Administrator', '0901000001', 'ACTIVE'
FROM roles r
WHERE r.code = 'ADMIN'
  AND NOT EXISTS (SELECT 1 FROM users WHERE email = 'admin@fpt.edu.vn');

INSERT IGNORE INTO admin_account (singleton_id, user_id)
SELECT 1, u.id
FROM users u
WHERE u.email = 'admin@fpt.edu.vn'
  AND NOT EXISTS (SELECT 1 FROM admin_account WHERE singleton_id = 1);

-- -----------------------------------------------------------------------------
-- 3. Majors
-- -----------------------------------------------------------------------------
INSERT INTO majors (id, code, name, description, total_credits, duration_years, status)
SELECT UUID(), 'SE', 'Software Engineering',
  'Software engineering program focused on software design, architecture, development, testing, and delivery',
  140, 4.0, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM majors WHERE code = 'SE');

INSERT INTO majors (id, code, name, description, total_credits, duration_years, status)
SELECT UUID(), 'IA', 'Information Assurance',
  'Information assurance program focused on cybersecurity, risk management, and secure systems',
  140, 4.0, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM majors WHERE code = 'IA');

INSERT INTO majors (id, code, name, description, total_credits, duration_years, status)
SELECT UUID(), 'AI', 'Artificial Intelligence',
  'Artificial intelligence program focused on machine learning, deep learning, computer vision, and NLP',
  140, 4.0, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM majors WHERE code = 'AI');

-- -----------------------------------------------------------------------------
-- 4. Semesters
-- -----------------------------------------------------------------------------
INSERT INTO semesters (id, code, name, start_date, end_date, is_current)
SELECT UUID(), 'FA2022', 'Fall 2022', '2022-09-05', '2022-12-23', FALSE
WHERE NOT EXISTS (SELECT 1 FROM semesters WHERE code = 'FA2022');

INSERT INTO semesters (id, code, name, start_date, end_date, is_current)
SELECT UUID(), 'SP2025', 'Spring 2025', '2025-01-06', '2025-04-26', FALSE
WHERE NOT EXISTS (SELECT 1 FROM semesters WHERE code = 'SP2025');

INSERT INTO semesters (id, code, name, start_date, end_date, is_current)
SELECT UUID(), 'SU2025', 'Summer 2025', '2025-05-05', '2025-08-23', FALSE
WHERE NOT EXISTS (SELECT 1 FROM semesters WHERE code = 'SU2025');

INSERT INTO semesters (id, code, name, start_date, end_date, is_current)
SELECT UUID(), 'FA2025', 'Fall 2025', '2025-09-02', '2025-12-20', FALSE
WHERE NOT EXISTS (SELECT 1 FROM semesters WHERE code = 'FA2025');

INSERT INTO semesters (id, code, name, start_date, end_date, is_current)
SELECT UUID(), 'SP2026', 'Spring 2026', '2026-01-05', '2026-04-25', FALSE
WHERE NOT EXISTS (SELECT 1 FROM semesters WHERE code = 'SP2026');

INSERT INTO semesters (id, code, name, start_date, end_date, is_current)
SELECT UUID(), 'SU2026', 'Summer 2026', '2026-05-04', '2026-08-22', FALSE
WHERE NOT EXISTS (SELECT 1 FROM semesters WHERE code = 'SU2026');

INSERT INTO semesters (id, code, name, start_date, end_date, is_current)
SELECT UUID(), 'FA2026', 'Fall 2026', '2026-09-07', '2026-12-26',
  NOT EXISTS (SELECT 1 FROM (SELECT id FROM semesters WHERE is_current = TRUE) cur)
WHERE NOT EXISTS (SELECT 1 FROM semesters WHERE code = 'FA2026');

INSERT INTO semesters (id, code, name, start_date, end_date, is_current)
SELECT UUID(), 'SP2027', 'Spring 2027', '2027-01-04', '2027-04-24', FALSE
WHERE NOT EXISTS (SELECT 1 FROM semesters WHERE code = 'SP2027');

-- -----------------------------------------------------------------------------
-- 5. Curriculum Versions (K17, K18, K19)
-- -----------------------------------------------------------------------------
INSERT INTO curriculum_versions (id, major_id, version, name, effective_from_semester_id, description, is_current, status)
SELECT UUID(), m.id, 'K17', CONCAT(m.name, ' K17'), s.id,
  CONCAT('Standard curriculum for ', m.name, ' cohort K17'), TRUE, 'ACTIVE'
FROM majors m
JOIN semesters s ON s.code = 'FA2022'
WHERE m.code IN ('SE', 'IA', 'AI')
  AND NOT EXISTS (SELECT 1 FROM curriculum_versions cv WHERE cv.major_id = m.id AND cv.version = 'K17');

INSERT INTO curriculum_versions (id, major_id, version, name, effective_from_semester_id, description, is_current, status)
SELECT UUID(), m.id, 'K18', CONCAT(m.name, ' K18'), s.id,
  CONCAT('Updated curriculum for ', m.name, ' cohort K18'), FALSE, 'ACTIVE'
FROM majors m
JOIN semesters s ON s.code = 'SP2025'
WHERE m.code IN ('SE', 'IA')
  AND NOT EXISTS (SELECT 1 FROM curriculum_versions cv WHERE cv.major_id = m.id AND cv.version = 'K18');

-- -----------------------------------------------------------------------------
-- 6. Course Categories
-- -----------------------------------------------------------------------------
INSERT INTO course_categories (id, code, name, description)
SELECT UUID(), 'CORE', 'Core Courses', 'Required foundational and professional core courses'
WHERE NOT EXISTS (SELECT 1 FROM course_categories WHERE code = 'CORE');

INSERT INTO course_categories (id, code, name, description)
SELECT UUID(), 'MATH', 'Mathematics & Basic Sciences', 'Foundational mathematics and quantitative reasoning courses'
WHERE NOT EXISTS (SELECT 1 FROM course_categories WHERE code = 'MATH');

INSERT INTO course_categories (id, code, name, description)
SELECT UUID(), 'SPEC', 'Specialization & Capstone', 'Major specialization, software engineering, and graduation capstone courses'
WHERE NOT EXISTS (SELECT 1 FROM course_categories WHERE code = 'SPEC');

INSERT INTO course_categories (id, code, name, description)
SELECT UUID(), 'GEN', 'General Education & Soft Skills', 'Foreign languages, soft skills, and general education requirements'
WHERE NOT EXISTS (SELECT 1 FROM course_categories WHERE code = 'GEN');

-- The remaining FLM knowledge blocks. The browser import check rejects unknown
-- categories, so a FLM export cannot be imported without them.
INSERT INTO course_categories (id, code, name, description)
SELECT UUID(), 'PREP', 'Preparation Courses', 'Pre-training preparation courses such as physical education, music, arts and life skills'
WHERE NOT EXISTS (SELECT 1 FROM course_categories WHERE code = 'PREP');

INSERT INTO course_categories (id, code, name, description)
SELECT UUID(), 'FOUND', 'Foundation & Basic Sciences', 'Foundational mathematics, computing and engineering science courses'
WHERE NOT EXISTS (SELECT 1 FROM course_categories WHERE code = 'FOUND');

INSERT INTO course_categories (id, code, name, description)
SELECT UUID(), 'OJT', 'On-the-Job Training', 'On-the-Job Training internship terms completed at a partner company'
WHERE NOT EXISTS (SELECT 1 FROM course_categories WHERE code = 'OJT');

INSERT INTO course_categories (id, code, name, description)
SELECT UUID(), 'CAP', 'Capstone Project', 'Graduation capstone projects and thesis courses'
WHERE NOT EXISTS (SELECT 1 FROM course_categories WHERE code = 'CAP');

-- -----------------------------------------------------------------------------

-- 7. Academic Rules (academic_policies with rule_name & grade JSON for new Admin UI)
-- -----------------------------------------------------------------------------
INSERT INTO academic_policies (
  id, curriculum_version_id, policy_type, condition_expression, restriction_expression,
  approval_required, priority, description, display_message, is_active
)
SELECT UUID(), cv.id, seed.policy_type, seed.condition_expression, seed.restriction_expression,
  seed.approval_required, seed.priority, seed.description, seed.display_message, TRUE
FROM (
  SELECT id FROM curriculum_versions WHERE version = 'K17' ORDER BY is_current DESC, id ASC LIMIT 1
) cv
JOIN (
  SELECT 'GPA_WARNING' AS policy_type,
         '{"rule_name":"Academic GPA Warning Threshold","grade":5.0,"min_gpa":5.0}' AS condition_expression,
         '{"max_credits":15,"require_advisor_review":true}' AS restriction_expression,
         TRUE AS approval_required, 10 AS priority,
         'Trigger academic warning when semester or cumulative GPA falls below 5.0' AS description,
         'Your GPA is below 5.0. Please schedule an advising session.' AS display_message
  UNION ALL
  SELECT 'ACADEMIC_PROBATION',
         '{"rule_name":"Academic Probation GPA Rule","grade":4.0,"min_gpa":4.0}',
         '{"max_credits":12,"mandatory_advising":true}',
         TRUE, 20,
         'Place student on academic probation when cumulative GPA drops below 4.0',
         'You are under academic probation due to cumulative GPA below 4.0.'
  UNION ALL
  SELECT 'MAX_CREDITS_PER_SEMESTER',
         '{"rule_name":"Standard Semester Credit Cap","grade":21,"max_credits":21}',
         '{"hard_cap":21}',
         FALSE, 5,
         'Maximum credits a student in good standing may register per semester (21 credits)',
         'Maximum allowed credits per semester is 21.'
  UNION ALL
  SELECT 'MIN_CREDITS_PER_SEMESTER',
         '{"rule_name":"Minimum Full-time Credit Load","grade":9,"min_credits":9}',
         '{"min_credits":9}',
         FALSE, 4,
         'Minimum credits required per regular semester to maintain full-time status',
         'Minimum required credits per semester is 9.'
  UNION ALL
  SELECT 'ADVANCED_STUDY',
         '{"rule_name":"Honors Overload Eligibility","grade":8.0,"min_gpa":8.0}',
         '{"max_credits":24}',
         TRUE, 8,
         'Allow students with cumulative GPA >= 8.0 to register up to 24 credits with advisor approval',
         'Eligible for advanced study credit overload (up to 24 credits).'
) seed
WHERE NOT EXISTS (
  SELECT 1 FROM academic_policies ap
  WHERE ap.policy_type = seed.policy_type
);

-- -----------------------------------------------------------------------------

-- 8. Default AI Configurations
-- -----------------------------------------------------------------------------
INSERT INTO ai_configurations (
  id, name, provider, model_name, purpose, temperature, max_tokens,
  retrieval_top_k, similarity_threshold, timeout_seconds, daily_request_limit,
  system_prompt, fallback_message, is_active
)
SELECT UUID(), 'Default Gemini Academic Advisor Chat', 'GEMINI', 'gemini-2.5-flash', 'CHAT',
  0.40, 2048, 5, 0.6500, 30, 50,
  'You are FAAPS AI Academic Advisor for FPT University Ho Chi Minh City. Provide accurate, supportive guidance on curriculum prerequisites, GPA improvement, and study planning.',
  'The AI Academic Assistant is temporarily busy. Please try again shortly or submit an advising request to your academic advisor.',
  TRUE
WHERE NOT EXISTS (SELECT 1 FROM ai_configurations WHERE name = 'Default Gemini Academic Advisor Chat');

COMMIT;
