package vn.edu.fpt.faaps.academic.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.infrastructure.config.LocalDatabaseSeeder;

class LocalDatabaseSeederTest {
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = ApiReportFixesTestSchema.createJdbc();
    }

    @Test
    void localDatabaseSeederTracksEmptyDatabaseAndSeedsIdempotently() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS advisors (
                    user_id VARCHAR(36) PRIMARY KEY, advisor_code VARCHAR(50),
                    specialization TEXT, max_advisees INT DEFAULT 30, status VARCHAR(20) DEFAULT 'ACTIVE'
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS course_categories (
                    id VARCHAR(36) PRIMARY KEY, code VARCHAR(50) UNIQUE NOT NULL,
                    name VARCHAR(100) NOT NULL, description TEXT
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS courses (
                    id VARCHAR(36) PRIMARY KEY, code VARCHAR(50) UNIQUE NOT NULL, name VARCHAR(255) NOT NULL,
                    credits INT NOT NULL, category_id VARCHAR(36) NOT NULL, description TEXT,
                    objectives TEXT, learning_outcomes TEXT, material_link VARCHAR(500),
                    status VARCHAR(20) DEFAULT 'ACTIVE'
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS course_prerequisites (
                    id VARCHAR(36) PRIMARY KEY, course_id VARCHAR(36) NOT NULL,
                    prerequisite_course_id VARCHAR(36) NOT NULL, group_no INT DEFAULT 1,
                    type VARCHAR(20) DEFAULT 'PREREQUISITE',
                    effective_from_curriculum_version_id VARCHAR(36),
                    replaced_by_reason VARCHAR(255)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS curriculum_courses (
                    id VARCHAR(36) PRIMARY KEY, curriculum_version_id VARCHAR(36) NOT NULL,
                    course_id VARCHAR(36) NOT NULL, semester_number INT NOT NULL,
                    is_mandatory BOOLEAN DEFAULT TRUE
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS academic_policies (
                    id VARCHAR(36) PRIMARY KEY, curriculum_version_id VARCHAR(36) NOT NULL,
                    policy_type VARCHAR(30) NOT NULL, condition_expression TEXT, restriction_expression TEXT,
                    approval_required BOOLEAN DEFAULT FALSE, priority INT DEFAULT 0, description TEXT,
                    is_active BOOLEAN DEFAULT TRUE
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS student_advisor_assignments (
                    id VARCHAR(36) PRIMARY KEY, student_id VARCHAR(36) NOT NULL,
                    advisor_id VARCHAR(36) NOT NULL, group_name VARCHAR(100), assigned_date DATE,
                    is_primary BOOLEAN DEFAULT TRUE, status VARCHAR(20) DEFAULT 'ACTIVE'
                )
                """);

        var seeder = new LocalDatabaseSeeder(jdbc, true);

        var firstRun = seeder.trackAndSeedIfEmpty(false);
        assertTrue(firstRun.seeded());
        assertEquals(0, firstRun.beforeCounts().get("majors"));
        assertTrue(firstRun.afterCounts().get("majors") >= 3);
        assertTrue(firstRun.afterCounts().get("academic_policies") >= 3);
        // The seven FLM knowledge blocks must exist, otherwise a FLM export
        // cannot be imported ("Category \"PREP\" does not exist.").
        assertEquals(8, firstRun.afterCounts().get("course_categories"));
        assertEquals(8, jdbc.queryForObject(
                "SELECT COUNT(*) FROM course_categories WHERE code IN "
                        + "('PREP', 'GEN', 'FOUND', 'CORE', 'SPEC', 'OJT', 'CAP', 'MATH')",
                Integer.class));

        // A clean start creates no sample data: the Admin imports the real
        // catalogue and creates the accounts.
        assertEquals(0, firstRun.afterCounts().get("courses"));
        assertEquals(0, firstRun.afterCounts().get("advisors"));
        assertEquals(0, firstRun.afterCounts().get("students"));
        assertEquals(0, firstRun.afterCounts().get("curriculum_courses"));
        assertEquals(0, firstRun.afterCounts().get("student_advisor_assignments"));
        // Only the Admin account exists.
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM users u JOIN roles r ON r.id = u.role_id WHERE r.code = 'ADMIN'",
                Integer.class));

        var secondRun = seeder.trackAndSeedIfEmpty(false);
        assertFalse(secondRun.seeded());
        assertEquals(firstRun.afterCounts().get("courses"), secondRun.afterCounts().get("courses"));
        assertEquals(firstRun.afterCounts().get("course_categories"),
                secondRun.afterCounts().get("course_categories"));
    }
}
