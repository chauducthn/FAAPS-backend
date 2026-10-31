-- =============================================================================
-- FAAPS Seed Script: 100 Mock Users (90 Students, 7 Advisors, 3 Admins)
-- Date: 2026-10-31
-- Distribution: 30 SE (SE180001-30), 30 IA (IA180001-30), 30 AI (AI180001-30)
-- 1 Active Singleton Admin + 2 Inactive Secondary Admins
-- Pre-assigns 20 Advisees across 7 Advisors; 70 Students unassigned
-- =============================================================================
USE `faaps`;

-- 1. Ensure Majors exist
INSERT INTO `majors` (`id`, `code`, `name`, `description`, `total_credits`, `duration_years`, `status`)
SELECT UUID(), 'SE', 'Software Engineering', 'Software Engineering program', 140, 4.0, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `majors` WHERE `code` = 'SE');

INSERT INTO `majors` (`id`, `code`, `name`, `description`, `total_credits`, `duration_years`, `status`)
SELECT UUID(), 'IA', 'Information Assurance', 'Information Assurance program', 140, 4.0, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `majors` WHERE `code` = 'IA');

INSERT INTO `majors` (`id`, `code`, `name`, `description`, `total_credits`, `duration_years`, `status`)
SELECT UUID(), 'AI', 'Artificial Intelligence', 'Artificial Intelligence program', 140, 4.0, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `majors` WHERE `code` = 'AI');

-- 2. Ensure K18 Curriculum Versions exist
INSERT INTO `curriculum_versions` (`id`, `major_id`, `version`, `name`, `effective_from_semester_id`, `is_current`, `status`)
SELECT UUID(), m.`id`, 'K18', CONCAT(m.`name`, ' - Cohort K18'), (SELECT `id` FROM `semesters` LIMIT 1), true, 'ACTIVE'
FROM `majors` m
WHERE m.`code` IN ('SE', 'IA', 'AI')
  AND NOT EXISTS (SELECT 1 FROM `curriculum_versions` cv WHERE cv.`major_id` = m.`id` AND cv.`version` = 'K18');

-- 3. Seed 3 Admins (1 Active Singleton, 2 Inactive Secondary)

-- Admin 1: Nguyen Van Lead (ACTIVE)
INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'ADMIN'), 'lead.admin@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Nguyen Van Lead', '0909999001', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'lead.admin@fpt.edu.vn');

-- Set designated singleton active admin in admin_account (if table exists)
INSERT INTO `admin_account` (`singleton_id`, `user_id`)
SELECT 1, u.`id`
FROM `users` u WHERE u.`email` = 'lead.admin@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `user_id` = VALUES(`user_id`);

-- Admin 2: Tran Audit Mot (INACTIVE)
INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'ADMIN'), 'audit1.admin@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Tran Audit Mot', '0909999002', 'INACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'audit1.admin@fpt.edu.vn');

-- Admin 3: Le Audit Hai (INACTIVE)
INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'ADMIN'), 'audit2.admin@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Le Audit Hai', '0909999003', 'INACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'audit2.admin@fpt.edu.vn');

-- 4. Seed 7 Advisors

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'ADVISOR'), 'adv.minhnv@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Nguyen Van Minh', '0901234001', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'adv.minhnv@fpt.edu.vn');

INSERT INTO `advisors` (`user_id`, `advisor_code`, `specialization`, `max_advisees`, `status`, `assignment_start_date`)
SELECT u.`id`, 'ADV001', 'Software Engineering & Cloud Architecture', 30, 'ACTIVE', DATE '2024-09-01'
FROM `users` u
WHERE u.`email` = 'adv.minhnv@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `advisor_code` = VALUES(`advisor_code`), `specialization` = VALUES(`specialization`), `max_advisees` = VALUES(`max_advisees`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'ADVISOR'), 'adv.maitt@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Tran Thi Mai', '0901234002', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'adv.maitt@fpt.edu.vn');

INSERT INTO `advisors` (`user_id`, `advisor_code`, `specialization`, `max_advisees`, `status`, `assignment_start_date`)
SELECT u.`id`, 'ADV002', 'Software Engineering & Web Systems', 30, 'ACTIVE', DATE '2024-09-01'
FROM `users` u
WHERE u.`email` = 'adv.maitt@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `advisor_code` = VALUES(`advisor_code`), `specialization` = VALUES(`specialization`), `max_advisees` = VALUES(`max_advisees`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'ADVISOR'), 'adv.longlh@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Le Hoang Long', '0901234003', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'adv.longlh@fpt.edu.vn');

