package vn.edu.fpt.faaps.academic.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import vn.edu.fpt.faaps.academic.service.AdminAttendanceService.AttendanceImportHistoryItem;
import vn.edu.fpt.faaps.academic.service.AdminAttendanceService.AttendanceImportPreview;
import vn.edu.fpt.faaps.academic.service.AdminAttendanceService.AttendanceRule;
import vn.edu.fpt.faaps.academic.service.AdminAttendanceService.CourseAttendanceOverride;

class ApiReportFixesAndAttendanceTest {
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = ApiReportFixesTestSchema.createJdbc();
    }

    @Test
    void testAttendanceConfigImportAndOnChangedDeltaNotification() throws Exception {
        AdminAttendanceService attendance = new AdminAttendanceService(jdbc);

        UUID studentId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        UUID semId = UUID.randomUUID();
        UUID catId = UUID.randomUUID();
        jdbc.update("INSERT INTO course_categories (id, code, name) VALUES (?, 'CORE', 'Core')", catId.toString());
        jdbc.update("INSERT INTO users (id, email, full_name, status) VALUES (?, 'se173012@fpt.edu.vn', 'Student A', 'ACTIVE')",
                studentId.toString());
        jdbc.update("INSERT INTO students (user_id, student_code) VALUES (?, 'SE173012')", studentId.toString());
        jdbc.update("INSERT INTO courses (id, code, name, credits, category_id) VALUES (?, 'PRF192', 'Programming Fundamentals', 3, ?)",
                courseId.toString(), catId.toString());
        jdbc.update("INSERT INTO semesters (id, code, name, start_date, end_date, is_current) "
                + "VALUES (?, 'FA25', 'Fall 2025', '2025-09-01', '2025-12-31', TRUE)", semId.toString());

        AttendanceRule defaultRule = attendance.saveDefaultRule(new AttendanceRule(20, 80, 3));
        assertEquals(20, defaultRule.totalSessions());

        CourseAttendanceOverride override = attendance.saveOverride(courseId, new AttendanceRule(20, 80, 3));
        assertEquals("PRF192", override.courseCode());
        assertEquals(1, attendance.listOverrides().size());

        String csv1 = "Student ID,Semester,Course code,Total sessions,Absent sessions\nSE173012,FA25,PRF192,20,3\n";
        MockMultipartFile file1 = new MockMultipartFile(
                "file", "att1.csv", "text/csv", csv1.getBytes(StandardCharsets.UTF_8));
        AttendanceImportPreview preview1 = attendance.previewImport(file1);
        assertEquals(1, preview1.validRows());
        assertEquals(0, preview1.errorRows());
        assertEquals("WARNING", preview1.rows().get(0).result());

        AttendanceImportHistoryItem confirmed1 = attendance.confirmImport(UUID.fromString(preview1.importId()));
        assertEquals(1, confirmed1.rowCount());
        assertEquals(1, confirmed1.warningCount());

        Integer notifCountAfterFirst = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE recipient_user_id = ?", Integer.class, studentId.toString());
        assertEquals(1, notifCountAfterFirst);

        MockMultipartFile file2 = new MockMultipartFile(
                "file", "att2.csv", "text/csv", csv1.getBytes(StandardCharsets.UTF_8));
        AttendanceImportPreview preview2 = attendance.previewImport(file2);
        attendance.confirmImport(UUID.fromString(preview2.importId()));

        Integer notifCountAfterUnchanged = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE recipient_user_id = ?", Integer.class, studentId.toString());
        assertEquals(1, notifCountAfterUnchanged, "Unchanged attendance import must NOT trigger a new notification");

        String csv3 = "Student ID,Semester,Course code,Total sessions,Absent sessions\nSE173012,FA25,PRF192,20,5\n";
        MockMultipartFile file3 = new MockMultipartFile(
                "file", "att3.csv", "text/csv", csv3.getBytes(StandardCharsets.UTF_8));
        AttendanceImportPreview preview3 = attendance.previewImport(file3);
        AttendanceImportHistoryItem confirmed3 = attendance.confirmImport(UUID.fromString(preview3.importId()));
        assertEquals(1, confirmed3.failCount());

        Integer notifCountAfterIncrease = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE recipient_user_id = ?", Integer.class, studentId.toString());
        assertEquals(2, notifCountAfterIncrease, "Increased % absent over total sessions must trigger OnChanged notification");
        assertEquals(3, attendance.listImports().size());

        String csvInvalid = """
                Student ID,Semester,Course code,Total sessions,Absent sessions
                ZZNOBODY1,FA25,PRF192,20,2
                SE173012,BADSEM,PRF192,20,2
                SE173012,FA25,NOPE999,20,2
                """;
        MockMultipartFile fileInvalid = new MockMultipartFile(
                "file", "att_invalid.csv", "text/csv", csvInvalid.getBytes(StandardCharsets.UTF_8));
        AttendanceImportPreview previewInvalid = attendance.previewImport(fileInvalid);
        assertEquals(0, previewInvalid.validRows());
        assertEquals(3, previewInvalid.errorRows());
        assertTrue(previewInvalid.rows().get(0).error().contains("Student not found"));
        assertTrue(previewInvalid.rows().get(1).error().contains("Semester not found"));
        assertTrue(previewInvalid.rows().get(2).error().contains("Course not found"));
        assertThrows(IllegalArgumentException.class,
                () -> attendance.confirmImport(UUID.fromString(previewInvalid.importId())));
    }
}
