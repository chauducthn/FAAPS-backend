package vn.edu.fpt.faaps.architecture.integration;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

final class AdminApiConventionSchema {

    record SeedIds(UUID semesterId, UUID curriculumVersionId, UUID categoryId) {
    }

    private AdminApiConventionSchema() {
    }

    static SeedIds resetAndSeed(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("DROP ALL OBJECTS");
        jdbcTemplate.execute("""
                CREATE TABLE roles (
                    id VARCHAR(36) PRIMARY KEY, code VARCHAR(50) UNIQUE NOT NULL, name VARCHAR(100) NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE users (
                    id VARCHAR(36) PRIMARY KEY, role_id VARCHAR(36), email VARCHAR(255) UNIQUE NOT NULL,
                    password_hash VARCHAR(255), auth_provider VARCHAR(20), external_id VARCHAR(255),
                    email_verified_at TIMESTAMP, full_name VARCHAR(255) NOT NULL,
                    phone VARCHAR(20), status VARCHAR(20) DEFAULT 'ACTIVE'
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE refresh_tokens (
                    id VARCHAR(36) PRIMARY KEY, user_id VARCHAR(36) NOT NULL,
                    token VARCHAR(500) UNIQUE NOT NULL, expires_at TIMESTAMP NOT NULL,
                    revoked_at TIMESTAMP, ip_address VARCHAR(45), user_agent VARCHAR(500),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE majors (
                    id VARCHAR(36) PRIMARY KEY, code VARCHAR(50) UNIQUE NOT NULL,
                    name VARCHAR(255) NOT NULL, description TEXT, total_credits INT NOT NULL,
                    duration_years DECIMAL(3,1) NOT NULL, status VARCHAR(20) DEFAULT 'ACTIVE',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE semesters (
                    id VARCHAR(36) PRIMARY KEY, code VARCHAR(50) UNIQUE NOT NULL,
                    name VARCHAR(100) NOT NULL, start_date DATE NOT NULL, end_date DATE NOT NULL,
                    is_current BOOLEAN DEFAULT false,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE curriculum_versions (
                    id VARCHAR(36) PRIMARY KEY, major_id VARCHAR(36) NOT NULL,
                    version VARCHAR(50) NOT NULL, name VARCHAR(255) NOT NULL,
                    effective_from_semester_id VARCHAR(36), effective_to_semester_id VARCHAR(36),
                    is_current BOOLEAN DEFAULT false, status VARCHAR(20) DEFAULT 'ACTIVE',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE students (
                    user_id VARCHAR(36) PRIMARY KEY, student_code VARCHAR(50) UNIQUE NOT NULL,
                    major_id VARCHAR(36) NOT NULL, curriculum_version_id VARCHAR(36) NOT NULL,
                    enrollment_semester_id VARCHAR(36) NOT NULL, academic_status VARCHAR(20) DEFAULT 'ACTIVE'
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE course_categories (
                    id VARCHAR(36) PRIMARY KEY, code VARCHAR(50) UNIQUE NOT NULL,
                    name VARCHAR(100) NOT NULL, description TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE courses (
                    id VARCHAR(36) PRIMARY KEY, code VARCHAR(50) UNIQUE NOT NULL,
                    name VARCHAR(255) NOT NULL, credits INT NOT NULL, category_id VARCHAR(36) NOT NULL,
                    description TEXT, objectives TEXT, learning_outcomes TEXT,
                    status VARCHAR(20) DEFAULT 'ACTIVE',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE course_prerequisites (
                    id VARCHAR(36) PRIMARY KEY, course_id VARCHAR(36) NOT NULL,
                    prerequisite_course_id VARCHAR(36) NOT NULL, group_no INT DEFAULT 1,
                    type VARCHAR(20) DEFAULT 'PREREQUISITE', minimum_score DECIMAL(4,2),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE curriculum_courses (
                    id VARCHAR(36) PRIMARY KEY, curriculum_version_id VARCHAR(36), course_id VARCHAR(36)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE academic_policies (
                    id VARCHAR(36) PRIMARY KEY, curriculum_version_id VARCHAR(36) NOT NULL,
                    policy_type VARCHAR(30) NOT NULL, condition_expression TEXT,
                    restriction_expression TEXT, approval_required BOOLEAN DEFAULT false,
                    priority INT DEFAULT 0, description TEXT, display_message TEXT,
                    is_active BOOLEAN DEFAULT true,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

        UUID semesterId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO semesters (id, code, name, start_date, end_date) "
                + "VALUES (?, 'SP2026', 'Spring 2026', '2026-01-01', '2026-04-30')", semesterId.toString());
        UUID categoryId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO course_categories (id, code, name) VALUES (?, 'SPEC', 'Specialized Knowledge')",
                categoryId.toString());
        UUID curriculumVersionId = UUID.randomUUID();
        UUID majorSeedId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO majors (id, code, name, total_credits, duration_years) "
                + "VALUES (?, 'SE_SEED', 'Software Engineering Seed', 140, 4.0)", majorSeedId.toString());
        jdbcTemplate.update("INSERT INTO curriculum_versions (id, major_id, version, name, effective_from_semester_id) "
                + "VALUES (?, ?, 'K18', 'K18 Curriculum', ?)",
                curriculumVersionId.toString(), majorSeedId.toString(), semesterId.toString());
        return new SeedIds(semesterId, curriculumVersionId, categoryId);
    }
}
