USE faaps;

START TRANSACTION;

INSERT INTO majors (id, code, name, description, total_credits, duration_years)
SELECT UUID(), 'FAAPS_DEMO_SE', 'Software Engineering (Demo)', 'FAAPS demo data only', 140, 4.0
WHERE NOT EXISTS (SELECT 1 FROM majors WHERE code = 'FAAPS_DEMO_SE');

INSERT INTO semesters (id, code, name, start_date, end_date, is_current)
SELECT UUID(), 'FAAPS_DEMO_FA2026', 'Fall 2026 (Demo)', '2026-09-01', '2026-12-31', FALSE
WHERE NOT EXISTS (SELECT 1 FROM semesters WHERE code = 'FAAPS_DEMO_FA2026');

INSERT INTO curriculum_versions (id, major_id, version, name, effective_from_semester_id, description)
SELECT UUID(), m.id, 'K99DEMO', 'Software Engineering K99 (Demo)', semester.id, 'FAAPS demo data only'
FROM majors m JOIN semesters semester ON semester.code = 'FAAPS_DEMO_FA2026'
WHERE m.code = 'FAAPS_DEMO_SE'
  AND NOT EXISTS (SELECT 1 FROM curriculum_versions cv WHERE cv.major_id = m.id AND cv.version = 'K99DEMO');

INSERT INTO users (id, role_id, email, auth_provider, full_name, status)
SELECT UUID(), access_role.id, 'faaps-demo-advisor-01@example.invalid', 'LOCAL', 'Demo Advisor 01', 'ACTIVE'
FROM roles access_role WHERE access_role.code = 'ADVISOR'
  AND NOT EXISTS (SELECT 1 FROM users WHERE email = 'faaps-demo-advisor-01@example.invalid');
INSERT INTO users (id, role_id, email, auth_provider, full_name, status)
SELECT UUID(), access_role.id, 'faaps-demo-advisor-02@example.invalid', 'LOCAL', 'Demo Advisor 02', 'ACTIVE'
FROM roles access_role WHERE access_role.code = 'ADVISOR'
  AND NOT EXISTS (SELECT 1 FROM users WHERE email = 'faaps-demo-advisor-02@example.invalid');

INSERT INTO advisors (user_id, max_advisees)
SELECT account.id, 30 FROM users account
WHERE account.email = 'faaps-demo-advisor-01@example.invalid'
  AND NOT EXISTS (SELECT 1 FROM advisors WHERE user_id = account.id);
INSERT INTO advisors (user_id, max_advisees)
SELECT account.id, 30 FROM users account
WHERE account.email = 'faaps-demo-advisor-02@example.invalid'
  AND NOT EXISTS (SELECT 1 FROM advisors WHERE user_id = account.id);

INSERT INTO users (id, role_id, email, auth_provider, full_name, status)
SELECT UUID(), access_role.id, 'faaps-demo-student-01@example.invalid', 'LOCAL', 'Demo Student 01', 'ACTIVE'
FROM roles access_role WHERE access_role.code = 'STUDENT'
  AND NOT EXISTS (SELECT 1 FROM users WHERE email = 'faaps-demo-student-01@example.invalid');
INSERT INTO users (id, role_id, email, auth_provider, full_name, status)
SELECT UUID(), access_role.id, 'faaps-demo-student-02@example.invalid', 'LOCAL', 'Demo Student 02', 'ACTIVE'
FROM roles access_role WHERE access_role.code = 'STUDENT'
  AND NOT EXISTS (SELECT 1 FROM users WHERE email = 'faaps-demo-student-02@example.invalid');
INSERT INTO users (id, role_id, email, auth_provider, full_name, status)
SELECT UUID(), access_role.id, 'faaps-demo-student-03@example.invalid', 'LOCAL', 'Demo Student 03', 'ACTIVE'
FROM roles access_role WHERE access_role.code = 'STUDENT'
  AND NOT EXISTS (SELECT 1 FROM users WHERE email = 'faaps-demo-student-03@example.invalid');
INSERT INTO users (id, role_id, email, auth_provider, full_name, status)
SELECT UUID(), access_role.id, 'faaps-demo-student-04@example.invalid', 'LOCAL', 'Demo Student 04', 'ACTIVE'
FROM roles access_role WHERE access_role.code = 'STUDENT'
  AND NOT EXISTS (SELECT 1 FROM users WHERE email = 'faaps-demo-student-04@example.invalid');

