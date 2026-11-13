-- =============================================================================
-- FAAPS seed: 63 mock students to test pagination, filters and bulk actions
-- Date: 2026-11-13
--
-- * Majors SE, AI, IA x cohorts K17, K18, K19, 7 students each (Student IDs XX{17|18|19}9101-9107).
-- * Every 9th student is SUSPENDED so the Status filter has something to show.
-- * Names use Vietnamese diacritics. They are written as UTF-8 hex literals, so the result is correct whatever
--   character set the MySQL client uses. Still prefer: mysql --default-character-set=utf8mb4 faaps < this file
-- * Safe to run twice: rows are skipped when the email already exists.
-- * Mock data only. Do not run on a shared or production database.
-- =============================================================================
USE `faaps`;
SET NAMES utf8mb4;

-- 1. Majors and K17/K18/K19 curriculum versions the students belong to.
INSERT INTO `majors` (`id`, `code`, `name`, `description`, `total_credits`, `duration_years`, `status`)
SELECT UUID(), v.code, v.name, CONCAT(v.name, ' program'), 140, 4.0, 'ACTIVE'
FROM (SELECT 'SE' AS code, 'Software Engineering' AS name
      UNION ALL SELECT 'AI', 'Artificial Intelligence'
      UNION ALL SELECT 'IA', 'Information Assurance') v
WHERE NOT EXISTS (SELECT 1 FROM `majors` m WHERE m.`code` = v.code);

INSERT INTO `curriculum_versions` (`id`, `major_id`, `version`, `name`, `effective_from_semester_id`, `is_current`, `status`)
SELECT UUID(), m.`id`, c.version, CONCAT(m.`name`, ' - Cohort ', c.version),
       (SELECT s.`id` FROM `semesters` s ORDER BY s.`start_date` LIMIT 1), false, 'ACTIVE'
FROM `majors` m
JOIN (SELECT 'K17' AS version UNION ALL SELECT 'K18' UNION ALL SELECT 'K19') c
WHERE m.`code` IN ('SE', 'AI', 'IA')
  AND NOT EXISTS (SELECT 1 FROM `curriculum_versions` cv WHERE cv.`major_id` = m.`id` AND cv.`version` = c.version);

-- 2. The students to add, once, in a temporary table.
DROP TEMPORARY TABLE IF EXISTS `tmp_mock_students`;
CREATE TEMPORARY TABLE `tmp_mock_students` (
  `email` varchar(255) NOT NULL,
  `full_name` varchar(255) NOT NULL,
  `phone` varchar(20) NOT NULL,
  `status` varchar(20) NOT NULL,
  `student_code` varchar(50) NOT NULL,
  `major_code` varchar(50) NOT NULL,
  `cohort` varchar(50) NOT NULL
);  -- no charset clause: the columns take the database default, like the real tables, so comparisons match

