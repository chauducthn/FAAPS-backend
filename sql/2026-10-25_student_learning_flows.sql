-- Apply once to the local FAAPS database before starting the Student learning flows.
-- Existing academic and administrator records are not modified.
CREATE TABLE IF NOT EXISTS student_transcript_imports (
    id CHAR(36) PRIMARY KEY,
    student_id CHAR(36) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PREVIEW',
    row_count INT NOT NULL DEFAULT 0,
    error_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_at TIMESTAMP NULL,
    INDEX idx_transcript_import_student (student_id, created_at),
    CONSTRAINT fk_transcript_import_student FOREIGN KEY (student_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS student_transcript_import_rows (
    id CHAR(36) PRIMARY KEY,
    import_id CHAR(36) NOT NULL,
    line_number INT NOT NULL,
    course_id CHAR(36) NULL,
    semester_id CHAR(36) NULL,
    course_code VARCHAR(50) NOT NULL,
    semester_code VARCHAR(50) NOT NULL,
    final_score DECIMAL(4,2) NULL,
    status VARCHAR(20) NOT NULL,
    attempt_number INT NOT NULL DEFAULT 1,
    validation_error VARCHAR(500) NULL,
    UNIQUE KEY uq_transcript_import_row (import_id, line_number),
    CONSTRAINT fk_transcript_import_row FOREIGN KEY (import_id) REFERENCES student_transcript_imports(id),
    CONSTRAINT fk_transcript_import_course FOREIGN KEY (course_id) REFERENCES courses(id),
    CONSTRAINT fk_transcript_import_semester FOREIGN KEY (semester_id) REFERENCES semesters(id)
);

CREATE TABLE IF NOT EXISTS course_learning_plans (
    id CHAR(36) PRIMARY KEY,
    student_id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    learning_goal TEXT NULL,
    version INT NOT NULL,
    previous_plan_id CHAR(36) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_course_learning_version (student_id, course_id, version),
    INDEX idx_course_learning_student (student_id, created_at),
    CONSTRAINT fk_course_learning_student FOREIGN KEY (student_id) REFERENCES users(id),
    CONSTRAINT fk_course_learning_course FOREIGN KEY (course_id) REFERENCES courses(id),
    CONSTRAINT fk_course_learning_previous FOREIGN KEY (previous_plan_id) REFERENCES course_learning_plans(id)
);

CREATE TABLE IF NOT EXISTS course_learning_tasks (
    id CHAR(36) PRIMARY KEY,
    plan_id CHAR(36) NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT NULL,
    due_date DATE NULL,
    resource_url VARCHAR(1000) NULL,
    sort_order INT NOT NULL,
    UNIQUE KEY uq_course_learning_task_order (plan_id, sort_order),
    CONSTRAINT fk_course_learning_task_plan FOREIGN KEY (plan_id) REFERENCES course_learning_plans(id)
);
