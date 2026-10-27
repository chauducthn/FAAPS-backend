package vn.edu.fpt.faaps.dashboard.controller;

import vn.edu.fpt.faaps.dashboard.controller.AdminOverviewController;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AdminOverviewControllerTest {
    private JdbcTemplate jdbc;
    private AdminOverviewController controller;
    private String majorId;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:overview-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE roles (id VARCHAR(36), code VARCHAR(20))");
        jdbc.execute("CREATE TABLE users (id VARCHAR(36), role_id VARCHAR(36), created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE majors (id VARCHAR(36), code VARCHAR(20), name VARCHAR(100))");
        jdbc.execute("CREATE TABLE curriculum_versions (id VARCHAR(36), major_id VARCHAR(36), version VARCHAR(20))");
        jdbc.execute("CREATE TABLE students (user_id VARCHAR(36), major_id VARCHAR(36))");
        jdbc.execute("INSERT INTO roles VALUES ('student-role', 'STUDENT'), ('advisor-role', 'ADVISOR')");
        majorId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO majors VALUES (?, 'SE', 'Software Engineering')", majorId);
        jdbc.update("INSERT INTO curriculum_versions VALUES (?, ?, 'K18')", UUID.randomUUID().toString(), majorId);
        jdbc.update("INSERT INTO curriculum_versions VALUES (?, ?, 'K18')", UUID.randomUUID().toString(), majorId);
        jdbc.execute("INSERT INTO users VALUES ('student-1', 'student-role', TIMESTAMP '2026-10-01 10:00:00')");
        jdbc.execute("INSERT INTO users VALUES ('advisor-1', 'advisor-role', TIMESTAMP '2026-10-01 10:00:00')");
        jdbc.update("INSERT INTO students VALUES ('student-1', ?)", majorId);
        controller = new AdminOverviewController(jdbc);
    }

    @Test
    void dashboardCountsCurriculumVersionsRatherThanCohortLabels() {
        assertEquals(1L, controller.accountCounts().get("students"));
        assertEquals(1L, controller.accountCounts().get("advisors"));
        assertEquals(2L, controller.accountCounts().get("cohorts"));
        assertEquals("SE", controller.majors().getFirst().code());
    }

    @Test
    void rejectsInvalidDashboardMonthRangesBeforeQuerying() {
        assertThrows(IllegalArgumentException.class, () -> controller.newStudents("2026-13", "2026-10", null));
        assertThrows(IllegalArgumentException.class, () -> controller.newStudents("2026-11", "2026-10", null));
        assertThrows(IllegalArgumentException.class, () -> controller.newStudents("2020-01", "2026-10", null));
    }
}

