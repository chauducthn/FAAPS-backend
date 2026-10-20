CREATE TABLE IF NOT EXISTS admin_handovers (
  id char(36) PRIMARY KEY DEFAULT (UUID()),
  from_admin_id char(36) NOT NULL,
  to_admin_id char(36) NOT NULL,
  handover_date date NOT NULL,
  notes text,
  recorded_by_user_id char(36) NOT NULL,
  created_at timestamp DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT admin_handovers_distinct_admins CHECK (from_admin_id <> to_admin_id),
  FOREIGN KEY (from_admin_id) REFERENCES users(id),
  FOREIGN KEY (to_admin_id) REFERENCES users(id),
  FOREIGN KEY (recorded_by_user_id) REFERENCES users(id)
);

ALTER TABLE advisors ADD COLUMN work_days varchar(20) NULL;
ALTER TABLE advisors ADD COLUMN assignment_start_date date NULL;