INSERT INTO students (user_id, student_code, major_id, curriculum_version_id, enrollment_semester_id)
SELECT account.id, 'FAAPS_DEMO_ST01', major.id, curriculum.id, semester.id
FROM users account JOIN majors major ON major.code = 'FAAPS_DEMO_SE'
JOIN curriculum_versions curriculum ON curriculum.major_id = major.id AND curriculum.version = 'K99DEMO'
JOIN semesters semester ON semester.code = 'FAAPS_DEMO_FA2026'
WHERE account.email = 'faaps-demo-student-01@example.invalid'
  AND NOT EXISTS (SELECT 1 FROM students WHERE user_id = account.id);
INSERT INTO students (user_id, student_code, major_id, curriculum_version_id, enrollment_semester_id)
SELECT account.id, 'FAAPS_DEMO_ST02', major.id, curriculum.id, semester.id
FROM users account JOIN majors major ON major.code = 'FAAPS_DEMO_SE'
JOIN curriculum_versions curriculum ON curriculum.major_id = major.id AND curriculum.version = 'K99DEMO'
JOIN semesters semester ON semester.code = 'FAAPS_DEMO_FA2026'
WHERE account.email = 'faaps-demo-student-02@example.invalid'
  AND NOT EXISTS (SELECT 1 FROM students WHERE user_id = account.id);
INSERT INTO students (user_id, student_code, major_id, curriculum_version_id, enrollment_semester_id)
SELECT account.id, 'FAAPS_DEMO_ST03', major.id, curriculum.id, semester.id
FROM users account JOIN majors major ON major.code = 'FAAPS_DEMO_SE'
JOIN curriculum_versions curriculum ON curriculum.major_id = major.id AND curriculum.version = 'K99DEMO'
JOIN semesters semester ON semester.code = 'FAAPS_DEMO_FA2026'
WHERE account.email = 'faaps-demo-student-03@example.invalid'
  AND NOT EXISTS (SELECT 1 FROM students WHERE user_id = account.id);
INSERT INTO students (user_id, student_code, major_id, curriculum_version_id, enrollment_semester_id)
SELECT account.id, 'FAAPS_DEMO_ST04', major.id, curriculum.id, semester.id
FROM users account JOIN majors major ON major.code = 'FAAPS_DEMO_SE'
JOIN curriculum_versions curriculum ON curriculum.major_id = major.id AND curriculum.version = 'K99DEMO'
JOIN semesters semester ON semester.code = 'FAAPS_DEMO_FA2026'
WHERE account.email = 'faaps-demo-student-04@example.invalid'
  AND NOT EXISTS (SELECT 1 FROM students WHERE user_id = account.id);

INSERT INTO student_advisor_assignments (id, student_id, advisor_id, group_name, assigned_date, notes)
SELECT UUID(), student.user_id, advisor.user_id, 'K99DEMO', CURRENT_DATE(), 'FAAPS demo seed'
FROM students student JOIN users advisor_account ON advisor_account.email = 'faaps-demo-advisor-01@example.invalid'
JOIN advisors advisor ON advisor.user_id = advisor_account.id
WHERE student.student_code = 'FAAPS_DEMO_ST01'
  AND NOT EXISTS (SELECT 1 FROM student_advisor_assignments assignment
                  WHERE assignment.student_id = student.user_id AND assignment.status = 'ACTIVE');
INSERT INTO student_advisor_assignments (id, student_id, advisor_id, group_name, assigned_date, notes)
SELECT UUID(), student.user_id, advisor.user_id, 'K99DEMO', CURRENT_DATE(), 'FAAPS demo seed'
FROM students student JOIN users advisor_account ON advisor_account.email = 'faaps-demo-advisor-02@example.invalid'
JOIN advisors advisor ON advisor.user_id = advisor_account.id
WHERE student.student_code = 'FAAPS_DEMO_ST02'
  AND NOT EXISTS (SELECT 1 FROM student_advisor_assignments assignment
                  WHERE assignment.student_id = student.user_id AND assignment.status = 'ACTIVE');

COMMIT;

SELECT 'demo_majors' AS item, COUNT(*) AS total FROM majors WHERE code = 'FAAPS_DEMO_SE'
UNION ALL SELECT 'demo_curricula', COUNT(*) FROM curriculum_versions WHERE version = 'K99DEMO'
UNION ALL SELECT 'demo_advisors', COUNT(*) FROM advisors advisor
JOIN users account ON account.id = advisor.user_id
WHERE account.email LIKE 'faaps-demo-advisor-%@example.invalid'
UNION ALL SELECT 'demo_students', COUNT(*) FROM students WHERE student_code LIKE 'FAAPS_DEMO_ST%'
UNION ALL SELECT 'demo_active_assignments', COUNT(*) FROM student_advisor_assignments
WHERE group_name = 'K99DEMO' AND status = 'ACTIVE';
