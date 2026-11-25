-- =============================================================================
-- FAAPS Local Database Seeder (2026-11-24)
-- 100% Idempotent: Tracks existing rows via WHERE NOT EXISTS / INSERT IGNORE
-- and only seeds missing baseline & sample data.
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

-- -----------------------------------------------------------------------------
-- 7. Courses (including dedicated material_link column)
-- -----------------------------------------------------------------------------
INSERT INTO courses (id, code, name, credits, category_id, description, objectives, learning_outcomes, material_link, status)
SELECT UUID(), seed.code, seed.name, seed.credits, cat.id, seed.description, seed.objectives, NULL, seed.material_link, 'ACTIVE'
FROM (
  SELECT 'PRF192' AS code, 'Programming Fundamentals' AS name, 3 AS credits, 'CORE' AS cat_code,
         'Fundamental programming concepts, control structures, arrays, pointers, and file I/O with C' AS description,
         'Master procedural programming and structured problem solving in C' AS objectives,
         'https://flm.fpt.edu.vn/gui/role/student/SyllabusDetails?sylId=PRF192' AS material_link
  UNION ALL SELECT 'MAE101', 'Mathematics for Engineering', 3, 'MATH',
         'Linear algebra, matrices, limits, derivatives, and integrals for engineering applications',
         'Apply calculus and linear algebra to computing problems',
         'https://flm.fpt.edu.vn/gui/role/student/SyllabusDetails?sylId=MAE101'
  UNION ALL SELECT 'CEA201', 'Computer Organization and Architecture', 3, 'CORE',
         'Digital logic, processor organization, memory hierarchy, and instruction sets',
         'Understand low-level computer hardware and instruction execution',
         'https://flm.fpt.edu.vn/gui/role/student/SyllabusDetails?sylId=CEA201'
  UNION ALL SELECT 'PRO192', 'Object-Oriented Programming', 3, 'CORE',
         'Encapsulation, inheritance, polymorphism, abstraction, and collections in Java',
         'Design and implement object-oriented applications using Java',
         'https://flm.fpt.edu.vn/gui/role/student/SyllabusDetails?sylId=PRO192'
  UNION ALL SELECT 'MAD101', 'Discrete Mathematics', 3, 'MATH',
         'Logic, sets, combinatorics, recursion, graphs, and trees for computer science',
         'Formulate mathematical proofs and graph algorithms',
         'https://flm.fpt.edu.vn/gui/role/student/SyllabusDetails?sylId=MAD101'
  UNION ALL SELECT 'OSG202', 'Operating Systems', 3, 'CORE',
         'Process management, threads, concurrency, deadlocks, memory, and file systems',
         'Analyze operating system internals and synchronization mechanisms',
         'https://flm.fpt.edu.vn/gui/role/student/SyllabusDetails?sylId=OSG202'
  UNION ALL SELECT 'CSD201', 'Data Structures and Algorithms', 3, 'CORE',
         'Linked lists, stacks, queues, trees, graphs, sorting, searching, and complexity analysis',
         'Implement efficient data structures and algorithmic solutions in Java',
         'https://flm.fpt.edu.vn/gui/role/student/SyllabusDetails?sylId=CSD201'
  UNION ALL SELECT 'DBI202', 'Introduction to Databases', 3, 'CORE',
         'Relational database modeling, ER diagrams, SQL, normalization, and transactions',
         'Design normalized relational schemas and write complex SQL queries',
         'https://flm.fpt.edu.vn/gui/role/student/SyllabusDetails?sylId=DBI202'
  UNION ALL SELECT 'LAB211', 'OOP with Java Lab', 3, 'CORE',
         'Intensive hands-on object-oriented programming laboratory in Java',
         'Build clean, maintainable console applications under coding conventions',
         'https://flm.fpt.edu.vn/gui/role/student/SyllabusDetails?sylId=LAB211'
  UNION ALL SELECT 'PRJ301', 'Java Web Application Development', 3, 'SPEC',
         'Servlets, JSP, MVC architecture, JDBC, session management, and web security',
         'Develop full-stack Java web applications adhering to MVC pattern',
         'https://flm.fpt.edu.vn/gui/role/student/SyllabusDetails?sylId=PRJ301'
  UNION ALL SELECT 'SWE201c', 'Introduction to Software Engineering', 3, 'SPEC',
         'Software development life cycle, Agile/Scrum, requirements, design, and testing',
         'Apply software engineering processes to team projects',
         'https://flm.fpt.edu.vn/gui/role/student/SyllabusDetails?sylId=SWE201c'
  UNION ALL SELECT 'SWR302', 'Software Requirement', 3, 'SPEC',
         'Requirement elicitation, analysis, specification (SRS), and validation',
         'Author complete Software Requirements Specifications for enterprise systems',
         'https://flm.fpt.edu.vn/gui/role/student/SyllabusDetails?sylId=SWR302'
  UNION ALL SELECT 'SWT301', 'Software Testing', 3, 'SPEC',
         'Unit testing, integration testing, black-box/white-box techniques, and test automation',
         'Design comprehensive test plans, test cases, and automated test suites',
         'https://flm.fpt.edu.vn/gui/role/student/SyllabusDetails?sylId=SWT301'
  UNION ALL SELECT 'SWP391', 'Application Development Project', 3, 'SPEC',
         'Team-based software development project applying Agile/Scrum and full-stack technologies',
         'Deliver a working software product in a cross-functional team',
         'https://flm.fpt.edu.vn/gui/role/student/SyllabusDetails?sylId=SWP391'
  UNION ALL SELECT 'IAI101', 'Introduction to Information Assurance', 3, 'CORE',
         'CIA triad, threat modeling, cryptography fundamentals, and security controls',
         'Identify security vulnerabilities and apply foundational protection controls',
         'https://flm.fpt.edu.vn/gui/role/student/SyllabusDetails?sylId=IAI101'
) seed
JOIN course_categories cat ON cat.code = seed.cat_code
WHERE NOT EXISTS (SELECT 1 FROM courses c WHERE c.code = seed.code);

