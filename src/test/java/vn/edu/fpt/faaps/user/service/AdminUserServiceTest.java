package vn.edu.fpt.faaps.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.dashboard.controller.AdminOverviewController;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.ChangeRole;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.Detail;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.Page;
import vn.edu.fpt.faaps.user.exception.UserExceptions.Conflict;
import vn.edu.fpt.faaps.user.exception.UserExceptions.NotFound;

class AdminUserServiceTest {
    private JdbcTemplate jdbc;
    private AdminUserService users;
    private UUID adminId;
    private UUID studentId;

    @BeforeEach
    void setUp() {
        AdminUserTestSupport.Fixture fixture = AdminUserTestSupport.createFixture();
        jdbc = fixture.jdbc();
        users = fixture.users();
        adminId = fixture.adminId();
        studentId = fixture.studentId();
    }

    @Test
    void listsUsersWithoutProfilesAndFiltersByRole() {
        Page page = users.list("STUDENT", "ACTIVE", "student", null, 0, 20, "userId,asc");
        assertEquals(1, page.totalElements());
        assertEquals(studentId, page.items().get(0).id());
        assertEquals("student@example.com", page.items().get(0).userId());
        assertEquals("STUDENT", users.detail(studentId).role());
    }

    @Test
    void listsMajorsAndFiltersStudentsByMajor() {
        UUID softwareMajor = UUID.randomUUID();
        UUID aiMajor = UUID.randomUUID();
        UUID aiStudent = UUID.randomUUID();
        String studentRole = jdbc.queryForObject("SELECT id FROM roles WHERE code = 'STUDENT'", String.class);
        jdbc.update("INSERT INTO majors (id, code, name) VALUES (?, 'SE', 'Software Engineering')", softwareMajor.toString());
        jdbc.update("INSERT INTO majors (id, code, name) VALUES (?, 'AI', 'Artificial Intelligence')", aiMajor.toString());
        jdbc.update("INSERT INTO students (user_id, student_code, major_id) VALUES (?, 'SE01', ?)",
                studentId.toString(), softwareMajor.toString());
        jdbc.update("INSERT INTO users (id, role_id, email, full_name, status) VALUES (?, ?, 'ai@example.com', 'AI Student', 'ACTIVE')",
                aiStudent.toString(), studentRole);
        jdbc.update("INSERT INTO students (user_id, student_code, major_id) VALUES (?, 'AI01', ?)",
                aiStudent.toString(), aiMajor.toString());

        List<AdminOverviewController.Major> majors = new AdminOverviewController(jdbc).majors();
        assertEquals(List.of("AI", "SE"), majors.stream().map(major -> major.code()).toList());
        assertEquals(softwareMajor, majors.get(1).id());

        Page filtered = users.list("STUDENT", null, null, softwareMajor, 0, 20, "userId,asc");
        assertEquals(1, filtered.totalElements());
        assertEquals(studentId, filtered.items().get(0).id());
        assertEquals(2, users.list(null, null, null, null, 0, 20, "userId,asc").totalElements());
        assertThrows(IllegalArgumentException.class, () -> users.list("ADMIN", null, null, null, 0, 20, "userId,asc"));
    }

    @Test
    void protectsOwnAdminAccountAndChangesStudentStatus() {
        assertThrows(Conflict.class, () -> users.changeStatus(List.of(adminId), "SUSPENDED", adminId));
        assertThrows(NotFound.class, () -> users.detail(adminId));
        assertThrows(NotFound.class, () -> users.changeStatus(List.of(adminId), "SUSPENDED", UUID.randomUUID()));
        assertEquals(1, users.changeStatus(List.of(studentId), "SUSPENDED", adminId));
        assertEquals("SUSPENDED", users.detail(studentId).status());
        assertEquals(1, users.changeStatus(List.of(studentId), "ACTIVE", adminId));
    }

