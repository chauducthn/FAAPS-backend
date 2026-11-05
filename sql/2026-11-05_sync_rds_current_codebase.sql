-- ==============================================================================
-- FAAPS Database Migration & Parity Script for Current Codebase
-- Target: AWS RDS MySQL 8.4 Production & Existing Local Instances
-- Generated: 2026-11-05
-- Resolves: BE-1 (missing AI report tables), student learning flows, and missing columns
-- ==============================================================================

USE `faaps`;

-- 1. Ensure System Roles
INSERT IGNORE INTO roles (id, code, name) VALUES
(UUID(), 'ADMIN', 'Admin'),
(UUID(), 'ADVISOR', 'Advisor'),
(UUID(), 'STUDENT', 'Student');

-- 2. Add missing columns on users (Date of Birth for registration)
SET @add_dob = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'date_of_birth') = 0,
    'ALTER TABLE users ADD COLUMN date_of_birth DATE NULL AFTER phone',
    'SELECT 1');
PREPARE stmt_dob FROM @add_dob;
EXECUTE stmt_dob;
DEALLOCATE PREPARE stmt_dob;

-- 3. Align notifications table (recipient_user_id)
SET @rename_notif = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'notifications' AND column_name = 'user_id') > 0
    AND (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'notifications' AND column_name = 'recipient_user_id') = 0,
    'ALTER TABLE notifications RENAME COLUMN user_id TO recipient_user_id',
    'SELECT 1');
PREPARE stmt_notif FROM @rename_notif;
EXECUTE stmt_notif;
DEALLOCATE PREPARE stmt_notif;

-- 4. Restore advisor_code and specialization on advisors table
SET @add_adv_code = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'advisors' AND column_name = 'advisor_code') = 0,
    'ALTER TABLE advisors ADD COLUMN advisor_code VARCHAR(50) NULL AFTER user_id, ADD COLUMN specialization TEXT NULL AFTER advisor_code, ADD UNIQUE KEY uq_advisors_advisor_code (advisor_code)',
    'SELECT 1');
PREPARE stmt_adv_code FROM @add_adv_code;
EXECUTE stmt_adv_code;
DEALLOCATE PREPARE stmt_adv_code;

-- 5. Create admin_account table (Single active administrator pattern)
CREATE TABLE IF NOT EXISTS admin_account (
  singleton_id TINYINT PRIMARY KEY,
  user_id CHAR(36) NOT NULL UNIQUE,
  CONSTRAINT chk_admin_account_singleton CHECK (singleton_id = 1),
  CONSTRAINT fk_admin_account_user FOREIGN KEY (user_id) REFERENCES users(id)
);

-- Seed admin_account if not present
INSERT IGNORE INTO admin_account (singleton_id, user_id)
SELECT 1, u.id FROM users u
JOIN roles r ON r.id = u.role_id
WHERE r.code = 'ADMIN' AND u.status = 'ACTIVE'
LIMIT 1;

-- 6. BE-1 FIX: Create ai_response_reports and ai_reported_messages
CREATE TABLE IF NOT EXISTS ai_response_reports (
    id CHAR(36) PRIMARY KEY,
    conversation_id CHAR(36) NOT NULL,
    student_id CHAR(36) NOT NULL,
    reason VARCHAR(100) NOT NULL,
    student_note TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    resolution_note TEXT,
    resolved_by CHAR(36),
    resolved_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_ai_report_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversations(id),
    CONSTRAINT fk_ai_report_student FOREIGN KEY (student_id) REFERENCES users(id),
    CONSTRAINT fk_ai_report_resolver FOREIGN KEY (resolved_by) REFERENCES users(id),
    INDEX idx_ai_report_status_created (status, created_at),
    INDEX idx_ai_report_student (student_id)
);

CREATE TABLE IF NOT EXISTS ai_reported_messages (
    id CHAR(36) PRIMARY KEY,
    report_id CHAR(36) NOT NULL,
    message_id CHAR(36) NOT NULL,
    student_note TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_reported_message_report FOREIGN KEY (report_id) REFERENCES ai_response_reports(id),
    CONSTRAINT fk_reported_message_message FOREIGN KEY (message_id) REFERENCES ai_messages(id),
    UNIQUE KEY uq_report_message (report_id, message_id)
);

