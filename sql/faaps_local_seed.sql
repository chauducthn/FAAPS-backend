USE faaps;
START TRANSACTION;

INSERT INTO majors (id, code, name, description, total_credits, duration_years, status)
SELECT UUID(), 'SE', 'Software Engineering',
  'Software engineering program focused on software design, development, testing, and delivery', 140, 4.0, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM majors WHERE code='SE');
INSERT INTO majors (id, code, name, description, total_credits, duration_years, status)
SELECT UUID(), 'IA', 'Information Assurance',
  'Information assurance program focused on cybersecurity, risk management, and secure systems', 140, 4.0, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM majors WHERE code='IA');

INSERT INTO semesters (id, code, name, start_date, end_date, is_current)
SELECT UUID(), 'FA2022', 'Fall 2022', '2022-09-05', '2022-12-23', FALSE
WHERE NOT EXISTS (SELECT 1 FROM semesters WHERE code='FA2022');

INSERT INTO curriculum_versions (id, major_id, version, name, effective_from_semester_id,
  description, is_current, status)
SELECT UUID(), m.id, 'K17', CONCAT(m.name, ' K17'), s.id,
  CONCAT('Curriculum for ', m.name, ' cohort K17'), TRUE, 'ACTIVE'
FROM majors m JOIN semesters s ON s.code='FA2022'
WHERE m.code IN ('SE','IA') AND NOT EXISTS (
  SELECT 1 FROM curriculum_versions cv WHERE cv.major_id=m.id AND cv.version='K17'
);

INSERT INTO course_categories (id, code, name, description)
SELECT UUID(), 'CORE', 'Core Courses', 'Required foundational and professional courses'
WHERE NOT EXISTS (SELECT 1 FROM course_categories WHERE code='CORE');

INSERT INTO courses (id, code, name, credits, category_id, description, status)
SELECT UUID(), seed.code, seed.name, 3, category.id, seed.description, 'ACTIVE'
FROM course_categories category JOIN (
  SELECT 'PRF192' code, 'Programming Fundamentals' name, 'Fundamental programming concepts and problem solving with C' description
  UNION ALL SELECT 'CSD201', 'Data Structures and Algorithms', 'Core data structures, algorithms, and complexity analysis'
  UNION ALL SELECT 'DBI202', 'Introduction to Databases', 'Relational database design, SQL, normalization, and transactions'
  UNION ALL SELECT 'IAI101', 'Introduction to Information Assurance', 'Security principles, threats, controls, and assurance practices'
) seed
WHERE category.code='CORE' AND NOT EXISTS (SELECT 1 FROM courses c WHERE c.code=seed.code);

INSERT INTO curriculum_courses (id, curriculum_version_id, course_id, semester_number, is_mandatory)
SELECT UUID(), cv.id, c.id,
  CASE c.code WHEN 'PRF192' THEN 1 ELSE 2 END, TRUE
FROM curriculum_versions cv JOIN majors m ON m.id=cv.major_id
JOIN courses c ON c.code IN ('PRF192','CSD201','DBI202','IAI101')
WHERE cv.version='K17'
  AND (m.code='SE' OR c.code IN ('PRF192','DBI202','IAI101'))
  AND NOT EXISTS (SELECT 1 FROM curriculum_courses cc WHERE cc.curriculum_version_id=cv.id AND cc.course_id=c.id);

INSERT INTO users (id, role_id, email, auth_provider, full_name, status)
SELECT UUID(), r.id, seed.email, 'LOCAL', seed.full_name, 'ACTIVE'
FROM roles r JOIN (
  SELECT 'hantt@fpt.edu.vn' email, 'Nguyễn Thị Thu Hà' full_name
  UNION ALL SELECT 'baopq@fpt.edu.vn', 'Phạm Quốc Bảo'
) seed
WHERE r.code='ADVISOR' AND NOT EXISTS (SELECT 1 FROM users u WHERE u.email=seed.email);
INSERT INTO advisors (user_id, max_advisees, status)
SELECT u.id, 30, 'ACTIVE' FROM users u
WHERE u.email IN ('hantt@fpt.edu.vn','baopq@fpt.edu.vn')
  AND NOT EXISTS (SELECT 1 FROM advisors a WHERE a.user_id=u.id);

INSERT INTO users (id, role_id, email, auth_provider, full_name, status)
SELECT UUID(), r.id, seed.email, 'LOCAL', seed.full_name, 'ACTIVE'
FROM roles r JOIN (
  SELECT 'luongnvse173012@fpt.edu.vn' email, 'Nguyễn Văn Lương' full_name
  UNION ALL SELECT 'lamvtse173026@fpt.edu.vn', 'Võ Thanh Lâm'
  UNION ALL SELECT 'khoitmia173041@fpt.edu.vn', 'Trần Minh Khôi'
  UNION ALL SELECT 'anhlhia173055@fpt.edu.vn', 'Lê Hoàng Anh'
) seed
WHERE r.code='STUDENT' AND NOT EXISTS (SELECT 1 FROM users u WHERE u.email=seed.email);

INSERT INTO students (user_id, student_code, major_id, curriculum_version_id,
  enrollment_semester_id, current_semester, academic_status)
SELECT u.id, seed.student_code, m.id, cv.id, sem.id, 9, 'ACTIVE'
FROM (
  SELECT 'luongnvse173012@fpt.edu.vn' email, 'SE173012' student_code, 'SE' major_code
  UNION ALL SELECT 'lamvtse173026@fpt.edu.vn', 'SE173026', 'SE'
  UNION ALL SELECT 'khoitmia173041@fpt.edu.vn', 'IA173041', 'IA'
  UNION ALL SELECT 'anhlhia173055@fpt.edu.vn', 'IA173055', 'IA'
) seed JOIN users u ON u.email=seed.email
JOIN majors m ON m.code=seed.major_code
JOIN curriculum_versions cv ON cv.major_id=m.id AND cv.version='K17'
JOIN semesters sem ON sem.code='FA2022'
WHERE NOT EXISTS (SELECT 1 FROM students st WHERE st.user_id=u.id);

INSERT INTO student_advisor_assignments (id, student_id, advisor_id, group_name,
  assigned_date, notes, status)
SELECT UUID(), st.user_id, advisor.id, 'K17', '2026-09-01', 'Initial advisor assignment', 'ACTIVE'
FROM students st JOIN users student_user ON student_user.id=st.user_id
JOIN users advisor ON advisor.email=IF(st.student_code IN ('SE173012','IA173041'),
  'hantt@fpt.edu.vn','baopq@fpt.edu.vn')
WHERE st.student_code IN ('SE173012','SE173026','IA173041')
  AND NOT EXISTS (SELECT 1 FROM student_advisor_assignments a
    WHERE a.student_id=st.user_id AND a.status='ACTIVE');

COMMIT;

SELECT m.code AS major, COUNT(*) AS students
FROM students st JOIN majors m ON m.id=st.major_id GROUP BY m.code ORDER BY m.code;
