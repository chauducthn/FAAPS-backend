-- Flow 2: keep the full generated study roadmap (terms, study methods, goal check) so a reload shows the plan
-- exactly as it was generated. study_plan_courses still holds one row per planned course.
-- The backend also creates this table on start-up (StudentRoadmapStore), so applying this file is optional.
CREATE TABLE IF NOT EXISTS student_roadmap_snapshots (
    study_plan_id CHAR(36) PRIMARY KEY,
    student_id CHAR(36) NOT NULL,
    payload LONGTEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