INSERT INTO `tmp_mock_students` (`email`, `full_name`, `phone`, `status`, `student_code`, `major_code`, `cohort`) VALUES
  ('huynh.quoc.huy.se179101@fpt.edu.vn', _utf8mb4 X'4875E1BBB36E68205175E1BB916320487579', '0911234567', 'ACTIVE', 'SE179101', 'SE', 'K17'),  -- Huỳnh Quốc Huy
  ('bui.ngoc.phuc.se179102@fpt.edu.vn', _utf8mb4 X'42C3B969204E67E1BB8D63205068C3BA63', '0912469134', 'ACTIVE', 'SE179102', 'SE', 'K17'),  -- Bùi Ngọc Phúc
  ('ly.gia.yen.se179103@fpt.edu.vn', _utf8mb4 X'4CC3BD204769612059E1BABF6E', '0913703701', 'ACTIVE', 'SE179103', 'SE', 'K17'),  -- Lý Gia Yến
  ('hoang.thuy.an.se179104@fpt.edu.vn', _utf8mb4 X'486FC3A06E67205468C3B97920416E', '0914938268', 'ACTIVE', 'SE179104', 'SE', 'K17'),  -- Hoàng Thùy An
  ('dang.thi.huy.se179105@fpt.edu.vn', _utf8mb4 X'C490E1BAB76E67205468E1BB8B20487579', '0916172835', 'ACTIVE', 'SE179105', 'SE', 'K17'),  -- Đặng Thị Huy
  ('duong.thanh.phuc.se179106@fpt.edu.vn', _utf8mb4 X'44C6B0C6A16E67205468616E68205068C3BA63', '0917407402', 'ACTIVE', 'SE179106', 'SE', 'K17'),  -- Dương Thanh Phúc
  ('pham.duc.yen.se179107@fpt.edu.vn', _utf8mb4 X'5068E1BAA16D20C490E1BBA9632059E1BABF6E', '0918641969', 'ACTIVE', 'SE179107', 'SE', 'K17'),  -- Phạm Đức Yến
  ('vo.hoang.an.se189101@fpt.edu.vn', _utf8mb4 X'56C3B520486FC3A06E6720416E', '0919876536', 'ACTIVE', 'SE189101', 'SE', 'K18'),  -- Võ Hoàng An
  ('ngo.xuan.huy.se189102@fpt.edu.vn', _utf8mb4 X'4E67C3B4205875C3A26E20487579', '0921111103', 'SUSPENDED', 'SE189102', 'SE', 'K18'),  -- Ngô Xuân Huy
  ('le.minh.phuc.se189103@fpt.edu.vn', _utf8mb4 X'4CC3AA204D696E68205068C3BA63', '0922345670', 'ACTIVE', 'SE189103', 'SE', 'K18'),  -- Lê Minh Phúc
  ('vu.huu.yen.se189104@fpt.edu.vn', _utf8mb4 X'56C5A92048E1BBAF752059E1BABF6E', '0923580237', 'ACTIVE', 'SE189104', 'SE', 'K18'),  -- Vũ Hữu Yến
  ('ho.anh.an.se189105@fpt.edu.vn', _utf8mb4 X'48E1BB9320416E6820416E', '0924814804', 'ACTIVE', 'SE189105', 'SE', 'K18'),  -- Hồ Anh An
  ('tran.tuan.huy.se189106@fpt.edu.vn', _utf8mb4 X'5472E1BAA76E205475E1BAA56E20487579', '0926049371', 'ACTIVE', 'SE189106', 'SE', 'K18'),  -- Trần Tuấn Huy
  ('phan.van.phuc.se189107@fpt.edu.vn', _utf8mb4 X'5068616E2056C4836E205068C3BA63', '0927283938', 'ACTIVE', 'SE189107', 'SE', 'K18'),  -- Phan Văn Phúc
  ('do.quoc.yen.se199101@fpt.edu.vn', _utf8mb4 X'C490E1BB97205175E1BB91632059E1BABF6E', '0928518505', 'ACTIVE', 'SE199101', 'SE', 'K19'),  -- Đỗ Quốc Yến
  ('nguyen.ngoc.an.se199102@fpt.edu.vn', _utf8mb4 X'4E677579E1BB856E204E67E1BB8D6320416E', '0929753072', 'ACTIVE', 'SE199102', 'SE', 'K19'),  -- Nguyễn Ngọc An
  ('huynh.gia.huy.se199103@fpt.edu.vn', _utf8mb4 X'4875E1BBB36E682047696120487579', '0930987639', 'ACTIVE', 'SE199103', 'SE', 'K19'),  -- Huỳnh Gia Huy
  ('bui.thuy.phuc.se199104@fpt.edu.vn', _utf8mb4 X'42C3B969205468C3B979205068C3BA63', '0932222206', 'SUSPENDED', 'SE199104', 'SE', 'K19'),  -- Bùi Thùy Phúc
  ('ly.thi.yen.se199105@fpt.edu.vn', _utf8mb4 X'4CC3BD205468E1BB8B2059E1BABF6E', '0933456773', 'ACTIVE', 'SE199105', 'SE', 'K19'),  -- Lý Thị Yến
  ('hoang.thanh.an.se199106@fpt.edu.vn', _utf8mb4 X'486FC3A06E67205468616E6820416E', '0934691340', 'ACTIVE', 'SE199106', 'SE', 'K19'),  -- Hoàng Thanh An
  ('dang.duc.huy.se199107@fpt.edu.vn', _utf8mb4 X'C490E1BAB76E6720C490E1BBA96320487579', '0935925907', 'ACTIVE', 'SE199107', 'SE', 'K19'),  -- Đặng Đức Huy
  ('duong.hoang.phuc.ai179101@fpt.edu.vn', _utf8mb4 X'44C6B0C6A16E6720486FC3A06E67205068C3BA63', '0937160474', 'ACTIVE', 'AI179101', 'AI', 'K17'),  -- Dương Hoàng Phúc
  ('pham.xuan.yen.ai179102@fpt.edu.vn', _utf8mb4 X'5068E1BAA16D205875C3A26E2059E1BABF6E', '0938395041', 'ACTIVE', 'AI179102', 'AI', 'K17'),  -- Phạm Xuân Yến
  ('vo.minh.an.ai179103@fpt.edu.vn', _utf8mb4 X'56C3B5204D696E6820416E', '0939629608', 'ACTIVE', 'AI179103', 'AI', 'K17'),  -- Võ Minh An
  ('ngo.huu.huy.ai179104@fpt.edu.vn', _utf8mb4 X'4E67C3B42048E1BBAF7520487579', '0940864175', 'ACTIVE', 'AI179104', 'AI', 'K17'),  -- Ngô Hữu Huy
  ('le.anh.phuc.ai179105@fpt.edu.vn', _utf8mb4 X'4CC3AA20416E68205068C3BA63', '0942098742', 'ACTIVE', 'AI179105', 'AI', 'K17'),  -- Lê Anh Phúc
  ('vu.tuan.yen.ai179106@fpt.edu.vn', _utf8mb4 X'56C5A9205475E1BAA56E2059E1BABF6E', '0943333309', 'SUSPENDED', 'AI179106', 'AI', 'K17'),  -- Vũ Tuấn Yến
  ('ho.van.an.ai179107@fpt.edu.vn', _utf8mb4 X'48E1BB932056C4836E20416E', '0944567876', 'ACTIVE', 'AI179107', 'AI', 'K17'),  -- Hồ Văn An
  ('tran.quoc.huy.ai189101@fpt.edu.vn', _utf8mb4 X'5472E1BAA76E205175E1BB916320487579', '0945802443', 'ACTIVE', 'AI189101', 'AI', 'K18'),  -- Trần Quốc Huy
  ('phan.ngoc.phuc.ai189102@fpt.edu.vn', _utf8mb4 X'5068616E204E67E1BB8D63205068C3BA63', '0947037010', 'ACTIVE', 'AI189102', 'AI', 'K18'),  -- Phan Ngọc Phúc
  ('do.gia.yen.ai189103@fpt.edu.vn', _utf8mb4 X'C490E1BB97204769612059E1BABF6E', '0948271577', 'ACTIVE', 'AI189103', 'AI', 'K18'),  -- Đỗ Gia Yến
  ('nguyen.thuy.an.ai189104@fpt.edu.vn', _utf8mb4 X'4E677579E1BB856E205468C3B97920416E', '0949506144', 'ACTIVE', 'AI189104', 'AI', 'K18'),  -- Nguyễn Thùy An
  ('huynh.thi.huy.ai189105@fpt.edu.vn', _utf8mb4 X'4875E1BBB36E68205468E1BB8B20487579', '0950740711', 'ACTIVE', 'AI189105', 'AI', 'K18'),  -- Huỳnh Thị Huy
  ('bui.thanh.phuc.ai189106@fpt.edu.vn', _utf8mb4 X'42C3B969205468616E68205068C3BA63', '0951975278', 'ACTIVE', 'AI189106', 'AI', 'K18'),  -- Bùi Thanh Phúc
  ('ly.duc.yen.ai189107@fpt.edu.vn', _utf8mb4 X'4CC3BD20C490E1BBA9632059E1BABF6E', '0953209845', 'ACTIVE', 'AI189107', 'AI', 'K18'),  -- Lý Đức Yến
  ('hoang.hoang.an.ai199101@fpt.edu.vn', _utf8mb4 X'486FC3A06E6720486FC3A06E6720416E', '0954444412', 'SUSPENDED', 'AI199101', 'AI', 'K19'),  -- Hoàng Hoàng An
  ('dang.xuan.huy.ai199102@fpt.edu.vn', _utf8mb4 X'C490E1BAB76E67205875C3A26E20487579', '0955678979', 'ACTIVE', 'AI199102', 'AI', 'K19'),  -- Đặng Xuân Huy
  ('duong.minh.phuc.ai199103@fpt.edu.vn', _utf8mb4 X'44C6B0C6A16E67204D696E68205068C3BA63', '0956913546', 'ACTIVE', 'AI199103', 'AI', 'K19'),  -- Dương Minh Phúc
  ('pham.huu.yen.ai199104@fpt.edu.vn', _utf8mb4 X'5068E1BAA16D2048E1BBAF752059E1BABF6E', '0958148113', 'ACTIVE', 'AI199104', 'AI', 'K19'),  -- Phạm Hữu Yến
  ('vo.anh.an.ai199105@fpt.edu.vn', _utf8mb4 X'56C3B520416E6820416E', '0959382680', 'ACTIVE', 'AI199105', 'AI', 'K19'),  -- Võ Anh An
  ('ngo.tuan.huy.ai199106@fpt.edu.vn', _utf8mb4 X'4E67C3B4205475E1BAA56E20487579', '0960617247', 'ACTIVE', 'AI199106', 'AI', 'K19'),  -- Ngô Tuấn Huy
  ('le.van.phuc.ai199107@fpt.edu.vn', _utf8mb4 X'4CC3AA2056C4836E205068C3BA63', '0961851814', 'ACTIVE', 'AI199107', 'AI', 'K19'),  -- Lê Văn Phúc
  ('vu.quoc.yen.ia179101@fpt.edu.vn', _utf8mb4 X'56C5A9205175E1BB91632059E1BABF6E', '0963086381', 'ACTIVE', 'IA179101', 'IA', 'K17'),  -- Vũ Quốc Yến
  ('ho.ngoc.an.ia179102@fpt.edu.vn', _utf8mb4 X'48E1BB93204E67E1BB8D6320416E', '0964320948', 'ACTIVE', 'IA179102', 'IA', 'K17'),  -- Hồ Ngọc An
  ('tran.gia.huy.ia179103@fpt.edu.vn', _utf8mb4 X'5472E1BAA76E2047696120487579', '0965555515', 'SUSPENDED', 'IA179103', 'IA', 'K17'),  -- Trần Gia Huy
  ('phan.thuy.phuc.ia179104@fpt.edu.vn', _utf8mb4 X'5068616E205468C3B979205068C3BA63', '0966790082', 'ACTIVE', 'IA179104', 'IA', 'K17'),  -- Phan Thùy Phúc
  ('do.thi.yen.ia179105@fpt.edu.vn', _utf8mb4 X'C490E1BB97205468E1BB8B2059E1BABF6E', '0968024649', 'ACTIVE', 'IA179105', 'IA', 'K17'),  -- Đỗ Thị Yến
  ('nguyen.thanh.an.ia179106@fpt.edu.vn', _utf8mb4 X'4E677579E1BB856E205468616E6820416E', '0969259216', 'ACTIVE', 'IA179106', 'IA', 'K17'),  -- Nguyễn Thanh An
  ('huynh.duc.huy.ia179107@fpt.edu.vn', _utf8mb4 X'4875E1BBB36E6820C490E1BBA96320487579', '0970493783', 'ACTIVE', 'IA179107', 'IA', 'K17'),  -- Huỳnh Đức Huy
  ('bui.hoang.phuc.ia189101@fpt.edu.vn', _utf8mb4 X'42C3B96920486FC3A06E67205068C3BA63', '0971728350', 'ACTIVE', 'IA189101', 'IA', 'K18'),  -- Bùi Hoàng Phúc
  ('ly.xuan.yen.ia189102@fpt.edu.vn', _utf8mb4 X'4CC3BD205875C3A26E2059E1BABF6E', '0972962917', 'ACTIVE', 'IA189102', 'IA', 'K18'),  -- Lý Xuân Yến
  ('hoang.minh.an.ia189103@fpt.edu.vn', _utf8mb4 X'486FC3A06E67204D696E6820416E', '0974197484', 'ACTIVE', 'IA189103', 'IA', 'K18'),  -- Hoàng Minh An
  ('dang.huu.huy.ia189104@fpt.edu.vn', _utf8mb4 X'C490E1BAB76E672048E1BBAF7520487579', '0975432051', 'ACTIVE', 'IA189104', 'IA', 'K18'),  -- Đặng Hữu Huy
  ('duong.anh.phuc.ia189105@fpt.edu.vn', _utf8mb4 X'44C6B0C6A16E6720416E68205068C3BA63', '0976666618', 'SUSPENDED', 'IA189105', 'IA', 'K18'),  -- Dương Anh Phúc
  ('pham.tuan.yen.ia189106@fpt.edu.vn', _utf8mb4 X'5068E1BAA16D205475E1BAA56E2059E1BABF6E', '0977901185', 'ACTIVE', 'IA189106', 'IA', 'K18'),  -- Phạm Tuấn Yến
  ('vo.van.an.ia189107@fpt.edu.vn', _utf8mb4 X'56C3B52056C4836E20416E', '0979135752', 'ACTIVE', 'IA189107', 'IA', 'K18'),  -- Võ Văn An
  ('ngo.quoc.huy.ia199101@fpt.edu.vn', _utf8mb4 X'4E67C3B4205175E1BB916320487579', '0980370319', 'ACTIVE', 'IA199101', 'IA', 'K19'),  -- Ngô Quốc Huy
  ('le.ngoc.phuc.ia199102@fpt.edu.vn', _utf8mb4 X'4CC3AA204E67E1BB8D63205068C3BA63', '0981604886', 'ACTIVE', 'IA199102', 'IA', 'K19'),  -- Lê Ngọc Phúc
  ('vu.gia.yen.ia199103@fpt.edu.vn', _utf8mb4 X'56C5A9204769612059E1BABF6E', '0982839453', 'ACTIVE', 'IA199103', 'IA', 'K19'),  -- Vũ Gia Yến
  ('ho.thuy.an.ia199104@fpt.edu.vn', _utf8mb4 X'48E1BB93205468C3B97920416E', '0984074020', 'ACTIVE', 'IA199104', 'IA', 'K19'),  -- Hồ Thùy An
  ('tran.thi.huy.ia199105@fpt.edu.vn', _utf8mb4 X'5472E1BAA76E205468E1BB8B20487579', '0985308587', 'ACTIVE', 'IA199105', 'IA', 'K19'),  -- Trần Thị Huy
  ('phan.thanh.phuc.ia199106@fpt.edu.vn', _utf8mb4 X'5068616E205468616E68205068C3BA63', '0986543154', 'ACTIVE', 'IA199106', 'IA', 'K19'),  -- Phan Thanh Phúc
  ('do.duc.yen.ia199107@fpt.edu.vn', _utf8mb4 X'C490E1BB9720C490E1BBA9632059E1BABF6E', '0987777721', 'SUSPENDED', 'IA199107', 'IA', 'K19');  -- Đỗ Đức Yến

