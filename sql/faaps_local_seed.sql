-- =============================================================================
-- FAAPS Local Database Seeder (Synced with 2026-11-24_seed_local_if_empty.sql)
-- 100% Idempotent: Tracks existing rows via WHERE NOT EXISTS / INSERT IGNORE
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
INSERT INTO course_categories (id, code, name, description)
SELECT UUID(), 'CORE', 'Core Courses', 'Required foundational and professional courses'
WHERE NOT EXISTS (SELECT 1 FROM course_categories WHERE code='CORE');

INSERT INTO course_categories (id, code, name, description)
SELECT UUID(), 'MATH', 'Mathematics & Basic Sciences', 'Foundational mathematics and quantitative reasoning courses'
WHERE NOT EXISTS (SELECT 1 FROM course_categories WHERE code='MATH');

INSERT INTO course_categories (id, code, name, description)
SELECT UUID(), 'SPEC', 'Specialization & Capstone', 'Major specialization and graduation capstone courses'
WHERE NOT EXISTS (SELECT 1 FROM course_categories WHERE code='SPEC');

-- 7. Courses (with dedicated material_link column)
INSERT INTO courses (id, code, name, credits, category_id, description, material_link, status)
SELECT UUID(), seed.code, seed.name, 3, category.id, seed.description,
  CONCAT('https://flm.fpt.edu.vn/gui/role/student/SyllabusDetails?sylId=', seed.code), 'ACTIVE'
FROM course_categories category JOIN (
  SELECT 'PRF192' code, 'Programming Fundamentals' name, 'Fundamental programming concepts and problem solving with C' description
  UNION ALL SELECT 'PRO192', 'Object-Oriented Programming', 'Encapsulation, inheritance, polymorphism, and collections in Java'
  UNION ALL SELECT 'CSD201', 'Data Structures and Algorithms', 'Core data structures, algorithms, and complexity analysis'
  UNION ALL SELECT 'DBI202', 'Introduction to Databases', 'Relational database design, SQL, normalization, and transactions'
  UNION ALL SELECT 'PRJ301', 'Java Web Application Development', 'Servlets, JSP, MVC architecture, and JDBC web development'
  UNION ALL SELECT 'SWE201c', 'Introduction to Software Engineering', 'Software development life cycle, Agile/Scrum, requirements, and testing'
  UNION ALL SELECT 'SWP391', 'Application Development Project', 'Team-based software application development project'
  UNION ALL SELECT 'IAI101', 'Introduction to Information Assurance', 'Security principles, threats, controls, and assurance practices'
) seed
WHERE category.code='CORE' AND NOT EXISTS (SELECT 1 FROM courses c WHERE c.code=seed.code);

UPDATE courses
SET material_link = CONCAT('https://flm.fpt.edu.vn/gui/role/student/SyllabusDetails?sylId=', code)
WHERE material_link IS NULL OR TRIM(material_link) = '';

UPDATE courses
SET learning_outcomes = NULL
WHERE learning_outcomes LIKE 'http://%' OR learning_outcomes LIKE 'https://%';

-- 8. Curriculum Courses
INSERT INTO curriculum_courses (id, curriculum_version_id, course_id, semester_number, is_mandatory)
SELECT UUID(), cv.id, c.id,
  CASE c.code
    WHEN 'PRF192' THEN 1
    WHEN 'PRO192' THEN 2
    WHEN 'CSD201' THEN 3
    WHEN 'DBI202' THEN 3
    WHEN 'IAI101' THEN 3
    WHEN 'PRJ301' THEN 4
    WHEN 'SWE201c' THEN 4
    WHEN 'SWP391' THEN 5
    ELSE 2
  END, TRUE
FROM curriculum_versions cv JOIN majors m ON m.id=cv.major_id
JOIN courses c ON c.code IN ('PRF192','PRO192','CSD201','DBI202','PRJ301','SWE201c','SWP391','IAI101')
WHERE cv.version='K17'
  AND (m.code='SE' OR c.code IN ('PRF192','PRO192','DBI202','IAI101'))
  AND NOT EXISTS (SELECT 1 FROM curriculum_courses cc WHERE cc.curriculum_version_id=cv.id AND cc.course_id=c.id);

-- 9. Academic Rules (rule_name & grade JSON)
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

