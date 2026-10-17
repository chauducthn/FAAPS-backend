CREATE TABLE IF NOT EXISTS cohort_advisors (
  cohort_id char(36) NOT NULL,
  advisor_id char(36) NOT NULL,
  is_active boolean NOT NULL DEFAULT true,
  created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (cohort_id, advisor_id),
  KEY idx_cohort_advisors_advisor (advisor_id),
  CONSTRAINT fk_cohort_advisors_cohort FOREIGN KEY (cohort_id) REFERENCES curriculum_versions(id),
  CONSTRAINT fk_cohort_advisors_advisor FOREIGN KEY (advisor_id) REFERENCES advisors(user_id)
);

ALTER TABLE notifications
  ADD COLUMN related_student_id char(36) NULL,
  ADD COLUMN resolved_at timestamp NULL,
  ADD KEY idx_advisor_request_student (related_student_id, notification_type, resolved_at),
  ADD CONSTRAINT fk_notification_related_student FOREIGN KEY (related_student_id) REFERENCES students(user_id) ON DELETE SET NULL;
