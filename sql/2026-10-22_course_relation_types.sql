USE `faaps`;

DROP PROCEDURE IF EXISTS `faaps_check_course_relation_types_20261022`;
DELIMITER $$
CREATE PROCEDURE `faaps_check_course_relation_types_20261022`()
BEGIN
  IF EXISTS (
    SELECT 1 FROM `course_prerequisites`
    WHERE `type` IS NULL OR `type` NOT IN ('PREREQUISITE', 'REPLACEMENT')
  ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Review unsupported course relation rows before migration';
  END IF;
  IF EXISTS (
    SELECT 1 FROM `academic_policies`
    WHERE `policy_type` NOT IN (
      'ADVANCED_STUDY', 'RETAKE', 'MAX_CREDITS_PER_SEMESTER',
      'MIN_CREDITS_PER_SEMESTER', 'COURSE_REPEAT_LIMIT', 'GPA_WARNING',
      'ACADEMIC_PROBATION', 'OTHER'
    )
  ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Review unsupported academic policy rows before migration';
  END IF;

  ALTER TABLE `course_prerequisites`
    MODIFY COLUMN `type` varchar(20) NOT NULL DEFAULT 'PREREQUISITE' COMMENT 'PREREQUISITE, REPLACEMENT',
    ADD CONSTRAINT `course_prerequisites_type_ck` CHECK (`type` IN ('PREREQUISITE', 'REPLACEMENT'));

  ALTER TABLE `academic_policies`
    MODIFY COLUMN `policy_type` varchar(30) NOT NULL COMMENT 'ADVANCED_STUDY, RETAKE, MAX_CREDITS_PER_SEMESTER, MIN_CREDITS_PER_SEMESTER, COURSE_REPEAT_LIMIT, GPA_WARNING, ACADEMIC_PROBATION, OTHER',
    ADD CONSTRAINT `academic_policies_type_ck` CHECK (`policy_type` IN (
      'ADVANCED_STUDY', 'RETAKE', 'MAX_CREDITS_PER_SEMESTER',
      'MIN_CREDITS_PER_SEMESTER', 'COURSE_REPEAT_LIMIT', 'GPA_WARNING',
      'ACADEMIC_PROBATION', 'OTHER'
    ));
END$$
DELIMITER ;

CALL `faaps_check_course_relation_types_20261022`();
DROP PROCEDURE `faaps_check_course_relation_types_20261022`;
