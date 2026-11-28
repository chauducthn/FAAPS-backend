-- Migration: roadmap versions (SYSTEM rule-based plan, AI refinement, student CUSTOM edit)
-- The backend also creates this table on start-up (CREATE TABLE IF NOT EXISTS), so this
-- file only documents the schema for environments that apply migrations by hand.

SET NAMES utf8mb4;
USE faaps;

CREATE TABLE IF NOT EXISTS student_roadmap_versions (
  student_id char(36) NOT NULL,
  kind varchar(10) NOT NULL COMMENT 'SYSTEM, AI or CUSTOM',
  payload longtext NOT NULL COMMENT 'StudyRoadmapDto as JSON',
  based_on varchar(10) NULL COMMENT 'For CUSTOM: the version it was edited from',
  is_active tinyint(1) NOT NULL DEFAULT 0 COMMENT 'The version the student follows',
  updated_at timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (student_id, kind)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
