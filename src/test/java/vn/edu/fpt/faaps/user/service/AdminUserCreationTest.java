package vn.edu.fpt.faaps.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.CreateAccount;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.Detail;

class AdminUserCreationTest {
    private JdbcTemplate jdbc;
    private AdminUserService users;

    @BeforeEach
    void setUp() {
        AdminUserTestSupport.Fixture fixture = AdminUserTestSupport.createFixture();
        jdbc = fixture.jdbc();
        users = fixture.users();
    }

    @Test
    void createsStudentAccountAndAcademicProfileAtomically() {
        UUID majorId = UUID.randomUUID();
        UUID curriculumId = UUID.randomUUID();
        UUID enrollmentSemesterId = UUID.randomUUID();
        UUID graduationSemesterId = UUID.randomUUID();
        jdbc.update("INSERT INTO majors (id, code, name, status) VALUES (?, 'SE', 'Software Engineering', 'ACTIVE')", majorId.toString());
        jdbc.update("INSERT INTO curriculum_versions (id, major_id, version, name, status) VALUES (?, ?, 'K20', 'SE K20', 'ACTIVE')",
                curriculumId.toString(), majorId.toString());
        jdbc.update("INSERT INTO semesters (id, code, name, start_date, end_date) VALUES (?, 'SP2026', 'Spring 2026', ?, ?)",
                enrollmentSemesterId.toString(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 30));
        jdbc.update("INSERT INTO semesters (id, code, name, start_date, end_date) VALUES (?, 'SP2028', 'Spring 2028', ?, ?)",
                graduationSemesterId.toString(), LocalDate.of(2028, 1, 1), LocalDate.of(2028, 4, 30));
        for (int term = 1; term <= 7; term++) {
            jdbc.update("INSERT INTO curriculum_courses (id, curriculum_version_id, semester_number) VALUES (?, ?, ?)",
                    UUID.randomUUID().toString(), curriculumId.toString(), term);
        }

        Detail created = users.create(new CreateAccount(
                "STUDENT", "new.student@example.com", "New Student", "0900000000",
                "se200001", curriculumId, enrollmentSemesterId, null, 1, null));

        assertEquals("STUDENT", created.role());
        assertEquals("SE200001", created.userId());
        assertEquals(majorId.toString(), created.majorId());
        assertEquals(curriculumId.toString(), created.curriculumVersionId());
        assertEquals(graduationSemesterId.toString(), created.expectedGraduationSemesterId());
        assertEquals("LOCAL", jdbc.queryForObject("SELECT auth_provider FROM users WHERE id = ?",
                String.class, created.id().toString()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM students WHERE user_id = ?",
                Integer.class, created.id().toString()));
    }

    @Test
    void createsStudentWithAdminSelectedGraduationSemester() {
        UUID majorId = UUID.randomUUID();
        UUID curriculumId = UUID.randomUUID();
        UUID enrollmentSemesterId = UUID.randomUUID();
        UUID adminChosenGradSemId = UUID.randomUUID();
        jdbc.update("INSERT INTO majors (id, code, name, status) VALUES (?, 'SE', 'Software Engineering', 'ACTIVE')", majorId.toString());
        jdbc.update("INSERT INTO curriculum_versions (id, major_id, version, name, status) VALUES (?, ?, 'K20', 'SE K20', 'ACTIVE')",
                curriculumId.toString(), majorId.toString());
        jdbc.update("INSERT INTO semesters (id, code, name, start_date, end_date) VALUES (?, 'SP2026', 'Spring 2026', ?, ?)",
                enrollmentSemesterId.toString(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 30));
        jdbc.update("INSERT INTO semesters (id, code, name, start_date, end_date) VALUES (?, 'FA2029', 'Fall 2029', ?, ?)",
                adminChosenGradSemId.toString(), LocalDate.of(2029, 9, 1), LocalDate.of(2029, 12, 31));

        Detail created = users.create(new CreateAccount(
                "STUDENT", "chosen.student@example.com", "Chosen Student", "0900000001",
                "se200002", curriculumId, enrollmentSemesterId, adminChosenGradSemId, 1, null));

        assertEquals(adminChosenGradSemId.toString(), created.expectedGraduationSemesterId());
    }