-- Backfill material_link on existing seeded courses if NULL
UPDATE courses
SET material_link = CONCAT('https://flm.fpt.edu.vn/gui/role/student/SyllabusDetails?sylId=', code)
WHERE material_link IS NULL OR TRIM(material_link) = '';

UPDATE courses
SET learning_outcomes = NULL
WHERE learning_outcomes LIKE 'http://%' OR learning_outcomes LIKE 'https://%';

-- -----------------------------------------------------------------------------
-- 8. Course Prerequisites
-- -----------------------------------------------------------------------------
INSERT INTO course_prerequisites (id, course_id, prerequisite_course_id, group_no, type, minimum_score)
SELECT UUID(), c.id, p.id, 1, 'PREREQUISITE', 5.00
FROM (
  SELECT 'PRO192' AS course_code, 'PRF192' AS prereq_code
  UNION ALL SELECT 'CSD201', 'PRO192'
  UNION ALL SELECT 'LAB211', 'PRO192'
  UNION ALL SELECT 'PRJ301', 'PRO192'
  UNION ALL SELECT 'PRJ301', 'DBI202'
  UNION ALL SELECT 'SWE201c', 'PRO192'
  UNION ALL SELECT 'SWR302', 'SWE201c'
  UNION ALL SELECT 'SWT301', 'SWE201c'
  UNION ALL SELECT 'SWP391', 'PRJ301'
  UNION ALL SELECT 'SWP391', 'SWE201c'
) rel
JOIN courses c ON c.code = rel.course_code
JOIN courses p ON p.code = rel.prereq_code
WHERE NOT EXISTS (
  SELECT 1 FROM course_prerequisites cp
  WHERE cp.course_id = c.id AND cp.prerequisite_course_id = p.id
);

-- -----------------------------------------------------------------------------
-- 9. Curriculum Courses (Map courses to K17 Curriculums)
-- -----------------------------------------------------------------------------
INSERT INTO curriculum_courses (id, curriculum_version_id, course_id, semester_number, is_mandatory)
SELECT UUID(), cv.id, c.id,
  CASE c.code
    WHEN 'PRF192' THEN 1
    WHEN 'MAE101' THEN 1
    WHEN 'CEA201' THEN 1
    WHEN 'PRO192' THEN 2
    WHEN 'MAD101' THEN 2
    WHEN 'OSG202' THEN 2
    WHEN 'CSD201' THEN 3
    WHEN 'DBI202' THEN 3
    WHEN 'LAB211' THEN 3
    WHEN 'IAI101' THEN 3
    WHEN 'PRJ301' THEN 4
    WHEN 'SWE201c' THEN 4
    WHEN 'SWR302' THEN 5
    WHEN 'SWT301' THEN 5
    WHEN 'SWP391' THEN 5
    ELSE 1
  END,
  TRUE
