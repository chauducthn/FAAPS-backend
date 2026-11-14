-- ==============================================================================
-- FAAPS Database Migration: Allow Unassigned Students (Decoupled Registration)
-- Target: AWS RDS MySQL 8.4 Production & Existing Local Instances
-- Resolves: Feedback "BE cần cho phép tạo student khi chưa có major_id và curriculum_version_id và enrollment_semester_id"
-- ==============================================================================

USE `faaps`;

ALTER TABLE `students` MODIFY `major_id` char(36) NULL;
ALTER TABLE `students` MODIFY `curriculum_version_id` char(36) NULL;
ALTER TABLE `students` MODIFY `enrollment_semester_id` char(36) NULL;
