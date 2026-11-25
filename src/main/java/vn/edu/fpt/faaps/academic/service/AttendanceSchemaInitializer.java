package vn.edu.fpt.faaps.academic.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

final class AttendanceSchemaInitializer {
    private static final Logger log = LoggerFactory.getLogger(AttendanceSchemaInitializer.class);

    private final JdbcTemplate jdbc;
    private volatile boolean schemaEnsured = false;

    AttendanceSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void ensureSchema() {
        if (schemaEnsured) {
            return;
        }
        synchronized (this) {
            if (schemaEnsured) {
                return;
            }
            try {
                createTables();
                seedDefaultConfig();
                schemaEnsured = true;
            } catch (Exception ex) {
                log.debug("Skipping attendance DDL initialization: {}", ex.getMessage());
            }
        }
    }

    private void createTables() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS attendance_default_config (
                    singleton_id INT PRIMARY KEY,
                    total_sessions INT NOT NULL DEFAULT 20,
                    min_attendance_percent INT NOT NULL DEFAULT 80,
                    warn_absent_sessions INT NOT NULL DEFAULT 3,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS course_attendance_configs (
                    course_id CHAR(36) PRIMARY KEY,
                    total_sessions INT NOT NULL,
                    min_attendance_percent INT NOT NULL,
                    warn_absent_sessions INT NOT NULL,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS attendance_imports (
                    id CHAR(36) PRIMARY KEY,
                    file_name VARCHAR(255) NOT NULL,
                    status VARCHAR(20) NOT NULL DEFAULT 'PREVIEW',
                    row_count INT NOT NULL DEFAULT 0,
                    valid_rows INT NOT NULL DEFAULT 0,
                    error_rows INT NOT NULL DEFAULT 0,
                    warning_count INT NOT NULL DEFAULT 0,
                    fail_count INT NOT NULL DEFAULT 0,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    confirmed_at TIMESTAMP NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS attendance_import_rows (
                    id CHAR(36) PRIMARY KEY,
                    import_id CHAR(36) NOT NULL,
                    line_number INT NOT NULL,
                    student_code VARCHAR(50),
                    semester_code VARCHAR(50),
                    course_code VARCHAR(50),
                    total_sessions INT,
                    absent_sessions INT,
                    validation_error TEXT,
                    result VARCHAR(20)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS student_course_attendance (
                    id CHAR(36) PRIMARY KEY,
                    student_id CHAR(36) NOT NULL,
                    course_id CHAR(36) NOT NULL,
                    semester_id CHAR(36) NOT NULL,
                    total_sessions INT NOT NULL,
                    absent_sessions INT NOT NULL,
                    absent_rate DECIMAL(5,2) NOT NULL,
                    result VARCHAR(20) NOT NULL,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE (student_id, course_id, semester_id)
                )
                """);
    }

    private void seedDefaultConfig() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_default_config WHERE singleton_id = 1", Integer.class);
        if (count == null || count == 0) {
            jdbc.update("""
                    INSERT INTO attendance_default_config
                    (singleton_id, total_sessions, min_attendance_percent, warn_absent_sessions, updated_at)
                    VALUES (1, 20, 80, 3, CURRENT_TIMESTAMP)
                    """);
        }
    }
}
