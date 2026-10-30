package vn.edu.fpt.faaps.academic.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

class StudentOnboardingServiceTest {
    private static final String ACTIVE_CURRICULUM = "c0000000-0000-0000-0000-000000000001";
    private static final String INACTIVE_CURRICULUM = "c0000000-0000-0000-0000-000000000002";
    private static final String SEMESTER = "s0000000-0000-0000-0000-000000000001";

    private static final java.time.LocalDate DOB = java.time.LocalDate.of(2005, 3, 14);

    private JdbcTemplate jdbc;
    private StudentOnboardingService service;
    private UUID userId;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:onboarding-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE users (id VARCHAR(36) PRIMARY KEY, phone VARCHAR(20), date_of_birth DATE, "
                + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE majors (id VARCHAR(36) PRIMARY KEY, code VARCHAR(20), name VARCHAR(100), "
                + "status VARCHAR(20))");
        jdbc.execute("CREATE TABLE curriculum_versions (id VARCHAR(36) PRIMARY KEY, major_id VARCHAR(36), "
                + "version VARCHAR(50), name VARCHAR(255), effective_from_semester_id VARCHAR(36), "
                + "status VARCHAR(20))");
        jdbc.execute("CREATE TABLE students (user_id VARCHAR(36) PRIMARY KEY, student_code VARCHAR(50) UNIQUE, "
                + "major_id VARCHAR(36), curriculum_version_id VARCHAR(36), enrollment_semester_id VARCHAR(36), "
                + "current_semester INT, current_gpa DECIMAL(4,2), total_credits_earned INT, "
                + "academic_status VARCHAR(20), created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbc.update("INSERT INTO majors VALUES ('m1', 'SE', 'Software Engineering', 'ACTIVE')");
        jdbc.update("INSERT INTO majors VALUES ('m2', 'OLD', 'Closed Major', 'INACTIVE')");
        jdbc.update("INSERT INTO curriculum_versions VALUES (?, 'm1', 'K19', 'SE K19', ?, 'ACTIVE')",
                ACTIVE_CURRICULUM, SEMESTER);
        jdbc.update("INSERT INTO curriculum_versions VALUES (?, 'm1', 'K10', 'SE K10', ?, 'INACTIVE')",
                INACTIVE_CURRICULUM, SEMESTER);
        jdbc.update("INSERT INTO curriculum_versions VALUES ('c3', 'm2', 'K1', 'OLD K1', ?, 'ACTIVE')", SEMESTER);
        userId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id) VALUES (?)", userId.toString());
        service = new StudentOnboardingService(jdbc);
    }

    @Test
    void offersOnlyActiveCurriculumsOfActiveMajors() {
        var choices = service.curriculumChoices();
        assertEquals(1, choices.size());
        assertEquals("SE", choices.get(0).majorCode());
        assertEquals("K19", choices.get(0).version());
    }

    @Test
    void createsTheStudentProfileFromTheChosenCurriculum() {
        service.complete(userId, " se173012 ", UUID.fromString(ACTIVE_CURRICULUM), DOB, "0901234567");

        var row = jdbc.queryForMap("SELECT * FROM students WHERE user_id = ?", userId.toString());
        assertEquals("SE173012", row.get("STUDENT_CODE"));
        assertEquals("m1", row.get("MAJOR_ID"));
        assertEquals(SEMESTER, row.get("ENROLLMENT_SEMESTER_ID"));
        assertEquals(1, row.get("CURRENT_SEMESTER"));
        assertEquals("0901234567", jdbc.queryForObject("SELECT phone FROM users WHERE id = ?", String.class,
                userId.toString()));
    }

    @Test
    void phoneIsOptional() {
        service.complete(userId, "SE173012", UUID.fromString(ACTIVE_CURRICULUM), DOB, "  ");
        assertNull(jdbc.queryForObject("SELECT phone FROM users WHERE id = ?", String.class, userId.toString()));
    }

    @Test
    void rejectsAnInvalidStudentId() {
        var error = assertThrows(ResponseStatusException.class,
                () -> service.complete(userId, "12345", UUID.fromString(ACTIVE_CURRICULUM), DOB, null));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }

    @Test
    void rejectsAnUnavailableCurriculum() {
        for (String id : new String[] {INACTIVE_CURRICULUM, UUID.randomUUID().toString()}) {
            var error = assertThrows(ResponseStatusException.class,
                    () -> service.complete(userId, "SE173012", UUID.fromString(id), DOB, null));
            assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        }
    }

    @Test
    void rejectsASecondRegistrationAndADuplicateStudentId() {
        service.complete(userId, "SE173012", UUID.fromString(ACTIVE_CURRICULUM), DOB, null);

        var again = assertThrows(ResponseStatusException.class,
                () -> service.complete(userId, "SE173013", UUID.fromString(ACTIVE_CURRICULUM), DOB, null));
        assertEquals(HttpStatus.CONFLICT, again.getStatusCode());

        UUID other = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id) VALUES (?)", other.toString());
        var duplicate = assertThrows(ResponseStatusException.class,
                () -> service.complete(other, "SE173012", UUID.fromString(ACTIVE_CURRICULUM), DOB, null));
        assertEquals(HttpStatus.CONFLICT, duplicate.getStatusCode());
    }
}
