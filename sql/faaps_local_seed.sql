-- =============================================================================
-- FAAPS Local Database Seeder (clean start)
-- 100% Idempotent: Tracks existing rows via WHERE NOT EXISTS / INSERT IGNORE
--
-- This seed creates only the structural data a fresh install needs:
--   roles, the Admin account, majors, semesters, curriculum versions,
--   course categories and the default academic rules.
--
-- It deliberately creates NO sample courses, NO demo advisors and NO demo
-- students. The Admin imports the real catalogue from FLM through
-- Admin > Courses > Import, and accounts are created through the app.
-- =============================================================================

USE faaps;

START TRANSACTION;

-- 1. Roles
INSERT INTO roles (id, code, name, description)
SELECT UUID(), 'ADMIN', 'Admin', 'System administrator role'
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE code = 'ADMIN');

INSERT INTO roles (id, code, name, description)
SELECT UUID(), 'ADVISOR', 'Advisor', 'Academic advisor role'
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE code = 'ADVISOR');

INSERT INTO roles (id, code, name, description)
SELECT UUID(), 'STUDENT', 'Student', 'FPT University student role'
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE code = 'STUDENT');

-- 2. Default Admin Account (admin@fpt.edu.vn / Admin@123)
-- 03-local-admin-and-defaults.sql replaces this hash with the generated one.
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

-- 3. Majors
INSERT INTO majors (id, code, name, description, total_credits, duration_years, status)
SELECT UUID(), 'SE', 'Software Engineering',
  'Software engineering program focused on software design, development, testing, and delivery', 140, 4.0, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM majors WHERE code='SE');

INSERT INTO majors (id, code, name, description, total_credits, duration_years, status)
SELECT UUID(), 'IA', 'Information Assurance',
  'Information assurance program focused on cybersecurity, risk management, and secure systems', 140, 4.0, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM majors WHERE code='IA');

INSERT INTO majors (id, code, name, description, total_credits, duration_years, status)
SELECT UUID(), 'AI', 'Artificial Intelligence',
  'Artificial intelligence program focused on machine learning, deep learning, computer vision, and NLP', 140, 4.0, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM majors WHERE code='AI');

-- 4. Semesters
INSERT INTO semesters (id, code, name, start_date, end_date, is_current)
SELECT UUID(), 'FA2022', 'Fall 2022', '2022-09-05', '2022-12-23', FALSE
WHERE NOT EXISTS (SELECT 1 FROM semesters WHERE code='FA2022');

INSERT INTO semesters (id, code, name, start_date, end_date, is_current)
SELECT UUID(), 'FA2025', 'Fall 2025', '2025-09-02', '2025-12-20', FALSE
WHERE NOT EXISTS (SELECT 1 FROM semesters WHERE code='FA2025');

INSERT INTO semesters (id, code, name, start_date, end_date, is_current)
SELECT UUID(), 'FA2026', 'Fall 2026', '2026-09-07', '2026-12-26',
  NOT EXISTS (SELECT 1 FROM (SELECT id FROM semesters WHERE is_current = TRUE) cur)
WHERE NOT EXISTS (SELECT 1 FROM semesters WHERE code='FA2026');

INSERT INTO semesters (id, code, name, start_date, end_date, is_current)
SELECT UUID(), 'SP2027', 'Spring 2027', '2027-01-04', '2027-04-24', FALSE
WHERE NOT EXISTS (SELECT 1 FROM semesters WHERE code='SP2027');

-- 5. Curriculum Versions
INSERT INTO curriculum_versions (id, major_id, version, name, effective_from_semester_id,
  description, is_current, status)
SELECT UUID(), m.id, 'K17', CONCAT(m.name, ' K17'), s.id,
  CONCAT('Curriculum for ', m.name, ' cohort K17'), TRUE, 'ACTIVE'
FROM majors m JOIN semesters s ON s.code='FA2022'
WHERE m.code IN ('SE','IA','AI') AND NOT EXISTS (
  SELECT 1 FROM curriculum_versions cv WHERE cv.major_id=m.id AND cv.version='K17'
);

-- 6. Course Categories
-- The browser import check rejects a course whose category is unknown, so a FLM
-- export cannot be imported unless every knowledge block already exists.
INSERT INTO course_categories (id, code, name, description)
SELECT UUID(), 'CORE', 'Core Courses', 'Required foundational and professional courses'
WHERE NOT EXISTS (SELECT 1 FROM course_categories WHERE code='CORE');

INSERT INTO course_categories (id, code, name, description)
SELECT UUID(), 'MATH', 'Mathematics & Basic Sciences', 'Foundational mathematics and quantitative reasoning courses'
WHERE NOT EXISTS (SELECT 1 FROM course_categories WHERE code='MATH');

INSERT INTO course_categories (id, code, name, description)
SELECT UUID(), 'SPEC', 'Specialization & Capstone', 'Major specialization and graduation capstone courses'
WHERE NOT EXISTS (SELECT 1 FROM course_categories WHERE code='SPEC');

INSERT INTO course_categories (id, code, name, description)
SELECT UUID(), 'PREP', 'Preparation Courses', 'Pre-training preparation courses such as physical education, music, arts and life skills'
WHERE NOT EXISTS (SELECT 1 FROM course_categories WHERE code='PREP');

INSERT INTO course_categories (id, code, name, description)
SELECT UUID(), 'GEN', 'General Education & Soft Skills', 'Foreign languages, humanities, general education and soft skills requirements'
WHERE NOT EXISTS (SELECT 1 FROM course_categories WHERE code='GEN');

INSERT INTO course_categories (id, code, name, description)
SELECT UUID(), 'FOUND', 'Foundation & Basic Sciences', 'Foundational mathematics, computing and engineering science courses'
WHERE NOT EXISTS (SELECT 1 FROM course_categories WHERE code='FOUND');

INSERT INTO course_categories (id, code, name, description)
SELECT UUID(), 'OJT', 'On-the-Job Training', 'On-the-Job Training internship terms completed at a partner company'
WHERE NOT EXISTS (SELECT 1 FROM course_categories WHERE code='OJT');

INSERT INTO course_categories (id, code, name, description)
SELECT UUID(), 'CAP', 'Capstone Project', 'Graduation capstone projects and thesis courses'
WHERE NOT EXISTS (SELECT 1 FROM course_categories WHERE code='CAP');

-- 7. Academic Rules (rule_name & grade JSON)
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
         '{"max_credits":15}' AS restriction_expression,
         TRUE AS approval_required, 10 AS priority,
         'Trigger academic warning when semester or cumulative GPA falls below 5.0' AS description,
         'Your GPA is below 5.0. Please schedule an advising session.' AS display_message
  UNION ALL
  SELECT 'MAX_CREDITS_PER_SEMESTER',
         '{"rule_name":"Standard Semester Credit Cap","grade":21,"max_credits":21}',
         '{"hard_cap":21}',
         FALSE, 5,
         'Maximum credits a student in good standing may register per semester (21 credits)',
         'Maximum allowed credits per semester is 21.'
) seed
WHERE NOT EXISTS (SELECT 1 FROM academic_policies ap WHERE ap.policy_type = seed.policy_type);

COMMIT;

SELECT 'courses' AS table_name, COUNT(*) AS row_count FROM courses
UNION ALL SELECT 'users', COUNT(*) FROM users
UNION ALL SELECT 'students', COUNT(*) FROM students
UNION ALL SELECT 'advisors', COUNT(*) FROM advisors
UNION ALL SELECT 'course_categories', COUNT(*) FROM course_categories;