-- 3. Accounts (they sign in with Google, so no password is stored).
INSERT INTO `users` (`id`, `role_id`, `email`, `auth_provider`, `full_name`, `phone`, `status`)
SELECT UUID(), (SELECT `id` FROM `roles` WHERE `code` = 'STUDENT'), t.`email`, 'GOOGLE', t.`full_name`, t.`phone`, t.`status`
FROM `tmp_mock_students` t
WHERE NOT EXISTS (SELECT 1 FROM `users` u WHERE u.`email` = t.`email`);

-- 4. Academic profiles.
INSERT INTO `students` (`user_id`, `student_code`, `major_id`, `curriculum_version_id`, `enrollment_semester_id`,
                        `current_semester`, `current_gpa`, `total_credits_earned`, `academic_status`)
SELECT u.`id`, t.`student_code`, m.`id`, cv.`id`, cv.`effective_from_semester_id`, 1, 0, 0, 'ACTIVE'
FROM `tmp_mock_students` t
JOIN `users` u ON u.`email` = t.`email`
JOIN `majors` m ON m.`code` = t.`major_code`
JOIN `curriculum_versions` cv ON cv.`major_id` = m.`id` AND cv.`version` = t.`cohort`
WHERE NOT EXISTS (SELECT 1 FROM `students` s WHERE s.`user_id` = u.`id`)
  AND NOT EXISTS (SELECT 1 FROM `students` s2 WHERE s2.`student_code` = t.`student_code`);

DROP TEMPORARY TABLE `tmp_mock_students`;
