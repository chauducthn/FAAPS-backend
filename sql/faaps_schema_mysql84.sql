CREATE DATABASE IF NOT EXISTS faaps CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE faaps;

CREATE TABLE `roles` (
  `id` char(36) PRIMARY KEY DEFAULT (UUID()),
  `code` varchar(50) UNIQUE NOT NULL COMMENT 'ADMIN, ADVISOR, STUDENT',
  `name` varchar(100) NOT NULL,
  `description` text,
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP),
  `updated_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `users` (
  `id` char(36) PRIMARY KEY DEFAULT (UUID()),
  `role_id` char(36) NOT NULL,
  `email` varchar(255) UNIQUE NOT NULL,
  `password_hash` varchar(255) COMMENT 'NULL when the account only signs in through an external provider (e.g. Google)',
  `auth_provider` varchar(20) DEFAULT 'LOCAL' COMMENT 'LOCAL, GOOGLE',
  `external_id` varchar(255) COMMENT 'Provider user id (Google "sub" claim)',
  `full_name` varchar(255) NOT NULL,
  `phone` varchar(20),
  `avatar_url` varchar(500),
  `status` varchar(20) DEFAULT 'ACTIVE' COMMENT 'ACTIVE, INACTIVE, SUSPENDED, LOCKED',
  `email_verified_at` timestamp,
  `last_login_at` timestamp,
  `failed_login_attempts` int DEFAULT 0,
  `locked_until` timestamp,
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP),
  `updated_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `refresh_tokens` (
  `id` char(36) PRIMARY KEY DEFAULT (UUID()),
  `user_id` char(36) NOT NULL,
  `token` varchar(500) UNIQUE NOT NULL,
  `expires_at` timestamp NOT NULL,
  `revoked_at` timestamp,
  `ip_address` varchar(45),
  `user_agent` varchar(500),
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `password_reset_tokens` (
  `id` char(36) PRIMARY KEY DEFAULT (UUID()),
  `user_id` char(36) NOT NULL,
  `token` varchar(255) UNIQUE NOT NULL,
  `expires_at` timestamp NOT NULL,
  `used_at` timestamp,
  `ip_address` varchar(45),
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `majors` (
  `id` char(36) PRIMARY KEY DEFAULT (UUID()),
  `code` varchar(50) UNIQUE NOT NULL,
  `name` varchar(255) NOT NULL,
  `description` text,
  `total_credits` int NOT NULL,
  `duration_years` decimal(3,1) NOT NULL,
  `status` varchar(20) DEFAULT 'ACTIVE' COMMENT 'ACTIVE, INACTIVE',
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP),
  `updated_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `semesters` (
  `id` char(36) PRIMARY KEY DEFAULT (UUID()),
  `code` varchar(50) UNIQUE NOT NULL COMMENT 'e.g. SP2026, SU2026, FA2026',
  `name` varchar(100) NOT NULL,
  `start_date` date NOT NULL,
  `end_date` date NOT NULL,
  `is_current` boolean DEFAULT false,
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP),
  `updated_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `curriculum_versions` (
  `id` char(36) PRIMARY KEY DEFAULT (UUID()),
  `major_id` char(36) NOT NULL,
  `version` varchar(50) NOT NULL COMMENT 'e.g. K18, K19',
  `name` varchar(255) NOT NULL,
  `effective_from_semester_id` char(36) NOT NULL,
  `effective_to_semester_id` char(36),
  `description` text,
  `is_current` boolean DEFAULT false,
  `status` varchar(20) DEFAULT 'ACTIVE' COMMENT 'ACTIVE, INACTIVE',
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP),
  `updated_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `course_categories` (
  `id` char(36) PRIMARY KEY DEFAULT (UUID()),
  `code` varchar(50) UNIQUE NOT NULL,
  `name` varchar(100) NOT NULL,
  `description` text,
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP),
  `updated_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `courses` (
  `id` char(36) PRIMARY KEY DEFAULT (UUID()),
  `code` varchar(50) UNIQUE NOT NULL,
  `name` varchar(255) NOT NULL,
  `credits` int NOT NULL,
  `category_id` char(36) NOT NULL,
  `description` text,
  `objectives` text,
  `learning_outcomes` text,
  `status` varchar(20) DEFAULT 'ACTIVE' COMMENT 'ACTIVE, INACTIVE',
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP),
  `updated_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `course_prerequisites` (
  `id` char(36) PRIMARY KEY DEFAULT (UUID()),
  `course_id` char(36) NOT NULL,
  `prerequisite_course_id` char(36) NOT NULL,
  `group_no` int NOT NULL DEFAULT 1 COMMENT 'Rows with the same group_no are alternatives (OR). Different group_no are all required (AND). Example: (A OR B) AND C -> A:1, B:1, C:2',
  `type` varchar(20) DEFAULT 'PREREQUISITE' COMMENT 'PREREQUISITE, CO_REQUISITE',
  `minimum_score` decimal(4,2) COMMENT 'Minimum score, 10-point scale (e.g. 5.00)',
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `curriculum_courses` (
  `id` char(36) PRIMARY KEY DEFAULT (UUID()),
  `curriculum_version_id` char(36) NOT NULL,
  `course_id` char(36) NOT NULL,
  `semester_number` int NOT NULL COMMENT 'Recommended term in the curriculum',
  `is_mandatory` boolean DEFAULT true,
  `group_name` varchar(100) COMMENT 'Elective group, if any',
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP),
  `updated_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `students` (
  `user_id` char(36) PRIMARY KEY,
  `student_code` varchar(50) UNIQUE NOT NULL,
  `major_id` char(36) NOT NULL,
  `curriculum_version_id` char(36) NOT NULL,
  `enrollment_semester_id` char(36) NOT NULL,
  `expected_graduation_semester_id` char(36),
  `current_semester` int DEFAULT 1,
  `current_gpa` decimal(4,2) DEFAULT 0 COMMENT 'GPA 10-point scale (0.00 - 10.00)',
  `total_credits_earned` int DEFAULT 0,
  `academic_status` varchar(20) DEFAULT 'ACTIVE' COMMENT 'ACTIVE, PROBATION, SUSPENDED, GRADUATED, WITHDRAWN, LEAVE_OF_ABSENCE',
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP),
  `updated_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `advisors` (
  `user_id` char(36) PRIMARY KEY,
  `advisor_code` varchar(50) UNIQUE NOT NULL,
  `specialization` text,
  `max_advisees` int DEFAULT 30,
  `status` varchar(20) DEFAULT 'ACTIVE' COMMENT 'ACTIVE, INACTIVE, ON_LEAVE',
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP),
  `updated_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `student_advisor_assignments` (
  `id` char(36) PRIMARY KEY DEFAULT (UUID()),
  `student_id` char(36) NOT NULL,
  `advisor_id` char(36) NOT NULL,
  `group_name` varchar(100) COMMENT 'Optional student group handled by the advisor, e.g. cohort K19 - SE',
  `assigned_date` date NOT NULL,
  `assigned_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `assigned_by_user_id` char(36),
  `end_date` date,
  `is_primary` boolean DEFAULT true,
  `status` varchar(20) DEFAULT 'ACTIVE' COMMENT 'ACTIVE, COMPLETED, TRANSFERRED',
  `notes` text,
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP),
  `updated_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `student_course_records` (
  `id` char(36) PRIMARY KEY DEFAULT (UUID()),
  `student_id` char(36) NOT NULL,
  `course_id` char(36) NOT NULL,
  `semester_id` char(36) NOT NULL,
  `final_score` decimal(4,2) COMMENT 'Final score imported from FAP, 10-point scale (0.00 - 10.00). NULL while in progress',
  `status` varchar(20) DEFAULT 'IN_PROGRESS' COMMENT 'IN_PROGRESS, PASSED, FAILED, WITHDRAWN, EXEMPTED',
  `attempt_number` int DEFAULT 1 COMMENT 'Retake tracking',
  `credits_earned` int DEFAULT 0,
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP),
  `updated_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `semester_summaries` (
  `id` char(36) PRIMARY KEY DEFAULT (UUID()),
  `student_id` char(36) NOT NULL,
  `semester_id` char(36) NOT NULL,
  `total_credits_enrolled` int DEFAULT 0,
  `total_credits_earned` int DEFAULT 0,
  `semester_gpa` decimal(4,2) DEFAULT 0 COMMENT '10-point scale. Imported from FAP or recomputed from student_course_records',
  `cumulative_gpa` decimal(4,2) DEFAULT 0 COMMENT '10-point scale',
  `academic_standing` varchar(20) DEFAULT 'GOOD' COMMENT 'GOOD, PROBATION, SUSPENDED',
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP),
  `updated_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `academic_warnings` (
  `id` char(36) PRIMARY KEY DEFAULT (UUID()),
  `student_id` char(36) NOT NULL,
  `semester_id` char(36) NOT NULL,
  `warning_type` varchar(20) NOT NULL COMMENT 'LOW_GPA, FAILED_COURSES, ATTENDANCE, OTHER',
  `severity` varchar(20) NOT NULL COMMENT 'WARNING, PROBATION, FINAL_WARNING',
  `description` text NOT NULL,
  `action_required` text,
  `issued_by_user_id` char(36) NOT NULL,
  `acknowledged_at` timestamp,
  `resolved_at` timestamp,
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP),
  `updated_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `study_plans` (
  `id` char(36) PRIMARY KEY DEFAULT (UUID()),
  `student_id` char(36) NOT NULL,
  `semester_id` char(36) NOT NULL,
  `version` int NOT NULL DEFAULT 1 COMMENT 'Increases each time the student resubmits after a review',
  `is_current` boolean DEFAULT true COMMENT 'Only the latest version of a plan is current',
  `previous_plan_id` char(36) COMMENT 'Version this one was created from',
  `plan_type` varchar(20) DEFAULT 'REGULAR' COMMENT 'REGULAR, MAKEUP, ACCELERATED, REDUCED',
  `total_credits_planned` int DEFAULT 0,
  `status` varchar(20) DEFAULT 'SUBMITTED' COMMENT 'SUBMITTED, APPROVED, REJECTED, NEEDS_REVISION',
  `submitted_at` timestamp,
  `reviewed_by_user_id` char(36),
  `reviewed_at` timestamp,
  `review_notes` text,
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP),
  `updated_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `study_plan_courses` (
  `id` char(36) PRIMARY KEY DEFAULT (UUID()),
  `study_plan_id` char(36) NOT NULL,
  `course_id` char(36) NOT NULL,
  `planned_credits` int NOT NULL,
  `priority` varchar(20) DEFAULT 'MEDIUM' COMMENT 'HIGH, MEDIUM, LOW',
  `notes` text,
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `course_recommendations` (
  `id` char(36) PRIMARY KEY DEFAULT (UUID()),
  `student_id` char(36) NOT NULL,
  `course_id` char(36) NOT NULL,
  `semester_id` char(36) NOT NULL,
  `recommendation_source` varchar(20) NOT NULL COMMENT 'ADVISOR, AI, CURRICULUM',
  `ai_message_id` char(36) COMMENT 'Set when the recommendation comes from an AI answer',
  `recommended_by_user_id` char(36) COMMENT 'Set when the source is ADVISOR',
  `confidence_score` decimal(5,4),
  `reasoning` text,
  `priority` varchar(20) DEFAULT 'MEDIUM' COMMENT 'HIGH, MEDIUM, LOW',
  `status` varchar(20) DEFAULT 'PENDING' COMMENT 'PENDING, ACCEPTED, REJECTED',
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP),
  `updated_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `academic_policies` (
  `id` char(36) PRIMARY KEY DEFAULT (UUID()),
  `curriculum_version_id` char(36) NOT NULL,
  `policy_type` varchar(30) NOT NULL COMMENT 'CO_REQUISITE, ADVANCED_STUDY, RETAKE, MAX_CREDITS_PER_SEMESTER, MIN_CREDITS_PER_SEMESTER, COURSE_REPEAT_LIMIT, GPA_WARNING, ACADEMIC_PROBATION, OTHER',
  `condition_expression` text COMMENT 'JSON, e.g. {"min_gpa": 5.0, "min_credits": 30}',
  `restriction_expression` text COMMENT 'JSON, restrictions when the policy applies',
  `approval_required` boolean DEFAULT false,
  `priority` int DEFAULT 0,
  `description` text,
  `display_message` text COMMENT 'Message shown to the student',
  `is_active` boolean DEFAULT true,
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP),
  `updated_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `graduation_requirements` (
  `id` char(36) PRIMARY KEY DEFAULT (UUID()),
  `curriculum_version_id` char(36) NOT NULL,
  `requirement_type` varchar(20) NOT NULL COMMENT 'TOTAL_CREDITS, CATEGORY_CREDITS, MINIMUM_GPA, SPECIFIC_COURSE, OTHER',
  `description` text NOT NULL,
  `min_credits` int COMMENT 'TOTAL_CREDITS, CATEGORY_CREDITS',
  `min_gpa` decimal(4,2) COMMENT 'MINIMUM_GPA, 10-point scale',
  `category_id` char(36) COMMENT 'CATEGORY_CREDITS',
  `course_id` char(36) COMMENT 'SPECIFIC_COURSE',
  `is_mandatory` boolean DEFAULT true,
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP),
  `updated_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `student_requirement_status` (
  `id` char(36) PRIMARY KEY DEFAULT (UUID()),
  `student_id` char(36) NOT NULL,
  `requirement_id` char(36) NOT NULL,
  `status` varchar(20) DEFAULT 'NOT_STARTED' COMMENT 'NOT_STARTED, IN_PROGRESS, SATISFIED, NOT_SATISFIED',
  `progress_value` decimal(6,2) COMMENT 'Credits earned or GPA reached for this requirement',
  `checked_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `graduation_checks` (
  `id` char(36) PRIMARY KEY DEFAULT (UUID()),
  `student_id` char(36) NOT NULL,
  `curriculum_version_id` char(36) NOT NULL,
  `check_date` timestamp DEFAULT (CURRENT_TIMESTAMP),
  `total_credits_earned` int DEFAULT 0,
  `current_gpa` decimal(4,2) DEFAULT 0 COMMENT '10-point scale',
  `is_eligible` boolean DEFAULT false,
  `checked_by_user_id` char(36),
  `notes` text,
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `advising_requests` (
  `id` char(36) PRIMARY KEY DEFAULT (UUID()),
  `student_id` char(36) NOT NULL,
  `advisor_id` char(36) COMMENT 'NULL until an advisor takes the request',
  `study_plan_id` char(36) COMMENT 'Optional: the plan the request is about',
  `request_type` varchar(30) NOT NULL COMMENT 'PLAN_REVIEW, COURSE_SELECTION, ACADEMIC_GUIDANCE, GRADUATION_CHECK, OTHER',
  `title` varchar(255) NOT NULL,
  `description` text,
  `status` varchar(20) DEFAULT 'PENDING' COMMENT 'PENDING, IN_PROGRESS, RESOLVED, CANCELLED',
  `resolved_at` timestamp,
  `resolution_summary` text,
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP),
  `updated_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `advising_messages` (
  `id` char(36) PRIMARY KEY DEFAULT (UUID()),
  `advising_request_id` char(36) NOT NULL,
  `sender_user_id` char(36) NOT NULL,
  `content` text NOT NULL,
  `is_internal` boolean DEFAULT false COMMENT 'Advisor-only note, hidden from the student',
  `read_at` timestamp,
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `advising_sessions` (
  `id` char(36) PRIMARY KEY DEFAULT (UUID()),
  `student_id` char(36) NOT NULL,
  `advisor_id` char(36) NOT NULL,
  `advising_request_id` char(36) COMMENT 'Optional: appointment created from a request',
  `session_type` varchar(20) DEFAULT 'SCHEDULED' COMMENT 'SCHEDULED, WALK_IN, VIRTUAL',
  `scheduled_at` timestamp,
  `started_at` timestamp,
  `ended_at` timestamp,
  `location` varchar(255),
  `meeting_link` varchar(500),
  `agenda` text,
  `summary` text,
  `status` varchar(20) DEFAULT 'SCHEDULED' COMMENT 'SCHEDULED, IN_PROGRESS, COMPLETED, CANCELLED, NO_SHOW',
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP),
  `updated_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `advising_notes` (
  `id` char(36) PRIMARY KEY DEFAULT (UUID()),
  `student_id` char(36) NOT NULL COMMENT 'A note belongs to the student, with or without a session',
  `advising_session_id` char(36) COMMENT 'NULL for notes written outside a session',
  `note_type` varchar(20) DEFAULT 'GENERAL' COMMENT 'GENERAL, ACADEMIC, PERSONAL, ACTION_ITEM, FOLLOW_UP',
  `content` text NOT NULL,
  `is_private` boolean DEFAULT false,
  `action_required` boolean DEFAULT false,
  `due_date` date,
  `created_by_user_id` char(36) NOT NULL,
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP),
  `updated_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `advisor_availability` (
  `id` char(36) PRIMARY KEY DEFAULT (UUID()),
  `advisor_id` char(36) NOT NULL,
  `day_of_week` varchar(20) NOT NULL COMMENT 'MONDAY ... SUNDAY',
  `start_time` time NOT NULL,
  `end_time` time NOT NULL,
  `location` varchar(255),
  `is_virtual` boolean DEFAULT false,
  `max_appointments` int DEFAULT 1,
  `is_active` boolean DEFAULT true,
  `effective_from` date,
  `effective_to` date,
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP),
  `updated_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `knowledge_documents` (
  `id` char(36) PRIMARY KEY DEFAULT (UUID()),
  `title` varchar(255) NOT NULL,
  `doc_type` varchar(20) NOT NULL COMMENT 'REGULATION, HANDBOOK, CURRICULUM, POLICY, OTHER',
  `description` text,
  `file_path` varchar(500) NOT NULL,
  `file_size` int,
  `mime_type` varchar(100),
  `language` varchar(10) DEFAULT 'vi',
  `version` int NOT NULL DEFAULT 1 COMMENT 'New upload = new row with higher version, old versions stay readable',
  `status` varchar(20) DEFAULT 'ACTIVE' COMMENT 'ACTIVE, INACTIVE. Only ACTIVE documents are retrieved',
  `effective_date` date,
  `ocr_used` boolean DEFAULT false COMMENT 'Text extracted through the OCR API',
  `processing_status` varchar(20) DEFAULT 'PENDING' COMMENT 'PENDING, PROCESSED, FAILED',
  `indexed_at` timestamp,
  `uploaded_by_user_id` char(36) NOT NULL,
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP),
  `updated_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `document_chunks` (
  `id` char(36) PRIMARY KEY DEFAULT (UUID()),
  `knowledge_document_id` char(36) NOT NULL,
  `chunk_no` int NOT NULL,
  `content` text NOT NULL,
  `page_number` int,
  `section_title` varchar(255),
  `token_count` int,
  `embedding` json COMMENT 'Vector from the embedding API. Use a vector column or an external vector DB if available',
  `embedding_model` varchar(100),
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `ai_configurations` (
  `id` char(36) PRIMARY KEY DEFAULT (UUID()),
  `name` varchar(100) UNIQUE NOT NULL,
  `provider` varchar(20) NOT NULL COMMENT 'GEMINI, OTHER',
  `model_name` varchar(100) NOT NULL,
  `purpose` varchar(20) NOT NULL COMMENT 'CHAT, EMBEDDING, OCR',
  `temperature` decimal(3,2) DEFAULT 0.7,
  `max_tokens` int DEFAULT 2048,
  `retrieval_top_k` int DEFAULT 5,
  `similarity_threshold` decimal(5,4) COMMENT 'Chunks below this relevance are ignored',
  `timeout_seconds` int DEFAULT 30,
  `daily_request_limit` int COMMENT 'Per-user quota',
  `system_prompt` text,
  `fallback_message` text COMMENT 'Shown when the AI API fails or times out',
  `is_active` boolean DEFAULT true,
  `updated_by_user_id` char(36),
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP),
  `updated_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `ai_conversations` (
  `id` char(36) PRIMARY KEY DEFAULT (UUID()),
  `user_id` char(36) NOT NULL,
  `title` varchar(255),
  `status` varchar(20) DEFAULT 'ACTIVE' COMMENT 'ACTIVE, CLOSED',
  `last_message_at` timestamp,
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `ai_messages` (
  `id` char(36) PRIMARY KEY DEFAULT (UUID()),
  `conversation_id` char(36) NOT NULL,
  `sender` varchar(20) NOT NULL COMMENT 'USER, ASSISTANT',
  `content` text NOT NULL,
  `question_category` varchar(30) COMMENT 'POLICY, COURSE_INFO, STUDY_PLAN, GRADUATION, UNSUPPORTED, OTHER',
  `is_unsupported` boolean DEFAULT false COMMENT 'Question outside the supported scope',
  `is_fallback` boolean DEFAULT false COMMENT 'Fallback message returned because the AI call failed',
  `confidence_score` decimal(5,4) COMMENT 'Answer confidence, ASSISTANT messages only',
  `ai_configuration_id` char(36),
  `was_helpful` boolean,
  `feedback` text,
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `ai_message_sources` (
  `id` char(36) PRIMARY KEY DEFAULT (UUID()),
  `message_id` char(36) NOT NULL,
  `chunk_id` char(36) NOT NULL,
  `relevance_score` decimal(5,4) COMMENT 'Similarity / confidence for this source',
  `quoted_text` text,
  `rank_position` int,
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `ai_usage_logs` (
  `id` char(36) PRIMARY KEY DEFAULT (UUID()),
  `user_id` char(36),
  `message_id` char(36),
  `ai_configuration_id` char(36),
  `feature` varchar(20) NOT NULL COMMENT 'CHAT, EMBEDDING, OCR, RECOMMENDATION, SUMMARIZATION',
  `prompt_tokens` int DEFAULT 0,
  `completion_tokens` int DEFAULT 0,
  `total_tokens` int DEFAULT 0,
  `latency_ms` int,
  `status` varchar(20) DEFAULT 'SUCCESS' COMMENT 'SUCCESS, TIMEOUT, ERROR, RATE_LIMITED, FALLBACK',
  `error_message` text,
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `notifications` (
  `id` char(36) PRIMARY KEY DEFAULT (UUID()),
  `user_id` char(36) NOT NULL,
  `notification_type` varchar(20) DEFAULT 'INFO' COMMENT 'INFO, WARNING, REMINDER, PLAN_STATUS, MESSAGE, ADVISOR_REQUEST',
  `title` varchar(255) NOT NULL,
  `message` text NOT NULL,
  `action_url` varchar(500),
  `related_student_id` char(36),
  `resolved_at` timestamp,
  `is_read` boolean DEFAULT false,
  `read_at` timestamp,
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `cohort_advisors` (
  `cohort_id` char(36) NOT NULL,
  `advisor_id` char(36) NOT NULL,
  `is_active` boolean NOT NULL DEFAULT true,
  `created_at` timestamp DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`cohort_id`, `advisor_id`),
  KEY `idx_cohort_advisors_advisor` (`advisor_id`)
);

CREATE TABLE `audit_logs` (
  `id` char(36) PRIMARY KEY DEFAULT (UUID()),
  `user_id` char(36),
  `action` varchar(100) NOT NULL,
  `entity_type` varchar(100) NOT NULL,
  `entity_id` char(36),
  `old_values` json,
  `new_values` json,
  `ip_address` varchar(45),
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE TABLE `error_logs` (
  `id` char(36) PRIMARY KEY DEFAULT (UUID()),
  `error_type` varchar(100) NOT NULL,
  `error_message` text NOT NULL,
  `stack_trace` text,
  `user_id` char(36),
  `request_url` varchar(500),
  `request_method` varchar(10),
  `severity` varchar(20) DEFAULT 'MEDIUM' COMMENT 'LOW, MEDIUM, HIGH, CRITICAL',
  `is_resolved` boolean DEFAULT false,
  `resolved_at` timestamp,
  `created_at` timestamp DEFAULT (CURRENT_TIMESTAMP)
);

CREATE INDEX `users_index_0` ON `users` (`role_id`, `status`);

CREATE UNIQUE INDEX `users_index_1` ON `users` (`auth_provider`, `external_id`);

CREATE INDEX `refresh_tokens_index_2` ON `refresh_tokens` (`user_id`, `expires_at`);

CREATE INDEX `semesters_index_3` ON `semesters` (`start_date`, `end_date`);

CREATE UNIQUE INDEX `curriculum_versions_index_4` ON `curriculum_versions` (`major_id`, `version`);

CREATE INDEX `curriculum_versions_index_5` ON `curriculum_versions` (`major_id`, `is_current`);

CREATE INDEX `courses_index_6` ON `courses` (`category_id`);

CREATE UNIQUE INDEX `course_prerequisites_index_7` ON `course_prerequisites` (`course_id`, `group_no`, `prerequisite_course_id`);

CREATE INDEX `course_prerequisites_index_8` ON `course_prerequisites` (`prerequisite_course_id`);

CREATE UNIQUE INDEX `curriculum_courses_index_9` ON `curriculum_courses` (`curriculum_version_id`, `course_id`);

CREATE INDEX `curriculum_courses_index_10` ON `curriculum_courses` (`curriculum_version_id`, `semester_number`);

CREATE INDEX `students_index_11` ON `students` (`major_id`);

CREATE INDEX `students_index_12` ON `students` (`academic_status`);

CREATE INDEX `student_advisor_assignments_index_13` ON `student_advisor_assignments` (`advisor_id`, `group_name`);

CREATE INDEX `student_advisor_assignments_index_14` ON `student_advisor_assignments` (`student_id`, `is_primary`);

CREATE UNIQUE INDEX `student_course_records_index_15` ON `student_course_records` (`student_id`, `course_id`, `attempt_number`);

CREATE INDEX `student_course_records_index_16` ON `student_course_records` (`student_id`, `semester_id`);

CREATE UNIQUE INDEX `semester_summaries_index_17` ON `semester_summaries` (`student_id`, `semester_id`);

CREATE INDEX `academic_warnings_index_18` ON `academic_warnings` (`student_id`);

CREATE INDEX `academic_warnings_index_19` ON `academic_warnings` (`semester_id`);

CREATE UNIQUE INDEX `study_plans_index_20` ON `study_plans` (`student_id`, `semester_id`, `version`);

CREATE INDEX `study_plans_index_21` ON `study_plans` (`student_id`, `semester_id`, `is_current`);

CREATE INDEX `study_plans_index_22` ON `study_plans` (`status`);

CREATE UNIQUE INDEX `study_plan_courses_index_23` ON `study_plan_courses` (`study_plan_id`, `course_id`);

CREATE INDEX `course_recommendations_index_24` ON `course_recommendations` (`student_id`, `semester_id`);

CREATE INDEX `course_recommendations_index_25` ON `course_recommendations` (`recommendation_source`, `status`);

CREATE INDEX `academic_policies_index_26` ON `academic_policies` (`curriculum_version_id`, `policy_type`);

CREATE INDEX `graduation_requirements_index_27` ON `graduation_requirements` (`curriculum_version_id`, `requirement_type`);

CREATE UNIQUE INDEX `student_requirement_status_index_28` ON `student_requirement_status` (`student_id`, `requirement_id`);

CREATE INDEX `student_requirement_status_index_29` ON `student_requirement_status` (`status`);

CREATE INDEX `graduation_checks_index_30` ON `graduation_checks` (`student_id`, `check_date`);

CREATE INDEX `advising_requests_index_31` ON `advising_requests` (`student_id`, `status`);

CREATE INDEX `advising_requests_index_32` ON `advising_requests` (`advisor_id`, `status`);

CREATE INDEX `advising_messages_index_33` ON `advising_messages` (`advising_request_id`, `created_at`);

CREATE INDEX `advising_sessions_index_34` ON `advising_sessions` (`student_id`, `scheduled_at`);

CREATE INDEX `advising_sessions_index_35` ON `advising_sessions` (`advisor_id`, `scheduled_at`);

CREATE INDEX `advising_notes_index_36` ON `advising_notes` (`student_id`, `created_at`);

CREATE INDEX `advising_notes_index_37` ON `advising_notes` (`advising_session_id`);

CREATE INDEX `advising_notes_index_38` ON `advising_notes` (`action_required`, `due_date`);

CREATE INDEX `advisor_availability_index_39` ON `advisor_availability` (`advisor_id`, `day_of_week`);

CREATE UNIQUE INDEX `knowledge_documents_index_40` ON `knowledge_documents` (`title`, `version`);

CREATE INDEX `knowledge_documents_index_41` ON `knowledge_documents` (`doc_type`, `status`);

CREATE UNIQUE INDEX `document_chunks_index_42` ON `document_chunks` (`knowledge_document_id`, `chunk_no`);

CREATE INDEX `ai_configurations_index_43` ON `ai_configurations` (`purpose`, `is_active`);

CREATE INDEX `ai_conversations_index_44` ON `ai_conversations` (`user_id`, `last_message_at`);

CREATE INDEX `ai_messages_index_45` ON `ai_messages` (`conversation_id`, `created_at`);

CREATE INDEX `ai_messages_index_46` ON `ai_messages` (`is_unsupported`);

CREATE INDEX `ai_message_sources_index_47` ON `ai_message_sources` (`message_id`);

CREATE INDEX `ai_message_sources_index_48` ON `ai_message_sources` (`chunk_id`);

CREATE INDEX `ai_usage_logs_index_49` ON `ai_usage_logs` (`user_id`, `created_at`);

CREATE INDEX `ai_usage_logs_index_50` ON `ai_usage_logs` (`feature`, `status`);

CREATE INDEX `notifications_index_51` ON `notifications` (`user_id`, `is_read`);

CREATE INDEX `audit_logs_index_52` ON `audit_logs` (`entity_type`, `entity_id`);

CREATE INDEX `audit_logs_index_53` ON `audit_logs` (`user_id`, `created_at`);

CREATE INDEX `error_logs_index_54` ON `error_logs` (`severity`, `is_resolved`);

CREATE INDEX `error_logs_index_55` ON `error_logs` (`created_at`);

ALTER TABLE `users` ADD FOREIGN KEY (`role_id`) REFERENCES `roles` (`id`);

ALTER TABLE `curriculum_versions` ADD FOREIGN KEY (`major_id`) REFERENCES `majors` (`id`);

ALTER TABLE `curriculum_versions` ADD FOREIGN KEY (`effective_from_semester_id`) REFERENCES `semesters` (`id`);

ALTER TABLE `curriculum_versions` ADD FOREIGN KEY (`effective_to_semester_id`) REFERENCES `semesters` (`id`);

ALTER TABLE `courses` ADD FOREIGN KEY (`category_id`) REFERENCES `course_categories` (`id`);

ALTER TABLE `curriculum_courses` ADD FOREIGN KEY (`course_id`) REFERENCES `courses` (`id`);

ALTER TABLE `students` ADD FOREIGN KEY (`user_id`) REFERENCES `users` (`id`);

ALTER TABLE `students` ADD FOREIGN KEY (`major_id`) REFERENCES `majors` (`id`);

ALTER TABLE `students` ADD FOREIGN KEY (`curriculum_version_id`) REFERENCES `curriculum_versions` (`id`);

ALTER TABLE `students` ADD FOREIGN KEY (`enrollment_semester_id`) REFERENCES `semesters` (`id`);

ALTER TABLE `students` ADD FOREIGN KEY (`expected_graduation_semester_id`) REFERENCES `semesters` (`id`);

ALTER TABLE `advisors` ADD FOREIGN KEY (`user_id`) REFERENCES `users` (`id`);

ALTER TABLE `student_advisor_assignments` ADD FOREIGN KEY (`student_id`) REFERENCES `students` (`user_id`);

ALTER TABLE `student_advisor_assignments` ADD FOREIGN KEY (`advisor_id`) REFERENCES `advisors` (`user_id`);

ALTER TABLE `student_advisor_assignments` ADD FOREIGN KEY (`assigned_by_user_id`) REFERENCES `users` (`id`) ON DELETE SET NULL;

ALTER TABLE `student_course_records` ADD FOREIGN KEY (`student_id`) REFERENCES `students` (`user_id`);

ALTER TABLE `student_course_records` ADD FOREIGN KEY (`course_id`) REFERENCES `courses` (`id`);

ALTER TABLE `student_course_records` ADD FOREIGN KEY (`semester_id`) REFERENCES `semesters` (`id`);

ALTER TABLE `semester_summaries` ADD FOREIGN KEY (`student_id`) REFERENCES `students` (`user_id`);

ALTER TABLE `semester_summaries` ADD FOREIGN KEY (`semester_id`) REFERENCES `semesters` (`id`);

ALTER TABLE `academic_warnings` ADD FOREIGN KEY (`student_id`) REFERENCES `students` (`user_id`);

ALTER TABLE `academic_warnings` ADD FOREIGN KEY (`semester_id`) REFERENCES `semesters` (`id`);

ALTER TABLE `academic_warnings` ADD FOREIGN KEY (`issued_by_user_id`) REFERENCES `users` (`id`);

ALTER TABLE `study_plans` ADD FOREIGN KEY (`student_id`) REFERENCES `students` (`user_id`);

ALTER TABLE `study_plans` ADD FOREIGN KEY (`semester_id`) REFERENCES `semesters` (`id`);

ALTER TABLE `study_plan_courses` ADD FOREIGN KEY (`course_id`) REFERENCES `courses` (`id`);

ALTER TABLE `course_recommendations` ADD FOREIGN KEY (`student_id`) REFERENCES `students` (`user_id`);

ALTER TABLE `course_recommendations` ADD FOREIGN KEY (`course_id`) REFERENCES `courses` (`id`);

ALTER TABLE `course_recommendations` ADD FOREIGN KEY (`semester_id`) REFERENCES `semesters` (`id`);

ALTER TABLE `graduation_requirements` ADD FOREIGN KEY (`category_id`) REFERENCES `course_categories` (`id`);

ALTER TABLE `graduation_requirements` ADD FOREIGN KEY (`course_id`) REFERENCES `courses` (`id`);

ALTER TABLE `graduation_checks` ADD FOREIGN KEY (`student_id`) REFERENCES `students` (`user_id`);

ALTER TABLE `graduation_checks` ADD FOREIGN KEY (`curriculum_version_id`) REFERENCES `curriculum_versions` (`id`);

ALTER TABLE `advising_requests` ADD FOREIGN KEY (`student_id`) REFERENCES `students` (`user_id`);

ALTER TABLE `advising_messages` ADD FOREIGN KEY (`sender_user_id`) REFERENCES `users` (`id`);

ALTER TABLE `advising_sessions` ADD FOREIGN KEY (`student_id`) REFERENCES `students` (`user_id`);

ALTER TABLE `advising_sessions` ADD FOREIGN KEY (`advisor_id`) REFERENCES `advisors` (`user_id`);

ALTER TABLE `advising_notes` ADD FOREIGN KEY (`student_id`) REFERENCES `students` (`user_id`);

ALTER TABLE `advising_notes` ADD FOREIGN KEY (`created_by_user_id`) REFERENCES `users` (`id`);

ALTER TABLE `knowledge_documents` ADD FOREIGN KEY (`uploaded_by_user_id`) REFERENCES `users` (`id`);

ALTER TABLE `ai_conversations` ADD FOREIGN KEY (`user_id`) REFERENCES `users` (`id`);

ALTER TABLE `ai_message_sources` ADD FOREIGN KEY (`chunk_id`) REFERENCES `document_chunks` (`id`);

ALTER TABLE `refresh_tokens` ADD FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE;

ALTER TABLE `password_reset_tokens` ADD FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE;

ALTER TABLE `course_prerequisites` ADD FOREIGN KEY (`course_id`) REFERENCES `courses` (`id`) ON DELETE CASCADE;

ALTER TABLE `course_prerequisites` ADD FOREIGN KEY (`prerequisite_course_id`) REFERENCES `courses` (`id`) ON DELETE CASCADE;

ALTER TABLE `curriculum_courses` ADD FOREIGN KEY (`curriculum_version_id`) REFERENCES `curriculum_versions` (`id`) ON DELETE CASCADE;

ALTER TABLE `study_plans` ADD FOREIGN KEY (`previous_plan_id`) REFERENCES `study_plans` (`id`) ON DELETE SET NULL;

ALTER TABLE `study_plans` ADD FOREIGN KEY (`reviewed_by_user_id`) REFERENCES `users` (`id`) ON DELETE SET NULL;

ALTER TABLE `study_plan_courses` ADD FOREIGN KEY (`study_plan_id`) REFERENCES `study_plans` (`id`) ON DELETE CASCADE;

ALTER TABLE `course_recommendations` ADD FOREIGN KEY (`ai_message_id`) REFERENCES `ai_messages` (`id`) ON DELETE SET NULL;

ALTER TABLE `course_recommendations` ADD FOREIGN KEY (`recommended_by_user_id`) REFERENCES `users` (`id`) ON DELETE SET NULL;

ALTER TABLE `academic_policies` ADD FOREIGN KEY (`curriculum_version_id`) REFERENCES `curriculum_versions` (`id`) ON DELETE CASCADE;

ALTER TABLE `graduation_requirements` ADD FOREIGN KEY (`curriculum_version_id`) REFERENCES `curriculum_versions` (`id`) ON DELETE CASCADE;

ALTER TABLE `student_requirement_status` ADD FOREIGN KEY (`student_id`) REFERENCES `students` (`user_id`) ON DELETE CASCADE;

ALTER TABLE `student_requirement_status` ADD FOREIGN KEY (`requirement_id`) REFERENCES `graduation_requirements` (`id`) ON DELETE CASCADE;

ALTER TABLE `graduation_checks` ADD FOREIGN KEY (`checked_by_user_id`) REFERENCES `users` (`id`) ON DELETE SET NULL;

ALTER TABLE `advising_requests` ADD FOREIGN KEY (`advisor_id`) REFERENCES `advisors` (`user_id`) ON DELETE SET NULL;

ALTER TABLE `advising_requests` ADD FOREIGN KEY (`study_plan_id`) REFERENCES `study_plans` (`id`) ON DELETE SET NULL;

ALTER TABLE `advising_messages` ADD FOREIGN KEY (`advising_request_id`) REFERENCES `advising_requests` (`id`) ON DELETE CASCADE;

ALTER TABLE `advising_sessions` ADD FOREIGN KEY (`advising_request_id`) REFERENCES `advising_requests` (`id`) ON DELETE SET NULL;

ALTER TABLE `advising_notes` ADD FOREIGN KEY (`advising_session_id`) REFERENCES `advising_sessions` (`id`) ON DELETE SET NULL;

ALTER TABLE `advisor_availability` ADD FOREIGN KEY (`advisor_id`) REFERENCES `advisors` (`user_id`) ON DELETE CASCADE;

ALTER TABLE `document_chunks` ADD FOREIGN KEY (`knowledge_document_id`) REFERENCES `knowledge_documents` (`id`) ON DELETE CASCADE;

ALTER TABLE `ai_configurations` ADD FOREIGN KEY (`updated_by_user_id`) REFERENCES `users` (`id`) ON DELETE SET NULL;

ALTER TABLE `ai_messages` ADD FOREIGN KEY (`conversation_id`) REFERENCES `ai_conversations` (`id`) ON DELETE CASCADE;

ALTER TABLE `ai_messages` ADD FOREIGN KEY (`ai_configuration_id`) REFERENCES `ai_configurations` (`id`) ON DELETE SET NULL;

ALTER TABLE `ai_message_sources` ADD FOREIGN KEY (`message_id`) REFERENCES `ai_messages` (`id`) ON DELETE CASCADE;

ALTER TABLE `ai_usage_logs` ADD FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE SET NULL;

ALTER TABLE `ai_usage_logs` ADD FOREIGN KEY (`message_id`) REFERENCES `ai_messages` (`id`) ON DELETE SET NULL;

ALTER TABLE `ai_usage_logs` ADD FOREIGN KEY (`ai_configuration_id`) REFERENCES `ai_configurations` (`id`) ON DELETE SET NULL;

ALTER TABLE `notifications` ADD FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE;
ALTER TABLE `notifications` ADD FOREIGN KEY (`related_student_id`) REFERENCES `students` (`user_id`) ON DELETE SET NULL;
ALTER TABLE `cohort_advisors` ADD FOREIGN KEY (`cohort_id`) REFERENCES `curriculum_versions` (`id`);
ALTER TABLE `cohort_advisors` ADD FOREIGN KEY (`advisor_id`) REFERENCES `advisors` (`user_id`);

ALTER TABLE `audit_logs` ADD FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE SET NULL;

ALTER TABLE `error_logs` ADD FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE SET NULL;

CREATE TABLE IF NOT EXISTS ai_response_reports (
    id CHAR(36) PRIMARY KEY,
    conversation_id CHAR(36) NOT NULL,
    student_id CHAR(36) NOT NULL,
    reason VARCHAR(100) NOT NULL,
    student_note TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    resolution_note TEXT,
    resolved_by CHAR(36),
    resolved_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_ai_report_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversations(id),
    CONSTRAINT fk_ai_report_student FOREIGN KEY (student_id) REFERENCES users(id),
    CONSTRAINT fk_ai_report_resolver FOREIGN KEY (resolved_by) REFERENCES users(id),
    INDEX idx_ai_report_status_created (status, created_at),
    INDEX idx_ai_report_student (student_id)
);

CREATE TABLE IF NOT EXISTS ai_reported_messages (
    id CHAR(36) PRIMARY KEY,
    report_id CHAR(36) NOT NULL,
    message_id CHAR(36) NOT NULL,
    student_note TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_reported_message_report FOREIGN KEY (report_id) REFERENCES ai_response_reports(id),
    CONSTRAINT fk_reported_message_message FOREIGN KEY (message_id) REFERENCES ai_messages(id),
    UNIQUE KEY uq_report_message (report_id, message_id)
);

INSERT IGNORE INTO roles (code, name) VALUES
('ADMIN', 'Admin'),
('ADVISOR', 'Advisor'),
('STUDENT', 'Student');