    @Test
    void changesStudentRoleToAdvisorAndKeepsAcademicHistory() {
        UUID majorId = UUID.randomUUID();
        UUID curriculumId = UUID.randomUUID();
        UUID semesterId = UUID.randomUUID();
        jdbc.update("INSERT INTO majors (id, code, name, status) VALUES (?, 'SE', 'Software Engineering', 'ACTIVE')", majorId.toString());
        jdbc.update("INSERT INTO semesters (id, code, name, start_date, end_date) "
                + "VALUES (?, 'FA2022', 'Fall 2022', DATE '2022-09-05', DATE '2022-12-23')", semesterId.toString());
        jdbc.update("INSERT INTO curriculum_versions (id, major_id, version, status) VALUES (?, ?, 'K17', 'ACTIVE')",
                curriculumId.toString(), majorId.toString());
        jdbc.update("INSERT INTO students (user_id, student_code, major_id, curriculum_version_id, enrollment_semester_id) "
                + "VALUES (?, 'SE173012', ?, ?, ?)",
                studentId.toString(), majorId.toString(), curriculumId.toString(), semesterId.toString());

        Detail changed = users.changeRole(studentId, new ChangeRole("ADVISOR", null, null, null, 25));
        assertEquals("ADVISOR", changed.role());
        assertEquals("student", changed.userId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM advisors WHERE user_id=?", Integer.class, studentId.toString()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM students WHERE user_id=?", Integer.class, studentId.toString()));
    }

    @Test
    void rejectsInvalidBulkStatusInputsWithoutNullPointerException() {
        assertThrows(IllegalArgumentException.class, () -> users.changeStatus(List.of(studentId), null, adminId));
        assertThrows(IllegalArgumentException.class, () -> users.changeStatus(null, "ACTIVE", adminId));
        assertThrows(IllegalArgumentException.class, () -> users.changeStatus(Arrays.asList(studentId, null), "ACTIVE", adminId));
    }

    @Test
    void listsTheMajorAndCohortOfEachStudent() {
        UUID majorId = UUID.randomUUID();
        UUID curriculumId = UUID.randomUUID();
        jdbc.update("INSERT INTO majors (id, code, name, status) VALUES (?, 'SE', 'Software Engineering', 'ACTIVE')", majorId.toString());
        jdbc.update("INSERT INTO curriculum_versions (id, major_id, version, name, status) VALUES (?, ?, 'K19', 'SE K19', 'ACTIVE')",
                curriculumId.toString(), majorId.toString());
        jdbc.update("INSERT INTO students (user_id, student_code, major_id, curriculum_version_id) VALUES (?, 'SE190001', ?, ?)",
                studentId.toString(), majorId.toString(), curriculumId.toString());

        var student = users.list("STUDENT", null, null, null, 0, 20, "userId,asc").items().get(0);
        assertEquals("SE", student.majorCode());
        assertEquals("K19", student.cohort());
    }

    @Test
    void expectedGraduationFollowsTheCurriculumLengthAndHowFarTheStudentIsBehind() {
        assertEquals("SU2029", ExpectedGraduation.code("FA2026", "FA2026", 1, 9));
        assertEquals(ExpectedGraduation.addSemesters("SP2026", 8 + 2), ExpectedGraduation.code("SP2026", "FA2026", 1, 9));
        assertEquals(ExpectedGraduation.addSemesters("SP2026", 8), ExpectedGraduation.code("SP2026", "SU2026", 5, 9));
        assertEquals("SP2027", ExpectedGraduation.addSemesters("FA2026", 1));
        assertEquals(null, ExpectedGraduation.code("K19", "FA2026", 1, 9));
        assertEquals(null, ExpectedGraduation.code("FA2026", "FA2026", 1, 0));
    }

    @Test
    void filtersStudentsByCurriculumVersionId() {
        UUID cur1 = UUID.randomUUID();
        UUID cur2 = UUID.randomUUID();
        jdbc.update("INSERT INTO students (user_id, student_code, curriculum_version_id) VALUES (?, 'SE999999', ?)",
                studentId.toString(), cur1.toString());
        assertEquals(1, users.list("STUDENT", null, null, null, cur1, 0, 10, "userId,asc").totalElements());
        assertEquals(0, users.list("STUDENT", null, null, null, cur2, 0, 10, "userId,asc").totalElements());
    }

    @Test
    void resetsPasswordSingleAndBulk() {
        AdminUserDtos.PasswordResetResponse singleResult = users.resetPassword(studentId);
        assertEquals(1, singleResult.updated());
        assertEquals("FPTUniversity@2026", singleResult.defaultPassword());
        assertEquals(1, singleResult.resetResults().size());
        assertEquals("SUCCESS", singleResult.resetResults().get(0).status());
        assertEquals("student@example.com", singleResult.resetResults().get(0).email());
        assertEquals("FPTUniversity@2026", singleResult.resetResults().get(0).newPassword());
        assertNotNull(jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, studentId.toString()));
        assertEquals("LOCAL", jdbc.queryForObject("SELECT auth_provider FROM users WHERE id = ?", String.class, studentId.toString()));

        AdminUserDtos.PasswordResetResponse bulkResult = users.bulkResetPassword(List.of(adminId, studentId));
        assertEquals(2, bulkResult.updated());
        assertEquals("FPTUniversity@2026", bulkResult.defaultPassword());
        assertEquals(2, bulkResult.resetResults().size());
        assertEquals("FPTUniversity@2026", bulkResult.resetResults().get(0).newPassword());
        assertEquals("FPTUniversity@2026", bulkResult.resetResults().get(1).newPassword());
    }
}