-- 10. Advisors
INSERT INTO users (id, role_id, email, password_hash, auth_provider, full_name, phone, status)
SELECT UUID(), r.id, seed.email,
  '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
  'LOCAL', seed.full_name, seed.phone, 'ACTIVE'
FROM roles r JOIN (
  SELECT 'hantt@fpt.edu.vn' email, 'Nguyễn Thị Thu Hà' full_name, '0902000001' phone
  UNION ALL SELECT 'baopq@fpt.edu.vn', 'Phạm Quốc Bảo', '0902000002'
) seed
WHERE r.code='ADVISOR' AND NOT EXISTS (SELECT 1 FROM users u WHERE u.email=seed.email);

INSERT INTO advisors (user_id, advisor_code, max_advisees, status)
SELECT u.id, IF(u.email='hantt@fpt.edu.vn','ADV001','ADV002'), 30, 'ACTIVE' FROM users u
WHERE u.email IN ('hantt@fpt.edu.vn','baopq@fpt.edu.vn')
  AND NOT EXISTS (SELECT 1 FROM advisors a WHERE a.user_id=u.id);

-- 11. Students
INSERT INTO users (id, role_id, email, password_hash, auth_provider, full_name, phone, status)
SELECT UUID(), r.id, seed.email,
  '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
  'LOCAL', seed.full_name, seed.phone, 'ACTIVE'
FROM roles r JOIN (
  SELECT 'luongnvse173012@fpt.edu.vn' email, 'Nguyễn Văn Lương' full_name, '0903000012' phone
  UNION ALL SELECT 'lamvtse173026@fpt.edu.vn', 'Võ Thanh Lâm', '0903000026'
  UNION ALL SELECT 'khoitmia173041@fpt.edu.vn', 'Trần Minh Khôi', '0903000041'
  UNION ALL SELECT 'anhlhia173055@fpt.edu.vn', 'Lê Hoàng Anh', '0903000055'
) seed
WHERE r.code='STUDENT' AND NOT EXISTS (SELECT 1 FROM users u WHERE u.email=seed.email);

INSERT INTO students (user_id, student_code, major_id, curriculum_version_id,
  enrollment_semester_id, current_semester, current_gpa, total_credits_earned, academic_status)
SELECT u.id, seed.student_code, m.id, cv.id, sem.id, 5, seed.gpa, seed.credits, seed.status
FROM (
  SELECT 'luongnvse173012@fpt.edu.vn' email, 'SE173012' student_code, 'SE' major_code, 7.85 gpa, 36 credits, 'ACTIVE' status
  UNION ALL SELECT 'lamvtse173026@fpt.edu.vn', 'SE173026', 'SE', 4.35, 24, 'PROBATION'
  UNION ALL SELECT 'khoitmia173041@fpt.edu.vn', 'IA173041', 'IA', 7.20, 30, 'ACTIVE'
  UNION ALL SELECT 'anhlhia173055@fpt.edu.vn', 'IA173055', 'IA', 4.60, 18, 'PROBATION'
) seed JOIN users u ON u.email=seed.email
JOIN majors m ON m.code=seed.major_code
JOIN curriculum_versions cv ON cv.major_id=m.id AND cv.version='K17'
JOIN semesters sem ON sem.code='FA2022'
WHERE NOT EXISTS (SELECT 1 FROM students st WHERE st.user_id=u.id);

-- 12. Student-Advisor Assignments
INSERT INTO student_advisor_assignments (id, student_id, advisor_id, group_name,
  assigned_date, notes, status)
SELECT UUID(), st.user_id, advisor.id, 'K17', '2026-09-01', 'Initial advisor assignment', 'ACTIVE'
FROM students st JOIN users student_user ON student_user.id=st.user_id
JOIN users advisor ON advisor.email=IF(st.student_code IN ('SE173012','IA173041'),
  'hantt@fpt.edu.vn','baopq@fpt.edu.vn')
WHERE st.student_code IN ('SE173012','SE173026','IA173041','IA173055')
  AND NOT EXISTS (SELECT 1 FROM student_advisor_assignments a
    WHERE a.student_id=st.user_id AND a.status='ACTIVE');

COMMIT;

SELECT m.code AS major, COUNT(*) AS students
FROM students st JOIN majors m ON m.id=st.major_id GROUP BY m.code ORDER BY m.code;
