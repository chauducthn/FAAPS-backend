-- Local development data cleanup. Do not run against shared or production databases.
START TRANSACTION;

UPDATE majors SET code='SE', name='Software Engineering',
  description='Software engineering program focused on software design, development, testing, and delivery'
WHERE code='FAAPS_DEMO_SE';

INSERT INTO majors (id, code, name, description, total_credits, duration_years, status)
SELECT UUID(), 'IA', 'Information Assurance',
  'Information assurance program focused on cybersecurity, risk management, and secure systems', 140, 4.0, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM majors WHERE code='IA');

UPDATE semesters SET code='FA2022', name='Fall 2022', start_date='2022-09-05',
  end_date='2022-12-23', is_current=FALSE
WHERE code='FAAPS_DEMO_FA2026';

UPDATE curriculum_versions cv JOIN majors m ON m.id=cv.major_id
SET cv.version='K17', cv.name='Software Engineering K17',
    cv.description='Curriculum for Software Engineering cohort K17', cv.is_current=TRUE
WHERE m.code='SE' AND cv.version='K99DEMO';

INSERT INTO curriculum_versions (id, major_id, version, name, effective_from_semester_id,
  description, is_current, status)
SELECT UUID(), m.id, 'K17', 'Information Assurance K17', s.id,
  'Curriculum for Information Assurance cohort K17', TRUE, 'ACTIVE'
FROM majors m JOIN semesters s ON s.code='FA2022'
WHERE m.code='IA' AND NOT EXISTS (
  SELECT 1 FROM curriculum_versions cv WHERE cv.major_id=m.id AND cv.version='K17'
);

UPDATE courses SET code='PRF192', name='Programming Fundamentals',
  description='Fundamental programming concepts and problem solving with C',
  objectives='Build structured programs and solve introductory computational problems',
  learning_outcomes='Write, test, and debug structured programs'
WHERE code='FAAPS_DEMO_PRG101';
UPDATE courses SET code='CSD201', name='Data Structures and Algorithms',
  description='Core data structures, algorithms, and complexity analysis',
  objectives='Select suitable data structures and algorithms for common problems',
  learning_outcomes='Implement and analyze fundamental algorithms'
WHERE code='FAAPS_DEMO_DSA201';
UPDATE courses SET code='DBI202', name='Introduction to Databases',
  description='Relational database design, SQL, normalization, and transactions',
  objectives='Design normalized relational databases and write reliable SQL',
  learning_outcomes='Create schemas, queries, and transactional database operations'
WHERE code='FAAPS_DEMO_DB202';
UPDATE courses SET code='IAI101', name='Introduction to Information Assurance',
  description='Security principles, threats, controls, and information assurance practices',
  objectives='Recognize common security risks and select appropriate controls',
  learning_outcomes='Assess basic security risks and explain defensive measures'
WHERE code='FAAPS_DEMO_DELETE999';

UPDATE users SET email='hantt@fpt.edu.vn', full_name='Nguyễn Thị Thu Hà'
WHERE email='faaps-demo-advisor-01@example.invalid';
UPDATE users SET email='baopq@fpt.edu.vn', full_name='Phạm Quốc Bảo'
WHERE email='faaps-demo-advisor-02@example.invalid';
UPDATE users SET full_name='Châu Đức Thiện'
WHERE email='chauducthien00@gmail.com';
UPDATE users SET full_name='Nguyễn Minh Anh'
WHERE email='edufameusa07@gmail.com';

UPDATE users SET email='luongnvse173012@fpt.edu.vn', full_name='Nguyễn Văn Lương'
WHERE email='faaps-demo-student-01@example.invalid';
UPDATE users SET email='lamvtse173026@fpt.edu.vn', full_name='Võ Thanh Lâm'
WHERE email='faaps-demo-student-02@example.invalid';
UPDATE users SET email='khoitmia173041@fpt.edu.vn', full_name='Trần Minh Khôi'
WHERE email='faaps-demo-student-03@example.invalid';
UPDATE users SET email='anhlhia173055@fpt.edu.vn', full_name='Lê Hoàng Anh'
WHERE email='faaps-demo-student-04@example.invalid';
UPDATE users SET full_name='Châu Đức Thiện'
WHERE email='thiencdse173019@fpt.edu.vn';

UPDATE students SET student_code='SE173012' WHERE student_code='FAAPS_DEMO_ST01';
UPDATE students SET student_code='SE173026' WHERE student_code='FAAPS_DEMO_ST02';
UPDATE students SET student_code='IA173041' WHERE student_code='FAAPS_DEMO_ST03';
UPDATE students SET student_code='IA173055' WHERE student_code='FAAPS_DEMO_ST04';
UPDATE students SET student_code='SE173019' WHERE student_code='FAAPS_LOCAL_ST173019';

UPDATE students st
JOIN majors m ON m.code=IF(st.student_code LIKE 'IA%', 'IA', 'SE')
JOIN curriculum_versions cv ON cv.major_id=m.id AND cv.version='K17'
JOIN semesters sem ON sem.code='FA2022'
SET st.major_id=m.id, st.curriculum_version_id=cv.id,
    st.enrollment_semester_id=sem.id, st.current_semester=9,
    st.updated_at=CURRENT_TIMESTAMP
WHERE st.student_code REGEXP '^(SE|IA)1[0-9]{5}$';

UPDATE student_advisor_assignments SET group_name='K17', notes='Initial advisor assignment'
WHERE group_name='K99DEMO' OR notes LIKE '%demo%' OR notes LIKE '%test%';

UPDATE academic_policies SET description='Academic warning when cumulative GPA is below 5.0',
  display_message='Review your academic progress with an advisor.'
WHERE description LIKE '%demo%' OR description LIKE '%mock%' OR description LIKE '%example%';

COMMIT;

SELECT s.student_code, u.full_name, m.code AS major, cv.version AS cohort
FROM students s JOIN users u ON u.id=s.user_id JOIN majors m ON m.id=s.major_id
JOIN curriculum_versions cv ON cv.id=s.curriculum_version_id
ORDER BY s.student_code;