    @Test
    void rejectsInvalidStudentCodeFormat() {
        UUID curriculumId = UUID.randomUUID();
        UUID enrollmentSemesterId = UUID.randomUUID();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> users.create(new CreateAccount("STUDENT", "test.qa@example.com", "QA Student",
                        "0900000000", "ABC123", curriculumId, enrollmentSemesterId, null, 1, null)));
        assertEquals("studentCode must start with campus (Q/D/H/S/C) + group (E/S/A) + cohort 01-22 + 4 digits (e.g. SE182965, SS209938)",
                ex.getMessage());
        for (String code : new String[] { "SE000001", "SE230001", "SE99001", "S1190001" }) {
            assertThrows(IllegalArgumentException.class, () -> users.create(new CreateAccount("STUDENT",
                    "test.qa@example.com", "QA Student", "0900000000", code,
                    curriculumId, enrollmentSemesterId, null, 1, null)), code);
        }
    }

    @Test
    void rejectsAPhoneThatIsNotAVietnameseMobileNumberForEveryRole() {
        for (String phone : new String[] { "+84901234567", "0123456789", "090123456", "0901 234 567" }) {
            assertThrows(IllegalArgumentException.class, () -> users.create(new CreateAccount(
                    "ADVISOR", "adv.phone@example.com", "Phone Advisor",
                    phone, null, null, null, null, null, null)), phone);
        }
        Detail created = users.create(new CreateAccount("ADVISOR", "adv.nophone@example.com", "No Phone",
                "  ", null, null, null, null, null, null));
        assertNull(jdbc.queryForObject("SELECT phone FROM users WHERE id = ?", String.class, created.id().toString()));
    }

    @Test
    void createsAdvisorAccountWithDefaultCapacity() {
        Detail created = users.create(new CreateAccount(
                "ADVISOR", "new.advisor@example.com", "New Advisor", null,
                null, null, null, null, null, null));

        assertEquals("ADVISOR", created.role());
        assertEquals("new.advisor", created.userId());
        assertEquals("new.advisor@example.com", created.email());
        assertEquals(30, jdbc.queryForObject("SELECT max_advisees FROM advisors WHERE user_id = ?",
                Integer.class, created.id().toString()));
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM advisors WHERE user_id = ?",
                String.class, created.id().toString()));
    }

    @Test
    void createsStudentAccountWithoutCurriculumAndSemester() {
        Detail created = users.create(new CreateAccount(
                "STUDENT", "freshman@example.com", "Freshman Student", "0912345678",
                "SE180001", null, null, null, null, null));

        assertEquals("STUDENT", created.role());
        assertEquals("freshman@example.com", created.email());
        assertEquals("SE180001", created.userId());
        assertEquals("SE180001", jdbc.queryForObject("SELECT student_code FROM students WHERE user_id = ?",
                String.class, created.id().toString()));
        assertNull(jdbc.queryForObject("SELECT major_id FROM students WHERE user_id = ?",
                String.class, created.id().toString()));
        assertNull(jdbc.queryForObject("SELECT curriculum_version_id FROM students WHERE user_id = ?",
                String.class, created.id().toString()));
        assertNull(jdbc.queryForObject("SELECT enrollment_semester_id FROM students WHERE user_id = ?",
                String.class, created.id().toString()));
    }

    @Test
    void rejectsInvalidIdentityFieldsBeforeCreatingAccount() {
        assertThrows(IllegalArgumentException.class, () -> users.create(new CreateAccount(
                "ADVISOR", "not-an-email", "New Advisor", null, null, null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> users.create(new CreateAccount(
                "ADVISOR", "valid@example.com", "New Advisor", "........", null, null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> users.create(new CreateAccount(
                "STUDENT", "student2@example.com", "New Student", null, "SE12345", null, null, null, null, null)));
    }
}