FROM curriculum_versions cv
JOIN majors m ON m.id = cv.major_id
JOIN courses c ON c.code IN (
  'PRF192','MAE101','CEA201','PRO192','MAD101','OSG202',
  'CSD201','DBI202','LAB211','IAI101','PRJ301','SWE201c',
  'SWR302','SWT301','SWP391'
)
WHERE cv.version = 'K17'
  AND (
    m.code = 'SE'
    OR (m.code = 'IA' AND c.code IN ('PRF192','MAE101','CEA201','PRO192','OSG202','CSD201','DBI202','IAI101'))
    OR (m.code = 'AI' AND c.code IN ('PRF192','MAE101','PRO192','MAD101','CSD201','DBI202'))
  )
  AND NOT EXISTS (
    SELECT 1 FROM curriculum_courses cc
    WHERE cc.curriculum_version_id = cv.id AND cc.course_id = c.id
  );

-- -----------------------------------------------------------------------------
-- 10. Academic Rules (academic_policies with rule_name & grade JSON for new Admin UI)
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
-- 11. Advisors & Advisor Availability
-- -----------------------------------------------------------------------------
INSERT INTO users (id, role_id, email, password_hash, auth_provider, full_name, phone, status)
SELECT UUID(), r.id, seed.email,
  '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
  'LOCAL', seed.full_name, seed.phone, 'ACTIVE'
FROM roles r
JOIN (
  SELECT 'hantt@fpt.edu.vn' AS email, 'Nguyễn Thị Thu Hà' AS full_name, '0902000001' AS phone
  UNION ALL SELECT 'baopq@fpt.edu.vn', 'Phạm Quốc Bảo', '0902000002'
  UNION ALL SELECT 'advisor.demo@fpt.edu.vn', 'Trần Quốc Cố Vấn', '0902000003'
) seed
WHERE r.code = 'ADVISOR'
  AND NOT EXISTS (SELECT 1 FROM users u WHERE u.email = seed.email);

INSERT INTO advisors (user_id, advisor_code, specialization, max_advisees, status, assignment_start_date)
SELECT u.id,
  CASE u.email
    WHEN 'hantt@fpt.edu.vn' THEN 'ADV001'
    WHEN 'baopq@fpt.edu.vn' THEN 'ADV002'
    ELSE 'ADV003'
  END,
  CASE u.email
    WHEN 'hantt@fpt.edu.vn' THEN 'Software Engineering & Capstone Advising'
    WHEN 'baopq@fpt.edu.vn' THEN 'Information Assurance & Cybersecurity Advising'
    ELSE 'Academic Planning & At-Risk Student Support'
  END,
  30, 'ACTIVE', '2025-09-01'
FROM users u
WHERE u.email IN ('hantt@fpt.edu.vn', 'baopq@fpt.edu.vn', 'advisor.demo@fpt.edu.vn')
  AND NOT EXISTS (SELECT 1 FROM advisors a WHERE a.user_id = u.id);

INSERT INTO advisor_availability (id, advisor_id, day_of_week, start_time, end_time, location, is_virtual, max_appointments, is_active)
SELECT UUID(), a.user_id, slot.day_of_week, slot.start_time, slot.end_time, slot.location, slot.is_virtual, 3, TRUE
FROM advisors a
JOIN (
  SELECT 'MONDAY' AS day_of_week, '08:30:00' AS start_time, '11:30:00' AS end_time, 'Room 204 - Alpha Building' AS location, FALSE AS is_virtual
  UNION ALL SELECT 'WEDNESDAY', '13:30:00', '16:30:00', 'Google Meet (Online)', TRUE
  UNION ALL SELECT 'FRIDAY', '09:00:00', '11:30:00', 'Room 204 - Alpha Building', FALSE
) slot
WHERE NOT EXISTS (
  SELECT 1 FROM advisor_availability aa
  WHERE aa.advisor_id = a.user_id AND aa.day_of_week = slot.day_of_week
);