-- 7. Create Student Transcript Import Tables
CREATE TABLE IF NOT EXISTS student_transcript_imports (
    id CHAR(36) PRIMARY KEY,
    student_id CHAR(36) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PREVIEW',
    row_count INT NOT NULL DEFAULT 0,
    error_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_at TIMESTAMP NULL,
    INDEX idx_transcript_import_student (student_id, created_at),
    CONSTRAINT fk_transcript_import_student FOREIGN KEY (student_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS student_transcript_import_rows (
    id CHAR(36) PRIMARY KEY,
    import_id CHAR(36) NOT NULL,
    line_number INT NOT NULL,
    course_id CHAR(36) NULL,
    semester_id CHAR(36) NULL,
    course_code VARCHAR(50) NOT NULL,
    semester_code VARCHAR(50) NOT NULL,
    final_score DECIMAL(4,2) NULL,
    status VARCHAR(20) NOT NULL,
    attempt_number INT NOT NULL DEFAULT 1,
    validation_error VARCHAR(500) NULL,
    UNIQUE KEY uq_transcript_import_row (import_id, line_number),
    CONSTRAINT fk_transcript_import_row FOREIGN KEY (import_id) REFERENCES student_transcript_imports(id),
    CONSTRAINT fk_transcript_import_course FOREIGN KEY (course_id) REFERENCES courses(id),
    CONSTRAINT fk_transcript_import_semester FOREIGN KEY (semester_id) REFERENCES semesters(id)
);

-- 8. Create Course Learning Plans & Tasks
CREATE TABLE IF NOT EXISTS course_learning_plans (
    id CHAR(36) PRIMARY KEY,
    student_id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    learning_goal TEXT NULL,
    version INT NOT NULL,
    previous_plan_id CHAR(36) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_course_learning_version (student_id, course_id, version),
    INDEX idx_course_learning_student (student_id, created_at),
    CONSTRAINT fk_course_learning_student FOREIGN KEY (student_id) REFERENCES users(id),
    CONSTRAINT fk_course_learning_course FOREIGN KEY (course_id) REFERENCES courses(id),
    CONSTRAINT fk_course_learning_previous FOREIGN KEY (previous_plan_id) REFERENCES course_learning_plans(id)
);

CREATE TABLE IF NOT EXISTS course_learning_tasks (
    id CHAR(36) PRIMARY KEY,
    plan_id CHAR(36) NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT NULL,
    due_date DATE NULL,
    resource_url VARCHAR(1000) NULL,
    sort_order INT NOT NULL,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    completed_at TIMESTAMP NULL,
    UNIQUE KEY uq_course_learning_task_order (plan_id, sort_order),
    CONSTRAINT fk_course_learning_task_plan FOREIGN KEY (plan_id) REFERENCES course_learning_plans(id)
);

-- 9. Create cohort_advisors if missing
CREATE TABLE IF NOT EXISTS cohort_advisors (
    cohort_id CHAR(36) NOT NULL,
    advisor_id CHAR(36) NOT NULL,
    assigned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (cohort_id, advisor_id),
    CONSTRAINT fk_cohort_advisors_cohort FOREIGN KEY (cohort_id) REFERENCES curriculum_versions(id) ON DELETE CASCADE,
    CONSTRAINT fk_cohort_advisors_advisor FOREIGN KEY (advisor_id) REFERENCES advisors(user_id) ON DELETE CASCADE
);

-- 10. Default Seed Fallbacks (Semesters & AI Config if none exist)
INSERT INTO semesters (id, code, name, start_date, end_date, is_current)
SELECT UUID(), 'FA2026', 'Fall 2026', '2026-09-01', '2026-12-25', TRUE
WHERE NOT EXISTS (SELECT 1 FROM semesters WHERE is_current = TRUE OR code = 'FA2026');

INSERT INTO ai_configurations (id, model_name, temperature, max_tokens, system_prompt, is_active)
SELECT UUID(), 'gemini-1.5-pro', 0.70, 2048, 'You are an academic advisor assistant for FPT University students.', TRUE
WHERE NOT EXISTS (SELECT 1 FROM ai_configurations WHERE is_active = TRUE);