INSERT INTO `advisors` (`user_id`, `advisor_code`, `specialization`, `max_advisees`, `status`, `assignment_start_date`)
SELECT u.`id`, 'ADV003', 'Information Assurance & Network Security', 30, 'ACTIVE', DATE '2024-09-01'
FROM `users` u
WHERE u.`email` = 'adv.longlh@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `advisor_code` = VALUES(`advisor_code`), `specialization` = VALUES(`specialization`), `max_advisees` = VALUES(`max_advisees`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'ADVISOR'), 'adv.thangpd@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Pham Duc Thang', '0901234004', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'adv.thangpd@fpt.edu.vn');

INSERT INTO `advisors` (`user_id`, `advisor_code`, `specialization`, `max_advisees`, `status`, `assignment_start_date`)
SELECT u.`id`, 'ADV004', 'Information Assurance & Cryptography', 30, 'ACTIVE', DATE '2024-09-01'
FROM `users` u
WHERE u.`email` = 'adv.thangpd@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `advisor_code` = VALUES(`advisor_code`), `specialization` = VALUES(`specialization`), `max_advisees` = VALUES(`max_advisees`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'ADVISOR'), 'adv.lanvt@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Vu Thi Lan', '0901234005', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'adv.lanvt@fpt.edu.vn');

INSERT INTO `advisors` (`user_id`, `advisor_code`, `specialization`, `max_advisees`, `status`, `assignment_start_date`)
SELECT u.`id`, 'ADV005', 'Artificial Intelligence & Machine Learning', 30, 'ACTIVE', DATE '2024-09-01'
FROM `users` u
WHERE u.`email` = 'adv.lanvt@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `advisor_code` = VALUES(`advisor_code`), `specialization` = VALUES(`specialization`), `max_advisees` = VALUES(`max_advisees`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'ADVISOR'), 'adv.tuandm@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Dang Minh Tuan', '0901234006', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'adv.tuandm@fpt.edu.vn');

INSERT INTO `advisors` (`user_id`, `advisor_code`, `specialization`, `max_advisees`, `status`, `assignment_start_date`)
SELECT u.`id`, 'ADV006', 'Artificial Intelligence & Computer Vision', 30, 'ACTIVE', DATE '2024-09-01'
FROM `users` u
WHERE u.`email` = 'adv.tuandm@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `advisor_code` = VALUES(`advisor_code`), `specialization` = VALUES(`specialization`), `max_advisees` = VALUES(`max_advisees`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'ADVISOR'), 'adv.khanhbq@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Bui Quoc Khanh', '0901234007', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'adv.khanhbq@fpt.edu.vn');

INSERT INTO `advisors` (`user_id`, `advisor_code`, `specialization`, `max_advisees`, `status`, `assignment_start_date`)
SELECT u.`id`, 'ADV007', 'Software Engineering & Mobile Development', 30, 'ACTIVE', DATE '2024-09-01'
FROM `users` u
WHERE u.`email` = 'adv.khanhbq@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `advisor_code` = VALUES(`advisor_code`), `specialization` = VALUES(`specialization`), `max_advisees` = VALUES(`max_advisees`);