-- -----------------------------------------------------------------------------
-- 12. Students (including active & at-risk students for Advisor Dashboard)
-- -----------------------------------------------------------------------------
INSERT INTO users (id, role_id, email, password_hash, auth_provider, full_name, phone, date_of_birth, status)
SELECT UUID(), r.id, seed.email,
  '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
  'LOCAL', seed.full_name, seed.phone, seed.dob, 'ACTIVE'
FROM roles r
JOIN (
  SELECT 'luongnvse173012@fpt.edu.vn' AS email, 'Nguyễn Văn Lương' AS full_name, '0903000012' AS phone, '2004-03-15' AS dob
  UNION ALL SELECT 'lamvtse173026@fpt.edu.vn', 'Võ Thanh Lâm', '0903000026', '2004-07-22'
  UNION ALL SELECT 'khoitmia173041@fpt.edu.vn', 'Trần Minh Khôi', '0903000041', '2004-11-05'
  UNION ALL SELECT 'anhlhia173055@fpt.edu.vn', 'Lê Hoàng Anh', '0903000055', '2004-05-19'
  UNION ALL SELECT 'student.demo@fpt.edu.vn', 'Nguyễn Minh Sinh Viên', '0903000099', '2004-01-20'
) seed
WHERE r.code = 'STUDENT'
  AND NOT EXISTS (SELECT 1 FROM users u WHERE u.email = seed.email);

INSERT INTO students (
  user_id, student_code, major_id, curriculum_version_id,
  enrollment_semester_id, expected_graduation_semester_id,
  current_semester, current_gpa, total_credits_earned, academic_status
)
SELECT u.id, seed.student_code, m.id, cv.id, enroll_sem.id, grad_sem.id,
  seed.current_semester, seed.current_gpa, seed.total_credits_earned, seed.academic_status
FROM (
  SELECT 'luongnvse173012@fpt.edu.vn' AS email, 'SE173012' AS student_code, 'SE' AS major_code,
         5 AS current_semester, 7.85 AS current_gpa, 36 AS total_credits_earned, 'ACTIVE' AS academic_status
  UNION ALL SELECT 'lamvtse173026@fpt.edu.vn', 'SE173026', 'SE',
         5, 4.35, 24, 'PROBATION'
  UNION ALL SELECT 'khoitmia173041@fpt.edu.vn', 'IA173041', 'IA',
         5, 7.20, 30, 'ACTIVE'
  UNION ALL SELECT 'anhlhia173055@fpt.edu.vn', 'IA173055', 'IA',
         4, 4.60, 18, 'PROBATION'
  UNION ALL SELECT 'student.demo@fpt.edu.vn', 'SE173099', 'SE',
         5, 8.10, 36, 'ACTIVE'
) seed
JOIN users u ON u.email = seed.email
JOIN majors m ON m.code = seed.major_code
JOIN curriculum_versions cv ON cv.major_id = m.id AND cv.version = 'K17'
JOIN semesters enroll_sem ON enroll_sem.code = 'FA2022'
LEFT JOIN semesters grad_sem ON grad_sem.code = 'SP2027'
WHERE NOT EXISTS (SELECT 1 FROM students st WHERE st.user_id = u.id);

-- -----------------------------------------------------------------------------
-- 13. Student-Advisor Assignments & Cohort Advisors
-- -----------------------------------------------------------------------------
INSERT INTO student_advisor_assignments (
  id, student_id, advisor_id, group_name, assigned_date, notes, is_primary, status
)
SELECT UUID(), st.user_id, adv.id, CONCAT('K17-', m.code), '2026-09-01',
  'Primary academic advisor assignment', TRUE, 'ACTIVE'
FROM students st
JOIN majors m ON m.id = st.major_id
JOIN users adv ON adv.email = CASE
  WHEN st.student_code IN ('SE173012', 'SE173099', 'IA173041') THEN 'hantt@fpt.edu.vn'
  ELSE 'baopq@fpt.edu.vn'
END
WHERE NOT EXISTS (
  SELECT 1 FROM student_advisor_assignments saa
  WHERE saa.student_id = st.user_id AND saa.status = 'ACTIVE'
);

INSERT IGNORE INTO cohort_advisors (cohort_id, advisor_id, is_active)
SELECT cv.id, a.user_id, TRUE
FROM curriculum_versions cv
JOIN advisors a
WHERE cv.version = 'K17';

