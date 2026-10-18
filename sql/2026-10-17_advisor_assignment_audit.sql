ALTER TABLE student_advisor_assignments
    ADD COLUMN assigned_at DATETIME NULL AFTER assigned_date,
    ADD COLUMN assigned_by_user_id CHAR(36) NULL AFTER assigned_at;

UPDATE student_advisor_assignments
SET assigned_at = COALESCE(created_at, TIMESTAMP(assigned_date))
WHERE assigned_at IS NULL;

ALTER TABLE student_advisor_assignments
    MODIFY COLUMN assigned_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD CONSTRAINT fk_assignment_assigned_by
        FOREIGN KEY (assigned_by_user_id) REFERENCES users(id) ON DELETE SET NULL;
