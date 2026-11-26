package vn.edu.fpt.faaps.academic.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

class StudentRegistrationServiceTest {
    private static final UUID CURRICULUM = UUID.fromString("c0000000-0000-0000-0000-000000000001");
    private static final LocalDate DOB = LocalDate.of(2005, 3, 14);
    private static final String PASSWORD = "Secret@123";

    private JdbcTemplate jdbc;
    private StudentRegistrationService service;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:registration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE roles (id VARCHAR(36) PRIMARY KEY, code VARCHAR(20))");
        jdbc.execute("CREATE TABLE users (id VARCHAR(36) PRIMARY KEY, role_id VARCHAR(36), "
                + "email VARCHAR(255) UNIQUE NOT NULL, password_hash VARCHAR(255), auth_provider VARCHAR(20), "
                + "full_name VARCHAR(255), phone VARCHAR(20), date_of_birth DATE, status VARCHAR(20), "
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
        jdbc.update("INSERT INTO roles VALUES ('r-student', 'STUDENT')");
        jdbc.update("INSERT INTO majors VALUES ('m1', 'SE', 'Software Engineering', 'ACTIVE')");
        jdbc.update("INSERT INTO curriculum_versions VALUES (?, 'm1', 'K19', 'SE K19', 's1', 'ACTIVE')", CURRICULUM.toString());
        service = new StudentRegistrationService(jdbc, encoder, new StudentOnboardingService(jdbc));
    }

    private UUID register(String email, String password, String code) {
        return service.register("Nguyen Van A", email, password, DOB, code, CURRICULUM, "0901234567");
    }

    private UUID registerWithoutMajor(String email, String password, String code) {
        return service.register("Nguyen Van A", email, password, DOB, code, null, "0901234567");
    }

    private void assertBadRequest(org.junit.jupiter.api.function.Executable call, String message) {
        var error = assertThrows(ResponseStatusException.class, call, message);
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode(), message);
    }

    @Test
    void createsAnActiveStudentWithAHashedPasswordAndTheProfile() {
        UUID id = register(" Student@FPT.edu.vn ", PASSWORD, "se193012");

        var user = jdbc.queryForMap("SELECT * FROM users WHERE id = ?", id.toString());
        assertEquals("student@fpt.edu.vn", user.get("EMAIL"));
        assertEquals("LOCAL", user.get("AUTH_PROVIDER"));
        assertEquals("ACTIVE", user.get("STATUS"));
        assertEquals("r-student", user.get("ROLE_ID"));
        assertEquals("0901234567", user.get("PHONE"));
        assertNotEquals(PASSWORD, user.get("PASSWORD_HASH"));
        assertTrue(encoder.matches(PASSWORD, (String) user.get("PASSWORD_HASH")));
        assertEquals(java.sql.Date.valueOf(DOB), user.get("DATE_OF_BIRTH"));
        var student = jdbc.queryForMap("SELECT * FROM students WHERE user_id = ?", id.toString());
        assertEquals("SE193012", student.get("STUDENT_CODE"));
        assertEquals("m1", student.get("MAJOR_ID"));
    }

    @Test
    void withoutACurriculumAccountAndStudentProfileResolveFromValidMajorCohortPair() {
        UUID id = registerWithoutMajor("a@fpt.edu.vn", PASSWORD, "SE190001");

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, id.toString()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM students WHERE user_id = ?", Integer.class, id.toString()));
        var student = jdbc.queryForMap("SELECT * FROM students WHERE user_id = ?", id.toString());
        assertEquals("SE190001", student.get("STUDENT_CODE"));
        assertEquals("m1", student.get("MAJOR_ID"));
        assertEquals(CURRICULUM.toString(), student.get("CURRICULUM_VERSION_ID"));
    }

    @Test
    void withoutACurriculumAStudentIdThatIsTakenIsStillRefused() {
        register("a@fpt.edu.vn", PASSWORD, "SE190001");
        var error = assertThrows(ResponseStatusException.class,
                () -> registerWithoutMajor("b@fpt.edu.vn", PASSWORD, "SE190001"));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class));
    }

    @Test
    void rejectsADuplicateEmailEvenWithDifferentCase() {
        register("a@fpt.edu.vn", PASSWORD, "SE193012");
        var error = assertThrows(ResponseStatusException.class, () -> register("A@FPT.EDU.VN", PASSWORD, "SE193013"));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
    }

    @Test
    void rejectsAStudentIdThatIsAlreadyRegistered() {
        register("a@fpt.edu.vn", PASSWORD, "SE193012");
        var error = assertThrows(ResponseStatusException.class, () -> register("b@fpt.edu.vn", PASSWORD, "SE193012"));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
    }

    @Test
    void rejectsPasswordsThatAreMissingACharacterClass() {
        for (String weak : new String[] {"short1", "onlyletters", "12345678", "Secret123", "secret@123", "SECRET@123",
                "Secret@abc", "Sec@1", null}) {
            assertBadRequest(() -> register("c@fpt.edu.vn", weak, "SE173012"), "password " + weak);
        }
    }

    @Test
    void rejectsPasswordsContainingTheStudentIdNameOrEmail() {
        for (String personal : new String[] {"Xx@se173012", "Xx@173012!", "Nguyen@2024", "Hello@NGUYEN1",
                "Student@2024", "Zz@1234van"}) {
            assertBadRequest(() -> register("student@fpt.edu.vn", personal, "SE173012"), "password " + personal);
        }
        assertTrue(StudentRegistrationService.containsPersonalInfo("Nguyễn@2024", "Nguyễn Văn An", "x@y.vn", "SE190001"));
        assertFalse(StudentRegistrationService.containsPersonalInfo("Tr0ub@dor&3", "Nguyen Van An", "x@y.vn", "SE190001"));
    }

    @Test
    void rejectsABadEmailDateStudentIdOrPhone() {
        assertBadRequest(() -> register("not-an-email", PASSWORD, "SE173012"), "email");
        assertBadRequest(() -> service.register("A B", "d@fpt.edu.vn", PASSWORD, LocalDate.now().plusDays(1),
                "SE173012", CURRICULUM, "0901234567"), "future birth date");
        assertBadRequest(() -> service.register("A B", "d@fpt.edu.vn", PASSWORD, LocalDate.now().minusYears(12),
                "SE173012", CURRICULUM, "0901234567"), "too young");
        for (String code : new String[] {"bad", "SE000001", "SE230001", "SE99001", "S1190001"}) {
            assertBadRequest(() -> register("d@fpt.edu.vn", PASSWORD, code), "student id " + code);
        }
        for (String phone : new String[] {null, "", "  ", "+84901234567", "0123456789", "090123456", "0901 234 567"}) {
            assertBadRequest(() -> service.register("A B", "d@fpt.edu.vn", PASSWORD, DOB, "SE173012", CURRICULUM, phone),
                    "phone " + phone);
        }
    }
}