-- 5. Seed 90 Students (30 SE, 30 IA, 30 AI)

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'se180001@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Nguyen Van Hai', '0981000001', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'se180001@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'SE180001', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'SE'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'se180001@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'se180002@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Tran Van Huy', '0981000002', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'se180002@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'SE180002', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'SE'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'se180002@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'se180003@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Le Van Khoa', '0981000003', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'se180003@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'SE180003', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'SE'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'se180003@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'se180004@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Pham Van Long', '0981000004', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'se180004@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'SE180004', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'SE'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'se180004@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'se180005@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Hoang Van Nghia', '0981000005', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'se180005@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'SE180005', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'SE'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'se180005@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'se180006@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Phan Van Quan', '0981000006', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'se180006@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'SE180006', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'SE'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'se180006@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'se180007@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Vu Van Son', '0981000007', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'se180007@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'SE180007', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'SE'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'se180007@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'se180008@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Dang Van Thao', '0981000008', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'se180008@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'SE180008', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'SE'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'se180008@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'se180009@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Bui Van Toan', '0981000009', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'se180009@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'SE180009', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'SE'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'se180009@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'se180010@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Do Van Tuan', '0981000010', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'se180010@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'SE180010', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'SE'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'se180010@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'se180011@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Ho Van Vinh', '0981000011', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'se180011@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'SE180011', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'SE'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'se180011@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'se180012@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Ngo Van An', '0981000012', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'se180012@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'SE180012', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'SE'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'se180012@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'se180013@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Duong Van Dung', '0981000013', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'se180013@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'SE180013', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'SE'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'se180013@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'se180014@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Ly Van Giang', '0981000014', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'se180014@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'SE180014', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'SE'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'se180014@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'se180015@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Nguyen Thi Hoang', '0981000015', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'se180015@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'SE180015', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'SE'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'se180015@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'se180016@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Tran Thi Khanh', '0981000016', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'se180016@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'SE180016', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'SE'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'se180016@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'se180017@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Le Thi Linh', '0981000017', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'se180017@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'SE180017', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'SE'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'se180017@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'se180018@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Pham Thi Nam', '0981000018', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'se180018@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'SE180018', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'SE'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'se180018@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'se180019@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Hoang Thi Phuc', '0981000019', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'se180019@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'SE180019', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'SE'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'se180019@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'se180020@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Phan Thi Sang', '0981000020', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'se180020@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'SE180020', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'SE'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'se180020@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'se180021@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Vu Thi Thanh', '0981000021', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'se180021@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'SE180021', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'SE'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'se180021@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'se180022@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Dang Thi Tien', '0981000022', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'se180022@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'SE180022', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'SE'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'se180022@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'se180023@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Bui Thi Tu', '0981000023', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'se180023@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'SE180023', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'SE'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'se180023@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'se180024@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Do Thi Viet', '0981000024', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'se180024@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'SE180024', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'SE'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'se180024@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'se180025@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Ho Thi Yen', '0981000025', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'se180025@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'SE180025', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'SE'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'se180025@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'se180026@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Ngo Thi Cuong', '0981000026', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'se180026@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'SE180026', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'SE'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'se180026@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'se180027@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Duong Thi Duc', '0981000027', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'se180027@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'SE180027', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'SE'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'se180027@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'se180028@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Ly Thi Hieu', '0981000028', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'se180028@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'SE180028', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'SE'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'se180028@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'se180029@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Nguyen Hoang Hung', '0981000029', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'se180029@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'SE180029', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'SE'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'se180029@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'se180030@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Tran Hoang Lam', '0981000030', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'se180030@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'SE180030', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'SE'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'se180030@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ia180001@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Le Hoang Minh', '0982000001', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ia180001@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'IA180001', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'IA'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ia180001@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ia180002@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Pham Hoang Phong', '0982000002', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ia180002@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'IA180002', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'IA'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ia180002@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ia180003@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Hoang Hoang Quang', '0982000003', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ia180003@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'IA180003', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'IA'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ia180003@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ia180004@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Phan Hoang Thang', '0982000004', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ia180004@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'IA180004', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'IA'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ia180004@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ia180005@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Vu Hoang Thinh', '0982000005', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ia180005@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'IA180005', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'IA'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ia180005@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ia180006@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Dang Hoang Trung', '0982000006', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ia180006@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'IA180006', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'IA'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ia180006@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ia180007@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Bui Hoang Tung', '0982000007', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ia180007@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'IA180007', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'IA'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ia180007@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ia180008@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Do Hoang Vu', '0982000008', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ia180008@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'IA180008', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'IA'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ia180008@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ia180009@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Ho Hoang Binh', '0982000009', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ia180009@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'IA180009', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'IA'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ia180009@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ia180010@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Ngo Hoang Dat', '0982000010', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ia180010@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'IA180010', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'IA'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ia180010@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ia180011@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Duong Hoang Hai', '0982000011', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ia180011@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'IA180011', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'IA'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ia180011@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ia180012@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Ly Hoang Huy', '0982000012', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ia180012@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'IA180012', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'IA'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ia180012@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ia180013@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Nguyen Duc Khoa', '0982000013', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ia180013@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'IA180013', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'IA'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ia180013@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ia180014@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Tran Duc Long', '0982000014', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ia180014@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'IA180014', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'IA'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ia180014@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ia180015@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Le Duc Nghia', '0982000015', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ia180015@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'IA180015', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'IA'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ia180015@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ia180016@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Pham Duc Quan', '0982000016', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ia180016@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'IA180016', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'IA'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ia180016@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ia180017@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Hoang Duc Son', '0982000017', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ia180017@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'IA180017', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'IA'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ia180017@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ia180018@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Phan Duc Thao', '0982000018', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ia180018@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'IA180018', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'IA'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ia180018@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ia180019@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Vu Duc Toan', '0982000019', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ia180019@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'IA180019', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'IA'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ia180019@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ia180020@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Dang Duc Tuan', '0982000020', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ia180020@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'IA180020', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'IA'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ia180020@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ia180021@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Bui Duc Vinh', '0982000021', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ia180021@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'IA180021', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'IA'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ia180021@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ia180022@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Do Duc An', '0982000022', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ia180022@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'IA180022', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'IA'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ia180022@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ia180023@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Ho Duc Dung', '0982000023', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ia180023@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'IA180023', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'IA'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ia180023@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ia180024@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Ngo Duc Giang', '0982000024', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ia180024@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'IA180024', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'IA'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ia180024@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ia180025@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Duong Duc Hoang', '0982000025', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ia180025@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'IA180025', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'IA'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ia180025@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ia180026@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Ly Duc Khanh', '0982000026', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ia180026@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'IA180026', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'IA'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ia180026@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ia180027@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Nguyen Minh Linh', '0982000027', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ia180027@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'IA180027', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'IA'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ia180027@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ia180028@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Tran Minh Nam', '0982000028', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ia180028@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'IA180028', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'IA'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ia180028@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ia180029@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Le Minh Phuc', '0982000029', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ia180029@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'IA180029', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'IA'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ia180029@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ia180030@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Pham Minh Sang', '0982000030', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ia180030@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'IA180030', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'IA'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ia180030@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ai180001@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Hoang Minh Thanh', '0983000001', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ai180001@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'AI180001', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'AI'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ai180001@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ai180002@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Phan Minh Tien', '0983000002', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ai180002@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'AI180002', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'AI'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ai180002@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ai180003@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Vu Minh Tu', '0983000003', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ai180003@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'AI180003', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'AI'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ai180003@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ai180004@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Dang Minh Viet', '0983000004', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ai180004@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'AI180004', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'AI'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ai180004@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ai180005@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Bui Minh Yen', '0983000005', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ai180005@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'AI180005', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'AI'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ai180005@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ai180006@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Do Minh Cuong', '0983000006', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ai180006@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'AI180006', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'AI'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ai180006@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ai180007@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Ho Minh Duc', '0983000007', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ai180007@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'AI180007', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'AI'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ai180007@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ai180008@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Ngo Minh Hieu', '0983000008', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ai180008@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'AI180008', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'AI'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ai180008@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ai180009@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Duong Minh Hung', '0983000009', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ai180009@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'AI180009', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'AI'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ai180009@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ai180010@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Ly Minh Lam', '0983000010', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ai180010@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'AI180010', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'AI'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ai180010@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ai180011@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Nguyen Quang Minh', '0983000011', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ai180011@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'AI180011', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'AI'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ai180011@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ai180012@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Tran Quang Phong', '0983000012', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ai180012@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'AI180012', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'AI'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ai180012@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ai180013@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Le Quang Quang', '0983000013', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ai180013@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'AI180013', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'AI'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ai180013@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ai180014@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Pham Quang Thang', '0983000014', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ai180014@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'AI180014', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'AI'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ai180014@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ai180015@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Hoang Quang Thinh', '0983000015', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ai180015@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'AI180015', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'AI'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ai180015@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ai180016@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Phan Quang Trung', '0983000016', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ai180016@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'AI180016', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'AI'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ai180016@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ai180017@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Vu Quang Tung', '0983000017', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ai180017@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'AI180017', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'AI'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ai180017@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ai180018@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Dang Quang Vu', '0983000018', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ai180018@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'AI180018', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'AI'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ai180018@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ai180019@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Bui Quang Binh', '0983000019', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ai180019@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'AI180019', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'AI'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ai180019@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ai180020@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Do Quang Dat', '0983000020', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ai180020@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'AI180020', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'AI'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ai180020@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ai180021@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Ho Quang Hai', '0983000021', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ai180021@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'AI180021', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'AI'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ai180021@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ai180022@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Ngo Quang Huy', '0983000022', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ai180022@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'AI180022', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'AI'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ai180022@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ai180023@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Duong Quang Khoa', '0983000023', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ai180023@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'AI180023', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'AI'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ai180023@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ai180024@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Ly Quang Long', '0983000024', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ai180024@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'AI180024', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'AI'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ai180024@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ai180025@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Nguyen Thanh Nghia', '0983000025', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ai180025@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'AI180025', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'AI'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ai180025@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ai180026@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Tran Thanh Quan', '0983000026', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ai180026@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'AI180026', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'AI'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ai180026@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ai180027@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Le Thanh Son', '0983000027', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ai180027@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'AI180027', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'AI'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ai180027@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ai180028@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Pham Thanh Thao', '0983000028', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ai180028@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'AI180028', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'AI'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ai180028@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ai180029@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Hoang Thanh Toan', '0983000029', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ai180029@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'AI180029', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'AI'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ai180029@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

