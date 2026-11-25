package vn.edu.fpt.faaps.academic.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.GenerateRoadmapDto;
import vn.edu.fpt.faaps.ai.dto.StudentAiDtos.StudyRoadmapDto;
import vn.edu.fpt.faaps.ai.service.StudentAiService;
import vn.edu.fpt.faaps.auth.service.AuthPasswordService;

class ApiReportFixesStudentAndAuthTest {
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = ApiReportFixesTestSchema.createJdbc();
    }

    @Test
    void testS1RoadmapRegenerateAndExcludePassedCoursesAndS3CourseCodeInValidationAndS4PhoneSpaces() {
        UUID studentId = UUID.randomUUID();
        UUID cvId = UUID.randomUUID();
        UUID semOld = UUID.randomUUID();
        UUID semCurrent = UUID.randomUUID();
        UUID c1 = UUID.randomUUID();
        UUID c2 = UUID.randomUUID();
        UUID catId = UUID.randomUUID();

        jdbc.update("INSERT INTO course_categories (id, code, name) VALUES (?, 'CORE', 'Core')", catId.toString());
        jdbc.update("INSERT INTO semesters (id, code, name, start_date, end_date, is_current) "
                + "VALUES (?, 'SP26', 'Spring 2026', '2026-01-01', '2026-04-30', FALSE)", semOld.toString());
        jdbc.update("INSERT INTO semesters (id, code, name, start_date, end_date, is_current) "
                + "VALUES (?, 'FA26', 'Fall 2026', '2026-09-01', '2026-12-31', TRUE)", semCurrent.toString());
        jdbc.update("INSERT INTO users (id, email, full_name, phone) "
                + "VALUES (?, 'student.demo@fpt.edu.vn', 'Demo Student', '0912 345 678')", studentId.toString());
        jdbc.update("INSERT INTO students (user_id, student_code, curriculum_version_id) VALUES (?, 'SE190001', ?)",
                studentId.toString(), cvId.toString());

        jdbc.update("INSERT INTO courses (id, code, name, credits, category_id) VALUES (?, 'PRF192', 'Programming Fundamentals', 3, ?)",
                c1.toString(), catId.toString());
        jdbc.update("INSERT INTO courses (id, code, name, credits, category_id) VALUES (?, 'PRO192', 'OOP', 3, ?)",
                c2.toString(), catId.toString());
        jdbc.update("INSERT INTO course_prerequisites (id, course_id, prerequisite_course_id, group_no, type) "
                + "VALUES (?, ?, ?, 1, 'PREREQUISITE')", UUID.randomUUID().toString(), c2.toString(), c1.toString());
        jdbc.update("INSERT INTO curriculum_courses (id, curriculum_version_id, course_id, semester_number, is_mandatory) "
                + "VALUES (?, ?, ?, 1, TRUE), (?, ?, ?, 2, TRUE)",
                UUID.randomUUID().toString(), cvId.toString(), c1.toString(),
                UUID.randomUUID().toString(), cvId.toString(), c2.toString());

        CourseLearningPlanService learningPlans = new CourseLearningPlanService(jdbc);
        StudentPlanService planService = new StudentPlanService(jdbc, learningPlans);
        var savedPlan = planService.saveCurriculum(studentId, semCurrent, "REGULAR", List.of(c2));
        assertEquals(1, savedPlan.issues().size());
        assertTrue(savedPlan.issues().get(0).contains("PRO192"));
        assertTrue(savedPlan.issues().get(0).contains("PRF192"));
        assertFalse(savedPlan.issues().get(0).contains(c2.toString()));

        jdbc.update("""
                INSERT INTO student_course_records (id, student_id, course_id, semester_id, final_score, status, credits_earned)
                VALUES (?, ?, ?, ?, 8.5, 'PASSED', 3)
                """, UUID.randomUUID().toString(), studentId.toString(), c1.toString(), semOld.toString());

        StudentAiService aiService = new StudentAiService(jdbc, null);
        StudyRoadmapDto firstRoadmap = aiService.generateRoadmap(studentId, new GenerateRoadmapDto("On time", 20));
        StudyRoadmapDto secondRoadmap = aiService.generateRoadmap(studentId,
                new GenerateRoadmapDto("Fast track", 20, semCurrent.toString(), "JOB", 8.5));
        assertNotNull(firstRoadmap.id());
        assertNotNull(secondRoadmap.id());
        assertEquals(3, secondRoadmap.totalCredits());
        assertTrue(secondRoadmap.summary().contains("Orientation: JOB"));
        assertTrue(secondRoadmap.summary().contains("Target GPA: 8.50"));
        assertTrue(secondRoadmap.summary().contains("FA26"));
        assertEquals(semCurrent.toString(),
                jdbc.queryForObject("SELECT semester_id FROM study_plans WHERE id = ?", String.class, secondRoadmap.id()));

        StudentAcademicService academicService = new StudentAcademicService(jdbc);
        assertEquals("0912345678", academicService.profile(studentId).phone());
        var updatedProfile = academicService.updateProfile(studentId, "Demo Student Updated", "0912 345 678");
        assertEquals("0912345678", updatedProfile.phone());
        assertEquals(2, academicService.coursesPaged("", 0, 20).totalElements());
    }

    @Test
    void testForgotResetAndChangePasswordFlows() throws Exception {
        AuthPasswordService passwordService = new AuthPasswordService(jdbc);
        UUID userId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, full_name, status, auth_provider)
                VALUES (?, 'student.demo@fpt.edu.vn', 'Student Demo', 'ACTIVE', 'LOCAL')
                """, userId.toString());

        var forgot = passwordService.forgotPassword("student.demo@fpt.edu.vn", "127.0.0.1");
        assertTrue(forgot.success());
        assertNotNull(forgot.resetToken());

        passwordService.resetPassword(forgot.resetToken(), "NewPass123");
        assertThrows(IllegalArgumentException.class,
                () -> passwordService.resetPassword(forgot.resetToken(), "AnotherPass123"));

        assertThrows(IllegalArgumentException.class,
                () -> passwordService.changePassword(userId, "WrongCurrent123", "ChangedPass456"));
        passwordService.changePassword(userId, "NewPass123", "ChangedPass456");

        String storedTokenInDb = jdbc.queryForObject(
                "SELECT token FROM password_reset_tokens WHERE user_id = ? LIMIT 1",
                String.class, userId.toString());
        assertNotEquals(forgot.resetToken(), storedTokenInDb);
        String json = new ObjectMapper().writeValueAsString(forgot);
        assertFalse(json.contains(forgot.resetToken()), "HTTP JSON response must never contain resetToken");

        passwordService.forgotPassword("student.demo@fpt.edu.vn", "127.0.0.1");
        passwordService.forgotPassword("student.demo@fpt.edu.vn", "127.0.0.1");
        assertThrows(ResponseStatusException.class,
                () -> passwordService.forgotPassword("student.demo@fpt.edu.vn", "127.0.0.1"));
    }
}
