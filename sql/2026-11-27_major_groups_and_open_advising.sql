-- ==============================================================================
-- FAAPS Database Migration: Major Groups (Khối ngành / Chuyên ngành lớn)
-- Idempotent for MySQL 8.4 (local & AWS RDS)
-- ==============================================================================

USE `faaps`;

-- 1. Create major_groups table
CREATE TABLE IF NOT EXISTS `major_groups` (
  `id` char(36) PRIMARY KEY DEFAULT (UUID()),
  `code` varchar(50) UNIQUE NOT NULL COMMENT 'FLM prefix: BIT, BBA, BCT, BEN, BJP, BKR, BCH, BCS, LLB',
  `name` varchar(255) NOT NULL,
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP),
  `updated_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

-- 2. Seed official FPTU / FLM Major Groups (idempotent)
INSERT INTO `major_groups` (`id`, `code`, `name`)
SELECT UUID(), 'BIT', 'Information Technology'
WHERE NOT EXISTS (SELECT 1 FROM `major_groups` WHERE `code` = 'BIT');

INSERT INTO `major_groups` (`id`, `code`, `name`)
SELECT UUID(), 'BBA', 'Business Administration'
WHERE NOT EXISTS (SELECT 1 FROM `major_groups` WHERE `code` = 'BBA');

INSERT INTO `major_groups` (`id`, `code`, `name`)
SELECT UUID(), 'BCT', 'Communication Technology'
WHERE NOT EXISTS (SELECT 1 FROM `major_groups` WHERE `code` = 'BCT');

INSERT INTO `major_groups` (`id`, `code`, `name`)
SELECT UUID(), 'BEN', 'English Language'
WHERE NOT EXISTS (SELECT 1 FROM `major_groups` WHERE `code` = 'BEN');

INSERT INTO `major_groups` (`id`, `code`, `name`)
SELECT UUID(), 'BJP', 'Japanese Language'
WHERE NOT EXISTS (SELECT 1 FROM `major_groups` WHERE `code` = 'BJP');

INSERT INTO `major_groups` (`id`, `code`, `name`)
SELECT UUID(), 'BKR', 'Korean Language'
WHERE NOT EXISTS (SELECT 1 FROM `major_groups` WHERE `code` = 'BKR');

INSERT INTO `major_groups` (`id`, `code`, `name`)
SELECT UUID(), 'BCH', 'Chinese Language'
WHERE NOT EXISTS (SELECT 1 FROM `major_groups` WHERE `code` = 'BCH');

INSERT INTO `major_groups` (`id`, `code`, `name`)
SELECT UUID(), 'BCS', 'Computer Science'
WHERE NOT EXISTS (SELECT 1 FROM `major_groups` WHERE `code` = 'BCS');

INSERT INTO `major_groups` (`id`, `code`, `name`)
SELECT UUID(), 'LLB', 'Law'
WHERE NOT EXISTS (SELECT 1 FROM `major_groups` WHERE `code` = 'LLB');

-- 3. Add majors.major_group_id (if missing)
SET @has_majors_group := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'majors' AND COLUMN_NAME = 'major_group_id'
);
SET @sql_majors_group := IF(
  @has_majors_group = 0,
  'ALTER TABLE `majors` ADD COLUMN `major_group_id` char(36) NULL AFTER `name`, ADD INDEX `idx_majors_major_group` (`major_group_id`), ADD CONSTRAINT `fk_majors_major_group` FOREIGN KEY (`major_group_id`) REFERENCES `major_groups` (`id`)',
  'SELECT 1'
);
PREPARE stmt FROM @sql_majors_group;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 4. Add advisors.major_group_id (if missing)
SET @has_advisors_group := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'advisors' AND COLUMN_NAME = 'major_group_id'
);
SET @sql_advisors_group := IF(
  @has_advisors_group = 0,
  'ALTER TABLE `advisors` ADD COLUMN `major_group_id` char(36) NULL AFTER `advisor_code`, ADD INDEX `idx_advisors_major_group` (`major_group_id`), ADD CONSTRAINT `fk_advisors_major_group` FOREIGN KEY (`major_group_id`) REFERENCES `major_groups` (`id`)',
  'SELECT 1'
);
PREPARE stmt FROM @sql_advisors_group;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 5. Backfill existing majors -> major_groups
UPDATE `majors`
SET `major_group_id` = (SELECT `id` FROM `major_groups` WHERE `code` = 'BIT' LIMIT 1)
WHERE `major_group_id` IS NULL
  AND UPPER(`code`) IN ('SE', 'AI', 'IA', 'IS', 'GD', 'IC', 'DS', 'DX', 'IOT', 'IT', 'AS', 'RA');

UPDATE `majors`
SET `major_group_id` = (SELECT `id` FROM `major_groups` WHERE `code` = 'BBA' LIMIT 1)
WHERE `major_group_id` IS NULL
  AND UPPER(`code`) IN ('IB', 'MKT', 'DM', 'HM', 'TM', 'FIN', 'EC', 'BA', 'LOG', 'SCM', 'EM', 'BF');

UPDATE `majors`
SET `major_group_id` = (SELECT `id` FROM `major_groups` WHERE `code` = 'BCT' LIMIT 1)
WHERE `major_group_id` IS NULL
  AND UPPER(`code`) IN ('MC', 'PR', 'IMC', 'BC');

UPDATE `majors`
SET `major_group_id` = (SELECT `id` FROM `major_groups` WHERE `code` = 'BEN' LIMIT 1)
WHERE `major_group_id` IS NULL AND UPPER(`code`) IN ('EN', 'EL');

UPDATE `majors`
SET `major_group_id` = (SELECT `id` FROM `major_groups` WHERE `code` = 'BJP' LIMIT 1)
WHERE `major_group_id` IS NULL AND UPPER(`code`) IN ('JP');

UPDATE `majors`
SET `major_group_id` = (SELECT `id` FROM `major_groups` WHERE `code` = 'BKR' LIMIT 1)
WHERE `major_group_id` IS NULL AND UPPER(`code`) IN ('KR');

UPDATE `majors`
SET `major_group_id` = (SELECT `id` FROM `major_groups` WHERE `code` = 'BCH' LIMIT 1)
WHERE `major_group_id` IS NULL AND UPPER(`code`) IN ('CN', 'CH');

UPDATE `majors`
SET `major_group_id` = (SELECT `id` FROM `major_groups` WHERE `code` = 'LLB' LIMIT 1)
WHERE `major_group_id` IS NULL AND UPPER(`code`) IN ('LAW');

-- 6. Default existing unassigned advisors to BIT so priority works immediately
UPDATE `advisors`
SET `major_group_id` = (SELECT `id` FROM `major_groups` WHERE `code` = 'BIT' LIMIT 1)
WHERE `major_group_id` IS NULL;