-- -----------------------------------------------------------------------------
-- 14. Student Course Records (Transcripts) & Semester Summaries
-- -----------------------------------------------------------------------------
INSERT INTO student_course_records (
  id, student_id, course_id, semester_id, final_score, status, attempt_number, credits_earned
)
SELECT UUID(), st.user_id, c.id, sem.id, rec.final_score, rec.status, 1,
  CASE WHEN rec.status = 'PASSED' THEN c.credits ELSE 0 END
FROM (
  -- SE173012 (Good standing)
  SELECT 'SE173012' AS student_code, 'PRF192' AS course_code, 'SP2025' AS sem_code, 8.20 AS final_score, 'PASSED' AS status
  UNION ALL SELECT 'SE173012', 'MAE101', 'SP2025', 7.50, 'PASSED'
  UNION ALL SELECT 'SE173012', 'PRO192', 'SU2025', 8.00, 'PASSED'
  UNION ALL SELECT 'SE173012', 'MAD101', 'SU2025', 7.80, 'PASSED'
  UNION ALL SELECT 'SE173012', 'CSD201', 'FA2025', 7.60, 'PASSED'
  UNION ALL SELECT 'SE173012', 'DBI202', 'FA2025', 8.10, 'PASSED'
  UNION ALL SELECT 'SE173012', 'PRJ301', 'FA2026', NULL, 'IN_PROGRESS'
  UNION ALL SELECT 'SE173012', 'SWE201c', 'FA2026', NULL, 'IN_PROGRESS'
  -- SE173026 (At-risk / Probation)
  UNION ALL SELECT 'SE173026', 'PRF192', 'SP2025', 5.50, 'PASSED'
  UNION ALL SELECT 'SE173026', 'MAE101', 'SP2025', 4.20, 'FAILED'
  UNION ALL SELECT 'SE173026', 'PRO192', 'SU2025', 5.10, 'PASSED'
  UNION ALL SELECT 'SE173026', 'CSD201', 'FA2025', 3.80, 'FAILED'
  UNION ALL SELECT 'SE173026', 'DBI202', 'FA2025', 4.50, 'FAILED'
  UNION ALL SELECT 'SE173026', 'PRJ301', 'FA2026', NULL, 'IN_PROGRESS'
  -- SE173099 (Demo student)
  UNION ALL SELECT 'SE173099', 'PRF192', 'SP2025', 8.50, 'PASSED'
  UNION ALL SELECT 'SE173099', 'PRO192', 'SU2025', 8.20, 'PASSED'
  UNION ALL SELECT 'SE173099', 'CSD201', 'FA2025', 7.90, 'PASSED'
  UNION ALL SELECT 'SE173099', 'DBI202', 'FA2025', 8.00, 'PASSED'
  UNION ALL SELECT 'SE173099', 'PRJ301', 'FA2026', NULL, 'IN_PROGRESS'
) rec
JOIN students st ON st.student_code = rec.student_code
JOIN courses c ON c.code = rec.course_code
JOIN semesters sem ON sem.code = rec.sem_code
WHERE NOT EXISTS (
  SELECT 1 FROM student_course_records scr
  WHERE scr.student_id = st.user_id AND scr.course_id = c.id AND scr.attempt_number = 1
);

INSERT INTO semester_summaries (
  id, student_id, semester_id, total_credits_enrolled, total_credits_earned,
  semester_gpa, cumulative_gpa, academic_standing
)
SELECT UUID(), st.user_id, sem.id, sum_seed.enrolled, sum_seed.earned,
  sum_seed.sem_gpa, sum_seed.cum_gpa, sum_seed.standing
FROM (
  SELECT 'SE173012' AS student_code, 'FA2025' AS sem_code, 15 AS enrolled, 15 AS earned, 7.85 AS sem_gpa, 7.85 AS cum_gpa, 'GOOD' AS standing
  UNION ALL SELECT 'SE173026', 'FA2025', 15, 6, 4.15, 4.35, 'PROBATION'
  UNION ALL SELECT 'IA173041', 'FA2025', 15, 15, 7.20, 7.20, 'GOOD'
  UNION ALL SELECT 'IA173055', 'FA2025', 15, 9, 4.50, 4.60, 'PROBATION'
  UNION ALL SELECT 'SE173099', 'FA2025', 15, 15, 8.15, 8.10, 'GOOD'
) sum_seed
JOIN students st ON st.student_code = sum_seed.student_code
JOIN semesters sem ON sem.code = sum_seed.sem_code
WHERE NOT EXISTS (
  SELECT 1 FROM semester_summaries ss
  WHERE ss.student_id = st.user_id AND ss.semester_id = sem.id
);

