USE `faaps`;

-- MySQL DDL implicitly commits; take a backup before applying this migration.
-- Preserve configured workdays before removing the denormalized profile column.
CREATE TABLE IF NOT EXISTS `advisor_work_schedules` (
  `advisor_id` char(36) NOT NULL,
  `work_days` varchar(20) NULL COMMENT 'Comma-separated ISO weekdays 1=Monday..7=Sunday',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`advisor_id`),
  CONSTRAINT `fk_advisor_work_schedules_advisor`
    FOREIGN KEY (`advisor_id`) REFERENCES `advisors` (`user_id`)
    ON DELETE CASCADE ON UPDATE CASCADE
);

INSERT INTO `advisor_work_schedules` (`advisor_id`, `work_days`)
SELECT `user_id`, `work_days`
FROM `advisors`
WHERE `work_days` IS NOT NULL AND TRIM(`work_days`) <> ''
ON DUPLICATE KEY UPDATE
  `work_days` = VALUES(`work_days`),
  `updated_at` = CURRENT_TIMESTAMP;

ALTER TABLE `advisors`
  DROP COLUMN `advisor_code`,
  DROP COLUMN `specialization`,
  DROP COLUMN `work_days`;
