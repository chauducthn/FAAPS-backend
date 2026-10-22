USE `faaps`;

-- MySQL DDL implicitly commits. The original advisor codes and specializations
-- cannot be reconstructed after UP; restore them from a database backup if needed.
ALTER TABLE `advisors`
  ADD COLUMN `advisor_code` varchar(50) NULL AFTER `user_id`,
  ADD UNIQUE KEY `uq_advisors_advisor_code` (`advisor_code`),
  ADD COLUMN `specialization` text NULL AFTER `advisor_code`,
  ADD COLUMN `work_days` varchar(20) NULL AFTER `status`;

UPDATE `advisors`
SET `advisor_code` = CONCAT('LEGACY-', `user_id`)
WHERE `advisor_code` IS NULL;

UPDATE `advisors` advisor
JOIN `advisor_work_schedules` schedule ON schedule.advisor_id = advisor.user_id
SET advisor.work_days = schedule.work_days;

ALTER TABLE `advisors`
  MODIFY COLUMN `advisor_code` varchar(50) NOT NULL;

DROP TABLE `advisor_work_schedules`;