-- -----------------------------------------------------------------------------
-- 15. Attendance Default Config & Student Course Attendance
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO attendance_default_config (
  singleton_id, total_sessions, min_attendance_percent, warn_absent_sessions, updated_at
) VALUES (1, 20, 80, 3, CURRENT_TIMESTAMP);

INSERT INTO student_course_attendance (
  id, student_id, course_id, semester_id, total_sessions, absent_sessions, absent_rate, result
)
SELECT UUID(), st.user_id, c.id, sem.id, att.total_sessions, att.absent_sessions, att.absent_rate, att.result
FROM (
  SELECT 'SE173012' AS student_code, 'PRJ301' AS course_code, 'FA2026' AS sem_code, 20 AS total_sessions, 1 AS absent_sessions, 5.00 AS absent_rate, 'OK' AS result
  UNION ALL SELECT 'SE173012', 'SWE201c', 'FA2026', 20, 3, 15.00, 'WARNING'
  UNION ALL SELECT 'SE173026', 'PRJ301', 'FA2026', 20, 5, 25.00, 'FAIL'
  UNION ALL SELECT 'SE173099', 'PRJ301', 'FA2026', 20, 0, 0.00, 'OK'
) att
JOIN students st ON st.student_code = att.student_code
JOIN courses c ON c.code = att.course_code
JOIN semesters sem ON sem.code = att.sem_code
WHERE NOT EXISTS (
  SELECT 1 FROM student_course_attendance sca
  WHERE sca.student_id = st.user_id AND sca.course_id = c.id AND sca.semester_id = sem.id
);

-- -----------------------------------------------------------------------------
-- 16. Academic Warnings (for At-Risk Students & Student Warnings API)
-- -----------------------------------------------------------------------------
INSERT INTO academic_warnings (
  id, student_id, semester_id, warning_type, severity, description, action_required, issued_by_user_id
)
SELECT UUID(), st.user_id, sem.id, warn.warning_type, warn.severity,
  warn.description, warn.action_required, admin_user.id
FROM (
  SELECT 'SE173026' AS student_code, 'FA2026' AS sem_code, 'LOW_GPA' AS warning_type, 'PROBATION' AS severity,
         'Cumulative GPA (4.35) is below the required 5.00 threshold and 3 courses were failed.' AS description,
         'Meet with your academic advisor to revise your study plan and prioritize retaking CSD201 and DBI202.' AS action_required
  UNION ALL
  SELECT 'SE173026', 'FA2026', 'ATTENDANCE', 'WARNING',
         'Absent rate in PRJ301 has reached 25% (5/20 sessions), exceeding the 20% maximum absence limit.',
         'Contact your lecturer and academic advisor immediately regarding PRJ301 attendance eligibility.'
  UNION ALL
  SELECT 'IA173055', 'FA2026', 'LOW_GPA', 'WARNING',
         'Cumulative GPA (4.60) is below the 5.00 academic standing threshold.',
         'Schedule an academic advising appointment before registering for next semester.'
) warn
JOIN students st ON st.student_code = warn.student_code
JOIN semesters sem ON sem.code = warn.sem_code
JOIN users admin_user ON admin_user.email = 'admin@fpt.edu.vn'
WHERE NOT EXISTS (
  SELECT 1 FROM academic_warnings aw
  WHERE aw.student_id = st.user_id AND aw.semester_id = sem.id AND aw.warning_type = warn.warning_type
);

-- -----------------------------------------------------------------------------
-- 17. Study Plans & Study Plan Courses
-- -----------------------------------------------------------------------------
INSERT INTO study_plans (
  id, student_id, semester_id, version, is_current, plan_type,
  total_credits_planned, status, submitted_at, review_notes
)
SELECT UUID(), st.user_id, sem.id, 1, TRUE, plan_seed.plan_type,
  plan_seed.total_credits, plan_seed.status, CURRENT_TIMESTAMP, plan_seed.review_notes
