package vn.edu.fpt.faaps.academic.service;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

final class ApiReportFixesTestSchema {

    private ApiReportFixesTestSchema() {
    }

    static JdbcTemplate createJdbc() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:api-report-fixes-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        createCoreTables(jdbc);
        createAcademicAndAiTables(jdbc);
        return jdbc;
    }

    private static void createCoreTables(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE roles (id VARCHAR(36) PRIMARY KEY, code VARCHAR(20) NOT NULL)");
        jdbc.execute("""
                CREATE TABLE users (
                    id VARCHAR(36) PRIMARY KEY, role_id VARCHAR(36), email VARCHAR(255) UNIQUE NOT NULL,
                    password_hash VARCHAR(255), auth_provider VARCHAR(20), external_id VARCHAR(255),
                    full_name VARCHAR(255) NOT NULL, phone VARCHAR(20), date_of_birth DATE,
                    status VARCHAR(20) DEFAULT 'ACTIVE', failed_login_attempts INT DEFAULT 0,
                    locked_until TIMESTAMP, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE password_reset_tokens (
                    id VARCHAR(36) PRIMARY KEY, user_id VARCHAR(36) NOT NULL,
                    token VARCHAR(255) UNIQUE NOT NULL, expires_at TIMESTAMP NOT NULL,
                    used_at TIMESTAMP, ip_address VARCHAR(45), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE majors (
                    id VARCHAR(36) PRIMARY KEY, code VARCHAR(50) UNIQUE NOT NULL,
                    name VARCHAR(255) NOT NULL, description TEXT, total_credits INT NOT NULL,
                    duration_years DECIMAL(3,1) NOT NULL, status VARCHAR(20) DEFAULT 'ACTIVE',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE semesters (
                    id VARCHAR(36) PRIMARY KEY, code VARCHAR(50) UNIQUE NOT NULL, name VARCHAR(100) NOT NULL,
                    start_date DATE NOT NULL, end_date DATE NOT NULL, is_current BOOLEAN DEFAULT FALSE
                )
                """);
        jdbc.execute("""
                CREATE TABLE curriculum_versions (
                    id VARCHAR(36) PRIMARY KEY, major_id VARCHAR(36) NOT NULL, version VARCHAR(50) NOT NULL,
                    name VARCHAR(255) NOT NULL, effective_from_semester_id VARCHAR(36),
                    is_current BOOLEAN DEFAULT TRUE, status VARCHAR(20) DEFAULT 'ACTIVE',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE students (
                    user_id VARCHAR(36) PRIMARY KEY, student_code VARCHAR(50) UNIQUE, major_id VARCHAR(36),
                    curriculum_version_id VARCHAR(36), enrollment_semester_id VARCHAR(36),
                    expected_graduation_semester_id VARCHAR(36), current_semester INT DEFAULT 1,
                    current_gpa DECIMAL(4,2) DEFAULT 0, total_credits_earned INT DEFAULT 0,
                    academic_status VARCHAR(20) DEFAULT 'ACTIVE',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE notifications (
                    id VARCHAR(36) PRIMARY KEY, recipient_user_id VARCHAR(36) NOT NULL,
                    notification_type VARCHAR(20) DEFAULT 'INFO', title VARCHAR(255) NOT NULL,
                    message TEXT NOT NULL, action_url VARCHAR(500), related_student_id VARCHAR(36),
                    resolved_at TIMESTAMP, is_read BOOLEAN DEFAULT FALSE, read_at TIMESTAMP,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }

    private static void createAcademicAndAiTables(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE course_categories (id VARCHAR(36) PRIMARY KEY, "
                + "code VARCHAR(50) UNIQUE NOT NULL, name VARCHAR(100) NOT NULL, description TEXT)");
        jdbc.execute("""
                CREATE TABLE courses (
                    id VARCHAR(36) PRIMARY KEY, code VARCHAR(50) UNIQUE NOT NULL, name VARCHAR(255) NOT NULL,
                    credits INT NOT NULL, category_id VARCHAR(36) NOT NULL, description TEXT, objectives TEXT,
                    learning_outcomes TEXT, status VARCHAR(20) DEFAULT 'ACTIVE',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE course_prerequisites (
                    id VARCHAR(36) PRIMARY KEY, course_id VARCHAR(36) NOT NULL,
                    prerequisite_course_id VARCHAR(36) NOT NULL, group_no INT DEFAULT 1,
                    type VARCHAR(20) DEFAULT 'PREREQUISITE', minimum_score DECIMAL(4,2),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE curriculum_courses (
                    id VARCHAR(36) PRIMARY KEY, curriculum_version_id VARCHAR(36), course_id VARCHAR(36),
                    semester_number INT DEFAULT 1, is_mandatory BOOLEAN DEFAULT TRUE, group_name VARCHAR(100)
                )
                """);
        jdbc.execute("""
                CREATE TABLE academic_policies (
                    id VARCHAR(36) PRIMARY KEY, curriculum_version_id VARCHAR(36) NOT NULL,
                    policy_type VARCHAR(30) NOT NULL, condition_expression TEXT, restriction_expression TEXT,
                    approval_required BOOLEAN DEFAULT FALSE, priority INT DEFAULT 0, description TEXT,
                    display_message TEXT, is_active BOOLEAN DEFAULT TRUE,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE ai_configurations (
                    id VARCHAR(36) PRIMARY KEY, name VARCHAR(100) UNIQUE, provider VARCHAR(20),
                    model_name VARCHAR(100), purpose VARCHAR(20), temperature DECIMAL(3,2), max_tokens INT,
                    retrieval_top_k INT, similarity_threshold DECIMAL(5,4), timeout_seconds INT,
                    daily_request_limit INT, system_prompt TEXT, fallback_message TEXT, is_active BOOLEAN,
                    updated_by_user_id VARCHAR(36), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE ai_usage_logs (
                    id VARCHAR(36) PRIMARY KEY, user_id VARCHAR(36), message_id VARCHAR(36),
                    ai_configuration_id VARCHAR(36), feature VARCHAR(20), prompt_tokens INT,
                    completion_tokens INT, total_tokens INT, latency_ms INT, status VARCHAR(20),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE advising_sessions (
                    id VARCHAR(36) PRIMARY KEY, student_id VARCHAR(36) NOT NULL, advisor_id VARCHAR(36) NOT NULL,
                    scheduled_at TIMESTAMP NOT NULL, ended_at TIMESTAMP, location VARCHAR(255),
                    agenda TEXT, status VARCHAR(20) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE student_course_records (
                    id VARCHAR(36) PRIMARY KEY, student_id VARCHAR(36) NOT NULL, course_id VARCHAR(36) NOT NULL,
                    semester_id VARCHAR(36) NOT NULL, final_score DECIMAL(4,2),
                    status VARCHAR(20) DEFAULT 'IN_PROGRESS', attempt_number INT DEFAULT 1, credits_earned INT DEFAULT 0
                )
                """);
        jdbc.execute("""
                CREATE TABLE study_plans (
                    id VARCHAR(36) PRIMARY KEY, student_id VARCHAR(36) NOT NULL, semester_id VARCHAR(36) NOT NULL,
                    version INT NOT NULL, is_current BOOLEAN DEFAULT TRUE, previous_plan_id VARCHAR(36),
                    plan_type VARCHAR(20) DEFAULT 'REGULAR', total_credits_planned INT DEFAULT 0,
                    status VARCHAR(20) DEFAULT 'DRAFT', submitted_at TIMESTAMP, reviewed_by_user_id VARCHAR(36),
                    reviewed_at TIMESTAMP, review_notes TEXT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, UNIQUE (student_id, semester_id, version)
                )
                """);
        jdbc.execute("CREATE TABLE study_plan_courses (id VARCHAR(36) PRIMARY KEY, study_plan_id VARCHAR(36) NOT NULL, "
                + "course_id VARCHAR(36) NOT NULL, planned_credits INT DEFAULT 3, "
                + "priority VARCHAR(20) DEFAULT 'MEDIUM', notes TEXT)");
        jdbc.execute("""
                CREATE TABLE course_recommendations (
                    id VARCHAR(36) PRIMARY KEY, student_id VARCHAR(36), course_id VARCHAR(36),
                    semester_id VARCHAR(36), recommendation_source VARCHAR(20), confidence_score DECIMAL(5,4),
                    reasoning TEXT, priority VARCHAR(20), status VARCHAR(20),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }
}
