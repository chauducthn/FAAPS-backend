USE `faaps`;

-- Restore advisor_code and specialization to advisors table
ALTER TABLE `advisors`
  ADD COLUMN `advisor_code` varchar(50) NULL AFTER `user_id`,
  ADD COLUMN `specialization` text NULL AFTER `advisor_code`,
  ADD UNIQUE KEY `uq_advisors_advisor_code` (`advisor_code`);