FROM (
  SELECT 'SE173012' AS student_code, 'SP2027' AS sem_code, 'REGULAR' AS plan_type, 9 AS total_credits, 'SUBMITTED' AS status,
         NULL AS review_notes
  UNION ALL
  SELECT 'SE173026', 'SP2027', 'MAKEUP', 6, 'SUBMITTED',
         NULL
  UNION ALL
  SELECT 'SE173099', 'SP2027', 'REGULAR', 9, 'APPROVED',
         'Well-balanced study plan aligned with K17 Software Engineering progression.'
) plan_seed
JOIN students st ON st.student_code = plan_seed.student_code
JOIN semesters sem ON sem.code = plan_seed.sem_code
WHERE NOT EXISTS (
  SELECT 1 FROM study_plans sp
  WHERE sp.student_id = st.user_id AND sp.semester_id = sem.id AND sp.version = 1
);

INSERT INTO study_plan_courses (id, study_plan_id, course_id, planned_credits, priority, notes)
SELECT UUID(), sp.id, c.id, c.credits, 'HIGH', 'Planned core progression course'
FROM study_plans sp
JOIN students st ON st.user_id = sp.student_id
JOIN courses c ON (
  (st.student_code IN ('SE173012', 'SE173099') AND c.code IN ('SWR302', 'SWT301', 'SWP391'))
  OR (st.student_code = 'SE173026' AND c.code IN ('CSD201', 'DBI202'))
)
WHERE NOT EXISTS (
  SELECT 1 FROM study_plan_courses spc
  WHERE spc.study_plan_id = sp.id AND spc.course_id = c.id
);

-- -----------------------------------------------------------------------------
-- 18. Advising Requests, Messages & Appointments (advising_sessions)
-- -----------------------------------------------------------------------------
INSERT INTO advising_requests (
  id, student_id, advisor_id, request_type, title, description, status
)
SELECT UUID(), st.user_id, adv.id, req.request_type, req.title, req.description, req.status
FROM (
  SELECT 'SE173012' AS student_code, 'hantt@fpt.edu.vn' AS advisor_email,
         'PLAN_REVIEW' AS request_type, 'Review Spring 2027 Capstone & Specialization Plan' AS title,
         'Em muốn nhờ cô xem giúp lộ trình đăng ký 3 môn SWR302, SWT301, SWP391 cho học kỳ Spring 2027.' AS description,
         'PENDING' AS status
  UNION ALL
  SELECT 'SE173026', 'baopq@fpt.edu.vn',
         'ACADEMIC_GUIDANCE', 'Tư vấn kế hoạch học lại CSD201 và cải thiện GPA',
         'GPA hiện tại của em dưới 5.0, em cần tư vấn lịch học lại CSD201 và DBI202 để thoát diện cảnh báo học vụ.',
         'IN_PROGRESS'
) req
JOIN students st ON st.student_code = req.student_code
JOIN users adv ON adv.email = req.advisor_email
WHERE NOT EXISTS (
  SELECT 1 FROM advising_requests ar
  WHERE ar.student_id = st.user_id AND ar.title = req.title
);

INSERT INTO advising_sessions (
  id, student_id, advisor_id, session_type, scheduled_at, location, meeting_link, agenda, summary, status
)
SELECT UUID(), st.user_id, adv.id, sess.session_type, sess.scheduled_at,
  sess.location, sess.meeting_link, sess.agenda, sess.summary, sess.status
FROM (
  SELECT 'SE173012' AS student_code, 'hantt@fpt.edu.vn' AS advisor_email,
         'VIRTUAL' AS session_type, DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 2 DAY) AS scheduled_at,
         'Google Meet' AS location, 'https://meet.google.com/faa-psad-vis' AS meeting_link,
         'Review Spring 2027 study plan and SWP391 project prerequisites' AS agenda,
         NULL AS summary, 'SCHEDULED' AS status
  UNION ALL
  SELECT 'SE173026', 'baopq@fpt.edu.vn',
         'SCHEDULED', DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 3 DAY),
         'Room 204 - Alpha Building', NULL,
         'Academic probation recovery plan and course retake strategy',
         'Advised student to focus on retaking CSD201 and DBI202 next semester and keep total credits <= 12.',
         'COMPLETED'
) sess
JOIN students st ON st.student_code = sess.student_code
JOIN users adv ON adv.email = sess.advisor_email
WHERE NOT EXISTS (
  SELECT 1 FROM advising_sessions s
  WHERE s.student_id = st.user_id AND s.agenda = sess.agenda
);

-- -----------------------------------------------------------------------------
-- 19. Default AI Configurations
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