INSERT INTO `users` (`id`, `role_id`, `email`, `password_hash`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), 'ai180030@fpt.edu.vn', '$2a$10$7EqJtq98hPqEX7fNZaFWoO5y2hFjVn1QJ6V4WwJj/qp3ky3MqiWSLrwPE1Qp4Mzvdee', 'LOCAL', 'Phan Thanh Tuan', '0983000030', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM `users` WHERE `email` = 'ai180030@fpt.edu.vn');

INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`, `current_semester`, `academic_status`)
SELECT u.`id`, 'AI180030', m.`id`, cv.`id`, (SELECT `id` FROM `semesters` LIMIT 1), 5, 'ACTIVE'
FROM `users` u
JOIN `majors` m ON m.`code` = 'AI'
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = 'K18'
WHERE u.`email` = 'ai180030@fpt.edu.vn'
ON DUPLICATE KEY UPDATE `student_code` = VALUES(`student_code`), `major_id` = VALUES(`major_id`);

-- 6. Pre-assign first 20 students to 7 advisors (~2-3 advisees each), leaving 70 unassigned

INSERT INTO `student_advisor_assignments` (`id`, `student_id`, `advisor_id`, `group_name`, `assigned_date`, `is_primary`, `status`, `notes`)
SELECT UUID(), s.`user_id`, a.`user_id`, 'K18 - SE', CURRENT_DATE, true, 'ACTIVE', 'Pre-assigned initial cohort advisee'
FROM `students` s
JOIN `users` u ON u.`id` = s.`user_id` AND u.`email` = 'se180001@fpt.edu.vn'
JOIN `users` adv_u ON adv_u.`email` = 'adv.minhnv@fpt.edu.vn'
JOIN `advisors` a ON a.`user_id` = adv_u.`id`
WHERE NOT EXISTS (
  SELECT 1 FROM `student_advisor_assignments` saa WHERE saa.`student_id` = s.`user_id` AND saa.`status` = 'ACTIVE'
);

INSERT INTO `student_advisor_assignments` (`id`, `student_id`, `advisor_id`, `group_name`, `assigned_date`, `is_primary`, `status`, `notes`)
SELECT UUID(), s.`user_id`, a.`user_id`, 'K18 - SE', CURRENT_DATE, true, 'ACTIVE', 'Pre-assigned initial cohort advisee'
FROM `students` s
JOIN `users` u ON u.`id` = s.`user_id` AND u.`email` = 'se180002@fpt.edu.vn'
JOIN `users` adv_u ON adv_u.`email` = 'adv.minhnv@fpt.edu.vn'
JOIN `advisors` a ON a.`user_id` = adv_u.`id`
WHERE NOT EXISTS (
  SELECT 1 FROM `student_advisor_assignments` saa WHERE saa.`student_id` = s.`user_id` AND saa.`status` = 'ACTIVE'
);

INSERT INTO `student_advisor_assignments` (`id`, `student_id`, `advisor_id`, `group_name`, `assigned_date`, `is_primary`, `status`, `notes`)
SELECT UUID(), s.`user_id`, a.`user_id`, 'K18 - SE', CURRENT_DATE, true, 'ACTIVE', 'Pre-assigned initial cohort advisee'
FROM `students` s
JOIN `users` u ON u.`id` = s.`user_id` AND u.`email` = 'se180003@fpt.edu.vn'
JOIN `users` adv_u ON adv_u.`email` = 'adv.minhnv@fpt.edu.vn'
JOIN `advisors` a ON a.`user_id` = adv_u.`id`
WHERE NOT EXISTS (
  SELECT 1 FROM `student_advisor_assignments` saa WHERE saa.`student_id` = s.`user_id` AND saa.`status` = 'ACTIVE'
);

INSERT INTO `student_advisor_assignments` (`id`, `student_id`, `advisor_id`, `group_name`, `assigned_date`, `is_primary`, `status`, `notes`)
SELECT UUID(), s.`user_id`, a.`user_id`, 'K18 - SE', CURRENT_DATE, true, 'ACTIVE', 'Pre-assigned initial cohort advisee'
FROM `students` s
JOIN `users` u ON u.`id` = s.`user_id` AND u.`email` = 'se180004@fpt.edu.vn'
JOIN `users` adv_u ON adv_u.`email` = 'adv.maitt@fpt.edu.vn'
JOIN `advisors` a ON a.`user_id` = adv_u.`id`
WHERE NOT EXISTS (
  SELECT 1 FROM `student_advisor_assignments` saa WHERE saa.`student_id` = s.`user_id` AND saa.`status` = 'ACTIVE'
);

INSERT INTO `student_advisor_assignments` (`id`, `student_id`, `advisor_id`, `group_name`, `assigned_date`, `is_primary`, `status`, `notes`)
SELECT UUID(), s.`user_id`, a.`user_id`, 'K18 - SE', CURRENT_DATE, true, 'ACTIVE', 'Pre-assigned initial cohort advisee'
FROM `students` s
JOIN `users` u ON u.`id` = s.`user_id` AND u.`email` = 'se180005@fpt.edu.vn'
JOIN `users` adv_u ON adv_u.`email` = 'adv.maitt@fpt.edu.vn'
JOIN `advisors` a ON a.`user_id` = adv_u.`id`
WHERE NOT EXISTS (
  SELECT 1 FROM `student_advisor_assignments` saa WHERE saa.`student_id` = s.`user_id` AND saa.`status` = 'ACTIVE'
);

INSERT INTO `student_advisor_assignments` (`id`, `student_id`, `advisor_id`, `group_name`, `assigned_date`, `is_primary`, `status`, `notes`)
SELECT UUID(), s.`user_id`, a.`user_id`, 'K18 - SE', CURRENT_DATE, true, 'ACTIVE', 'Pre-assigned initial cohort advisee'
FROM `students` s
JOIN `users` u ON u.`id` = s.`user_id` AND u.`email` = 'se180006@fpt.edu.vn'
JOIN `users` adv_u ON adv_u.`email` = 'adv.maitt@fpt.edu.vn'
JOIN `advisors` a ON a.`user_id` = adv_u.`id`
WHERE NOT EXISTS (
  SELECT 1 FROM `student_advisor_assignments` saa WHERE saa.`student_id` = s.`user_id` AND saa.`status` = 'ACTIVE'
);

INSERT INTO `student_advisor_assignments` (`id`, `student_id`, `advisor_id`, `group_name`, `assigned_date`, `is_primary`, `status`, `notes`)
SELECT UUID(), s.`user_id`, a.`user_id`, 'K18 - IA', CURRENT_DATE, true, 'ACTIVE', 'Pre-assigned initial cohort advisee'
FROM `students` s
JOIN `users` u ON u.`id` = s.`user_id` AND u.`email` = 'se180007@fpt.edu.vn'
JOIN `users` adv_u ON adv_u.`email` = 'adv.longlh@fpt.edu.vn'
JOIN `advisors` a ON a.`user_id` = adv_u.`id`
WHERE NOT EXISTS (
  SELECT 1 FROM `student_advisor_assignments` saa WHERE saa.`student_id` = s.`user_id` AND saa.`status` = 'ACTIVE'
);

INSERT INTO `student_advisor_assignments` (`id`, `student_id`, `advisor_id`, `group_name`, `assigned_date`, `is_primary`, `status`, `notes`)
SELECT UUID(), s.`user_id`, a.`user_id`, 'K18 - IA', CURRENT_DATE, true, 'ACTIVE', 'Pre-assigned initial cohort advisee'
FROM `students` s
JOIN `users` u ON u.`id` = s.`user_id` AND u.`email` = 'se180008@fpt.edu.vn'
JOIN `users` adv_u ON adv_u.`email` = 'adv.longlh@fpt.edu.vn'
JOIN `advisors` a ON a.`user_id` = adv_u.`id`
WHERE NOT EXISTS (
  SELECT 1 FROM `student_advisor_assignments` saa WHERE saa.`student_id` = s.`user_id` AND saa.`status` = 'ACTIVE'
);

INSERT INTO `student_advisor_assignments` (`id`, `student_id`, `advisor_id`, `group_name`, `assigned_date`, `is_primary`, `status`, `notes`)
SELECT UUID(), s.`user_id`, a.`user_id`, 'K18 - IA', CURRENT_DATE, true, 'ACTIVE', 'Pre-assigned initial cohort advisee'
FROM `students` s
JOIN `users` u ON u.`id` = s.`user_id` AND u.`email` = 'se180009@fpt.edu.vn'
JOIN `users` adv_u ON adv_u.`email` = 'adv.longlh@fpt.edu.vn'
JOIN `advisors` a ON a.`user_id` = adv_u.`id`
WHERE NOT EXISTS (
  SELECT 1 FROM `student_advisor_assignments` saa WHERE saa.`student_id` = s.`user_id` AND saa.`status` = 'ACTIVE'
);

INSERT INTO `student_advisor_assignments` (`id`, `student_id`, `advisor_id`, `group_name`, `assigned_date`, `is_primary`, `status`, `notes`)
SELECT UUID(), s.`user_id`, a.`user_id`, 'K18 - IA', CURRENT_DATE, true, 'ACTIVE', 'Pre-assigned initial cohort advisee'
FROM `students` s
JOIN `users` u ON u.`id` = s.`user_id` AND u.`email` = 'se180010@fpt.edu.vn'
JOIN `users` adv_u ON adv_u.`email` = 'adv.thangpd@fpt.edu.vn'
JOIN `advisors` a ON a.`user_id` = adv_u.`id`
WHERE NOT EXISTS (
  SELECT 1 FROM `student_advisor_assignments` saa WHERE saa.`student_id` = s.`user_id` AND saa.`status` = 'ACTIVE'
);

INSERT INTO `student_advisor_assignments` (`id`, `student_id`, `advisor_id`, `group_name`, `assigned_date`, `is_primary`, `status`, `notes`)
SELECT UUID(), s.`user_id`, a.`user_id`, 'K18 - IA', CURRENT_DATE, true, 'ACTIVE', 'Pre-assigned initial cohort advisee'
FROM `students` s
JOIN `users` u ON u.`id` = s.`user_id` AND u.`email` = 'se180011@fpt.edu.vn'
JOIN `users` adv_u ON adv_u.`email` = 'adv.thangpd@fpt.edu.vn'
JOIN `advisors` a ON a.`user_id` = adv_u.`id`
WHERE NOT EXISTS (
  SELECT 1 FROM `student_advisor_assignments` saa WHERE saa.`student_id` = s.`user_id` AND saa.`status` = 'ACTIVE'
);

INSERT INTO `student_advisor_assignments` (`id`, `student_id`, `advisor_id`, `group_name`, `assigned_date`, `is_primary`, `status`, `notes`)
SELECT UUID(), s.`user_id`, a.`user_id`, 'K18 - IA', CURRENT_DATE, true, 'ACTIVE', 'Pre-assigned initial cohort advisee'
FROM `students` s
JOIN `users` u ON u.`id` = s.`user_id` AND u.`email` = 'se180012@fpt.edu.vn'
JOIN `users` adv_u ON adv_u.`email` = 'adv.thangpd@fpt.edu.vn'
JOIN `advisors` a ON a.`user_id` = adv_u.`id`
WHERE NOT EXISTS (
  SELECT 1 FROM `student_advisor_assignments` saa WHERE saa.`student_id` = s.`user_id` AND saa.`status` = 'ACTIVE'
);

INSERT INTO `student_advisor_assignments` (`id`, `student_id`, `advisor_id`, `group_name`, `assigned_date`, `is_primary`, `status`, `notes`)
SELECT UUID(), s.`user_id`, a.`user_id`, 'K18 - AI', CURRENT_DATE, true, 'ACTIVE', 'Pre-assigned initial cohort advisee'
FROM `students` s
JOIN `users` u ON u.`id` = s.`user_id` AND u.`email` = 'se180013@fpt.edu.vn'
JOIN `users` adv_u ON adv_u.`email` = 'adv.lanvt@fpt.edu.vn'
JOIN `advisors` a ON a.`user_id` = adv_u.`id`
WHERE NOT EXISTS (
  SELECT 1 FROM `student_advisor_assignments` saa WHERE saa.`student_id` = s.`user_id` AND saa.`status` = 'ACTIVE'
);

INSERT INTO `student_advisor_assignments` (`id`, `student_id`, `advisor_id`, `group_name`, `assigned_date`, `is_primary`, `status`, `notes`)
SELECT UUID(), s.`user_id`, a.`user_id`, 'K18 - AI', CURRENT_DATE, true, 'ACTIVE', 'Pre-assigned initial cohort advisee'
FROM `students` s
JOIN `users` u ON u.`id` = s.`user_id` AND u.`email` = 'se180014@fpt.edu.vn'
JOIN `users` adv_u ON adv_u.`email` = 'adv.lanvt@fpt.edu.vn'
JOIN `advisors` a ON a.`user_id` = adv_u.`id`
WHERE NOT EXISTS (
  SELECT 1 FROM `student_advisor_assignments` saa WHERE saa.`student_id` = s.`user_id` AND saa.`status` = 'ACTIVE'
);

INSERT INTO `student_advisor_assignments` (`id`, `student_id`, `advisor_id`, `group_name`, `assigned_date`, `is_primary`, `status`, `notes`)
SELECT UUID(), s.`user_id`, a.`user_id`, 'K18 - AI', CURRENT_DATE, true, 'ACTIVE', 'Pre-assigned initial cohort advisee'
FROM `students` s
JOIN `users` u ON u.`id` = s.`user_id` AND u.`email` = 'se180015@fpt.edu.vn'
JOIN `users` adv_u ON adv_u.`email` = 'adv.lanvt@fpt.edu.vn'
JOIN `advisors` a ON a.`user_id` = adv_u.`id`
WHERE NOT EXISTS (
  SELECT 1 FROM `student_advisor_assignments` saa WHERE saa.`student_id` = s.`user_id` AND saa.`status` = 'ACTIVE'
);

INSERT INTO `student_advisor_assignments` (`id`, `student_id`, `advisor_id`, `group_name`, `assigned_date`, `is_primary`, `status`, `notes`)
SELECT UUID(), s.`user_id`, a.`user_id`, 'K18 - AI', CURRENT_DATE, true, 'ACTIVE', 'Pre-assigned initial cohort advisee'
FROM `students` s
JOIN `users` u ON u.`id` = s.`user_id` AND u.`email` = 'se180016@fpt.edu.vn'
JOIN `users` adv_u ON adv_u.`email` = 'adv.tuandm@fpt.edu.vn'
JOIN `advisors` a ON a.`user_id` = adv_u.`id`
WHERE NOT EXISTS (
  SELECT 1 FROM `student_advisor_assignments` saa WHERE saa.`student_id` = s.`user_id` AND saa.`status` = 'ACTIVE'
);

INSERT INTO `student_advisor_assignments` (`id`, `student_id`, `advisor_id`, `group_name`, `assigned_date`, `is_primary`, `status`, `notes`)
SELECT UUID(), s.`user_id`, a.`user_id`, 'K18 - AI', CURRENT_DATE, true, 'ACTIVE', 'Pre-assigned initial cohort advisee'
FROM `students` s
JOIN `users` u ON u.`id` = s.`user_id` AND u.`email` = 'se180017@fpt.edu.vn'
JOIN `users` adv_u ON adv_u.`email` = 'adv.tuandm@fpt.edu.vn'
JOIN `advisors` a ON a.`user_id` = adv_u.`id`
WHERE NOT EXISTS (
  SELECT 1 FROM `student_advisor_assignments` saa WHERE saa.`student_id` = s.`user_id` AND saa.`status` = 'ACTIVE'
);

INSERT INTO `student_advisor_assignments` (`id`, `student_id`, `advisor_id`, `group_name`, `assigned_date`, `is_primary`, `status`, `notes`)
SELECT UUID(), s.`user_id`, a.`user_id`, 'K18 - AI', CURRENT_DATE, true, 'ACTIVE', 'Pre-assigned initial cohort advisee'
FROM `students` s
JOIN `users` u ON u.`id` = s.`user_id` AND u.`email` = 'se180018@fpt.edu.vn'
JOIN `users` adv_u ON adv_u.`email` = 'adv.tuandm@fpt.edu.vn'
JOIN `advisors` a ON a.`user_id` = adv_u.`id`
WHERE NOT EXISTS (
  SELECT 1 FROM `student_advisor_assignments` saa WHERE saa.`student_id` = s.`user_id` AND saa.`status` = 'ACTIVE'
);

INSERT INTO `student_advisor_assignments` (`id`, `student_id`, `advisor_id`, `group_name`, `assigned_date`, `is_primary`, `status`, `notes`)
SELECT UUID(), s.`user_id`, a.`user_id`, 'K18 - SE', CURRENT_DATE, true, 'ACTIVE', 'Pre-assigned initial cohort advisee'
FROM `students` s
JOIN `users` u ON u.`id` = s.`user_id` AND u.`email` = 'se180019@fpt.edu.vn'
JOIN `users` adv_u ON adv_u.`email` = 'adv.khanhbq@fpt.edu.vn'
JOIN `advisors` a ON a.`user_id` = adv_u.`id`
WHERE NOT EXISTS (
  SELECT 1 FROM `student_advisor_assignments` saa WHERE saa.`student_id` = s.`user_id` AND saa.`status` = 'ACTIVE'
);

INSERT INTO `student_advisor_assignments` (`id`, `student_id`, `advisor_id`, `group_name`, `assigned_date`, `is_primary`, `status`, `notes`)
SELECT UUID(), s.`user_id`, a.`user_id`, 'K18 - SE', CURRENT_DATE, true, 'ACTIVE', 'Pre-assigned initial cohort advisee'
FROM `students` s
JOIN `users` u ON u.`id` = s.`user_id` AND u.`email` = 'se180020@fpt.edu.vn'
JOIN `users` adv_u ON adv_u.`email` = 'adv.khanhbq@fpt.edu.vn'
JOIN `advisors` a ON a.`user_id` = adv_u.`id`
WHERE NOT EXISTS (
  SELECT 1 FROM `student_advisor_assignments` saa WHERE saa.`student_id` = s.`user_id` AND saa.`status` = 'ACTIVE'
);

-- 6b. Ensure Advisors are registered in Cohort Pools
INSERT IGNORE INTO `cohort_advisors` (`cohort_id`, `advisor_id`, `is_active`)
SELECT cv.`id`, a.`user_id`, 1
FROM `curriculum_versions` cv
CROSS JOIN `advisors` a;

-- 7. Verification Summary Query
SELECT r.`code` AS role, COUNT(*) AS total_count
FROM `users` u
JOIN `roles` r ON r.`id` = u.`role_id`
WHERE u.`email` LIKE '%@fpt.edu.vn'
GROUP BY r.`code`;
